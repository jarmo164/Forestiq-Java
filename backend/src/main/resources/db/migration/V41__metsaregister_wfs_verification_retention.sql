-- V41: Persist Metsaregister mirror completeness checks and support bounded cleanup.

ALTER TABLE metsaregister_wfs_layer
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN last_verification_state VARCHAR(48),
    ADD COLUMN last_verified_local_count BIGINT,
    ADD COLUMN last_verified_provider_count BIGINT;

ALTER TABLE metsaregister_wfs_layer
    ADD CONSTRAINT metsaregister_wfs_layer_verification_state_check
    CHECK (last_verification_state IS NULL OR last_verification_state IN (
        'COMPLETE',
        'NO_GENERATION',
        'UNKNOWN_PROVIDER_COUNT',
        'GENERATION_METADATA_MISMATCH',
        'PROVIDER_METADATA_MISMATCH',
        'ROW_COUNT_MISMATCH',
        'ACTUAL_ROW_COUNT_MISMATCH'
    ));

CREATE INDEX metsaregister_wfs_generation_retired_cleanup_idx
    ON metsaregister_wfs_generation(completed_at, id)
    WHERE state='RETIRED';
