-- V60.1: canonical, generation-scoped forestry domain read model.
--
-- V37 metsaregister_wfs_* remains the provider/raw staging mirror.  This migration
-- introduces one logical METSAREGISTER_FORESTRY generation that freezes the exact
-- raw generation chosen for every manifest-approved LOCAL_DOMAIN_DATA layer.  All
-- application/domain reads can therefore use one publication pointer and cannot
-- accidentally combine independently refreshed provider generations.

INSERT INTO geospatial_source
    (source_key, provider, dataset_key, display_name, source_kind, config_key,
     refresh_interval_seconds, freshness_target_seconds,
     expected_source_srid, canonical_srid)
VALUES
    ('METSAREGISTER_FORESTRY', 'METSAREGISTER', 'forestry-domain',
     'Metsaregister — canonical forestry domain', 'WFS',
     'forestiq.registry.metsaregister.wfs-url', 21600, 86400, 4326, 3301)
ON CONFLICT (source_key) DO UPDATE SET
    provider = EXCLUDED.provider,
    dataset_key = EXCLUDED.dataset_key,
    display_name = EXCLUDED.display_name,
    source_kind = EXCLUDED.source_kind,
    config_key = EXCLUDED.config_key,
    refresh_interval_seconds = EXCLUDED.refresh_interval_seconds,
    freshness_target_seconds = EXCLUDED.freshness_target_seconds,
    expected_source_srid = EXCLUDED.expected_source_srid,
    canonical_srid = EXCLUDED.canonical_srid,
    updated_at = CURRENT_TIMESTAMP;

CREATE TABLE forestry_generation_source (
    generation_id UUID NOT NULL,
    source_key VARCHAR(128) NOT NULL DEFAULT 'METSAREGISTER_FORESTRY'
        CHECK (source_key = 'METSAREGISTER_FORESTRY'),
    layer_name VARCHAR(160) NOT NULL,
    mirror_generation_id UUID NOT NULL,
    source_row_count BIGINT NOT NULL CHECK (source_row_count >= 0),
    manifest_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, layer_name),
    UNIQUE (generation_id, layer_name, mirror_generation_id),
    CONSTRAINT forestry_generation_source_generation_fk
        FOREIGN KEY (source_key, generation_id)
        REFERENCES geospatial_generation(source_key, id)
        ON DELETE CASCADE,
    CONSTRAINT forestry_generation_source_mirror_fk
        FOREIGN KEY (mirror_generation_id, layer_name)
        REFERENCES metsaregister_wfs_generation(id, layer_name)
        ON DELETE RESTRICT
);

CREATE INDEX forestry_generation_source_mirror_idx
    ON forestry_generation_source (layer_name, mirror_generation_id);

-- Normalised canonical storage.  Provider JSON is retained as attributes for
-- forensic/detail use, while common business fields are extracted once so API,
-- future MVT and analysis paths do not repeatedly interpret provider-specific JSON.
CREATE TABLE forestry_domain_feature (
    id BIGSERIAL PRIMARY KEY,
    source_key VARCHAR(128) NOT NULL DEFAULT 'METSAREGISTER_FORESTRY'
        CHECK (source_key = 'METSAREGISTER_FORESTRY'),
    generation_id UUID NOT NULL,
    layer_name VARCHAR(160) NOT NULL,
    mirror_generation_id UUID NOT NULL,
    source_feature_key VARCHAR(320) NOT NULL,
    source_id VARCHAR(320),
    source_hash CHAR(64) NOT NULL,
    cadastral_code VARCHAR(64),
    compartment_id VARCHAR(320),
    domain_code VARCHAR(160),
    title VARCHAR(512),
    work_code VARCHAR(128),
    decision VARCHAR(1000),
    provider_status VARCHAR(256),
    notification_number VARCHAR(320),
    cadastre_subpart_code BIGINT,
    damage_code VARCHAR(160),
    registration_date BIGINT,
    confirmation_date BIGINT,
    archive_date BIGINT,
    event_date BIGINT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    area NUMERIC(18, 4),
    amount_to_be_cut NUMERIC(18, 4),
    volume NUMERIC(18, 4),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    geom geometry(Geometry, 3301),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT forestry_domain_feature_generation_fk
        FOREIGN KEY (source_key, generation_id)
        REFERENCES geospatial_generation(source_key, id)
        ON DELETE CASCADE,
    CONSTRAINT forestry_domain_feature_snapshot_fk
        FOREIGN KEY (generation_id, layer_name, mirror_generation_id)
        REFERENCES forestry_generation_source(generation_id, layer_name, mirror_generation_id)
        ON DELETE CASCADE,
    UNIQUE (generation_id, layer_name, source_feature_key),
    CHECK (geom IS NULL OR ST_SRID(geom) = 3301),
    CHECK (geom IS NULL OR NOT ST_IsEmpty(geom))
);

