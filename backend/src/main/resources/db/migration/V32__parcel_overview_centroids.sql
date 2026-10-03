-- Lightweight low-zoom map support. Keep individual parcel polygons out of the
-- overview path: one precomputed point per rendered parcel is enough to aggregate
-- a bounded grid tile without request-time transform/simplification.
ALTER TABLE land_parcel_render
    ADD COLUMN IF NOT EXISTS centroid_3857 geometry(Point, 3857);

UPDATE land_parcel_render
   SET centroid_3857 = ST_PointOnSurface(boundary_low_3857)
 WHERE centroid_3857 IS NULL;

ALTER TABLE land_parcel_render
    ALTER COLUMN centroid_3857 SET NOT NULL;

CREATE INDEX IF NOT EXISTS land_parcel_render_centroid_gix
    ON land_parcel_render USING GIST (centroid_3857);

-- Replace the existing refresh function so future canonical parcel changes keep
-- the overview point in the same set-based read-model transaction.
CREATE OR REPLACE FUNCTION refresh_land_parcel_render(
    p_organization_id UUID,
    p_parcel_ids UUID[] DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM land_parcel_render pr
     WHERE pr.organization_id = p_organization_id
       AND (p_parcel_ids IS NULL OR pr.parcel_id = ANY(p_parcel_ids))
       AND NOT EXISTS (
           SELECT 1
             FROM land_parcel p
            WHERE p.organization_id = pr.organization_id
              AND p.id = pr.parcel_id
              AND p.boundary IS NOT NULL
              AND NOT ST_IsEmpty(p.boundary)
       );

    INSERT INTO land_parcel_render
        (organization_id, parcel_id, cadastral_code, area_hectares, marked,
         boundary_3857, boundary_low_3857, centroid_3857,
         source_updated_at, refreshed_at)
    SELECT p.organization_id,
           p.id,
           p.cadastral_code,
           p.area_hectares,
           COALESCE(p.marked, FALSE),
           projected.geom_3857,
           simplified.geom_low,
           ST_PointOnSurface(simplified.geom_low),
           p.updated_at,
           CURRENT_TIMESTAMP
      FROM land_parcel p
      CROSS JOIN LATERAL (
          SELECT ST_Transform(ST_Force2D(p.boundary), 3857) AS geom_3857
      ) projected
      CROSS JOIN LATERAL (
          SELECT ST_SimplifyPreserveTopology(projected.geom_3857, 20.0) AS geom_low
      ) simplified
     WHERE p.organization_id = p_organization_id
       AND p.boundary IS NOT NULL
       AND NOT ST_IsEmpty(p.boundary)
       AND (p_parcel_ids IS NULL OR p.id = ANY(p_parcel_ids))
    ON CONFLICT (organization_id, parcel_id) DO UPDATE SET
        cadastral_code = EXCLUDED.cadastral_code,
        area_hectares = EXCLUDED.area_hectares,
        marked = EXCLUDED.marked,
        boundary_3857 = EXCLUDED.boundary_3857,
        boundary_low_3857 = EXCLUDED.boundary_low_3857,
        centroid_3857 = EXCLUDED.centroid_3857,
        source_updated_at = EXCLUDED.source_updated_at,
        refreshed_at = EXCLUDED.refreshed_at;

    PERFORM refresh_land_parcel_render_owners(p_organization_id, p_parcel_ids);
END
$$;

ANALYZE land_parcel_render;
