INSERT INTO owner_status_definition (organization_id, id, color_hex, days_out_of_search, is_protected)
SELECT organization.id, status.id, status.color_hex, status.days_out_of_search, TRUE
FROM organization
CROSS JOIN (
    VALUES
        ('VALUED', '0f766e', 0),
        ('NEGOTIATION', 'd97706', 0)
) AS status(id, color_hex, days_out_of_search)
ON CONFLICT (organization_id, id) DO NOTHING;
