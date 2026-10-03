-- V59: generation-scoped storage for approved ETAK and administrative reference layers.
-- Reader visibility is controlled exclusively by geospatial_source_current; rows belonging to
-- STAGING/FAILED/RETIRED generations are never selected by current-generation views.

CREATE TABLE geospatial_feature (
    source_key VARCHAR(128) NOT NULL REFERENCES geospatial_source(source_key) ON DELETE RESTRICT,
    generation_id UUID NOT NULL REFERENCES geospatial_generation(id) ON DELETE CASCADE,
    feature_id VARCHAR(300) NOT NULL,
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    geom geometry(Geometry, 3301) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, feature_id)
);

CREATE INDEX geospatial_feature_source_generation_idx
    ON geospatial_feature (source_key, generation_id);
CREATE INDEX geospatial_feature_geom_gix
    ON geospatial_feature USING GIST (geom);

CREATE OR REPLACE VIEW geospatial_feature_current AS
SELECT f.source_key,
       f.generation_id,
       g.source_date,
       g.source_version,
       g.artifact_checksum,
       g.schema_fingerprint,
       f.feature_id,
       f.properties,
       f.geom
  FROM geospatial_feature f
  JOIN geospatial_source_current c
    ON c.source_key = f.source_key
   AND c.generation_id = f.generation_id
  JOIN geospatial_generation g ON g.id = f.generation_id;

INSERT INTO geospatial_source
    (source_key, provider, dataset_key, display_name, source_kind, config_key,
     refresh_interval_seconds, freshness_target_seconds, expected_source_srid, canonical_srid, enabled)
VALUES
    ('MARU_ETAK_WOODY', 'MAA_JA_RUUMIAMET', 'etak:E_305_puittaimestik_a', 'ETAK — puittaimestik', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_WETLAND', 'MAA_JA_RUUMIAMET', 'etak:E_306_margala_a', 'ETAK — märgala', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_STANDING_WATER', 'MAA_JA_RUUMIAMET', 'etak:E_202_seisuveekogu_a', 'ETAK — seisuveekogu', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_FLOWING_WATER', 'MAA_JA_RUUMIAMET', 'etak:E_203_vooluveekogu_j', 'ETAK — vooluveekogu', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_ROAD', 'MAA_JA_RUUMIAMET', 'etak:E_501_tee_j', 'ETAK — teed', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_ARABLE', 'MAA_JA_RUUMIAMET', 'etak:E_303_haritav_maa_a', 'ETAK — haritav maa', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ETAK_OPEN', 'MAA_JA_RUUMIAMET', 'etak:E_304_lage_a', 'ETAK — lage ala', 'WFS', 'forestiq.geospatial.etak.endpoint', 604800, 864000, 3301, 3301, TRUE),
    ('MARU_ADMIN_COUNTY', 'MAA_JA_RUUMIAMET', 'maakond_pind', 'Haldusjaotus — maakond', 'WFS', 'forestiq.geospatial.admin.endpoint', 2592000, 3456000, 3301, 3301, TRUE),
    ('MARU_ADMIN_MUNICIPALITY', 'MAA_JA_RUUMIAMET', 'omavalitsus_pind', 'Haldusjaotus — omavalitsus', 'WFS', 'forestiq.geospatial.admin.endpoint', 2592000, 3456000, 3301, 3301, TRUE),
    ('MARU_ADMIN_SETTLEMENT', 'MAA_JA_RUUMIAMET', 'asustusyksus_pind', 'Haldusjaotus — asustusüksus', 'WFS', 'forestiq.geospatial.admin.endpoint', 2592000, 3456000, 3301, 3301, TRUE),
    ('MARU_BASEMAP_METADATA', 'MAA_JA_RUUMIAMET', 'basemap-catalog', 'Maa- ja Ruumiameti aluskaardi metaandmed', 'TILE', 'forestiq.registry.maaamet', 86400, 172800, NULL, 3857, TRUE)
ON CONFLICT (source_key) DO UPDATE SET
    provider = EXCLUDED.provider,
    dataset_key = EXCLUDED.dataset_key,
    display_name = EXCLUDED.display_name,
    source_kind = EXCLUDED.source_kind,
    config_key = EXCLUDED.config_key,
    refresh_interval_seconds = EXCLUDED.refresh_interval_seconds,
    freshness_target_seconds = EXCLUDED.freshness_target_seconds,
    expected_source_srid = EXCLUDED.expected_source_srid,
    canonical_srid = EXCLUDED.canonical_srid,
    updated_at = CURRENT_TIMESTAMP;
