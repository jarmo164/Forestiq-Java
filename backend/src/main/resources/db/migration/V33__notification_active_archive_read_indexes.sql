-- Keep current-state browsing isolated from archive history while #135 measures
-- whether physical partitioning adds enough value to justify its operational cost.
CREATE INDEX IF NOT EXISTS cadastre_notification_current_cursor_idx
    ON cadastre_notification (organization_id, cadastral_code, id DESC)
    WHERE archived = FALSE;

-- Archive browsing is deliberately time-bounded and ordered by immutable source
-- archive date plus id for deterministic keyset pagination. Null archive dates
-- remain outside this path until the source-data policy classifies them.
CREATE INDEX IF NOT EXISTS cadastre_notification_archive_range_cursor_idx
    ON cadastre_notification (
        organization_id,
        cadastral_code,
        archive_date DESC,
        id DESC
    )
    WHERE archived = TRUE AND archive_date IS NOT NULL;

ANALYZE cadastre_notification;
