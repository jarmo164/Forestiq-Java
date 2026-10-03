-- Read-path indexes for the interactive admin workdesk. Keep prep-data DISTINCT
-- scans and page-only signal enrichment inside tenant-local index ranges.
CREATE INDEX IF NOT EXISTS data_change_signal_org_owner_actionable_idx
    ON data_change_signal (organization_id, owner_id, signal_type, detected_at DESC)
    WHERE dismissed_at IS NULL;

CREATE INDEX IF NOT EXISTS land_parcel_org_county_prep_idx
    ON land_parcel (organization_id, county)
    WHERE county IS NOT NULL;

CREATE INDEX IF NOT EXISTS land_parcel_org_municipality_prep_idx
    ON land_parcel (organization_id, municipality)
    WHERE municipality IS NOT NULL;

CREATE INDEX IF NOT EXISTS owner_org_type_prep_idx
    ON owner (organization_id, owner_type)
    WHERE owner_type IS NOT NULL;
