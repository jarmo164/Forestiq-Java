\set ON_ERROR_STOP on

-- Usage examples:
--   psql "$DATABASE_URL" -f scripts/perf/seed_postgis.sql
--   psql "$DATABASE_URL" -v owner_count=250000 -v parcel_count=2000000 -f scripts/perf/seed_postgis.sql
--
-- The fixture is isolated in one deterministic organization. Re-running it
-- replaces only that organization and never touches normal ForestIQ tenants.

\if :{?owner_count}
\else
\set owner_count 50000
\endif

\if :{?parcel_count}
\else
\set parcel_count 100000
\endif

\echo 'ForestIQ performance fixture: owners=' :owner_count ', parcels=' :parcel_count

DELETE FROM organization
 WHERE id = '99999999-9999-9999-9999-999999999999';

INSERT INTO organization (id, name)
VALUES ('99999999-9999-9999-9999-999999999999', 'ForestIQ Performance Fixture');

INSERT INTO owner (
    id, organization_id, display_name, personal_or_registry_code,
    status, assigned_to_subject
)
SELECT
    md5('forestiq-perf-owner-' || series)::uuid,
    '99999999-9999-9999-9999-999999999999'::uuid,
    'Performance Owner ' || series,
    'PERF-' || lpad(series::text, 12, '0'),
    CASE
        WHEN series % 5 = 0 THEN 'CONTACTED'
        WHEN series % 5 = 1 THEN 'INTERESTED'
        WHEN series % 5 = 2 THEN 'NEW'
        WHEN series % 5 = 3 THEN 'ASSIGNED'
        ELSE 'EVALUATION'
    END,
    CASE
        WHEN series % 3 = 0 THEN 'perf-caller-a'
        WHEN series % 3 = 1 THEN 'perf-caller-b'
        ELSE NULL
    END
FROM generate_series(1, :owner_count) AS series;

-- Spread rectangles across an Estonia-sized grid. The generated polygons are
-- deliberately simple; dense-tile tests stress candidate count/index behavior
-- without spending fixture time on artificial high-vertex geometry creation.
INSERT INTO land_parcel (
    id, organization_id, owner_id, cadastral_code, area_hectares,
    county, municipality, marked, boundary
)
SELECT
    md5('forestiq-perf-parcel-' || series)::uuid,
    '99999999-9999-9999-9999-999999999999'::uuid,
    md5('forestiq-perf-owner-' || (((series - 1) % :owner_count) + 1))::uuid,
    'PERF:' || lpad(series::text, 16, '0'),
    1.0000 + (series % 500)::numeric / 10.0,
    'Performance County ' || (series % 15),
    'Performance Municipality ' || (series % 79),
    (series % 20 = 0),
    ST_MakeEnvelope(
        21.50 + (series % 5000) * 0.00035,
        57.50 + ((series / 5000) % 5000) * 0.00018,
        21.50 + (series % 5000) * 0.00035 + 0.00025,
        57.50 + ((series / 5000) % 5000) * 0.00018 + 0.00012,
        4326
    )
FROM generate_series(1, :parcel_count) AS series;

INSERT INTO owner_parcel_relation (
    organization_id, owner_id, parcel_id, source, active,
    source_seen_at, created_at, updated_at
)
SELECT
    '99999999-9999-9999-9999-999999999999'::uuid,
    md5('forestiq-perf-owner-' || (((series - 1) % :owner_count) + 1))::uuid,
    md5('forestiq-perf-parcel-' || series)::uuid,
    'METSIS_PORTFOLIO',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM generate_series(1, :parcel_count) AS series
ON CONFLICT DO NOTHING;

ANALYZE owner;
ANALYZE land_parcel;
ANALYZE owner_parcel_relation;

SELECT 'owners' AS entity, count(*) AS rows
  FROM owner
 WHERE organization_id = '99999999-9999-9999-9999-999999999999'
UNION ALL
SELECT 'parcels', count(*)
  FROM land_parcel
 WHERE organization_id = '99999999-9999-9999-9999-999999999999'
UNION ALL
SELECT 'relations', count(*)
  FROM owner_parcel_relation
 WHERE organization_id = '99999999-9999-9999-9999-999999999999';
