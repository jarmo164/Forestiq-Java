-- V61: database role boundaries for API and Python ingestion runtime.
--
-- Flyway remains the single schema-migration authority. Python ingestion must not
-- run Alembic or another parallel migration tool against these structures.
--
-- The schemas are created now as responsibility markers. Existing public tables
-- remain in place during migration so generated Java/jOOQ reads keep working.

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS ingest;

COMMENT ON SCHEMA core IS 'Canonical ForestIQ domain data. Managed through Flyway.';
COMMENT ON SCHEMA app IS 'API-facing read models and materialized views. Managed through Flyway.';
COMMENT ON SCHEMA ingest IS 'Python ingestion run/source state and provider staging/raw structures. Managed through Flyway.';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forestiq_api') THEN
        CREATE ROLE forestiq_api NOLOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forestiq_ingest') THEN
        CREATE ROLE forestiq_ingest NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public, core, app TO forestiq_api;
GRANT USAGE ON SCHEMA public, core, app, ingest TO forestiq_ingest;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO forestiq_api;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO forestiq_ingest;

-- Keep the current Spring application writable for API-owned business records
-- while explicitly removing provider staging/raw write access below.
GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO forestiq_api;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO forestiq_api;

-- API may enqueue or inspect runs, but must not mutate provider staging/raw data.
GRANT INSERT, UPDATE ON TABLE integration_sync_run TO forestiq_api;

REVOKE INSERT, UPDATE, DELETE ON TABLE
    cadastre_import_stage,
    owner_portfolio_import_stage,
    owner_snapshot_row,
    owner_parcel_snapshot_row,
    inheritance_notice_import,
    notification_import_stage,
    notification_import_reject,
    external_dataset_feature,
    external_dataset_snapshot_run,
    registry_bulk_sync_feature,
    metsaregister_wfs_feature,
    geospatial_feature
FROM forestiq_api;

-- Python ingestion owns source/run/staging state and source-owned canonical
-- publish tables. It can read app context, but must not manage CRM/auth records.
GRANT INSERT, UPDATE, DELETE ON TABLE
    integration_sync_run,
    cadastre_import_stage,
    owner_portfolio_import_stage,
    owner_snapshot_row,
    owner_parcel_snapshot_row,
    inheritance_notice_import,
    notification_import_run,
    notification_import_stage,
    notification_import_reject,
    external_dataset_current,
    external_dataset_feature,
    external_dataset_snapshot_run,
    forest_registry_feature,
    forestry_domain_feature,
    forestry_generation_source,
    geospatial_source,
    geospatial_source_run,
    geospatial_source_current,
    geospatial_generation,
    geospatial_generation_event,
    geospatial_feature,
    geospatial_cadastre_domain_event,
    geospatial_cadastre_domain_link,
    geospatial_cadastre_parcel,
    geospatial_cadastre_reconciliation,
    geospatial_cadastre_replacement_candidate,
    metsaregister_wfs_layer,
    metsaregister_wfs_generation,
    metsaregister_wfs_feature,
    metsaregister_forestry_generation_member,
    registry_bulk_sync_checkpoint,
    registry_bulk_sync_feature,
    metsis_portfolio_sync_checkpoint,
    weasel_transition_sync_checkpoint
TO forestiq_ingest;

GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO forestiq_ingest;

REVOKE INSERT, UPDATE, DELETE ON TABLE
    organization,
    company_profile,
    owner,
    owner_activity,
    owner_following,
    owner_status_definition,
    contact_outcome_definition,
    deal_case,
    deal_case_event,
    deal_case_parcel,
    deal_evaluation,
    deal_loss_outcome,
    deal_loss_reason_definition,
    contract,
    contract_history,
    contract_signing_event,
    contract_signing_state,
    contract_template,
    contract_version_audit,
    document,
    message,
    reminder,
    reminder_event,
    reminder_notification_delivery,
    user_notification_preference,
    saved_workbasket,
    saved_workbasket_event,
    saved_workbasket_owner,
    saved_workbasket_parcel,
    saved_workbasket_share,
    management_assignment_item,
    management_assignment_run,
    sales_target_segment,
    sales_target_segment_event,
    stage_probability_config
FROM forestiq_ingest;

ALTER DEFAULT PRIVILEGES IN SCHEMA ingest
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forestiq_ingest;
ALTER DEFAULT PRIVILEGES IN SCHEMA ingest
    GRANT SELECT ON TABLES TO forestiq_api;
ALTER DEFAULT PRIVILEGES IN SCHEMA app
    GRANT SELECT ON TABLES TO forestiq_api, forestiq_ingest;
ALTER DEFAULT PRIVILEGES IN SCHEMA core
    GRANT SELECT ON TABLES TO forestiq_api, forestiq_ingest;

DO $$
BEGIN
    IF has_table_privilege('forestiq_api', 'public.cadastre_import_stage', 'INSERT') THEN
        RAISE EXCEPTION 'forestiq_api must not be able to insert provider staging rows';
    END IF;

    IF has_table_privilege('forestiq_api', 'public.geospatial_feature', 'UPDATE') THEN
        RAISE EXCEPTION 'forestiq_api must not be able to update provider raw feature rows';
    END IF;

    IF has_table_privilege('forestiq_ingest', 'public.owner_activity', 'INSERT') THEN
        RAISE EXCEPTION 'forestiq_ingest must not be able to insert CRM activity rows';
    END IF;

    IF has_table_privilege('forestiq_ingest', 'public.contract', 'UPDATE') THEN
        RAISE EXCEPTION 'forestiq_ingest must not be able to update protected sales contract rows';
    END IF;
END
$$;
