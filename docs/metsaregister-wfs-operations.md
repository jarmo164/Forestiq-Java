# Metsaregister WFS mirror operations

## Purpose

ForestIQ stores the public Metsaregister WFS source once in a provider-global PostGIS mirror. Tenant-facing views and parcel intersections read only a fully published generation. A partially downloaded generation is never promoted over the last-known-good snapshot.

The runtime WFS catalogue is authoritative. Do not hardcode a historical layer count in operational checks: provider additions are discovered by `GetCapabilities`, schema approval is fail-closed, and protected/unavailable layers can remain intentionally disabled.

## Normal verification

Use the ORG_ADMIN endpoint:

`GET /api/v1/admin/integrations/metsaregister-wfs/verification?deep=false`

This checks each enabled/provider-present layer against generation metadata and the provider `numberMatched` recorded during ingestion. The result is persisted on `metsaregister_wfs_layer` as `last_verified_at`, `last_verification_state`, `last_verified_local_count`, and `last_verified_provider_count`.

Use `deep=true` only during controlled verification windows. Deep verification counts the actual current-generation feature rows and can be expensive on multi-million-row layers.

A healthy layer should report `COMPLETE`. Investigate `NO_GENERATION`, `UNKNOWN_PROVIDER_COUNT`, or any `*_MISMATCH` state before declaring a full mirror complete.

## Provider limits and resumability

ForestIQ caps a single WFS page at 1,000,000 rows and normally uses much smaller pages. Completion is determined from actual `features`, `numberReturned`, and `numberMatched`; reaching a page boundary alone never marks a layer complete.

Each page commits its checkpoint (`next_start_index`, pages and rows staged) in its own transaction. After restart, the next run resumes the existing `STAGING` generation. Publication occurs only after the provider count and staged count agree.

For a layer above 1,000,000 rows, verify that:

1. more than one page/checkpoint was processed;
2. `rows_staged` increases across runs;
3. the final `provider_number_matched` equals `current_row_count`;
4. deep verification returns `COMPLETE`.

## Failure recovery

### Provider outage or timeout

Leave the `STAGING` generation intact. The retry/backoff policy is bounded and the next scheduled/manual run resumes from the durable checkpoint. Do not delete the last published generation.

### Schema drift

`DescribeFeatureType` changes put an approved layer into `SCHEMA_DRIFT`. Review identity, sort and geometry fields before approving the changed schema. Do not force ingestion around this guard: deterministic ordering is required for safe `startIndex` resume.

### Stuck checkpoint

First compare `staging_next_start_index`, `staging_rows_staged`, `staging_provider_number_matched`, last error and provider availability in the Admin layer view. Retry the single layer manually. Preserve the staging generation unless the operator has established that the provider ordering/identity changed and the generation cannot safely resume.

### Malformed geometry or duplicate/missing key

The affected layer fails closed; other layers continue independently. The current published generation remains authoritative. Fix/understand provider data or schema identity before starting a replacement generation.

## Generation retention

Published snapshots are immutable. When a new generation is promoted, the old one becomes `RETIRED`.

`POST /api/v1/admin/integrations/metsaregister-wfs/maintenance/cleanup-retired`

removes only `RETIRED` generations older than the configured retention window. Features are deleted through the generation foreign key with `ON DELETE CASCADE`. The active `current_generation_id` is explicitly excluded and also protected by a foreign key.

Default retired-generation retention is 14 days. Override with:

`FORESTIQ_REGISTRY_METSAREGISTER_RETIRED_GENERATION_RETENTION_DAYS`

`STAGING` generations are not automatically deleted because they are the durable restart checkpoint. Recovery or abandonment of a stuck staging generation must be an explicit operator decision.

## Performance evidence

Run these only on a production-like replica or maintenance window. Record timestamp, layer, row count and environment with every result; do not commit provider payloads, credentials or access tokens.

### Local Compose verification - 2026-09-03

Environment: local Docker Compose stack, PostgreSQL 17 with PostGIS 3.5, API readiness `200`.

Completeness:

- Enabled and approved provider layers: 22.
- Published enabled layers: 22.
- Current rows: 14,208,910.
- Deep verification endpoint: `GET /api/v1/admin/integrations/metsaregister-wfs/verification?deep=true`.
- Deep verification result: 22 layers stored `COMPLETE`; local rows 14,208,910, provider rows 14,208,910.

Large-layer resume and publication:

