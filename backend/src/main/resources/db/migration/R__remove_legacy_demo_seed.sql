-- V1 historically inserted demo CRM data. Do not edit V1: changing an applied Flyway
-- migration would break checksum validation in existing installations.
--
-- This repeatable cleanup removes the legacy demo organization only when the database
-- still contains the exact untouched seed shape. After cleanup, keep a single empty
-- bootstrap organization so a fresh deployment has a tenant that matches the initial
-- Keycloak administrator.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM organization
        WHERE id = '11111111-1111-1111-1111-111111111111'
          AND name = 'ForestIQ Demo'
    )
    AND (SELECT count(*) FROM owner WHERE organization_id = '11111111-1111-1111-1111-111111111111') = 2
    AND EXISTS (
        SELECT 1 FROM owner
        WHERE id = '22222222-2222-2222-2222-222222222222'
          AND organization_id = '11111111-1111-1111-1111-111111111111'
          AND display_name = 'Mari Mets'
          AND personal_or_registry_code = '48901010011'
          AND email = 'mari.mets@example.test'
    )
    AND EXISTS (
        SELECT 1 FROM owner
        WHERE id = '33333333-3333-3333-3333-333333333333'
          AND organization_id = '11111111-1111-1111-1111-111111111111'
          AND display_name = 'OÜ Kuusemetsa'
          AND personal_or_registry_code = '14999999'
          AND email = 'info@kuusemetsa.example.test'
    )
    AND (SELECT count(*) FROM land_parcel WHERE organization_id = '11111111-1111-1111-1111-111111111111') = 2
    AND EXISTS (
        SELECT 1 FROM land_parcel
        WHERE id = '44444444-4444-4444-4444-444444444444'
          AND owner_id = '22222222-2222-2222-2222-222222222222'
          AND cadastral_code = '78401:101:0101'
    )
    AND EXISTS (
        SELECT 1 FROM land_parcel
        WHERE id = '55555555-5555-5555-5555-555555555555'
          AND owner_id = '33333333-3333-3333-3333-333333333333'
          AND cadastral_code = '78401:101:0102'
    )
    THEN
        -- Foreign keys are ON DELETE CASCADE for the seed-owned CRM rows.
        DELETE FROM organization
        WHERE id = '11111111-1111-1111-1111-111111111111'
          AND name = 'ForestIQ Demo';
    END IF;

    -- Fresh production/pilot databases need one tenant for the generated initial
    -- Keycloak administrator. Existing tenant data is never replaced.
    IF NOT EXISTS (SELECT 1 FROM organization) THEN
        INSERT INTO organization (id, name)
        VALUES ('11111111-1111-1111-1111-111111111111', 'ForestIQ');
    END IF;

    -- Deleting the untouched legacy demo tenant cascades tenant-scoped defaults that
    -- earlier versioned migrations seeded. Restore the complete built-in bootstrap
    -- configuration here. Because this migration is repeatable, it also self-heals
    -- databases where an older cleanup already recreated ForestIQ without these rows.
    -- Other tenant configurations are intentionally not touched.
    IF EXISTS (
        SELECT 1
        FROM organization
        WHERE id = '11111111-1111-1111-1111-111111111111'
          AND name = 'ForestIQ'
    ) THEN
        INSERT INTO owner_status_definition
            (organization_id, id, color_hex, days_out_of_search, is_protected)
        SELECT '11111111-1111-1111-1111-111111111111'::uuid,
               status.id, status.color_hex, status.days_out_of_search, TRUE
        FROM (
            VALUES
                ('NEW', '6b7280', 0),
                ('CONTACTED', '2563eb', 0),
                ('INTERESTED', '16a34a', 0),
                ('NOT_INTERESTED', 'dc2626', 30),
                ('EVALUATION', '9333ea', 0),
                ('CONTRACTED', '047857', 0),
                ('ASSIGNED', 'ca8a04', 0),
                ('WAITS_FOR_EVALUATION', '0891b2', 0),
                ('DECEASED', '4b5563', 0),
                ('VALUED', '0f766e', 0),
                ('NEGOTIATION', 'd97706', 0)
        ) AS status(id, color_hex, days_out_of_search)
        ON CONFLICT (organization_id, id) DO NOTHING;

        INSERT INTO contact_outcome_definition
            (organization_id, code, label, requires_reason, active, sort_order)
        SELECT '11111111-1111-1111-1111-111111111111'::uuid,
               outcome.code, outcome.label, outcome.requires_reason, TRUE, outcome.sort_order
        FROM (
            VALUES
                ('CONTACTED', 'Kontakt loodud', FALSE, 10),
                ('NO_ANSWER', 'Ei vastanud', FALSE, 20),
                ('INTERESTED', 'Huvitatud', FALSE, 30),
                ('NOT_INTERESTED', 'Ei ole huvitatud', TRUE, 40),
                ('FOLLOW_UP', 'Vajab järeltegevust', FALSE, 50)
        ) AS outcome(code, label, requires_reason, sort_order)
        ON CONFLICT (organization_id, code) DO NOTHING;

        INSERT INTO deal_loss_reason_definition
            (organization_id, code, label, active, sort_order)
        SELECT '11111111-1111-1111-1111-111111111111'::uuid,
               reason.code, reason.label, TRUE, reason.sort_order
        FROM (
            VALUES
                ('PRICE_GAP', 'Hinnavahe', 10),
                ('CUSTOMER_WITHDREW', 'Klient loobus', 20),
                ('COMPETITOR', 'Konkurent', 30),
                ('TITLE_OR_LEGAL_RISK', 'Õiguslik või omandirisk', 40),
                ('PROPERTY_NOT_SUITABLE', 'Kinnistu ei sobinud', 50),
                ('NO_RESPONSE', 'Klient ei vastanud', 60),
                ('OTHER', 'Muu', 100)
        ) AS reason(code, label, sort_order)
        ON CONFLICT (organization_id, code) DO NOTHING;

        INSERT INTO stage_probability_config
            (organization_id, stage, probability, version_no, created_by_subject)
        SELECT '11111111-1111-1111-1111-111111111111'::uuid,
               probability.stage, probability.probability, 1, 'system-migration'
        FROM (
            VALUES
                ('QUALIFIED', 0.10::numeric),
                ('EVALUATION_REQUESTED', 0.20::numeric),
                ('EVALUATING', 0.25::numeric),
                ('VALUED', 0.40::numeric),
                ('OFFER_PREPARED', 0.55::numeric),
                ('OFFER_SENT', 0.65::numeric),
                ('NEGOTIATION', 0.75::numeric),
                ('WON', 1.00::numeric),
                ('LOST', 0.00::numeric),
                ('CANCELLED', 0.00::numeric)
        ) AS probability(stage, probability)
        ON CONFLICT DO NOTHING;
    END IF;
END $$;
