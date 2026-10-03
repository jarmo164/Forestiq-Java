-- Cursor indexes for bounded parcel-history reads. These match the actual
-- organization + cadastral-code + id keyset predicates used by the API and
-- avoid sorting/scanning a large tenant history before applying LIMIT.
-- Keep archive partitioning evidence-gated by #133/#135.

CREATE INDEX IF NOT EXISTS cadastre_notification_org_cadastre_id_idx
    ON cadastre_notification (organization_id, cadastral_code, id DESC);

CREATE INDEX IF NOT EXISTS forest_registry_feature_org_cadastre_id_idx
    ON forest_registry_feature (organization_id, cadastral_code, id DESC);

CREATE INDEX IF NOT EXISTS cadastre_sub_part_org_cadastre_id_idx
    ON cadastre_sub_part (organization_id, cadastral_code, id DESC);
