-- V21: resumable first-run bootstrap storage for complete external datasets.
-- Public nationwide WFS data is stored once and projected into tenant tables by cadastral code.

CREATE TABLE external_dataset_snapshot_run (
    id UUID PRIMARY KEY,
    source_key VARCHAR(64) NOT NULL,
    source_url TEXT NOT NULL,
    type_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'FAILED', 'COMPLETE')),
    next_start_index BIGINT NOT NULL DEFAULT 0 CHECK (next_start_index >= 0),
    pages_processed BIGINT NOT NULL DEFAULT 0 CHECK (pages_processed >= 0),
    records_staged BIGINT NOT NULL DEFAULT 0 CHECK (records_staged >= 0),
    expected_records BIGINT,
    last_error TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
CREATE INDEX external_dataset_snapshot_run_source_idx
    ON external_dataset_snapshot_run (source_key, started_at DESC);
CREATE UNIQUE INDEX external_dataset_snapshot_run_active_idx
    ON external_dataset_snapshot_run (source_key)
    WHERE status = 'RUNNING';

CREATE TABLE external_dataset_feature (
    source_key VARCHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES external_dataset_snapshot_run(id) ON DELETE CASCADE,
    feature_key VARCHAR(300) NOT NULL,
    source_id VARCHAR(300),
    cadastral_code VARCHAR(32),
    properties JSONB NOT NULL,
    geometry JSONB,
    raw_feature JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_key, snapshot_id, feature_key)
);
CREATE INDEX external_dataset_feature_cadastre_idx
    ON external_dataset_feature (source_key, snapshot_id, cadastral_code);
CREATE INDEX external_dataset_feature_properties_gin
    ON external_dataset_feature USING GIN (properties);

CREATE TABLE external_dataset_current (
    source_key VARCHAR(64) PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES external_dataset_snapshot_run(id) ON DELETE RESTRICT,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Ametlikud Teadaanded is a dated XML feed rather than WFS. Keep every parsed
-- inheritance notice in a shared archive; tenant matching/import remains separate.
CREATE TABLE external_inheritance_notice (
    notice_number VARCHAR(160) PRIMARY KEY,
    feed_date DATE NOT NULL,
    announcement_date DATE,
    source_url TEXT,
    personal_code VARCHAR(32),
    death_date DATE,
    certification_deadline DATE,
    notary_name VARCHAR(300),
    notary_phone VARCHAR(100),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX external_inheritance_notice_feed_date_idx
    ON external_inheritance_notice (feed_date DESC);
CREATE INDEX external_inheritance_notice_personal_code_idx
    ON external_inheritance_notice (personal_code)
    WHERE personal_code IS NOT NULL;
