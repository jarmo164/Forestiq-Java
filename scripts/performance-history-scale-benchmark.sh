#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command_name in docker curl awk sed sort xargs; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command missing: $command_name" >&2
    exit 1
  fi
done
if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required" >&2
  exit 1
fi
if [[ ! -f .env ]]; then
  echo "ForestIQ .env is missing." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -f compose.performance.yaml ]]; then
  COMPOSE=(docker compose -f compose.yaml -f compose.performance.yaml)
else
  COMPOSE=(docker compose -f compose.yaml)
fi

API_PORT="${API_PORT:-8080}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-forestiq-dev}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${API_PORT}}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://127.0.0.1:${KEYCLOAK_PORT}}"
BENCHMARK_USERNAME="${BENCHMARK_USERNAME:-admin}"
BENCHMARK_PASSWORD="${BENCHMARK_PASSWORD:-forestiq}"
REQUESTS="${REQUESTS:-50}"
CONCURRENCY="${CONCURRENCY:-5}"
TILE_REQUESTS="${TILE_REQUESTS:-40}"
TILE_CONCURRENCY="${TILE_CONCURRENCY:-5}"
TOKEN="${TOKEN:-}"
AUTO_TOKEN=0
STATS_PID=""

STAMP="$(date +%Y%m%d-%H%M%S)"
GIT_SHA="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
OUT_DIR="${PERF_OUTPUT_ROOT:-.run/performance-history}/${STAMP}-${GIT_SHA}"
mkdir -p "$OUT_DIR/errors"
SUMMARY="$OUT_DIR/http-summary.tsv"