CREATE INDEX forestry_domain_feature_generation_layer_idx
    ON forestry_domain_feature (generation_id, layer_name, id DESC);
CREATE INDEX forestry_domain_feature_cadastral_idx
    ON forestry_domain_feature (generation_id, cadastral_code, layer_name, id DESC)
    WHERE cadastral_code IS NOT NULL;
CREATE INDEX forestry_domain_feature_compartment_idx
    ON forestry_domain_feature (generation_id, compartment_id, layer_name)
    WHERE compartment_id IS NOT NULL;
CREATE INDEX forestry_domain_feature_geom_gist
    ON forestry_domain_feature USING GIST (geom)
    WHERE geom IS NOT NULL;

CREATE OR REPLACE FUNCTION metsaregister_safe_bigint(value TEXT)
RETURNS BIGINT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN value IS NOT NULL AND btrim(value) ~ '^[+-]?[0-9]+$'
        THEN btrim(value)::BIGINT
        ELSE NULL
    END;
$$;

CREATE OR REPLACE FUNCTION metsaregister_safe_numeric(value TEXT)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN value IS NOT NULL
         AND replace(btrim(value), ',', '.') ~ '^[+-]?[0-9]+([.][0-9]+)?$'
        THEN replace(btrim(value), ',', '.')::NUMERIC
        ELSE NULL
    END;
$$;

-- One authoritative current-feature view.  A failed raw refresh cannot move this
-- pointer; only a fully validated composite generation is publishable.
CREATE VIEW forestry_current_feature AS
SELECT
    f.id,
    f.generation_id,
    f.layer_name,
    f.mirror_generation_id,
    f.source_feature_key,
    f.source_id,
    f.source_hash,
    f.cadastral_code,
    f.compartment_id,
    f.domain_code,
    f.title,
    f.work_code,
    f.decision,
    f.provider_status,
    f.notification_number,
    f.cadastre_subpart_code,
    f.damage_code,
    f.registration_date,
    f.confirmation_date,
    f.archive_date,
    f.event_date,
    f.archived,
    f.area,
    f.amount_to_be_cut,
    f.volume,
    f.attributes,
    f.geom,
    g.source_version,
    g.artifact_checksum,
    c.published_at
FROM geospatial_source_current c
JOIN geospatial_generation g
  ON g.source_key = c.source_key
 AND g.id = c.generation_id
 AND g.status = 'PUBLISHED'
JOIN forestry_domain_feature f
  ON f.source_key = c.source_key
 AND f.generation_id = c.generation_id
WHERE c.source_key = 'METSAREGISTER_FORESTRY';

COMMENT ON VIEW forestry_current_feature IS
    'Canonical last-known-good forestry domain rows from one published composite generation.';

CREATE VIEW forestry_current_notification AS
SELECT
    id,
    generation_id,
    layer_name,
    mirror_generation_id,
    source_feature_key,
    source_id,
    cadastral_code,
    notification_number,
    amount_to_be_cut,
    area,
    cadastre_subpart_code,
    damage_code,
    confirmation_date,
    registration_date,
    decision,
    provider_status AS state,
    work_code,
    archived,
    archive_date,
    attributes,
    geom
FROM forestry_current_feature
WHERE layer_name IN ('metsaregister:teatis', 'metsaregister:teatis_arhiiv');

CREATE VIEW forestry_current_compartment AS
SELECT
    id,
    generation_id,
    mirror_generation_id,
    source_feature_key,
    source_id,
    cadastral_code,
    compartment_id,
    cadastre_subpart_code AS subpart_code,
    title,
    work_code,
    decision,
    area,
    volume,
    event_date,
    attributes,
    geom
FROM forestry_current_feature
WHERE layer_name = 'metsaregister:eraldis';

CREATE VIEW forestry_current_compartment_element AS
SELECT * FROM forestry_current_feature
WHERE layer_name = 'metsaregister:eraldis_element';

CREATE VIEW forestry_current_compartment_peculiarity AS
SELECT * FROM forestry_current_feature
WHERE layer_name = 'metsaregister:isearasused';

CREATE VIEW forestry_current_compartment_damage AS
SELECT * FROM forestry_current_feature
WHERE layer_name = 'metsaregister:kahjustused';

CREATE VIEW forestry_current_classifier AS
SELECT
    generation_id,
    CASE layer_name
        WHEN 'metsaregister:kl_kasvukoht' THEN 'KASVUKOHT'
        WHEN 'metsaregister:kl_omandivorm' THEN 'OMANDIVORM'
        WHEN 'metsaregister:kl_rinne' THEN 'RINNE'
        WHEN 'metsaregister:kl_puuliik' THEN 'PUULIIK'
    END AS classifier_type,
    domain_code AS code,
    title AS label,
    source_feature_key,
    source_id,
    attributes
