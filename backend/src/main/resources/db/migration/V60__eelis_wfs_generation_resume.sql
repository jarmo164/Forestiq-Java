-- V60: durable WFS page checkpoints for the generic geospatial generation model.
--
-- EELIS and future WFS providers are retrieved with count + startIndex. A page and
-- its durable cursor must be committed together, so restart never republishes or
-- loses a partially staged generation.

ALTER TABLE geospatial_generation
    ADD COLUMN provider_number_matched BIGINT
        CHECK (provider_number_matched IS NULL OR provider_number_matched >= 0),
    ADD COLUMN next_start_index BIGINT NOT NULL DEFAULT 0
        CHECK (next_start_index >= 0),
    ADD COLUMN pages_processed BIGINT NOT NULL DEFAULT 0
        CHECK (pages_processed >= 0);

ALTER TABLE geospatial_feature
    ADD COLUMN source_id VARCHAR(300);

CREATE INDEX geospatial_feature_source_id_idx
    ON geospatial_feature (source_key, source_id)
    WHERE source_id IS NOT NULL;

-- WFS offset paging needs a documented unique identity/sort value. This prevents a
-- duplicate provider identity from crossing a page boundary unnoticed.
CREATE UNIQUE INDEX geospatial_feature_generation_source_id_unique_idx
    ON geospatial_feature (generation_id, source_id)
    WHERE source_id IS NOT NULL;

-- A source can have one interrupted/resumable generation at a time. Published
-- generations remain readable while the replacement is staged.
CREATE UNIQUE INDEX geospatial_generation_one_staging_idx
    ON geospatial_generation (source_key)
    WHERE status = 'STAGING';

COMMENT ON COLUMN geospatial_generation.provider_number_matched IS
    'WFS numberMatched captured before staging begins; publication requires the local row count to equal it.';
COMMENT ON COLUMN geospatial_generation.next_start_index IS
    'Next WFS 2.0 startIndex to fetch. Updated in the same transaction as every completed page.';
COMMENT ON COLUMN geospatial_generation.pages_processed IS
    'Committed WFS pages in this generation.';
COMMENT ON COLUMN geospatial_feature.source_id IS
    'Stable provider-side identity property when configured, for example EELIS sys_id.';
