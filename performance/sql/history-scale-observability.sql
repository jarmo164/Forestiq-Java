\pset pager off
\timing on

\echo '=== HISTORY SCALE CARDINALITY ==='
SELECT 'cadastre_notification' AS relation, count(*) AS rows
FROM cadastre_notification WHERE organization_id = :'org_id'::uuid
UNION ALL
SELECT 'forest_registry_feature', count(*)
FROM forest_registry_feature WHERE organization_id = :'org_id'::uuid
UNION ALL
SELECT 'cadastre_sub_part', count(*)
FROM cadastre_sub_part WHERE organization_id = :'org_id'::uuid
ORDER BY relation;

\echo '=== NOTIFICATION ACTIVE / ARCHIVE ==='
SELECT archived, count(*) AS rows,
       pg_size_pretty(sum(pg_column_size(n))::bigint) AS approximate_row_bytes
FROM cadastre_notification n
WHERE organization_id = :'org_id'::uuid
GROUP BY archived
ORDER BY archived;

\echo '=== PER-CADASTRE DISTRIBUTIONS ==='
WITH per AS (
    SELECT cadastral_code, count(*)::bigint AS n
    FROM cadastre_notification
    WHERE organization_id = :'org_id'::uuid
    GROUP BY cadastral_code
)
SELECT 'notifications' AS dataset, count(*) AS cadastres,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY n) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY n) AS p95,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY n) AS p99,
       max(n) AS max
FROM per
UNION ALL
SELECT 'forest_features', count(*),
       percentile_cont(0.50) WITHIN GROUP (ORDER BY n),
       percentile_cont(0.95) WITHIN GROUP (ORDER BY n),
       percentile_cont(0.99) WITHIN GROUP (ORDER BY n), max(n)
FROM (
    SELECT cadastral_code, count(*)::bigint AS n
    FROM forest_registry_feature
    WHERE organization_id = :'org_id'::uuid
    GROUP BY cadastral_code
) x
UNION ALL
SELECT 'sub_parts', count(*),
       percentile_cont(0.50) WITHIN GROUP (ORDER BY n),
       percentile_cont(0.95) WITHIN GROUP (ORDER BY n),
       percentile_cont(0.99) WITHIN GROUP (ORDER BY n), max(n)
FROM (
    SELECT cadastral_code, count(*)::bigint AS n
    FROM cadastre_sub_part
    WHERE organization_id = :'org_id'::uuid
    GROUP BY cadastral_code
) x;

\echo '=== RAW PAYLOAD / GEOMETRY BYTE DISTRIBUTIONS ==='
SELECT 'forest_feature.attributes' AS field,
       count(*) FILTER (WHERE attributes IS NOT NULL) AS populated,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY octet_length(attributes)) FILTER (WHERE attributes IS NOT NULL)) AS p50_bytes,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY octet_length(attributes)) FILTER (WHERE attributes IS NOT NULL)) AS p95_bytes,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY octet_length(attributes)) FILTER (WHERE attributes IS NOT NULL)) AS p99_bytes,
       max(octet_length(attributes)) AS max_bytes
FROM forest_registry_feature WHERE organization_id = :'org_id'::uuid
UNION ALL
SELECT 'forest_feature.geometry',
       count(*) FILTER (WHERE geometry IS NOT NULL),
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY octet_length(geometry)) FILTER (WHERE geometry IS NOT NULL)),
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY octet_length(geometry)) FILTER (WHERE geometry IS NOT NULL)),
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY octet_length(geometry)) FILTER (WHERE geometry IS NOT NULL)),
       max(octet_length(geometry))
FROM forest_registry_feature WHERE organization_id = :'org_id'::uuid
UNION ALL
SELECT 'cadastre_sub_part.polygon',
       count(*) FILTER (WHERE polygon IS NOT NULL),
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY octet_length(polygon)) FILTER (WHERE polygon IS NOT NULL)),
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY octet_length(polygon)) FILTER (WHERE polygon IS NOT NULL)),
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY octet_length(polygon)) FILTER (WHERE polygon IS NOT NULL)),
       max(octet_length(polygon))
