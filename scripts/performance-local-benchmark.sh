#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for required in docker curl awk sed sort xargs; do
  if ! command -v "$required" >/dev/null 2>&1; then
    echo "Required command missing: $required" >&2
    exit 1
  fi
done
if [[ ! -f compose.yaml ]]; then
  echo "Run this script from a ForestIQ checkout (compose.yaml is missing)." >&2
  exit 1
fi
if [[ ! -f .env ]]; then
  echo "ForestIQ .env is missing. Create/configure it before benchmarking." >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

API_PORT="${API_PORT:-8080}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-forestiq-dev}"
BENCHMARK_USERNAME="${BENCHMARK_USERNAME:-admin}"
BENCHMARK_PASSWORD="${BENCHMARK_PASSWORD:-forestiq}"
REQUESTS="${REQUESTS:-200}"
CONCURRENCY="${CONCURRENCY:-10}"
TILE_REQUESTS="${TILE_REQUESTS:-120}"
TILE_CONCURRENCY="${TILE_CONCURRENCY:-5}"
BASE_URL="${BASE_URL:-http://localhost:${API_PORT}}"
KEYCLOAK_URL="${KEYCLOAK_URL:-${NEXT_PUBLIC_KEYCLOAK_URL:-http://localhost:${KEYCLOAK_PORT}}}"
BENCHMARK_INCLUDE_LEGACY="${BENCHMARK_INCLUDE_LEGACY:-0}"
BENCHMARK_PERFORMANCE_PROFILE="${BENCHMARK_PERFORMANCE_PROFILE:-1}"

if [[ "$BENCHMARK_PERFORMANCE_PROFILE" == "1" && -f compose.performance.yaml ]]; then
  COMPOSE=(docker compose -f compose.yaml -f compose.performance.yaml)
  COMPOSE_PROFILE="performance"
else
  COMPOSE=(docker compose -f compose.yaml)
  COMPOSE_PROFILE="plain"
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
GIT_SHA="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
GIT_BRANCH="$(git branch --show-current 2>/dev/null || echo detached)"
LABEL="${BENCHMARK_LABEL:-${GIT_BRANCH}-${GIT_SHA}}"
SAFE_LABEL="$(printf '%s' "$LABEL" | tr '/ :\\' '----' | tr -cd '[:alnum:]_.-')"
OUT_DIR=".run/performance/${SAFE_LABEL}-${STAMP}"
mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.tsv"
TOKEN="${TOKEN:-}"
DIRECT_GRANT_CHANGED=0
STATS_PID=""

cleanup() {
  if [[ -n "$STATS_PID" ]]; then
    kill "$STATS_PID" >/dev/null 2>&1 || true
    wait "$STATS_PID" >/dev/null 2>&1 || true
  fi
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

wait_for_service() {
  local name="$1" attempts="${2:-180}"
  for ((i=1; i<=attempts; i++)); do
    local cid health state
    cid="$("${COMPOSE[@]}" ps -q "$name" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || true)"
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || true)"
      if [[ "$health" == "healthy" || "$health" == "running" ]]; then
        return 0
      fi
      if [[ "$state" == "exited" ]]; then
        echo "$name exited during startup" >&2
        "${COMPOSE[@]}" logs --tail=200 "$name" >&2 || true
        return 1
      fi
    fi
    sleep 2
  done
  echo "Timed out waiting for $name" >&2
  "${COMPOSE[@]}" ps >&2 || true
  return 1
}

percentile() {
  local sorted="$1" fraction="$2" n idx
  n="$(wc -l < "$sorted" | tr -d ' ')"
  if [[ "$n" == "0" ]]; then printf 'NA'; return; fi
  idx="$(awk -v n="$n" -v f="$fraction" 'BEGIN{x=n*f;i=int(x);if(x>i)i++;if(i<1)i=1;print i}')"
  sed -n "${idx}p" "$sorted"
}

extract_access_token() {
  local json="$1" token=""
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import json' >/dev/null 2>&1; then
    token="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))' 2>/dev/null || true)"
  fi
  if [[ -z "$token" ]]; then
    token="$(printf '%s' "$json" | tr -d '\r\n' | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
  fi
  printf '%s' "$token"
}

