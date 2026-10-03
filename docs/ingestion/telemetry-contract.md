# Ingestion operational telemetry contract

This contract keeps process health, queue health and source freshness separate.
It is the API/UI acceptance boundary for issue #369.

## Admin API

`GET /api/v1/admin/integrations` remains the source list and run-history
contract. `GET /api/v1/admin/integrations/operations` adds runtime-wide
low-cardinality state:

| Field | Meaning |
| --- | --- |
| `queueDepth` | QUEUED durable commands |
| `oldestQueuedAgeSeconds` | age of the oldest QUEUED command, 0 when empty |
| `runningJobs` | actively leased commands |
| `workerHeartbeatAgeSeconds` | newest worker heartbeat age, null when absent |
| `schedulerHeartbeatAgeSeconds` | current scheduler heartbeat age, null when absent |
| `workerHealthy` | heartbeat within configured threshold |
| `schedulerHealthy` | heartbeat/lease loop within configured threshold |

Each job view continues to expose `latestRun`, `latestSuccess`,
`currentRun` and `health`. Run metrics use bounded keys:
`rowsExtracted`, `bytesExtracted`, `rowsStaged`, `rowsPublished`,
`rowsRejected`, `providerLatencyMs`, `retryCount`,
`recoveryCount`, `publishedGeneration` and `sourceDate`.
Error summaries are redacted and capped; payloads and row identifiers are not
returned.

## UI semantics

Admin → Teenused renders three independent badges:

1. runtime: worker/scheduler heartbeat;
2. queue: depth and oldest age;
3. source: latest published generation and freshness SLO.

A provider outage marks only the affected source DEGRADED/STALE. It must not
change API readiness. A failed refresh shows the failure while retaining the
latest successful generation.

## Alerts

| Signal | Warning | Critical |
| --- | --- | --- |
| worker/scheduler heartbeat | > 60 s | > 120 s |
| oldest queued age | > source poll interval × 2 | > freshness SLO |
| failed refresh | one failed run | repeated failure and SLO breach |
| source data age | > 80% SLO | > 100% SLO |

Metric labels are limited to `job_key`, `phase`, `outcome` and
`trigger`. Never label metrics with organization, owner, cadastral, run,
URL, tenant or error-text values.

## Production evidence

Telemetry acceptance remains open until the intended Render workspace is
confirmed and the operator records live deploy IDs, heartbeat ages, queue state
and one complete source-family cycle in `cutover-evidence.md`.
