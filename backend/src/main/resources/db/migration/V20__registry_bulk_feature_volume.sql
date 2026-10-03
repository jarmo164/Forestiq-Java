-- V19 already creates volume for fresh databases. Keep this migration
-- compatible with databases where the column may or may not already exist.
ALTER TABLE registry_bulk_sync_feature
    ADD COLUMN IF NOT EXISTS volume NUMERIC(12,4);