acquire_benchmark_token() {
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

  local token_json
  token_json="$(curl -fsS --max-time 15 -X POST \
    "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=password' \
    --data-urlencode 'client_id=forestiq-web' \
    --data-urlencode "username=${BENCHMARK_USERNAME}" \
    --data-urlencode "password=${BENCHMARK_PASSWORD}")"
  TOKEN="$(extract_access_token "$token_json")"
  if [[ -z "$TOKEN" ]]; then
    echo "Could not obtain local benchmark token. Set TOKEN manually and rerun." >&2
    exit 1
  fi
  export TOKEN
}

ensure_benchmark_token() {
  local probe
  probe="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" "${BASE_URL}/actuator/health" || true)"
  if [[ "$probe" == "200" ]]; then return 0; fi
  acquire_benchmark_token
  probe="$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" "${BASE_URL}/actuator/health" || true)"
  if [[ "$probe" != "200" ]]; then
    echo "Benchmark token probe failed with HTTP $probe." >&2
    exit 1
  fi
}

psql_value() {
  local sql="$1"
  printf '%s\n' "$sql" | "${COMPOSE[@]}" exec -T postgres sh -lc \
    'psql -X -v ON_ERROR_STOP=1 -Atq -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | tr -d '\r' | head -1
}

endpoint_exists() {
  local url="$1" code
  code="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" "$url" || true)"
  [[ "$code" != "404" && "$code" != "405" && "$code" != "000" ]]
}

benchmark_fixed_url() {
  local name="$1" url="$2" requests="$3" concurrency="$4" accept="${5:-application/json}"
  local csv="$OUT_DIR/${name}.csv" times="$OUT_DIR/${name}.times-ms" bytes="$OUT_DIR/${name}.bytes"

  seq 1 "$requests" | xargs -P "$concurrency" -I{} env TOKEN="$TOKEN" url="$url" accept="$accept" bash -c '
    result="$(curl -sS --max-time 30 -o /dev/null \
      -H "Accept: $accept" -H "Authorization: Bearer $TOKEN" \
      -w "%{http_code},%{time_total},%{size_download}" "$url" 2>/dev/null)"
    rc=$?
    if [[ $rc -eq 0 ]]; then printf "%s\n" "$result"; else printf "000,30.000000,0\n"; fi
  ' > "$csv"

  awk -F, '$1>=200 && $1<300 {printf "%.3f\n",$2*1000}' "$csv" | sort -n > "$times"
  awk -F, '$1>=200 && $1<300 {print $3}' "$csv" | sort -n > "$bytes"
  summarize "$name" "$csv" "$times" "$bytes"
}

benchmark_cold_tile() {
  local name="$1" base="$2" requests="$3" concurrency="$4"
  local csv="$OUT_DIR/${name}.csv" times="$OUT_DIR/${name}.times-ms" bytes="$OUT_DIR/${name}.bytes"

  seq 1 "$requests" | xargs -P "$concurrency" -I{} env TOKEN="$TOKEN" base="$base" bash -c '
    result="$(curl -sS --max-time 30 -o /dev/null \
      -H "Accept: application/vnd.mapbox-vector-tile" -H "Authorization: Bearer $TOKEN" \
      -w "%{http_code},%{time_total},%{size_download}" "${base}?v={}" 2>/dev/null)"
    rc=$?
    if [[ $rc -eq 0 ]]; then printf "%s\n" "$result"; else printf "000,30.000000,0\n"; fi
  ' > "$csv"

  awk -F, '$1>=200 && $1<300 {printf "%.3f\n",$2*1000}' "$csv" | sort -n > "$times"
  awk -F, '$1>=200 && $1<300 {print $3}' "$csv" | sort -n > "$bytes"
  summarize "$name" "$csv" "$times" "$bytes"
}

summarize() {
  local name="$1" csv="$2" times="$3" bytes="$4"
  local ok total avg p50 p95 p99 max avg_bytes p95_bytes
  total="$(wc -l < "$csv" | tr -d ' ')"
  ok="$(awk -F, '$1>=200 && $1<300 {n++} END{print n+0}' "$csv")"
  avg="$(awk '{s+=$1;n++} END{if(n)printf "%.2f",s/n;else print "NA"}' "$times")"
  p50="$(percentile "$times" 0.50)"
  p95="$(percentile "$times" 0.95)"
  p99="$(percentile "$times" 0.99)"
  max="$(tail -1 "$times" 2>/dev/null || true)"; max="${max:-NA}"
  avg_bytes="$(awk '{s+=$1;n++} END{if(n)printf "%.0f",s/n;else print "NA"}' "$bytes")"
  p95_bytes="$(percentile "$bytes" 0.95)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$name" "$ok" "$total" "$avg" "$p50" "$p95" "$p99" "$max" "$avg_bytes" "$p95_bytes" >> "$SUMMARY"
}

