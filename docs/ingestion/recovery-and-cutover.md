# Ingestion recovery and source-by-source cutover

## Recovery invariants

- Queue claims and scheduler ownership are PostgreSQL leases, never process-only
  flags.
- SIGTERM stops new claims, renews/checkpoints the active unit when possible and
  exits within 300 seconds.
- An expired lease is reclaimable; a retry reuses the command idempotency key.
- Cursors advance only after atomic publish.
- Validation/provider/publish failure never replaces the current
  last-known-good generation.
- Operators do not repair runs by deleting queue rows or editing generation
  pointers.

## Failure drills

| Drill | Pass condition |
| --- | --- |
| SIGTERM during extract | lease expires/requeues; retry does not duplicate canonical rows |
| provider outage | source becomes degraded; API readiness stays UP |
| malformed/schema-drift payload | validation blocks publish; prior generation remains current |
| database restart | clients reconnect; stale claim is recovered; scheduler does not duplicate the window |
| two schedulers | one PostgreSQL lease owner enqueues a job key/window |
| missed schedule | catch-up respects per-source window/count limits |
| publish interruption | transaction rolls back and generation pointer does not move |

## Cutover

For each job key:

1. keep Java as the route owner and run Python in shadow mode;
2. accept parity for counts, checksums, stable IDs and domain projection;
3. complete one manual Python run;
4. disable Java ownership before enabling Python ownership;
5. record two consecutive scheduled Python successes;
6. verify worker/scheduler heartbeats, queue lag, freshness and generation;
7. remove that source's provider credentials from the API;
8. exercise routing rollback without resetting a cursor or generation;
9. attach the completed `cutover-evidence.md` section.

Only one executor and one scheduler owner may be active for a job key/window.
Rollback is a routing change for one source family, not a database restore.

## Render verification boundary

This repository configures the services but does not select a Render workspace
or deploy production. After the workspace is explicitly confirmed, verify live
deploys/logs/metrics and run the manual
`Production Ingestion Verification` workflow. Until that evidence exists,
production telemetry and full-cycle smoke gates remain unchecked.
