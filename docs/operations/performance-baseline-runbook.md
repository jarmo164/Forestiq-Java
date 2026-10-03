# Production Performance Baseline Runbook

Status: ready for post-launch measurement

## Rule

ForestIQ performance tuning is evidence-driven. Local workstation benchmarks are not release gates. Do not add architectural complexity unless production telemetry shows a real bottleneck.

## Measurement Window

Use at least one representative business day after launch. If traffic is still sparse, collect a longer window and label it as low-traffic baseline.

## Privacy

Do not export personal data, bearer tokens, raw owner notes, private payloads or sensitive query parameter values. Store only aggregate metrics, redacted endpoint patterns and anonymized workflow observations.

## Critical Workflows

| Workflow | Signals |
| --- | --- |
| Owner search/list | API p50/p95/p99, error rate, DB query duration, response size |
| Owner detail and activities | API latency, DB locks/waits, slow queries |
| Ownership relations | API latency, query plans, dead tuples, relation table index usage |
| Parcel detail | API latency, partial section failures, payload size |
| Map tiles/MVT | cache hit/miss, tile generation p95/p99, CPU/memory |
| Admin integrations | queue depth, run duration, worker heartbeat age, failure class |
| Background imports | run duration, DB temp I/O, API latency during import |

## Data Sources

| Source | What to capture |
| --- | --- |
| API metrics | Request count, p50/p95/p99, error rate by route group |
| PostgreSQL | slow queries, locks/waits, connection usage, temp I/O, dead tuples |
| Containers/services | CPU, memory, restart count, health state |
| Ingestion control plane | run state, queue depth, heartbeat, data age |
| User reports | workflow, approximate time, visible error, role |

## Decision Rules

Create a child issue only when all are present:

1. affected live workflow;
2. baseline p95/p99 or resource evidence;
3. suspected bottleneck;
4. measurable target;
5. production-safe before/after validation plan.

Do not introduce Redis/Valkey, search engines, read replicas, partitioning, larger tiers or extra replicas without measured evidence.

## Output

Save one filled baseline report per measurement window using docs/operations/performance-baseline-report-template.md.
