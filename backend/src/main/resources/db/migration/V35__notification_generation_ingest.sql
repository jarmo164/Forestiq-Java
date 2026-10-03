-- Generation-aware nationwide notification ingestion. Small per-cadastre online
-- refreshes may continue to use the OLTP path; large snapshots/backfills use the
-- run/stage model below so publish is set-based, idempotent and recoverable.

ALTER TABLE cadastre_notification
    ADD COLUMN IF NOT EXISTS source_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_external_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_generation UUID,
    ADD COLUMN IF NOT EXISTS source_seen_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS cadastre_notification_source_identity_uq
    ON cadastre_notification (organization_id, source_name, source_external_id)
    WHERE source_name IS NOT NULL AND source_external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS cadastre_notification_source_generation_idx
    ON cadastre_notification (organization_id, source_name, archived, source_generation)
    WHERE source_name IS NOT NULL;

CREATE TABLE notification_import_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    source_name VARCHAR(64) NOT NULL,
    source_checksum VARCHAR(128) NOT NULL,
    archived BOOLEAN NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('STAGING','READY','PUBLISHING','PUBLISHED','FAILED')),
    staged_count BIGINT NOT NULL DEFAULT 0,
    inserted_count BIGINT NOT NULL DEFAULT 0,
    updated_count BIGINT NOT NULL DEFAULT 0,
    unchanged_count BIGINT NOT NULL DEFAULT 0,
    deleted_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    staged_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (organization_id, source_name, source_checksum, archived)
);

CREATE INDEX notification_import_run_recent_idx
    ON notification_import_run (organization_id, source_name, started_at DESC);

CREATE UNLOGGED TABLE notification_import_stage (
    run_id UUID NOT NULL REFERENCES notification_import_run(id) ON DELETE CASCADE,
    source_row BIGINT NOT NULL,
    source_external_id VARCHAR(128) NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL,
    notification_number BIGINT,
    cadastre_subpart_code BIGINT,
    work_code VARCHAR(32),
    decision VARCHAR(512),
    registration_date BIGINT,
    confirmation_date BIGINT,
    archive_date BIGINT,
    archived BOOLEAN NOT NULL,
    area NUMERIC(12,4),
    amount_to_be_cut NUMERIC(12,4),
    source_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, source_external_id)
);

CREATE INDEX notification_import_stage_cadastre_idx
    ON notification_import_stage (run_id, cadastral_code);

CREATE TABLE notification_import_reject (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES notification_import_run(id) ON DELETE CASCADE,
    source_row BIGINT,
    reason VARCHAR(300) NOT NULL,
    sample JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX notification_import_reject_run_idx
    ON notification_import_reject (run_id, id);