cleanup() {
  if [[ -n "$STATS_PID" ]]; then
    kill "$STATS_PID" >/dev/null 2>&1 || true
    wait "$STATS_PID" >/dev/null 2>&1 || true
  fi
  if [[ "$AUTO_TOKEN" == "1" ]]; then
    "${COMPOSE[@]}" exec -T -e PERF_REALM="$KEYCLOAK_REALM" keycloak bash -lc '
      K=/opt/keycloak/bin/kcadm.sh
      $K config credentials --server http://127.0.0.1:8080 --realm master \
        --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 || exit 0
      cid=$($K get clients -r "$PERF_REALM" -q clientId=forestiq-web --fields id \
        | sed -n "s/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1)
      [[ -n "$cid" ]] && $K update "clients/$cid" -r "$PERF_REALM" -s directAccessGrantsEnabled=false >/dev/null 2>&1 || true
    ' >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

wait_for_service() {
  local name="$1"
  local attempts="${2:-300}"
  for ((i=1; i<=attempts; i++)); do
    local cid state
    cid="$("${COMPOSE[@]}" ps -q "$name" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      if [[ "$state" == "healthy" || "$state" == "running" ]]; then
        return 0
      fi
      if [[ "$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || true)" == "exited" ]]; then
        echo "$name exited during startup" >&2
        "${COMPOSE[@]}" logs --tail=200 "$name" >&2 || true
        return 1
      fi
    fi
    sleep 2
  done
  echo "Timed out waiting for $name" >&2
  return 1
}

psql_value() {
  local sql="$1"
  printf '%s\n' "$sql" | "${COMPOSE[@]}" exec -T postgres sh -lc \
    'psql -X -v ON_ERROR_STOP=1 -Atq -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | tr -d '\r' | head -1
}

percentile_file() {
  local sorted="$1" fraction="$2" n idx
  n="$(wc -l < "$sorted" | tr -d ' ')"
  if [[ "$n" == "0" ]]; then printf 'NA'; return; fi
  idx="$(awk -v n="$n" -v f="$fraction" 'BEGIN{x=n*f;i=int(x);if(x>i)i++;if(i<1)i=1;print i}')"
  sed -n "${idx}p" "$sorted"
}

http_bench() {
  local name="$1" url="$2" requests="$3" concurrency="$4" accept="${5:-application/json}"
  local csv="$OUT_DIR/${name}.csv" times="$OUT_DIR/${name}.times-ms" bytes="$OUT_DIR/${name}.bytes"
  local diag="$OUT_DIR/errors/${name}.body" diag_status

  diag_status="$(curl -sS --max-time 30 -o "$diag" -w '%{http_code}' \
    -H "Accept: $accept" -H "Authorization: Bearer $TOKEN" "$url" || printf '000')"
  if [[ "$diag_status" =~ ^2 ]]; then
    rm -f "$diag"
  else
    printf '%s\n' "$diag_status" > "$OUT_DIR/errors/${name}.status"
  fi

  export TOKEN url accept
  seq 1 "$requests" | xargs -P "$concurrency" -I{} bash -c '
    curl -sS --max-time 30 -o /dev/null \
      -H "Accept: $accept" -H "Authorization: Bearer $TOKEN" \
      -w "%{http_code},%{time_total},%{size_download}\n" "$url" || printf "000,30,0\n"
  ' > "$csv"

  awk -F, '$1>=200 && $1<300 {printf "%.3f\n",$2*1000}' "$csv" | sort -n > "$times"
  awk -F, '$1>=200 && $1<300 {print $3}' "$csv" | sort -n > "$bytes"

  local total ok avg p50 p95 p99 max avg_bytes p95_bytes
  total="$(wc -l < "$csv" | tr -d ' ')"
  ok="$(awk -F, '$1>=200 && $1<300 {n++} END{print n+0}' "$csv")"
  avg="$(awk '{s+=$1;n++} END{if(n)printf "%.2f",s/n;else print "NA"}' "$times")"
  p50="$(percentile_file "$times" 0.50)"
  p95="$(percentile_file "$times" 0.95)"
  p99="$(percentile_file "$times" 0.99)"
  max="$(tail -1 "$times" 2>/dev/null || true)"; max="${max:-NA}"
  avg_bytes="$(awk '{s+=$1;n++} END{if(n)printf "%.0f",s/n;else print "NA"}' "$bytes")"
  p95_bytes="$(percentile_file "$bytes" 0.95)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$name" "$ok" "$total" "$avg" "$p50" "$p95" "$p99" "$max" "$avg_bytes" "$p95_bytes" >> "$SUMMARY"
}

cold_tile_bench() {
  local name="$1" base="$2" requests="$3" concurrency="$4"
  local csv="$OUT_DIR/${name}.csv" times="$OUT_DIR/${name}.times-ms" bytes="$OUT_DIR/${name}.bytes"
  local version_base="$(date +%s)000"
  export TOKEN base version_base
  seq 1 "$requests" | xargs -P "$concurrency" -I{} bash -c '
    v=$((version_base + {}))
    curl -sS --max-time 30 -o /dev/null \
      -H "Accept: application/vnd.mapbox-vector-tile" -H "Authorization: Bearer $TOKEN" \
      -w "%{http_code},%{time_total},%{size_download}\n" "${base}?v=${v}" || printf "000,30,0\n"
  ' > "$csv"
  awk -F, '$1>=200 && $1<300 {printf "%.3f\n",$2*1000}' "$csv" | sort -n > "$times"
  awk -F, '$1>=200 && $1<300 {print $3}' "$csv" | sort -n > "$bytes"
  local total ok avg p50 p95 p99 max avg_bytes p95_bytes
  total="$(wc -l < "$csv" | tr -d ' ')"
  ok="$(awk -F, '$1>=200 && $1<300 {n++} END{print n+0}' "$csv")"
  avg="$(awk '{s+=$1;n++} END{if(n)printf "%.2f",s/n;else print "NA"}' "$times")"
  p50="$(percentile_file "$times" 0.50)"; p95="$(percentile_file "$times" 0.95)"; p99="$(percentile_file "$times" 0.99)"
  max="$(tail -1 "$times" 2>/dev/null || true)"; max="${max:-NA}"
  avg_bytes="$(awk '{s+=$1;n++} END{if(n)printf "%.0f",s/n;else print "NA"}' "$bytes")"
  p95_bytes="$(percentile_file "$bytes" 0.95)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$name" "$ok" "$total" "$avg" "$p50" "$p95" "$p99" "$max" "$avg_bytes" "$p95_bytes" >> "$SUMMARY"
}

sanitize() {
  sed -E 's/[0-9]{5}:[0-9]{3}:[0-9]{4}/<CADASTRE>/g'
}

capture_host_memory() {
  local file="$1"
  {
    date '+captured=%Y-%m-%dT%H:%M:%S%z'
    if command -v powershell.exe >/dev/null 2>&1; then
      powershell.exe -NoProfile -Command \
        'Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory,TotalVirtualMemorySize,FreeVirtualMemory | Format-List'
    elif command -v free >/dev/null 2>&1; then
      free -h
    elif command -v vm_stat >/dev/null 2>&1; then
      vm_stat
      sysctl vm.swapusage 2>/dev/null || true
    fi
  } > "$file" 2>&1 || true
}

echo "Starting ForestIQ history-scale benchmark for commit $GIT_SHA"
echo "Results: $OUT_DIR"

"${COMPOSE[@]}" up -d --build > "$OUT_DIR/compose-up.log" 2>&1
wait_for_service postgres 450
wait_for_service keycloak 300
wait_for_service api 600

# Local dev token. Supplying TOKEN bypasses the temporary direct-grant change.
if [[ -z "$TOKEN" ]]; then
  "${COMPOSE[@]}" exec -T -e PERF_REALM="$KEYCLOAK_REALM" keycloak bash -lc '
    set -e
    K=/opt/keycloak/bin/kcadm.sh
    $K config credentials --server http://127.0.0.1:8080 --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
    cid=$($K get clients -r "$PERF_REALM" -q clientId=forestiq-web --fields id \
      | sed -n "s/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1)
    test -n "$cid"
    $K update "clients/$cid" -r "$PERF_REALM" -s directAccessGrantsEnabled=true >/dev/null
  '
  AUTO_TOKEN=1
  token_json="$(curl -fsS --max-time 15 -X POST \
    "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=forestiq-web' \
    --data-urlencode "username=${BENCHMARK_USERNAME}" \
    --data-urlencode "password=${BENCHMARK_PASSWORD}")"
  if command -v python3 >/dev/null 2>&1; then
    TOKEN="$(printf '%s' "$token_json" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("access_token",""))')"
  else
    TOKEN="$(printf '%s' "$token_json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
  fi
  [[ -n "$TOKEN" ]] || { echo "Could not obtain benchmark token" >&2; exit 1; }
fi
export TOKEN

SEED_ORG="11111111-1111-1111-1111-111111111111"
if [[ -n "${PERF_ORG_ID:-}" ]]; then
  ORG_ID="$PERF_ORG_ID"
elif [[ "$(psql_value "SELECT count(*) FROM land_parcel WHERE organization_id='$SEED_ORG'::uuid;")" != "0" ]]; then
  ORG_ID="$SEED_ORG"
else
  ORG_ID="$(psql_value "SELECT organization_id FROM land_parcel GROUP BY organization_id ORDER BY count(*) DESC LIMIT 1;")"
  if [[ "$AUTO_TOKEN" == "1" ]]; then
    echo "Dataset is not in the local dev admin organization. Rerun with TOKEN=... PERF_ORG_ID=$ORG_ID" >&2
    exit 1
  fi
fi
[[ -n "$ORG_ID" ]] || { echo "No land_parcel dataset found" >&2; exit 1; }

history_counts_sql="WITH h AS (
 SELECT cadastral_code,count(*)::bigint n FROM cadastre_notification WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
 UNION ALL SELECT cadastral_code,count(*)::bigint FROM forest_registry_feature WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
 UNION ALL SELECT cadastral_code,count(*)::bigint FROM cadastre_sub_part WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
), totals AS (SELECT cadastral_code,sum(n) n FROM h GROUP BY cadastral_code)
SELECT cadastral_code||'|'||n FROM totals ORDER BY n DESC,cadastral_code LIMIT 1;"
HIGH_ROW="$(psql_value "$history_counts_sql")"
if [[ -z "$HIGH_ROW" ]]; then
  HIGH_CODE="$(psql_value "SELECT cadastral_code FROM land_parcel WHERE organization_id='$ORG_ID'::uuid AND boundary IS NOT NULL ORDER BY cadastral_code LIMIT 1;")"
  HIGH_COUNT=0
else
  HIGH_CODE="${HIGH_ROW%%|*}"; HIGH_COUNT="${HIGH_ROW##*|}"
fi

SPARSE_ROW="$(psql_value "WITH h AS (
 SELECT cadastral_code,count(*)::bigint n FROM cadastre_notification WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
 UNION ALL SELECT cadastral_code,count(*)::bigint FROM forest_registry_feature WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
 UNION ALL SELECT cadastral_code,count(*)::bigint FROM cadastre_sub_part WHERE organization_id='$ORG_ID'::uuid GROUP BY cadastral_code
), totals AS (SELECT cadastral_code,sum(n) n FROM h GROUP BY cadastral_code)
SELECT cadastral_code||'|'||n FROM totals ORDER BY n ASC,cadastral_code LIMIT 1;")"
if [[ -z "$SPARSE_ROW" ]]; then SPARSE_CODE="$HIGH_CODE"; SPARSE_COUNT=0; else SPARSE_CODE="${SPARSE_ROW%%|*}"; SPARSE_COUNT="${SPARSE_ROW##*|}"; fi
[[ -n "$HIGH_CODE" ]] || { echo "No representative cadastral code found" >&2; exit 1; }

archive_range="$(psql_value "SELECT COALESCE(min(archive_date),0)||'|'||COALESCE(max(archive_date),9223372036854775807) FROM cadastre_notification WHERE organization_id='$ORG_ID'::uuid AND cadastral_code='$HIGH_CODE' AND archived=TRUE;")"
ARCHIVE_FROM="${archive_range%%|*}"; ARCHIVE_TO="${archive_range##*|}"

tile_xy() {
  local z="$1"
  psql_value "WITH p AS (
    SELECT ST_PointOnSurface(boundary_3857) g FROM land_parcel_render
    WHERE organization_id='$ORG_ID'::uuid AND cadastral_code='$HIGH_CODE' LIMIT 1
  ), fallback AS (
    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(25.5,58.7),4326),3857) g WHERE NOT EXISTS (SELECT 1 FROM p)
  ), point AS (SELECT g FROM p UNION ALL SELECT g FROM fallback LIMIT 1)
  SELECT floor((ST_X(g)+20037508.342789244)/(40075016.685578488/power(2,$z)))::int||'|'||
         floor((20037508.342789244-ST_Y(g))/(40075016.685578488/power(2,$z)))::int FROM point;"
}
Z7="$(tile_xy 7)"; Z11="$(tile_xy 11)"; Z14="$(tile_xy 14)"
Z7X="${Z7%%|*}"; Z7Y="${Z7##*|}"; Z11X="${Z11%%|*}"; Z11Y="${Z11##*|}"; Z14X="${Z14%%|*}"; Z14Y="${Z14##*|}"

