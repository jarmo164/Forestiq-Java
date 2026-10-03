# ForestIQ history-scale performance runbook

This runbook is the evidence gate for issues #132/#133 and feeds the archive/ingestion decisions in #135/#136.

## Purpose

Measure ForestIQ against an already-loaded local or approved private dataset representing the production envelope:

- about 780k cadastral parcels;
- about 1.2M owner-parcel relations;
- up to about 8M forest notifications, including archived history;
- up to about 1.8M forest-plan sub-parts/features.

The harness is **non-destructive**. It builds/starts the current Docker Compose checkout, runs read workloads and database diagnostics, and leaves the stack and volumes in place. It does not seed, truncate, `down -v`, `VACUUM FULL`, REINDEX or perform a production import.

## One-command run

From the ForestIQ repository root:

```bash
bash scripts/performance-history-scale-benchmark.sh
```

The normal local dev realm is supported automatically. For a non-seed organization or another approved environment, provide an access token and organization explicitly:

```bash
TOKEN='<bearer-token>' PERF_ORG_ID='<organization-uuid>' \
  bash scripts/performance-history-scale-benchmark.sh
```

Useful workload overrides:

```bash
REQUESTS=100 CONCURRENCY=10 TILE_REQUESTS=80 TILE_CONCURRENCY=5 \
  bash scripts/performance-history-scale-benchmark.sh
```

## What the harness selects automatically

The harness does not require a private cadastral identifier on the command line. Inside PostgreSQL it selects:

1. a high-history cadastral unit by the combined count of notifications, registry features and sub-parts;
2. a sparse cadastral unit for contrast;
3. representative z7, z11 and z14 map tiles around the high-history unit.

Cadastral identifiers are not written to the environment summary. SQL plans and API logs are passed through a cadastral-code redactor before being written to the result directory.

## Captured evidence

Results are written under:

```text
.run/performance-history/<timestamp>-<git-sha>/
```

`.run/` is ignored by Git. The result contains:

- `http-summary.tsv` — success count, avg/p50/p95/p99/max latency and response-byte percentiles;
- high-history and sparse parcel-summary, notification, feature and sub-part cursor workloads;
- cold and warm z7/z11/z14 protected MVT workloads;
- `database-observability.txt` — cardinality, active/archive split, per-cadastre distributions, payload/geometry byte percentiles, table/index health, temp I/O, waits/locks, settings and top `pg_stat_statements`;
- `query-plans.txt` — `EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)` for parcel summary, notification/archive pages, feature/sub-part pages and low/mid/high zoom MVT generation;
- `docker-stats.csv` — repeated per-container CPU/memory/network/block-I/O samples;
- host physical/virtual memory snapshots before/after where the host shell exposes them;
- `wal.txt` — WAL delta during the measured HTTP workload;
- `errors/` plus a sanitized API tail log for diagnosing non-2xx results such as the previously observed parcel-summary failure.

The Spring application already exposes Actuator `metrics` and `prometheus` endpoints. When deeper cache/Hikari investigation is required, capture those from the API container during the same run rather than introducing another monitoring platform solely for this benchmark.

## Initial performance gates

These are investigation/release targets, not claims about an environment that has not been measured:

| Flow | Initial target |
| --- | ---: |
| Parcel compact summary | p95 <= 300 ms |
| First notification/sub-part/feature cursor page | p95 <= 300 ms |
| Filtered archive cursor page | p95 <= 500 ms |
| Low-zoom overview tile | p95 <= 200 ms after #137 overview work |
| Detailed MVT warm cache hit | p95 <= 50 ms |
| Typical detailed MVT cold generation | investigation budget <= 500 ms |
| HTTP error rate | < 1% |

A pass requires the relevant representative cardinality. A small local table cannot prove the 8M/1.8M gate.

## Evidence required before #135 partitioning

Do not partition `cadastre_notification` merely because the projected archive count is large. Use the run to answer:

- actual active/archive row ratio;
- archive-date quality and distribution;
- p50/p95/p99/max records per cadastral unit;
- table/index size and write churn;
- whether time-bounded archive reads are the real access pattern;
- whether the current index-only alternative meets the target;
- whether a candidate partitioned layout would allow meaningful partition pruning.

Only then select between the simpler indexed table and active + partitioned archive design.

## Privacy and committing results

Never commit:

- bearer tokens or `.env` contents;
- raw provider payloads;
- owner/person data;
- raw cadastral identifiers from a private dataset;
- raw files from `.run/performance-history/` without review.

For issue evidence, create a sanitized aggregate report under `performance/benchmark-runs/` containing environment class, cardinalities, percentiles, redacted plans and conclusions. Review it before pushing.

## Concurrent ingestion gate

This harness establishes the idle/read baseline. Issue #125/#136 additionally requires the same critical browsing paths while a real COPY/set-based import is running, plus import rows/sec, WAL/database growth and post-import health. Do not simulate success by running an empty or tiny import; use the approved representative source or a scale-equivalent sanitized fixture.
