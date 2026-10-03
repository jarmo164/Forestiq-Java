# Maa- ja Ruumiamet basemap, search and parcel-generation boundary

## Scope

This decision completes ForestIQ map work for issues #237, #238, #245, #246 and #247. It follows the target architecture in #225: raster basemaps are presentation sources, cadastral geometry is canonical ForestIQ data, and search/geocoding is a separate responsibility.

## Basemap delivery

Browser clients never receive the Maa- ja Ruumiamet tile-service base URL. They first read the safe ForestIQ catalog:

- `GET /api/v1/map/basemaps`

and load approved tiles through:

- `GET /api/v1/map/basemaps/{key}/{z}/{x}/{y}`

Enabled Web Mercator layers are `kaart`, `ortofoto` and `hybriid`. `reljeef` remains disabled by default until an approved Web Mercator provider contract is configured.

The upstream service is the official pre-tiled TMS service. MapLibre uses normal XYZ coordinates with a top-left Y origin. The proxy converts the requested Y to TMS exactly once:

`providerY = (2^z - 1) - browserY`

The proxy applies strict coordinate validation, bounded concurrency, request/connect timeouts, retry only for transient failures, response content-type/size validation and a weighted six-hour in-process cache. Provider error pages are never cached as tiles. Metrics are low-cardinality by approved layer and outcome.

Basemap source URLs and environment-specific provider configuration stay server-side. User-facing attribution is `Maa- ja Ruumiamet`; an optional configured source date is exposed in the safe descriptor.

## Basemap UX

`MaaruumBasemapControl` adds/removes no business data and never calls `map.setStyle()`. It creates raster sources beneath ForestIQ parcel/forestry vector layers and changes only raster-layer visibility. Therefore:

- parcel selection survives basemap changes;
- forestry overlays survive basemap changes;
- the chosen basemap is persisted in local storage;
- a provider failure is reported as a non-blocking background-map warning and does not imply that ForestIQ canonical data is unavailable.

The map starts with a neutral local background rather than a direct third-party tile URL. This prevents accidental bypass of the ForestIQ map boundary.

## Parcel generation contract

The server is authoritative for the currently published cadastral generation. `/api/v1/map/generation` returns the current generation UUID and representation version. The browser polls it and changes both overview/detail tile URLs when it changes.

Tile URLs carry a key in the form:

`{publishedGenerationUuid}:r{representationVersion}`

The backend validates that key against the current published generation before generating a tile. A stale key returns HTTP 409 and cannot be served with newer-generation content under the old URL. Cache identity remains scoped by organisation, assignment visibility, generation and representation version.

Low zoom is explicitly an overview/aggregated representation. Clicking an overview feature zooms into the accurate working band; detailed parcel geometry starts at zoom 11. Full business detail is still loaded from the parcel/domain API after selection, not from MVT attributes.

## Search boundary

ForestIQ exposes one normalized search endpoint:

- `GET /api/v1/map/search?q=...&limit=...`

Queries are length- and result-bounded.

### Exact cadastral code

For an exact `12345:678:9012` query the service checks the locally published, organization/assignment-authorized cadastral read model first. A local result is deterministic, contains canonical parcel focus bounds/centroid, and does not depend on In-AKS availability.

### Address search

Normal address queries use the Maa- ja Ruumiamet In-AKS gazetteer through the backend. Provider-specific fields are normalized into the ForestIQ DTO:

- result type;
- display label;
- longitude/latitude;
- optional cadastral code;
- provider identity;
- local/external indicator.

The frontend never depends on the provider response schema. The adapter applies a 10-second request timeout, bounded retries, a 512 KiB response cap, result deduplication and a short bounded cache.

Selecting an external address result only focuses the map; it does not create a parcel or clear the current CRM work. Only a result resolved as local canonical cadastral data opens parcel detail.

## Failure semantics

Basemap and address-search failures are independent from canonical ForestIQ parcel health:

- basemap provider unavailable → background warning, vector/domain work remains available;
- In-AKS unavailable → address-search warning, current map selection/work remains unchanged;
- exact local cadastral search → remains available without In-AKS;
- stale parcel generation tile URL → HTTP 409, generation polling supplies a new URL;
- no published cadastral generation → existing map generation endpoint reports unavailable and parcel tile/detail reads fail closed.

## Configuration

Environment variables:

- `MAARUUM_BASEMAP_TMS_BASE_URL`
- `MAARUUM_INAKS_URL`
- `MAARUUM_MAP_INSTITUTION`
- `MAARUUM_MAP_SYSTEM`
- `MAARUUM_MAP_ENVIRONMENT`
- `MAARUUM_MAP_SOURCE_DATE`
- `MAARUUM_ORTOFOTO_SOURCE_DATE`
- `MAARUUM_HYBRID_SOURCE_DATE`
- `MAARUUM_RELIEF_SOURCE_DATE`
- `MAARUUM_RELIEF_WEB_MERCATOR_ENABLED`

Provider base URLs must not be duplicated into frontend environment variables.