{
  echo "captured=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "commit=$(git rev-parse HEAD 2>/dev/null || true)"
  echo "branch=$(git branch --show-current 2>/dev/null || true)"
  echo "dirty_files=$(git status --porcelain | wc -l | tr -d ' ')"
  echo "docker=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  echo "compose=$(docker compose version --short 2>/dev/null || true)"
  echo "request_count=$REQUESTS"
  echo "request_concurrency=$CONCURRENCY"
  echo "tile_request_count=$TILE_REQUESTS"
  echo "tile_concurrency=$TILE_CONCURRENCY"
  echo "organization_id=$ORG_ID"
  echo "high_history_rows=$HIGH_COUNT"
  echo "sparse_history_rows=$SPARSE_COUNT"
  echo "representative_codes=REDACTED"
} > "$OUT_DIR/environment.txt"
capture_host_memory "$OUT_DIR/host-memory-before.txt"

# Enable/reset statement statistics when the performance profile supports them.
printf '%s\n' 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset(); SELECT pg_stat_reset();' \
  | "${COMPOSE[@]}" exec -T postgres sh -lc 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1 || true
START_LSN="$(psql_value 'SELECT pg_current_wal_lsn();')"

(
  echo 'timestamp,name,cpu_percent,mem_usage,mem_percent,net_io,block_io,pids'
  while true; do
    ts="$(date '+%Y-%m-%dT%H:%M:%S%z')"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}' 2>/dev/null \
      | awk -v ts="$ts" -F'|' 'BEGIN{OFS=","} /^forestiq-/ {print ts,$1,$2,$3,$4,$5,$6,$7}'
    sleep 2
  done
) > "$OUT_DIR/docker-stats.csv" &
STATS_PID=$!

