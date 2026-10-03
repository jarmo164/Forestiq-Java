\set ON_ERROR_STOP on
\if :{?org_id}
\else
\set org_id '11111111-1111-1111-1111-111111111111'
\endif
\set perf_org :'org_id'

\echo '=== 1. Cadastral lookup ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
SELECT p.id, p.cadastral_code, p.area_hectares, p.updated_at
FROM land_parcel p
WHERE p.organization_id = :perf_org::uuid
  AND p.cadastral_code = '99999:000:0001';

\echo '=== 2. Owner cursor page first, relation enrichment second ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
WITH owner_page AS MATERIALIZED (
    SELECT o.id,
           o.display_name,
           o.personal_or_registry_code,
           o.status,
           o.updated_at,
           o.assigned_to_subject,
           o.phone,
           o.status_set_at
    FROM owner o
    WHERE o.organization_id = :perf_org::uuid
    ORDER BY o.updated_at DESC, o.id ASC
    LIMIT 50
),
parcel_counts AS (
    SELECT opr.owner_id, count(opr.parcel_id) AS parcel_count
    FROM owner_parcel_relation opr
    JOIN owner_page op ON op.id = opr.owner_id
    WHERE opr.organization_id = :perf_org::uuid
      AND opr.active = TRUE
    GROUP BY opr.owner_id
)
SELECT op.id,
       op.display_name,
       op.personal_or_registry_code,
       op.status,
       COALESCE(pc.parcel_count, 0) AS parcel_count,
       op.updated_at
FROM owner_page op
LEFT JOIN parcel_counts pc ON pc.owner_id = op.id
ORDER BY op.updated_at DESC, op.id ASC;

\echo '=== 2b. Owner cursor next-page predicate ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
SELECT o.id, o.display_name, o.updated_at
FROM owner o
WHERE o.organization_id = :perf_org::uuid
  AND (
      o.updated_at < TIMESTAMPTZ '2026-08-01T00:00:00Z'
      OR (o.updated_at = TIMESTAMPTZ '2026-08-01T00:00:00Z'
          AND o.id > '00000000-0000-0000-0000-000000000000'::uuid)
  )
ORDER BY o.updated_at DESC, o.id ASC
LIMIT 51;

\echo '=== 3a. Legacy MVT assembly (same canonical data, before-read-model comparator) ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
WITH tile AS (
    SELECT ST_TileEnvelope(8, 145, 75) AS envelope
),
bounds AS (
    SELECT envelope, ST_Transform(envelope, 4326) AS envelope_4326
    FROM tile
),
candidates AS (
    SELECT
        p.id,
        p.cadastral_code,
        p.area_hectares,
        p.marked,
        p.boundary,
        current_owner.status AS owner_status,
        current_owner.assigned_to_subject
    FROM land_parcel p
    LEFT JOIN LATERAL (
        SELECT o.id, o.status, o.assigned_to_subject
        FROM owner_parcel_relation opr
        JOIN owner o
          ON o.id = opr.owner_id
         AND o.organization_id = opr.organization_id
        WHERE opr.organization_id = p.organization_id
          AND opr.parcel_id = p.id
          AND opr.active = TRUE
        ORDER BY forestiq_owner_status_rank(o.status), opr.created_at ASC, o.id ASC
        LIMIT 1
    ) current_owner ON TRUE,
    bounds
    WHERE p.organization_id = :perf_org::uuid
      AND p.boundary IS NOT NULL
      AND p.boundary && bounds.envelope_4326
    ORDER BY p.id
    LIMIT 1500
),
mvtgeom AS (
    SELECT
        c.id::text AS id,
        c.cadastral_code,
        c.area_hectares,
        c.marked,
        c.owner_status,
        c.assigned_to_subject,
        ST_AsMVTGeom(
            ST_SimplifyPreserveTopology(
                ST_Transform(c.boundary, 3857),
                40_075_016.68557849 / (4096.0 * power(2.0, 8))
            ),
            bounds.envelope, 4096, 64, TRUE
        ) AS geom
    FROM candidates c, bounds
)
SELECT octet_length(ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom')) AS tile_bytes,
       count(*) AS feature_count
FROM mvtgeom;

\echo '=== 3b. Render-model MVT assembly (no transform/simplify/owner LATERAL) ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
WITH tile AS (
    SELECT ST_TileEnvelope(8, 145, 75) AS envelope
),
candidates AS (
    SELECT pr.parcel_id,
           pr.cadastral_code,
           pr.area_hectares,
           pr.marked,
           pr.boundary_low_3857 AS render_geometry,
           ro.owner_status,
           ro.assigned_to_subject
      FROM land_parcel_render pr
      LEFT JOIN land_parcel_render_owner ro
        ON ro.organization_id=pr.organization_id
       AND ro.parcel_id=pr.parcel_id
       AND ro.scope_key='ALL'
      CROSS JOIN tile
     WHERE pr.organization_id=:perf_org::uuid
       AND pr.boundary_3857 && tile.envelope
     ORDER BY pr.parcel_id
     LIMIT 1500
),
mvtgeom AS (
    SELECT c.parcel_id::text AS id,
           c.cadastral_code,
           c.area_hectares,
           c.marked,
           c.owner_status,
           c.assigned_to_subject,
           ST_AsMVTGeom(c.render_geometry, tile.envelope, 4096, 64, TRUE) AS geom
      FROM candidates c
      CROSS JOIN tile
)
SELECT octet_length(ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom')) AS tile_bytes,
       count(*) AS feature_count
FROM mvtgeom;

\echo '=== 4. Stale registry selection ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
SELECT p.organization_id, p.cadastral_code
FROM land_parcel p
WHERE p.organization_id = :perf_org::uuid
  AND NOT EXISTS (
      SELECT 1
      FROM registry_sync_log l
      WHERE l.organization_id = p.organization_id
        AND l.cadastral_code = p.cadastral_code
        AND l.registry_name = 'METSAREGISTER_TEATISED'
        AND l.status = 'SUCCESS'
        AND l.created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
  )
ORDER BY (
    SELECT max(a.created_at)
    FROM registry_sync_log a
    WHERE a.organization_id = p.organization_id
      AND a.cadastral_code = p.cadastral_code
      AND a.registry_name = 'METSAREGISTER_TEATISED'
) ASC NULLS FIRST,
    p.updated_at ASC NULLS FIRST,
    p.cadastral_code ASC
LIMIT 250;

\echo '=== 5. Durable queue claim candidate ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, VERBOSE)
SELECT id
FROM integration_sync_run
WHERE state = 'QUEUED'
ORDER BY queued_at ASC, id ASC
FOR UPDATE SKIP LOCKED
LIMIT 1;
