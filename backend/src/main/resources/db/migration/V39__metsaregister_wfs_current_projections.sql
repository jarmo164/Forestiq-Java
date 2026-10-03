-- V39: Stable read projections over the provider-global Metsaregister WFS mirror.
--
-- These objects deliberately read only the layer's current_generation_id. They
-- do not filter on the layer state, because a failed refresh must not hide the
-- last-known-good published generation from application reads.
--
-- Provider rows remain global and are never copied per organization. Tenant
-- scoping is applied only when a query starts from a ForestIQ land_parcel.

CREATE OR REPLACE VIEW metsaregister_wfs_current_feature AS
SELECT
    l.layer_name,
    l.title AS layer_title,
    l.current_generation_id AS generation_id,
    f.feature_key,
    f.source_id,
    f.source_hash,
    f.properties,
    f.geometry,
    f.created_at
FROM metsaregister_wfs_layer l
JOIN metsaregister_wfs_feature f
  ON f.layer_name = l.layer_name
 AND f.generation_id = l.current_generation_id
WHERE l.current_generation_id IS NOT NULL;

COMMENT ON VIEW metsaregister_wfs_current_feature IS
    'Last-known-good current provider-global Metsaregister WFS features; no tenant duplication.';

-- Public forest notifications, both current and archived. Provider field names
-- have changed historically, so common typed aliases are exposed while the full
-- properties JSON remains available to consumers.
CREATE OR REPLACE VIEW metsaregister_wfs_notification AS
SELECT
    c.layer_name,
    (c.layer_name = 'metsaregister:teatis_arhiiv') AS archived,
    c.feature_key,
    c.source_id,
    COALESCE(
        NULLIF(c.properties ->> 'katastri_nr', ''),
        NULLIF(c.properties ->> 'katastritunnus', ''),
        NULLIF(c.properties ->> 'katastritunnus_', ''),
        NULLIF(c.properties ->> 'tunnus', '')
    ) AS cadastral_code,
    COALESCE(
        NULLIF(c.properties ->> 'teatise_nr', ''),
        NULLIF(c.properties ->> 'teatis_nr', ''),
        NULLIF(c.properties ->> 'number', ''),
        c.source_id,
        c.feature_key
    ) AS notification_key,
    COALESCE(
        NULLIF(c.properties ->> 'staatus', ''),
        NULLIF(c.properties ->> 'status', '')
    ) AS provider_status,
    c.properties,
    c.geometry,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name IN ('metsaregister:teatis', 'metsaregister:teatis_arhiiv');

-- Forest compartments are the parent entity for stand elements, peculiarities
-- and damages. source_id/feature_key remain available when provider JSON names
-- are absent or change.
CREATE OR REPLACE VIEW metsaregister_wfs_compartment AS
SELECT
    c.feature_key,
    c.source_id,
    COALESCE(NULLIF(c.properties ->> 'sys_id', ''), c.source_id, c.feature_key) AS compartment_id,
    COALESCE(
        NULLIF(c.properties ->> 'katastri_nr', ''),
        NULLIF(c.properties ->> 'katastritunnus', ''),
        NULLIF(c.properties ->> 'tunnus', '')
    ) AS cadastral_code,
    c.properties,
    c.geometry,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name = 'metsaregister:eraldis';

CREATE OR REPLACE VIEW metsaregister_wfs_compartment_element AS
SELECT
    c.feature_key,
    c.source_id,
    COALESCE(
        NULLIF(c.properties ->> 'eraldis_id', ''),
        NULLIF(c.properties ->> 'eraldis_sys_id', ''),
        NULLIF(c.properties ->> 'parent_id', '')
    ) AS compartment_id,
    c.properties,
    c.geometry,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name = 'metsaregister:eraldis_element';

CREATE OR REPLACE VIEW metsaregister_wfs_compartment_peculiarity AS
SELECT
    c.feature_key,
    c.source_id,
    NULLIF(c.properties ->> 'eraldis_id', '') AS compartment_id,
    NULLIF(c.properties ->> 'kood', '') AS code,
    c.properties,
    c.geometry,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name = 'metsaregister:isearasused';