dense_tile() {
  local z="$1"
  local has_centroid tile
  has_centroid="$(psql_value "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='land_parcel_render' AND column_name='centroid_3857';" || echo 0)"
  if [[ "$has_centroid" == "1" ]]; then
    tile="$(psql_value "WITH cfg AS (SELECT 40075016.685578488 / power(2.0,$z) AS tile_size), points AS (SELECT centroid_3857 AS p FROM land_parcel_render WHERE organization_id='${ORG_ID}'::uuid AND centroid_3857 IS NOT NULL), tiles AS (SELECT floor((ST_X(p)+20037508.342789244)/cfg.tile_size)::int AS x, floor((20037508.342789244-ST_Y(p))/cfg.tile_size)::int AS y FROM points CROSS JOIN cfg) SELECT x||','||y FROM tiles GROUP BY x,y ORDER BY count(*) DESC LIMIT 1;" || true)"
    if [[ -n "$tile" ]]; then printf '%s' "$tile"; return; fi
  fi
  if [[ "$z" == "8" ]]; then printf '145,77'; else printf '2320,1232'; fi
}

host_memory_snapshot() {
  local label="$1"
  {
    echo "=== host_memory_${label} ==="
    echo "docker_mem_total_bytes=$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
    if command -v powershell.exe >/dev/null 2>&1; then
      powershell.exe -NoProfile -Command '$os=Get-CimInstance Win32_OperatingSystem; "total_visible_kb=$($os.TotalVisibleMemorySize)"; "free_physical_kb=$($os.FreePhysicalMemory)"; "total_virtual_kb=$($os.TotalVirtualMemorySize)"; "free_virtual_kb=$($os.FreeVirtualMemory)"' 2>/dev/null | tr -d '\r' || true
    elif command -v free >/dev/null 2>&1; then
      free -k || true
    fi
  } >> "$OUT_DIR/system.txt"
}

cat <<EOF
ForestIQ local performance benchmark
  label:        $LABEL
  branch:       $GIT_BRANCH
  commit:       $GIT_SHA
  compose:      $COMPOSE_PROFILE
  output:       $OUT_DIR
  API requests: $REQUESTS @ concurrency $CONCURRENCY
  tile requests:$TILE_REQUESTS @ concurrency $TILE_CONCURRENCY
  legacy load:  $BENCHMARK_INCLUDE_LEGACY

The default run measures only supported interactive cursor/current paths. Legacy
list stress is opt-in so retired queries cannot distort DB/temp-I/O measurements.
The script rebuilds the CURRENT checkout; it never pulls, switches branches or
removes Docker volumes.
EOF

"${COMPOSE[@]}" up -d --build postgres keycloak keycloak-setup minio minio-setup api frontend \
  > "$OUT_DIR/compose-up.log" 2>&1
wait_for_service postgres
wait_for_service keycloak
wait_for_service api
wait_for_service frontend

if [[ -z "$TOKEN" ]]; then acquire_benchmark_token; fi
ensure_benchmark_token
export TOKEN

SEED_ORG_ID="11111111-1111-1111-1111-111111111111"
ORG_ID="${PERF_ORG_ID:-}"
if [[ -z "$ORG_ID" ]]; then
  seed_rows="$(psql_value "SELECT count(*) FROM land_parcel WHERE organization_id='${SEED_ORG_ID}'::uuid;" || echo 0)"
  if [[ "$seed_rows" != "0" ]]; then
    ORG_ID="$SEED_ORG_ID"
  else
    ORG_ID="$(psql_value "SELECT organization_id FROM land_parcel GROUP BY organization_id ORDER BY count(*) DESC LIMIT 1;" || true)"
    echo "Warning: benchmark selected organization $ORG_ID; ensure TOKEN belongs to it or set PERF_ORG_ID/TOKEN explicitly." >&2
  fi
fi
if [[ -z "$ORG_ID" ]]; then
  echo "No organization with parcel data found." >&2
  exit 1
fi

