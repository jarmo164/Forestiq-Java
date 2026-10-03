-- Contract signing lifecycle, historical sales analytics, dynamic target segments and audited workload assignment.

CREATE TABLE contract_signing_state (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    contract_id UUID NOT NULL REFERENCES contract(id) ON DELETE CASCADE,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
    responsible_subject VARCHAR(100) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stall_reason VARCHAR(2000),
    signed_document_id UUID REFERENCES document(id) ON DELETE SET NULL,
    signed_external_url VARCHAR(2000),
    updated_by_subject VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, contract_id),
    CONSTRAINT contract_signing_status_check CHECK (status IN ('PREPARING','SENT_FOR_SIGNATURE','SIGNED','CANCELLED')),
    CONSTRAINT contract_signing_signed_reference_check CHECK (
        status <> 'SIGNED' OR signed_document_id IS NOT NULL OR signed_external_url IS NOT NULL
    ),
    CONSTRAINT contract_signing_single_reference_check CHECK (
        signed_document_id IS NULL OR signed_external_url IS NULL
    )
);
CREATE INDEX contract_signing_deal_idx ON contract_signing_state (organization_id, deal_case_id, due_at);
CREATE INDEX contract_signing_due_idx ON contract_signing_state (organization_id, status, due_at)
    WHERE status IN ('PREPARING','SENT_FOR_SIGNATURE');

CREATE TABLE contract_signing_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    contract_id UUID NOT NULL REFERENCES contract(id) ON DELETE CASCADE,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    responsible_subject VARCHAR(100) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(2000),
    reference_kind VARCHAR(16),
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT contract_signing_event_type_check CHECK (event_type IN ('CREATED','STATUS_CHANGED','SIGNED_REFERENCE_CHANGED')),
    CONSTRAINT contract_signing_event_status_check CHECK (to_status IN ('PREPARING','SENT_FOR_SIGNATURE','SIGNED','CANCELLED')),
    CONSTRAINT contract_signing_event_reference_kind_check CHECK (reference_kind IS NULL OR reference_kind IN ('DOCUMENT','EXTERNAL'))
);
CREATE INDEX contract_signing_event_timeline_idx
    ON contract_signing_event (organization_id, contract_id, created_at DESC, id DESC);

-- Existing controlled versions become visible in the signing workbench immediately after migration.
INSERT INTO contract_signing_state
    (organization_id, contract_id, deal_case_id, status, responsible_subject, due_at,
     status_at, updated_by_subject, updated_at)
SELECT organization_id, contract_id, deal_case_id, 'PREPARING', created_by_subject,
       created_at + interval '7 days', created_at, created_by_subject, created_at
FROM contract_version_audit
ON CONFLICT DO NOTHING;

INSERT INTO contract_signing_event
    (organization_id, contract_id, deal_case_id, event_type, from_status, to_status, effective_at,
     responsible_subject, due_at, reason, created_by_subject, created_at)
SELECT v.organization_id, v.contract_id, v.deal_case_id, 'CREATED', NULL, 'PREPARING', v.created_at,
       v.created_by_subject, v.created_at + interval '7 days', 'Kontrollitud lepinguversioon loodi.',
       v.created_by_subject, v.created_at
FROM contract_version_audit v
WHERE NOT EXISTS (
    SELECT 1 FROM contract_signing_event e
    WHERE e.organization_id = v.organization_id AND e.contract_id = v.contract_id
);

CREATE OR REPLACE FUNCTION create_contract_signing_state_from_version() RETURNS trigger AS $$
BEGIN
    INSERT INTO contract_signing_state
        (organization_id, contract_id, deal_case_id, status, responsible_subject, due_at,
         status_at, updated_by_subject, updated_at)
    VALUES (NEW.organization_id, NEW.contract_id, NEW.deal_case_id, 'PREPARING', NEW.created_by_subject,
            NEW.created_at + interval '7 days', NEW.created_at, NEW.created_by_subject, NEW.created_at)
    ON CONFLICT DO NOTHING;

    INSERT INTO contract_signing_event
        (organization_id, contract_id, deal_case_id, event_type, from_status, to_status, effective_at,
         responsible_subject, due_at, reason, created_by_subject, created_at)
    VALUES (NEW.organization_id, NEW.contract_id, NEW.deal_case_id, 'CREATED', NULL, 'PREPARING', NEW.created_at,
            NEW.created_by_subject, NEW.created_at + interval '7 days', 'Kontrollitud lepinguversioon loodi.',
            NEW.created_by_subject, NEW.created_at);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_contract_version_create_signing_state
