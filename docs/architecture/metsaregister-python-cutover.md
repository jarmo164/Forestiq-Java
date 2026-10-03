# Metsaregister Python Ingestion Cutover

Status: implementation-ready, pending shadow evidence

## What Is Implemented

The Python runtime now has a Metsaregister WFS mirror path:

- shared HTTP/WFS toolkit for retry, rate-limit, paging and response bounds;
- `MetsaregisterWfsAdapter` for provider-specific WFS access;
- `MetsaregisterWfsMirrorPipeline` for generation-scoped staging and publish using temp-table COPY plus set-based insert;
- shadow comparison helpers for row counts, stable IDs, bounds and source metadata.

## Generation Safety

A new `metsaregister_wfs_generation` is staged first. Failed staging marks only that generation as `FAILED`; it does not update `metsaregister_wfs_layer.current_generation_id`. Publish switches the current pointer only after staging succeeds.

## Cutover Steps

1. Run Python in shadow mode for approved local-domain Metsaregister layers.
2. Compare against current Java generation:
   - row counts;
   - stable IDs;
   - geometry bounds;
   - notification/sub-part samples;
   - source version/checksum metadata.
3. Set `INGEST_PYTHON_JOB_KEYS=METSAREGISTER_WFS_MIRROR`.
4. Observe at least two successful manual/scheduled runs.
5. Only then disable/remove legacy Java execution for this source.

## Not Yet Proven

This issue must remain open until the shadow comparison report and two successful runs are attached. The code path is ready for that evidence, but the live provider parity step has not been executed in this workspace.


## Fail-closed cutover evidence gate

The code-ready adapter is not sufficient to enable Python ownership. Before routing
`METSAREGISTER_WFS_MIRROR` to Python, collect the live shadow comparison into
`MetsaregisterCutoverEvidence` and call `require_cutover_ready(...)`.

The gate requires all of the following at the same time:

- every approved layer has exact row-count parity;
- stable IDs match the Java/current generation;
- geometry bounds and source metadata match;
- forest-notification and forest-plan sample comparisons match;
- at least two successful Python-owned manual/scheduled runs have been observed.

Any missing evidence fails closed. The evidence object can be serialized with
`write_evidence(...)` and attached to the migration issue/PR without including
provider credentials or raw private payloads.

This guardrail intentionally does not manufacture live parity. Routing must remain
on the current owner until the measured report passes.


## Evidence CLI

Create the sanitized #361 bundle entry for #363 with:

~~~bash
python scripts/build_metsaregister_cutover_evidence.py shadow-input.json \
  --repository-sha "$(git rev-parse HEAD)" \
  --output metsaregister.json \
  --require-ready
~~~

The input contains aggregate layer counts, matching stable-ID counts, boolean bounds/source-metadata parity, sample parity, rollback verification and the observed successful Python-run count. It must not contain raw provider payloads or credentials.

The command exits non-zero when any layer differs, fewer than two Python-owned runs exist, sample parity is missing, or rollback/recovery has not been verified. The generated `metsaregister.json` is directly consumable by the #361 source cutover bundle validator.
