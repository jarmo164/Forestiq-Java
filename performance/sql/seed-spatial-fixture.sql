\set ON_ERROR_STOP on
\if :{?parcel_count}
\else
\set parcel_count 100000
\endif
\if :{?org_id}
\else
\set org_id '11111111-1111-1111-1111-111111111111'
\endif

-- Default to the development/demo organization so the existing Keycloak admin
-- token can exercise the fixture. Override with -v org_id=... when benchmarking
-- another tenant. Only PERF-* synthetic rows are removed/recreated.
\set perf_org :'org_id'

INSERT INTO organization (id, name)
VALUES (:perf_org::uuid, 'ForestIQ performance fixture')
ON CONFLICT (id) DO NOTHING;

DELETE FROM owner
WHERE organization_id = :perf_org::uuid
  AND personal_or_registry_code LIKE 'PERF-%';
DELETE FROM land_parcel
WHERE organization_id = :perf_org::uuid
  AND cadastral_code LIKE '99999:%';

-- One owner per ten parcels keeps joins realistic without making owner volume
-- identical to parcel volume. IDs are deterministic so the fixture is repeatable.
WITH settings AS (
    SELECT :parcel_count::bigint AS parcel_count
), owners AS (
    SELECT generate_series(1, GREATEST(1, CEIL(parcel_count / 10.0)::bigint)) AS n
    FROM settings
)
INSERT INTO owner (
    id, organization_id, display_name, personal_or_registry_code,
    email, phone, status, assigned_to_subject
)
SELECT
    md5('perf-owner-' || :perf_org || '-' || n)::uuid,
    :perf_org::uuid,
    'Performance Owner ' || n,
    'PERF-' || n,
    'perf-' || n || '@example.test',
    '+3725' || lpad((n % 10000000)::text, 7, '0'),
    CASE WHEN n % 5 = 0 THEN 'EVALUATION' ELSE 'NEW' END,
    CASE WHEN n % 4 = 0 THEN 'perf-caller-b' ELSE 'perf-caller-a' END
FROM owners;

-- Synthetic grid over Estonia-like lon/lat bounds. Geometry is canonical 4326
-- and intentionally small so the GiST bbox + MVT path can be benchmarked.
WITH settings AS (
    SELECT :parcel_count::bigint AS parcel_count,
           CEIL(SQRT(:parcel_count::numeric))::bigint AS grid_width
), parcels AS (
    SELECT gs,
           ((gs - 1) % grid_width) AS gx,
           ((gs - 1) / grid_width) AS gy,
           grid_width
    FROM settings,
         generate_series(1, parcel_count) AS gs
)
INSERT INTO land_parcel (
    id, organization_id, owner_id, cadastral_code, area_hectares,
    boundary, county, municipality, marked
)
SELECT
    md5('perf-parcel-' || :perf_org || '-' || gs)::uuid,
    :perf_org::uuid,
    md5('perf-owner-' || :perf_org || '-' || (((gs - 1) / 10) + 1))::uuid,
    '99999:' || lpad((((gs - 1) / 10000) % 1000)::text, 3, '0') || ':' || lpad(((gs - 1) % 10000 + 1)::text, 4, '0'),
    1.0 + (gs % 250) / 10.0,
    ST_MakeEnvelope(
        23.5 + (gx::double precision / grid_width) * 4.5,
        57.4 + (gy::double precision / grid_width) * 2.2,
        23.5 + ((gx + 0.80)::double precision / grid_width) * 4.5,
        57.4 + ((gy + 0.80)::double precision / grid_width) * 2.2,
        4326
    ),
    CASE WHEN gy % 3 = 0 THEN 'Harjumaa' WHEN gy % 3 = 1 THEN 'Tartumaa' ELSE 'Pärnumaa' END,
    'Performance vald ' || (gy % 25),
    gs % 20 = 0
FROM parcels;

INSERT INTO owner_parcel_relation (
    organization_id, owner_id, parcel_id, source, active,
    source_seen_at, created_at, updated_at
)
SELECT
    p.organization_id,
    p.owner_id,
    p.id,
    'PERFORMANCE_FIXTURE',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM land_parcel p
WHERE p.organization_id = :perf_org::uuid
  AND p.cadastral_code LIKE '99999:%';

ANALYZE owner;
ANALYZE land_parcel;
ANALYZE owner_parcel_relation;

SELECT 'owners' AS dataset, count(*) AS rows
FROM owner WHERE organization_id = :perf_org::uuid AND personal_or_registry_code LIKE 'PERF-%'
UNION ALL
SELECT 'parcels', count(*)
FROM land_parcel WHERE organization_id = :perf_org::uuid AND cadastral_code LIKE '99999:%'
UNION ALL
SELECT 'relations', count(*)
FROM owner_parcel_relation WHERE organization_id = :perf_org::uuid AND source = 'PERFORMANCE_FIXTURE';
