\set ON_ERROR_STOP on

-- Deterministic data used only by Compose-backed Playwright tests.
-- The script is intentionally idempotent so a local E2E run can be reset.

-- Reset workflow state for the fixed E2E owners before restoring their baseline.
DELETE FROM deal_case
WHERE organization_id = '11111111-1111-1111-1111-111111111111'
  AND owner_id IN (
    'bbbbbbbb-0000-0000-0000-000000000001',
    'bbbbbbbb-0000-0000-0000-000000000002',
    'bbbbbbbb-0000-0000-0000-000000000003'
  );

DELETE FROM owner_parcel_relation
WHERE organization_id = '11111111-1111-1111-1111-111111111111'
  AND owner_id IN (
    'bbbbbbbb-0000-0000-0000-000000000001',
    'bbbbbbbb-0000-0000-0000-000000000002',
    'bbbbbbbb-0000-0000-0000-000000000003'
  );

DELETE FROM reminder
WHERE organization_id = '11111111-1111-1111-1111-111111111111'
  AND owner_id IN (
    'bbbbbbbb-0000-0000-0000-000000000001',
    'bbbbbbbb-0000-0000-0000-000000000002',
    'bbbbbbbb-0000-0000-0000-000000000003'
  );

DELETE FROM owner_activity
WHERE organization_id = '11111111-1111-1111-1111-111111111111'
  AND owner_id IN (
    'bbbbbbbb-0000-0000-0000-000000000001',
    'bbbbbbbb-0000-0000-0000-000000000002',
    'bbbbbbbb-0000-0000-0000-000000000003'
  );

INSERT INTO owner (
    id, organization_id, display_name, personal_or_registry_code, email, phone,
    status, assigned_to_subject, lock_version
) VALUES
    (
      'bbbbbbbb-0000-0000-0000-000000000001',
      '11111111-1111-1111-1111-111111111111',
      'E2E Assignment Owner', 'E2E0001', 'assignment@example.test', '+372 5559 0001',
      'NEW', NULL, 0
    ),
    (
      'bbbbbbbb-0000-0000-0000-000000000002',
      '11111111-1111-1111-1111-111111111111',
      'E2E Deal Owner', 'E2E0002', 'deal@example.test', '+372 5559 0002',
      'INTERESTED', 'aaaaaaaa-0000-0000-0000-000000000002', 0
    ),
    (
      'bbbbbbbb-0000-0000-0000-000000000003',
      '11111111-1111-1111-1111-111111111111',
      'E2E Foreign Owner', 'E2E0003', 'foreign@example.test', '+372 5559 0003',
      'INTERESTED', 'aaaaaaaa-0000-0000-0000-000000000003', 0
    )
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    personal_or_registry_code = EXCLUDED.personal_or_registry_code,
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    status = EXCLUDED.status,
    assigned_to_subject = EXCLUDED.assigned_to_subject,
    lock_version = 0,
    updated_at = CURRENT_TIMESTAMP;

-- A deterministic owner in another organization proves tenant isolation even for
-- organization-wide roles such as ORG_ADMIN and CRM_MANAGER.
INSERT INTO owner (
    id, organization_id, display_name, personal_or_registry_code, email, phone,
    status, assigned_to_subject, lock_version
) VALUES (
    '99999999-0000-0000-0000-000000000001',
    '99999999-9999-9999-9999-999999999999',
    'E2E Cross Tenant Owner', 'E2E-XTENANT', 'cross-tenant@example.test', '+372 5559 9999',
    'NEW', NULL, 0
)
ON CONFLICT (id) DO UPDATE SET
    organization_id = EXCLUDED.organization_id,
    display_name = EXCLUDED.display_name,
    personal_or_registry_code = EXCLUDED.personal_or_registry_code,
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    status = EXCLUDED.status,
    assigned_to_subject = NULL,
    lock_version = 0,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO land_parcel (
    id, organization_id, owner_id, cadastral_code, area_hectares, boundary
) VALUES
    (
      'cccccccc-0000-0000-0000-000000000001',
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000001',
      '10001:001:0001', 10.1000,
      ST_GeomFromText('POLYGON((24.700 59.400,24.705 59.400,24.705 59.405,24.700 59.405,24.700 59.400))', 4326)
    ),
    (
      'cccccccc-0000-0000-0000-000000000002',
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000002',
      '10001:001:0002', 20.2000,
      ST_GeomFromText('POLYGON((24.710 59.400,24.715 59.400,24.715 59.405,24.710 59.405,24.710 59.400))', 4326)
    ),
    (
      'cccccccc-0000-0000-0000-000000000003',
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000003',
      '10001:001:0003', 30.3000,
      ST_GeomFromText('POLYGON((24.720 59.400,24.725 59.400,24.725 59.405,24.720 59.405,24.720 59.400))', 4326)
    )
