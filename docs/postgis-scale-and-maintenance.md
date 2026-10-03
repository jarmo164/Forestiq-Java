# PostGIS scale and maintenance policy

**Scope:** ForestIQ production PostgreSQL/PostGIS datasets and the query paths tracked by issue #81.

## 1. Planning envelope

These are capacity-planning envelopes, not claims about the current production row count. Re-measure quarterly and before a provider import is materially expanded.

| Dataset | Initial planning envelope | Primary access pattern |
| --- | ---: | --- |
| `land_parcel` | 1–3 million current rows | organization + cadastral code, bbox/MVT, updated cursor |
| `forest_registry_feature` | 10–30 million current/source rows | organization + cadastral + source |
| `registry_sync_log` | potentially tens of millions without retention | organization + cadastral + registry + freshness |
| integration run/checkpoint tables | low millions over long retention | state/queue/cursor/time |
| CRM owner/deal/reminder tables | materially smaller than registry data | organization-scoped interactive reads/writes |

Current-state CRM tables are not partitioned. Partitioning them would add operational complexity without evidence that it improves measured queries.

## 2. Index contract

Critical queries must keep the tenant predicate in SQL before paging/aggregation and use indexes aligned to the predicate. Flyway migration `V22__backend_scale_indexes.sql` owns the current scale indexes, including:

- `land_parcel_org_updated_idx`;
- `land_parcel_org_marked_idx`;
- existing `land_parcel_boundary_gix` for bbox/tile access;
- `owner_org_assignee_status_updated_idx`;
- active owner/parcel relation indexes;
- registry freshness/source indexes;
- durable queue partial index for `state = 'QUEUED'`.

Do not add indexes speculatively. Every new production index must name the query it protects and account for write amplification/storage.

## 3. Geometry policy

- Persist canonical parcel geometry in SRID 4326.
- Apply tenant + bbox predicates against stored parcel geometry so the GiST index remains usable.
- Transform only the bounded candidate set to WebMercator for MVT encoding.
- Low zoom MVT may simplify candidate geometry after bbox/tenant filtering; never transform/simplify the entire table as a request-time pre-step.
- Invalid provider geometry must be normalized/rejected during ingestion/apply, not repeatedly repaired on interactive reads.
- Introduce stored simplified geometry/materialized low-zoom models only when representative tile measurements prove the current bounded-candidate approach insufficient.

`forest_registry_feature.geometry` is provider/source payload storage and is not the canonical interactive parcel geometry. Do not convert it to PostGIS solely because of its column name; spatial request paths use normalized `land_parcel.boundary`.

## 4. Partitioning decision

Introduce partitions only when all relevant conditions are met:

1. a table is expected to exceed roughly 20–50 million retained rows **or** its indexes no longer fit the operational memory/IO budget;
2. the dominant retention/query boundary maps cleanly to a partition key, for example source + month;
3. representative `EXPLAIN (ANALYZE, BUFFERS)` demonstrates material gain from partition pruning;
4. uniqueness, FK and maintenance implications have been reviewed.

Likely first candidates are append-heavy registry audit/history/raw tables, not `owner` or `land_parcel` current state.

## 5. Retention

PostgreSQL is the source of truth for business/current state; raw/provider diagnostics are not retained indefinitely by default.

- Keep durable integration run/checkpoint state needed for recovery and operational history.
- Apply registry audit retention/payload limits defined by ingestion policy.
- Do not persist complete WFS/provider payloads when normalized fields plus bounded diagnostics are sufficient.
- Archive/delete obsolete high-volume audit/history by time window; retention changes must be Flyway/config documented and observable.
- Never delete the last-known-good current snapshot as part of audit cleanup.

## 6. Statistics, VACUUM and ANALYZE

Production must keep PostgreSQL autovacuum enabled. Monitor dead tuples and analyze freshness on high-churn/import tables.

Operational check:

```sql
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

After unusually large bulk loads, run targeted `ANALYZE` on affected tables after apply completes. Avoid routine `VACUUM FULL`; it is blocking and requires a planned maintenance window.

`V22__backend_scale_indexes.sql` raises statistics targets on skew-sensitive organization/status/registry columns. Revisit those targets only with planner evidence.

## 7. Executable query-plan regression gate

The repository contains a deterministic scale fixture and a machine-verifiable plan gate. The preferred representative run is:

```bash
DATABASE_URL="$DATABASE_URL" \
python scripts/perf/run_query_plan_gate.py --seed
```

The wrapper requests the representative fixture size of 250,000 owners and 2,000,000 parcels, runs `ANALYZE`, captures `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, SETTINGS, FORMAT JSON)` and validates these critical paths:

1. organization-scoped owner search;
2. organization + cadastral-code lookup;
3. protected parcel MVT bbox query;
4. stale registry selection;
5. durable integration queue claim.

The raw SQL is in `scripts/perf/explain_critical_queries.sql`; validation is in `scripts/perf/validate_query_plans.py`. The gate returns non-zero when a protected critical-table sequential scan touches at least 100,000 rows, or when required plan evidence is missing/invalid.

Validator regression tests:

```bash
python scripts/perf/test_validate_query_plans.py
```

Artifacts are written under `build/perf/` and are intentionally not hard-coded into source as universal planner numbers.

### Plan-review rules

The automated threshold catches large obvious regressions. Also investigate when a representative plan:

- loses the organization predicate before the bounded step;
- loses `land_parcel_boundary_gix` for a spatial bbox path;
- performs an avoidable unbounded sort;
- shows buffers/execution time growing disproportionately with unrelated tenant data;
- misses a purpose-built index when table size/selectivity makes that index appropriate.

Do not assert exact PostgreSQL cost estimates in tests. Protect structural invariants and retain real duration/buffer data as capacity evidence under `docs/performance-benchmark.md`.

## 8. Growth review

Record at least monthly in production observability:

```sql
SELECT
  relname,
  pg_total_relation_size(relid) AS total_bytes,
  n_live_tup,
  n_dead_tup
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
```

Escalate a storage/partition review when:

- a high-volume table grows >25% month-over-month for two consecutive months;
- dead tuples remain >20% of live rows after normal autovacuum cycles;
- a critical index no longer produces the expected plan;
- MVT/search p95 exceeds the performance budget with DB time as the measured bottleneck.

This policy deliberately prefers measured PostgreSQL tuning over premature sharding or microservices.

## 9. Issue completion boundary

Issue #81 owns the database scaling **design and regression protection**: schema/index contract, geometry policy, retention/partition criteria, maintenance policy and a repeatable large-fixture query-plan gate. Those repository deliverables are implemented.

Environment-specific execution times and capacity baselines are operational performance evidence and belong to `docs/performance-benchmark.md`; they must be measured on the target environment when making capacity/release decisions rather than duplicated as a permanent blocker in both #81 and #85.