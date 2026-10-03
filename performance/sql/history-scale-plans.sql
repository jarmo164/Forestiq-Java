\pset pager off
\timing on

\echo '=== PLAN: parcel compact summary ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT p.id,
       p.cadastral_code,
       p.area_hectares,
       p.forest_area,
       ST_X(ST_PointOnSurface(g.safe_boundary)) AS centroid_longitude,
       ST_Y(ST_PointOnSurface(g.safe_boundary)) AS centroid_latitude,
       current_owner.id AS owner_id,
       current_owner.status
FROM land_parcel p
LEFT JOIN LATERAL (
    SELECT CASE
        WHEN p.boundary IS NULL OR ST_IsEmpty(p.boundary) THEN NULL
        ELSE ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)), 3)
    END AS safe_boundary
) g ON true
LEFT JOIN LATERAL (
    SELECT o.id, o.status
    FROM owner_parcel_relation opr
    JOIN owner o
      ON o.id = opr.owner_id
     AND o.organization_id = opr.organization_id
    WHERE opr.organization_id = p.organization_id
      AND opr.parcel_id = p.id
      AND opr.active = TRUE
    ORDER BY opr.created_at ASC, o.id ASC
    LIMIT 1
) current_owner ON true
WHERE p.organization_id = :'org_id'::uuid
  AND p.cadastral_code = :'cadastre';

\echo '=== PLAN: notification cursor page ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT id, notification_number, area, registration_date, archived, archive_date
FROM cadastre_notification
WHERE organization_id = :'org_id'::uuid
  AND cadastral_code = :'cadastre'
ORDER BY id DESC
LIMIT 51;

\echo '=== PLAN: bounded archived notification time range ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT id, notification_number, registration_date, archive_date
FROM cadastre_notification
WHERE organization_id = :'org_id'::uuid
  AND cadastral_code = :'cadastre'
  AND archived = TRUE
  AND archive_date BETWEEN :archive_from AND :archive_to
ORDER BY id DESC
LIMIT 51;

\echo '=== PLAN: forest feature cursor page ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT id, source_layer, source_id, subpart_code, title, work_code, decision, area, volume, event_date
FROM forest_registry_feature
WHERE organization_id = :'org_id'::uuid
  AND cadastral_code = :'cadastre'
ORDER BY id DESC
LIMIT 51;

\echo '=== PLAN: sub-part cursor page ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
SELECT id, sub_part_code, tree_type_code, area
FROM cadastre_sub_part
WHERE organization_id = :'org_id'::uuid
  AND cadastral_code = :'cadastre'
ORDER BY id DESC
LIMIT 51;

\echo '=== PLAN: low zoom MVT ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_TileEnvelope(7, :z7x, :z7y) AS envelope
), candidates AS (
    SELECT pr.parcel_id, pr.cadastral_code, pr.area_hectares, pr.marked,
           pr.boundary_low_3857 AS render_geometry,
           ro.owner_status, ro.assigned_to_subject
    FROM land_parcel_render pr
    LEFT JOIN land_parcel_render_owner ro
      ON ro.organization_id = pr.organization_id
     AND ro.parcel_id = pr.parcel_id
     AND ro.scope_key = 'ALL'
    CROSS JOIN tile
    WHERE pr.organization_id = :'org_id'::uuid
      AND pr.boundary_3857 && tile.envelope
    ORDER BY pr.parcel_id
    LIMIT 1500
), mvtgeom AS (
    SELECT c.parcel_id::text AS id, c.cadastral_code, c.area_hectares, c.marked,
           c.owner_status, c.assigned_to_subject,
           ST_AsMVTGeom(c.render_geometry, tile.envelope, 4096, 64, true) AS geom
    FROM candidates c CROSS JOIN tile
)
SELECT ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom') FROM mvtgeom;

\echo '=== PLAN: mid zoom MVT ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_TileEnvelope(11, :z11x, :z11y) AS envelope
), candidates AS (
    SELECT pr.parcel_id, pr.cadastral_code, pr.area_hectares, pr.marked,
           pr.boundary_3857 AS render_geometry,
           ro.owner_status, ro.assigned_to_subject
    FROM land_parcel_render pr
    LEFT JOIN land_parcel_render_owner ro
      ON ro.organization_id = pr.organization_id
     AND ro.parcel_id = pr.parcel_id
     AND ro.scope_key = 'ALL'
    CROSS JOIN tile
    WHERE pr.organization_id = :'org_id'::uuid
      AND pr.boundary_3857 && tile.envelope
    ORDER BY pr.parcel_id
    LIMIT 5000
), mvtgeom AS (
    SELECT c.parcel_id::text AS id, c.cadastral_code, c.area_hectares, c.marked,
           c.owner_status, c.assigned_to_subject,
           ST_AsMVTGeom(c.render_geometry, tile.envelope, 4096, 64, true) AS geom
    FROM candidates c CROSS JOIN tile
)
SELECT ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom') FROM mvtgeom;

\echo '=== PLAN: high zoom MVT ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_TileEnvelope(14, :z14x, :z14y) AS envelope
), candidates AS (
    SELECT pr.parcel_id, pr.cadastral_code, pr.area_hectares, pr.marked,
           pr.boundary_3857 AS render_geometry,
           ro.owner_status, ro.assigned_to_subject
    FROM land_parcel_render pr
    LEFT JOIN land_parcel_render_owner ro
      ON ro.organization_id = pr.organization_id
     AND ro.parcel_id = pr.parcel_id
     AND ro.scope_key = 'ALL'
    CROSS JOIN tile
    WHERE pr.organization_id = :'org_id'::uuid
      AND pr.boundary_3857 && tile.envelope
    ORDER BY pr.parcel_id
    LIMIT 10000
), mvtgeom AS (
    SELECT c.parcel_id::text AS id, c.cadastral_code, c.area_hectares, c.marked,
           c.owner_status, c.assigned_to_subject,
           ST_AsMVTGeom(c.render_geometry, tile.envelope, 4096, 64, true) AS geom
    FROM candidates c CROSS JOIN tile
)
SELECT ST_AsMVT(mvtgeom, 'land_parcel', 4096, 'geom') FROM mvtgeom;
