# ForestIQ backend performance benchmark

**Scope:** issue #85 / backend performance release policy  
**Status:** benchmark tooling and enforceable budgets are implemented. Environment-specific measurements are release/capacity evidence and must be refreshed when infrastructure, workload or critical queries materially change.

## 1. What a performance run proves

Performance is a release property, not a unit-test assumption. A valid run records at least:

- p50 / p95 / p99 HTTP latency;
- error rate, response bytes and requests/second at a stated concurrency;
- PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plans for critical reads;
- jOOQ/DB timing from ForestIQ metrics;
- API behavior while a worker is processing integration jobs;
- dataset size, DB tier, API replica count and worker concurrency.

Do not compare results unless those inputs are documented. Do not treat a measurement from one Render/Postgres tier as a universal baseline.

## 2. Initial latency budgets

| Scenario | Initial hard target |
| --- | ---: |
| common API read p95 | < 200 ms |
| complex owner/search/filter p95 | < 300 ms |
| detail backend read p95 | < 300 ms |
| cached vector tile p95 | < 100 ms |
| uncached protected MVT DB query | < 300 ms initial investigation budget; tune per layer after measurement |
| durable integration enqueue | < 250 ms and no provider network I/O on request thread |
| HTTP error rate during steady benchmark | 0% unless the scenario explicitly tests overload |

If a representative run misses a target, preserve the result, find the bottleneck and either fix capacity/query behavior or deliberately revise the target with evidence.

## 3. PostGIS scale gate

The preferred representative gate is one command:

```bash
DATABASE_URL="$DATABASE_URL" \
python scripts/perf/run_query_plan_gate.py --seed
```

`--seed` recreates only the deterministic performance organization and, by default, asks `seed_postgis.sql` for **250,000 owners and 2,000,000 parcels**. It then captures all critical plans and validates them.

For a lightweight developer fixture, the seed SQL itself intentionally defaults to 50,000 owners / 100,000 parcels:

```bash
psql "$DATABASE_URL" -f scripts/perf/seed_postgis.sql
python scripts/perf/run_query_plan_gate.py --database-url "$DATABASE_URL"
```

The performance tenant is:

```text
99999999-9999-9999-9999-999999999999
```

Never point the destructive seed step at a database where that reserved organization ID contains real data.

### Gate coverage

The plan gate captures:

- organization-scoped owner full-text search;
- organization + cadastral-code lookup;
- protected PostGIS MVT bbox/candidate/encoding path;
- stale registry selection;
- durable `QUEUED` integration-run claim.

Artifacts are written under `build/perf/`:

- `query-plans.json.txt` — raw PostgreSQL JSON plans;
- `query-plan-report.json` — compact machine-readable result.

The validator fails when a critical-table sequential scan touches at least 100,000 rows. The threshold is a regression guard, not permission for every smaller sequential scan: query latency, buffers, tenant predicate placement and index choice still require review.

Override fixture size or tile when needed:

```bash
python scripts/perf/run_query_plan_gate.py \
  --database-url "$DATABASE_URL" \
  --seed \
  --owner-count 500000 \
  --parcel-count 3000000 \
  --tile-z 12 --tile-x 2302 --tile-y 1243
```

The validator itself has stdlib regression tests:

```bash
python scripts/perf/test_validate_query_plans.py
```

## 4. Plan acceptance

Reject/investigate a critical plan when it:

- sequentially scans a large current-state relation without a measured reason;
- filters organization/security scope only after a broad scan;
- performs an avoidable sort over an unbounded candidate set;
- stops using `land_parcel_boundary_gix` for a representative tile candidate scan;
- stops using the durable-queue partial index when queue history is large enough for it to matter;
- has buffer reads or execution time that grow disproportionately with tenant-independent data volume.

Store the full plan, not only the final execution time. Do not assert unstable PostgreSQL planner cost numbers exactly; protect structural invariants and measure real duration/buffers.

## 5. HTTP latency gate

`scripts/perf/benchmark_http.py` uses only the Python standard library and exits non-zero when no request succeeds, the error budget is exceeded, or p95 is above `--p95-ms`.

Example owner-list gate:

```bash
FORESTIQ_BENCHMARK_TOKEN="$TOKEN" \
python scripts/perf/benchmark_http.py \
  --base-url http://localhost:8080 \
  --path '/api/v1/owners?search=&page=0&size=20' \
  --requests 500 \
  --concurrency 16 \
  --p95-ms 200 \
  --output build/perf/owner-list.json
```

