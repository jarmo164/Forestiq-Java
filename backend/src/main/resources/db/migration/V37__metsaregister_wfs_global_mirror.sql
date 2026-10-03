-- V37: Provider-level Metsaregister WFS catalogue and generation-safe source mirror.
--
-- The mirror is deliberately NOT organization-scoped: public national source rows
-- are stored once and projected into tenant-visible application data separately.
-- A new generation is staged completely before it becomes current, so interrupted
-- WFS reads cannot replace the last-known-good layer snapshot.

CREATE TABLE metsaregister_wfs_layer (
    layer_name VARCHAR(160) PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    provider_present BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING_SCHEMA'
        CHECK (state IN ('PENDING_SCHEMA', 'READY', 'SYNCING', 'HEALTHY', 'FAILED', 'UNSUPPORTED', 'SCHEMA_DRIFT')),
    default_crs VARCHAR(64),
    geometry_property VARCHAR(120),
    geometry_type VARCHAR(120),
    identity_property VARCHAR(120),
    sort_property VARCHAR(120),
    schema_fingerprint CHAR(64),
    schema_definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider_number_matched BIGINT CHECK (provider_number_matched IS NULL OR provider_number_matched >= 0),
    current_row_count BIGINT NOT NULL DEFAULT 0 CHECK (current_row_count >= 0),
    current_generation_id UUID,
    last_seen_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE metsaregister_wfs_generation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    layer_name VARCHAR(160) NOT NULL REFERENCES metsaregister_wfs_layer(layer_name) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL DEFAULT 'STAGING'
        CHECK (state IN ('STAGING', 'PUBLISHED', 'FAILED', 'RETIRED')),
    next_start_index BIGINT NOT NULL DEFAULT 0 CHECK (next_start_index >= 0),
    pages_processed INT NOT NULL DEFAULT 0 CHECK (pages_processed >= 0),
    rows_staged BIGINT NOT NULL DEFAULT 0 CHECK (rows_staged >= 0),
    provider_number_matched BIGINT CHECK (provider_number_matched IS NULL OR provider_number_matched >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT metsaregister_wfs_generation_id_layer_unique UNIQUE (id, layer_name)
);

-- Only one resumable staging generation may exist per layer at a time.
CREATE UNIQUE INDEX metsaregister_wfs_generation_one_staging_idx
    ON metsaregister_wfs_generation(layer_name)
    WHERE state = 'STAGING';
CREATE INDEX metsaregister_wfs_generation_layer_state_idx
    ON metsaregister_wfs_generation(layer_name, state, started_at DESC);

CREATE TABLE metsaregister_wfs_feature (
    generation_id UUID NOT NULL,
    layer_name VARCHAR(160) NOT NULL,
    feature_key VARCHAR(320) NOT NULL,
    source_id VARCHAR(320),
    source_hash CHAR(64) NOT NULL,
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    geometry geometry(Geometry, 4326),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, feature_key),
    CONSTRAINT metsaregister_wfs_feature_generation_fk
        FOREIGN KEY (generation_id, layer_name)
        REFERENCES metsaregister_wfs_generation(id, layer_name)
        ON DELETE CASCADE
);

CREATE INDEX metsaregister_wfs_feature_layer_generation_idx
    ON metsaregister_wfs_feature(layer_name, generation_id, feature_key);
CREATE INDEX metsaregister_wfs_feature_source_id_idx
    ON metsaregister_wfs_feature(layer_name, source_id)
    WHERE source_id IS NOT NULL;
CREATE INDEX metsaregister_wfs_feature_geometry_gix
    ON metsaregister_wfs_feature USING GIST (geometry)
    WHERE geometry IS NOT NULL;

-- A layer may only point at a generation that belongs to that same layer.
-- Current generations are switched before old generations are retired/deleted.
ALTER TABLE metsaregister_wfs_layer
    ADD CONSTRAINT metsaregister_wfs_layer_current_generation_fk
    FOREIGN KEY (current_generation_id, layer_name)
    REFERENCES metsaregister_wfs_generation(id, layer_name);

-- Current authoritative public WFS scope from the Keskkonnaportaal catalogue,
-- verified 2026-09-01. Runtime capability discovery will reconcile these rows
-- with GetCapabilities / DescribeFeatureType and surface additions/removals/drift.
INSERT INTO metsaregister_wfs_layer (layer_name, title) VALUES
    ('metsaregister:isearasused', 'Iseärasused'),
    ('metsaregister:kahjustused', 'Kahjustused'),
    ('metsaregister:katsealad', 'Katsealad'),
    ('metsaregister:kl_kasvukoht', 'Klassifikaator: Metsa kasvukohatüübid'),
    ('metsaregister:kl_omandivorm', 'Klassifikaator: Omandivorm'),
    ('metsaregister:kl_rinne', 'Klassifikaator: Puistu rinded'),
    ('metsaregister:kl_puuliik', 'Klassifikaator: Puuliigid'),
    ('metsaregister:eraldis', 'Metsaregistri eraldised'),
    ('metsaregister:eraldis_element', 'Metsaregistri eraldiste puistu koosseis'),
    ('metsaregister:mr__teema_raie_diameeter', 'Raieküpsus: diameeter'),
    ('metsaregister:mr__teema_raie_liik', 'Raieküpsus: liik'),
    ('metsaregister:mr__teema_raie_taius', 'Raieküpsus: täius'),
    ('metsaregister:mr__teema_raie_vanus', 'Raieküpsus: vanus'),
    ('metsaregister:natura_2000_alad', 'Natura 2000 alad'),
    ('metsaregister:yp_kaitsemets', 'ÜP Kaitsemetsad'),
    ('metsaregister:mke_arhiiv', 'Arhiveeritud metsakaitseekspertiisi alad'),
    ('metsaregister:mue', 'Metsa uuendamise ekspertiisid MUE'),
    ('metsaregister:kuusekooreyrask_mke', 'Metsakaitseekspertiisid kuuse-kooreüraski kahjustusega aladel'),
    ('metsaregister:mke', 'Raie soovitusega metsakaitseekspertiisid'),
    ('metsaregister:teatis_arhiiv', 'Arhiveeritud avalikud metsateatised'),
    ('metsaregister:teatis', 'Kehtivad metsateatised')
ON CONFLICT (layer_name) DO NOTHING;