AFTER INSERT ON contract_version_audit
FOR EACH ROW EXECUTE FUNCTION create_contract_signing_state_from_version();

CREATE OR REPLACE FUNCTION protect_contract_signing_event() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'contract_signing_event is immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_contract_signing_event_immutable
BEFORE UPDATE OR DELETE ON contract_signing_event
FOR EACH ROW EXECUTE FUNCTION protect_contract_signing_event();

CREATE TABLE stage_probability_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    probability NUMERIC(5,4) NOT NULL CHECK (probability >= 0 AND probability <= 1),
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT stage_probability_validity_check CHECK (valid_to IS NULL OR valid_to > valid_from),
    UNIQUE (organization_id, stage, version_no)
);
CREATE UNIQUE INDEX stage_probability_current_idx
    ON stage_probability_config (organization_id, stage) WHERE valid_to IS NULL;

INSERT INTO stage_probability_config (organization_id, stage, probability, version_no, created_by_subject)
SELECT o.id, p.stage, p.probability, 1, 'system-migration'
FROM organization o
CROSS JOIN (VALUES
    ('QUALIFIED', 0.10::numeric),
    ('EVALUATION_REQUESTED', 0.20::numeric),
    ('EVALUATING', 0.25::numeric),
    ('VALUED', 0.40::numeric),
    ('OFFER_PREPARED', 0.55::numeric),
    ('OFFER_SENT', 0.65::numeric),
    ('NEGOTIATION', 0.75::numeric),
    ('WON', 1.00::numeric),
    ('LOST', 0.00::numeric),
    ('CANCELLED', 0.00::numeric)
) AS p(stage, probability)
ON CONFLICT DO NOTHING;

CREATE TABLE sales_target_segment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name VARCHAR(180) NOT NULL,
    description VARCHAR(1000),
    rules JSONB NOT NULL,
    recommended_workflow VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_calculated_at TIMESTAMPTZ,
    last_calculated_count INTEGER,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_subject VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sales_target_segment_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT sales_target_segment_count_check CHECK (last_calculated_count IS NULL OR last_calculated_count >= 0)
);
CREATE INDEX sales_target_segment_org_idx ON sales_target_segment (organization_id, active, updated_at DESC);

CREATE TABLE sales_target_segment_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    segment_id UUID NOT NULL REFERENCES sales_target_segment(id) ON DELETE CASCADE,
    event_type VARCHAR(24) NOT NULL,
    actor_subject VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sales_target_segment_event_type_check CHECK (event_type IN ('CREATED','UPDATED','CALCULATED','ASSIGNMENT_PREVIEWED','ASSIGNED'))
);
CREATE INDEX sales_target_segment_event_idx ON sales_target_segment_event (organization_id, segment_id, created_at DESC);

CREATE TABLE management_assignment_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    segment_id UUID REFERENCES sales_target_segment(id) ON DELETE SET NULL,
    assignment_rule VARCHAR(64) NOT NULL,
    rationale TEXT NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count >= 0),
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE management_assignment_item (
    run_id UUID NOT NULL REFERENCES management_assignment_run(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    from_subject VARCHAR(100),
    to_subject VARCHAR(100) NOT NULL,
    rationale VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, owner_id)
);
CREATE INDEX management_assignment_subject_idx
    ON management_assignment_item (organization_id, to_subject, created_at DESC);

CREATE OR REPLACE FUNCTION protect_management_assignment_audit() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'management assignment audit is immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_management_assignment_run_immutable
BEFORE UPDATE OR DELETE ON management_assignment_run
FOR EACH ROW EXECUTE FUNCTION protect_management_assignment_audit();
CREATE TRIGGER trg_management_assignment_item_immutable
BEFORE UPDATE OR DELETE ON management_assignment_item
FOR EACH ROW EXECUTE FUNCTION protect_management_assignment_audit();
