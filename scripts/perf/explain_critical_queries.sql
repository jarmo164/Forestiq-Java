\set ON_ERROR_STOP on
\timing off

\if :{?organization_id}
\else
\set organization_id '99999999-9999-9999-9999-999999999999'
\endif

\if :{?tile_z}
\else
\set tile_z 10
\endif
\if :{?tile_x}
\else
\set tile_x 575
\endif
\if :{?tile_y}
\else
\set tile_y 310
\endif

\echo __FORESTIQ_PLAN__:owner_search
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT id, display_name, personal_or_registry_code, status, assigned_to_subject, updated_at
  FROM owner
 WHERE organization_id = :'organization_id'::uuid
   AND search_document @@ plainto_tsquery('simple', 'Performance')
 ORDER BY updated_at DESC, id
 LIMIT 50;

\echo __FORESTIQ_PLAN__:cadastral_lookup
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT id, cadastral_code, area_hectares, boundary
  FROM land_parcel
 WHERE organization_id = :'organization_id'::uuid
   AND cadastral_code = 'PERF:0000000000000001';

\echo __FORESTIQ_PLAN__:protected_mvt
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_TileEnvelope(:tile_z::int, :tile_x::int, :tile_y::int) AS envelope
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
    JOIN LATERAL (
        SELECT o.status, o.assigned_to_subject
        FROM owner_parcel_relation opr
        JOIN owner o
          ON o.id = opr.owner_id
         AND o.organization_id = opr.organization_id
        WHERE opr.organization_id = p.organization_id
          AND opr.parcel_id = p.id
          AND opr.active = true
        ORDER BY opr.created_at ASC, o.id ASC
        LIMIT 1
    ) current_owner ON true,
    bounds
    WHERE p.organization_id = :'organization_id'::uuid
      AND p.boundary IS NOT NULL
      AND p.boundary && bounds.envelope_4326
    ORDER BY p.id
    LIMIT 5000
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
                40_075_016.68557849 / (4096.0 * power(2.0, :tile_z::int))
            ),
            bounds.envelope, 4096, 64, true
        ) AS geom
    FROM candidates c, bounds
)
SELECT octet_length(ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom')) AS tile_bytes,
       count(*) AS feature_count
FROM mvtgeom;

\echo __FORESTIQ_PLAN__:stale_registry
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT p.organization_id, p.cadastral_code
  FROM land_parcel p
 WHERE p.organization_id = :'organization_id'::uuid
   AND NOT EXISTS (
       SELECT 1
         FROM registry_sync_log l
        WHERE l.organization_id = p.organization_id
          AND l.cadastral_code = p.cadastral_code
          AND l.registry_name = 'METSAREGISTER_TEATISED'
          AND l.status = 'SUCCESS'
          AND l.created_at >= CURRENT_TIMESTAMP - INTERVAL '6 hours'
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

\echo __FORESTIQ_PLAN__:durable_queue
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
SELECT id
  FROM integration_sync_run
 WHERE state = 'QUEUED'
 ORDER BY queued_at ASC, id ASC
 LIMIT 1;
