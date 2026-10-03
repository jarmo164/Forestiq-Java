\set ON_ERROR_STOP on

-- E2E owns its organization explicitly; production Flyway migrations do not provide it.
INSERT INTO organization (id, name)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'ForestIQ E2E'),
  ('99999999-9999-9999-9999-999999999999', 'ForestIQ E2E Foreign Tenant')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

\ir e2e-seed.sql
