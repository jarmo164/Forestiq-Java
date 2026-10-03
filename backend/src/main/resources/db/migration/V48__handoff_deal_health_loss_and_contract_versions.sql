-- Workbasket handoff, deal health/loss outcomes and controlled contract versions (#191-#195).

ALTER TABLE saved_workbasket_event DROP CONSTRAINT IF EXISTS saved_workbasket_event_type_check;
ALTER TABLE saved_workbasket_event ADD CONSTRAINT saved_workbasket_event_type_check
    CHECK (event_type IN ('CREATED','UPDATED','OPENED','DELETED','SHARED','ACCEPTED','SHARE_CANCELLED'));

CREATE TABLE saved_workbasket_share (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    workbasket_id UUID NOT NULL REFERENCES saved_workbasket(id) ON DELETE CASCADE,
    recipient_subject VARCHAR(100),
    recipient_role VARCHAR(64),
    permission VARCHAR(12) NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT saved_workbasket_share_recipient_check CHECK (
        (recipient_subject IS NOT NULL AND recipient_role IS NULL) OR
        (recipient_subject IS NULL AND recipient_role IS NOT NULL)
    ),
    CONSTRAINT saved_workbasket_share_permission_check CHECK (permission IN ('READ','EDIT')),
    CONSTRAINT saved_workbasket_share_status_check CHECK (status IN ('PENDING','ACCEPTED','CANCELLED')),
    CONSTRAINT saved_workbasket_share_purpose_check CHECK (btrim(purpose) <> '')
);
CREATE INDEX saved_workbasket_share_inbox_idx
    ON saved_workbasket_share (organization_id, status, due_at, created_at DESC);
CREATE INDEX saved_workbasket_share_basket_idx
    ON saved_workbasket_share (organization_id, workbasket_id, created_at DESC);

CREATE TABLE deal_loss_reason_definition (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    label VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 100,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, code),
    CONSTRAINT deal_loss_reason_code_check CHECK (code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT deal_loss_reason_label_check CHECK (btrim(label) <> '')
);

INSERT INTO deal_loss_reason_definition (organization_id, code, label, sort_order)
SELECT o.id, d.code, d.label, d.sort_order
FROM organization o
CROSS JOIN (VALUES
    ('PRICE_GAP', 'Hinnavahe', 10),
    ('CUSTOMER_WITHDREW', 'Klient loobus', 20),
    ('COMPETITOR', 'Konkurent', 30),
    ('TITLE_OR_LEGAL_RISK', 'Õiguslik või omandirisk', 40),
    ('PROPERTY_NOT_SUITABLE', 'Kinnistu ei sobinud', 50),
    ('NO_RESPONSE', 'Klient ei vastanud', 60),
    ('OTHER', 'Muu', 100)
) AS d(code, label, sort_order)
ON CONFLICT DO NOTHING;

CREATE TABLE deal_loss_outcome (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    reason_code VARCHAR(64) NOT NULL,
    lost_from_stage VARCHAR(32) NOT NULL,
    sales_subject VARCHAR(100) NOT NULL,
    note TEXT,
    follow_up_at TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, deal_case_id),
    CONSTRAINT deal_loss_outcome_reason_fk FOREIGN KEY (organization_id, reason_code)
        REFERENCES deal_loss_reason_definition(organization_id, code)
);
CREATE INDEX deal_loss_outcome_reporting_idx
    ON deal_loss_outcome (organization_id, created_at DESC, sales_subject, lost_from_stage, reason_code);

CREATE TABLE contract_version_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    contract_id UUID NOT NULL REFERENCES contract(id) ON DELETE RESTRICT,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE RESTRICT,
    version_no INTEGER NOT NULL,
    replaces_contract_id UUID REFERENCES contract(id) ON DELETE RESTRICT,
    change_reason TEXT,
    checklist JSONB NOT NULL,
    template_id UUID NOT NULL,
    template_sha256 VARCHAR(64) NOT NULL,
    source_snapshot JSONB NOT NULL,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT contract_version_audit_version_check CHECK (version_no > 0),
    CONSTRAINT contract_version_audit_change_reason_check CHECK (
        version_no = 1 OR (change_reason IS NOT NULL AND btrim(change_reason) <> '')
    ),
    UNIQUE (organization_id, deal_case_id, version_no),
    UNIQUE (organization_id, contract_id)
);
CREATE INDEX contract_version_audit_deal_idx
    ON contract_version_audit (organization_id, deal_case_id, version_no DESC);

CREATE OR REPLACE FUNCTION protect_contract_version_audit()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Contract version audit is immutable';
END;
$$;

CREATE TRIGGER trg_contract_version_audit_immutable
BEFORE UPDATE OR DELETE ON contract_version_audit
FOR EACH ROW EXECUTE FUNCTION protect_contract_version_audit();

COMMENT ON TABLE contract_version_audit IS
    'Immutable evidence for controlled generated contract versions: checklist, source snapshot and template fingerprint.';