FROM cadastre_sub_part WHERE organization_id = :'org_id'::uuid;

\echo '=== ARCHIVE TIME DISTRIBUTION (provider epoch where recognizable) ==='
WITH normalized AS (
    SELECT CASE
        WHEN archive_date >= 100000000000 THEN to_char(to_timestamp(archive_date / 1000.0), 'YYYY-MM')
        WHEN archive_date >= 1000000000 THEN to_char(to_timestamp(archive_date::double precision), 'YYYY-MM')
        ELSE 'legacy-or-unknown'
      END AS bucket
    FROM cadastre_notification
    WHERE organization_id = :'org_id'::uuid AND archived = TRUE
)
SELECT bucket, count(*) AS rows
FROM normalized
GROUP BY bucket
ORDER BY bucket DESC
LIMIT 120;

\echo '=== TABLE / INDEX HEALTH ==='
SELECT s.relname,
       s.n_live_tup,
       s.n_dead_tup,
       s.seq_scan,
       s.idx_scan,
       s.last_analyze,
       s.last_autoanalyze,
       s.last_autovacuum,
       pg_size_pretty(pg_total_relation_size(s.relid)) AS total_size
FROM pg_stat_user_tables s
WHERE s.relname IN (
  'cadastre_notification', 'forest_registry_feature', 'cadastre_sub_part',
  'land_parcel', 'land_parcel_render', 'land_parcel_render_owner'
)
ORDER BY pg_total_relation_size(s.relid) DESC;

SELECT tablename, indexname, pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
       idx_scan
FROM pg_indexes i
JOIN pg_stat_user_indexes s
  ON s.schemaname = i.schemaname AND s.indexrelname = i.indexname
WHERE i.tablename IN ('cadastre_notification', 'forest_registry_feature', 'cadastre_sub_part')
ORDER BY i.tablename, pg_relation_size(indexrelid) DESC;

\echo '=== DATABASE / TEMP / IO ==='
SELECT datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit,
       temp_files, pg_size_pretty(temp_bytes) AS temp_bytes,
       blk_read_time, blk_write_time
FROM pg_stat_database
WHERE datname = current_database();

\echo '=== ACTIVE WAITS / LOCKS ==='
SELECT wait_event_type, wait_event, state, count(*) AS sessions
FROM pg_stat_activity
WHERE datname = current_database() AND pid <> pg_backend_pid()
GROUP BY wait_event_type, wait_event, state
ORDER BY sessions DESC, wait_event_type NULLS LAST;

SELECT locktype, mode, granted, count(*) AS locks
FROM pg_locks
WHERE database = (SELECT oid FROM pg_database WHERE datname = current_database())
GROUP BY locktype, mode, granted
ORDER BY granted, locks DESC;

\echo '=== DATABASE SETTINGS ==='
SELECT name, setting, unit
FROM pg_settings
WHERE name IN (
  'max_connections','shared_buffers','effective_cache_size','work_mem',
  'maintenance_work_mem','random_page_cost','effective_io_concurrency',
  'track_io_timing','max_parallel_workers_per_gather'
)
ORDER BY name;

\echo '=== TOP STATEMENTS BY TOTAL TIME ==='
SELECT queryid, calls, round(total_exec_time::numeric, 2) AS total_ms,
       round(mean_exec_time::numeric, 2) AS mean_ms, rows,
       shared_blks_read, shared_blks_hit, temp_blks_read, temp_blks_written,
       left(regexp_replace(query, E'[\\n\\r\\t]+', ' ', 'g'), 180) AS query_sample
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 15;

\echo '=== TOP STATEMENTS BY MEAN TIME (>= 3 calls) ==='
SELECT queryid, calls, round(total_exec_time::numeric, 2) AS total_ms,
       round(mean_exec_time::numeric, 2) AS mean_ms, rows,
       shared_blks_read, shared_blks_hit, temp_blks_read, temp_blks_written,
       left(regexp_replace(query, E'[\\n\\r\\t]+', ' ', 'g'), 180) AS query_sample
FROM pg_stat_statements
WHERE calls >= 3
ORDER BY mean_exec_time DESC
LIMIT 15;
