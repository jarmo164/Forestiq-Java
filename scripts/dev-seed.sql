\set ON_ERROR_STOP on

-- Explicit local-development data. This file is never executed by Flyway.
-- Run it manually against a development database when sample CRM data is useful.

INSERT INTO organization (id, name)
VALUES ('dddddddd-1111-1111-1111-111111111111', 'ForestIQ Local Demo')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, email, phone, status, assigned_to_subject)
VALUES
    ('dddddddd-2222-2222-2222-222222222222', 'dddddddd-1111-1111-1111-111111111111', 'Mari Mets', 'DEV48901010011', 'mari.mets@example.test', '+372 5550 0101', 'INTERESTED', NULL),
    ('dddddddd-3333-3333-3333-333333333333', 'dddddddd-1111-1111-1111-111111111111', 'OÜ Kuusemetsa', 'DEV14999999', 'info@kuusemetsa.example.test', '+372 5550 0102', 'EVALUATION', NULL)
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    personal_or_registry_code = EXCLUDED.personal_or_registry_code,
    email = EXCLUDED.email,
    phone = EXCLUDED.phone,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO land_parcel (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
VALUES
    ('dddddddd-4444-4444-4444-444444444444', 'dddddddd-1111-1111-1111-111111111111', 'dddddddd-2222-2222-2222-222222222222', '78401:101:9101', 12.4500, ST_GeomFromText('POLYGON((24.753 59.437,24.763 59.437,24.763 59.442,24.753 59.442,24.753 59.437))', 4326)),
    ('dddddddd-5555-5555-5555-555555555555', 'dddddddd-1111-1111-1111-111111111111', 'dddddddd-3333-3333-3333-333333333333', '78401:101:9102', 8.3200, ST_GeomFromText('POLYGON((24.770 59.430,24.779 59.430,24.779 59.435,24.770 59.435,24.770 59.430))', 4326))
ON CONFLICT (id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    cadastral_code = EXCLUDED.cadastral_code,
    area_hectares = EXCLUDED.area_hectares,
    boundary = EXCLUDED.boundary,
    updated_at = CURRENT_TIMESTAMP;
