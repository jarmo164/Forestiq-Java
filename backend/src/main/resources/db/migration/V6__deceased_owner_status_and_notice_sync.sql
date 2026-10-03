-- A deceased owner is no longer part of the ordinary sales pipeline. The linked
-- inheritance case becomes the only active workflow for that person.
INSERT INTO owner_status_definition (organization_id, id, color_hex, days_out_of_search, is_protected)
SELECT organization.id, 'DECEASED', '4b5563', 0, TRUE
FROM organization
ON CONFLICT (organization_id, id) DO NOTHING;

CREATE TABLE inheritance_notice_sync (
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL,
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_notice_number VARCHAR(100),
    last_error TEXT,
    PRIMARY KEY (organization_id, owner_id),
    CONSTRAINT inheritance_notice_sync_owner_fk
        FOREIGN KEY (organization_id, owner_id)
        REFERENCES owner (organization_id, id)
        ON DELETE CASCADE
);

CREATE INDEX inheritance_notice_sync_due_idx
    ON inheritance_notice_sync (organization_id, last_checked_at);
