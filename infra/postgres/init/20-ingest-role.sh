#!/bin/sh
set -eu

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${API_DATABASE_USER:?API_DATABASE_USER is required}"
: "${API_DATABASE_PASSWORD:?API_DATABASE_PASSWORD is required}"
: "${INGEST_DATABASE_USER:?INGEST_DATABASE_USER is required}"
: "${INGEST_DATABASE_PASSWORD:?INGEST_DATABASE_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=api_user="$API_DATABASE_USER" \
  --set=api_password="$API_DATABASE_PASSWORD" \
  --set=ingest_user="$INGEST_DATABASE_USER" \
  --set=ingest_password="$INGEST_DATABASE_PASSWORD" <<'EOSQL'
SELECT 'CREATE ROLE forestiq_api NOLOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forestiq_api')\gexec
SELECT 'CREATE ROLE forestiq_ingest NOLOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forestiq_ingest')\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'api_user', :'api_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'api_user')\gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'ingest_user', :'ingest_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'ingest_user')\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'api_user')\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'ingest_user')\gexec

SELECT format('GRANT forestiq_api TO %I', :'api_user')\gexec
SELECT format('GRANT forestiq_ingest TO %I', :'ingest_user')\gexec
EOSQL
