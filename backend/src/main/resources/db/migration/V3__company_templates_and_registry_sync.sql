-- V3: Company profiles, editable contract templates and external registry sync logs

CREATE TABLE company_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    legal_name VARCHAR(300) NOT NULL,
    registry_code VARCHAR(32),
    vat_code VARCHAR(32),
    address TEXT,
    email VARCHAR(320),
    phone VARCHAR(64),
    website VARCHAR(512),
    bank_name VARCHAR(180),
    iban VARCHAR(64),
    bic VARCHAR(32),
    representative_name VARCHAR(300),
    representative_role VARCHAR(180),
    logo_object_key VARCHAR(512),
    is_default BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT company_profile_organization_name_unique UNIQUE (organization_id, legal_name)
);
CREATE UNIQUE INDEX company_profile_default_per_org_idx ON company_profile (organization_id) WHERE is_default;

CREATE TABLE contract_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    company_profile_id UUID REFERENCES company_profile(id) ON DELETE SET NULL,
    name VARCHAR(240) NOT NULL,
    code VARCHAR(100) NOT NULL,
    description TEXT,
    document_type VARCHAR(16) NOT NULL DEFAULT 'PDF' CHECK (document_type IN ('PDF', 'DOCX')),
    html_template TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_subject VARCHAR(100) NOT NULL,
    updated_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT contract_template_organization_code_unique UNIQUE (organization_id, code)
);
CREATE UNIQUE INDEX contract_template_default_per_org_idx ON contract_template (organization_id) WHERE is_default;

ALTER TABLE contract
    ADD COLUMN IF NOT EXISTS company_profile_id UUID REFERENCES company_profile(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS template_id UUID REFERENCES contract_template(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS object_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS file_name VARCHAR(512),
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(255);

CREATE TABLE registry_sync_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    cadastral_code VARCHAR(32) NOT NULL,
    registry_name VARCHAR(64) NOT NULL,
    request_url TEXT,
    status VARCHAR(32) NOT NULL,
    records_updated INT NOT NULL DEFAULT 0,
    error_message TEXT,
    raw_response JSONB,
    synced_by_subject VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX registry_sync_log_parcel_idx ON registry_sync_log (organization_id, cadastral_code, created_at DESC);

INSERT INTO company_profile (
    id, organization_id, legal_name, registry_code, address, email, representative_name, representative_role, is_default
) VALUES (
    '66666666-6666-6666-6666-666666666666',
    '11111111-1111-1111-1111-111111111111',
    'ForestIQ Demo OÜ',
    '14999999',
    'Näidistänav 1, Tallinn',
    'info@forestiq.local',
    'Metsa Ekspert',
    'juhatuse liige',
    TRUE
) ON CONFLICT DO NOTHING;

INSERT INTO contract_template (
    id, organization_id, company_profile_id, name, code, description, html_template, active, is_default,
    created_by_subject, updated_by_subject
) VALUES (
    '77777777-7777-7777-7777-777777777777',
    '11111111-1111-1111-1111-111111111111',
    '66666666-6666-6666-6666-666666666666',
    'Metsaraieõiguse ostu-müügileping',
    'FOREST_HARVEST_DEFAULT',
    'Vaikimisi lepingumall metsa raieõiguse võõrandamiseks.',
    '<!DOCTYPE html><html><head><meta charset="UTF-8"/><style>@page { size: A4; margin: 24mm 18mm; } body { font-family: DejaVu Sans, sans-serif; font-size: 10pt; line-height: 1.45; color: #18261a; } h1 { text-align: center; font-size: 15pt; margin-bottom: 22px; } h2 { font-size: 11pt; margin-top: 16px; } .meta { margin-bottom: 18px; } .signature { margin-top: 44px; display: table; width: 100%; } .signature div { display: table-cell; width: 50%; padding-right: 24px; } .line { border-top: 1px solid #444; padding-top: 4px; margin-top: 42px; } table { border-collapse: collapse; width: 100%; margin: 10px 0; } th, td { border: 1px solid #888; padding: 5px; text-align: left; }</style></head><body><h1>METSA RAIEÕIGUSE OSTU-MÜÜGILEPING</h1><p class="meta">Leping nr {{contract.number}}<br/>Kuupäev: {{contract.date}}<br/>Koht: {{contract.place}}</p><h2>1. Pooled</h2><p><strong>Ostja:</strong> {{company.legalName}}, registrikood {{company.registryCode}}, aadress {{company.address}}, esindaja {{company.representativeName}} ({{company.representativeRole}}).</p><p><strong>Müüja:</strong> {{seller.name}}, isiku- või registrikood {{seller.code}}, aadress {{seller.address}}.</p><h2>2. Lepingu ese</h2><p>Müüja võõrandab Ostjale kasvava metsa raieõiguse järgmistel kinnistutel:</p><table><thead><tr><th>Katastritunnus</th><th>Aadress</th><th>Pindala (ha)</th></tr></thead><tbody>{{contract.parcelsRows}}</tbody></table><h2>3. Hind ja tasumine</h2><p>Raieõiguse müügihind on <strong>{{contract.price}} eurot</strong>. Tasumine toimub poolte kokkuleppel Müüja pangakontole {{seller.iban}}.</p><h2>4. Lõppsätted</h2><p>Leping jõustub allkirjastamisel. Leping on koostatud kahes võrdset juriidilist jõudu omavas eksemplaris.</p><div class="signature"><div><div class="line">Müüja: {{seller.name}}</div></div><div><div class="line">Ostja: {{company.representativeName}}</div></div></div></body></html>',
    TRUE, TRUE, 'system', 'system'
) ON CONFLICT DO NOTHING;
