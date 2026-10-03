-- Transient bulk-load staging is rebuildable and does not need WAL durability.
-- Canonical/public snapshots are only published after validation and set-based merge.
CREATE UNLOGGED TABLE cadastre_import_stage (
    run_id UUID NOT NULL,
    row_number BIGINT NOT NULL,
    feature_key VARCHAR(300) NOT NULL,
    source_id VARCHAR(300),
    cadastral_code VARCHAR(32) NOT NULL,
    properties JSONB NOT NULL,
    geometry JSONB,
    raw_feature JSONB NOT NULL
);
CREATE INDEX cadastre_import_stage_run_idx
    ON cadastre_import_stage (run_id, cadastral_code);

-- Provider pages are copied here without per-row canonical lookups. Duplicate
-- owners/relations across upstream pages are normalized set-wise after the
-- complete snapshot has arrived. cadastral_code is nullable on purpose: an
-- owner with zero active cadastres must still survive a complete snapshot.
CREATE UNLOGGED TABLE owner_portfolio_import_stage (
    run_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    owner_code VARCHAR(32) NOT NULL,
    owner_name VARCHAR(300) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    cadastral_code VARCHAR(32)
);
CREATE INDEX owner_portfolio_import_stage_run_idx
    ON owner_portfolio_import_stage (organization_id, run_id, owner_code, cadastral_code);

-- Owner portfolio snapshot rows are run-scoped transient staging. They are
-- repopulated from the provider on every run, so WAL durability only adds cost.
ALTER TABLE owner_snapshot_row SET UNLOGGED;
ALTER TABLE owner_parcel_snapshot_row SET UNLOGGED;
