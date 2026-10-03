ALTER TABLE reminder_notification_delivery
    DROP CONSTRAINT reminder_notification_delivery_status_check;

ALTER TABLE reminder_notification_delivery
    ADD CONSTRAINT reminder_notification_delivery_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'SUPPRESSED'));

CREATE INDEX reminder_notification_delivery_processing_idx
    ON reminder_notification_delivery (last_attempt_at)
    WHERE status = 'PROCESSING';
