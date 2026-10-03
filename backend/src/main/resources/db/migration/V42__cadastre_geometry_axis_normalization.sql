-- Normalize Maa- ja Ruumiamet cadastral GeoJSON at the ingestion boundary.
--
-- GeoServer/WFS 2.0 axis order is easy to misinterpret for EPSG:4326. ForestIQ
-- keeps raw_feature untouched for diagnostics, while geometry is canonicalized
-- to longitude/latitude EPSG:4326 before a snapshot can be projected into
-- land_parcel. This prevents a provider axis-order mismatch from leaking into
-- canonical geometry and the Web Mercator render read model.

CREATE OR REPLACE FUNCTION forestiq_cadastre_geometry_in_estonia(p_geometry geometry)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
        p_geometry IS NOT NULL
        AND NOT ST_IsEmpty(p_geometry)
        AND ST_SRID(p_geometry) = 4326
        -- Deliberately slightly wider than Estonia's cadastral extent. The
        -- guard is intended to catch CRS/axis mistakes, not clip border data.
        AND ST_XMin(Box2D(p_geometry)) >= 20.5
        AND ST_XMax(Box2D(p_geometry)) <= 29.5
        AND ST_YMin(Box2D(p_geometry)) >= 56.5
        AND ST_YMax(Box2D(p_geometry)) <= 60.5,
        FALSE
    )
$$;

CREATE OR REPLACE FUNCTION forestiq_normalize_cadastre_geojson(p_geometry JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    parsed geometry;
    canonical geometry;
    flipped geometry;
BEGIN
    IF jsonb_typeof(p_geometry) <> 'object' THEN
        RAISE EXCEPTION 'Cadastre geometry must be a GeoJSON object';
    END IF;

    BEGIN
        parsed := ST_Force2D(ST_MakeValid(ST_GeomFromGeoJSON(p_geometry::text)));
    EXCEPTION
        WHEN OTHERS THEN
            RAISE EXCEPTION 'Invalid cadastre GeoJSON: %', SQLERRM;
    END;

    IF parsed IS NULL OR ST_IsEmpty(parsed) THEN
        RAISE EXCEPTION 'Cadastre geometry is empty';
    END IF;

    -- GeoJSON and ForestIQ canonical geometry use x/y = longitude/latitude.
    canonical := ST_SetSRID(parsed, 4326);
    IF forestiq_cadastre_geometry_in_estonia(canonical) THEN
        RETURN ST_AsGeoJSON(canonical, 9, 0)::jsonb;
    END IF;

    -- Maa- ja Ruumiamet WFS 2.0 may expose EPSG:4326 in formal axis order.
    -- If the provider delivered latitude/longitude, normalize it exactly once
    -- at ingestion and keep every downstream consumer on canonical lon/lat.
    flipped := ST_SetSRID(ST_FlipCoordinates(parsed), 4326);
    IF forestiq_cadastre_geometry_in_estonia(flipped) THEN
        RETURN ST_AsGeoJSON(flipped, 9, 0)::jsonb;
    END IF;

    RAISE EXCEPTION
        'Cadastre geometry is outside Estonia after both lon/lat and lat/lon interpretations';
END
$$;

CREATE OR REPLACE FUNCTION forestiq_normalize_cadastre_feature_geometry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_key = 'MAAAMET_CADASTRE' AND NEW.geometry IS NOT NULL THEN
        NEW.geometry := forestiq_normalize_cadastre_geojson(NEW.geometry);
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS external_dataset_feature_cadastre_geometry_normalize
    ON external_dataset_feature;

CREATE TRIGGER external_dataset_feature_cadastre_geometry_normalize
BEFORE INSERT OR UPDATE ON external_dataset_feature
FOR EACH ROW
EXECUTE FUNCTION forestiq_normalize_cadastre_feature_geometry();

COMMENT ON FUNCTION forestiq_normalize_cadastre_geojson(JSONB) IS
    'Normalizes cadastral GeoJSON to EPSG:4326 longitude/latitude, flips a detected WFS axis-order mismatch, and rejects non-Estonian geometry.';

-- Repair already projected parcels that exhibit the exact historical axis swap.
-- Statement-level land_parcel render triggers rebuild boundary_3857/overview data
-- from the corrected canonical geometry, so no frontend workaround is required.
UPDATE land_parcel
   SET boundary = ST_SetSRID(
           ST_FlipCoordinates(ST_Force2D(boundary)),
           4326
       ),
       updated_at = CURRENT_TIMESTAMP
 WHERE boundary IS NOT NULL
   AND NOT forestiq_cadastre_geometry_in_estonia(
       ST_SetSRID(ST_Force2D(boundary), 4326)
   )
   AND forestiq_cadastre_geometry_in_estonia(
       ST_SetSRID(ST_FlipCoordinates(ST_Force2D(boundary)), 4326)
   );

ANALYZE land_parcel;
ANALYZE land_parcel_render;
