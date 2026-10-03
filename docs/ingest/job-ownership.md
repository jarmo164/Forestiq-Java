# Ingestion Job Ownership

Status: accepted  
Issue: #393

## Rule

Every production `job_key` in `integration_sync_run` has exactly one execution owner in `integration_job_ownership`.

Workers must satisfy both checks before claiming a row:

1. the job key is enabled for that runtime by deployment configuration;
2. the database ownership row names that runtime as the owner.

This keeps an incorrect environment flag from allowing Java and Python to drain the same work.

## Current Ownership Matrix

| Job key | Owner | Trigger | Source | Max duration | Alert |
| --- | --- | --- | --- | ---: | --- |
| `OWNER_CADASTRE_RELATIONS` | `java-api-worker` | Spring API/scheduler | Metsis owner portfolio | 120 min | `owner-cadastre-relations-stale` |
| `CADASTRE_PUBLIC_DATA` | `java-api-worker` | Spring API/scheduler | Cadastre public data | 240 min | `cadastre-public-data-stale` |
| `FOREST_NOTIFICATIONS` | `java-api-worker` | Spring API/scheduler | Metsaregister notifications | 180 min | `forest-notifications-stale` |
| `FOREST_PLAN` | `java-api-worker` | Spring API/scheduler | Metsaregister forest plan | 180 min | `forest-plan-stale` |
| `METSAREGISTER_WFS_MIRROR` | `java-api-worker` | Spring API/scheduler | Metsaregister WFS mirror | 360 min | `metsaregister-wfs-mirror-stale` |
| `METSAREGISTER_FORESTRY_CANONICAL` | `java-api-worker` | Spring API/scheduler | Canonical forestry projection | 240 min | `metsaregister-forestry-canonical-stale` |
| `WEASEL_OWNERSHIP_TRANSITIONS` | `java-api-worker` | Spring API/scheduler | Weasel transitions | 120 min | `weasel-ownership-transitions-stale` |
| `INHERITANCE_NOTICES` | `java-api-worker` | Spring API/scheduler | Inheritance notices | 120 min | `inheritance-notices-stale` |

The matrix reflects the active production owner. Source-family Python cutovers tracked by #363, #364 and #365 must update this table in the same release that enables the Python worker key.

## Cutover Procedure

1. Add parity evidence for the source family.
2. Change the relevant `integration_job_ownership.worker_type` to `python-ingest-worker`.
3. Add the key to `FORESTIQ_INGEST_ENABLED_JOB_KEYS`.
4. Add the same key to `FORESTIQ_RUNTIME_PYTHON_JOB_KEYS` so Java inline and worker execution exclude it.
5. Run manual and scheduled smoke runs.
6. Leave rollback by moving ownership back to `java-api-worker` and removing the Python routing flags.

## Runtime Guardrails

- Java claim joins `integration_job_ownership` with `worker_type = 'java-api-worker'`.
- Python claim joins `integration_job_ownership` with `worker_type = 'python-ingest-worker'`.
- `integration_sync_run.job_key` has a foreign key to the ownership table.
- Unknown job keys fail closed at insertion time.
- RabbitMQ and Redis are not durable ingestion owners for this contract.
