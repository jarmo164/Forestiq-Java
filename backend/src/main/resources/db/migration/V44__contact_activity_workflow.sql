-- Structured contact logging and auditable follow-up workflow (#181-#185).

CREATE TABLE contact_outcome_definition (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    label VARCHAR(160) NOT NULL,
    requires_reason BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 100,
    PRIMARY KEY (organization_id, code),
    CONSTRAINT contact_outcome_code_check CHECK (code ~ '^[A-Z0-9_]+$')
);

INSERT INTO contact_outcome_definition (organization_id, code, label, requires_reason, active, sort_order)
SELECT id, defaults.code, defaults.label, defaults.requires_reason, TRUE, defaults.sort_order
FROM organization
CROSS JOIN (VALUES
    ('CONTACTED', 'Kontakt loodud', FALSE, 10),
    ('NO_ANSWER', 'Ei vastanud', FALSE, 20),
    ('INTERESTED', 'Huvitatud', FALSE, 30),
    ('NOT_INTERESTED', 'Ei ole huvitatud', TRUE, 40),
    ('FOLLOW_UP', 'Vajab järeltegevust', FALSE, 50)
) AS defaults(code, label, requires_reason, sort_order)
ON CONFLICT DO NOTHING;

-- V2 kept the original activity type check intentionally narrow. Structured
-- contact logging needs first-class channel types while preserving every legacy
-- type that existing code can still write.
ALTER TABLE owner_activity DROP CONSTRAINT IF EXISTS owner_activity_type_check;
ALTER TABLE owner_activity ADD CONSTRAINT owner_activity_type_check
    CHECK (activity_type IN (
        'NOTE', 'STATUS_CHANGED', 'ASSIGNED', 'CADASTRE_ADDED', 'CADASTRE_REMOVED',
        'LABEL_CHANGED', 'EVALUATION', 'REMINDER', 'FOLLOWING', 'SYSTEM',
        'PHONE', 'EMAIL', 'MEETING', 'STATUS_CHANGE', 'ASSIGNMENT', 'CREATED', 'OWNER_UPDATED'
    ));

ALTER TABLE owner_activity
    ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN contact_channel VARCHAR(16),
    ADD COLUMN outcome_code VARCHAR(64),
    ADD COLUMN reason TEXT,
    ADD COLUMN deal_case_id UUID REFERENCES deal_case(id) ON DELETE SET NULL,
    ADD COLUMN related_reminder_id BIGINT REFERENCES reminder(id) ON DELETE SET NULL;

ALTER TABLE owner_activity
    ADD CONSTRAINT owner_activity_source_type_check CHECK (source_type IN ('USER', 'AUTOMATED')),
    ADD CONSTRAINT owner_activity_contact_channel_check CHECK (contact_channel IS NULL OR contact_channel IN ('PHONE', 'EMAIL', 'MEETING', 'OTHER'));

UPDATE owner_activity
SET source_type = 'AUTOMATED'
WHERE activity_type IN (
    'STATUS_CHANGED', 'ASSIGNED', 'CADASTRE_ADDED', 'CADASTRE_REMOVED', 'LABEL_CHANGED', 'SYSTEM',
    'STATUS_CHANGE', 'ASSIGNMENT', 'CREATED', 'OWNER_UPDATED'
);

CREATE INDEX idx_owner_activity_history
    ON owner_activity (organization_id, owner_id, created_at DESC, id DESC);
CREATE INDEX idx_owner_activity_deal_history
    ON owner_activity (organization_id, deal_case_id, created_at DESC, id DESC)
    WHERE deal_case_id IS NOT NULL;
CREATE INDEX idx_owner_activity_outcome
    ON owner_activity (organization_id, outcome_code, created_at DESC)
    WHERE outcome_code IS NOT NULL;

ALTER TABLE reminder
    ADD COLUMN assignee_subject VARCHAR(100),
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN activity_type VARCHAR(40) NOT NULL DEFAULT 'FOLLOW_UP',
    ADD COLUMN priority_reason VARCHAR(300),
    ADD COLUMN deal_case_id UUID REFERENCES deal_case(id) ON DELETE SET NULL,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN completion_note VARCHAR(1000),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE reminder
SET assignee_subject = COALESCE(NULLIF(creator, ''), 'system')
WHERE assignee_subject IS NULL;

-- Existing ReminderService inserts do not know about assignee_subject yet. Keep those
-- writes backward compatible by defaulting the assignee to the creator at DB level.
CREATE OR REPLACE FUNCTION reminder_default_assignee_subject()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.assignee_subject IS NULL OR btrim(NEW.assignee_subject) = '' THEN
        NEW.assignee_subject := NEW.creator;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reminder_default_assignee_subject_trigger
BEFORE INSERT ON reminder
FOR EACH ROW
EXECUTE FUNCTION reminder_default_assignee_subject();

ALTER TABLE reminder
    ALTER COLUMN assignee_subject SET NOT NULL,
    ADD CONSTRAINT reminder_workflow_status_check CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED'));

CREATE INDEX idx_reminder_assignee_queue
    ON reminder (organization_id, assignee_subject, status, due_time, id);
CREATE INDEX idx_reminder_owner_history
    ON reminder (organization_id, owner_id, created_at DESC, id DESC)
    WHERE owner_id IS NOT NULL;

CREATE TABLE reminder_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    reminder_id BIGINT NOT NULL REFERENCES reminder(id) ON DELETE CASCADE,
    owner_id UUID REFERENCES owner(id) ON DELETE SET NULL,
    deal_case_id UUID REFERENCES deal_case(id) ON DELETE SET NULL,
    event_type VARCHAR(20) NOT NULL,
    old_due_time TIMESTAMPTZ,
    new_due_time TIMESTAMPTZ,
    from_assignee VARCHAR(100),
    to_assignee VARCHAR(100),
    reason VARCHAR(1000),
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reminder_event_type_check CHECK (event_type IN ('CREATED', 'COMPLETED', 'POSTPONED', 'DELEGATED', 'CANCELLED'))
);

CREATE INDEX idx_reminder_event_history
    ON reminder_event (organization_id, reminder_id, created_at DESC, id DESC);
CREATE INDEX idx_reminder_event_owner_history
    ON reminder_event (organization_id, owner_id, created_at DESC, id DESC)
    WHERE owner_id IS NOT NULL;
