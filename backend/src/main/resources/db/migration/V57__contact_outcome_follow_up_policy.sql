-- Outcome policy is organization-owned: a contact outcome may require a durable next activity.
ALTER TABLE contact_outcome_definition
    ADD COLUMN requires_next_activity BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE contact_outcome_definition
SET requires_next_activity = TRUE
WHERE code IN ('NO_ANSWER', 'FOLLOW_UP');
