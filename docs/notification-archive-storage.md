# Notification active/archive storage decision

Status: **phase 1 implemented; physical partitioning remains evidence-gated by #133/#135**.

## Context

ForestIQ is expected to retain about 8M archived forest notifications, while the parcel panel normally needs only current notifications. The legacy table stores both classes in `cadastre_notification` and identifies history with `archived` plus a provider `archive_date` stored as `BIGINT`.

The row count alone is not enough reason to partition. The important questions are whether current reads can avoid archive pages/indexes, whether archive access is naturally time-bounded, whether `archive_date` is sufficiently immutable/clean to be a partition key, and whether pruning materially improves the measured workload.

## Options

### A. One table with workload-specific partial indexes

Current reads:

```text
organization_id + cadastral_code + archived=false + id DESC
```

Archive reads:

```text
organization_id + cadastral_code + archived=true
+ mandatory archive_date range
+ (archive_date DESC, id DESC) keyset cursor
```

Advantages:

- no data rewrite or cutover;
- existing notification identity/idempotency remains unchanged;
- simple rollback;
- partial indexes keep the common current path away from archive index entries;
- gives a clean baseline against which partitioning can be measured.

Costs:

- heap still contains both hot and cold rows;
- large archive churn can still affect table-level VACUUM/analyze and storage locality;
- retention cannot use cheap partition detach/drop.

### B. Small active table + time-partitioned archive relation

Preferred long-term shape **only when measurements justify it**:

- current table/read model contains current notifications only;
- append-oriented archive table is partitioned by a normalized immutable archive/registration month;
- archive API always supplies a time range so PostgreSQL can prune partitions;
- broad export is asynchronous and separate from interactive browsing.

Advantages:

- current-state heap/indexes stay small;
- time-bounded archive reads can prune old partitions;
- retention can detach/drop partitions rather than mass-delete history;
- archive maintenance can be isolated from the hot relation.

Costs/risks:

- PostgreSQL uniqueness/identity must include or otherwise account for the partition key;
- late/null/invalid provider dates need deterministic routing;
- existing foreign-key/idempotency semantics must survive movement between active/archive relations;
- backfill and cutover add operational risk and require reconciliation/rollback.

## Phase 1 decision

Implement **Option A now** and measure it before introducing partitioning.

The API contract is:

- `GET .../notifications/current/page` — current (`archived=false`) notifications only, bounded keyset by `id DESC`;
- `GET .../notifications/archive/page?from=...&to=...` — archive only, a mandatory finite provider-date range, ordered by `(archive_date DESC, id DESC)` with a two-column continuation cursor;
- max page size 100;
- tenant and assignment authorization runs before either query;
- archive rows with null `archive_date` are deliberately excluded from the time-range API until the source-data quality run classifies them.

Matching partial indexes are maintained separately for current and archive reads.

The map parcel panel uses the current-only path by default. Archive history is not fetched merely because a parcel was selected.

## Partition decision gate

Use `scripts/performance-history-scale-benchmark.sh` and the #133 representative dataset. Select Option B only if all of the following are demonstrated:

1. archive cardinality/date quality is representative of the expected 8M workload;
2. current reads materially benefit from physical hot/cold separation beyond partial indexes;
3. archive queries use bounded time windows that would prune a meaningful number of partitions;
4. table/index/VACUUM pressure from the shared heap is material;
5. the migration/reconciliation cost is justified by measured latency/operational gains.

If the indexed one-table model meets the SLOs with acceptable maintenance pressure, retain it and close the partitioning proposal with evidence rather than adding complexity.

## If partitioning is approved

### Partition key and granularity

Normalize provider date into a real immutable timestamp/month key first. Do **not** partition directly on ambiguous mixed-unit `BIGINT` values. Determine month vs quarter from measured date distribution and partition-count budget.

Late data goes to its historical partition after validation. Null/unparseable dates go to a small explicit quarantine/default relation and are observable; they must not silently land in an arbitrary month.

### Migration

1. preflight disk space, locks, date quality, future partition coverage and source identity rules;
2. create shadow active/archive structures and per-partition measured indexes;
3. backfill in bounded batches ordered by stable identity/date;
4. reconcile total/current/archive/null-date counts and source-identity checksums after each batch;
5. use controlled dual-write or a bounded write freeze for cutover;
6. switch reads only after reconciliation passes;
7. retain old relation for a bounded rollback window;
8. on rollback, restore old reads/writes; never reverse via a single production-wide delete/rewrite.

### Operations

- provision partitions ahead of time;
- targeted `ANALYZE` changed partitions after publish;
- observe partition sizes, dead tuples, autovacuum/analyze freshness, locks and query plans;
- retention uses detach/archive/drop according to policy, never routine `VACUUM FULL`;
- alert on default/quarantine partition growth and missing future partitions.

## Ingestion dependency

#136 must publish to whichever model wins this gate using COPY staging + set-based validation/merge. It must not call the interactive domain path once per notification. The ingest run must reconcile staged/inserted/updated/unchanged/rejected counts and only mark a source generation successful after the destination state is complete.
