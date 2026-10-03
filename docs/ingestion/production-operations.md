# Python ingestion production operations

This runbook is the operational contract for issues #367, #368, #369 and #374.
It complements the source-by-source migration evidence under
`docs/ingestion/source-migration/`; it does not replace provider-specific
parity proof.

## Runtime topology and ownership

| Service | Responsibility | Provider access | Public HTTP |
| --- | --- | --- | --- |
| `forestiq-api` | Domain API, Admin API, manual enqueue | None after a source is cut over | Yes |
| `forestiq-ingest-scheduler` | Calculate due windows and enqueue durable commands | None | No |
| `forestiq-ingest-worker` | `python -m forestiq_ingest.worker`; claim, extract, validate, stage and atomically publish | Only migrated source credentials | No |
| PostgreSQL/PostGIS | Queue, leases, heartbeats, run history, generations and domain data | n/a | No |

The scheduler is a long-lived Render worker, not a Render Cron Job. Its
PostgreSQL lease, bounded catch-up policy and heartbeat are part of the
application contract. A Cron Job would hide missed-window state between runs
and a manually triggered cron cancels an active run.

Neither Python process receives inbound traffic. Liveness is the process
lifecycle; operational health is persisted in PostgreSQL and exposed through
the Java Admin API. Provider availability never participates in the API
readiness group.

## Credential boundary

Production uses separate database login material for the application and
ingestion runtimes. The exact roles and grants are created by the versioned
database-role migration/ADR from #355.

| Variable family | API | Scheduler | Worker |
| --- | :---: | :---: | :---: |
| API database credential | yes | no | no |
| Ingestion database credential | no | yes | yes |
| Provider URL/token/tenant | no | no | yes |
| Keycloak admin secret | only if required by API | no | no |
| Object-store credential | only for domain documents | no | only when an adapter requires it |

Render values marked `sync: false` are filled in the Dashboard/secret manager.
Do not copy provider secrets into API or scheduler variables "for readiness".
Readiness reports whether the API can serve its own dependencies, not whether
an external provider happens to be reachable.

After each source cutover, inspect all three service configurations and remove
that source's provider values from `forestiq-api`. Record names, never values,
in the cutover evidence.

## Worker lifecycle

The worker must:

1. claim through the durable PostgreSQL contract;
2. renew its worker heartbeat and the active run lease;
3. stop claiming new work when it receives `SIGTERM`;
4. finish or checkpoint the active unit within the shutdown window;
5. release/expire the lease so another worker can recover it;
6. exit non-zero only for process-level failure, not for one terminal provider
   record.

Render sends `SIGTERM` before `SIGKILL`. Configure
`maxShutdownDelaySeconds: 300` for worker and scheduler. Jobs longer than the
window must checkpoint, and stale claims must be reclaimed by lease expiry.
Never rely on a local file or process memory as the only recovery marker.

The ingestion image may contain GDAL/PostGIS client libraries. API and scheduler
images remain lean. Temporary downloads use ephemeral storage, are streamed,
size-bounded, cleaned after a run and safe to lose on restart. No Render
persistent disk is required for queue correctness.

## Scheduler lifecycle

The scheduler may read only versioned schedule/routing policy and PostgreSQL
state. It must not import provider adapters, call provider endpoints or receive
provider credentials.

For each job key and execution window:

- one active schedule owner is selected through a PostgreSQL advisory lock or
  lease;
- enqueue uses a database uniqueness/idempotency key;
- lease loss is logged and reflected in scheduler state;
- missed windows are caught up only within the source policy's maximum window
  and maximum enqueue count;
- one source cannot be simultaneously enabled in Java and Python scheduling;
- manual and scheduled triggers write the same versioned command, with distinct
  trigger metadata.

A restart may delay an enqueue but must not multiply it. Catch-up must not
create an unbounded backlog or burst against a provider.

## Health and freshness semantics

The Admin control plane reports independent state dimensions:

