-- V65: durable liveness evidence for dedicated Python ingestion runtimes.
-- Additive only: N-1 application processes can ignore this table safely.

CREATE TABLE IF NOT EXISTS ingestion_runtime_heartbeat (
    component VARCHAR(32) NOT NULL,
    instance_id VARCHAR(160) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (component, instance_id),
    CONSTRAINT ingestion_runtime_heartbeat_component_check
        CHECK (component IN ('scheduler', 'worker'))
);

CREATE INDEX IF NOT EXISTS ingestion_runtime_heartbeat_last_seen_idx
    ON ingestion_runtime_heartbeat (component, last_seen_at DESC);

GRANT SELECT ON TABLE ingestion_runtime_heartbeat TO forestiq_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ingestion_runtime_heartbeat TO forestiq_ingest;

COMMENT ON TABLE ingestion_runtime_heartbeat IS
    'Liveness evidence for the dedicated Python scheduler and worker runtimes used by production smoke verification.';
