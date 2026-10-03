-- V55: Atomic ForestIQ forestry generations over the retained Metsaregister WFS source mirror.
--
-- Provider layer generations remain immutable staging/source artifacts. A ForestIQ forestry
-- generation is an atomic bundle of those layer generations and is the only generation identity
-- exposed to new domain/map consumers. Publication uses the shared geospatial source control plane,
-- so switching or rolling back one pointer changes every canonical forestry read at once.

INSERT INTO geospatial_source
    (source_key, provider, dataset_key, display_name, source_kind, config_key,
     refresh_interval_seconds, freshness_target_seconds, expected_source_srid, canonical_srid)
VALUES
    ('METSAREGISTER_FORESTRY', 'METSAREGISTER', 'forestry-local-domain',
     'Metsaregister — ForestIQ canonical forestry domain', 'OTHER',
     'metsaregister-layer-manifest.json', 21600, 86400, 4326, 4326)
ON CONFLICT (source_key) DO NOTHING;

CREATE TABLE metsaregister_forestry_generation_member (
    source_key VARCHAR(128) NOT NULL DEFAULT 'METSAREGISTER_FORESTRY'
        CHECK (source_key = 'METSAREGISTER_FORESTRY'),
    forestry_generation_id UUID NOT NULL,
    layer_name VARCHAR(160) NOT NULL,
    layer_generation_id UUID NOT NULL,
    row_count BIGINT NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (forestry_generation_id, layer_name),
    CONSTRAINT metsaregister_forestry_generation_fk
        FOREIGN KEY (source_key, forestry_generation_id)
        REFERENCES geospatial_generation(source_key, id)
        ON DELETE RESTRICT,
    CONSTRAINT metsaregister_forestry_layer_generation_fk
        FOREIGN KEY (layer_generation_id, layer_name)
        REFERENCES metsaregister_wfs_generation(id, layer_name)
        ON DELETE RESTRICT
);

CREATE INDEX metsaregister_forestry_member_layer_idx
    ON metsaregister_forestry_generation_member(layer_name, layer_generation_id);

COMMENT ON TABLE metsaregister_forestry_generation_member IS
    'Atomic mapping from one published ForestIQ forestry generation to immutable provider-layer generations. ON DELETE RESTRICT preserves rollback generations.';

CREATE OR REPLACE VIEW metsaregister_forestry_current_feature AS
SELECT
    current.generation_id AS forestry_generation_id,
    member.layer_name,
    member.layer_generation_id,
    feature.feature_key,
    feature.source_id,
    feature.source_hash,
    feature.properties,
    feature.geometry,
    feature.created_at
FROM geospatial_source_current current
JOIN metsaregister_forestry_generation_member member
  ON member.source_key = current.source_key
 AND member.forestry_generation_id = current.generation_id
JOIN metsaregister_wfs_feature feature
  ON feature.layer_name = member.layer_name
 AND feature.generation_id = member.layer_generation_id
WHERE current.source_key = 'METSAREGISTER_FORESTRY';

COMMENT ON VIEW metsaregister_forestry_current_feature IS
    'Canonical last-known-good forestry rows. Every row in one response resolves through the same published ForestIQ forestry generation.';

CREATE OR REPLACE VIEW metsaregister_forestry_notification AS
SELECT
    c.forestry_generation_id,
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
    COALESCE(NULLIF(c.properties ->> 'staatus', ''), NULLIF(c.properties ->> 'status', '')) AS provider_status,
    c.properties,
    c.geometry,
    c.source_hash
FROM metsaregister_forestry_current_feature c
WHERE c.layer_name IN ('metsaregister:teatis', 'metsaregister:teatis_arhiiv');

CREATE OR REPLACE VIEW metsaregister_forestry_compartment AS
SELECT
    c.forestry_generation_id,
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
    c.source_hash
FROM metsaregister_forestry_current_feature c
WHERE c.layer_name = 'metsaregister:eraldis';

CREATE OR REPLACE VIEW metsaregister_forestry_compartment_element AS
SELECT
    c.forestry_generation_id,
    c.feature_key,
    c.source_id,
    COALESCE(
        NULLIF(c.properties ->> 'eraldis_id', ''),
        NULLIF(c.properties ->> 'eraldis_sys_id', ''),
        NULLIF(c.properties ->> 'parent_id', '')
    ) AS compartment_id,
    c.properties,
    c.geometry,
    c.source_hash
FROM metsaregister_forestry_current_feature c
WHERE c.layer_name = 'metsaregister:eraldis_element';