CREATE OR REPLACE VIEW metsaregister_wfs_compartment_damage AS
SELECT
    c.feature_key,
    c.source_id,
    NULLIF(c.properties ->> 'eraldis_id', '') AS compartment_id,
    NULLIF(c.properties ->> 'kood', '') AS code,
    COALESCE(
        NULLIF(c.properties ->> 'kahjustuse_pohjus', ''),
        NULLIF(c.properties ->> 'pohjus', '')
    ) AS cause,
    COALESCE(
        NULLIF(c.properties ->> 'kahjustuse_aste', ''),
        NULLIF(c.properties ->> 'aste', '')
    ) AS severity,
    c.properties,
    c.geometry,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name = 'metsaregister:kahjustused';

-- One classifier contract keeps the four code lists directly joinable without
-- creating tenant-local copies. Consumers can join on classifier_type + code.
CREATE OR REPLACE VIEW metsaregister_wfs_classifier AS
SELECT
    CASE c.layer_name
        WHEN 'metsaregister:kl_kasvukoht' THEN 'KASVUKOHT'
        WHEN 'metsaregister:kl_omandivorm' THEN 'OMANDIVORM'
        WHEN 'metsaregister:kl_rinne' THEN 'RINNE'
        WHEN 'metsaregister:kl_puuliik' THEN 'PUULIIK'
    END AS classifier_type,
    NULLIF(c.properties ->> 'kood', '') AS code,
    COALESCE(
        NULLIF(c.properties ->> 'nimetus', ''),
        NULLIF(c.properties ->> 'nimi', ''),
        NULLIF(c.properties ->> 'kirjeldus', '')
    ) AS label,
    c.feature_key,
    c.source_id,
    c.properties,
    c.source_hash,
    c.generation_id
FROM metsaregister_wfs_current_feature c
WHERE c.layer_name IN (
    'metsaregister:kl_kasvukoht',
    'metsaregister:kl_omandivorm',
    'metsaregister:kl_rinne',
    'metsaregister:kl_puuliik'
);

-- Convenient parent projection with child collections. LATERAL aggregation
-- avoids the N*M*P row explosion caused by joining all child tables together.
CREATE OR REPLACE VIEW metsaregister_wfs_compartment_detail AS
SELECT
    p.feature_key,
    p.source_id,
    p.compartment_id,
    p.cadastral_code,
    p.properties,
    p.geometry,
    p.source_hash,
    p.generation_id,
    COALESCE(e.elements, '[]'::jsonb) AS elements,
    COALESCE(x.peculiarities, '[]'::jsonb) AS peculiarities,
    COALESCE(d.damages, '[]'::jsonb) AS damages
FROM metsaregister_wfs_compartment p
LEFT JOIN LATERAL (
    SELECT jsonb_agg(
        jsonb_build_object(
            'featureKey', e.feature_key,
            'sourceId', e.source_id,
            'properties', e.properties
        ) ORDER BY e.feature_key
    ) AS elements
    FROM metsaregister_wfs_compartment_element e
    WHERE e.compartment_id = p.compartment_id
) e ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(
        jsonb_build_object(
            'featureKey', x.feature_key,
            'sourceId', x.source_id,
            'code', x.code,
            'properties', x.properties
        ) ORDER BY x.feature_key
    ) AS peculiarities
    FROM metsaregister_wfs_compartment_peculiarity x
    WHERE x.compartment_id = p.compartment_id
) x ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(
        jsonb_build_object(
            'featureKey', d.feature_key,
            'sourceId', d.source_id,
            'code', d.code,
            'cause', d.cause,
            'severity', d.severity,
            'properties', d.properties
        ) ORDER BY d.feature_key
    ) AS damages
    FROM metsaregister_wfs_compartment_damage d
    WHERE d.compartment_id = p.compartment_id
) d ON TRUE;

-- Spatial overlays are resolved at query time from the tenant parcel into the
-- global source mirror. The bbox predicate allows the existing GiST index on
-- metsaregister_wfs_feature.geometry to prune candidates before ST_Intersects.
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
    geometry geometry(Geometry, 4326)
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
     AND c.geometry && p.boundary
     AND ST_Intersects(
            c.geometry,
            ST_CollectionExtract(ST_MakeValid(ST_Force2D(p.boundary)), 3)
         )
    WHERE p.organization_id = p_organization_id
      AND p.cadastral_code = p_cadastral_code
      AND p.boundary IS NOT NULL
      AND NOT ST_IsEmpty(p.boundary)
      AND (p_layers IS NULL OR c.layer_name = ANY (p_layers));
$$;

COMMENT ON FUNCTION metsaregister_wfs_features_for_parcel(UUID, TEXT, TEXT[]) IS
    'Tenant-scoped parcel intersection against the global last-known-good Metsaregister WFS mirror.';
