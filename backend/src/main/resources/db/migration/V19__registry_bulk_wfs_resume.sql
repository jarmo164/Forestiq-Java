-- Durable provider paging state for batched Metsaregister recovery.
-- Live parcel projections are not touched until the whole provider window has
-- been consumed; interrupted runs therefore preserve last-known-good data.
CREATE TABLE registry_bulk_sync_checkpoint (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    registry_name VARCHAR(64) NOT NULL,
    batch_id UUID NOT NULL,
    cadastral_codes JSONB NOT NULL,
    next_start_index BIGINT NOT NULL DEFAULT 0 CHECK (next_start_index >= 0),
    pages_processed INT NOT NULL DEFAULT 0 CHECK (pages_processed >= 0),
    records_staged BIGINT NOT NULL DEFAULT 0 CHECK (records_staged >= 0),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failure_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, registry_name)
);

CREATE TABLE registry_bulk_sync_feature (
    batch_id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    registry_name VARCHAR(64) NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL,
    feature_key VARCHAR(160) NOT NULL,
    source_id VARCHAR(120),
    title VARCHAR(300),
    work_code VARCHAR(32),
    decision VARCHAR(512),
    area NUMERIC(12,4),
    volume NUMERIC(12,4),
    source_hash CHAR(64) NOT NULL,
    PRIMARY KEY (batch_id, cadastral_code, feature_key)
);
CREATE INDEX registry_bulk_sync_feature_batch_idx
    ON registry_bulk_sync_feature (organization_id, registry_name, batch_id, cadastral_code);
