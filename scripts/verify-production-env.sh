#!/usr/bin/env bash
# ForestIQ production preflight. Run only with variables injected by a secret manager:
#   env $(op run --env-file=.env.production -- printenv) ./scripts/verify-production-env.sh
# The script prints variable names only; it never prints secret values.
set -euo pipefail

required=(
  DATABASE_URL
  DATABASE_USERNAME
  DATABASE_PASSWORD
  KEYCLOAK_ISSUER_URI
  KEYCLOAK_JWK_SET_URI
  KEYCLOAK_ADMIN_BASE_URL
  KEYCLOAK_ADMIN_REALM
  KEYCLOAK_ADMIN_CLIENT_SECRET
  CORS_ALLOWED_ORIGINS
  S3_ENDPOINT
  S3_ACCESS_KEY
  S3_SECRET_KEY
  S3_BUCKET
  NEXT_PUBLIC_KEYCLOAK_URL
  NEXT_PUBLIC_KEYCLOAK_REALM
  NEXT_PUBLIC_MAPTILER_KEY
)

missing=()
invalid=()
warnings=()

is_set() {
  [[ -n "${!1:-}" ]]
}

require() {
  if ! is_set "$1"; then
    missing+=("$1")
  fi
}

require_https_url() {
  local name="$1"
  local value="${!name:-}"
  if [[ -n "$value" && ! "$value" =~ ^https://[^[:space:]]+$ ]]; then
    invalid+=("$name must be an https URL")
  fi
}

for variable in "${required[@]}"; do
  require "$variable"
done

for variable in KEYCLOAK_ISSUER_URI KEYCLOAK_JWK_SET_URI KEYCLOAK_ADMIN_BASE_URL S3_ENDPOINT NEXT_PUBLIC_KEYCLOAK_URL; do
  require_https_url "$variable"
done

IFS=',' read -r -a cors_origins <<< "${CORS_ALLOWED_ORIGINS:-}"
for origin in "${cors_origins[@]}"; do
  origin="${origin//[[:space:]]/}"
  [[ -z "$origin" ]] && continue
  if [[ ! "$origin" =~ ^https://[^/]+$ ]]; then
    invalid+=("CORS_ALLOWED_ORIGINS contains a non-HTTPS origin")
    break
  fi
done

check_enabled_worker() {
  local enabled_name="$1"
  shift
  if [[ "${!enabled_name:-false}" != "true" ]]; then
    return
  fi
  for dependency in "$@"; do
    require "$dependency"
  done
}

check_enabled_worker INHERITANCE_SOURCE_AUTO_REFRESH_ENABLED INHERITANCE_SOURCE_BASE_URL
check_enabled_worker METSIS_PORTFOLIO_AUTO_SYNC_ENABLED METSIS_PORTFOLIO_URI METSIS_PORTFOLIO_AUTHORIZATION METSIS_PORTFOLIO_ORGANIZATION_ID
check_enabled_worker WEASEL_AUTO_REFRESH_ENABLED WEASEL_API_BASE_URL WEASEL_API_ACCESS_TOKEN WEASEL_ORGANIZATION_ID

if ! is_set SENTRY_DSN; then
  warnings+=("SENTRY_DSN is not set; production errors will not be forwarded to Sentry")
fi
if [[ "${S3_PATH_STYLE_ACCESS:-false}" == "true" ]]; then
  warnings+=("S3_PATH_STYLE_ACCESS is enabled; confirm that the production object-storage provider requires it")
fi

if ((${#missing[@]} > 0)); then
  printf 'Missing required production variables:\n' >&2
  printf '  - %s\n' "${missing[@]}" >&2
fi
if ((${#invalid[@]} > 0)); then
  printf 'Invalid production configuration:\n' >&2
  printf '  - %s\n' "${invalid[@]}" >&2
fi
if ((${#warnings[@]} > 0)); then
  printf 'Production preflight warnings:\n' >&2
  printf '  - %s\n' "${warnings[@]}" >&2
fi

if ((${#missing[@]} > 0 || ${#invalid[@]} > 0)); then
  exit 1
fi

printf 'ForestIQ production environment preflight passed.\n'
