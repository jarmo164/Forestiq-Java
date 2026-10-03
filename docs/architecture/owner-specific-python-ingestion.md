# Owner-Specific Python Ingestion Migration

Status: implementation-ready, pending parity evidence

## Implemented Foundation

Python adapters now exist for owner-specific flows:

| Job key | Adapter | Source mode | Watermark rule |
| --- | --- | --- | --- |
| `OWNER_CADASTRE_RELATIONS` | `MetsisOwnerPortfolioAdapter` | Snapshot | Checkpoint only after full snapshot publish |
| `WEASEL_OWNERSHIP_TRANSITIONS` | `WeaselOwnershipAdapter` | Incremental | Read from last watermark minus overlap; advance after publish |
| `INHERITANCE_NOTICES` | `InheritanceNoticeAdapter` | Incremental/date-window | Re-read overlap days; advance last completed day after publish |

## Domain Rules Preserved

- Multi-owner parcel relations keep owner identity in the source relation key.
- Provider updates must not silently overwrite `manual_override` relations.
- Incremental retry/catch-up reuses deterministic source IDs, so duplicate events can be ignored/upserted.
- Failed runs do not call checkpoint/watermark advancement helpers.

## Cutover Evidence Needed

This issue remains open until shadow parity proves:

- counts and stable source IDs match Java/current output;
- multi-owner fixtures remain multi-owner;
- relation end/reactivate cases match current canonical behavior;
- manual-vs-provider conflict cases are preserved;
- provider outage/malformed payload/retry tests pass;
- Java handlers can be excluded via `INGEST_PYTHON_JOB_KEYS`.


## Combined cutover evidence gate

The three owner-specific source families are evaluated together before Java execution
can be disabled:

- `OWNER_CADASTRE_RELATIONS`
- `WEASEL_OWNERSHIP_TRANSITIONS`
- `INHERITANCE_NOTICES`

`OwnerSourceCutoverEvidence` requires all three sources to be present and to match
current counts, stable IDs and selected business-case fixtures. In addition, the
cutover must prove that:

- multi-owner relationships remain distinct;
- relation end/reactivate behavior is preserved;
- manual overrides are never silently overwritten by provider changes;
- retry/catch-up is idempotent;
- a failed run does not advance the watermark/cursor;
- at least two successful Python-owned runs are observed per source family.

`require_cutover_ready(...)` fails closed. Sanitized evidence can be written with
`write_evidence(...)`; raw personal data, provider payloads and credentials are not
part of the report.


## Evidence CLI

Build the combined #361 bundle entry for the three owner-specific source families:

~~~bash
python scripts/build_owner_flows_cutover_evidence.py owner-flows-input.json \
  --repository-sha "$(git rev-parse HEAD)" \
  --output owner-flows.json \
  --require-ready
~~~

All three job families must be present. For each one the current/Python counts, stable IDs and selected business cases must match and at least two Python-owned runs must have succeeded. The combined domain guard additionally requires preserved multi-owner, end/reactivate, manual-override, retry-idempotency and failed-run watermark semantics.

The generated `owner-flows.json` is directly consumable by the #361 source cutover bundle validator.
