#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <notification-csv> [--archived]" >&2
  exit 2
fi
CSV="$1"; shift
ARCHIVED_FLAG=""
if [[ "${1:-}" == "--archived" ]]; then ARCHIVED_FLAG="--archived"; shift; fi
if [[ ! -f "$CSV" ]]; then echo "Input CSV not found: $CSV" >&2; exit 1; fi
for command in docker curl awk sort python3; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done
[[ -f .env ]] || { echo ".env is required" >&2; exit 1; }

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
SOURCE_NAME="${NOTIFICATION_SOURCE_NAME:-METSAREGISTER_SNAPSHOT}"
BASE_URL="${BASE_URL:-http://localhost:${API_PORT}}"
KEYCLOAK_URL="${KEYCLOAK_URL:-${NEXT_PUBLIC_KEYCLOAK_URL:-http://localhost:${KEYCLOAK_PORT}}}"
SAMPLE_REQUESTS="${INGEST_SAMPLE_REQUESTS:-60}"
SAMPLE_CONCURRENCY="${INGEST_SAMPLE_CONCURRENCY:-5}"
DURING_INTERVAL_SECONDS="${INGEST_DURING_INTERVAL_SECONDS:-1}"
TOKEN="${TOKEN:-}"
DIRECT_GRANT_CHANGED=0
STATS_PID=""
IMPORT_PID=""

if [[ -f compose.performance.yaml ]]; then
  COMPOSE=(docker compose -f compose.yaml -f compose.performance.yaml)
else
  COMPOSE=(docker compose -f compose.yaml)
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
LABEL="${BENCHMARK_LABEL:-ingestion-${STAMP}}"
OUT_DIR=".run/performance/${LABEL}"
mkdir -p "$OUT_DIR"

cleanup() {
  [[ -n "$STATS_PID" ]] && kill "$STATS_PID" >/dev/null 2>&1 || true
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
    local cid health
    cid="$("${COMPOSE[@]}" ps -q "$name" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      [[ "$health" == "healthy" || "$health" == "running" ]] && return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $name" >&2
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
  local body
  body="$(curl -fsS --max-time 15 -X POST \
    "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=forestiq-web' \
    --data-urlencode "username=${BENCHMARK_USERNAME}" \
    --data-urlencode "password=${BENCHMARK_PASSWORD}")"
  TOKEN="$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))')"
  [[ -n "$TOKEN" ]] || { echo "Could not obtain benchmark token" >&2; exit 1; }
  export TOKEN
}

psql_value() {
  printf '%s\n' "$1" | "${COMPOSE[@]}" exec -T postgres sh -lc \
    'psql -X -Atq -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | tr -d '\r' | head -1
}

snapshot_db() {
  local label="$1"
  {
    echo "label=$label"
    psql_value "SELECT 'database_bytes='||pg_database_size(current_database());"
    psql_value "SELECT 'wal_bytes='||(SELECT wal_bytes::bigint FROM pg_stat_wal);"
    psql_value "SELECT 'notification_rows='||count(*) FROM cadastre_notification WHERE organization_id='${ORG_ID}'::uuid;"
    psql_value "SELECT 'notification_dead_tuples='||coalesce(n_dead_tup,0) FROM pg_stat_user_tables WHERE relname='cadastre_notification';"
    psql_value "SELECT 'waiting_sessions='||count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event IS NOT NULL;"
    psql_value "SELECT 'open_transactions='||count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction';"
  } > "$OUT_DIR/db-${label}.txt"
}

p95_from_file() {
  local file="$1"
  awk -F, '$1>=200 && $1<300 {print $2*1000}' "$file" | sort -n | awk '
    {a[NR]=$1} END {if(!NR){print "NA"; exit} i=int(NR*0.95); if(NR*0.95>i)i++; if(i<1)i=1; printf "%.3f",a[i]}'
}

sample_url() {
  local phase="$1" name="$2" url="$3" requests="$4" concurrency="$5" accept="${6:-application/json}"
  local out="$OUT_DIR/${phase}-${name}.csv"
  seq 1 "$requests" | xargs -P "$concurrency" -I{} env TOKEN="$TOKEN" URL="$url" ACCEPT="$accept" bash -c '
    line="$(curl -sS --max-time 30 -o /dev/null -H "Authorization: Bearer $TOKEN" -H "Accept: $ACCEPT" -w "%{http_code},%{time_total},%{size_download}" "$URL" 2>/dev/null)"; rc=$?
    if [[ $rc -eq 0 ]]; then printf "%s\n" "$line"; else printf "000,30.000000,0\n"; fi
  ' >> "$out"
}

sample_once() {
  local phase="$1" name="$2" url="$3" accept="${4:-application/json}"
  local out="$OUT_DIR/${phase}-${name}.csv"
  local line rc
  set +e
  line="$(curl -sS --max-time 30 -o /dev/null -H "Authorization: Bearer $TOKEN" -H "Accept: $accept" \
    -w '%{http_code},%{time_total},%{size_download}' "$url" 2>/dev/null)"; rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then printf '%s\n' "$line" >> "$out"; else printf '000,30.000000,0\n' >> "$out"; fi
}

"${COMPOSE[@]}" up -d --build postgres keycloak keycloak-setup minio minio-setup api > "$OUT_DIR/compose-up.log" 2>&1
wait_service postgres
wait_service keycloak
wait_service api
[[ -n "$TOKEN" ]] || acquire_token