CADASTRAL_CODE="$(psql_value "SELECT cadastral_code FROM land_parcel WHERE organization_id='${ORG_ID}'::uuid AND boundary IS NOT NULL ORDER BY updated_at DESC NULLS LAST,cadastral_code LIMIT 1;" || true)"
if [[ -z "$CADASTRAL_CODE" ]]; then
  CADASTRAL_CODE="$(psql_value "SELECT cadastral_code FROM land_parcel WHERE organization_id='${ORG_ID}'::uuid ORDER BY cadastral_code LIMIT 1;" || true)"
fi

OVERVIEW_TILE="$(dense_tile 8)"
DETAIL_TILE="$(dense_tile 12)"
OVERVIEW_X="${OVERVIEW_TILE%,*}"; OVERVIEW_Y="${OVERVIEW_TILE#*,}"
DETAIL_X="${DETAIL_TILE%,*}"; DETAIL_Y="${DETAIL_TILE#*,}"

{
  echo "captured=$(date -Iseconds)"
  echo "label=$LABEL"
  echo "branch=$GIT_BRANCH"
  echo "commit=$(git rev-parse HEAD 2>/dev/null || true)"
  echo "dirty=$(git status --porcelain | wc -l | tr -d ' ')"
  echo "compose_profile=$COMPOSE_PROFILE"
  echo "organization_id=$ORG_ID"
  echo "overview_tile=8/$OVERVIEW_X/$OVERVIEW_Y"
  echo "detail_tile=12/$DETAIL_X/$DETAIL_Y"
  echo "uname=$(uname -a)"
  echo "docker=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
  echo "compose=$(docker compose version --short 2>/dev/null || true)"
} > "$OUT_DIR/system.txt"
host_memory_snapshot before

{
  echo "=== DATASET COUNTS ==="
  "${COMPOSE[@]}" exec -T postgres sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
SELECT current_database() AS database_name, pg_size_pretty(pg_database_size(current_database())) AS database_size;
SELECT 'owner' AS table_name,count(*) AS rows FROM owner WHERE organization_id='${ORG_ID}'::uuid
UNION ALL SELECT 'land_parcel',count(*) FROM land_parcel WHERE organization_id='${ORG_ID}'::uuid
UNION ALL SELECT 'owner_parcel_relation',count(*) FROM owner_parcel_relation WHERE organization_id='${ORG_ID}'::uuid
UNION ALL SELECT 'registry_sync_log',count(*) FROM registry_sync_log WHERE organization_id='${ORG_ID}'::uuid
ORDER BY table_name;
SELECT count(*) FILTER (WHERE boundary IS NOT NULL) AS geometries,
       round(avg(ST_NPoints(boundary))::numeric,1) AS avg_points,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p50_points,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p95_points,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p99_points
FROM land_parcel WHERE organization_id='${ORG_ID}'::uuid AND boundary IS NOT NULL;
SQL
} > "$OUT_DIR/dataset.txt"

# Keep planner tuple/dead-tuple estimates intact. Reset only pg_stat_statements
# when available, then calculate DB/temp/WAL deltas from explicit before/after
# snapshots instead of pg_stat_reset().
printf "SELECT pg_stat_statements_reset();\n" | "${COMPOSE[@]}" exec -T postgres sh -lc \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1 || true

COUNTERS_BEFORE="$(psql_value "SELECT concat_ws('|',xact_commit,xact_rollback,blks_read,blks_hit,temp_files,temp_bytes,(SELECT wal_bytes::bigint FROM pg_stat_wal)) FROM pg_stat_database WHERE datname=current_database();")"

(
  echo 'timestamp,name,cpu_percent,mem_usage,mem_percent,net_io,block_io,pids'
  while true; do
    ts="$(date -Iseconds)"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}' 2>/dev/null \
      | awk -v ts="$ts" -F'|' 'BEGIN{OFS=","} /^forestiq-/ {print ts,$1,$2,$3,$4,$5,$6,$7}'
    sleep 2
  done
) > "$OUT_DIR/docker-stats.csv" &
STATS_PID=$!

printf 'benchmark\tok\ttotal\tavg_ms\tp50_ms\tp95_ms\tp99_ms\tmax_ms\tavg_bytes\tp95_bytes\n' > "$SUMMARY"

