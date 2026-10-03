# Source parity and cutover evidence

This runbook is the mandatory gate for #363, #364, #365, #366 and #372. It records evidence; it does not grant permission to delete Java fallback code.

## Execution modes

- `JAVA`: Java may extract and publish; Python must not publish.
- `SHADOW`: Java remains the only publisher. Python may extract, stage, validate and compare, but may not advance a generation pointer or watermark.
- `PYTHON`: Python is the only provider executor and canonical publisher. Java remains enqueue/control-plane and a disabled rollback handler until cleanup is separately approved.

Dual canonical writes are forbidden. Shadow tables/run artifacts must be isolated from current canonical generation pointers.

## Evidence document

Each attempted transition stores one JSON document conforming to `fixtures/parity-evidence.schema.json`. Evidence is immutable for a completed run and contains:

1. repository SHA and adapter/toolkit version;
2. environment and source/job key;
3. redacted source version/checksum;
4. Java and Python run IDs;
5. counts, stable-ID set/checksum, duplicates and malformed rows;
6. source-specific geometry or business-case comparisons;
7. watermark before/after and proof that failed/shadow runs did not advance it;
8. current-generation before/after and last-known-good verification;
9. manual, scheduled, recovery and rollback observations;
10. explicit approver and timestamp.

## Required transition checks

| Transition | Required evidence |
| --- | --- |
| `NOT_STARTED` → `SHADOW` | Adapter fixture tests; retry/terminal failure classification; stage isolation; routing proves Java is the only publisher |
| `SHADOW` → `CUTOVER_READY` | Full representative parity within approved tolerances; no silent truncation; schema/CRS checks; failed publish leaves last-known-good current |
| `CUTOVER_READY` → `CUT_OVER` | One successful manual Python run, at least two successful scheduled Python runs, provider-outage recovery, malformed-payload failure, and a routing rollback rehearsal |
| `CUT_OVER` → Java cleanup | #370 checklist attached; secrets confirmed absent from API deployment for this source; no Java scheduler/provider call; observation window accepted |

## Source-specific gates

### Metsaregister

- Compare each approved local-domain layer separately.
- Require equal stable-ID sets unless an explicit provider-time skew window is documented.
- Compare `numberMatched`, received rows, duplicate IDs, schema fingerprint, geometry type, EPSG:3301 bounds and selected notification/compartment projections.
- A page ceiling, schema drift, invalid geometry or incomplete layer is fail-closed and cannot publish.

### Cadastre / ETAK / administrative snapshot

- Compare immutable artifact checksum/version before import.
- Verify cadastral-code uniqueness, Estonia envelope, CRS=EPSG:3301 and geometry validity.
- Measure peak memory and prove chunk/COPY/set-based load behavior for the production-shaped snapshot.
- An unchanged checksum must produce a no-op with the same current generation.

### Owner portfolio / Weasel / inheritance

- Exercise the synthetic cases in `owner-flows.synthetic.json`: multi-owner, manual conflict, relation end/reactivate, duplicated transition and duplicated/late notice.
- Compare stable source IDs and active/end-state sets, not only aggregate counts.
- Retry/catch-up must not duplicate an event, notice or relationship.
- Failure before publish leaves the cursor/date checkpoint unchanged.

## Recovery and rollback

1. Stop claiming new runs for the job key.
2. Let the active lease finish or mark it recoverable through the shared queue contract; never run both provider executors.
3. Verify the previously published generation/read model is still readable.
4. Change the single-executor route from `PYTHON` back to `JAVA`.
5. Enqueue one bounded manual Java run and compare its result to the last Python evidence.
6. Record the rollback run IDs and reason; do not delete Python staging until the incident is closed.

## Cleanup hold

All baseline rows are `NOT_STARTED` in this commit. Therefore #366 must not remove Java execution paths, source secrets or GDAL/runtime dependencies yet.
