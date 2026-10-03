-- V40: Per-layer refresh policy for the provider-global WFS mirror.

ALTER TABLE metsaregister_wfs_layer
    ADD COLUMN refresh_interval_minutes INT NOT NULL DEFAULT 1440
        CHECK (refresh_interval_minutes BETWEEN 15 AND 10080),
    ADD COLUMN refresh_priority INT NOT NULL DEFAULT 50
        CHECK (refresh_priority BETWEEN 1 AND 1000);

-- Current notifications are operationally the most time-sensitive layer.
UPDATE metsaregister_wfs_layer
   SET refresh_interval_minutes = 360,
       refresh_priority = 10
 WHERE layer_name = 'metsaregister:teatis';

-- Core forest-plan source layers refresh daily and ahead of secondary overlays.
UPDATE metsaregister_wfs_layer
   SET refresh_interval_minutes = 1440,
       refresh_priority = 20
 WHERE layer_name IN (
    'metsaregister:eraldis',
    'metsaregister:eraldis_element',
    'metsaregister:isearasused',
    'metsaregister:kahjustused'
 );

UPDATE metsaregister_wfs_layer
   SET refresh_interval_minutes = 1440,
       refresh_priority = 30
 WHERE layer_name = 'metsaregister:teatis_arhiiv';

-- Small classifier tables change rarely; a weekly refresh is sufficient and
-- they remain manually force-refreshable at any time.
UPDATE metsaregister_wfs_layer
   SET refresh_interval_minutes = 10080,
       refresh_priority = 90
 WHERE layer_name IN (
    'metsaregister:kl_kasvukoht',
    'metsaregister:kl_omandivorm',
    'metsaregister:kl_rinne',
    'metsaregister:kl_puuliik'
 );

CREATE INDEX metsaregister_wfs_layer_refresh_idx
    ON metsaregister_wfs_layer(
        approved,
        enabled,
        provider_present,
        refresh_priority,
        last_success_at
    );
