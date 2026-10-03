#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
[[ -f .env ]] || { echo ".env is required" >&2; exit 1; }
for command in docker curl awk sort python3; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

set -a
# shellcheck disable=SC1091
source .env
set +a

API_PORT="${API_PORT:-8080}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-forestiq-dev}"
BENCHMARK_USERNAME="${BENCHMARK_USERNAME:-admin}"
BENCHMARK_PASSWORD="${BENCHMARK_PASSWORD:-forestiq}"
ORG_ID="${PERF_ORG_ID:-11111111-1111-1111-1111-111111111111}"
BASE_URL="${BASE_URL:-http://localhost:${API_PORT}}"
KEYCLOAK_URL="${KEYCLOAK_URL:-${NEXT_PUBLIC_KEYCLOAK_URL:-http://localhost:${KEYCLOAK_PORT}}}"
REQUESTS="${FINAL_GATE_REQUESTS:-120}"
CONCURRENCY="${FINAL_GATE_CONCURRENCY:-5}"
TOKEN="${TOKEN:-}"
DIRECT_GRANT_CHANGED=0

if [[ -f compose.performance.yaml ]]; then
  COMPOSE=(docker compose -f compose.yaml -f compose.performance.yaml)
else
  COMPOSE=(docker compose -f compose.yaml)
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LABEL="${BENCHMARK_LABEL:-final-gates-${STAMP}}"
OUT_DIR=".run/performance/${LABEL}"
mkdir -p "$OUT_DIR"