# Supported interactive paths first. These are the numbers used for SLO decisions.
benchmark_fixed_url owners_cursor "${BASE_URL}/api/v1/owners/cursor?search=&size=50" "$REQUESTS" "$CONCURRENCY"
benchmark_fixed_url relations_cursor "${BASE_URL}/api/v1/ownership-relations/cursor?search=&size=50" "$REQUESTS" "$CONCURRENCY"
if [[ -n "$CADASTRAL_CODE" ]]; then
  benchmark_fixed_url parcel_summary "${BASE_URL}/api/v1/map/land-parcels/${CADASTRAL_CODE}/summary" "$REQUESTS" "$CONCURRENCY"
fi

DETAIL_BASE="${BASE_URL}/api/v1/map/land-parcels/12/${DETAIL_X}/${DETAIL_Y}.pbf"
benchmark_cold_tile tile_detail_cold "$DETAIL_BASE" "$TILE_REQUESTS" "$TILE_CONCURRENCY"
benchmark_fixed_url tile_detail_warm "${DETAIL_BASE}?v=424242" "$TILE_REQUESTS" "$TILE_CONCURRENCY" 'application/vnd.mapbox-vector-tile'

OVERVIEW_BASE="${BASE_URL}/api/v1/map/land-parcels-overview/8/${OVERVIEW_X}/${OVERVIEW_Y}.pbf"
if endpoint_exists "$OVERVIEW_BASE"; then
  benchmark_cold_tile tile_overview_cold "$OVERVIEW_BASE" "$TILE_REQUESTS" "$TILE_CONCURRENCY"
  benchmark_fixed_url tile_overview_warm "${OVERVIEW_BASE}?v=424242" "$TILE_REQUESTS" "$TILE_CONCURRENCY" 'application/vnd.mapbox-vector-tile'
fi

# Old list endpoints are deliberately excluded from normal measurements because
# their multi-second scans/sorts can generate GBs of temp I/O and contaminate the
# supported-path results. Enable only when benchmarking an older checkout.
if [[ "$BENCHMARK_INCLUDE_LEGACY" == "1" ]]; then
  benchmark_fixed_url owners_legacy "${BASE_URL}/api/v1/owners?search=&page=0&size=50" "$REQUESTS" "$CONCURRENCY"
  benchmark_fixed_url relations_legacy "${BASE_URL}/api/v1/ownership-relations?limit=50" "$REQUESTS" "$CONCURRENCY"
fi

kill "$STATS_PID" >/dev/null 2>&1 || true
wait "$STATS_PID" >/dev/null 2>&1 || true
STATS_PID=""
host_memory_snapshot after

COUNTERS_AFTER="$(psql_value "SELECT concat_ws('|',xact_commit,xact_rollback,blks_read,blks_hit,temp_files,temp_bytes,(SELECT wal_bytes::bigint FROM pg_stat_wal)) FROM pg_stat_database WHERE datname=current_database();")"
IFS='|' read -r BC BR BREAD BHIT BTF BTB BWAL <<< "$COUNTERS_BEFORE"
IFS='|' read -r AC AR AREAD AHIT ATF ATB AWAL <<< "$COUNTERS_AFTER"
BC="${BC:-0}"; BR="${BR:-0}"; BREAD="${BREAD:-0}"; BHIT="${BHIT:-0}"; BTF="${BTF:-0}"; BTB="${BTB:-0}"; BWAL="${BWAL:-0}"
AC="${AC:-0}"; AR="${AR:-0}"; AREAD="${AREAD:-0}"; AHIT="${AHIT:-0}"; ATF="${ATF:-0}"; ATB="${ATB:-0}"; AWAL="${AWAL:-0}"
{
  echo -e 'metric\tbefore\tafter\tdelta'
  echo -e "xact_commit\t$BC\t$AC\t$((AC-BC))"
  echo -e "xact_rollback\t$BR\t$AR\t$((AR-BR))"
  echo -e "blocks_read\t$BREAD\t$AREAD\t$((AREAD-BREAD))"
  echo -e "blocks_hit\t$BHIT\t$AHIT\t$((AHIT-BHIT))"
  echo -e "temp_files\t$BTF\t$ATF\t$((ATF-BTF))"
  echo -e "temp_bytes\t$BTB\t$ATB\t$((ATB-BTB))"
  echo -e "wal_bytes\t$BWAL\t$AWAL\t$((AWAL-BWAL))"
} > "$OUT_DIR/database-delta.tsv"

