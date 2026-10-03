-- V4: tenant-aware integrity constraints and default owner statuses.

ALTER TABLE owner
    ADD CONSTRAINT owner_organization_id_unique UNIQUE (organization_id, id);

ALTER TABLE land_parcel
    ADD CONSTRAINT land_parcel_organization_id_unique UNIQUE (organization_id, id);

ALTER TABLE owner_status_definition
    DROP CONSTRAINT IF EXISTS owner_status_definition_pkey;
ALTER TABLE owner_status_definition
    ADD CONSTRAINT owner_status_definition_pkey PRIMARY KEY (organization_id, id);

INSERT INTO owner_status_definition (organization_id, id, color_hex, days_out_of_search, is_protected)
SELECT organization.id, status.id, status.color_hex, status.days_out_of_search, TRUE
FROM organization
CROSS JOIN (
    VALUES
        ('NEW', '6b7280', 0),
        ('CONTACTED', '2563eb', 0),
        ('INTERESTED', '16a34a', 0),
        ('NOT_INTERESTED', 'dc2626', 30),
        ('EVALUATION', '9333ea', 0),
        ('CONTRACTED', '047857', 0),
        ('ASSIGNED', 'ca8a04', 0),
        ('WAITS_FOR_EVALUATION', '0891b2', 0)
) AS status(id, color_hex, days_out_of_search)
ON CONFLICT (organization_id, id) DO NOTHING;

ALTER TABLE land_parcel
    DROP CONSTRAINT IF EXISTS land_parcel_owner_id_fkey;
ALTER TABLE land_parcel
    ADD CONSTRAINT land_parcel_organization_owner_fk
    FOREIGN KEY (organization_id, owner_id)
    REFERENCES owner (organization_id, id)
    ON DELETE CASCADE;

ALTER TABLE owner_activity
    DROP CONSTRAINT IF EXISTS owner_activity_owner_id_fkey;
ALTER TABLE owner_activity
    ADD CONSTRAINT owner_activity_organization_owner_fk
    FOREIGN KEY (organization_id, owner_id)
    REFERENCES owner (organization_id, id)
    ON DELETE CASCADE;

ALTER TABLE document
    DROP CONSTRAINT IF EXISTS document_owner_id_fkey;
ALTER TABLE document
    ADD CONSTRAINT document_organization_owner_fk
    FOREIGN KEY (organization_id, owner_id)
    REFERENCES owner (organization_id, id)
    ON DELETE SET NULL (owner_id);

ALTER TABLE reminder
    DROP CONSTRAINT IF EXISTS reminder_owner_id_fkey;
ALTER TABLE reminder
    ADD CONSTRAINT reminder_organization_owner_fk
    FOREIGN KEY (organization_id, owner_id)
    REFERENCES owner (organization_id, id)
    ON DELETE SET NULL (owner_id);

ALTER TABLE company_profile
    ADD CONSTRAINT company_profile_organization_id_unique UNIQUE (organization_id, id);

ALTER TABLE contract_template
    ADD CONSTRAINT contract_template_organization_id_unique UNIQUE (organization_id, id);
ALTER TABLE contract_template
    DROP CONSTRAINT IF EXISTS contract_template_company_profile_id_fkey;
ALTER TABLE contract_template
    ADD CONSTRAINT contract_template_organization_company_fk
    FOREIGN KEY (organization_id, company_profile_id)
    REFERENCES company_profile (organization_id, id)
    ON DELETE SET NULL (company_profile_id);

ALTER TABLE contract
    DROP CONSTRAINT IF EXISTS contract_company_profile_id_fkey;
ALTER TABLE contract
    DROP CONSTRAINT IF EXISTS contract_template_id_fkey;
ALTER TABLE contract
    ADD CONSTRAINT contract_organization_company_fk
    FOREIGN KEY (organization_id, company_profile_id)
    REFERENCES company_profile (organization_id, id)
    ON DELETE SET NULL (company_profile_id);
ALTER TABLE contract
    ADD CONSTRAINT contract_organization_template_fk
    FOREIGN KEY (organization_id, template_id)
    REFERENCES contract_template (organization_id, id)
    ON DELETE SET NULL (template_id);
