# Ingestion cutover evidence

Copy the section below once per source family. Keep incomplete gates unchecked.
This file is a template: do not replace missing production measurements with
examples or estimates.

## Release identity

- Source family:
- Job key(s):
- Evidence owner:
- UTC verification window:
- Git commit:
- Render worker deploy ID:
- Render scheduler deploy ID:
- Database migration version:
- Queue contract version:
- Adapter version:
- Schedule policy version:

## Pre-cutover gates

- [ ] Deterministic adapter fixtures pass, including pagination, schema drift,
      duplicate/malformed rows and retryable/terminal errors.
- [ ] Real Postgres/PostGIS stage → validate → publish tests pass.
- [ ] Failed validation/publish preserves the current generation.
- [ ] Two-worker claim race, idempotent retry and stale lease recovery pass.
- [ ] Worker `SIGTERM` recovery is demonstrated.
- [ ] Provider outage and database restart recovery are demonstrated.
- [ ] Production-shaped import shows bounded memory.
- [ ] Throughput, peak memory, database locks/waits/WAL and interactive API
      latency are recorded below.
- [ ] API/frontend contract remains compatible.
- [ ] Provider secret names are absent from API and scheduler configuration.
- [ ] Ingestion database role/grants are verified without recording secrets.

## Parity

| Check | Legacy baseline | Python shadow | Result/evidence link |
| --- | --- | --- | --- |
| Source record count |  |  |  |
| Normalized/staged count |  |  |  |
| Published count |  |  |  |
| Rejected count/reasons |  |  |  |
| Snapshot/checksum |  |  |  |
| Selected stable IDs |  |  |  |
| Domain projections |  |  |  |
| Current generation |  |  |  |

Explain every accepted mismatch and link its approved rule:

## Performance

| Measurement | Baseline | During ingest | Post-publish | Gate |
| --- | --- | --- | --- | --- |
| Throughput (rows/s and bytes/s) |  |  |  |  |
| Worker peak RSS |  |  |  | bounded/no full materialization |
| Temporary storage peak |  |  |  | below configured limit |
| PostgreSQL locks/waits |  |  |  | no unsafe blocking |
| WAL growth |  |  |  | capacity reviewed |
| API p95 latency/error rate |  |  |  | approved interactive degradation |
| ANALYZE/index readiness |  |  |  | ready before route switch |

## Controlled cutover

- [ ] Shadow run parity accepted.
- [ ] One manual Python run succeeded.
- [ ] First scheduled Python run succeeded.
- [ ] Second scheduled Python run succeeded.
- [ ] Worker heartbeat, scheduler heartbeat, queue lag and freshness are visible.
- [ ] Last-known-good generation is visible.
- [ ] Java schedule owner is disabled before Python schedule owner is enabled.
- [ ] No execution window has two schedule owners.
- [ ] Provider credentials were removed from the API after route switch.
- [ ] Production verification artifact:
- [ ] Operator approval:

Manual run ID:

Scheduled run IDs:

Published generation/checksum:

## Failure and recovery

| Scenario | Injection/observation | Expected | Actual evidence |
| --- | --- | --- | --- |
| Worker crash/SIGTERM |  | claim is recoverable |  |
| Provider outage |  | source degraded; API ready |  |
| Invalid/schema-drift data |  | publish blocked; previous generation current |  |
| Database restart |  | retry/lease recovery; no duplicate publish |  |
| Lost scheduler lease |  | one owner; state visible |  |
| Missed schedule/catch-up |  | bounded enqueue/no burst |  |

## Rollback drill

- Previous route owner:
- Route toggle/policy change:
- UTC disabled:
- In-flight job disposition:
- Queue state before rollback:
- UTC previous owner restored:
- First successful run after rollback:
- Data generation after rollback:
- [ ] No duplicate scheduled command was created.
- [ ] No cursor/checkpoint was manually reset.
- [ ] Current last-known-good data remained readable.

## Final decision

- [ ] GO
- [ ] NO-GO
- Decision maker:
- UTC decision time:
- Open defects/risks:
