-- V56: Store the provider-global Metsaregister WFS mirror in the canonical Estonia CRS (EPSG:3301).
--
-- Provider GeoJSON is requested in EPSG:4326, but ForestIQ's spatial contract uses EPSG:3301
-- for canonical/local storage. Existing rows are transformed in-place, dependent projections
-- are recreated with the new geometry typmod, and tenant parcel overlays transform the parcel
-- boundary at the query boundary before spatial predicates are evaluated.

CREATE TEMP TABLE forestiq_v56_view_restore (
    sequence_no INTEGER NOT NULL,
    view_name TEXT NOT NULL,
    definition TEXT NOT NULL,
    comment_text TEXT
) ON COMMIT DROP;

INSERT INTO forestiq_v56_view_restore (sequence_no, view_name, definition, comment_text)
SELECT ordinality::INTEGER,
       view_name,
       pg_get_viewdef(to_regclass(view_name), TRUE),
       obj_description(to_regclass(view_name), 'pg_class')
FROM unnest(ARRAY[
    'metsaregister_wfs_current_feature',
    'metsaregister_wfs_notification',
    'metsaregister_wfs_compartment',
    'metsaregister_wfs_compartment_element',
    'metsaregister_wfs_compartment_peculiarity',
    'metsaregister_wfs_compartment_damage',
    'metsaregister_wfs_classifier',
    'metsaregister_wfs_compartment_detail',
    'metsaregister_forestry_current_feature',
    'metsaregister_forestry_notification',
    'metsaregister_forestry_compartment',
    'metsaregister_forestry_compartment_element',
    'metsaregister_forestry_compartment_peculiarity',
    'metsaregister_forestry_compartment_damage',
    'metsaregister_forestry_compartment_detail'
]) WITH ORDINALITY AS views(view_name, ordinality)
WHERE to_regclass(view_name) IS NOT NULL;

-- Drop dependent objects explicitly and without CASCADE. If an unaccounted dependency exists,
-- migration fails closed rather than silently deleting it.
DROP FUNCTION IF EXISTS metsaregister_wfs_features_for_parcel(UUID, TEXT, TEXT[]);

DROP VIEW IF EXISTS metsaregister_forestry_compartment_detail;
DROP VIEW IF EXISTS metsaregister_forestry_compartment_damage;
DROP VIEW IF EXISTS metsaregister_forestry_compartment_peculiarity;
DROP VIEW IF EXISTS metsaregister_forestry_compartment_element;
DROP VIEW IF EXISTS metsaregister_forestry_compartment;
DROP VIEW IF EXISTS metsaregister_forestry_notification;
DROP VIEW IF EXISTS metsaregister_forestry_current_feature;

DROP VIEW IF EXISTS metsaregister_wfs_compartment_detail;
DROP VIEW IF EXISTS metsaregister_wfs_classifier;
DROP VIEW IF EXISTS metsaregister_wfs_compartment_damage;
DROP VIEW IF EXISTS metsaregister_wfs_compartment_peculiarity;
DROP VIEW IF EXISTS metsaregister_wfs_compartment_element;
DROP VIEW IF EXISTS metsaregister_wfs_compartment;
DROP VIEW IF EXISTS metsaregister_wfs_notification;
DROP VIEW IF EXISTS metsaregister_wfs_current_feature;

DROP INDEX IF EXISTS metsaregister_wfs_feature_geometry_gix;

ALTER TABLE metsaregister_wfs_feature
    ALTER COLUMN geometry TYPE geometry(Geometry, 3301)
    USING CASE
        WHEN geometry IS NULL THEN NULL
        ELSE ST_Transform(ST_Force2D(geometry), 3301)
    END;

CREATE INDEX metsaregister_wfs_feature_geometry_gix
    ON metsaregister_wfs_feature USING GIST (geometry)
    WHERE geometry IS NOT NULL;

COMMENT ON COLUMN metsaregister_wfs_feature.geometry IS
    'Canonical Metsaregister geometry in EPSG:3301 (L-EST97). Provider GeoJSON is transformed from EPSG:4326 at ingestion.';

UPDATE geospatial_source
SET expected_source_srid = 4326,
    canonical_srid = 3301,
    updated_at = CURRENT_TIMESTAMP
WHERE source_key = 'METSAREGISTER_FORESTRY';

-- The provider-layer member composition is unchanged, but V56 changes the stored geometry CRS
-- for every existing bundle. Keep current/rollback generation metadata truthful after the schema
-- migration so observability and rollback never claim canonical EPSG:4326 for EPSG:3301 rows.
UPDATE geospatial_generation
SET source_srid = 4326,
    canonical_srid = 3301
WHERE source_key = 'METSAREGISTER_FORESTRY';

-- Recreate the pre-existing projections in dependency order. pg_get_viewdef was captured before
-- the base-column type change, so the SQL stays semantically identical while the geometry output
-- now resolves to EPSG:3301.
DO $$
DECLARE
    view_record RECORD;
BEGIN
    FOR view_record IN
        SELECT sequence_no, view_name, definition, comment_text
        FROM forestiq_v56_view_restore
        ORDER BY sequence_no
    LOOP
        EXECUTE format('CREATE VIEW %I AS %s', view_record.view_name, view_record.definition);
        IF view_record.comment_text IS NOT NULL THEN
            EXECUTE format('COMMENT ON VIEW %I IS %L', view_record.view_name, view_record.comment_text);
        END IF;
    END LOOP;
END $$;

-- Tenant parcels remain stored in their existing cadastral CRS (EPSG:4326). Convert the parcel
-- boundary to the canonical forestry CRS before both the GiST bbox predicate and exact intersection.
CREATE OR REPLACE FUNCTION metsaregister_wfs_features_for_parcel(
    p_organization_id UUID,
    p_cadastral_code TEXT,
    p_layers TEXT[] DEFAULT NULL
)
RETURNS TABLE (
    layer_name VARCHAR(160),
    feature_key VARCHAR(320),
    source_id VARCHAR(320),
    source_hash CHAR(64),
    properties JSONB,
    geometry geometry(Geometry, 3301)
)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT
        c.layer_name,
        c.feature_key,
        c.source_id,
        c.source_hash,
        c.properties,
        c.geometry
    FROM land_parcel p
    JOIN metsaregister_wfs_current_feature c
      ON c.geometry IS NOT NULL
     AND c.geometry && ST_Transform(
            ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)), 3),
            3301
         )
     AND ST_Intersects(
            c.geometry,
            ST_Transform(
                ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)), 3),
                3301
            )
         )
    WHERE p.organization_id = p_organization_id
      AND p.cadastral_code = p_cadastral_code
      AND p.boundary IS NOT NULL
      AND NOT ST_IsEmpty(p.boundary)
      AND (p_layers IS NULL OR c.layer_name = ANY (p_layers));
$$;

COMMENT ON FUNCTION metsaregister_wfs_features_for_parcel(UUID, TEXT, TEXT[]) IS
    'Tenant-scoped parcel intersection against canonical EPSG:3301 last-known-good Metsaregister WFS geometry.';
