#!/usr/bin/env bash
set -Eeuo pipefail

run_id="${GITHUB_RUN_ID:-unknown}"
attempt="${GITHUB_RUN_ATTEMPT:-1}"
compose_project="${COMPOSE_PROJECT_NAME:-forestiqe2e${run_id}${attempt}}"

ci_containers=(
  "forestiq-ingest-it-${run_id}-${attempt}"
  "forestiq-metsaregister-it-${run_id}-${attempt}"
  "forestiq-openapi-it-${run_id}-${attempt}"
)

if command -v docker >/dev/null 2>&1; then
  if [[ -f .env.e2e && -f compose.yaml ]]; then
    docker compose --env-file .env.e2e -f compose.yaml       down --volumes --remove-orphans --rmi local >/dev/null 2>&1 || true
  fi

  for container in "${ci_containers[@]}"; do
    docker rm -f "$container" >/dev/null 2>&1 || true
  done

  while IFS= read -r resource; do
    [[ -n "$resource" ]] && docker rm -f "$resource" >/dev/null 2>&1 || true
  done < <(docker ps -aq --filter "label=com.docker.compose.project=$compose_project")

  while IFS= read -r volume; do
    [[ -n "$volume" ]] && docker volume rm -f "$volume" >/dev/null 2>&1 || true
  done < <(docker volume ls -q --filter "label=com.docker.compose.project=$compose_project")

  while IFS= read -r network; do
    [[ -n "$network" ]] && docker network rm "$network" >/dev/null 2>&1 || true
  done < <(docker network ls -q --filter "label=com.docker.compose.project=$compose_project")

  for image in forestiq-api forestiq-web forestiq-ingest; do
    docker image rm -f "${image}:heavy-${run_id}-${attempt}" >/dev/null 2>&1 || true
  done

  # Only ForestIQ CI-labeled images are eligible for age-based image pruning.
  docker image prune -af \
    --filter "label=org.forestiq.ci=true" \
    --filter "until=168h" >/dev/null 2>&1 || true

  # BuildKit cache lives inside Docker Desktop's VM/disk image and can fill up
  # even when the macOS host filesystem still has plenty of free space.
  # This runner is repository-dedicated, so stale build cache older than one day
  # is safe to evict between CI jobs.
  docker builder prune -af --filter "until=24h" >/dev/null 2>&1 || true

  # Remove stopped ForestIQ E2E leftovers from interrupted/cancelled runs.
  while IFS= read -r container; do
    [[ -n "$container" ]] && docker rm -f "$container" >/dev/null 2>&1 || true
  done < <(docker ps -aq --filter "name=forestiqe2e" --filter "status=exited")

  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    project="$(docker volume inspect "$volume" --format '{{ index .Labels "com.docker.compose.project" }}' 2>/dev/null || true)"
    [[ "$project" == forestiqe2e* ]] && docker volume rm -f "$volume" >/dev/null 2>&1 || true
  done < <(docker volume ls -q)

  while IFS= read -r network; do
    [[ -n "$network" ]] || continue
    project="$(docker network inspect "$network" --format '{{ index .Labels "com.docker.compose.project" }}' 2>/dev/null || true)"
    [[ "$project" == forestiqe2e* ]] && docker network rm "$network" >/dev/null 2>&1 || true
  done < <(docker network ls -q)
fi

rm -f .env.e2e openapi-backend.pid openapi-backend.log
rm -rf   artifacts/e2e   artifacts/metsaregister   ingest/artifacts   frontend/playwright-report   frontend/test-results   frontend/.next   backend/build   ingest/.pytest_cache

echo "ForestIQ self-hosted CI cleanup completed for run ${run_id}/${attempt}."
