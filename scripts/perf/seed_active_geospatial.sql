\set ON_ERROR_STOP on

-- Disposable-only scale fixture for the active geospatial/MVT gates.
-- Refuse to run against any normal ForestIQ database by name.
DO $$
BEGIN
    IF current_database() NOT LIKE '%e2e%' AND current_database() NOT LIKE '%perf%' THEN
        RAISE EXCEPTION 'Refusing performance geospatial seed outside an e2e/perf database: %', current_database();
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- Representative current cadastre generation (100k canonical polygons).
-- ---------------------------------------------------------------------------
UPDATE geospatial_generation
   SET status='RETIRED', retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP)
 WHERE source_key='MARU_CADASTRE' AND status='PUBLISHED'
   AND id <> 'eeeeeeee-9999-0000-0000-000000000001';

INSERT INTO geospatial_source_run
    (id,source_key,trigger_type,status,source_date,source_version,rows_read,rows_staged,rows_inserted,started_at,completed_at)
VALUES
    ('dddddddd-9999-0000-0000-000000000001','MARU_CADASTRE','BOOTSTRAP','SUCCESS',
     CURRENT_DATE,'performance-fixture-v1',100000,100000,100000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO geospatial_generation
    (id,source_key,run_id,status,source_date,source_version,artifact_checksum,
     source_srid,canonical_srid,row_count,validation_summary,validated_at,published_at)
VALUES
    ('eeeeeeee-9999-0000-0000-000000000001','MARU_CADASTRE',
     'dddddddd-9999-0000-0000-000000000001','STAGING',CURRENT_DATE,'performance-fixture-v1',
     'sha256:performance-cadastre-v1',4326,3301,100000,'{"fixture":"performance"}'::jsonb,
     CURRENT_TIMESTAMP,NULL)
ON CONFLICT (id) DO UPDATE SET status='STAGING', published_at=NULL, retired_at=NULL;

DELETE FROM geospatial_cadastre_parcel
 WHERE generation_id='eeeeeeee-9999-0000-0000-000000000001';

INSERT INTO geospatial_cadastre_parcel
    (source_key,generation_id,cadastral_code,provider_feature_id,source_attributes,source_area_m2,geom)
SELECT
    'MARU_CADASTRE',
    'eeeeeeee-9999-0000-0000-000000000001'::uuid,
    'PERF:' || lpad(series::text,16,'0'),
    'perf-cad-' || series,
    '{"fixture":"performance"}'::jsonb,
    625.0,
    ST_Multi(ST_MakeEnvelope(
        350000 + (series % 1000) * 125,
        6370000 + ((series / 1000) % 1000) * 125,
        350025 + (series % 1000) * 125,
        6370025 + ((series / 1000) % 1000) * 125,
        3301
    ))
FROM generate_series(1,100000) AS series;

UPDATE geospatial_generation
   SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP, validated_at=COALESCE(validated_at,CURRENT_TIMESTAMP)
 WHERE id='eeeeeeee-9999-0000-0000-000000000001';

INSERT INTO geospatial_source_current(source_key,generation_id,published_at)
VALUES ('MARU_CADASTRE','eeeeeeee-9999-0000-0000-000000000001',CURRENT_TIMESTAMP)
ON CONFLICT(source_key) DO UPDATE SET generation_id=EXCLUDED.generation_id,published_at=EXCLUDED.published_at;

-- ---------------------------------------------------------------------------
-- Representative generic overlay generation (20k features).
-- ---------------------------------------------------------------------------
UPDATE geospatial_generation
   SET status='RETIRED', retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP)
 WHERE source_key='MARU_ETAK_WOODY' AND status='PUBLISHED'
   AND id <> 'eeeeeeee-9999-0000-0000-000000000002';

INSERT INTO geospatial_source_run
    (id,source_key,trigger_type,status,source_date,source_version,rows_read,rows_staged,rows_inserted,started_at,completed_at)