"${COMPOSE[@]}" exec -T postgres sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$OUT_DIR/database-after.txt" <<'SQL'
\pset pager off
\timing on
SELECT relname AS table_name,n_live_tup,n_dead_tup,seq_scan,idx_scan,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       last_analyze,last_autoanalyze,last_vacuum,last_autovacuum
FROM pg_stat_user_tables
WHERE relname IN ('owner','land_parcel','owner_parcel_relation','registry_sync_log','land_parcel_render','land_parcel_render_owner')
ORDER BY pg_total_relation_size(relid) DESC;
SELECT relname,heap_blks_read,heap_blks_hit,
       CASE WHEN heap_blks_read+heap_blks_hit=0 THEN NULL ELSE round(100.0*heap_blks_hit/(heap_blks_read+heap_blks_hit),2) END AS heap_cache_hit_pct,
       idx_blks_read,idx_blks_hit
FROM pg_statio_user_tables
WHERE relname IN ('owner','land_parcel','owner_parcel_relation','land_parcel_render','land_parcel_render_owner')
ORDER BY relname;
SELECT datname,numbackends,xact_commit,xact_rollback,blks_read,blks_hit,temp_files,
       pg_size_pretty(temp_bytes) AS temp_bytes,blk_read_time,blk_write_time
FROM pg_stat_database WHERE datname=current_database();
SELECT wal_records,wal_fpi,pg_size_pretty(wal_bytes::bigint) AS wal_bytes FROM pg_stat_wal;
SQL

# Workload-specific top statements. Failure is non-fatal when the extension is
# unavailable on an intentionally plain compose profile.
"${COMPOSE[@]}" exec -T postgres sh -lc 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >> "$OUT_DIR/database-after.txt" 2>/dev/null <<'SQL' || true
\echo '=== pg_stat_statements by total time ==='
SELECT calls,round(total_exec_time::numeric,2) AS total_ms,round(mean_exec_time::numeric,2) AS mean_ms,rows,
       left(regexp_replace(query,'[[:space:]]+',' ','g'),220) AS query
FROM pg_stat_statements
ORDER BY total_exec_time DESC LIMIT 10;
\echo '=== pg_stat_statements by mean time (calls >= 3) ==='
SELECT calls,round(total_exec_time::numeric,2) AS total_ms,round(mean_exec_time::numeric,2) AS mean_ms,rows,
       left(regexp_replace(query,'[[:space:]]+',' ','g'),220) AS query
FROM pg_stat_statements
WHERE calls >= 3
ORDER BY mean_exec_time DESC LIMIT 10;
SQL

if [[ -n "$CADASTRAL_CODE" ]]; then
  SAFE_CADASTRAL="${CADASTRAL_CODE//\'/\'\'}"
  "${COMPOSE[@]}" exec -T postgres sh -lc 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$OUT_DIR/query-plans.txt" <<SQL
\\pset pager off
\\timing on
\\echo '=== owner cursor candidate page ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,display_name,updated_at
FROM owner
WHERE organization_id='${ORG_ID}'::uuid
ORDER BY updated_at DESC,id ASC
LIMIT 51;
\\echo '=== ownership relation cursor candidate page ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT owner_id,parcel_id,updated_at
FROM owner_parcel_relation
WHERE organization_id='${ORG_ID}'::uuid
ORDER BY updated_at DESC,owner_id ASC,parcel_id ASC
LIMIT 51;
\\echo '=== tenant cadastral lookup ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,cadastral_code,area_hectares
FROM land_parcel
WHERE organization_id='${ORG_ID}'::uuid AND cadastral_code='${SAFE_CADASTRAL}'
LIMIT 1;
SQL
fi

curl -fsS --max-time 10 -H "Authorization: Bearer $TOKEN" \
  "${BASE_URL}/actuator/metrics/forestiq.map.tile.cache.requests" \
  > "$OUT_DIR/map-cache-metrics.json" 2>/dev/null || true
curl -fsS --max-time 10 -H "Authorization: Bearer $TOKEN" \
  "${BASE_URL}/actuator/metrics/forestiq.map.tile.latency" \
  > "$OUT_DIR/map-latency-metrics.json" 2>/dev/null || true

{
  echo
  echo "ForestIQ benchmark completed."
  echo "Result directory: $OUT_DIR"
  echo "Supported-path results were isolated from legacy stress."
  echo
  column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
  echo
  echo "DB deltas: $OUT_DIR/database-delta.tsv"
  echo "Resource samples: $OUT_DIR/docker-stats.csv"
  echo "Query plans: $OUT_DIR/query-plans.txt"
} | tee "$OUT_DIR/result.txt"
