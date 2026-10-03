#!/usr/bin/env bash
set -Eeuo pipefail

event_name="${1:-}"
base_sha="${2:-}"
head_sha="${3:-HEAD}"
output_file="${GITHUB_OUTPUT:-/dev/stdout}"

backend=false
frontend=false
ingest=false
contract=false
observability=false
ci_config=false
heavy=false
all=false
docs_only=true
performance_profile="none"
required_integration_tests=""
required_performance_evidence=""
changed_files=()

mark_performance() {
  performance_profile="performance"
  required_integration_tests="backend-integration,metsaregister-integration"
  required_performance_evidence="query-plan,mvt"
  heavy=true
}

classify_path() {
  local path="$1"
  changed_files+=("$path")

  case "$path" in
    docs/*|*.md) ;;
    *) docs_only=false ;;
  esac

  case "$path" in
    backend/*)
      backend=true
      [[ "$performance_profile" == "none" ]] && performance_profile="standard"
      ;;
    frontend/*)
      frontend=true
      [[ "$performance_profile" == "none" ]] && performance_profile="standard"
      ;;
    ingest/*|scripts/metsaregister/*)
      ingest=true
      [[ "$performance_profile" == "none" ]] && performance_profile="standard"
      ;;
  esac

  # Fail closed for anything that can alter SQL shape, persistence, map rendering,
  # registry/durable queue behavior, or an unknown production backend class.
  case "$path" in
    backend/src/main/resources/db/migration/*|    backend/src/main/java/*Repository.java|    backend/src/main/java/*/*Repository.java|    backend/src/main/java/ee/forestiq/cadastre/*|    backend/src/main/java/ee/forestiq/geospatial/*|    backend/src/main/java/ee/forestiq/map/*|    backend/src/main/java/ee/forestiq/registry/*|    backend/src/main/java/ee/forestiq/integration/*|    performance/*|scripts/perf/*|config/critical-paths.json)
      mark_performance
      ;;
    backend/src/main/java/*)
      # Unknown/package-moved production Java is deliberately conservative.
      mark_performance
      ;;
  esac

  case "$path" in
    .github/workflows/*|scripts/ci/*|config/ci-workload-ownership.json|scripts/validate-security-workflow.py|scripts/install-self-hosted-runner*.sh|scripts/capture-runner-registration-evidence.sh)
      ci_config=true
      heavy=true
      ;;
    infra/*|compose.yaml|compose.*.yaml|backend/Dockerfile|frontend/Dockerfile|ingest/Dockerfile)
      contract=true
      heavy=true
      ;;
    render.yaml|config/render-env-contract.json|scripts/validate_render_blueprint_contract.py|scripts/test_render_blueprint_contract.py|scripts/test_ingestion_deployment_contract.py|scripts/test_verify_production_ingestion.py|scripts/test_api_ingest_boundary.py|scripts/validate_api_ingest_boundary.py)
      contract=true
      heavy=true
      ;;
    scripts/validate-observability-config.py|observability/*)
      observability=true
      ;;
    backend/*|frontend/*|ingest/*|scripts/metsaregister/*|performance/*|scripts/perf/*|config/critical-paths.json|docs/*|*.md)
      ;;
    *)
      all=true
      ;;
  esac
}

if [[ "$event_name" != "pull_request" || -z "$base_sha" ]]; then
  all=true
  docs_only=false
else
  if [[ -n "${FORESTIQ_CHANGED_FILES:-}" ]]; then
    while IFS= read -r path; do
      [[ -n "$path" ]] && classify_path "$path"
    done <<< "$FORESTIQ_CHANGED_FILES"
  else
    while IFS= read -r path; do
      [[ -n "$path" ]] && classify_path "$path"
    done < <(git diff --name-only "$base_sha" "$head_sha")
  fi
fi

if [[ "$all" == "true" ]]; then
  backend=true
  frontend=true
  ingest=true
  contract=true
  observability=true
  mark_performance
fi

{
  echo "backend=$backend"
  echo "frontend=$frontend"
  echo "ingest=$ingest"
  echo "contract=$contract"
  echo "observability=$observability"
  echo "ci_config=$ci_config"
  echo "heavy=$heavy"
  echo "docs_only=$docs_only"
  echo "performance_profile=$performance_profile"
  echo "required_integration_tests=$required_integration_tests"
  echo "required_performance_evidence=$required_performance_evidence"
  if [[ "$event_name" != "pull_request" || -z "$base_sha" ]]; then
    echo "changed_files=ALL"
  else
    changed_csv="$(IFS=,; echo "${changed_files[*]}")"
    echo "changed_files=$changed_csv"
  fi
} >> "$output_file"

printf 'backend=%s frontend=%s ingest=%s contract=%s observability=%s ci_config=%s heavy=%s docs_only=%s performance_profile=%s required_integration_tests=%s required_performance_evidence=%s changed_files=%s\n'   "$backend" "$frontend" "$ingest" "$contract" "$observability" "$ci_config" "$heavy" "$docs_only"   "$performance_profile" "$required_integration_tests" "$required_performance_evidence"   "$([[ "$event_name" != "pull_request" || -z "$base_sha" ]] && echo ALL || (IFS=,; echo "${changed_files[*]}"))"