| Dimension | Healthy meaning | Failure meaning |
| --- | --- | --- |
| API readiness | API database/auth dependencies are usable | API cannot safely serve requests |
| Worker heartbeat | a worker heartbeat is within its threshold | missing/dead worker |
| Scheduler heartbeat | scheduler heartbeat and lease loop are current | missing scheduler or lost loop |
| Queue | depth/oldest age remain within policy | stuck or growing backlog |
| Run phase | queue/extract/validate/publish reached a terminal state | bounded phase-specific error |
| Source freshness | last successful published generation is within SLO | degraded/stale source |
| Published data | last-known-good generation remains readable | no valid generation exists |

A provider outage changes the affected source to `DEGRADED` or `STALE`;
it does not fail `/actuator/health/readiness`. A failed validation or publish
must leave the previous generation current and visible in Admin.

Required low-cardinality telemetry includes queue depth, oldest queued age,
running count, heartbeat age, run duration, extracted/staged/published/rejected
row and byte counters, provider latency/error counts, validation/publish
failures, retry/recovery count, last success age and current generation.
Labels may contain bounded values such as job key, phase and outcome. Never use
owner IDs, cadastral identifiers, run IDs, URLs, error text or tenant IDs as
metric labels.

Logs use structured fields `service`, `job_key`, `run_id`, `phase` and
`outcome`. Values that may contain credentials, provider payloads or personal
data are redacted or omitted. Admin error summaries are sanitized and bounded.

## Render deployment

The Blueprint must define both Python processes as `type: worker`,
`runtime: docker`, `plan: starter` (minimum initial baseline),
`autoDeployTrigger: commit` and `maxShutdownDelaySeconds: 300`.

Before applying the Blueprint:

1. validate `render.yaml` with Render CLI/schema;
2. confirm worker and scheduler use the ingestion database credential;
3. confirm only worker contains migrated provider variables;
4. confirm preview generation and preview ingestion remain disabled;
5. run the image/config contract tests;
6. review current worker memory, runtime and temporary storage measurements;
7. increase the plan before cutover if production-shaped evidence exceeds the
   initial plan's safe headroom.

A Blueprint sync does not prove deployment success. After apply, record the
deployed commit, live deploy IDs and the absence of build/runtime errors.

## Incident signals and first response

| Signal | First check | Safe first action |
| --- | --- | --- |
| Missing worker heartbeat | worker deploy/logs and DB connectivity | restore worker; allow lease recovery |
| Missing scheduler heartbeat | scheduler deploy/logs and leader state | restore one scheduler owner |
| Oldest queue age rising | worker heartbeat, claim errors, DB locks | pause affected routing if data safety is uncertain |
| Provider errors | bounded error class and provider status | keep API up; retry per source policy |
| Validation failures | rejected counts/schema drift | keep last-known-good generation |
| Publish failures | transaction/locks/storage and generation pointer | do not delete current generation |
| Freshness SLO exceeded | latest success, queue and schedule ownership | roll back only the affected source route |

Never delete queue rows, reset cursors, edit generation pointers or mark runs
successful to make the dashboard green.

## Production acceptance evidence

Do not close #367/#368/#369/#370/#374 from CI alone. Required evidence is:

- both Python services are live on the intended production commit;
- separate database roles are observed in configuration (names only);
- provider secret names exist only on the worker;
- worker and scheduler heartbeats are current;
- queue/runs/freshness/current generation are visible in Admin;
- provider failure does not fail API readiness;
- worker restart recovers a stale claim;
- each migrated source has manual success, two scheduled successes, failure
  recovery and routing rollback proof;
- a production-shaped run records throughput, peak memory, database
  locks/waits/WAL and interactive API latency;
- the read-only production verification workflow produces a PASS artifact.

Use `docs/ingestion/cutover-evidence.md` for the auditable source-by-source
record. Redact secrets and personal/source row identifiers.


## Repository verification

Run the secret-free contract tests before requesting a Blueprint sync:

```bash
python -m unittest \
  scripts/test_ingestion_deployment_contract.py \
  scripts/test_verify_production_ingestion.py
docker compose config --quiet
docker compose -f compose.yaml -f compose.prod.yaml config --quiet
```

The Python image/CLI is supplied by the ingestion-core change. This ops branch
must be merged only with that dependency present.
