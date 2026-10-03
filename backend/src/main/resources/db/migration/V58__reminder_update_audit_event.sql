ALTER TABLE reminder_event DROP CONSTRAINT IF EXISTS reminder_event_type_check;
ALTER TABLE reminder_event ADD CONSTRAINT reminder_event_type_check
    CHECK (event_type IN ('CREATED', 'COMPLETED', 'POSTPONED', 'DELEGATED', 'CANCELLED', 'UPDATED'));
