-- MetsIS/forestek remains the source for active owner-to-cadastre relations.
-- ForestIQ stores a minimal operational projection and a normalised relation, not source responses.

CREATE TABLE owner_parcel_relation (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL REFERENCES land_parcel(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL DEFAULT 'METSIS_PORTFOLIO',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    source_seen_at TIMESTAMPTZ NOT NULL,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, owner_id, parcel_id),
    CONSTRAINT owner_parcel_relation_lifecycle CHECK ((active AND deactivated_at IS NULL) OR (NOT active AND deactivated_at IS NOT NULL))
);
CREATE INDEX owner_parcel_relation_owner_active_idx ON owner_parcel_relation (organization_id, owner_id) WHERE active;
CREATE INDEX owner_parcel_relation_parcel_active_idx ON owner_parcel_relation (organization_id, parcel_id) WHERE active;

-- Existing primary-owner links are projected once so current CRM data stays visible through the new relation model.
INSERT INTO owner_parcel_relation (organization_id, owner_id, parcel_id, source, active, source_seen_at)
SELECT organization_id, owner_id, id, 'FORESTIQ_LEGACY', TRUE, COALESCE(updated_at, CURRENT_TIMESTAMP)
FROM land_parcel
ON CONFLICT (organization_id, owner_id, parcel_id) DO NOTHING;

CREATE TABLE metsis_portfolio_sync_checkpoint (
    organization_id UUID PRIMARY KEY REFERENCES organization(id) ON DELETE CASCADE,
    cursor_date DATE,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error TEXT,
    last_rows_seen INTEGER NOT NULL DEFAULT 0,
    last_relations_added INTEGER NOT NULL DEFAULT 0,
    last_relations_closed INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
