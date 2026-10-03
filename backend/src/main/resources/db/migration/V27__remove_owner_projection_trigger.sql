-- Canonical ownership has been owner_parcel_relation since V18. The old
-- compatibility projection multiplied every relation write into an additional
-- land_parcel lookup/update and is not capable of representing co-ownership.
--
-- Keep the nullable legacy column temporarily so generated jOOQ classes and any
-- external read-only compatibility consumer do not break in the same release,
-- but stop maintaining it. No business logic may treat it as authoritative.
DROP TRIGGER IF EXISTS trg_owner_parcel_relation_owner_projection ON owner_parcel_relation;
DROP FUNCTION IF EXISTS maintain_land_parcel_owner_projection();
DROP FUNCTION IF EXISTS sync_land_parcel_owner_projection(uuid);

COMMENT ON COLUMN land_parcel.owner_id IS
    'DEPRECATED, NOT MAINTAINED. Current ownership is defined exclusively by active owner_parcel_relation rows. Remove this column after generated-schema compatibility cleanup.';
