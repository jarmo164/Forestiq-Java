CREATE TABLE integration_sync_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    job_key VARCHAR(80) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    requested_by_subject VARCHAR(120) NOT NULL,
    state VARCHAR(20) NOT NULL,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    message VARCHAR(500),
    error_summary VARCHAR(500),
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT integration_sync_run_trigger_check CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT integration_sync_run_state_check CHECK (state IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED'
    ))
);
CREATE INDEX integration_sync_run_history_idx
    ON integration_sync_run (organization_id, job_key, queued_at DESC);
CREATE UNIQUE INDEX integration_sync_run_one_active_idx
    ON integration_sync_run (organization_id, job_key)
    WHERE state IN ('QUEUED', 'RUNNING');

-- A run-scoped staging snapshot prevents partial owner/parcel feeds from
-- deactivating the last known-good relationship set.
CREATE TABLE owner_parcel_snapshot_row (
    run_id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_code VARCHAR(32) NOT NULL,
    owner_name VARCHAR(300) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (run_id, organization_id, owner_code, cadastral_code)
);
CREATE INDEX owner_parcel_snapshot_run_idx
    ON owner_parcel_snapshot_row (organization_id, run_id);

CREATE TABLE owner_snapshot_row (
    run_id UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_code VARCHAR(32) NOT NULL,
    owner_name VARCHAR(300) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (run_id, organization_id, owner_code)
);

ALTER TABLE owner ADD COLUMN source_display_name VARCHAR(300);
ALTER TABLE owner ADD COLUMN source_owner_type VARCHAR(32);

-- Only normalized, matched notice metadata is retained. Raw XML and notice
-- bodies are deliberately discarded after parsing.
CREATE TABLE inheritance_notice_import (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    notice_number VARCHAR(100) NOT NULL,
    owner_id UUID NOT NULL,
    announcement_date DATE NOT NULL,
    source_url VARCHAR(500),
    death_date DATE,
    certification_deadline DATE,
    notary_name VARCHAR(255),
    notary_phone VARCHAR(100),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, notice_number),
    CONSTRAINT inheritance_notice_import_owner_fk
        FOREIGN KEY (organization_id, owner_id) REFERENCES owner (organization_id, id) ON DELETE CASCADE
);
CREATE INDEX inheritance_notice_import_owner_idx
    ON inheritance_notice_import (organization_id, owner_id, announcement_date DESC);

ALTER TABLE inheritance_case ADD COLUMN assigned_to_subject VARCHAR(120);
CREATE INDEX inheritance_case_assignment_idx
    ON inheritance_case (organization_id, assigned_to_subject, status, updated_at DESC);
