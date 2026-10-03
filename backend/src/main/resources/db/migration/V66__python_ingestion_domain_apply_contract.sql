-- V66: Narrow security-definer apply contract for Python-owned external ingestion.
--
-- Python owns provider I/O, normalization, staging and source checkpoints.
-- The forestiq_ingest role must not receive broad DML rights to CRM/domain tables.
-- Instead it may invoke these deterministic, migration-owned apply functions.
-- Each function validates tenant/source scope and exposes only the exact mutation
-- needed by one migrated source family.

GRANT CREATE ON SCHEMA ingest TO forestiq_ingest;
GRANT INSERT, UPDATE ON TABLE external_inheritance_notice TO forestiq_ingest;

CREATE OR REPLACE FUNCTION forestiq_ingest_apply_owner_portfolio(
    p_run_id UUID,
    p_organization_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_seen_at TIMESTAMPTZ := CURRENT_TIMESTAMP;
    v_added INTEGER := 0;
    v_closed INTEGER := 0;
    v_rows INTEGER := 0;
    v_staged_owners INTEGER := 0;
    v_active_source_relations INTEGER := 0;
BEGIN
    IF p_run_id IS NULL OR p_organization_id IS NULL THEN
        RAISE EXCEPTION 'owner portfolio run and organization are required';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.organization WHERE id = p_organization_id) THEN
        RAISE EXCEPTION 'unknown owner portfolio organization %', p_organization_id;
    END IF;

    SELECT count(*)::integer,
           count(DISTINCT owner_code)::integer
      INTO v_rows, v_staged_owners
      FROM public.owner_portfolio_import_stage
     WHERE run_id = p_run_id
       AND organization_id = p_organization_id;

    SELECT count(*)::integer
      INTO v_active_source_relations
      FROM public.owner_parcel_relation
     WHERE organization_id = p_organization_id
       AND source = 'METSIS_PORTFOLIO'
       AND active = TRUE;

    IF v_staged_owners = 0 AND v_active_source_relations > 0 THEN
        RAISE EXCEPTION
            'MetsIS empty snapshot did not safely confirm closing existing active source relations';
    END IF;

    INSERT INTO public.owner_snapshot_row
        (run_id, organization_id, owner_code, owner_name, owner_type)
    SELECT p_run_id,
           p_organization_id,
           first_row.owner_code,
           first_row.owner_name,
           first_row.owner_type
      FROM (
            SELECT DISTINCT ON (owner_code)
                   owner_code,
                   owner_name,
                   owner_type,
                   source_row_number
              FROM public.owner_portfolio_import_stage
             WHERE run_id = p_run_id
               AND organization_id = p_organization_id
             ORDER BY owner_code, source_row_number
           ) first_row
    ON CONFLICT (run_id, organization_id, owner_code) DO NOTHING;

    INSERT INTO public.owner_parcel_snapshot_row
        (run_id, organization_id, owner_code, owner_name, owner_type, cadastral_code)
    SELECT p_run_id,
           p_organization_id,
           s.owner_code,
           s.owner_name,
           s.owner_type,
           raw.cadastral_code
      FROM public.owner_snapshot_row s
      JOIN (
            SELECT DISTINCT owner_code, cadastral_code
              FROM public.owner_portfolio_import_stage
             WHERE run_id = p_run_id
               AND organization_id = p_organization_id
               AND cadastral_code IS NOT NULL
           ) raw ON raw.owner_code = s.owner_code
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
    ON CONFLICT (run_id, organization_id, owner_code, cadastral_code) DO NOTHING;

    INSERT INTO public.owner
        (id, organization_id, display_name, personal_or_registry_code, owner_type,
         source_display_name, source_owner_type, status, updated_at)
    SELECT gen_random_uuid(),
           s.organization_id,
           s.owner_name,
           s.owner_code,
           CASE WHEN upper(s.owner_type) = 'COMPANY' THEN 'FIRMA' ELSE 'ERAISIK' END,
           s.owner_name,
           CASE WHEN upper(s.owner_type) = 'COMPANY' THEN 'FIRMA' ELSE 'ERAISIK' END,
           'NEW',
           v_seen_at
      FROM public.owner_snapshot_row s
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
    ON CONFLICT (organization_id, personal_or_registry_code) DO UPDATE
        SET source_display_name = EXCLUDED.source_display_name,
            source_owner_type = EXCLUDED.source_owner_type,
            updated_at = EXCLUDED.updated_at;

    INSERT INTO public.land_parcel
        (id, organization_id, owner_id, cadastral_code, area_hectares, name, updated_at)
    SELECT gen_random_uuid(),
           p_organization_id,
           NULL,
           s.cadastral_code,
           0,
           s.cadastral_code,
           v_seen_at
      FROM (
            SELECT DISTINCT cadastral_code
              FROM public.owner_parcel_snapshot_row
             WHERE run_id = p_run_id
               AND organization_id = p_organization_id
           ) s
    ON CONFLICT (organization_id, cadastral_code) DO NOTHING;

    INSERT INTO public.ownership_relation_conflict
        (id, organization_id, owner_id, parcel_id, source, observed_active,
         relation_revision, status, observed_at, created_at)
    SELECT gen_random_uuid(),
           r.organization_id,
           r.owner_id,
           r.parcel_id,
           'METSIS_PORTFOLIO',
           TRUE,
           r.manual_revision,
           'OPEN',
           v_seen_at,
           CURRENT_TIMESTAMP
      FROM public.owner_parcel_snapshot_row s
      JOIN public.owner o
        ON o.organization_id = s.organization_id
       AND o.personal_or_registry_code = s.owner_code
      JOIN public.land_parcel p
        ON p.organization_id = s.organization_id
       AND p.cadastral_code = s.cadastral_code
      JOIN public.owner_parcel_relation r
        ON r.organization_id = s.organization_id
       AND r.owner_id = o.id
       AND r.parcel_id = p.id
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
       AND r.manual_override = TRUE
       AND r.active = FALSE
    ON CONFLICT
        (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
    DO NOTHING;

    SELECT count(*)::integer
      INTO v_added
      FROM public.owner_parcel_snapshot_row s
      JOIN public.owner o
        ON o.organization_id = s.organization_id
       AND o.personal_or_registry_code = s.owner_code
      JOIN public.land_parcel p
        ON p.organization_id = s.organization_id
       AND p.cadastral_code = s.cadastral_code
      LEFT JOIN public.owner_parcel_relation r
        ON r.organization_id = s.organization_id
       AND r.owner_id = o.id
       AND r.parcel_id = p.id
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
       AND COALESCE(r.manual_override, FALSE) = FALSE
       AND (r.owner_id IS NULL OR r.active = FALSE);

    INSERT INTO public.data_change_signal
        (id, organization_id, owner_id, parcel_id, signal_type, source_registry,
         source_key, summary, payload, created_by_subject)
    SELECT gen_random_uuid(),
           s.organization_id,
           o.id,
           p.id,
           'PORTFOLIO_OWNERSHIP_ADDED',
           'METSIS_PORTFOLIO',
           s.owner_code || ':' || s.cadastral_code,
           'Omanikuportfellis tuvastati uus aktiivne katastriüksus ' || s.cadastral_code || '.',
           '{}'::jsonb,
           'system-owner-cadastre-sync'
      FROM public.owner_parcel_snapshot_row s
      JOIN public.owner o
        ON o.organization_id = s.organization_id
       AND o.personal_or_registry_code = s.owner_code
      JOIN public.land_parcel p
        ON p.organization_id = s.organization_id
       AND p.cadastral_code = s.cadastral_code
      LEFT JOIN public.owner_parcel_relation r
        ON r.organization_id = s.organization_id
       AND r.owner_id = o.id
       AND r.parcel_id = p.id
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
       AND COALESCE(r.manual_override, FALSE) = FALSE
       AND (r.owner_id IS NULL OR r.active = FALSE)
    ON CONFLICT DO NOTHING;

    INSERT INTO public.owner_parcel_relation
        (organization_id, owner_id, parcel_id, source, active, source_seen_at,
         deactivated_at, updated_at)
    SELECT s.organization_id,
           o.id,
           p.id,
           'METSIS_PORTFOLIO',
           TRUE,
           v_seen_at,
           NULL,
           v_seen_at
      FROM public.owner_parcel_snapshot_row s
      JOIN public.owner o
        ON o.organization_id = s.organization_id
       AND o.personal_or_registry_code = s.owner_code
      JOIN public.land_parcel p
        ON p.organization_id = s.organization_id
       AND p.cadastral_code = s.cadastral_code
     WHERE s.run_id = p_run_id
       AND s.organization_id = p_organization_id
    ON CONFLICT (organization_id, owner_id, parcel_id) DO UPDATE
        SET source = EXCLUDED.source,
            active = TRUE,
            source_seen_at = EXCLUDED.source_seen_at,
            deactivated_at = NULL,
            updated_at = EXCLUDED.updated_at
      WHERE public.owner_parcel_relation.manual_override = FALSE;

    INSERT INTO public.ownership_relation_conflict
        (id, organization_id, owner_id, parcel_id, source, observed_active,
         relation_revision, status, observed_at, created_at)
    SELECT gen_random_uuid(),
           r.organization_id,
           r.owner_id,
           r.parcel_id,
           'METSIS_PORTFOLIO',
           FALSE,
           r.manual_revision,
           'OPEN',
           v_seen_at,
           CURRENT_TIMESTAMP
      FROM public.owner_parcel_relation r
      JOIN public.owner o
        ON o.organization_id = r.organization_id
       AND o.id = r.owner_id
      JOIN public.land_parcel p
        ON p.organization_id = r.organization_id
       AND p.id = r.parcel_id
     WHERE r.organization_id = p_organization_id
       AND r.manual_override = TRUE
       AND r.active = TRUE
       AND NOT EXISTS (
            SELECT 1
              FROM public.owner_parcel_snapshot_row s
             WHERE s.run_id = p_run_id
               AND s.organization_id = r.organization_id
               AND s.owner_code = o.personal_or_registry_code
               AND s.cadastral_code = p.cadastral_code
       )
    ON CONFLICT
        (organization_id, owner_id, parcel_id, source, observed_active, relation_revision)
    DO NOTHING;

    UPDATE public.owner_parcel_relation r
       SET active = FALSE,
           deactivated_at = v_seen_at,
           updated_at = v_seen_at
      FROM public.owner o,
           public.land_parcel p
     WHERE r.organization_id = p_organization_id
       AND r.source = 'METSIS_PORTFOLIO'
       AND r.active = TRUE
       AND r.manual_override = FALSE
       AND o.id = r.owner_id
       AND o.organization_id = r.organization_id
       AND p.id = r.parcel_id
       AND p.organization_id = r.organization_id
       AND NOT EXISTS (
            SELECT 1
              FROM public.owner_parcel_snapshot_row s
             WHERE s.run_id = p_run_id
               AND s.organization_id = r.organization_id
               AND s.owner_code = o.personal_or_registry_code
               AND s.cadastral_code = p.cadastral_code
       );
    GET DIAGNOSTICS v_closed = ROW_COUNT;

    INSERT INTO public.metsis_portfolio_sync_checkpoint
        (organization_id, cursor_date, last_success_at, last_failure_at, last_error,
         last_rows_seen, last_relations_added, last_relations_closed, updated_at)
    VALUES
        (p_organization_id, CURRENT_DATE, v_seen_at, NULL, NULL,
         v_rows, v_added, v_closed, v_seen_at)
    ON CONFLICT (organization_id) DO UPDATE
        SET cursor_date = EXCLUDED.cursor_date,
            last_success_at = EXCLUDED.last_success_at,
            last_failure_at = NULL,
            last_error = NULL,
            last_rows_seen = EXCLUDED.last_rows_seen,
            last_relations_added = EXCLUDED.last_relations_added,
            last_relations_closed = EXCLUDED.last_relations_closed,
            updated_at = EXCLUDED.updated_at;

    DELETE FROM public.owner_parcel_snapshot_row WHERE run_id = p_run_id;
    DELETE FROM public.owner_snapshot_row WHERE run_id = p_run_id;
    DELETE FROM public.owner_portfolio_import_stage WHERE run_id = p_run_id;

    RETURN jsonb_build_object(
        'rows', v_rows,
        'owners', v_staged_owners,
        'relationsAdded', v_added,
        'relationsClosed', v_closed
    );
END;
$$;

CREATE OR REPLACE FUNCTION forestiq_ingest_apply_weasel_transition(
    p_organization_id UUID,
    p_external_id BIGINT,
    p_cadastral_code TEXT,
    p_transition_at TIMESTAMPTZ,
    p_old_owner_identifiers TEXT[],
    p_new_owner_identifiers TEXT[]
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_row RECORD;
    v_matched INTEGER := 0;
    v_imported INTEGER := 0;
    v_direction TEXT;
    v_summary TEXT;
    v_event_inserted BOOLEAN;
BEGIN
    IF p_organization_id IS NULL
       OR p_external_id IS NULL
       OR p_external_id <= 0
       OR p_cadastral_code IS NULL
       OR btrim(p_cadastral_code) = ''
       OR p_transition_at IS NULL THEN
        RAISE EXCEPTION 'invalid Weasel transition apply arguments';
    END IF;

    FOR v_row IN
        SELECT p.id AS parcel_id,
               o.id AS owner_id,
               o.personal_or_registry_code AS owner_identifier
          FROM public.land_parcel p
          JOIN public.owner_parcel_relation opr
            ON opr.parcel_id = p.id
           AND opr.organization_id = p.organization_id
           AND opr.active = TRUE
          JOIN public.owner o
            ON o.id = opr.owner_id
           AND o.organization_id = opr.organization_id
         WHERE p.organization_id = p_organization_id
           AND p.cadastral_code = p_cadastral_code
           AND o.personal_or_registry_code IS NOT NULL
           AND (
                o.personal_or_registry_code = ANY(COALESCE(p_old_owner_identifiers, ARRAY[]::TEXT[]))
                OR
                o.personal_or_registry_code = ANY(COALESCE(p_new_owner_identifiers, ARRAY[]::TEXT[]))
           )
    LOOP
        v_matched := v_matched + 1;
        v_direction := CASE
            WHEN v_row.owner_identifier = ANY(COALESCE(p_old_owner_identifiers, ARRAY[]::TEXT[]))
             AND v_row.owner_identifier = ANY(COALESCE(p_new_owner_identifiers, ARRAY[]::TEXT[]))
                THEN 'MIXED'
            WHEN v_row.owner_identifier = ANY(COALESCE(p_old_owner_identifiers, ARRAY[]::TEXT[]))
                THEN 'OUTGOING'
            ELSE 'INCOMING'
        END;

        INSERT INTO public.ownership_transition_event
            (organization_id, parcel_id, owner_id, weasel_transition_id,
             cadastral_code, transition_at, old_owner_count, new_owner_count,
             relationship_direction, imported_by_subject)
        VALUES
            (p_organization_id, v_row.parcel_id, v_row.owner_id, p_external_id,
             p_cadastral_code, p_transition_at,
             cardinality(COALESCE(p_old_owner_identifiers, ARRAY[]::TEXT[])),
             cardinality(COALESCE(p_new_owner_identifiers, ARRAY[]::TEXT[])),
             v_direction, 'system-weasel-sync')
        ON CONFLICT (organization_id, weasel_transition_id, owner_id) DO NOTHING
        RETURNING TRUE INTO v_event_inserted;

        v_summary := 'Weaseli andmeallikas tuvastas katastriüksuse '
            || p_cadastral_code || ' omandimuutuse ('
            || CASE v_direction
                WHEN 'OUTGOING' THEN 'senise omaniku väljumine'
                WHEN 'INCOMING' THEN 'uue omaniku lisandumine'
                ELSE 'omanike koosseisu muutus'
               END
            || ').';

        INSERT INTO public.data_change_signal
            (id, organization_id, owner_id, parcel_id, signal_type,
             source_registry, source_key, summary, payload, created_by_subject)
        VALUES
            (gen_random_uuid(), p_organization_id, v_row.owner_id, v_row.parcel_id,
             'OWNERSHIP_TRANSITION', 'WEASEL', p_external_id::TEXT, v_summary,
             jsonb_build_object(
                'weaselTransitionId', p_external_id,
                'cadastralCode', p_cadastral_code,
                'transitionAt', p_transition_at,
                'direction', v_direction,
                'oldOwnerCount', cardinality(COALESCE(p_old_owner_identifiers, ARRAY[]::TEXT[])),
                'newOwnerCount', cardinality(COALESCE(p_new_owner_identifiers, ARRAY[]::TEXT[]))
             ),
             'system-weasel-sync')
        ON CONFLICT DO NOTHING;

        IF COALESCE(v_event_inserted, FALSE) THEN
            INSERT INTO public.owner_activity
                (organization_id, owner_id, activity_type, content, created_by_subject)
            VALUES
                (p_organization_id, v_row.owner_id, 'SYSTEM', v_summary, 'system-weasel-sync');
            v_imported := v_imported + 1;
        END IF;
        v_event_inserted := FALSE;
    END LOOP;

    RETURN jsonb_build_object(
        'matched', v_matched,
        'imported', v_imported
    );
END;
$$;

CREATE OR REPLACE FUNCTION forestiq_ingest_apply_inheritance_notice(
    p_organization_id UUID,
    p_notice_number TEXT,
    p_source_url TEXT,
    p_announcement_date DATE,
    p_death_date DATE,
    p_certification_deadline DATE,
    p_notary_name TEXT,
    p_notary_phone TEXT,
    p_personal_code TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_owner_id UUID;
    v_owner_status TEXT;
    v_case_id UUID;
    v_now TIMESTAMPTZ := CURRENT_TIMESTAMP;
BEGIN
    IF p_organization_id IS NULL
       OR p_notice_number IS NULL
       OR btrim(p_notice_number) = ''
       OR p_personal_code IS NULL
       OR p_personal_code !~ '^[0-9]{11}$' THEN
        RAISE EXCEPTION 'invalid inheritance notice apply arguments';
    END IF;

    SELECT id, status
      INTO v_owner_id, v_owner_status
      FROM public.owner
     WHERE organization_id = p_organization_id
       AND personal_or_registry_code = p_personal_code
     FOR UPDATE;

    IF v_owner_id IS NULL THEN
        RETURN jsonb_build_object('matched', FALSE, 'imported', FALSE, 'duplicate', FALSE);
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.inheritance_notice_import
         WHERE organization_id = p_organization_id
           AND notice_number = p_notice_number
    ) THEN
        RETURN jsonb_build_object('matched', TRUE, 'imported', FALSE, 'duplicate', TRUE);
    END IF;

    IF v_owner_status <> 'DECEASED' THEN
        UPDATE public.owner
           SET status = 'DECEASED',
               status_set_at = v_now,
               updated_at = v_now
         WHERE organization_id = p_organization_id
           AND id = v_owner_id;

        INSERT INTO public.data_change_signal
            (id, organization_id, owner_id, signal_type, source_registry,
             source_key, summary, payload, created_by_subject)
        VALUES
            (gen_random_uuid(), p_organization_id, v_owner_id, 'DECEASED_OWNER',
             'INHERITANCE_NOTICES', p_notice_number,
             'Ametlik pärimisteade tuvastas surnud omaniku; pärimisjuhtum vajab käsitlemist.',
             jsonb_build_object('noticeNumber', p_notice_number),
             'system-inheritance-notice-sync')
        ON CONFLICT DO NOTHING;

        INSERT INTO public.user_status_change_record
            (organization_id, user_subject, from_status, to_status)
        VALUES
            (p_organization_id, 'system-inheritance-notice-sync', v_owner_status, 'DECEASED');

        INSERT INTO public.owner_activity
            (organization_id, owner_id, activity_type, content, created_by_subject)
        VALUES
            (p_organization_id, v_owner_id, 'SYSTEM',
             'Ametliku pärimisteate ' || p_notice_number
                || ' alusel märgiti omanik surnuks ning avati pärimistöövoog.',
             'system-inheritance-notice-sync');
    END IF;

    SELECT id
      INTO v_case_id
      FROM public.inheritance_case
     WHERE organization_id = p_organization_id
       AND owner_id = v_owner_id
       AND source_notice_number = p_notice_number
     LIMIT 1;

    IF v_case_id IS NULL THEN
        IF EXISTS (
            SELECT 1
              FROM public.inheritance_case
             WHERE organization_id = p_organization_id
               AND owner_id = v_owner_id
               AND status NOT IN ('SETTLED','CLOSED','NOT_RELEVANT')
        ) THEN
            RAISE EXCEPTION 'owner already has an open inheritance case';
        END IF;

        INSERT INTO public.inheritance_case
            (id, organization_id, owner_id, source_notice_number, source_url,
             announcement_date, death_date, certification_deadline,
             notary_name, notary_phone, status, started_at, created_by_subject)
        VALUES
            (gen_random_uuid(), p_organization_id, v_owner_id, p_notice_number,
             NULLIF(btrim(p_source_url), ''), p_announcement_date, p_death_date,
             p_certification_deadline, NULLIF(btrim(p_notary_name), ''),
             NULLIF(btrim(p_notary_phone), ''), 'NOTICE_RECEIVED', v_now,
             'system-inheritance-notice-sync')
        RETURNING id INTO v_case_id;

        INSERT INTO public.inheritance_case_event
            (id, inheritance_case_id, organization_id, event_type,
             description, event_data, created_by_subject)
        VALUES
            (gen_random_uuid(), v_case_id, p_organization_id, 'CASE_STARTED',
             'Pärimisjuhtum algatati ametliku teate või käsitsi kinnitatud info alusel.',
             '{"status":"NOTICE_RECEIVED"}'::jsonb,
             'system-inheritance-notice-sync');

        INSERT INTO public.owner_activity
            (organization_id, owner_id, activity_type, content, created_by_subject)
        VALUES
            (p_organization_id, v_owner_id, 'SYSTEM', 'Pärimisjuhtum algatati.',
             'system-inheritance-notice-sync');
    END IF;

    INSERT INTO public.inheritance_case_event
        (id, inheritance_case_id, organization_id, event_type,
         description, event_data, created_by_subject)
    VALUES
        (gen_random_uuid(), v_case_id, p_organization_id, 'OFFICIAL_NOTICE_IMPORTED',
         'Ametlik pärimisteade imporditi serveritevahelise liidese kaudu.',
         jsonb_build_object('noticeNumber', p_notice_number),
         'system-inheritance-notice-sync');

    INSERT INTO public.inheritance_notice_import
        (organization_id, notice_number, owner_id, announcement_date,
         source_url, death_date, certification_deadline, notary_name, notary_phone)
    VALUES
        (p_organization_id, p_notice_number, v_owner_id, p_announcement_date,
         NULLIF(btrim(p_source_url), ''), p_death_date, p_certification_deadline,
         NULLIF(btrim(p_notary_name), ''), NULLIF(btrim(p_notary_phone), ''))
    ON CONFLICT DO NOTHING;

    INSERT INTO public.inheritance_notice_sync
        (organization_id, owner_id, last_checked_at, last_notice_number, last_error)
    VALUES
        (p_organization_id, v_owner_id, v_now, p_notice_number, NULL)
    ON CONFLICT (organization_id, owner_id) DO UPDATE
        SET last_checked_at = EXCLUDED.last_checked_at,
            last_notice_number = EXCLUDED.last_notice_number,
            last_error = NULL;

    RETURN jsonb_build_object(
        'matched', TRUE,
        'imported', TRUE,
        'duplicate', FALSE,
        'ownerId', v_owner_id,
        'caseId', v_case_id
    );
END;
$$;

CREATE OR REPLACE FUNCTION forestiq_ingest_apply_registry_bulk_batch(
    p_organization_id UUID,
    p_registry_name TEXT,
    p_batch_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $registry$
DECLARE
    v_codes JSONB;
    v_code TEXT;
    v_parcel_id UUID;
    v_feature RECORD;
    v_owner_id UUID;
    v_records INTEGER := 0;
    v_cadastres INTEGER := 0;
    v_affected INTEGER := 0;
    v_is_new BOOLEAN;
BEGIN
    IF p_registry_name NOT IN ('METSAREGISTER_TEATISED', 'METSAREGISTER_ERALDISED') THEN
        RAISE EXCEPTION 'unsupported registry bulk apply source %', p_registry_name;
    END IF;

    SELECT cadastral_codes
      INTO v_codes
      FROM public.registry_bulk_sync_checkpoint
     WHERE organization_id = p_organization_id
       AND registry_name = p_registry_name
       AND batch_id = p_batch_id
     FOR UPDATE;

    IF v_codes IS NULL THEN
        RAISE EXCEPTION 'registry bulk checkpoint not found for batch %', p_batch_id;
    END IF;

    FOR v_code IN SELECT jsonb_array_elements_text(v_codes)
    LOOP
        SELECT id
          INTO v_parcel_id
          FROM public.land_parcel
         WHERE organization_id = p_organization_id
           AND cadastral_code = v_code;

        IF v_parcel_id IS NULL THEN
            RAISE EXCEPTION 'tenant parcel % not found during registry apply', v_code;
        END IF;

        IF p_registry_name = 'METSAREGISTER_TEATISED' THEN
            FOR v_feature IN
                SELECT feature_key, source_id, title, work_code, decision, area, source_hash
                  FROM public.registry_bulk_sync_feature
                 WHERE batch_id = p_batch_id
                   AND organization_id = p_organization_id
                   AND registry_name = p_registry_name
                   AND cadastral_code = v_code
            LOOP
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM public.cadastre_notification n
                     WHERE n.organization_id = p_organization_id
                       AND n.cadastral_code = v_code
                       AND n.archived = FALSE
                       AND n.notification_number IS NOT NULL
                       AND n.notification_number::TEXT = v_feature.source_id
                ) INTO v_is_new;

                IF v_is_new THEN
                    FOR v_owner_id IN
                        SELECT DISTINCT owner_id
                          FROM public.owner_parcel_relation
                         WHERE organization_id = p_organization_id
                           AND parcel_id = v_parcel_id
                           AND active = TRUE
                    LOOP
                        INSERT INTO public.data_change_signal
                            (id, organization_id, owner_id, parcel_id, signal_type,
                             source_registry, source_key, summary, payload, created_by_subject)
                        VALUES
                            (gen_random_uuid(), p_organization_id, v_owner_id, v_parcel_id,
                             'NEW_FOREST_NOTIFICATION', p_registry_name,
                             v_code || ':' || COALESCE(v_feature.source_id, v_feature.feature_key),
                             'Metsaregistris lisandus uus metsateatis '
                                || COALESCE(NULLIF(v_feature.title, ''), v_feature.feature_key) || '.',
                             jsonb_build_object(
                                 'sourceHash', v_feature.source_hash,
                                 'batchId', p_batch_id
                             ),
                             'system-registry-bulk-recovery')
                        ON CONFLICT DO NOTHING;
                    END LOOP;
                END IF;
            END LOOP;

            DELETE FROM public.cadastre_notification
             WHERE organization_id = p_organization_id
               AND cadastral_code = v_code
               AND archived = FALSE;

            INSERT INTO public.cadastre_notification
                (organization_id, cadastral_code, notification_number, work_code, decision, area)
            SELECT organization_id,
                   cadastral_code,
                   CASE WHEN source_id ~ '^[0-9]+$' THEN source_id::BIGINT ELSE NULL END,
                   work_code,
                   decision,
                   area
              FROM public.registry_bulk_sync_feature
             WHERE batch_id = p_batch_id
               AND organization_id = p_organization_id
               AND registry_name = p_registry_name
               AND cadastral_code = v_code;
            GET DIAGNOSTICS v_affected = ROW_COUNT;
            v_records := v_records + v_affected;
        ELSE
            FOR v_feature IN
                SELECT feature_key, source_id, title, work_code, decision, area, volume, source_hash
                  FROM public.registry_bulk_sync_feature
                 WHERE batch_id = p_batch_id
                   AND organization_id = p_organization_id
                   AND registry_name = p_registry_name
                   AND cadastral_code = v_code
            LOOP
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM public.forest_registry_feature f
                     WHERE f.organization_id = p_organization_id
                       AND f.cadastral_code = v_code
                       AND f.source_layer = 'METSAREGISTER_ERALDISED'
                       AND f.source_id IS NOT DISTINCT FROM v_feature.source_id
                       AND COALESCE((f.attributes::jsonb)->>'sourceHash', '') = v_feature.source_hash
                ) INTO v_is_new;

                IF v_is_new THEN
                    FOR v_owner_id IN
                        SELECT DISTINCT owner_id
                          FROM public.owner_parcel_relation
                         WHERE organization_id = p_organization_id
                           AND parcel_id = v_parcel_id
                           AND active = TRUE
                    LOOP
                        INSERT INTO public.data_change_signal
                            (id, organization_id, owner_id, parcel_id, signal_type,
                             source_registry, source_key, summary, payload, created_by_subject)
                        VALUES
                            (gen_random_uuid(), p_organization_id, v_owner_id, v_parcel_id,
                             'NEW_FOREST_COMPARTMENT', p_registry_name,
                             v_code || ':' || COALESCE(v_feature.source_id, v_feature.feature_key)
                                 || ':' || v_feature.source_hash,
                             'Metsaregistri eraldise andmed muutusid: '
                                || COALESCE(NULLIF(v_feature.title, ''), v_feature.feature_key) || '.',
                             jsonb_build_object(
                                 'sourceHash', v_feature.source_hash,
                                 'batchId', p_batch_id
                             ),
                             'system-registry-bulk-recovery')
                        ON CONFLICT DO NOTHING;
                    END LOOP;
                END IF;
            END LOOP;

            DELETE FROM public.forest_registry_feature
             WHERE organization_id = p_organization_id
               AND cadastral_code = v_code
               AND source_layer = 'METSAREGISTER_ERALDISED';

            INSERT INTO public.forest_registry_feature
                (organization_id, cadastral_code, source_layer, source_id, title,
                 work_code, decision, area, volume, attributes)
            SELECT organization_id,
                   cadastral_code,
                   'METSAREGISTER_ERALDISED',
                   source_id,
                   title,
                   work_code,
                   decision,
                   area,
                   volume,
                   jsonb_build_object('sourceHash', source_hash)::TEXT
              FROM public.registry_bulk_sync_feature
             WHERE batch_id = p_batch_id
               AND organization_id = p_organization_id
               AND registry_name = p_registry_name
               AND cadastral_code = v_code;
            GET DIAGNOSTICS v_affected = ROW_COUNT;
            v_records := v_records + v_affected;

            IF v_affected > 0 THEN
                UPDATE public.land_parcel
                   SET mk_date = (extract(epoch FROM clock_timestamp()) * 1000)::BIGINT
                 WHERE organization_id = p_organization_id
                   AND cadastral_code = v_code;
            END IF;
        END IF;

        INSERT INTO public.registry_sync_log
            (id, organization_id, cadastral_code, registry_name, request_url,
             status, records_updated, error_message, raw_response, synced_by_subject)
        VALUES
            (gen_random_uuid(), p_organization_id, v_code, p_registry_name, NULL,
             'SUCCESS',
             (SELECT count(*)::INTEGER
                FROM public.registry_bulk_sync_feature
               WHERE batch_id = p_batch_id
                 AND organization_id = p_organization_id
                 AND registry_name = p_registry_name
                 AND cadastral_code = v_code),
             NULL,
             jsonb_build_object('batchId', p_batch_id),
             'system-registry-bulk-recovery');

        v_cadastres := v_cadastres + 1;
    END LOOP;

    DELETE FROM public.registry_bulk_sync_feature
     WHERE batch_id = p_batch_id
       AND organization_id = p_organization_id
       AND registry_name = p_registry_name;

    DELETE FROM public.registry_bulk_sync_checkpoint
     WHERE organization_id = p_organization_id
       AND registry_name = p_registry_name
       AND batch_id = p_batch_id;

    RETURN jsonb_build_object(
        'cadastres', v_cadastres,
        'records', v_records
    );
END;
$registry$;

REVOKE ALL ON FUNCTION forestiq_ingest_apply_owner_portfolio(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION forestiq_ingest_apply_weasel_transition(UUID, BIGINT, TEXT, TIMESTAMPTZ, TEXT[], TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION forestiq_ingest_apply_inheritance_notice(UUID, TEXT, TEXT, DATE, DATE, DATE, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION forestiq_ingest_apply_registry_bulk_batch(UUID, TEXT, UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION forestiq_ingest_apply_owner_portfolio(UUID, UUID) TO forestiq_ingest;
GRANT EXECUTE ON FUNCTION forestiq_ingest_apply_weasel_transition(UUID, BIGINT, TEXT, TIMESTAMPTZ, TEXT[], TEXT[]) TO forestiq_ingest;
GRANT EXECUTE ON FUNCTION forestiq_ingest_apply_inheritance_notice(UUID, TEXT, TEXT, DATE, DATE, DATE, TEXT, TEXT, TEXT) TO forestiq_ingest;
GRANT EXECUTE ON FUNCTION forestiq_ingest_apply_registry_bulk_batch(UUID, TEXT, UUID) TO forestiq_ingest;

COMMENT ON FUNCTION forestiq_ingest_apply_owner_portfolio(UUID, UUID) IS
    'Narrow migration-owned apply boundary for a fully staged MetsIS snapshot. forestiq_ingest has EXECUTE but no broad CRM DML grants.';
COMMENT ON FUNCTION forestiq_ingest_apply_weasel_transition(UUID, BIGINT, TEXT, TIMESTAMPTZ, TEXT[], TEXT[]) IS
    'Narrow migration-owned apply boundary for one normalized Weasel ownership transition.';
COMMENT ON FUNCTION forestiq_ingest_apply_inheritance_notice(UUID, TEXT, TEXT, DATE, DATE, DATE, TEXT, TEXT, TEXT) IS
    'Narrow migration-owned apply boundary for one normalized/matched inheritance notice.';
COMMENT ON FUNCTION forestiq_ingest_apply_registry_bulk_batch(UUID, TEXT, UUID) IS
    'Narrow migration-owned apply boundary for one fully staged tenant Metsaregister notification/forest-plan batch.';
