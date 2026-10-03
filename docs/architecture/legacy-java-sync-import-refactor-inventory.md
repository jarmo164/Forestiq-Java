# Legacy Java Sync/Import Refactor Inventory

This inventory supports #384 and the later #366 cleanup. It records which large Java sync/import services are still allowed during the Python ingestion migration, what responsibility split already exists, and what should happen after parity evidence is available.

## Decision Matrix

| Class | Current responsibility split | Decision | Cleanup link |
| --- | --- | --- | --- |
| `PublicDatasetBootstrapService` | Provider WFS fetch is isolated in `fetchPage(...)`; snapshot run state, page persistence, publish, projection and audit are separate methods. | Remove after parity for public WFS snapshot ingestion. Do not add new source behavior here; new batch ETL belongs in `ingest/`. | #366 after Metsaregister/geospatial parity evidence. |
| `MetsisPortfolioSyncService` | Provider HTTP is delegated to `MetsisPortfolioClient`; the service owns staging, normalization, canonical apply, metrics and checkpoint persistence. | Move to Python after owner portfolio shadow parity. If touched before cutover, only split DB apply helpers; do not add provider execution. | #365, #372, then #366. |
| `CadastreGenerationImportService` | No provider HTTP fetch; it imports a prepared local snapshot and owns validation, canonical generation insert, compare counters and publish metadata. | Keep temporarily as Java geospatial import orchestration until the Python geospatial snapshot pipeline has live parity. Then retire or reduce to read-side/admin status only. | #364, then #366. |
| `MetsaregisterWfsMirrorService` | WFS HTTP is delegated to `MetsaregisterWfsClient`; generation lifecycle, feature staging and publish are split across methods. | Remove after Metsaregister Python WFS mirror cutover. New mirror/stage behavior should be added to `ingest/src/forestiq_ingest/sources/metsaregister_mirror.py`. | #363, then #366. |

## Current Boundary Checks

The Java backend has an `ExternalSystemBoundary` marker and ArchUnit guardrail. Any class that talks to external HTTP systems must explicitly declare whether it is:

- `LEGACY_JAVA_INGESTION`
- `REAL_TIME_DOMAIN_OPERATION`
- `PLATFORM_ADMIN_CLIENT`

## Refactor Rule During Migration

Before source parity evidence exists:

- keep existing integration job keys unchanged;
- keep API response contracts unchanged;
- do not move provider credentials back into the API runtime;
- do not combine provider HTTP fetch/parsing with canonical publish logic in a new method;
- document every remaining Java ingestion class as a #366 removal candidate.

## Closure Evidence Required Before Removal

A legacy Java ingestion class can be removed only after the linked source issue has fixture/shadow parity, retry/outage evidence, Python routing proof, rollback notes and the required successful post-cutover runs.

## Machine-readable cleanup plan

`config/legacy-ingestion-cleanup-plan.json` mirrors this inventory for #366 and maps each source family to its sanitized evidence file, Java removal candidates and intentionally retained exceptions.

`scripts/validate_legacy_ingestion_cleanup.py` is the mandatory preflight before a source-specific deletion PR. Missing or pending parity fails closed.
