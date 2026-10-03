# Cadastral generation cutover and rollback

Status: target production contract for issues #242–#245. Epic #225 is authoritative when this document conflicts with legacy registry behavior.

## Authoritative paths after cutover

| Concern | Authoritative path | Legacy path disposition |
| --- | --- | --- |
| National cadastral ingestion | Maa- ja Ruumiamet nightly GeoPackage -> staging -> validation -> published `MARU_CADASTRE` generation | Nationwide/page-by-page Maa-amet WFS ingestion is not scheduled and must not be reintroduced |
| Map overview/detail geometry | `geospatial_source_current` -> `geospatial_cadastre_parcel` -> generation-backed MVT | `land_parcel_render.boundary_*` is migration/rollback-era data only |
| Selected parcel geometry/bounds | published generation joined to tenant `land_parcel` by cadastral code | `land_parcel.boundary` is not map geometry authority |
| Exact cadastral-code map search | local code selection -> generation-backed `/api/v1/map/land-parcels/{code}/summary` | provider WFS is not in the request path |
| Owner/status/commercial state | tenant domain tables (`land_parcel`, owner relations/render-owner projection) | never copied into public generation geometry |
| Targeted Maa-amet WFS | one-cadastral-code compatibility/verification refresh only | must never write `geospatial_generation`, `geospatial_source_current` or `geospatial_cadastre_parcel` |
| Metsaregister WFS | forestry notifications/plan/mirror only | explicitly out of scope of cadastral cutover; remains enabled |

## Publication contract

1. Download the official national GeoPackage as a streamed artifact.
2. Record provider/source metadata and SHA-256 checksum.
3. Import into a new `STAGING` generation in EPSG:3301.
4. Validate schema, cadastral identities, Estonia envelope, geometry validity, counters and indexes.
5. Reconcile stable ForestIQ parcel identities by cadastral code without recreating ownership relations.
6. Atomically publish the validated generation and move `geospatial_source_current`.
7. Only after publication can map/detail readers observe the generation.
8. A failed or partial import leaves the previous published generation current.

`geospatial_cadastre_parcel` has database guards which allow canonical geometry mutation only while its generation is `STAGING`. A targeted WFS refresh therefore cannot overwrite a published national generation.

## Scheduling

`CADASTRE_PUBLIC_DATA` represents one national job, not one job per tenant. The existing integration-run model is organization-scoped, so the scheduler uses exactly one deterministic coordinator organization to enqueue the national job. The handler itself imports `scope=ALL_ESTONIA`. If no organization exists, no scheduled run is created; after the first organization exists the next scheduler cycle can run normally.

Metsaregister WFS schedules are independent and must not be disabled during this cutover.

## Map cache and browser refresh

- Backend tile cache identity contains organization, visibility scope, z/x/y, server-resolved published generation UUID and representation version.
- Client supplied version/query values are never generation selectors.
- `/api/v1/map/generation` returns server-owned generation metadata with `no-store` semantics.
- MapLibre uses that metadata only to force source refresh after publication.
- Selected cadastral code is application state outside the vector source, so it survives source/basemap refresh.

## Rollback

Rollback is a pointer operation, not a provider re-download.

1. Identify the last known-good `RETIRED` `MARU_CADASTRE` generation and its source date/checksum.
2. Use the generation control-plane rollback operation to promote that generation.
3. Confirm `geospatial_source_current.generation_id` points to the expected generation.
4. Query `/api/v1/map/generation`; the UUID must match the rollback target.
5. Fetch representative overview/detail tiles and exact cadastral summaries from at least two dense and two rural areas.
6. Verify owner relations and stable `land_parcel.id` values were preserved by reconciliation.
7. Do not run targeted WFS refresh as a substitute for rollback.

The next normal successful snapshot publication can move forward from the rolled-back generation. Cache keys change automatically with the current generation UUID, so no fleet-wide cache purge is required for correctness.

## Verification checklist

Before removing the cutover flag/declaring #242 complete:

- published generation exists and row count is plausible for Estonia;
- map overview z6–10 is aggregate, not misleading full parcel boundaries;
- detail z11+ comes from the published generation;
- exact cadastral-code search returns geometry from the same generation;
- no national WFS job is registered/scheduled;
- targeted WFS writes no generation tables;
- browser cannot select an arbitrary generation with a query parameter;
- generation switch causes a fresh tile cache key;
- failed import retains last-known-good current generation;
- rollback changes map/detail reads without reconstructing owner relations.

## Legacy cleanup boundary

Do not drop `land_parcel_render`, `land_parcel.boundary`, Maa-amet WFS adapter code or old registry audit rows in the same deployment as the read cutover. Keep them for one observed production rollback window. After that window, remove only after confirming no production read path references them and preserving any audit/history data required by business workflows.
