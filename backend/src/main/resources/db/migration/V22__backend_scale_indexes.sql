-- Backend scale indexes for tenant-scoped CRM, MVT and durable integration work.
-- Keep these indexes narrow and aligned with production predicates; do not
-- partition current-state tables until measured row counts/query plans justify it.

-- Durable worker queue: ordered partial index keeps SKIP LOCKED claims bounded.
CREATE INDEX IF NOT EXISTS integration_sync_run_queued_idx
    ON integration_sync_run (queued_at ASC, id ASC)
    WHERE state = 'QUEUED';

-- MVT/search path: geometry already has a GiST index from V1. The tenant/cursor
-- index complements it for organization-scoped scans and deterministic batches.
CREATE INDEX IF NOT EXISTS land_parcel_org_updated_idx
    ON land_parcel (organization_id, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS land_parcel_org_marked_idx
    ON land_parcel (organization_id, marked, updated_at DESC, id);

-- Authorization and work-queue predicates are always tenant scoped.
CREATE INDEX IF NOT EXISTS owner_org_assignee_status_updated_idx
    ON owner (organization_id, assigned_to_subject, status, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS owner_parcel_relation_org_parcel_active_idx
    ON owner_parcel_relation (organization_id, parcel_id, created_at ASC, owner_id)
    WHERE active = TRUE;

CREATE INDEX IF NOT EXISTS owner_parcel_relation_org_owner_active_idx
    ON owner_parcel_relation (organization_id, owner_id, parcel_id)
    WHERE active = TRUE;

-- Missing/stale registry selection and audit lookups.
CREATE INDEX IF NOT EXISTS registry_sync_log_freshness_idx
    ON registry_sync_log (organization_id, cadastral_code, registry_name, status, created_at DESC);

CREATE INDEX IF NOT EXISTS forest_registry_feature_org_cadastre_source_idx
    ON forest_registry_feature (organization_id, cadastral_code, source_layer, source_id);

CREATE INDEX IF NOT EXISTS cadastre_notification_org_cadastre_idx
    ON cadastre_notification (organization_id, cadastral_code, created_at DESC);

-- Planner statistics matter for the highly skewed tenant/status columns.
ALTER TABLE owner ALTER COLUMN organization_id SET STATISTICS 500;
ALTER TABLE owner ALTER COLUMN status SET STATISTICS 250;
ALTER TABLE land_parcel ALTER COLUMN organization_id SET STATISTICS 500;
ALTER TABLE registry_sync_log ALTER COLUMN registry_name SET STATISTICS 250;
