-- Auditable/manual owner-to-cadastre relationship management.
-- owner_parcel_relation remains the canonical current-state model; these fields
-- add provenance/manual protection while append-only audit/conflict tables keep history.

ALTER TABLE owner_parcel_relation
    ADD COLUMN source_external_key VARCHAR(255),
    ADD COLUMN manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN manual_actor_subject VARCHAR(255),
    ADD COLUMN manual_reason TEXT,
    ADD COLUMN manual_changed_at TIMESTAMPTZ,
    ADD COLUMN manual_revision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE owner_parcel_relation
    ADD CONSTRAINT owner_parcel_relation_manual_metadata_check CHECK (
        (NOT manual_override)
        OR (manual_actor_subject IS NOT NULL AND manual_reason IS NOT NULL AND btrim(manual_reason) <> '' AND manual_changed_at IS NOT NULL)
    ),
    ADD CONSTRAINT owner_parcel_relation_manual_revision_check CHECK (manual_revision >= 0);

CREATE TABLE owner_parcel_relation_audit (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    relation_source VARCHAR(32) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    reason TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT owner_parcel_relation_audit_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT owner_parcel_relation_audit_parcel_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT owner_parcel_relation_audit_reason_check CHECK (btrim(reason) <> '')
);

CREATE INDEX owner_parcel_relation_audit_relation_idx
    ON owner_parcel_relation_audit (organization_id, owner_id, parcel_id, created_at DESC);

CREATE TABLE ownership_relation_conflict (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    source VARCHAR(32) NOT NULL,
    observed_active BOOLEAN NOT NULL,
    relation_revision INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by_subject VARCHAR(255),
    resolution VARCHAR(32),
    resolution_reason TEXT,
    CONSTRAINT ownership_relation_conflict_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ownership_relation_conflict_parcel_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ownership_relation_conflict_status_check CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ownership_relation_conflict_resolution_check CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by_subject IS NULL AND resolution IS NULL AND resolution_reason IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by_subject IS NOT NULL
            AND resolution IN ('KEEP_MANUAL', 'ACCEPT_PROVIDER')
            AND resolution_reason IS NOT NULL AND btrim(resolution_reason) <> '')
    ),
    CONSTRAINT ownership_relation_conflict_revision_check CHECK (relation_revision >= 0),
    CONSTRAINT ownership_relation_conflict_observation_unique
        UNIQUE (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
);

CREATE INDEX ownership_relation_conflict_open_idx
    ON ownership_relation_conflict (organization_id, created_at DESC)
    WHERE status = 'OPEN';

CREATE INDEX ownership_relation_conflict_relation_idx
    ON ownership_relation_conflict (organization_id, owner_id, parcel_id, created_at DESC);

COMMENT ON COLUMN owner_parcel_relation.manual_override IS
    'When true, automated ownership synchronization must not change the active state silently; disagreement is recorded as an ownership_relation_conflict.';
COMMENT ON COLUMN owner_parcel_relation.manual_revision IS
    'Increments on deliberate manual state changes and scopes conflict deduplication to that manual decision.';
