#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/scripts/e2e-compose.defaults"
PROJECT_NAME="forestiq-e2e"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/compose.yaml")
FRONTEND_URL="${PLAYWRIGHT_BASE_URL:-http://127.0.0.1:13000}"
KEYCLOAK_URL="http://127.0.0.1:18180/realms/forestiq-dev"
API_HEALTH_URL="http://127.0.0.1:18080/actuator/health/readiness"
KEEP_STACK="${E2E_KEEP_STACK:-false}"

for command in docker curl pnpm; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "E2E prerequisite failed: '$command' is not installed or not on PATH." >&2
    exit 2
  fi
done

if ! docker info >/dev/null 2>&1; then
  echo "E2E prerequisite failed: Docker daemon is not running." >&2
  exit 2
fi

cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ "$exit_code" -ne 0 ]]; then
    echo "E2E failed. Current service state:" >&2
    "${COMPOSE[@]}" ps >&2 || true
    "${COMPOSE[@]}" logs --tail=80 postgres keycloak keycloak-setup api frontend >&2 || true
  fi
  if [[ "$KEEP_STACK" != "true" ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  else
    echo "E2E_KEEP_STACK=true: isolated stack left running as project '$PROJECT_NAME'."
  fi
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-90}"
  local sleep_seconds="${4:-2}"
  local i

  for ((i=1; i<=attempts; i++)); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      echo "E2E prerequisite ready: $name"
      return 0
    fi
    sleep "$sleep_seconds"
  done

  echo "E2E prerequisite failed: $name did not become ready at $url." >&2
  return 1
}

cd "$ROOT_DIR"

# The project name and alternate host ports isolate E2E containers, networks and
# volumes from a developer's normal ForestIQ stack and data.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build postgres keycloak keycloak-setup minio minio-setup api frontend

wait_for_url "Keycloak" "$KEYCLOAK_URL"
wait_for_url "ForestIQ API" "$API_HEALTH_URL"
wait_for_url "ForestIQ frontend" "$FRONTEND_URL/login"

# Copy both SQL files so psql's relative \ir in e2e-seed-all.sql resolves inside
# the container. The isolated E2E database is recreated for every default run.
"${COMPOSE[@]}" cp "$ROOT_DIR/scripts/e2e-seed.sql" postgres:/tmp/e2e-seed.sql
"${COMPOSE[@]}" cp "$ROOT_DIR/scripts/e2e-seed-all.sql" postgres:/tmp/e2e-seed-all.sql
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U forestiq -d forestiq -f /tmp/e2e-seed-all.sql

echo "Installing deterministic frontend test dependencies..."
pnpm --dir "$ROOT_DIR/frontend" install --frozen-lockfile
pnpm --dir "$ROOT_DIR/frontend" exec playwright install chromium

echo "Running authenticated ForestIQ E2E suite against $FRONTEND_URL ..."
CI="${CI:-true}" PLAYWRIGHT_BASE_URL="$FRONTEND_URL" pnpm --dir "$ROOT_DIR/frontend" run test:e2e:playwright
