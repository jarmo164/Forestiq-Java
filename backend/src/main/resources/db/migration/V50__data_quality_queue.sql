-- Auditable data-quality work queue for CRM and deal hygiene (#201).

CREATE TABLE data_quality_scan_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    detected_count INTEGER NOT NULL DEFAULT 0,
    auto_resolved_count INTEGER NOT NULL DEFAULT 0,
    created_by_subject VARCHAR(100) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT data_quality_scan_status_check CHECK (status IN ('RUNNING','SUCCESS')),
    CONSTRAINT data_quality_scan_counts_check CHECK (detected_count >= 0 AND auto_resolved_count >= 0)
);
CREATE INDEX data_quality_scan_org_idx ON data_quality_scan_run (organization_id, started_at DESC);

CREATE TABLE data_quality_issue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    deal_case_id UUID REFERENCES deal_case(id) ON DELETE CASCADE,
    issue_type VARCHAR(40) NOT NULL,
    source_name VARCHAR(160) NOT NULL,
    source_key VARCHAR(240) NOT NULL,
    summary VARCHAR(1200) NOT NULL,
    impact VARCHAR(16) NOT NULL,
    recommended_subject VARCHAR(100),
    assigned_subject VARCHAR(100),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    action_reason VARCHAR(2000),
    last_scan_id UUID REFERENCES data_quality_scan_run(id) ON DELETE SET NULL,
    updated_by_subject VARCHAR(100) NOT NULL DEFAULT 'system-data-quality',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT data_quality_issue_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id)
        ON DELETE CASCADE,
    CONSTRAINT data_quality_issue_type_check CHECK (issue_type IN (
        'MISSING_CONTACT','STALE_OWNER_DATA','DUPLICATE_CONTACT','MISSING_DEAL_INFO'
    )),
    CONSTRAINT data_quality_issue_impact_check CHECK (impact IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    CONSTRAINT data_quality_issue_status_check CHECK (status IN ('OPEN','ASSIGNED','RESOLVED')),
    CONSTRAINT data_quality_issue_resolution_check CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND action_reason IS NOT NULL)
        OR (status <> 'RESOLVED')
    ),
    UNIQUE (organization_id, issue_type, source_key)
);
CREATE INDEX data_quality_issue_active_idx
    ON data_quality_issue (organization_id, status, impact, first_detected_at, id)
    WHERE status <> 'RESOLVED';
CREATE INDEX data_quality_issue_assignee_idx
    ON data_quality_issue (organization_id, assigned_subject, status, first_detected_at, id);
CREATE INDEX data_quality_issue_owner_idx
    ON data_quality_issue (organization_id, owner_id, first_detected_at DESC);

CREATE TABLE data_quality_issue_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    issue_id UUID NOT NULL REFERENCES data_quality_issue(id) ON DELETE CASCADE,
    event_type VARCHAR(24) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    reason VARCHAR(2000),
    actor_subject VARCHAR(100) NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT data_quality_event_type_check CHECK (event_type IN (
        'DETECTED','REOPENED','ASSIGNED','RELEASED','RESOLVED'
    ))
);
CREATE INDEX data_quality_issue_event_idx
    ON data_quality_issue_event (organization_id, issue_id, created_at DESC, id DESC);

CREATE OR REPLACE FUNCTION audit_data_quality_issue() RETURNS trigger AS $$
DECLARE
    event_name VARCHAR(24);
BEGIN
    IF TG_OP = 'INSERT' THEN
        event_name := 'DETECTED';
    ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
        IF OLD.status = 'RESOLVED' AND NEW.status IN ('OPEN','ASSIGNED') THEN
            event_name := 'REOPENED';
        ELSIF OLD.status = 'ASSIGNED' AND NEW.status = 'OPEN' THEN
            event_name := 'RELEASED';
        ELSIF NEW.status = 'ASSIGNED' THEN
            event_name := 'ASSIGNED';
        ELSIF NEW.status = 'RESOLVED' THEN
            event_name := 'RESOLVED';
        END IF;
    ELSIF OLD.assigned_subject IS DISTINCT FROM NEW.assigned_subject AND NEW.status = 'ASSIGNED' THEN
        event_name := 'ASSIGNED';
    END IF;

    IF event_name IS NOT NULL THEN
        INSERT INTO data_quality_issue_event
            (organization_id, issue_id, event_type, from_status, to_status, reason, actor_subject, snapshot)
        VALUES (
            NEW.organization_id,
            NEW.id,
            event_name,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END,
            NEW.status,
            NEW.action_reason,
            NEW.updated_by_subject,
            jsonb_build_object(
                'issueType', NEW.issue_type,
                'sourceName', NEW.source_name,
                'sourceKey', NEW.source_key,
                'summary', NEW.summary,
                'impact', NEW.impact,
                'recommendedSubject', COALESCE(NEW.recommended_subject, ''),
                'assignedSubject', COALESCE(NEW.assigned_subject, '')
            )
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_data_quality_issue_audit
AFTER INSERT OR UPDATE ON data_quality_issue
FOR EACH ROW EXECUTE FUNCTION audit_data_quality_issue();

CREATE OR REPLACE FUNCTION protect_data_quality_issue_event() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'data_quality_issue_event is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_data_quality_issue_event_immutable
BEFORE UPDATE OR DELETE ON data_quality_issue_event
FOR EACH ROW EXECUTE FUNCTION protect_data_quality_issue_event();

-- Keep recurring rule evaluation index-friendly on the production-sized owner dataset.
CREATE INDEX data_quality_missing_contact_source_idx
    ON owner (organization_id, id)
    WHERE COALESCE(btrim(phone), '') = '' AND COALESCE(btrim(email), '') = '';
CREATE INDEX data_quality_owner_stale_source_idx
    ON owner (organization_id, updated_at, id);
CREATE INDEX data_quality_owner_email_source_idx
    ON owner (organization_id, lower(btrim(email)), id)
    WHERE email IS NOT NULL AND btrim(email) <> '';
CREATE INDEX data_quality_owner_phone_source_idx
    ON owner (organization_id, btrim(phone), id)
    WHERE phone IS NOT NULL AND btrim(phone) <> '';
CREATE INDEX data_quality_relation_freshness_idx
    ON owner_parcel_relation (organization_id, owner_id, source_seen_at DESC)
    WHERE active = TRUE;