Use the current API contract for complex-search requests and its 300 ms budget. Benchmark sparse and dense protected vector tiles separately.

Hosted GitHub Actions are not required for these gates. They are deliberately provider-neutral commands that can be run locally, during a release, from a deployment job, or by another CI system later.

## 6. API isolation under background ingestion

The runtime split is part of the performance contract. For a capacity/release check:

1. benchmark the API role with no active integration work;
2. record DB pool usage, CPU, jOOQ query time and HTTP p50/p95/p99;
3. start the worker role with production-like `FORESTIQ_RUNTIME_WORKER_CONCURRENCY`;
4. enqueue a representative integration job through the durable queue;
5. repeat the same HTTP benchmark while the worker is active;
6. record the delta in p95/p99, DB pool saturation and query time.

The comparison is invalid if provider work executes in the API process. A capacity regression is significant when background work causes sustained API p95 above the endpoint budget or saturates the documented API datasource pool.

## 7. Cache-unavailable comparison

For a Redis/Valkey-enabled profile:

1. warm the allowed cache path;
2. benchmark and record hit metrics;
3. stop/block the cache;
4. repeat the requests;
5. verify PostgreSQL fallback continues and `forestiq.cache.errors` increases;
6. record latency and DB-query deltas.

Correctness must not depend on cache availability. The `core` deployment with `spring.cache.type=none` is the direct PostgreSQL baseline.

## 8. Metrics and dashboard

`PerformanceMetricsConfiguration` publishes p95/p99 distributions and a percentile histogram for `http.server.requests`. `JooqQueryMetricsConfiguration` publishes p95/p99 plus a percentile histogram for `forestiq.db.jooq.query.latency` and counts slow queries in `forestiq.db.jooq.query.slow`.

The provisioned Grafana dashboard is:

```text
infra/grafana/provisioning/dashboards/forestiq-performance.json
```

It exposes:

- HTTP p95 / p99;
- jOOQ DB p95 / p99;
- HTTP request and 5xx rates;
- Hikari active/idle/pending connections;
- slow jOOQ query rate;
- integration queue depth/running/worker-active signals.

At minimum preserve the following metrics during a retained performance run:

- `http.server.requests` p95/p99;
- `forestiq.db.jooq.query.latency` p95/p99 and `forestiq.db.jooq.query.slow`;
- Hikari active/idle/pending connections;
- `forestiq.map.tile.latency`, bytes, features and feature-cap hits;
- `forestiq.integration.queue.depth` and running count;
- `forestiq.integration.worker.active` and saturation;
- cache requests/errors/evictions/load time where applicable.

Never add organization IDs, subjects, cadastral codes or arbitrary query strings as metric labels.

## 9. Capacity inputs to record

Every retained baseline should state:

- PostgreSQL/PostGIS provider/tier, vCPU/RAM/storage and connection limit;
- owner, parcel, relation and registry/history row counts;
- API replicas and `DATABASE_POOL_SIZE`;
- worker replicas, worker DB pool and concurrency;
- cache enabled/disabled and tier;
- JVM heap/container memory;
- HTTP concurrency and benchmark-client location;
- whether external-provider latency is included.

Do not increase worker concurrency beyond DB/provider capacity without measured headroom.

## 10. Baseline record template

```text
Date/time UTC:
Commit SHA:
Environment:
PostgreSQL/PostGIS tier:
Dataset: owners=, parcels=, relations=, registry rows=
API replicas / pool:
Worker replicas / concurrency / pool:
Redis/Valkey:
Scenario:
Requests / concurrency:
p50 / p95 / p99:
RPS:
Error rate:
Mean/max response bytes:
DB/jOOQ p95 or key query execution time:
Relevant EXPLAIN report:
Background ingestion active: yes/no
Result vs budget: PASS/FAIL
Notes:
```

## 11. Issue vs operational evidence

Issue #85 tracks delivery of the benchmark harness and enforceable performance budgets. That implementation is complete when the repository contains:

- representative scale fixture generation;
- machine-verifiable critical query-plan gates;
- HTTP p95/error-budget enforcement;
- p95/p99 HTTP and DB observability with a provisioned dashboard;
- runtime metrics needed to diagnose failures;
- a repeatable procedure for idle-vs-background-ingestion comparison.

Actual latency values are environment-specific operational evidence. Record and refresh them during capacity/release work; do **not** invent or commit a universal PASS when no representative database/runtime was measured.