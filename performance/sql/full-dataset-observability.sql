\pset pager off
\timing on

-- Run after starting PostgreSQL with compose.performance.yaml so
-- pg_stat_statements is preloaded.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

\echo '=== DATABASE SIZE ==='
SELECT current_database() AS database_name,
       pg_size_pretty(pg_database_size(current_database())) AS database_size;

\echo '=== LARGE TABLE SIZES ==='
SELECT relname AS table_name,
       n_live_tup,
       n_dead_tup,
       seq_scan,
       idx_scan,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       pg_size_pretty(pg_relation_size(relid)) AS heap_size,
       pg_size_pretty(pg_indexes_size(relid)) AS index_size
FROM pg_stat_user_tables
WHERE relname IN (
    'owner',
    'land_parcel',
    'owner_parcel_relation',
    'registry_sync_log',
    'data_change_signal',
    'forest_registry_feature',
    'cadastre_notification'
)
ORDER BY pg_total_relation_size(relid) DESC;

\echo '=== CACHE / IO STATS ==='
SELECT relname,
       heap_blks_read,
       heap_blks_hit,
       CASE WHEN heap_blks_read + heap_blks_hit = 0 THEN NULL
            ELSE round(100.0 * heap_blks_hit / (heap_blks_read + heap_blks_hit), 2)
       END AS heap_cache_hit_pct,
       idx_blks_read,
       idx_blks_hit
FROM pg_statio_user_tables
WHERE relname IN ('owner', 'land_parcel', 'owner_parcel_relation')
ORDER BY relname;

\echo '=== GEOMETRY COMPLEXITY ==='
SELECT count(*) FILTER (WHERE boundary IS NOT NULL) AS geometries,
       round(avg(ST_NPoints(boundary))::numeric, 1) AS avg_points,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p50_points,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p95_points,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY ST_NPoints(boundary)) AS p99_points,
       pg_size_pretty(avg(pg_column_size(boundary))::bigint) AS avg_geometry_bytes
FROM land_parcel
WHERE boundary IS NOT NULL;

\echo '=== TOP STATEMENTS BY TOTAL TIME ==='
SELECT calls,
       round(total_exec_time::numeric, 1) AS total_exec_ms,
       round(mean_exec_time::numeric, 2) AS mean_exec_ms,
       rows,
       shared_blks_hit,
       shared_blks_read,
       temp_blks_read,
       temp_blks_written,
       left(regexp_replace(query, '\\s+', ' ', 'g'), 220) AS query_sample
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT 15;

\echo '=== TOP STATEMENTS BY MEAN TIME (MIN 5 CALLS) ==='
SELECT calls,
       round(total_exec_time::numeric, 1) AS total_exec_ms,
       round(mean_exec_time::numeric, 2) AS mean_exec_ms,
       rows,
       shared_blks_hit,
       shared_blks_read,
       temp_blks_read,
       temp_blks_written,
       left(regexp_replace(query, '\\s+', ' ', 'g'), 220) AS query_sample
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND calls >= 5
ORDER BY mean_exec_time DESC
LIMIT 15;

\echo '=== TEMP FILES / CONNECTIONS ==='
SELECT datname,
       numbackends,
       temp_files,
       pg_size_pretty(temp_bytes) AS temp_bytes,
       blk_read_time,
       blk_write_time
FROM pg_stat_database
WHERE datname = current_database();

\echo '=== LONG RUNNING QUERIES / LOCK WAITS ==='
SELECT pid,
       state,
       wait_event_type,
       wait_event,
       now() - query_start AS age,
       left(regexp_replace(query, '\\s+', ' ', 'g'), 220) AS query_sample
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND state <> 'idle'
ORDER BY query_start;
