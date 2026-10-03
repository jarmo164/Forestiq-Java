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

\echo __FORESTIQ_PLAN__:parcel_detail
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH current_generation AS (
    SELECT generation_id
      FROM geospatial_source_current
     WHERE source_key = 'MARU_CADASTRE'
), selected_parcel AS (
    SELECT cadastral_code
      FROM land_parcel
     WHERE organization_id = :'organization_id'::uuid
     ORDER BY updated_at DESC NULLS LAST, cadastral_code
     LIMIT 1
)
SELECT p.id,
       p.cadastral_code,
       p.area_hectares,
       ST_XMin(COALESCE(ST_Transform(gp.geom, 4326), p.boundary)) AS bbox_west,
       (SELECT count(*) FROM cadastre_notification n
         WHERE n.organization_id=p.organization_id AND n.cadastral_code=p.cadastral_code) AS notification_count
  FROM land_parcel p
  JOIN selected_parcel s ON s.cadastral_code=p.cadastral_code
  JOIN current_generation cg ON true
  LEFT JOIN geospatial_cadastre_parcel gp
    ON gp.source_key='MARU_CADASTRE'
   AND gp.generation_id=cg.generation_id
   AND gp.cadastral_code=p.cadastral_code
 WHERE p.organization_id=:'organization_id'::uuid;

\echo __FORESTIQ_PLAN__:overview_unrestricted
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH tile AS (
  SELECT ST_TileEnvelope(:tile_z::int,:tile_x::int,:tile_y::int) AS envelope
)
SELECT count(*)
  FROM land_parcel_render pr
  CROSS JOIN tile
 WHERE pr.organization_id=:'organization_id'::uuid
   AND pr.centroid_3857 && tile.envelope;

\echo __FORESTIQ_PLAN__:overview_restricted
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_Transform(ST_TileEnvelope(:tile_z::int,:tile_x::int,:tile_y::int),3301) AS envelope_3301
), current_generation AS (
    SELECT generation_id
      FROM geospatial_source_current
     WHERE source_key='MARU_CADASTRE'
), scope AS (
    SELECT scope_key
      FROM land_parcel_render_owner
     WHERE organization_id=:'organization_id'::uuid
       AND scope_key LIKE 'SUB:%'
     LIMIT 1
)
SELECT count(*)
  FROM geospatial_cadastre_parcel gp
  JOIN current_generation cg ON cg.generation_id=gp.generation_id
  JOIN land_parcel p
    ON p.organization_id=:'organization_id'::uuid
   AND p.cadastral_code=gp.cadastral_code
  JOIN land_parcel_render_owner ro
    ON ro.organization_id=p.organization_id
   AND ro.parcel_id=p.id
   AND ro.scope_key=COALESCE((SELECT scope_key FROM scope),'SUB:__no_subject__')
  CROSS JOIN tile
 WHERE gp.source_key='MARU_CADASTRE'
   AND gp.geom && tile.envelope_3301;

\echo __FORESTIQ_PLAN__:tile_limit
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH tile AS (
    SELECT ST_Transform(ST_TileEnvelope(:tile_z::int,:tile_x::int,:tile_y::int),3301) AS envelope_3301
), current_generation AS (
    SELECT generation_id
      FROM geospatial_source_current
     WHERE source_key='MARU_CADASTRE'
), bounded_candidates AS (
    SELECT 1
      FROM geospatial_cadastre_parcel gp
      JOIN current_generation cg ON cg.generation_id=gp.generation_id
      JOIN land_parcel p
        ON p.organization_id=:'organization_id'::uuid
       AND p.cadastral_code=gp.cadastral_code
      CROSS JOIN tile
     WHERE gp.source_key='MARU_CADASTRE'
       AND gp.geom && tile.envelope_3301
     LIMIT 6001
)
SELECT count(*) FROM bounded_candidates;

\echo __FORESTIQ_PLAN__:overlay_mvt
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)
WITH bounds AS (
    SELECT ST_TileEnvelope(:tile_z::int,:tile_x::int,:tile_y::int) AS b3857
), current_overlay AS (
    SELECT c.source_key,c.generation_id
      FROM geospatial_source_current c
     WHERE c.source_key <> 'MARU_CADASTRE'
       AND c.source_key <> 'METSAREGISTER_FORESTRY'
       AND EXISTS (
          SELECT 1 FROM geospatial_feature f
           WHERE f.source_key=c.source_key AND f.generation_id=c.generation_id
       )
     ORDER BY c.source_key
     LIMIT 1
), candidates AS (
    SELECT f.feature_id,
           ST_AsMVTGeom(ST_Transform(f.geom,3857),b.b3857,4096,64,true) AS geom
      FROM geospatial_feature f
      JOIN current_overlay o
        ON o.source_key=f.source_key AND o.generation_id=f.generation_id
      CROSS JOIN bounds b
     WHERE f.geom && ST_Transform(b.b3857,3301)
       AND ST_Intersects(f.geom,ST_Transform(b.b3857,3301))
     ORDER BY f.feature_id
     LIMIT 5001
)
SELECT count(*)::int AS feature_count,
       COALESCE(ST_AsMVT(candidates,'context',4096,'geom'),''::bytea) AS tile
  FROM candidates;