POSTGRES_CID="$("${COMPOSE[@]}" ps -q postgres)"
CADASTRAL_CODE="$(psql_value "SELECT cadastral_code FROM land_parcel WHERE organization_id='${ORG_ID}'::uuid ORDER BY updated_at DESC NULLS LAST,cadastral_code LIMIT 1;")"
DETAIL_TILE="$(psql_value "WITH cfg AS (SELECT 40075016.685578488/power(2.0,12) tile_size), t AS (SELECT centroid_3857 p FROM land_parcel_render WHERE organization_id='${ORG_ID}'::uuid AND centroid_3857 IS NOT NULL), x AS (SELECT floor((ST_X(p)+20037508.342789244)/cfg.tile_size)::int tx,floor((20037508.342789244-ST_Y(p))/cfg.tile_size)::int ty FROM t CROSS JOIN cfg) SELECT tx||'/'||ty FROM x GROUP BY tx,ty ORDER BY count(*) DESC LIMIT 1;")"
DETAIL_X="${DETAIL_TILE%/*}"; DETAIL_Y="${DETAIL_TILE#*/}"
OWNER_URL="${BASE_URL}/api/v1/owners/cursor?search=&size=50"
RELATION_URL="${BASE_URL}/api/v1/ownership-relations/cursor?search=&size=50"
PARCEL_URL="${BASE_URL}/api/v1/map/land-parcels/${CADASTRAL_CODE}/summary"
TILE_URL="${BASE_URL}/api/v1/map/land-parcels/12/${DETAIL_X}/${DETAIL_Y}.pbf?v=ingestion-gate"

{
  echo "captured=$(date -Iseconds)"
  echo "commit=$(git rev-parse HEAD)"
  echo "dirty=$(git status --porcelain | wc -l | tr -d ' ')"
  echo "organization_id=$ORG_ID"
  echo "csv=$CSV"
  echo "csv_bytes=$(wc -c < "$CSV" | tr -d ' ')"
  echo "archived=${ARCHIVED_FLAG:+true}"
  echo "source_name=$SOURCE_NAME"
} > "$OUT_DIR/system.txt"

(
  echo 'timestamp,name,cpu_percent,mem_usage,mem_percent,block_io,pids'
  while true; do
    ts="$(date -Iseconds)"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.BlockIO}}|{{.PIDs}}' 2>/dev/null \
      | awk -F'|' -v ts="$ts" 'BEGIN{OFS=","} /^forestiq-/ {print ts,$1,$2,$3,$4,$5,$6}'
    sleep 2
  done
) > "$OUT_DIR/docker-stats.csv" &
STATS_PID=$!

snapshot_db before
sample_url before owners "$OWNER_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
sample_url before relations "$RELATION_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
sample_url before parcel "$PARCEL_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
curl -fsS --max-time 30 -o /dev/null -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.mapbox-vector-tile' "$TILE_URL" || true
sample_url before tile "$TILE_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY" 'application/vnd.mapbox-vector-tile'

IMPORT_STARTED="$(date +%s)"
python3 scripts/import_notifications_bulk.py "$CSV" $ARCHIVED_FLAG \
  --organization-id "$ORG_ID" --source-name "$SOURCE_NAME" --container "$POSTGRES_CID" \
  > "$OUT_DIR/import-result.json" 2> "$OUT_DIR/import-error.log" &
IMPORT_PID=$!

while kill -0 "$IMPORT_PID" >/dev/null 2>&1; do
  sample_once during owners "$OWNER_URL"
  sample_once during relations "$RELATION_URL"
  sample_once during parcel "$PARCEL_URL"
  sample_once during tile "$TILE_URL" 'application/vnd.mapbox-vector-tile'
  sleep "$DURING_INTERVAL_SECONDS"
done
set +e
wait "$IMPORT_PID"; IMPORT_RC=$?
set -e
IMPORT_PID=""
IMPORT_FINISHED="$(date +%s)"

snapshot_db after-import
if [[ "$IMPORT_RC" -ne 0 ]]; then
  echo "Import failed; see $OUT_DIR/import-error.log" >&2
  exit "$IMPORT_RC"
fi

sample_url after owners "$OWNER_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
sample_url after relations "$RELATION_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
sample_url after parcel "$PARCEL_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY"
sample_url after tile "$TILE_URL" "$SAMPLE_REQUESTS" "$SAMPLE_CONCURRENCY" 'application/vnd.mapbox-vector-tile'
snapshot_db after-browse

kill "$STATS_PID" >/dev/null 2>&1 || true
wait "$STATS_PID" >/dev/null 2>&1 || true
STATS_PID=""

{
  echo -e 'phase\tendpoint\tp95_ms\tok\ttotal'
  for phase in before during after; do
    for endpoint in owners relations parcel tile; do
      file="$OUT_DIR/${phase}-${endpoint}.csv"
      [[ -f "$file" ]] || continue
      total="$(wc -l < "$file" | tr -d ' ')"
      ok="$(awk -F, '$1>=200&&$1<300{n++}END{print n+0}' "$file")"
      echo -e "${phase}\t${endpoint}\t$(p95_from_file "$file")\t${ok}\t${total}"
    done
  done
} > "$OUT_DIR/latency-summary.tsv"

{
  echo "import_duration_seconds=$((IMPORT_FINISHED-IMPORT_STARTED))"
  cat "$OUT_DIR/latency-summary.tsv"
  echo
  echo "import metrics:"
  cat "$OUT_DIR/import-result.json"
  echo
  echo "post-import safety:"
  cat "$OUT_DIR/db-after-import.txt"
} | tee "$OUT_DIR/result.txt"

echo "Artifacts: $OUT_DIR"
