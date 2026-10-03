-- V64: explicit job-key ownership for durable integration workers.
--
-- Each production job_key belongs to exactly one execution owner. Claim queries
-- join this table so Java and Python runtimes cannot accidentally drain each
-- other's work even when environment flags are misconfigured.

CREATE TABLE IF NOT EXISTS integration_job_ownership (
    job_key TEXT PRIMARY KEY,
    worker_type TEXT NOT NULL CHECK (worker_type IN ('java-api-worker', 'python-ingest-worker')),
    trigger_owner TEXT NOT NULL,
    source_system TEXT NOT NULL,
    max_duration_minutes INTEGER NOT NULL CHECK (max_duration_minutes > 0),
    retry_policy TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    alert_key TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO integration_job_ownership (
    job_key, worker_type, trigger_owner, source_system, max_duration_minutes,
    retry_policy, idempotency_key, alert_key, notes
) VALUES
    ('OWNER_CADASTRE_RELATIONS', 'java-api-worker', 'spring-api-or-scheduler', 'Metsis owner portfolio', 120, 'durable queue max_attempts with lease recovery', 'organization_id + job_key active-run guard + provider relation identity', 'owner-cadastre-relations-stale', 'Target Python cutover is tracked by #365.'),
    ('CADASTRE_PUBLIC_DATA', 'java-api-worker', 'spring-api-or-scheduler', 'Cadastre public data', 240, 'durable queue max_attempts with generation-safe publish', 'organization_id + job_key active-run guard + source checksum/generation', 'cadastre-public-data-stale', 'Target Python cutover is tracked by #364.'),
    ('FOREST_NOTIFICATIONS', 'java-api-worker', 'spring-api-or-scheduler', 'Metsaregister forest notifications', 180, 'durable queue max_attempts with catch-up window', 'organization_id + job_key active-run guard + notification stable source id', 'forest-notifications-stale', 'Target Python cutover is tracked by #363.'),
    ('FOREST_PLAN', 'java-api-worker', 'spring-api-or-scheduler', 'Metsaregister forest plan', 180, 'durable queue max_attempts with generation-safe publish', 'organization_id + job_key active-run guard + plan/compartment identity', 'forest-plan-stale', 'Target Python cutover is tracked by #363.'),
    ('METSAREGISTER_WFS_MIRROR', 'java-api-worker', 'spring-api-or-scheduler', 'Metsaregister WFS mirror', 360, 'durable queue max_attempts with fail-closed source validation', 'organization_id + job_key active-run guard + WFS feature identity', 'metsaregister-wfs-mirror-stale', 'Target Python cutover is tracked by #363.'),
    ('METSAREGISTER_FORESTRY_CANONICAL', 'java-api-worker', 'spring-api-or-scheduler', 'Metsaregister canonical forestry projection', 240, 'durable queue max_attempts with last-known-good generation', 'organization_id + job_key active-run guard + published_generation_id', 'metsaregister-forestry-canonical-stale', 'Target Python cutover is tracked by #363.'),
    ('WEASEL_OWNERSHIP_TRANSITIONS', 'java-api-worker', 'spring-api-or-scheduler', 'Weasel ownership transitions', 120, 'durable queue max_attempts with watermark catch-up', 'organization_id + job_key active-run guard + transition stable id', 'weasel-ownership-transitions-stale', 'Target Python cutover is tracked by #365.'),
    ('INHERITANCE_NOTICES', 'java-api-worker', 'spring-api-or-scheduler', 'Inheritance notice source', 120, 'durable queue max_attempts with overlap/catch-up', 'organization_id + job_key active-run guard + notice stable id', 'inheritance-notices-stale', 'Target Python cutover is tracked by #365.')
ON CONFLICT (job_key) DO UPDATE SET
    worker_type = EXCLUDED.worker_type,
    trigger_owner = EXCLUDED.trigger_owner,
    source_system = EXCLUDED.source_system,
    max_duration_minutes = EXCLUDED.max_duration_minutes,
    retry_policy = EXCLUDED.retry_policy,
    idempotency_key = EXCLUDED.idempotency_key,
    alert_key = EXCLUDED.alert_key,
    notes = EXCLUDED.notes,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE integration_sync_run
    DROP CONSTRAINT IF EXISTS integration_sync_run_job_key_ownership_fk;

ALTER TABLE integration_sync_run
    ADD CONSTRAINT integration_sync_run_job_key_ownership_fk
    FOREIGN KEY (job_key)
    REFERENCES integration_job_ownership (job_key);

COMMENT ON TABLE integration_job_ownership IS
    'Single source of truth for which runtime may claim each durable integration job_key.';
COMMENT ON COLUMN integration_job_ownership.worker_type IS
    'Exactly one runtime owner per job_key: java-api-worker or python-ingest-worker.';
