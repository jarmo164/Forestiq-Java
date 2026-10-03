# ForestIQ performance diagnostics

ForestIQ performance decisions are based on the deployed production environment and real workload after go-live. A specific developer laptop, RAM size or synthetic local p95 target is **not a release gate**.

The current local application is responsive enough for development. Do not block feature delivery on workstation-specific benchmark thresholds or tune production architecture from one machine's resource profile.

Authoritative follow-up: GitHub issue **#208 — Post-launch production performance validation and tuning**.

## Policy

- Local performance scripts are optional diagnostics only.
- Production-safe aggregate telemetry is the authoritative source for p50/p95/p99, error rate, CPU/memory, PostgreSQL pressure, cache behavior and background-job impact.
- Optimize only a measured production bottleneck.
- Do not add Redis/Valkey, replicas, partitioning, read replicas, Elasticsearch/OpenSearch, larger database tiers or other architectural complexity solely because an old local benchmark suggested it.
- Never attach personal data, bearer tokens, private provider payloads or sensitive query values to performance evidence.

## Retained diagnostics

The repository keeps general-purpose tools that are useful when investigating a real bottleneck:

- `scripts/performance-local-benchmark.sh` — optional supported-path development comparison;
- `scripts/performance-baseline.sh` — PostgreSQL/container diagnostic capture;
- `scripts/performance-final-gates.sh` — query-plan/cache diagnostics;
- `scripts/performance-history-scale-benchmark.sh` — history-query diagnostics when a suitable dataset exists;
- `scripts/performance-ingestion-concurrency.sh` — ingestion/concurrency diagnostics;
- `performance/sql/` — repeatable `EXPLAIN`, observability and scale queries.

These tools may be used locally or adapted for a safe staging/production investigation, but their local numeric thresholds are not release criteria.

## Production validation after go-live

Issue #208 owns the next performance review. With representative live usage, capture aggregate evidence for:

- owner and ownership-relation browsing/search;
- parcel detail and map tiles;
- PostgreSQL slow queries, waits/locks, temp I/O and connection-pool pressure;
- API/worker/container CPU and memory;
- MVT cache hit/miss/load and tile-generation latency across the real replica topology;
- background synchronization/import duration and impact on interactive requests;
- user-visible slow flows reported from the live application.

For a confirmed bottleneck, create a focused child issue containing the affected workflow, baseline evidence, suspected cause, measurable target and before/after validation.

## Existing architecture worth preserving

Performance work already delivered useful structural improvements independent of the old laptop gate:

- cursor/keyset paths for large owner/relation lists;
- bounded compact parcel/history reads;
- set-based/COPY ingestion paths;
- compact import-run auditing;
- precomputed map overview/read models;
- bounded in-process MVT caching;
- PostgreSQL/PostGIS observability and query-plan tooling.

Keep these unless production evidence shows a concrete reason to change them.
