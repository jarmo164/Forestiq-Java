-- Decision-support, notification preferences and saved map workbaskets (#186-#190).

CREATE TABLE user_notification_preference (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    user_subject VARCHAR(100) NOT NULL,
    lead_minutes INTEGER NOT NULL DEFAULT 60,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_address VARCHAR(320),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, user_subject),
    CONSTRAINT user_notification_preference_lead_check CHECK (lead_minutes BETWEEN 0 AND 10080),
    CONSTRAINT user_notification_preference_email_check CHECK (
        NOT email_enabled OR (email_address IS NOT NULL AND btrim(email_address) <> '')
    )
);

CREATE TABLE reminder_notification_delivery (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    reminder_id BIGINT NOT NULL REFERENCES reminder(id) ON DELETE CASCADE,
    recipient_subject VARCHAR(100) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    notification_kind VARCHAR(20) NOT NULL,
    reminder_due_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reminder_notification_delivery_channel_check CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT reminder_notification_delivery_kind_check CHECK (notification_kind IN ('DUE_SOON', 'OVERDUE')),
    CONSTRAINT reminder_notification_delivery_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT reminder_notification_delivery_attempts_check CHECK (attempts >= 0),
    CONSTRAINT reminder_notification_delivery_unique UNIQUE
        (organization_id, reminder_id, recipient_subject, channel, notification_kind, reminder_due_time)
);
CREATE INDEX reminder_notification_delivery_retry_idx
    ON reminder_notification_delivery (status, last_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX reminder_notification_delivery_recipient_idx
    ON reminder_notification_delivery (organization_id, recipient_subject, created_at DESC);

CREATE TABLE evaluation_evidence_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    schema_version INTEGER NOT NULL DEFAULT 1,
    snapshot JSONB NOT NULL,
    source_observed_at TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by_subject VARCHAR(100),
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT evaluation_evidence_snapshot_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT evaluation_evidence_snapshot_state_check CHECK (state IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT evaluation_evidence_snapshot_confirmation_check CHECK (
        (state = 'DRAFT' AND confirmed_by_subject IS NULL AND confirmed_at IS NULL)
        OR
        (state = 'CONFIRMED' AND confirmed_by_subject IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);
CREATE INDEX evaluation_evidence_snapshot_deal_idx
    ON evaluation_evidence_snapshot (organization_id, deal_case_id, created_at DESC);

ALTER TABLE deal_evaluation
    ADD COLUMN evidence_snapshot_id UUID REFERENCES evaluation_evidence_snapshot(id) ON DELETE SET NULL;

CREATE OR REPLACE FUNCTION attach_latest_confirmed_evidence_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.evidence_snapshot_id IS NULL THEN
        SELECT ees.id
          INTO NEW.evidence_snapshot_id
          FROM evaluation_evidence_snapshot ees
         WHERE ees.organization_id = NEW.organization_id
           AND ees.deal_case_id = NEW.deal_case_id
           AND ees.state = 'CONFIRMED'
           AND ees.confirmed_at <= COALESCE(NEW.submitted_at, CURRENT_TIMESTAMP)
         ORDER BY ees.confirmed_at DESC, ees.created_at DESC, ees.id DESC
         LIMIT 1;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_deal_evaluation_attach_evidence ON deal_evaluation;
CREATE TRIGGER trg_deal_evaluation_attach_evidence
BEFORE INSERT ON deal_evaluation
FOR EACH ROW EXECUTE FUNCTION attach_latest_confirmed_evidence_snapshot();

CREATE OR REPLACE FUNCTION protect_confirmed_evidence_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.state = 'CONFIRMED' THEN
        RAISE EXCEPTION 'Confirmed evaluation evidence snapshot is immutable';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

DROP TRIGGER IF EXISTS trg_evidence_snapshot_immutable ON evaluation_evidence_snapshot;
CREATE TRIGGER trg_evidence_snapshot_immutable
BEFORE UPDATE OR DELETE ON evaluation_evidence_snapshot
FOR EACH ROW EXECUTE FUNCTION protect_confirmed_evidence_snapshot();

CREATE TABLE saved_workbasket (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    creator_subject VARCHAR(100) NOT NULL,
    name VARCHAR(180) NOT NULL,
    purpose VARCHAR(1000),
    selection_mode VARCHAR(16) NOT NULL DEFAULT 'FIXED',
    map_filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    parcel_ids UUID[] NOT NULL DEFAULT '{}'::uuid[],
    owner_ids UUID[] NOT NULL DEFAULT '{}'::uuid[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT saved_workbasket_selection_mode_check CHECK (selection_mode IN ('FIXED', 'DYNAMIC')),
    CONSTRAINT saved_workbasket_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT saved_workbasket_parcel_limit_check CHECK (cardinality(parcel_ids) <= 1000),
    CONSTRAINT saved_workbasket_owner_limit_check CHECK (cardinality(owner_ids) <= 500)
);
CREATE INDEX saved_workbasket_creator_idx
    ON saved_workbasket (organization_id, creator_subject, updated_at DESC);

CREATE TABLE saved_workbasket_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    workbasket_id UUID NOT NULL REFERENCES saved_workbasket(id) ON DELETE CASCADE,
    event_type VARCHAR(24) NOT NULL,
    actor_subject VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT saved_workbasket_event_type_check CHECK (event_type IN ('CREATED', 'UPDATED', 'OPENED', 'DELETED'))
);
CREATE INDEX saved_workbasket_event_idx
    ON saved_workbasket_event (organization_id, workbasket_id, created_at DESC);