VALUES
    ('dddddddd-9999-0000-0000-000000000002','MARU_ETAK_WOODY','BOOTSTRAP','SUCCESS',
     CURRENT_DATE,'performance-fixture-v1',20000,20000,20000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO geospatial_generation
    (id,source_key,run_id,status,source_date,source_version,artifact_checksum,
     source_srid,canonical_srid,row_count,validation_summary,validated_at,published_at)
VALUES
    ('eeeeeeee-9999-0000-0000-000000000002','MARU_ETAK_WOODY',
     'dddddddd-9999-0000-0000-000000000002','STAGING',CURRENT_DATE,'performance-fixture-v1',
     'sha256:performance-overlay-v1',3301,3301,20000,'{"fixture":"performance"}'::jsonb,
     CURRENT_TIMESTAMP,NULL)
ON CONFLICT (id) DO UPDATE SET status='STAGING', published_at=NULL, retired_at=NULL;

DELETE FROM geospatial_feature
 WHERE generation_id='eeeeeeee-9999-0000-0000-000000000002';

INSERT INTO geospatial_feature(source_key,generation_id,feature_id,properties,geom)
SELECT
    'MARU_ETAK_WOODY',
    'eeeeeeee-9999-0000-0000-000000000002'::uuid,
    'perf-overlay-' || series,
    jsonb_build_object('fixture','performance','n',series),
    ST_MakeEnvelope(
        350000 + (series % 500) * 250,
        6370000 + ((series / 500) % 500) * 250,
        350050 + (series % 500) * 250,
        6370050 + ((series / 500) % 500) * 250,
        3301
    )
FROM generate_series(1,20000) AS series;

UPDATE geospatial_generation
   SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP, validated_at=COALESCE(validated_at,CURRENT_TIMESTAMP)
 WHERE id='eeeeeeee-9999-0000-0000-000000000002';

INSERT INTO geospatial_source_current(source_key,generation_id,published_at)
VALUES ('MARU_ETAK_WOODY','eeeeeeee-9999-0000-0000-000000000002',CURRENT_TIMESTAMP)
ON CONFLICT(source_key) DO UPDATE SET generation_id=EXCLUDED.generation_id,published_at=EXCLUDED.published_at;

-- ---------------------------------------------------------------------------
-- Canonical forestry generation: every queryable catalog layer has >=12k rows.
-- ---------------------------------------------------------------------------
INSERT INTO geospatial_source_run
    (id,source_key,trigger_type,status,source_date,source_version,rows_read,rows_staged,rows_inserted,started_at,completed_at)
VALUES
    ('dddddddd-9999-0000-0000-000000000003','METSAREGISTER_FORESTRY','BOOTSTRAP','SUCCESS',
     CURRENT_DATE,'performance-fixture-v1',60000,60000,60000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

UPDATE geospatial_generation
   SET status='RETIRED', retired_at=COALESCE(retired_at,CURRENT_TIMESTAMP)
 WHERE source_key='METSAREGISTER_FORESTRY' AND status='PUBLISHED'
   AND id <> 'eeeeeeee-9999-0000-0000-000000000003';

INSERT INTO geospatial_generation
    (id,source_key,run_id,status,source_date,source_version,artifact_checksum,
     source_srid,canonical_srid,row_count,validation_summary,validated_at,published_at)
VALUES
    ('eeeeeeee-9999-0000-0000-000000000003','METSAREGISTER_FORESTRY',
     'dddddddd-9999-0000-0000-000000000003','STAGING',CURRENT_DATE,'performance-fixture-v1',
     'sha256:performance-forestry-v1',4326,3301,60000,'{"fixture":"performance"}'::jsonb,
     CURRENT_TIMESTAMP,NULL)
ON CONFLICT (id) DO UPDATE SET status='STAGING', published_at=NULL, retired_at=NULL;

DO $$
DECLARE
    layer_name text;
    layer_no integer := 0;
    provider_generation uuid;
BEGIN
    FOREACH layer_name IN ARRAY ARRAY[
        'metsaregister:teatis',
        'metsaregister:teatis_arhiiv',
        'metsaregister:eraldis',
        'metsaregister:isearasused',
        'metsaregister:kahjustused'
    ]
    LOOP
        layer_no := layer_no + 1;
        provider_generation := md5('forestiq-perf-provider-' || layer_name)::uuid;

        INSERT INTO metsaregister_wfs_generation
            (id,layer_name,state,next_start_index,pages_processed,rows_staged,
             provider_number_matched,started_at,completed_at,updated_at)
        VALUES
            (provider_generation,layer_name,'PUBLISHED',12000,12,12000,12000,
             CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        ON CONFLICT(id) DO UPDATE SET state='PUBLISHED',rows_staged=12000,
            provider_number_matched=12000,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP;

        DELETE FROM metsaregister_wfs_feature WHERE generation_id=provider_generation;

        INSERT INTO metsaregister_wfs_feature
            (generation_id,layer_name,feature_key,source_id,source_hash,properties,geometry)
        SELECT
            provider_generation,
            layer_name,
            'perf-' || layer_no || '-' || series,
            'perf-source-' || layer_no || '-' || series,
            md5(layer_name || ':' || series) || md5('h' || series),
            jsonb_build_object(
                'fixture','performance',
                'katastri_nr','PERF:' || lpad(series::text,16,'0'),
                'teatise_nr','T-' || series,
                'sys_id','E-' || series,
                'eraldis_id','E-' || series,
                'kood','K' || (series % 20),
                'staatus','ACTIVE',
                'kahjustuse_pohjus','fixture',
                'kahjustuse_aste','1'
            ),
            ST_MakeEnvelope(
                350000 + (series % 200) * 650 + layer_no * 10,
                6370000 + ((series / 200) % 200) * 650 + layer_no * 10,
                350080 + (series % 200) * 650 + layer_no * 10,
                6370080 + ((series / 200) % 200) * 650 + layer_no * 10,
                3301
            )
        FROM generate_series(1,12000) AS series;

        UPDATE metsaregister_wfs_layer
           SET current_generation_id=provider_generation,
               current_row_count=12000,
               state='HEALTHY',
               provider_present=TRUE,
               last_success_at=CURRENT_TIMESTAMP,
               updated_at=CURRENT_TIMESTAMP
         WHERE metsaregister_wfs_layer.layer_name=layer_name;

        INSERT INTO metsaregister_forestry_generation_member
            (forestry_generation_id,layer_name,layer_generation_id,row_count)
        VALUES
            ('eeeeeeee-9999-0000-0000-000000000003',layer_name,provider_generation,12000)
        ON CONFLICT(forestry_generation_id,layer_name)
        DO UPDATE SET layer_generation_id=EXCLUDED.layer_generation_id,row_count=EXCLUDED.row_count;
    END LOOP;
END
$$;

UPDATE geospatial_generation
   SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP, validated_at=COALESCE(validated_at,CURRENT_TIMESTAMP)
 WHERE id='eeeeeeee-9999-0000-0000-000000000003';

INSERT INTO geospatial_source_current(source_key,generation_id,published_at)
VALUES ('METSAREGISTER_FORESTRY','eeeeeeee-9999-0000-0000-000000000003',CURRENT_TIMESTAMP)
ON CONFLICT(source_key) DO UPDATE SET generation_id=EXCLUDED.generation_id,published_at=EXCLUDED.published_at;

ANALYZE geospatial_cadastre_parcel;
ANALYZE geospatial_feature;
ANALYZE metsaregister_wfs_feature;
ANALYZE metsaregister_forestry_generation_member;

SELECT 'cadastre' AS fixture,count(*) AS rows
  FROM geospatial_cadastre_parcel WHERE generation_id='eeeeeeee-9999-0000-0000-000000000001'
UNION ALL
SELECT 'overlay',count(*) FROM geospatial_feature WHERE generation_id='eeeeeeee-9999-0000-0000-000000000002'
UNION ALL
SELECT 'forestry',count(*) FROM metsaregister_wfs_feature f
  JOIN metsaregister_forestry_generation_member m
    ON m.layer_generation_id=f.generation_id AND m.layer_name=f.layer_name
 WHERE m.forestry_generation_id='eeeeeeee-9999-0000-0000-000000000003';
