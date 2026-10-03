#!/bin/sh
set -eu

# Render Postgres exposes a libpq-style connection string. ForestIQ/Spring uses
# JDBC plus separate username/password properties, so strip URI credentials and
# normalize only Render-style URLs. Existing jdbc:postgresql:// URLs are kept.
case "${DATABASE_URL:-}" in
  postgresql://*|postgres://*)
    database_location="${DATABASE_URL#*://}"
    database_location="${database_location#*@}"
    export DATABASE_URL="jdbc:postgresql://${database_location}"
    ;;
esac

keycloak_realm="${KEYCLOAK_REALM:-forestiq}"

# Free Render web services communicate through their public service URLs. Build
# all Spring Security and Keycloak Admin endpoints from that public URL unless a
# more specific environment variable has explicitly been supplied.
if [ -n "${KEYCLOAK_PUBLIC_URL:-}" ]; then
  keycloak_public_url="${KEYCLOAK_PUBLIC_URL%/}"

  if [ -z "${KEYCLOAK_ISSUER_URI:-}" ]; then
    export KEYCLOAK_ISSUER_URI="${keycloak_public_url}/realms/${keycloak_realm}"
  fi

  if [ -z "${KEYCLOAK_JWK_SET_URI:-}" ]; then
    export KEYCLOAK_JWK_SET_URI="${keycloak_public_url}/realms/${keycloak_realm}/protocol/openid-connect/certs"
  fi

  if [ -z "${KEYCLOAK_ADMIN_BASE_URL:-}" ]; then
    export KEYCLOAK_ADMIN_BASE_URL="${keycloak_public_url}"
  fi
fi

# The Render API process only serves HTTP. Avoid eagerly constructing the large
# integration/service graph before the web server can accept traffic, and skip
# Hibernate's duplicate schema validation there. Flyway still validates and
# applies the migration history before the application becomes ready. Scheduler
# and worker roles keep the existing eager startup and Hibernate validation.
if [ "${FORESTIQ_RUNTIME_ROLE:-all}" = "api" ]; then
  set -- \
    --spring.main.lazy-initialization=true \
    --spring.jpa.hibernate.ddl-auto=none \
    "$@"
fi

exec java -XX:MaxRAMPercentage=75.0 -jar /app/app.jar "$@"
