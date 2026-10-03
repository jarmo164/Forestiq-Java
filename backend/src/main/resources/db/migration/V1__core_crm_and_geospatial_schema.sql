CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (name)
);

CREATE TABLE owner (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    display_name VARCHAR(300) NOT NULL,
    personal_or_registry_code VARCHAR(32) NOT NULL,
    email VARCHAR(320),
    phone VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    assigned_to_subject VARCHAR(100),
    search_document TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(display_name, '') || ' ' || coalesce(personal_or_registry_code, '') || ' ' || coalesce(email, '') || ' ' || coalesce(phone, ''))
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT owner_status_check CHECK (status IN ('NEW', 'CONTACTED', 'INTERESTED', 'NOT_INTERESTED', 'EVALUATION', 'CONTRACTED')),
    CONSTRAINT owner_organization_code_unique UNIQUE (organization_id, personal_or_registry_code)
);
CREATE INDEX owner_organization_status_idx ON owner (organization_id, status);
CREATE INDEX owner_search_document_idx ON owner USING GIN (search_document);

CREATE TABLE land_parcel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    cadastral_code VARCHAR(32) NOT NULL,
    area_hectares NUMERIC(12, 4) NOT NULL CHECK (area_hectares >= 0),
    boundary geometry(Geometry, 4326),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT parcel_organization_code_unique UNIQUE (organization_id, cadastral_code)
);
CREATE INDEX land_parcel_boundary_gix ON land_parcel USING GIST (boundary);
CREATE INDEX land_parcel_owner_idx ON land_parcel (organization_id, owner_id);

CREATE TABLE owner_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    activity_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT owner_activity_type_check CHECK (activity_type IN ('NOTE', 'STATUS_CHANGED', 'ASSIGNED'))
);
CREATE INDEX owner_activity_timeline_idx ON owner_activity (organization_id, owner_id, created_at DESC);

CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    owner_id UUID REFERENCES owner(id) ON DELETE SET NULL,
    object_key VARCHAR(512) NOT NULL,
    original_file_name VARCHAR(512) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    created_by_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT document_organization_key_unique UNIQUE (organization_id, object_key)
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

INSERT INTO organization (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'ForestIQ Demo') ON CONFLICT DO NOTHING;

INSERT INTO owner (id, organization_id, display_name, personal_or_registry_code, email, phone, status, assigned_to_subject)
VALUES
    ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'Mari Mets', '48901010011', 'mari.mets@example.test', '+372 5550 0101', 'INTERESTED', '11111111-2222-3333-4444-555555555555'),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'OÜ Kuusemetsa', '14999999', 'info@kuusemetsa.example.test', '+372 5550 0102', 'EVALUATION', '11111111-2222-3333-4444-555555555555')
ON CONFLICT DO NOTHING;

INSERT INTO land_parcel (id, organization_id, owner_id, cadastral_code, area_hectares, boundary)
VALUES
    ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '78401:101:0101', 12.4500, ST_GeomFromText('POLYGON((24.753 59.437,24.763 59.437,24.763 59.442,24.753 59.442,24.753 59.437))', 4326)),
    ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', '78401:101:0102', 8.3200, ST_GeomFromText('POLYGON((24.770 59.430,24.779 59.430,24.779 59.435,24.770 59.435,24.770 59.430))', 4326))
ON CONFLICT DO NOTHING;

INSERT INTO owner_activity (organization_id, owner_id, activity_type, content, created_by_subject)
VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'NOTE', 'Esimene kontakt loodud; omanik on huvitatud pakkumise arutamisest.', '11111111-2222-3333-4444-555555555555'),
    ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 'STATUS_CHANGED', 'Omanik suunati metsamajanduskava hindamise järjekorda.', '11111111-2222-3333-4444-555555555555');