CREATE OR REPLACE VIEW metsaregister_forestry_compartment_peculiarity AS
SELECT
    c.forestry_generation_id,
    c.feature_key,
    c.source_id,
    NULLIF(c.properties ->> 'eraldis_id', '') AS compartment_id,
    NULLIF(c.properties ->> 'kood', '') AS code,
    c.properties,
    c.geometry,
    c.source_hash
FROM metsaregister_forestry_current_feature c
WHERE c.layer_name = 'metsaregister:isearasused';

CREATE OR REPLACE VIEW metsaregister_forestry_compartment_damage AS
SELECT
    c.forestry_generation_id,
    c.feature_key,
    c.source_id,
    NULLIF(c.properties ->> 'eraldis_id', '') AS compartment_id,
    NULLIF(c.properties ->> 'kood', '') AS code,
    COALESCE(NULLIF(c.properties ->> 'kahjustuse_pohjus', ''), NULLIF(c.properties ->> 'pohjus', '')) AS cause,
    COALESCE(NULLIF(c.properties ->> 'kahjustuse_aste', ''), NULLIF(c.properties ->> 'aste', '')) AS severity,
    c.properties,
    c.geometry,
    c.source_hash
FROM metsaregister_forestry_current_feature c
WHERE c.layer_name = 'metsaregister:kahjustused';

CREATE OR REPLACE VIEW metsaregister_forestry_compartment_detail AS
SELECT
    parent.forestry_generation_id,
    parent.feature_key,
    parent.source_id,
    parent.compartment_id,
    parent.cadastral_code,
    parent.properties,
    parent.geometry,
    parent.source_hash,
    COALESCE(elements.items, '[]'::jsonb) AS elements,
    COALESCE(peculiarities.items, '[]'::jsonb) AS peculiarities,
    COALESCE(damages.items, '[]'::jsonb) AS damages
FROM metsaregister_forestry_compartment parent
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'featureKey', child.feature_key,
        'sourceId', child.source_id,
        'properties', child.properties
    ) ORDER BY child.feature_key) AS items
    FROM metsaregister_forestry_compartment_element child
    WHERE child.forestry_generation_id = parent.forestry_generation_id
      AND child.compartment_id = parent.compartment_id
) elements ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'featureKey', child.feature_key,
        'sourceId', child.source_id,
        'code', child.code,
        'properties', child.properties
    ) ORDER BY child.feature_key) AS items
    FROM metsaregister_forestry_compartment_peculiarity child
    WHERE child.forestry_generation_id = parent.forestry_generation_id
      AND child.compartment_id = parent.compartment_id
) peculiarities ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'featureKey', child.feature_key,
        'sourceId', child.source_id,
        'code', child.code,
        'cause', child.cause,
        'severity', child.severity,
        'properties', child.properties
    ) ORDER BY child.feature_key) AS items
    FROM metsaregister_forestry_compartment_damage child
    WHERE child.forestry_generation_id = parent.forestry_generation_id
      AND child.compartment_id = parent.compartment_id
) damages ON TRUE;

-- Dual-read comparison used during cutover. A zero delta means canonical and legacy-current
-- paths see the same source generation/row volume for a layer. No organization data is copied.
CREATE OR REPLACE VIEW metsaregister_forestry_cutover_comparison AS
WITH legacy AS (
    SELECT l.layer_name, l.current_generation_id, l.current_row_count
      FROM metsaregister_wfs_layer l
     WHERE l.consumption_mode = 'LOCAL_DOMAIN_DATA'
       AND l.approved = TRUE
       AND l.enabled = TRUE
), canonical AS (
    SELECT member.layer_name,
           member.layer_generation_id,
           member.row_count,
           member.forestry_generation_id
      FROM geospatial_source_current current
      JOIN metsaregister_forestry_generation_member member
        ON member.source_key = current.source_key
       AND member.forestry_generation_id = current.generation_id
     WHERE current.source_key = 'METSAREGISTER_FORESTRY'
)
SELECT
    legacy.layer_name,
    canonical.forestry_generation_id,
    legacy.current_generation_id AS legacy_generation_id,
    canonical.layer_generation_id AS canonical_layer_generation_id,
    legacy.current_row_count AS legacy_row_count,
    canonical.row_count AS canonical_row_count,
    (legacy.current_generation_id = canonical.layer_generation_id) AS generation_matches,
    (legacy.current_row_count = canonical.row_count) AS row_count_matches
FROM legacy
LEFT JOIN canonical USING (layer_name);