printf 'benchmark\tok\ttotal\tavg_ms\tp50_ms\tp95_ms\tp99_ms\tmax_ms\tavg_bytes\tp95_bytes\n' > "$SUMMARY"

http_bench parcel_summary_high "${BASE_URL}/api/v1/map/land-parcels/${HIGH_CODE}/summary" "$REQUESTS" "$CONCURRENCY"
http_bench parcel_summary_sparse "${BASE_URL}/api/v1/map/land-parcels/${SPARSE_CODE}/summary" "$REQUESTS" "$CONCURRENCY"
http_bench notifications_high "${BASE_URL}/api/v1/cadastres/${HIGH_CODE}/notifications/page?limit=50" "$REQUESTS" "$CONCURRENCY"
http_bench notifications_sparse "${BASE_URL}/api/v1/cadastres/${SPARSE_CODE}/notifications/page?limit=50" "$REQUESTS" "$CONCURRENCY"
http_bench forest_features_high "${BASE_URL}/api/v1/cadastres/${HIGH_CODE}/forest-features/page?limit=50" "$REQUESTS" "$CONCURRENCY"
http_bench forest_features_sparse "${BASE_URL}/api/v1/cadastres/${SPARSE_CODE}/forest-features/page?limit=50" "$REQUESTS" "$CONCURRENCY"
http_bench sub_parts_high "${BASE_URL}/api/v1/cadastres/${HIGH_CODE}/sub-parts/page?limit=50" "$REQUESTS" "$CONCURRENCY"
http_bench sub_parts_sparse "${BASE_URL}/api/v1/cadastres/${SPARSE_CODE}/sub-parts/page?limit=50" "$REQUESTS" "$CONCURRENCY"

