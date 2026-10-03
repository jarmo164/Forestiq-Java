-- A deal case represents one concrete opportunity to acquire forest, land or both
-- from an owner. It keeps sales qualification, evaluator handoff and valuation
-- separate from the owner record so an owner may have a complete history.
CREATE TABLE deal_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    sale_subject VARCHAR(16) NOT NULL CHECK (sale_subject IN ('FOREST', 'LAND', 'BOTH')),
    stage VARCHAR(32) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (stage IN ('QUALIFIED', 'EVALUATION_REQUESTED', 'EVALUATING', 'VALUED', 'OFFER_PREPARED', 'OFFER_SENT', 'NEGOTIATION', 'WON', 'LOST', 'CANCELLED')),
    sales_subject VARCHAR(100) NOT NULL,
    evaluator_subject VARCHAR(100),
    decision_maker VARCHAR(300),
    sale_timeframe VARCHAR(120),
    price_expectation NUMERIC(14, 2),
    qualification_notes TEXT,
    next_action TEXT NOT NULL,
    next_action_due_at TIMESTAMPTZ,
    evaluation_due_at TIMESTAMPTZ,
    stage_set_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deal_case_organization_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id)
        ON DELETE CASCADE
);
CREATE INDEX deal_case_owner_idx ON deal_case (organization_id, owner_id, created_at DESC);
CREATE INDEX deal_case_evaluation_queue_idx ON deal_case (organization_id, stage, evaluation_due_at, created_at)
    WHERE stage IN ('EVALUATION_REQUESTED', 'EVALUATING');
CREATE INDEX deal_case_sales_idx ON deal_case (organization_id, sales_subject, stage, next_action_due_at);

CREATE TABLE deal_case_parcel (
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    parcel_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (deal_case_id, parcel_id),
    CONSTRAINT deal_case_parcel_organization_parcel_fk
        FOREIGN KEY (organization_id, parcel_id)
        REFERENCES land_parcel (organization_id, id)
        ON DELETE CASCADE
);
CREATE INDEX deal_case_parcel_parcel_idx ON deal_case_parcel (organization_id, parcel_id);

CREATE TABLE deal_evaluation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'NEEDS_DATA', 'SUBMITTED', 'APPROVED')),
    estimated_min_price NUMERIC(14, 2),
    estimated_max_price NUMERIC(14, 2),
    recommended_purchase_price NUMERIC(14, 2),
    internal_min_price NUMERIC(14, 2),
    proposed_offer_price NUMERIC(14, 2),
    offer_valid_until DATE,
    assumptions TEXT,
    risks TEXT,
    returned_reason TEXT,
    created_by_subject VARCHAR(100) NOT NULL,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deal_evaluation_revision_unique UNIQUE (deal_case_id, revision)
);
CREATE INDEX deal_evaluation_case_idx ON deal_evaluation (organization_id, deal_case_id, revision DESC);

CREATE TABLE deal_case_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    deal_case_id UUID NOT NULL REFERENCES deal_case(id) ON DELETE CASCADE,
    event_type VARCHAR(48) NOT NULL,
    description TEXT NOT NULL,
    event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX deal_case_event_timeline_idx ON deal_case_event (organization_id, deal_case_id, created_at DESC);
