-- V51: generic source/run/generation control plane for Maa- ja Ruumiamet geospatial datasets.
--
-- This intentionally coexists with the legacy external_dataset_* and Metsaregister-specific
-- tables during migration. New cadastre/ETAK/admin ingestion uses this model; old tables are
-- removed only after #242 cutover proves that no consumer still depends on them.

CREATE TABLE geospatial_source (
    source_key VARCHAR(128) PRIMARY KEY,
    provider VARCHAR(128) NOT NULL,
    dataset_key VARCHAR(160) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    source_kind VARCHAR(32) NOT NULL
        CHECK (source_kind IN ('SNAPSHOT', 'WFS', 'TILE', 'SEARCH', 'OTHER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_key VARCHAR(240),
    refresh_interval_seconds BIGINT CHECK (refresh_interval_seconds IS NULL OR refresh_interval_seconds > 0),
    freshness_target_seconds BIGINT CHECK (freshness_target_seconds IS NULL OR freshness_target_seconds > 0),
    expected_source_srid INTEGER CHECK (expected_source_srid IS NULL OR expected_source_srid > 0),
    canonical_srid INTEGER CHECK (canonical_srid IS NULL OR canonical_srid > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, dataset_key)
);

CREATE TABLE geospatial_source_run (
    id UUID PRIMARY KEY,
    source_key VARCHAR(128) NOT NULL REFERENCES geospatial_source(source_key) ON DELETE RESTRICT,
    trigger_type VARCHAR(24) NOT NULL
        CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'RECOVERY', 'BOOTSTRAP')),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED')),
    source_date DATE,
    source_version VARCHAR(300),
    artifact_checksum VARCHAR(128),
    artifact_bytes BIGINT CHECK (artifact_bytes IS NULL OR artifact_bytes >= 0),
    schema_fingerprint VARCHAR(128),
    rows_read BIGINT NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
    rows_staged BIGINT NOT NULL DEFAULT 0 CHECK (rows_staged >= 0),
    rows_inserted BIGINT NOT NULL DEFAULT 0 CHECK (rows_inserted >= 0),
    rows_updated BIGINT NOT NULL DEFAULT 0 CHECK (rows_updated >= 0),
    rows_unchanged BIGINT NOT NULL DEFAULT 0 CHECK (rows_unchanged >= 0),
    rows_removed BIGINT NOT NULL DEFAULT 0 CHECK (rows_removed >= 0),
    rows_rejected BIGINT NOT NULL DEFAULT 0 CHECK (rows_rejected >= 0),
    geometries_repaired BIGINT NOT NULL DEFAULT 0 CHECK (geometries_repaired >= 0),
    error_summary VARCHAR(2000),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX geospatial_source_run_source_created_idx
    ON geospatial_source_run (source_key, created_at DESC);
CREATE INDEX geospatial_source_run_source_status_idx
    ON geospatial_source_run (source_key, status, created_at DESC);
CREATE UNIQUE INDEX geospatial_source_run_active_idx
    ON geospatial_source_run (source_key)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE geospatial_generation (
    id UUID PRIMARY KEY,
    source_key VARCHAR(128) NOT NULL REFERENCES geospatial_source(source_key) ON DELETE RESTRICT,
    run_id UUID NOT NULL UNIQUE REFERENCES geospatial_source_run(id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('STAGING', 'VALIDATED', 'PUBLISHED', 'RETIRED', 'FAILED')),
    source_date DATE,
    source_version VARCHAR(300),
    artifact_checksum VARCHAR(128),
    artifact_bytes BIGINT CHECK (artifact_bytes IS NULL OR artifact_bytes >= 0),
    schema_fingerprint VARCHAR(128),
    source_srid INTEGER CHECK (source_srid IS NULL OR source_srid > 0),
    canonical_srid INTEGER NOT NULL CHECK (canonical_srid > 0),
    row_count BIGINT NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    rejected_row_count BIGINT NOT NULL DEFAULT 0 CHECK (rejected_row_count >= 0),
    repaired_geometry_count BIGINT NOT NULL DEFAULT 0 CHECK (repaired_geometry_count >= 0),
    validation_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_summary VARCHAR(2000),
    UNIQUE (source_key, id),
    CHECK (status <> 'VALIDATED' OR validated_at IS NOT NULL),
    CHECK (status NOT IN ('PUBLISHED', 'RETIRED') OR published_at IS NOT NULL),
    CHECK (status <> 'RETIRED' OR retired_at IS NOT NULL),
    CHECK (status <> 'FAILED' OR failed_at IS NOT NULL)
);

CREATE INDEX geospatial_generation_source_created_idx
    ON geospatial_generation (source_key, created_at DESC);
CREATE INDEX geospatial_generation_source_status_idx
    ON geospatial_generation (source_key, status, created_at DESC);
CREATE UNIQUE INDEX geospatial_generation_published_idx
    ON geospatial_generation (source_key)
    WHERE status = 'PUBLISHED';
-- A successfully validated checksum represents one immutable source snapshot. Failed/staging
-- attempts are intentionally excluded so the same provider artifact can be retried safely.
CREATE UNIQUE INDEX geospatial_generation_source_checksum_idx
    ON geospatial_generation (source_key, artifact_checksum)
    WHERE artifact_checksum IS NOT NULL
      AND status IN ('VALIDATED', 'PUBLISHED', 'RETIRED');

CREATE TABLE geospatial_source_current (
    source_key VARCHAR(128) PRIMARY KEY REFERENCES geospatial_source(source_key) ON DELETE RESTRICT,
    generation_id UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT geospatial_source_current_generation_fk
        FOREIGN KEY (source_key, generation_id)
        REFERENCES geospatial_generation(source_key, id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX geospatial_source_current_generation_idx
    ON geospatial_source_current (generation_id);

CREATE TABLE geospatial_generation_event (
    id BIGSERIAL PRIMARY KEY,
    source_key VARCHAR(128) NOT NULL REFERENCES geospatial_source(source_key) ON DELETE RESTRICT,
    generation_id UUID NOT NULL REFERENCES geospatial_generation(id) ON DELETE RESTRICT,
    run_id UUID REFERENCES geospatial_source_run(id) ON DELETE RESTRICT,
    event_type VARCHAR(24) NOT NULL
        CHECK (event_type IN ('CREATED', 'VALIDATED', 'PUBLISHED', 'RETIRED', 'ROLLED_BACK', 'FAILED')),
    actor_subject VARCHAR(300),
    reason VARCHAR(1000),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX geospatial_generation_event_source_idx
    ON geospatial_generation_event (source_key, occurred_at DESC);
CREATE INDEX geospatial_generation_event_generation_idx
    ON geospatial_generation_event (generation_id, occurred_at DESC);

-- A current pointer may only point at a PUBLISHED generation of the same source.
-- The composite foreign key above protects the source match; this trigger protects status.
CREATE OR REPLACE FUNCTION validate_geospatial_source_current()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM geospatial_generation g
         WHERE g.id = NEW.generation_id
           AND g.source_key = NEW.source_key
           AND g.status = 'PUBLISHED'
    ) THEN
        RAISE EXCEPTION 'Current geospatial generation % for source % must be PUBLISHED',
            NEW.generation_id, NEW.source_key;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_geospatial_source_current
BEFORE INSERT OR UPDATE ON geospatial_source_current
FOR EACH ROW
EXECUTE FUNCTION validate_geospatial_source_current();

-- First authoritative source registration for the new platform. The actual download URL stays
-- in environment/configuration so a provider endpoint change does not require a data migration.
-- The source CRS is deliberately not guessed here: #240 must inspect the GeoPackage metadata and
-- normalize the verified source CRS to the canonical EPSG:3301 contract before publication.
INSERT INTO geospatial_source
    (source_key, provider, dataset_key, display_name, source_kind, config_key,
     refresh_interval_seconds, freshness_target_seconds,
     expected_source_srid, canonical_srid)
VALUES
    ('MARU_CADASTRE', 'MAA_JA_RUUMIAMET', 'cadastre', 'Maa- ja Ruumiamet — kataster',
     'SNAPSHOT', 'forestiq.geospatial.cadastre.snapshot-url',
     86400, 129600, NULL, 3301)
ON CONFLICT (source_key) DO NOTHING;