cold_tile_bench tile_z7_cold "${BASE_URL}/api/v1/map/land-parcels/7/${Z7X}/${Z7Y}.pbf" "$TILE_REQUESTS" "$TILE_CONCURRENCY"
curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/v1/map/land-parcels/7/${Z7X}/${Z7Y}.pbf?v=700001" || true
http_bench tile_z7_warm "${BASE_URL}/api/v1/map/land-parcels/7/${Z7X}/${Z7Y}.pbf?v=700001" "$TILE_REQUESTS" "$TILE_CONCURRENCY" "application/vnd.mapbox-vector-tile"

cold_tile_bench tile_z11_cold "${BASE_URL}/api/v1/map/land-parcels/11/${Z11X}/${Z11Y}.pbf" "$TILE_REQUESTS" "$TILE_CONCURRENCY"
curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/v1/map/land-parcels/11/${Z11X}/${Z11Y}.pbf?v=1100001" || true
http_bench tile_z11_warm "${BASE_URL}/api/v1/map/land-parcels/11/${Z11X}/${Z11Y}.pbf?v=1100001" "$TILE_REQUESTS" "$TILE_CONCURRENCY" "application/vnd.mapbox-vector-tile"

cold_tile_bench tile_z14_cold "${BASE_URL}/api/v1/map/land-parcels/14/${Z14X}/${Z14Y}.pbf" "$TILE_REQUESTS" "$TILE_CONCURRENCY"
curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/v1/map/land-parcels/14/${Z14X}/${Z14Y}.pbf?v=1400001" || true
http_bench tile_z14_warm "${BASE_URL}/api/v1/map/land-parcels/14/${Z14X}/${Z14Y}.pbf?v=1400001" "$TILE_REQUESTS" "$TILE_CONCURRENCY" "application/vnd.mapbox-vector-tile"

