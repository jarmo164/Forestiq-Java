# Nationwide notification bulk ingestion

This runbook covers #136/#125. It is for full snapshots/backfills (including multi-million-row archive files), not small online per-cadastre refreshes.

## Architecture

```text
CSV/provider snapshot
  -> SHA-256 source checksum
  -> notification_import_run (durable checkpoint/status)
  -> COPY raw rows into temporary PostgreSQL staging
  -> set-based normalize/validate/deduplicate
  -> UNLOGGED notification_import_stage
  -> one advisory-locked publish transaction
       -> ensure missing parcel shells
       -> changed-only source-identity UPSERT
       -> unchanged rows untouched
       -> stale rows removed only inside same source + active/archive class
       -> publish run counters atomically
  -> ANALYZE cadastre_notification
  -> persisted run metrics
```

Canonical reads continue to use `cadastre_notification`. Staging is disposable; `notification_import_run` is durable.

## Source identity and idempotency

A nationwide import must supply a stable `source_name`. Canonical source identity is:

```text
(organization_id, source_name, source_external_id)
```

The importer computes SHA-256 over the whole input file. If the same organization/source/archive/checksum has already reached `PUBLISHED`, re-running the command is a no-op.

Rows also receive a normalized field hash. An existing canonical row is physically updated only when its hash/archive class changed. This is critical at 8M scale: an unchanged full snapshot must not generate 8M updates/WAL/dead tuples.

## Run command

Active snapshot:

```bash
python3 scripts/import_notifications_bulk.py andmed/teatis_full.normalized.csv
```

Archive snapshot:

```bash
python3 scripts/import_notifications_bulk.py andmed/teatis_arhiiv_full.normalized.csv --archived
```

Use a separate `--source-name` only when the files represent different authoritative providers, not merely different dates from the same source.

## Failure/restart behavior

- raw COPY/normalization failure leaves canonical data unchanged;
- publish is one transaction; it either commits the new source generation or rolls back;
- a failed run is marked `FAILED` with a bounded error string;
- the durable run/checksum is reused on retry;
- UNLOGGED staging may disappear after an unclean PostgreSQL restart, which is expected: the retry re-COPYs and re-normalizes the source before publish;
- stale deletion is scoped to the same organization + source + active/archive class, so a failed or unrelated provider run cannot erase another source.

The importer takes a transaction-scoped advisory lock for `(organization, source, archive-class)` so two publishes for the same logical source cannot race.

## Validation/rejects

Rows missing source identity, cadastral code or notification number are rejected before canonical publish. The durable run records the total reject count; `notification_import_reject` keeps only bounded diagnostic samples (default 100, maximum 1000 from the CLI) so malformed input cannot create an unbounded error-log table.

Duplicate `source_external_id` values inside one file resolve to the last source row in that snapshot.

## Run metrics

`notification_import_run` records:

- staged/inserted/updated/unchanged/deleted/rejected counts;
- start/staged/published/failure timestamps;
- source checksum;
- publish duration and rows/sec;
- database growth bytes;
- WAL growth bytes;
- notification dead tuples before/after;
- waiting sessions before/after.

These are aggregate operational metrics and are safe to include in sanitized benchmark evidence.

## Concurrent performance gate

Use the same real file while measuring supported browsing:

```bash
BENCHMARK_LABEL=ingest-final \
  bash scripts/performance-ingestion-concurrency.sh \
  andmed/teatis_arhiiv_full.normalized.csv --archived
```

The harness measures owners, relations, parcel summary and a representative z12 tile before, during and after the import while also sampling container CPU/RAM and DB/WAL/lock health.

Acceptance target from #125:

- during-import critical p95 should stay within about 2x normal p95 unless the committed baseline justifies another bound;
- after publish + targeted ANALYZE, p95 must return to the normal interactive SLO;
- no stuck `idle in transaction` sessions/failed publish transaction;
- no blanket `VACUUM FULL` or REINDEX is run automatically.

## Vacuum policy

`ANALYZE cadastre_notification` is automatic after a successful publish. Normal autovacuum remains responsible for reclaiming changed/deleted tuples. Do not run `VACUUM FULL` or REINDEX as part of ingestion.

If a measured import leaves unusually high dead tuples and autovacuum does not make progress, investigate autovacuum thresholds/I/O pressure first. A regular `VACUUM (ANALYZE) cadastre_notification` may be scheduled during a safe operational window based on evidence; it is not a default import step.
