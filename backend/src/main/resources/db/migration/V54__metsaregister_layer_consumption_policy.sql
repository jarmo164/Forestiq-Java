-- V54: Separate provider discovery from ForestIQ consumption authorization.
-- Provider discovery may add catalogue rows, but only manifest-approved
-- LOCAL_DOMAIN_DATA rows may be scheduled or manually ingested.

ALTER TABLE metsaregister_wfs_layer
    ADD COLUMN consumption_mode VARCHAR(32) NOT NULL DEFAULT 'DISABLED'
        CHECK (consumption_mode IN ('EXTERNAL_MAP','BOUNDED_QUERY','LOCAL_DOMAIN_DATA','FORESTIQ_DERIVED_MAP','DISABLED')),
    ADD COLUMN migration_disposition VARCHAR(24) NOT NULL DEFAULT 'RETIRE'
        CHECK (migration_disposition IN ('KEEP_LOCAL','EXTERNAL_MAP','BOUNDED_QUERY','DERIVED_ONLY','RETIRE')),
    ADD COLUMN manifest_version VARCHAR(32);

-- Reset legacy blanket approval. Application startup reconciliation below will
-- explicitly approve only the LOCAL_DOMAIN_DATA entries present in the checked-
-- in manifest. This also makes upgrades safe before the first scheduler tick.
UPDATE metsaregister_wfs_layer
   SET approved = FALSE,
       enabled = FALSE,
       consumption_mode = 'DISABLED',
       migration_disposition = 'RETIRE',
       manifest_version = NULL;

-- Cut over display-only layers from national WFS persistence to provider-backed
-- map presentation. Break the current-generation FK first; deleting generations
-- then cascades to their staged/published feature rows and releases the storage
-- that these overlays no longer need. Business-query layers are deliberately
-- excluded from this list.
UPDATE metsaregister_wfs_layer
   SET current_generation_id = NULL,
       current_row_count = 0,
       last_success_at = NULL
 WHERE layer_name IN (
    'metsaregister:katsealad',
    'metsaregister:natura_2000_alad',
    'metsaregister:yp_kaitsemets',
    'metsaregister:mr__teema_raie_diameeter',
    'metsaregister:mr__teema_raie_liik',
    'metsaregister:mr__teema_raie_taius',
    'metsaregister:mr__teema_raie_vanus',
    'metsaregister:mke_arhiiv',
    'metsaregister:mue',
    'metsaregister:kuusekooreyrask_mke',
    'metsaregister:mke',
    'metsaregister:pesitsusrahu_maatriks',
    'metsaregister:raadatud_metsateatised'
 );

DELETE FROM metsaregister_wfs_generation
 WHERE layer_name IN (
    'metsaregister:katsealad',
    'metsaregister:natura_2000_alad',
    'metsaregister:yp_kaitsemets',
    'metsaregister:mr__teema_raie_diameeter',
    'metsaregister:mr__teema_raie_liik',
    'metsaregister:mr__teema_raie_taius',
    'metsaregister:mr__teema_raie_vanus',
    'metsaregister:mke_arhiiv',
    'metsaregister:mue',
    'metsaregister:kuusekooreyrask_mke',
    'metsaregister:mke',
    'metsaregister:pesitsusrahu_maatriks',
    'metsaregister:raadatud_metsateatised'
 );

CREATE INDEX metsaregister_wfs_layer_consumption_idx
    ON metsaregister_wfs_layer(consumption_mode, approved, enabled, provider_present);
