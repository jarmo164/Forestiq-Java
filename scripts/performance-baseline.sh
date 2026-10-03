#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE=(docker compose -f compose.yaml)
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="performance/baselines"
OUT_FILE="$OUT_DIR/local-diagnostics-$STAMP.local.txt"
mkdir -p "$OUT_DIR"

cat <<'EOF'
Starting/refreshing the normal local PostgreSQL and API containers for optional diagnostics.
This command is not a release gate and has no workstation-specific SLO.
No database contents are deleted.
EOF

"${COMPOSE[@]}" up -d postgres api

printf 'Waiting for PostgreSQL health...\n'
for _ in $(seq 1 60); do
  if "${COMPOSE[@]}" exec -T postgres sh -lc 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

{
  echo "ForestIQ local diagnostic baseline"
  echo "Captured: $(date -Iseconds)"
  echo "NOTE: authoritative performance validation is deferred to production (GitHub #208)."
  echo
  echo "=== DOCKER STATS ==="
  docker stats --no-stream || true
  echo
  echo "=== POSTGRESQL OBSERVABILITY ==="
  "${COMPOSE[@]}" exec -T postgres sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < performance/sql/full-dataset-observability.sql
} | tee "$OUT_FILE"

cat <<EOF

Diagnostic capture written to:
  $OUT_FILE

The .local.txt file can contain internal identifiers/query text. Review and
sanitize it before sharing aggregate findings. Local numbers are diagnostic only;
production telemetry under GitHub #208 is authoritative for performance decisions.
EOF