FROM forestry_current_feature
WHERE layer_name IN (
    'metsaregister:kl_kasvukoht',
    'metsaregister:kl_omandivorm',
    'metsaregister:kl_rinne',
    'metsaregister:kl_puuliik'
);

-- Child collections are guaranteed to come from the same composite generation as
-- the parent because every source view resolves through forestry_current_feature.
CREATE VIEW forestry_current_compartment_detail AS
SELECT
    p.id,
    p.generation_id,
    p.source_feature_key,
    p.source_id,
    p.cadastral_code,
    p.compartment_id,
    p.subpart_code,
    p.title,
    p.work_code,
    p.decision,
    p.area,
    p.volume,
    p.event_date,
    p.attributes,
    p.geom,
    COALESCE(e.elements, '[]'::jsonb) AS elements,
    COALESCE(x.peculiarities, '[]'::jsonb) AS peculiarities,
    COALESCE(d.damages, '[]'::jsonb) AS damages
FROM forestry_current_compartment p
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'id', e.id,
        'sourceFeatureKey', e.source_feature_key,
        'sourceId', e.source_id,
        'attributes', e.attributes
    ) ORDER BY e.id) AS elements
    FROM forestry_current_compartment_element e
    WHERE e.generation_id = p.generation_id
      AND e.compartment_id = p.compartment_id
) e ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'id', x.id,
        'sourceFeatureKey', x.source_feature_key,
        'sourceId', x.source_id,
        'code', x.domain_code,
        'attributes', x.attributes
    ) ORDER BY x.id) AS peculiarities
    FROM forestry_current_compartment_peculiarity x
    WHERE x.generation_id = p.generation_id
      AND x.compartment_id = p.compartment_id
) x ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'id', d.id,
        'sourceFeatureKey', d.source_feature_key,
        'sourceId', d.source_id,
        'code', d.domain_code,
        'status', d.provider_status,
        'attributes', d.attributes
    ) ORDER BY d.id) AS damages
    FROM forestry_current_compartment_damage d
    WHERE d.generation_id = p.generation_id
      AND d.compartment_id = p.compartment_id
) d ON TRUE;

-- Canonical rows are immutable after validation.  Failed/staging rows may be
-- cleaned up; retired rows may be cleaned only after the generation-level rollback
-- retention guard below permits removal of the parent generation.
CREATE OR REPLACE FUNCTION guard_forestry_domain_generation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_generation UUID;
    target_status VARCHAR(16);
BEGIN
    target_generation := CASE WHEN TG_OP = 'DELETE' THEN OLD.generation_id ELSE NEW.generation_id END;

    SELECT status INTO target_status
      FROM geospatial_generation
     WHERE id = target_generation
       AND source_key = 'METSAREGISTER_FORESTRY';

    IF TG_OP IN ('INSERT', 'UPDATE') AND target_status <> 'STAGING' THEN
        RAISE EXCEPTION 'Forestry generation % is immutable in status %', target_generation, target_status;
    END IF;

    IF TG_OP = 'DELETE' AND target_status IN ('VALIDATED', 'PUBLISHED') THEN
        RAISE EXCEPTION 'Forestry generation % cannot be deleted in status %', target_generation, target_status;
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_guard_forestry_domain_generation_mutation
BEFORE INSERT OR UPDATE OR DELETE ON forestry_domain_feature
FOR EACH ROW
EXECUTE FUNCTION guard_forestry_domain_generation_mutation();

-- Never let maintenance remove the only retained rollback target.  Once a newer
-- generation has been published there must remain at least one RETIRED known-good
-- generation until another retired generation exists.  PUBLISHED is already
-- protected by geospatial_source_current, but the explicit check gives a clear error.
CREATE OR REPLACE FUNCTION guard_forestry_last_known_good_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.source_key <> 'METSAREGISTER_FORESTRY' THEN
        RETURN OLD;
    END IF;

    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Cannot delete the published forestry generation %', OLD.id;
    END IF;

    IF OLD.status = 'RETIRED'
       AND NOT EXISTS (
           SELECT 1
             FROM geospatial_generation other
            WHERE other.source_key = OLD.source_key
              AND other.id <> OLD.id
              AND other.status = 'RETIRED'
       ) THEN
        RAISE EXCEPTION 'Cannot delete the last known-good forestry rollback generation %', OLD.id;
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_guard_forestry_last_known_good_generation
BEFORE DELETE ON geospatial_generation
FOR EACH ROW
EXECUTE FUNCTION guard_forestry_last_known_good_generation();

COMMENT ON TABLE forestry_generation_source IS
    'Exact raw Metsaregister mirror generation set frozen into one canonical forestry publication.';
COMMENT ON TABLE forestry_domain_feature IS
    'Canonical generation-scoped forestry domain read model in EPSG:3301; V37 remains raw staging only.';
