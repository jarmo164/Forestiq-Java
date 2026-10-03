-- Dataset snapshot runs are the canonical audit record for bulk public-dataset
-- imports. Keep aggregate counters here instead of writing one SUCCESS row per
-- cadastral feature into registry_sync_log.
ALTER TABLE external_dataset_snapshot_run
    ADD COLUMN records_projected BIGINT NOT NULL DEFAULT 0 CHECK (records_projected >= 0),
    ADD COLUMN geometry_records BIGINT NOT NULL DEFAULT 0 CHECK (geometry_records >= 0),
    ADD COLUMN bytes_read BIGINT NOT NULL DEFAULT 0 CHECK (bytes_read >= 0),
    ADD COLUMN records_rejected BIGINT NOT NULL DEFAULT 0 CHECK (records_rejected >= 0);

COMMENT ON TABLE external_dataset_snapshot_run IS
    'Batch-level operational audit for full external dataset loads. Per-cadastral registry_sync_log remains reserved for individual registry synchronization/freshness semantics.';
