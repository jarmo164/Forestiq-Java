-- Weasel remains the canonical source for ownership-transition details.
-- ForestIQ retains only a minimal operational audit needed for a data-first work signal.

ALTER TABLE ownership_transition_event
    ADD COLUMN old_owner_count SMALLINT NOT NULL DEFAULT 0 CHECK (old_owner_count >= 0),
    ADD COLUMN new_owner_count SMALLINT NOT NULL DEFAULT 0 CHECK (new_owner_count >= 0),
    ADD COLUMN relationship_direction VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (relationship_direction IN ('OUTGOING', 'INCOMING', 'MIXED', 'UNKNOWN'));

UPDATE ownership_transition_event
SET old_owner_count = COALESCE(jsonb_array_length(old_owner_identifiers), 0),
    new_owner_count = COALESCE(jsonb_array_length(new_owner_identifiers), 0);

-- Existing data is deliberately minimised before the duplicate source fields are removed.
ALTER TABLE ownership_transition_event
    DROP COLUMN old_owner_identifiers,
    DROP COLUMN new_owner_identifiers,
    DROP COLUMN source_payload;
