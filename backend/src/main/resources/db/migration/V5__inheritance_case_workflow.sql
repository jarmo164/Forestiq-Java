CREATE TABLE inheritance_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    source_notice_number VARCHAR(64),
    source_url VARCHAR(500),
    announcement_date DATE,
    death_date DATE,
    certification_deadline DATE,
    notary_name VARCHAR(255),
    notary_phone VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'NOTICE_RECEIVED',
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    created_by_subject VARCHAR(100) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inheritance_case_status_check CHECK (status IN (
        'NOTICE_RECEIVED', 'HEIR_DISCOVERY', 'HEIRS_CONTACTED', 'NEGOTIATION', 'SETTLED', 'CLOSED', 'NOT_RELEVANT'
    )),
    CONSTRAINT inheritance_case_dates_check CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT inheritance_case_notice_unique UNIQUE (organization_id, source_notice_number)
);
CREATE UNIQUE INDEX inheritance_case_one_open_case_per_owner_idx
    ON inheritance_case (organization_id, owner_id)
    WHERE status NOT IN ('SETTLED', 'CLOSED', 'NOT_RELEVANT');
CREATE INDEX inheritance_case_owner_idx ON inheritance_case (organization_id, owner_id, updated_at DESC);
CREATE INDEX inheritance_case_status_idx ON inheritance_case (organization_id, status, certification_deadline);

CREATE TABLE inheritance_heir (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inheritance_case_id UUID NOT NULL REFERENCES inheritance_case(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    display_name VARCHAR(255) NOT NULL,
    personal_code VARCHAR(32),
    registry_code VARCHAR(32),
    inheritance_share VARCHAR(64),
    relation_to_deceased VARCHAR(120),
    phone VARCHAR(64),
    email VARCHAR(320),
    contact_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    assigned_to_subject VARCHAR(100),
    source VARCHAR(64) NOT NULL DEFAULT 'MANUAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inheritance_heir_contact_status_check CHECK (contact_status IN (
        'UNVERIFIED', 'READY_TO_CONTACT', 'CONTACTED', 'INTERESTED', 'NOT_INTERESTED', 'NOT_REACHABLE'
    )),
    CONSTRAINT inheritance_heir_identity_check CHECK (personal_code IS NOT NULL OR registry_code IS NOT NULL OR display_name <> '')
);
CREATE INDEX inheritance_heir_case_idx ON inheritance_heir (inheritance_case_id, created_at);
CREATE INDEX inheritance_heir_organization_identity_idx ON inheritance_heir (organization_id, personal_code, registry_code);

CREATE TABLE inheritance_case_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inheritance_case_id UUID NOT NULL REFERENCES inheritance_case(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    event_type VARCHAR(48) NOT NULL,
    description TEXT NOT NULL,
    event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX inheritance_case_event_timeline_idx
    ON inheritance_case_event (inheritance_case_id, created_at DESC);

COMMENT ON TABLE inheritance_case IS 'Organisatsioonipõhine pärimisjuhtum; ametlik teade on otsusetoe allikas, mitte pärimisõiguse lõplik tõend.';
COMMENT ON TABLE inheritance_heir IS 'Pärija kontaktandmed ja töövoo seis; ligipääs ainult volitatud organisatsiooni kasutajatele.';
COMMENT ON TABLE inheritance_case_event IS 'Lisatav auditijälg pärimisjuhtumi alguse, staatuse, pärijate ja lõpetamise kohta.';
