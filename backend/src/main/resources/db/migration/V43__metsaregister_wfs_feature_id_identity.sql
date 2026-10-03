-- V43: Some public Metsaregister layers do not expose a unique property-only
-- identity. Use the WFS feature id as an identity component while keeping
-- deterministic paging sorted by real provider properties.

UPDATE metsaregister_wfs_layer
SET identity_property = 'eraldis_id,puuliigi_kood,kvanus,pohjuse_kood,osakaal,rinde_kood,__feature_id',
    sort_property = 'eraldis_id,puuliigi_kood,kvanus,pohjuse_kood,osakaal,rinde_kood',
    updated_at = CURRENT_TIMESTAMP
WHERE layer_name = 'metsaregister:kahjustused';

UPDATE metsaregister_wfs_layer
SET identity_property = 'sys_id,__feature_id',
    sort_property = 'sys_id',
    updated_at = CURRENT_TIMESTAMP
WHERE layer_name IN (
    'metsaregister:mr__teema_raie_diameeter',
    'metsaregister:mr__teema_raie_liik',
    'metsaregister:mr__teema_raie_taius',
    'metsaregister:mr__teema_raie_vanus'
);
