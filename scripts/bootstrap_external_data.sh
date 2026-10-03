#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The bootstrap is a one-shot Spring process. It uses an explicit runtime role
# so normal API/scheduler/worker deployments cannot accidentally execute the
# synchronous external-data import path.
export FORESTIQ_BOOTSTRAP_ENABLED="${FORESTIQ_BOOTSTRAP_ENABLED:-true}"
export FORESTIQ_RUNTIME_ROLE="${FORESTIQ_RUNTIME_ROLE:-bootstrap}"
export FORESTIQ_SCHEDULING_ENABLED="${FORESTIQ_SCHEDULING_ENABLED:-false}"
export SERVER_PORT="${SERVER_PORT:-0}"

# The normal integration uses a deliberately small safety cap. During the first
# bootstrap we must allow the Forestek/MetsIS source-of-truth snapshot to reach
# its natural end instead of stopping after the first 100 pages.
export METSIS_PORTFOLIO_MAX_PAGES_PER_RUN="${METSIS_PORTFOLIO_MAX_PAGES_PER_RUN:-10000}"

# Optional tuning (Spring relaxed binding reads these environment variables):
# FORESTIQ_BOOTSTRAP_ORGANIZATION_ID=<uuid>
# FORESTIQ_BOOTSTRAP_PAGE_SIZE=10000
# FORESTIQ_BOOTSTRAP_MAX_PAGES_PER_DATASET=100000
# FORESTIQ_BOOTSTRAP_MIN_REQUEST_INTERVAL_MS=100
# FORESTIQ_BOOTSTRAP_INHERITANCE_DAYS=365
# FORESTIQ_BOOTSTRAP_REQUIRE_PRIVATE_SOURCES=true
# FORESTIQ_BOOTSTRAP_CLEANUP_PREVIOUS_SNAPSHOTS=true
# FORESTIQ_BOOTSTRAP_LEGACY_PUBLIC_DATASETS_ENABLED=false
# METSIS_PORTFOLIO_MAX_PAGES_PER_RUN=10000

if [[ -n "${FORESTIQ_BOOTSTRAP_JAR:-}" ]]; then
  exec java -jar "${FORESTIQ_BOOTSTRAP_JAR}" --server.port="${SERVER_PORT}"
fi

cd "${ROOT_DIR}/backend"
exec ./gradlew bootRun --args="--server.port=${SERVER_PORT}"
