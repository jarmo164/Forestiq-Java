# Legacy Java Ingestion Cleanup Guardrail

Status: cleanup blocked until parity evidence is complete

## Rule

Do not remove a Java provider client, handler, scheduler branch or API credential until the matching source row below is in `CUT_OVER`, `JAVA_EXCEPTION` or `RETIRED` state and has linked evidence.

## Service Ownership Matrix

| Source family | Java execution status | Python status | Cleanup state | Evidence required |
| --- | --- | --- | --- | --- |
| Metsaregister WFS mirror | Legacy retained | Python code-ready | `BLOCKED_PARITY` | #363 shadow report and two successful Python-owned runs |
| Cadastre public data | Legacy retained | Python foundation-ready | `BLOCKED_PARITY` | #364 official snapshot parity and generation safety proof |
| ETAK/admin geospatial | Legacy retained | Python foundation-ready | `BLOCKED_PARITY` | #364 approved dataset parity |
| MetsIS owner portfolio | Legacy retained | Python adapter-ready | `BLOCKED_PARITY` | #365/#372 owner relation parity |
| Weasel ownership transitions | Legacy retained | Python adapter-ready | `BLOCKED_PARITY` | #365 incremental retry/cursor parity |
| Inheritance notices | Legacy retained | Python adapter-ready | `BLOCKED_PARITY` | #365 date-window catch-up parity |
| RIK objektide_arv SOAP | Java retained | Not Python target | `JAVA_EXCEPTION` | #372 real-time exception documented |

## Cleanup Checklist Per Source

A source may be cleaned from Spring only when all are true:

- migration matrix status is `CUT_OVER`, `JAVA_EXCEPTION` or `RETIRED`;
- fixture/shadow parity is attached;
- at least one manual and one scheduled Python-owned run succeeded where applicable;
- provider outage/malformed payload/retry tests passed;
- rollback decision is documented;
- API/Admin DTO contract did not change;
- provider credentials are absent from API runtime and present only in ingestion runtime;
- scheduler/enqueue routing sends migrated keys only to Python.

## What Can Be Removed Later

After the checklist passes source-by-source:

- Java provider HTTP/WFS/snapshot clients for migrated sources;
- Java `IntegrationJobHandler.run(...)` provider execution branches for migrated keys;
- source-specific API container secrets;
- ingestion-only geospatial runtime dependencies from the API image;
- obsolete scheduler branches that execute provider calls instead of enqueueing jobs.

## What Must Remain

- API/Admin run history and enqueue endpoints;
- Java read repositories and DTOs for published canonical/read-model data;
- documented real-time RIK exception until reclassified.

## Deployment boundary guard

While source-by-source Java deletion is still blocked on parity, the production deployment boundary is already enforceable.

`scripts/validate_api_ingest_boundary.py` fails when:

- the Render `forestiq-api` service receives known ingest-only provider keys for MetsIS, Weasel, inheritance, Metsaregister or snapshot ingestion;
- the API service stops declaring its explicit runtime role;
- the API image starts installing GDAL/OGR ingestion tooling.

The guard is part of the reusable `Ingestion Contract` workflow.

## Cleanup preflight CLI

Before deleting any legacy Java source implementation, validate the sanitized live cutover evidence:

~~~bash
python scripts/validate_legacy_ingestion_cleanup.py \
  --evidence-dir /path/to/sanitized-cutover-evidence \
  --require-ready
~~~

Or check one family:

~~~bash
python scripts/validate_legacy_ingestion_cleanup.py \
  --evidence-dir /path/to/sanitized-cutover-evidence \
  --source metsaregister \
  --require-ready
~~~

The source-to-Java-candidate mapping is versioned in `config/legacy-ingestion-cleanup-plan.json`. A source is eligible only when its #361 evidence entry is READY, parity is explicit, at least two Python-owned runs succeeded, and rollback/recovery is verified.

A documented Java real-time exception is reported as retained rather than silently deleted. The preflight is intentionally non-destructive: it tells a cleanup PR which source families are safe to remove after live evidence exists.
