-- V2: Full legacy feature parity
-- Adds all tables needed to replicate the legacy MetsIS CRM functionality

-- ============================================================
-- 1. Dynamic owner statuses (admin-configurable)
-- ============================================================
CREATE TABLE owner_status_definition (
    id VARCHAR(100) PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    color_hex VARCHAR(6) NOT NULL DEFAULT 'cccccc',
    days_out_of_search INT NOT NULL DEFAULT 0 CHECK (days_out_of_search >= 0 AND days_out_of_search <= 50000),
    is_protected BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 2. Extend owner table with legacy fields
-- ============================================================
ALTER TABLE owner
    ADD COLUMN IF NOT EXISTS owner_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS address TEXT,
    ADD COLUMN IF NOT EXISTS info TEXT,
    ADD COLUMN IF NOT EXISTS status_set_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_cadastre_list_refresh BIGINT,
    ADD COLUMN IF NOT EXISTS out_of_admin_search_from TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS out_of_admin_search_to TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS out_of_admin_search_reason VARCHAR(100);

-- Remove the hard-coded status enum constraint and allow dynamic statuses
ALTER TABLE owner DROP CONSTRAINT IF EXISTS owner_status_check;

-- ============================================================
-- 3. Owner followings (users following an owner)
-- ============================================================
CREATE TABLE owner_following (
    owner_id UUID NOT NULL REFERENCES owner(id) ON DELETE CASCADE,
    user_subject VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, user_subject)
);

-- ============================================================
-- 4. Extend land_parcel (cadastre) with legacy fields
-- ============================================================
ALTER TABLE land_parcel
    ADD COLUMN IF NOT EXISTS name VARCHAR(300),
    ADD COLUMN IF NOT EXISTS address TEXT,
    ADD COLUMN IF NOT EXISTS county VARCHAR(120),
    ADD COLUMN IF NOT EXISTS municipality VARCHAR(120),
    ADD COLUMN IF NOT EXISTS postal VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reg_nr VARCHAR(64),
    ADD COLUMN IF NOT EXISTS parcel_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS marked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS mk_date BIGINT,
    ADD COLUMN IF NOT EXISTS yard_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS forest_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS arable_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS meadow_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS underwater_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS buildings_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS other_area NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS our_price VARCHAR(64),
    ADD COLUMN IF NOT EXISTS owners_price VARCHAR(64);

CREATE INDEX IF NOT EXISTS land_parcel_county_idx ON land_parcel (county);
CREATE INDEX IF NOT EXISTS land_parcel_municipality_idx ON land_parcel (municipality);

-- ============================================================
-- 5. Cadastre sub-parts (forest plan polygons)
-- ============================================================
CREATE TABLE cadastre_sub_part (
    id BIGSERIAL PRIMARY KEY,
    cadastral_code VARCHAR(32) NOT NULL,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    sub_part_code INT,
    tree_type_code VARCHAR(16),
    area NUMERIC(12,4),
    polygon TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX cadastre_sub_part_code_idx ON cadastre_sub_part (cadastral_code);

-- ============================================================
-- 6. Cadastre labels
-- ============================================================
CREATE TABLE cadastre_label (
    cadastral_code VARCHAR(32) NOT NULL,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    label VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cadastral_code, organization_id, label)
);

-- ============================================================
-- 7. Cadastre notifications (forest notifications from metsaregister)
-- ============================================================
CREATE TABLE cadastre_notification (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    cadastral_code VARCHAR(32) NOT NULL,
    notification_number BIGINT,
    amount_to_be_cut NUMERIC(12,4),
    area NUMERIC(12,4),
    cadastre_subpart_code BIGINT,
    damage_code VARCHAR(32),
    confirmation_date BIGINT,
    registration_date BIGINT,
    decision VARCHAR(512),
    state BIGINT,
    work_code VARCHAR(32),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    archive_date BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX cadastre_notification_code_idx ON cadastre_notification (cadastral_code);

-- ============================================================
-- 8. Forest registry features
-- ============================================================
CREATE TABLE forest_registry_feature (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    cadastral_code VARCHAR(32) NOT NULL,
    source_layer VARCHAR(120),
    source_id VARCHAR(120),
    subpart_code INT,
    title VARCHAR(300),
    work_code VARCHAR(32),
    decision VARCHAR(512),
    area NUMERIC(12,4),
    volume NUMERIC(12,4),
    event_date BIGINT,
    attributes TEXT,
    geometry TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX forest_registry_feature_code_idx ON forest_registry_feature (cadastral_code);

-- ============================================================
-- 9. Reminders
-- ============================================================
CREATE TABLE reminder (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    creator VARCHAR(100) NOT NULL,
    owner_id UUID REFERENCES owner(id) ON DELETE SET NULL,
    cadastral_code VARCHAR(32),
    property_name VARCHAR(300),
    reminder_text VARCHAR(500) NOT NULL,
    due_time TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX reminder_creator_idx ON reminder (creator, due_time);

-- ============================================================
-- 10. Internal messages
-- ============================================================
CREATE TABLE message (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    sender VARCHAR(100) NOT NULL,
    recipient VARCHAR(100) NOT NULL,
    message_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    noticed_at TIMESTAMPTZ
);
CREATE INDEX message_recipient_idx ON message (recipient, created_at DESC);
CREATE INDEX message_sender_idx ON message (sender, created_at DESC);

-- ============================================================
-- 11. Persons dump (contact book)
-- ============================================================
CREATE TABLE persons_dump (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    source VARCHAR(120),
    name VARCHAR(300),
    phone VARCHAR(64),
    address TEXT,
    code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX persons_dump_name_idx ON persons_dump (organization_id, name);

-- ============================================================
-- 12. Contracts and contract history
-- ============================================================
CREATE TABLE contract (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    base_id UUID,
    contract_data BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE contract_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    sellers TEXT,
    buyer TEXT,
    contract_no VARCHAR(120),
    cadastres TEXT,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX contract_history_created_idx ON contract_history (organization_id, created_at DESC);

-- ============================================================
-- 13. User statistics (owner status change tracking)
-- ============================================================
CREATE TABLE user_status_change_record (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    user_subject VARCHAR(100) NOT NULL,
    from_status VARCHAR(100),
    to_status VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX user_status_change_user_idx ON user_status_change_record (user_subject, created_at);

-- ============================================================
-- 14. Extend owner_activity with more types
-- ============================================================
ALTER TABLE owner_activity DROP CONSTRAINT IF EXISTS owner_activity_type_check;
ALTER TABLE owner_activity ADD CONSTRAINT owner_activity_type_check
    CHECK (activity_type IN ('NOTE', 'STATUS_CHANGED', 'ASSIGNED', 'CADASTRE_ADDED', 'CADASTRE_REMOVED', 'LABEL_CHANGED', 'EVALUATION', 'REMINDER', 'FOLLOWING', 'SYSTEM'));
