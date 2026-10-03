-- Derived, immutable business signals created from official registry refreshes.
-- They make external data changes queryable before anyone is assigned to call.
CREATE TABLE data_change_signal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    parcel_id UUID,
    signal_type VARCHAR(48) NOT NULL CHECK (signal_type IN ('NEW_FOREST_NOTIFICATION', 'NEW_FOREST_COMPARTMENT', 'DECEASED_OWNER')),
    source_registry VARCHAR(80) NOT NULL,
    source_key VARCHAR(180) NOT NULL,
    summary TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    first_assigned_at TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL DEFAULT 'system-data-refresh',
    CONSTRAINT data_change_signal_organization_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT data_change_signal_organization_parcel_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT data_change_signal_source_unique UNIQUE (organization_id, signal_type, source_registry, source_key)
);
CREATE INDEX data_change_signal_owner_idx ON data_change_signal (organization_id, owner_id, detected_at DESC);
CREATE INDEX data_change_signal_actionable_idx ON data_change_signal (organization_id, detected_at DESC)
    WHERE dismissed_at IS NULL;