ON CONFLICT (id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    cadastral_code = EXCLUDED.cadastral_code,
    area_hectares = EXCLUDED.area_hectares,
    boundary = EXCLUDED.boundary,
    updated_at = CURRENT_TIMESTAMP;

-- Contract generation requires an organization-owned default company and
-- template. The production bootstrap removes legacy demo configuration, so
-- E2E must establish its own explicit, tenant-scoped prerequisite.
INSERT INTO company_profile (
    id, organization_id, legal_name, registry_code, address, email,
    representative_name, representative_role, is_default
) VALUES (
    'ffffffff-0000-0000-0000-000000000001',
    '11111111-1111-1111-1111-111111111111',
    'ForestIQ E2E OÜ', '14999999', 'Testtänav 1, Tallinn', 'e2e@forestiq.test',
    'E2E Esindaja', 'juhatuse liige', TRUE
)
ON CONFLICT (id) DO UPDATE SET
    legal_name = EXCLUDED.legal_name,
    registry_code = EXCLUDED.registry_code,
    address = EXCLUDED.address,
    email = EXCLUDED.email,
    representative_name = EXCLUDED.representative_name,
    representative_role = EXCLUDED.representative_role,
    is_default = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO contract_template (
    id, organization_id, company_profile_id, name, code, description,
    html_template, active, is_default, created_by_subject, updated_by_subject
) VALUES (
    'ffffffff-0000-0000-0000-000000000002',
    '11111111-1111-1111-1111-111111111111',
    'ffffffff-0000-0000-0000-000000000001',
    'ForestIQ E2E leping', 'FORESTIQ_E2E_DEFAULT',
    'Playwright end-to-end contract template.',
    '<!DOCTYPE html><html><head><meta charset="UTF-8"/><style>@page { size: A4; margin: 24mm 18mm; } body { font-family: sans-serif; font-size: 10pt; }</style></head><body><h1>ForestIQ E2E leping</h1><p>Leping nr {{contract.number}}</p><p>Kuupäev: {{contract.date}}; koht: {{contract.place}}</p><p>Ostja: {{company.legalName}}, {{company.registryCode}}</p><p>Müüja: {{seller.name}}, {{seller.code}}</p><p>Hind: {{contract.price}} eurot</p><p>{{contract.terms}}</p><table><thead><tr><th>Katastritunnus</th><th>Aadress</th><th>Pindala</th></tr></thead><tbody>{{contract.parcelsRows}}</tbody></table></body></html>',
    TRUE, TRUE, 'e2e-seed', 'e2e-seed'
)
ON CONFLICT (id) DO UPDATE SET
    company_profile_id = EXCLUDED.company_profile_id,
    name = EXCLUDED.name,
    code = EXCLUDED.code,
    description = EXCLUDED.description,
    html_template = EXCLUDED.html_template,
    active = TRUE,
    is_default = TRUE,
    updated_by_subject = 'e2e-seed',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO owner_parcel_relation (
    organization_id, owner_id, parcel_id, source, active, source_seen_at
) VALUES
    (
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000001',
      'cccccccc-0000-0000-0000-000000000001',
      'METSIS_PORTFOLIO', true, CURRENT_TIMESTAMP
    ),
    (
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000002',
      'cccccccc-0000-0000-0000-000000000002',
      'METSIS_PORTFOLIO', true, CURRENT_TIMESTAMP
    ),
    (
      '11111111-1111-1111-1111-111111111111',
      'bbbbbbbb-0000-0000-0000-000000000003',
      'cccccccc-0000-0000-0000-000000000003',
      'METSIS_PORTFOLIO', true, CURRENT_TIMESTAMP
    );

-- Map reads use a published Maa- ja Ruumiamet generation, not the legacy
-- land_parcel.boundary column. Keep the E2E fixture aligned with that reader.
INSERT INTO geospatial_source_run (id, source_key, trigger_type, status, started_at, completed_at)
VALUES (
    'dddddddd-0000-0000-0000-000000000001',
    'MARU_CADASTRE',
    'BOOTSTRAP',
    'SUCCESS',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO geospatial_generation (
    id, source_key, run_id, status, source_date, source_version,
    artifact_checksum, canonical_srid, row_count, validation_summary,
    validated_at, published_at
) VALUES (
    'eeeeeeee-0000-0000-0000-000000000001',
    'MARU_CADASTRE',
    'dddddddd-0000-0000-0000-000000000001',
    'STAGING',
    DATE '2026-09-08',
    'e2e-fixture-v1',
    'sha256:e2e-fixture-v1',
    3301,
    3,
    '{"fixture": true}'::jsonb,
    CURRENT_TIMESTAMP,
    NULL
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO geospatial_cadastre_parcel (
    source_key, generation_id, cadastral_code, provider_feature_id,
    source_attributes, source_area_m2, geom
) VALUES
    (
      'MARU_CADASTRE', 'eeeeeeee-0000-0000-0000-000000000001', '10001:001:0001', 'e2e-1',
      '{"fixture": true}'::jsonb, 101000,
      ST_Multi(ST_Transform(ST_GeomFromText('POLYGON((24.700 59.400,24.705 59.400,24.705 59.405,24.700 59.405,24.700 59.400))', 4326), 3301))
    ),
    (
      'MARU_CADASTRE', 'eeeeeeee-0000-0000-0000-000000000001', '10001:001:0002', 'e2e-2',
      '{"fixture": true}'::jsonb, 202000,
      ST_Multi(ST_Transform(ST_GeomFromText('POLYGON((24.710 59.400,24.715 59.400,24.715 59.405,24.710 59.405,24.710 59.400))', 4326), 3301))
    ),
    (
      'MARU_CADASTRE', 'eeeeeeee-0000-0000-0000-000000000001', '10001:001:0003', 'e2e-3',
      '{"fixture": true}'::jsonb, 303000,
      ST_Multi(ST_Transform(ST_GeomFromText('POLYGON((24.720 59.400,24.725 59.400,24.725 59.405,24.720 59.405,24.720 59.400))', 4326), 3301))
    )
ON CONFLICT (generation_id, cadastral_code) DO NOTHING;

UPDATE geospatial_generation
   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
 WHERE id = 'eeeeeeee-0000-0000-0000-000000000001'
   AND status = 'STAGING';

INSERT INTO geospatial_source_current (source_key, generation_id, published_at)
VALUES ('MARU_CADASTRE', 'eeeeeeee-0000-0000-0000-000000000001', CURRENT_TIMESTAMP)
ON CONFLICT (source_key) DO UPDATE
    SET generation_id = EXCLUDED.generation_id,
        published_at = EXCLUDED.published_at;

INSERT INTO owner_activity (organization_id, owner_id, activity_type, content, created_by_subject)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000002', 'NOTE', 'E2E qualification baseline.', 'aaaaaaaa-0000-0000-0000-000000000002'),
  ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000003', 'NOTE', 'E2E cross-assignment baseline.', 'aaaaaaaa-0000-0000-0000-000000000003');

-- Keep all current parcels fresh so the registry recovery E2E proves the running
-- recovery endpoint/UI without depending on public registry network availability.
INSERT INTO registry_sync_log (
    organization_id, cadastral_code, registry_name, status, records_updated,
    synced_by_subject, created_at
)
SELECT p.organization_id, p.cadastral_code, source.registry_name, 'SUCCESS', 1, 'e2e-seed', CURRENT_TIMESTAMP
FROM land_parcel p
CROSS JOIN (VALUES
    ('MAAAMET_WFS'),
    ('METSAREGISTER_TEATISED'),
    ('METSAREGISTER_ERALDISED')
) AS source(registry_name)
WHERE p.organization_id = '11111111-1111-1111-1111-111111111111';
