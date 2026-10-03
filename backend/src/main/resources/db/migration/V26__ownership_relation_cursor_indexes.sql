-- Stable relation keyset order for the 1.2M-row administration view.
CREATE INDEX IF NOT EXISTS owner_parcel_relation_org_updated_cursor_idx
    ON owner_parcel_relation (organization_id, updated_at DESC, owner_id ASC, parcel_id ASC);

CREATE INDEX IF NOT EXISTS owner_parcel_relation_org_active_updated_cursor_idx
    ON owner_parcel_relation (organization_id, active, updated_at DESC, owner_id ASC, parcel_id ASC);

CREATE INDEX IF NOT EXISTS owner_parcel_relation_org_source_updated_cursor_idx
    ON owner_parcel_relation (organization_id, source, updated_at DESC, owner_id ASC, parcel_id ASC);

-- Prefix search paths used by the relation cursor endpoint. Owner display-name
-- search continues to use owner.search_document + GIN.
CREATE INDEX IF NOT EXISTS owner_org_code_prefix_idx
    ON owner (organization_id, (lower(personal_or_registry_code)) text_pattern_ops);

CREATE INDEX IF NOT EXISTS land_parcel_org_cadastre_prefix_idx
    ON land_parcel (organization_id, (lower(cadastral_code)) text_pattern_ops);

CREATE INDEX IF NOT EXISTS land_parcel_org_name_prefix_idx
    ON land_parcel (organization_id, (lower(name)) text_pattern_ops)
    WHERE name IS NOT NULL;

CREATE INDEX IF NOT EXISTS ownership_relation_conflict_open_lookup_idx
    ON ownership_relation_conflict (organization_id, owner_id, parcel_id, created_at DESC, id)
    WHERE status = 'OPEN';
