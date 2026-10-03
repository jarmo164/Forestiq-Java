-- V38: Reconcile the two additional layers advertised by the live Metsaregister
-- WFS catalogue on 2026-09-01. Public provider data stays global and is stored
-- once regardless of ForestIQ tenant count.
--
-- pesitsusrahu_maatriks is anonymously readable and approved for mirroring.
-- raadatud_metsateatised is advertised by GetCapabilities/DescribeFeatureType,
-- but anonymous GetFeature currently returns HTTP 401, so keep it catalogued
-- but explicitly disabled until provider authentication/access is supported.

INSERT INTO metsaregister_wfs_layer (layer_name, title, approved, enabled)
VALUES ('metsaregister:pesitsusrahu_maatriks', 'Pesitsusrahu maatriks', TRUE, TRUE)
ON CONFLICT (layer_name) DO UPDATE
    SET title = EXCLUDED.title,
        approved = TRUE,
        enabled = TRUE,
        updated_at = CURRENT_TIMESTAMP;

INSERT INTO metsaregister_wfs_layer (layer_name, title, approved, enabled, last_error)
VALUES (
    'metsaregister:raadatud_metsateatised',
    'Raadatud metsateatised',
    FALSE,
    FALSE,
    'Provider advertises the layer but anonymous GetFeature returned HTTP 401 on 2026-09-01'
)
ON CONFLICT (layer_name) DO UPDATE
    SET title = EXCLUDED.title,
        approved = FALSE,
        enabled = FALSE,
        last_error = EXCLUDED.last_error,
        updated_at = CURRENT_TIMESTAMP;