-- Weasel publishes cadastral ownership transitions, not physical location data.
-- Store the minimum source identifiers needed for auditability and data-first work allocation.

ALTER TABLE data_change_signal DROP CONSTRAINT IF EXISTS data_change_signal_type_check;
ALTER TABLE data_change_signal ADD CONSTRAINT data_change_signal_type_check
    CHECK (signal_type IN ('NEW_FOREST_NOTIFICATION', 'NEW_FOREST_COMPARTMENT', 'DECEASED_OWNER', 'OWNERSHIP_TRANSITION'));

CREATE TABLE ownership_transition_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    weasel_transition_id BIGINT NOT NULL,
    cadastral_code VARCHAR(32) NOT NULL,
    transition_at TIMESTAMPTZ NOT NULL,
    old_owner_identifiers JSONB NOT NULL DEFAULT '[]'::jsonb,
    new_owner_identifiers JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    imported_by_subject VARCHAR(100) NOT NULL DEFAULT 'system-weasel-sync',
    CONSTRAINT ownership_transition_event_org_parcel_fk FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ownership_transition_event_org_owner_fk FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ownership_transition_event_unique UNIQUE (organization_id, weasel_transition_id)
);
CREATE INDEX ownership_transition_event_owner_idx ON ownership_transition_event (organization_id, owner_id, transition_at DESC);
CREATE INDEX ownership_transition_event_parcel_idx ON ownership_transition_event (organization_id, cadastral_code, transition_at DESC);

CREATE TABLE weasel_transition_sync_checkpoint (
    organization_id UUID PRIMARY KEY REFERENCES organization(id) ON DELETE CASCADE,
    cursor_time TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
