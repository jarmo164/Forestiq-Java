-- Interactive owner cursor pagination. These indexes match the actual keyset
-- order so PostgreSQL can stop after the requested page instead of sorting a
-- large owner candidate set.
CREATE INDEX IF NOT EXISTS owner_org_updated_cursor_idx
    ON owner (organization_id, updated_at DESC, id ASC);

CREATE INDEX IF NOT EXISTS owner_org_assignee_updated_cursor_idx
    ON owner (organization_id, assigned_to_subject, updated_at DESC, id ASC);
