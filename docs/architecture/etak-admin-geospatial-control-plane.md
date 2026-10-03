# ETAK and administrative geospatial control plane

Status: target architecture implementation for ForestIQ GIS architecture (Epic #225).

## Purpose

ForestIQ treats Maa- ja Ruumiamet reference layers as versioned external source data, not as mutable domain tables. Every approved dataset has a source contract, a run history and an immutable generation. Readers see only the generation referenced by `geospatial_source_current`.

This implementation covers issues #248, #249, #250, #252 and #253.

## Approved data scope

The checked-in contract is `backend/src/main/resources/etak-admin-layer-manifest.json`. It is validated during application startup so an invalid source key, unsafe identity field, non-HTTPS endpoint, unsupported geometry family, missing business purpose, invalid CRS or missing disabled reason fails fast before ingestion can run.

Enabled ETAK context layers:

| ForestIQ source | Provider layer | Business use | Important caveat |
| --- | --- | --- | --- |
| `MARU_ETAK_WOODY` | `etak:E_305_puittaimestik_a` | topographic woody-vegetation overlap | not Metsaregister inventory, stand or legal forestry data |
| `MARU_ETAK_WETLAND` | `etak:E_306_margala_a` | wetland context | does not define legal protection/drainage status |
| `MARU_ETAK_STANDING_WATER` | `etak:E_202_seisuveekogu_a` | standing-water context | restriction zones come from separate authoritative sources |
| `MARU_ETAK_FLOWING_WATER` | `etak:E_203_vooluveekogu_j` | rivers/ditches context | does not define legal water restrictions |
| `MARU_ETAK_ROAD` | `etak:E_501_tee_j` | access/road proximity context | does not prove public access or easements |
| `MARU_ETAK_ARABLE` | `etak:E_303_haritav_maa_a` | arable-land cover context | does not replace PRIA/cadastral land-use semantics |
| `MARU_ETAK_OPEN` | `etak:E_304_lage_a` | open-land cover context | topographic context only |

Enabled administrative reference layers are county, municipality and settlement boundaries from the current Maa- ja Ruumiamet administrative WFS. Buildings are intentionally present in the manifest but disabled until a concrete ForestIQ business use-case is approved. This prevents “import all ETAK” from becoming an accidental requirement.

All enabled vector layers declare source and canonical CRS `EPSG:3301`. Browser presentation remains Web Mercator through PostGIS MVT generation.

## WFS ingestion contract

`EtakAdminWfsClient` is the only provider-facing WFS boundary. It uses WFS 2.0 `GetFeature` with explicit `count`, `startIndex` and `srsName=EPSG:3301`.

Safety bounds:

- default page size 5,000 features and never above 5,000;
- maximum page response 32 MiB by default;
- connect and request timeouts;
- at most two provider attempts by default (max configurable bound is three);
- 4xx is not retried; transient transport/5xx failure can be retried;
- non-JSON, malformed FeatureCollection, empty/oversized payloads and provider pages larger than requested are rejected;
- provider payload schema never leaks to frontend code.

The 5,000 feature page bound follows the published Maa- ja Ruumiamet ETAK WFS service constraint. National imports therefore page explicitly rather than relying on a single unbounded GetFeature response.

## Generation-safe publication

`EtakAdminGenerationIngestionService` reuses the generic V51 source/run/generation model.

Per source:

1. create a `RUNNING` source run;
2. create an invisible `STAGING` generation;
3. fetch and insert bounded WFS pages into `geospatial_feature` under that generation id;
4. normalize properties to lower-case keys and require the manifest identity field;
5. build a schema fingerprint from geometry family + sorted observed fields;
6. build an order-independent content checksum from feature identity/properties/geometry;
7. validate row-count range, required fields, geometry validity, geometry family and SRID 3301;
8. stop publication when schema drift is detected unless an administrator explicitly forces a reviewed refresh;
9. mark the generation `VALIDATED`;
10. atomically switch `geospatial_source_current` using `GeospatialSourceGenerationRepository.publishValidated`.

A failed or partial run never changes the current pointer. The previous published generation remains available. Publication retires the old generation only inside the same database transaction that publishes the validated target.

Repeated identical source content is detected by checksum and completes as `UNCHANGED` instead of publishing a redundant generation.

## Storage and spatial indexes

Migration V57 adds `geospatial_feature`:

- immutable `generation_id` ownership;
- stable provider `feature_id`;
- normalized source `properties` JSONB;
- canonical `geometry(Geometry, 3301)`;
- `(source_key, generation_id)` index;
- GiST geometry index.

`geospatial_feature_current` is a convenience view that exposes only current published generation rows.

## Parcel facts and provenance

`GET /api/v1/map/land-parcels/{cadastralCode}/context` first passes through the existing organization/assignment authorization boundary and then derives bounded spatial facts from the current published reference generations.

Current facts:

- ETAK woody-vegetation overlap area in hectares and percentage of parcel;
- county;
- municipality;
- settlement.

Every derived fact carries `sourceKey`, exact `generationId`, `sourceDate`, `sourceVersion` and content checksum. The woody-vegetation fact also carries the manifest caveat so it cannot silently be presented as Metsaregister forest inventory/legal status.

Queries use current-generation equality, GiST-compatible bounding-box predicates and `ST_Intersects`/`ST_Covers` rather than loading national feature collections into application memory.

## Optional map overlays

`GET /api/v1/map/context/layers` returns only safe ForestIQ descriptors. Provider URLs are never returned.

`GET /api/v1/map/context/mvt/{sourceKey}/{generationId}/{z}/{x}/{y}.pbf`:

- requires authentication;
- only accepts manifest-approved map-visible sources;
- rejects a stale generation key with HTTP 409;
- enforces per-layer zoom ranges;
- filters with a tile bounding box before geometry clipping;
- caps candidate features at 5,000 and encoded MVT at 1 MiB;
- exposes only lightweight feature identity/type properties;
- uses private immutable generation-keyed cache headers and `Vary: Authorization`.

The MapLibre `EtakAdminOverlayControl` is opt-in. Visibility/opacity are persisted locally, context-layer failure is non-blocking, and the control never calls `setStyle()`, so basemap changes and ForestIQ parcel selection remain stable.

## Scheduling and locking

All manual and scheduled refreshes pass through `GeospatialRefreshCoordinator`.

The coordinator holds a PostgreSQL session advisory lock for the full source refresh, keyed by source. This prevents two API/worker replicas from simultaneously downloading/staging the same source. V51's unique active-run index remains a second database guard.

`GeospatialRefreshScheduler` is disabled by default and can be enabled with:

```text
FORESTIQ_GEOSPATIAL_SCHEDULER_ENABLED=true
```

The scanner checks due sources; heavy work is limited to two worker tasks. The manifest/source refresh intervals are weekly for ETAK and monthly for administrative boundaries. A failed provider refresh is persisted as a failed run but does not participate in Spring readiness, so a transient Maa- ja Ruumiamet outage cannot mark the ForestIQ API itself unready.

## Admin → Teenused

Admin API:

- `GET /api/v1/admin/geospatial/sources`
- `GET /api/v1/admin/geospatial/sources/{sourceKey}`
- `POST /api/v1/admin/geospatial/sources/{sourceKey}/refresh`

Only `ORG_ADMIN` can access these endpoints. Manual sync returns `202 Accepted` and uses the exact same coordinator/lock/run path as scheduling.

The UI shows, separately:

- configured cadence and freshness target;
- current generation, source date/version/checksum, row count and bytes;
- latest run status, counters, duration and bounded error summary;
- last successful refresh age/freshness state;
- recent run history;
- running state and manual-sync availability;
- business purpose and caveat.

There is deliberately no endpoint or button that publishes a partial/staging generation.

## Metrics and alerts

Low-cardinality metrics include source/trigger/outcome only. Feature ids, owner ids and cadastral codes are never metric labels.

Key metrics:

- `forestiq.geospatial.refresh.requests`
- `forestiq.geospatial.refresh.duration`
- `forestiq.geospatial.refresh.lock_skipped`
- `forestiq.geospatial.source.freshness.seconds`
- `forestiq.geospatial.source.stale`

Recommended alerts:

- stale gauge = 1 beyond one evaluation period;
- repeated failed refresh outcomes for one source;
- unusually large refresh duration or bytes/rows change;
- repeated lock skips outside an expected rolling deployment window.

## Rollback and recovery

Rollback remains the generic V51 operation: switch a source back to a retained retired generation atomically. The ETAK/admin importer never repairs a failed current dataset in-place. A corrected source is imported as a new generation, validated and then published.

Before enabling scheduled national ingestion in production, run one manual source at a time from Admin → Teenused and confirm provider schema fingerprints, row ranges, source dates and overlay rendering. Enable the scheduler only after those first publications establish known-good baselines.
