\set ON_ERROR_STOP on
\if :{?org_id}
\else
\set org_id '11111111-1111-1111-1111-111111111111'
\endif
\set perf_org :'org_id'
\pset pager off
\timing on

-- Resolve real, non-sensitive anchors from the selected tenant. These helper
-- selects are not benchmark plans; only the EXPLAIN statements below are gates.
SELECT updated_at AS deep_updated_at, id AS deep_id
  FROM owner
 WHERE organization_id=:perf_org::uuid
 ORDER BY updated_at DESC,id ASC
 OFFSET LEAST(10000, GREATEST(0,(SELECT count(*)-1 FROM owner WHERE organization_id=:perf_org::uuid)))
 LIMIT 1 \gset

SELECT regexp_replace(split_part(display_name,' ',1),'[^[:alnum:]]','','g') AS owner_term
  FROM owner
 WHERE organization_id=:perf_org::uuid AND length(display_name)>=3
 ORDER BY updated_at DESC LIMIT 1 \gset

SELECT status AS owner_status
  FROM owner
 WHERE organization_id=:perf_org::uuid
 GROUP BY status ORDER BY count(*) DESC LIMIT 1 \gset

SELECT cadastral_code AS parcel_code
  FROM land_parcel
 WHERE organization_id=:perf_org::uuid
 ORDER BY updated_at DESC NULLS LAST,cadastral_code LIMIT 1 \gset

SELECT o.personal_or_registry_code AS relation_term
  FROM owner o
  JOIN owner_parcel_relation r ON r.organization_id=o.organization_id AND r.owner_id=o.id
 WHERE o.organization_id=:perf_org::uuid
 ORDER BY r.updated_at DESC LIMIT 1 \gset

\echo '=== owner first cursor page ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,display_name,personal_or_registry_code,status,updated_at,assigned_to_subject,phone,status_set_at
FROM owner
WHERE organization_id=:perf_org::uuid
ORDER BY updated_at DESC,id ASC
LIMIT 51;

\echo '=== owner text search ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,display_name,personal_or_registry_code,status,updated_at
FROM owner
WHERE organization_id=:perf_org::uuid
  AND search_document @@ websearch_to_tsquery('simple', :'owner_term')
ORDER BY updated_at DESC,id ASC
LIMIT 51;

\echo '=== owner deep keyset page ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,display_name,updated_at
FROM owner
WHERE organization_id=:perf_org::uuid
  AND (updated_at < :'deep_updated_at'::timestamptz
       OR (updated_at=:'deep_updated_at'::timestamptz AND id > :'deep_id'::uuid))
ORDER BY updated_at DESC,id ASC
LIMIT 51;

\echo '=== admin owner filtered search ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,display_name,personal_or_registry_code,status,updated_at,assigned_to_subject
FROM owner
WHERE organization_id=:perf_org::uuid
  AND status=:'owner_status'
  AND search_document @@ websearch_to_tsquery('simple', :'owner_term')
ORDER BY updated_at DESC,id ASC
LIMIT 101;

\echo '=== ownership relation first cursor page ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT owner_id,parcel_id,updated_at
FROM owner_parcel_relation
WHERE organization_id=:perf_org::uuid
ORDER BY updated_at DESC,owner_id ASC,parcel_id ASC
LIMIT 51;

\echo '=== ownership relation searched cursor candidates ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT r.owner_id,r.parcel_id,r.updated_at
FROM owner_parcel_relation r
WHERE r.organization_id=:perf_org::uuid
  AND (
    EXISTS (
      SELECT 1 FROM owner o
       WHERE o.organization_id=r.organization_id AND o.id=r.owner_id
         AND (o.search_document @@ websearch_to_tsquery('simple', :'relation_term')
              OR lower(o.personal_or_registry_code) LIKE lower(:'relation_term')||'%')
    )
    OR EXISTS (
      SELECT 1 FROM land_parcel p
       WHERE p.organization_id=r.organization_id AND p.id=r.parcel_id
         AND (lower(p.cadastral_code) LIKE lower(:'relation_term')||'%'
              OR lower(coalesce(p.name,'')) LIKE lower(:'relation_term')||'%')
    )
  )
ORDER BY r.updated_at DESC,r.owner_id ASC,r.parcel_id ASC
LIMIT 51;

\echo '=== parcel summary/detail ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT p.id,p.cadastral_code,p.name,p.address,p.area_hectares,
       ST_X(ST_PointOnSurface(g.safe_boundary)) AS centroid_longitude,
       ST_Y(ST_PointOnSurface(g.safe_boundary)) AS centroid_latitude,
       ST_XMin(g.safe_boundary) AS bbox_west,
       ST_YMin(g.safe_boundary) AS bbox_south,
       ST_XMax(g.safe_boundary) AS bbox_east,
       ST_YMax(g.safe_boundary) AS bbox_north,
       current_owner.id AS owner_id,current_owner.display_name,current_owner.status
