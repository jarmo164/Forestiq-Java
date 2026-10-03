# Ingestion Job Run Contract

Status: accepted

## Purpose

Spring API and the Python ingestion runtime share `integration_sync_run` as the durable PostgreSQL queue. Spring enqueues jobs. Workers claim, heartbeat, transition phases and complete the same row. Python workers do not call Java REST endpoints to finish work.

No new broker is introduced for this contract.

## States

| State | Owner | Meaning |
| --- | --- | --- |
| `QUEUED` | Spring/API or scheduler | Job is durable and waiting for a worker. |
| `RUNNING` | Worker | Worker has claimed the row and started extraction/staging. |
| `VALIDATING` | Worker | Staged payload is being checked before publication. |
| `PUBLISHING` | Worker | Validated data is being merged into canonical/read-model tables. |
| `SUCCEEDED` | Worker | Run completed successfully. |
| `PARTIAL` | Worker | Run completed but intentionally left backlog/catch-up work. |
| `FAILED` | Worker or recovery | Run failed with bounded `error_summary`. |
| `CANCELLED` | API/operator | Run was stopped before completion. |
| `SKIPPED` | Worker | Claim was superseded by another active guard. |

## Fields

| Field | Rule |
| --- | --- |
| `id` | Run/job identifier. |
| `organization_id` | Required for current tenant-scoped jobs; future global jobs may add a nullable companion contract. |
| `job_key` | Stable enum/string routing key. |
| `contract_version` | Bounded shared contract version. Current version is `1`. |
| `parameters` | Bounded JSON object. It is declarative configuration, never executable payload. |
| `queued_at`, `started_at`, `finished_at` | Lifecycle timestamps. |
| `heartbeat_at`, `worker_id`, `lease_expires_at` | Lease ownership and crash recovery. |
| `rows_read`, `rows_staged`, `rows_written`, `rows_rejected` | Standard counters surfaced to Admin API through run history and metrics. |
| `bytes_read`, `bytes_staged`, `bytes_written` | Optional volume counters. |
| `source_version`, `source_date`, `source_checksum` | Provider snapshot identity when available. |
| `published_generation_id` | Published generation for generation-based sources. |
| `metrics` | Backward-compatible JSON metrics for source-specific counters. |
| `error_summary` | Sanitized and bounded failure text. |

## Claim Semantics

Workers claim one row with:

`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`

inside an `UPDATE ... FROM next_run RETURNING ...` statement. The update moves the row to `RUNNING`, sets `worker_id`, `heartbeat_at`, `started_at`, `lease_expires_at` and increments `attempts`. Multiple worker replicas can run the same claim statement safely; only one receives a given run.

## Recovery

A worker must heartbeat before `lease_expires_at`. If the worker crashes, another worker may reclaim an active row after the lease expires. Publication must remain idempotent: source adapters use run/generation identifiers and existing unique keys so retrying a recovered run cannot duplicate canonical data.

## Completion

Workers complete by updating the run row to `SUCCEEDED`, `PARTIAL`, `FAILED` or `CANCELLED`, setting counters, bounded error details and `finished_at`. Java Admin API reads the same row history; no callback endpoint is required.


## Source Cutover Routing

Spring reads `FORESTIQ_RUNTIME_PYTHON_JOB_KEYS` / `forestiq.runtime.python-job-keys` as a comma-separated list. When a job key is listed:

- manual and scheduled requests still create a durable `integration_sync_run` row;
- API/scheduler runtimes do not invoke the Java `IntegrationJobHandler`;
- Java worker claim filters exclude that key;
- Python workers may claim it through the shared PostgreSQL contract.

Rollback is operationally simple: remove the key from the routing flag and restart the Spring worker so the legacy Java handler can claim subsequent queued rows.