- Representative layer: `metsaregister:eraldis`.
- Final published row count: 1,769,638.
- Provider `numberMatched`: 1,769,638.
- Bounded refresh runs: 19.
- Successful row-writing runs: 18.
- Provider timeout/failure runs: 1, followed by a successful resume from the retained `STAGING` checkpoint.
- Pages written: 172.
- Total measured duration including timeout: 2,422,533 ms.
- Write throughput excluding the timeout-only run: 752.1 rows/s.
- Final generation state: promoted to `PUBLISHED`; no `STAGING` generation remained for the layer.

Footprint and maintenance:

- `metsaregister_wfs_feature` table size: 13 GB.
- `metsaregister_wfs_feature` index size: 4,305 MB.
- `metsaregister_wfs_feature` total relation size: 17 GB.
- Generation states after refresh: 22 `PUBLISHED`, 3 `RETIRED`, 0 `STAGING`.
- `ANALYZE metsaregister_wfs_feature` completed at `2026-09-03 16:28:33.235685+00`.
- `pg_stat_user_tables` after refresh showed `vacuum_count=1`, `autovacuum_count=7`, `analyze_count=2`, `autoanalyze_count=9` for `metsaregister_wfs_feature`.

Spatial data and query plan:

- `metsaregister:eraldis` spatial extent: `POLYGON((21.81808017 57.50937278,21.81808017 59.6843957,28.16444766 59.6843957,28.16444766 57.50937278,21.81808017 57.50937278))`.
- Other large spatial layers (`pesitsusrahu_maatriks`, `teatis_arhiiv`, `mr__teema_raie_*`, `teatis`, `mue`) had extents inside Estonia's lon/lat range.
- Representative `ST_Intersects` query on `metsaregister:eraldis` returned 823 rows.
- The plan used `metsaregister_wfs_feature_geometry_gix` for the spatial prefilter and `metsaregister_wfs_layer_pkey` for the layer lookup.
- Execution time: 21.979 ms; planning time: 39.546 ms; shared buffers hit/read: 3,365/81.

Interactive latency during verified mirror state:

- Admin layers endpoint sample: 25 authenticated requests.
- Statuses: 25 x `200`.
- p50: 9 ms.
- p95: 34 ms.
- max: 112 ms.

Table/index footprint:

```sql
SELECT pg_size_pretty(pg_relation_size('metsaregister_wfs_feature')) AS table_size,
       pg_size_pretty(pg_indexes_size('metsaregister_wfs_feature')) AS index_size,
       pg_size_pretty(pg_total_relation_size('metsaregister_wfs_feature')) AS total_size;
```

Write progress/throughput can be derived from the integration run metrics: `rows`, `pages`, `durationMs`, `retries`, per-layer outcome, `stagingLayers` and `checkpointLagRows`. Calculate rows/s from `rows * 1000 / durationMs` for the same run.

After a representative bulk refresh, inspect statistics before/after `ANALYZE metsaregister_wfs_feature;`. Use `VACUUM (ANALYZE)` only when normal PostgreSQL maintenance policy requires it; do not place VACUUM inside the ingestion transaction.

Spatial plan check (substitute real geometry/parcel lookup parameters):

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT f.feature_key
FROM metsaregister_wfs_feature f
JOIN metsaregister_wfs_layer l
  ON l.layer_name=f.layer_name
 AND l.current_generation_id=f.generation_id
WHERE f.layer_name='metsaregister:eraldis'
  AND f.geometry && ST_MakeEnvelope(24.5, 58.8, 25.0, 59.2, 4326)
  AND ST_Intersects(f.geometry, ST_MakeEnvelope(24.5, 58.8, 25.0, 59.2, 4326));
```

The plan should use `metsaregister_wfs_feature_geometry_gix` for the spatial prefilter and the layer/generation index for generation-scoped reads. If it falls back to a large sequential scan, capture the plan and table statistics before changing indexes.

## Interactive latency safety

Bulk WFS writes run through the integration worker and do not replace current generations until completion. During a representative refresh, compare API p95/p99 latency and PostgreSQL connection-pool saturation against the normal baseline. If interactive latency degrades, lower `METSAREGISTER_WFS_BULK_PAGES_PER_RUN`, reduce page size, or schedule large layers outside the busiest window before increasing database resources.

A refresh is not considered production-proven until at least one provider layer over 1,000,000 rows has completed through multiple checkpoints and its deep verification, DB footprint, write throughput and representative spatial query plan have been recorded.