FROM land_parcel p
LEFT JOIN LATERAL (
  SELECT o.id,o.display_name,o.status
  FROM owner_parcel_relation r JOIN owner o
    ON o.organization_id=r.organization_id AND o.id=r.owner_id
  WHERE r.organization_id=p.organization_id AND r.parcel_id=p.id AND r.active=true
  ORDER BY forestiq_owner_status_rank(o.status),r.created_at,o.id
  LIMIT 1
) current_owner ON true
LEFT JOIN LATERAL (
  SELECT CASE WHEN p.boundary IS NULL OR ST_IsEmpty(p.boundary) THEN NULL
              ELSE ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)),3) END AS safe_boundary
) g ON true
WHERE p.organization_id=:perf_org::uuid AND p.cadastral_code=:'parcel_code';

\echo '=== overview preaggregated cell lookup ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT grid_x,grid_y,owner_status_key,parcel_count
FROM land_parcel_overview_cell
WHERE organization_id=:perf_org::uuid
  AND scope_key='ALL'
  AND zoom=8
  AND (tile_x,tile_y)=(
    SELECT tile_x,tile_y FROM land_parcel_overview_cell
     WHERE organization_id=:perf_org::uuid AND scope_key='ALL' AND zoom=8
     GROUP BY tile_x,tile_y ORDER BY sum(parcel_count) DESC LIMIT 1
  )
ORDER BY parcel_count DESC,grid_x,grid_y,owner_status_key
LIMIT 4096;

\echo '=== dense z12 detail tile candidate/encode ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
WITH dense AS (
  SELECT floor((ST_X(centroid_3857)+20037508.342789244)/(40075016.685578488/power(2.0,12)))::int x,
         floor((20037508.342789244-ST_Y(centroid_3857))/(40075016.685578488/power(2.0,12)))::int y
  FROM land_parcel_render WHERE organization_id=:perf_org::uuid
  GROUP BY x,y ORDER BY count(*) DESC LIMIT 1
), tile AS (
  SELECT ST_TileEnvelope(12,d.x,d.y) envelope FROM dense d
), candidates AS (
  SELECT pr.parcel_id,pr.cadastral_code,pr.area_hectares,pr.marked,pr.boundary_3857 render_geometry,
         ro.owner_status,ro.assigned_to_subject
  FROM land_parcel_render pr
  LEFT JOIN land_parcel_render_owner ro
    ON ro.organization_id=pr.organization_id AND ro.parcel_id=pr.parcel_id AND ro.scope_key='ALL'
  CROSS JOIN tile
  WHERE pr.organization_id=:perf_org::uuid AND pr.boundary_3857 && tile.envelope
  ORDER BY pr.parcel_id LIMIT 5000
), mvtgeom AS (
  SELECT c.parcel_id::text id,c.cadastral_code,c.area_hectares,c.marked,c.owner_status,c.assigned_to_subject,
         ST_AsMVTGeom(c.render_geometry,t.envelope,4096,64,true) geom
  FROM candidates c CROSS JOIN tile t
)
SELECT octet_length(ST_AsMVT(mvtgeom,'land_parcel',4096,'geom')),count(*) FROM mvtgeom;

\echo '=== dense z14 detail tile candidate/encode ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
WITH dense AS (
  SELECT floor((ST_X(centroid_3857)+20037508.342789244)/(40075016.685578488/power(2.0,14)))::int x,
         floor((20037508.342789244-ST_Y(centroid_3857))/(40075016.685578488/power(2.0,14)))::int y
  FROM land_parcel_render WHERE organization_id=:perf_org::uuid
  GROUP BY x,y ORDER BY count(*) DESC LIMIT 1
), tile AS (
  SELECT ST_TileEnvelope(14,d.x,d.y) envelope FROM dense d
), candidates AS (
  SELECT pr.parcel_id,pr.cadastral_code,pr.boundary_3857 render_geometry
  FROM land_parcel_render pr CROSS JOIN tile
  WHERE pr.organization_id=:perf_org::uuid AND pr.boundary_3857 && tile.envelope
  ORDER BY pr.parcel_id LIMIT 10000
), mvtgeom AS (
  SELECT parcel_id::text id,cadastral_code,ST_AsMVTGeom(render_geometry,t.envelope,4096,64,true) geom
  FROM candidates CROSS JOIN tile t
)
SELECT octet_length(ST_AsMVT(mvtgeom,'land_parcel',4096,'geom')),count(*) FROM mvtgeom;

\echo '=== integration queue claim ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id
FROM integration_sync_run
WHERE state='QUEUED'
ORDER BY queued_at,id
FOR UPDATE SKIP LOCKED
LIMIT 1;

\echo '=== recent integration jobs ==='
EXPLAIN (ANALYZE,BUFFERS,WAL,SETTINGS)
SELECT id,job_key,state,queued_at,started_at,finished_at
FROM integration_sync_run
WHERE organization_id=:perf_org::uuid
ORDER BY queued_at DESC,id DESC
LIMIT 100;