kill "$STATS_PID" >/dev/null 2>&1 || true
wait "$STATS_PID" >/dev/null 2>&1 || true
STATS_PID=""

"${COMPOSE[@]}" exec -T -e PERF_ORG_ID="$ORG_ID" postgres sh -lc \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v org_id="$PERF_ORG_ID" -f -' \
  < performance/sql/history-scale-observability.sql | sanitize > "$OUT_DIR/database-observability.txt"

"${COMPOSE[@]}" exec -T \
  -e PERF_ORG_ID="$ORG_ID" -e PERF_CADASTRE="$HIGH_CODE" \
  -e PERF_ARCHIVE_FROM="$ARCHIVE_FROM" -e PERF_ARCHIVE_TO="$ARCHIVE_TO" \
  -e PERF_Z7X="$Z7X" -e PERF_Z7Y="$Z7Y" \
  -e PERF_Z11X="$Z11X" -e PERF_Z11Y="$Z11Y" \
  -e PERF_Z14X="$Z14X" -e PERF_Z14Y="$Z14Y" \
  postgres sh -lc '
    psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      -v org_id="$PERF_ORG_ID" -v cadastre="$PERF_CADASTRE" \
      -v archive_from="$PERF_ARCHIVE_FROM" -v archive_to="$PERF_ARCHIVE_TO" \
      -v z7x="$PERF_Z7X" -v z7y="$PERF_Z7Y" \
      -v z11x="$PERF_Z11X" -v z11y="$PERF_Z11Y" \
      -v z14x="$PERF_Z14X" -v z14y="$PERF_Z14Y" -f -
  ' < performance/sql/history-scale-plans.sql | sanitize > "$OUT_DIR/query-plans.txt"

END_LSN="$(psql_value 'SELECT pg_current_wal_lsn();')"
WAL_BYTES="$(psql_value "SELECT pg_wal_lsn_diff('$END_LSN','$START_LSN')::bigint;")"
{
  echo "start_lsn=$START_LSN"
  echo "end_lsn=$END_LSN"
  echo "wal_bytes=$WAL_BYTES"
  echo "wal_pretty=$(psql_value "SELECT pg_size_pretty($WAL_BYTES::bigint);")"
} > "$OUT_DIR/wal.txt"

"${COMPOSE[@]}" ps > "$OUT_DIR/compose-ps.txt" 2>&1 || true
docker stats --no-stream > "$OUT_DIR/docker-stats-final.txt" 2>&1 || true
"${COMPOSE[@]}" logs --no-color --tail=1500 api 2>&1 | sanitize > "$OUT_DIR/api-tail.log" || true
capture_host_memory "$OUT_DIR/host-memory-after.txt"

{
  echo "# ForestIQ history-scale benchmark"
  echo
  echo "Commit: $GIT_SHA"
  echo "Representative cadastres: redacted"
  echo "High-history combined rows: $HIGH_COUNT"
  echo "Sparse-history combined rows: $SPARSE_COUNT"
  echo "WAL generated during measured workload: $(psql_value "SELECT pg_size_pretty($WAL_BYTES::bigint);")"
  echo
  echo '## HTTP summary'
  echo '```'
  column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
  echo '```'
  echo
  echo "Non-2xx diagnostic bodies, if any: errors/"
  echo "Database distributions/top statements: database-observability.txt"
  echo "EXPLAIN ANALYZE JSON plans: query-plans.txt"
  echo "Container pressure: docker-stats.csv"
  echo "API diagnostics (cadastral codes redacted): api-tail.log"
} > "$OUT_DIR/README.md"

printf '\nForestIQ history-scale benchmark completed.\nResults: %s\n\n' "$OUT_DIR"
cat "$SUMMARY"
