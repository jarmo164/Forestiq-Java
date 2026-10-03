\set ON_ERROR_STOP on
\if :{?api_password}
\else
  \echo 'api_password psql variable is required'
  \quit 2
\endif
\if :{?ingest_password}
\else
  \echo 'ingest_password psql variable is required'
  \quit 2
\endif
\if :{?keycloak_password}
\else
  \echo 'keycloak_password psql variable is required'
  \quit 2
\endif

SELECT format('CREATE ROLE forestiq_api_login LOGIN PASSWORD %L', :'api_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='forestiq_api_login') \gexec
SELECT format('ALTER ROLE forestiq_api_login LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'api_password') \gexec
GRANT forestiq_api TO forestiq_api_login;
ALTER ROLE forestiq_api_login SET search_path TO public, app, core;

SELECT format('CREATE ROLE forestiq_ingest_login LOGIN PASSWORD %L', :'ingest_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='forestiq_ingest_login') \gexec
SELECT format('ALTER ROLE forestiq_ingest_login LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'ingest_password') \gexec
GRANT forestiq_ingest TO forestiq_ingest_login;
ALTER ROLE forestiq_ingest_login SET search_path TO public, ingest, core, app;

SELECT format('CREATE ROLE forestiq_keycloak_login LOGIN PASSWORD %L', :'keycloak_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='forestiq_keycloak_login') \gexec
SELECT format('ALTER ROLE forestiq_keycloak_login LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'keycloak_password') \gexec
CREATE SCHEMA IF NOT EXISTS keycloak AUTHORIZATION forestiq_keycloak_login;
ALTER SCHEMA keycloak OWNER TO forestiq_keycloak_login;
GRANT USAGE, CREATE ON SCHEMA keycloak TO forestiq_keycloak_login;
ALTER ROLE forestiq_keycloak_login SET search_path TO keycloak;
