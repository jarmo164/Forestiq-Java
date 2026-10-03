-- V62: shared PostgreSQL queue contract for Spring enqueue and Python claim/complete.
--
-- This extends the existing integration_sync_run durable queue instead of adding
-- RabbitMQ/Kafka/Redis as another broker. Python workers claim rows directly
-- with FOR UPDATE SKIP LOCKED and write completion state back to PostgreSQL.

ALTER TABLE integration_sync_run
    ADD COLUMN IF NOT EXISTS contract_version SMALLINT NOT NULL DEFAULT 1
        CHECK (contract_version BETWEEN 1 AND 99),
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS worker_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0
        CHECK (attempts >= 0),
    ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 3
        CHECK (max_attempts > 0),
    ADD COLUMN IF NOT EXISTS rows_read BIGINT NOT NULL DEFAULT 0
        CHECK (rows_read >= 0),
    ADD COLUMN IF NOT EXISTS rows_staged BIGINT NOT NULL DEFAULT 0
        CHECK (rows_staged >= 0),
    ADD COLUMN IF NOT EXISTS rows_written BIGINT NOT NULL DEFAULT 0
        CHECK (rows_written >= 0),
    ADD COLUMN IF NOT EXISTS rows_rejected BIGINT NOT NULL DEFAULT 0
        CHECK (rows_rejected >= 0),
    ADD COLUMN IF NOT EXISTS bytes_read BIGINT NOT NULL DEFAULT 0
        CHECK (bytes_read >= 0),
    ADD COLUMN IF NOT EXISTS bytes_staged BIGINT NOT NULL DEFAULT 0
        CHECK (bytes_staged >= 0),
    ADD COLUMN IF NOT EXISTS bytes_written BIGINT NOT NULL DEFAULT 0
        CHECK (bytes_written >= 0),
    ADD COLUMN IF NOT EXISTS source_version VARCHAR(300),
    ADD COLUMN IF NOT EXISTS source_date DATE,
    ADD COLUMN IF NOT EXISTS source_checksum VARCHAR(128),
    ADD COLUMN IF NOT EXISTS published_generation_id UUID;

ALTER TABLE integration_sync_run
    DROP CONSTRAINT IF EXISTS integration_sync_run_state_check;

ALTER TABLE integration_sync_run
    ADD CONSTRAINT integration_sync_run_state_check CHECK (state IN (
        'QUEUED',
        'RUNNING',
        'VALIDATING',
        'PUBLISHING',
        'SUCCEEDED',
        'PARTIAL',
        'FAILED',
        'CANCELLED',
        'SKIPPED'
    ));

DROP INDEX IF EXISTS integration_sync_run_one_active_idx;
CREATE UNIQUE INDEX integration_sync_run_one_active_idx
    ON integration_sync_run (organization_id, job_key)
    WHERE state IN ('QUEUED', 'RUNNING', 'VALIDATING', 'PUBLISHING');

CREATE INDEX IF NOT EXISTS integration_sync_run_claim_idx
    ON integration_sync_run (state, lease_expires_at, queued_at, id)
    WHERE state IN ('QUEUED', 'RUNNING', 'VALIDATING', 'PUBLISHING');

COMMENT ON TABLE integration_sync_run IS
    'Shared durable ingestion queue/run contract. Spring enqueues rows; Python and legacy Java workers claim with PostgreSQL row locks and complete without REST callbacks.';
COMMENT ON COLUMN integration_sync_run.contract_version IS
    'Bounded shared contract version for parameter and metrics semantics. Version 1 is JSON object parameters, not executable payload.';
COMMENT ON COLUMN integration_sync_run.heartbeat_at IS
    'Last worker heartbeat while RUNNING, VALIDATING or PUBLISHING.';
COMMENT ON COLUMN integration_sync_run.worker_id IS
    'Runtime worker currently owning the lease.';
COMMENT ON COLUMN integration_sync_run.lease_expires_at IS
    'When another worker may safely reclaim this run after a crash.';
COMMENT ON COLUMN integration_sync_run.published_generation_id IS
    'Generation identifier published by source-owned geospatial/registry jobs when applicable.';
