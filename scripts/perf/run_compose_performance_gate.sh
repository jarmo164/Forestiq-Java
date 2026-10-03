#!/usr/bin/env bash
set -Eeuo pipefail

ENV_FILE="${1:-.env.e2e}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://127.0.0.1:8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-forestiq-dev}"
BENCHMARK_USERNAME="${BENCHMARK_USERNAME:-admin}"
BENCHMARK_PASSWORD="${BENCHMARK_PASSWORD:-forestiq}"
OUT_DIR="${PERFORMANCE_OUT_DIR:-build/perf}"

[[ -f "$ENV_FILE" ]] || { echo "Missing Compose env file: $ENV_FILE" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"

case "$POSTGRES_DB" in
  *e2e*|*perf*) ;;
  *) echo "Refusing performance seed against non-disposable database: $POSTGRES_DB" >&2; exit 2 ;;
esac

dc() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

mkdir -p "$OUT_DIR"
tmp_bin="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_bin"
}
trap cleanup EXIT

# The Python gates intentionally use the standard psql CLI. Route that CLI into
# the already-running disposable Compose PostGIS container so the Mac runner
# does not need a separately installed libpq package.
cat > "$tmp_bin/psql" <<'WRAPPER'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == postgresql://* || "${1:-}" == postgres://* ]]; then
  shift
fi
exec docker compose --env-file "$FORESTIQ_PERF_ENV_FILE" -f "$FORESTIQ_PERF_COMPOSE_FILE"   exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
WRAPPER
chmod +x "$tmp_bin/psql"
export PATH="$tmp_bin:$PATH"
export FORESTIQ_PERF_ENV_FILE="$ENV_FILE"
export FORESTIQ_PERF_COMPOSE_FILE="$COMPOSE_FILE"
export DATABASE_URL="postgresql://disposable/$POSTGRES_DB"

echo "Seeding representative owner/parcel fixture in disposable database $POSTGRES_DB"
python3 scripts/perf/run_query_plan_gate.py   --database-url "$DATABASE_URL"   --seed   --owner-count 250000   --parcel-count 2000000   --runs 5   --output-dir "$OUT_DIR/query-plan"

echo "Seeding representative active geospatial and forestry generations"
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -f scripts/perf/seed_active_geospatial.sql

python3 scripts/perf/run_active_map_plan_gate.py   --database-url "$DATABASE_URL"   --organization-id 99999999-9999-9999-9999-999999999999   --output "$OUT_DIR/active-map-plan-report.json"

echo "Enabling local benchmark password grant in disposable Keycloak"
dc exec -T keycloak bash -lc "
  /opt/keycloak/bin/kcadm.sh config credentials --server http://127.0.0.1:8080 --realm master \
    --user \"\$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"\$KC_BOOTSTRAP_ADMIN_PASSWORD\" >/dev/null
  cid=\$(/opt/keycloak/bin/kcadm.sh get clients -r '$KEYCLOAK_REALM' -q clientId=forestiq-web --fields id \
    | sed -n 's/.*\"id\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p' | head -1)
  test -n \"\$cid\"
  /opt/keycloak/bin/kcadm.sh update \"clients/\$cid\" -r '$KEYCLOAK_REALM' -s directAccessGrantsEnabled=true >/dev/null
"

token_json="$(curl -fsS --max-time 15 -X POST   "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"   -H 'Content-Type: application/x-www-form-urlencoded'   --data-urlencode 'grant_type=password'   --data-urlencode 'client_id=forestiq-web'   --data-urlencode "username=$BENCHMARK_USERNAME"   --data-urlencode "password=$BENCHMARK_PASSWORD")"
FORESTIQ_BENCHMARK_TOKEN="$(printf '%s' "$token_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')"
[[ -n "$FORESTIQ_BENCHMARK_TOKEN" ]] || { echo "Could not acquire disposable benchmark token" >&2; exit 2; }
export FORESTIQ_BENCHMARK_TOKEN

python3 scripts/perf/benchmark_forestry_mvt.py   --base-url "$BASE_URL"   --cold-requests 20   --requests 80   --concurrency 8   --output "$OUT_DIR/forestry-mvt.json"

commit="$(git rev-parse HEAD)"
python3 scripts/perf/performance_evidence.py   --profile performance   --commit "$commit"   --command "compose performance gate: query-plan + active-map + forestry-mvt"   --query-plan "$OUT_DIR/query-plan/query-plan-report.json"   --active-map "$OUT_DIR/active-map-plan-report.json"   --mvt "$OUT_DIR/forestry-mvt.json"   --output "$OUT_DIR/performance-evidence.json"

echo "Performance gate PASS: $OUT_DIR/performance-evidence.json"