cleanup() {
  if [[ "$DIRECT_GRANT_CHANGED" == "1" ]]; then
    "${COMPOSE[@]}" exec -T keycloak bash -lc '
      /opt/keycloak/bin/kcadm.sh config credentials --server http://127.0.0.1:8080 --realm master \
        --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null &&
      cid=$(/opt/keycloak/bin/kcadm.sh get clients -r "'"$KEYCLOAK_REALM"'" -q clientId=forestiq-web --fields id \
        | sed -n "s/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1) &&
      test -n "$cid" && /opt/keycloak/bin/kcadm.sh update "clients/$cid" -r "'"$KEYCLOAK_REALM"'" -s directAccessGrantsEnabled=false >/dev/null
    ' >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

wait_service() {
  local name="$1"
  for _ in $(seq 1 180); do
    cid="$("${COMPOSE[@]}" ps -q "$name" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      [[ "$health" == "healthy" || "$health" == "running" ]] && return 0
    fi
    sleep 2
  done
  return 1
}

acquire_token() {
  if [[ "$DIRECT_GRANT_CHANGED" != "1" ]]; then
    "${COMPOSE[@]}" exec -T keycloak bash -lc '
      /opt/keycloak/bin/kcadm.sh config credentials --server http://127.0.0.1:8080 --realm master \
        --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
      cid=$(/opt/keycloak/bin/kcadm.sh get clients -r "'"$KEYCLOAK_REALM"'" -q clientId=forestiq-web --fields id \
        | sed -n "s/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1)
      test -n "$cid"
      /opt/keycloak/bin/kcadm.sh update "clients/$cid" -r "'"$KEYCLOAK_REALM"'" -s directAccessGrantsEnabled=true >/dev/null
    '
    DIRECT_GRANT_CHANGED=1
  fi
  body="$(curl -fsS --max-time 15 -X POST \
    "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' --data-urlencode 'client_id=forestiq-web' \
    --data-urlencode "username=${BENCHMARK_USERNAME}" --data-urlencode "password=${BENCHMARK_PASSWORD}")"
  TOKEN="$(printf '%s' "$body" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("access_token",""))')"
  [[ -n "$TOKEN" ]] || { echo "Unable to obtain token" >&2; exit 1; }
  export TOKEN
}

psql_value() {
  printf '%s\n' "$1" | "${COMPOSE[@]}" exec -T postgres sh -lc \
    'psql -X -Atq -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | tr -d '\r' | head -1
}

percentile() {
  local file="$1" fraction="$2"
  sort -n "$file" | awk -v f="$fraction" '{a[NR]=$1} END{if(!NR){print "NA";exit} i=int(NR*f);if(NR*f>i)i++;if(i<1)i=1;printf "%.3f",a[i]}'
}

measure() {
  local name="$1" url="$2" auth="$3" accept="${4:-application/json}"
  local csv="$OUT_DIR/${name}.csv" times="$OUT_DIR/${name}.times"
  local args=(python3 scripts/performance_http_client.py --url "$url" --requests "$REQUESTS" \
    --concurrency "$CONCURRENCY" --output "$csv" --accept "$accept")
  if [[ "$auth" == "1" ]]; then args+=(--token "$TOKEN"); fi
  "${args[@]}"
  awk -F, '$1>=200&&$1<300{printf "%.3f\n",$2*1000}' "$csv" > "$times"
  ok="$(awk -F, '$1>=200&&$1<300{n++}END{print n+0}' "$csv")"
  avg="$(awk '{s+=$1;n++}END{if(n)printf "%.3f",s/n;else print "NA"}' "$times")"
  p50="$(percentile "$times" 0.50)"; p95="$(percentile "$times" 0.95)"; p99="$(percentile "$times" 0.99)"
  bytes="$(awk -F, '$1>=200&&$1<300{s+=$3;n++}END{if(n)printf "%.0f",s/n;else print "NA"}' "$csv")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$ok" "$avg" "$p50" "$p95" "$p99" "$bytes" >> "$OUT_DIR/summary.tsv"
}

prime() {
  local url="$1" accept="$2"
  code="$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' -H "Accept: $accept" -H "Authorization: Bearer $TOKEN" "$url")"
  [[ "$code" -ge 200 && "$code" -lt 300 ]] || { echo "Cache prime failed HTTP $code: $url" >&2; exit 1; }
}

"${COMPOSE[@]}" up -d --build postgres keycloak keycloak-setup minio minio-setup api > "$OUT_DIR/compose-up.log" 2>&1
wait_service postgres; wait_service keycloak; wait_service api
[[ -n "$TOKEN" ]] || acquire_token

OVERVIEW_TILE="$(psql_value "SELECT tile_x||'/'||tile_y FROM land_parcel_overview_cell WHERE organization_id='${ORG_ID}'::uuid AND scope_key='ALL' AND zoom=8 GROUP BY tile_x,tile_y ORDER BY sum(parcel_count) DESC LIMIT 1;")"
DETAIL_TILE="$(psql_value "WITH cfg AS (SELECT 40075016.685578488/power(2.0,12) s), p AS (SELECT centroid_3857 c FROM land_parcel_render WHERE organization_id='${ORG_ID}'::uuid), t AS (SELECT floor((ST_X(c)+20037508.342789244)/s)::int x,floor((20037508.342789244-ST_Y(c))/s)::int y FROM p,cfg) SELECT x||'/'||y FROM t GROUP BY x,y ORDER BY count(*) DESC LIMIT 1;")"
OVERVIEW_X="${OVERVIEW_TILE%/*}"; OVERVIEW_Y="${OVERVIEW_TILE#*/}"
DETAIL_X="${DETAIL_TILE%/*}"; DETAIL_Y="${DETAIL_TILE#*/}"
VERSION="$(date +%s%N)"
OVERVIEW_URL="${BASE_URL}/api/v1/map/land-parcels-overview/8/${OVERVIEW_X}/${OVERVIEW_Y}.pbf?v=${VERSION}"
DETAIL_URL="${BASE_URL}/api/v1/map/land-parcels/12/${DETAIL_X}/${DETAIL_Y}.pbf?v=${VERSION}"

printf 'benchmark\tok\tavg_ms\tp50_ms\tp95_ms\tp99_ms\tavg_bytes\n' > "$OUT_DIR/summary.tsv"
measure health_no_auth "${BASE_URL}/actuator/health" 0
measure owner_size1 "${BASE_URL}/api/v1/owners/cursor?search=&size=1" 1

prime "$DETAIL_URL" 'application/vnd.mapbox-vector-tile'
measure tile_detail_true_warm "$DETAIL_URL" 1 'application/vnd.mapbox-vector-tile'
prime "$OVERVIEW_URL" 'application/vnd.mapbox-vector-tile'
measure tile_overview_true_warm "$OVERVIEW_URL" 1 'application/vnd.mapbox-vector-tile'

"${COMPOSE[@]}" exec -T -e PERF_ORG_ID="$ORG_ID" postgres sh -lc \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v org_id="$PERF_ORG_ID" -f /dev/stdin' \
  < performance/sql/final-gate-plans.sql > "$OUT_DIR/query-plans.txt"

{
  echo "captured=$(date -Iseconds)"
  echo "commit=$(git rev-parse HEAD)"
  echo "dirty=$(git status --porcelain | wc -l | tr -d ' ')"
  echo "organization_id=$ORG_ID"
  echo "overview_tile=8/$OVERVIEW_X/$OVERVIEW_Y"
  echo "detail_tile=12/$DETAIL_X/$DETAIL_Y"
  echo "docker_mem_total_bytes=$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
} > "$OUT_DIR/system.txt"

curl -fsS --max-time 10 -H "Authorization: Bearer $TOKEN" \
  "${BASE_URL}/actuator/metrics/forestiq.map.tile.cache.requests" > "$OUT_DIR/map-cache-metrics.json" || true
curl -fsS --max-time 10 -H "Authorization: Bearer $TOKEN" \
  "${BASE_URL}/actuator/metrics/forestiq.map.tile.latency" > "$OUT_DIR/map-generation-metrics.json" || true

cat "$OUT_DIR/summary.tsv" | tee "$OUT_DIR/result.txt"
echo "Query plans: $OUT_DIR/query-plans.txt"
echo "Artifacts: $OUT_DIR"
