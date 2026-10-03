# Parcel detail read model

Status: accepted for feature #328  
Architecture parent: #225  
Technical issue: #337

## Goal

The parcel detail workspace is the user-facing aggregation surface for one cadastral parcel. It must present a coherent overview without creating a second canonical parcel/owner/forestry model or one giant blocking endpoint.

## Read-path boundary

### Fast overview

`GET /api/v1/map/land-parcels/{cadastralCode}/summary` is a bounded overview projection. It may contain:

- parcel identity and cadastral attributes;
- published cadastre generation/date;
- location and land-area fields already stored in the parcel projection;
- all currently visible canonical owner relations as a small relation summary;
- fixed-size counts for sub-parts, notifications and forest-registry features;
- ForestIQ workflow summary already authorized by the map read boundary.

The overview must **not** contain bulk notification history, full sub-part lists, raw upstream payloads or polygon collections.

### Bounded detail sections

Large or optional sections are loaded independently after the user opens them:

- notifications: keyset pages;
- forest-registry features: keyset pages, raw attributes/geometry by explicit feature request;
- sub-parts: keyset pages, geometry by internal id or external sub-part code;
- geospatial context facts: bounded server-side parcel intersections against the current published source generations.

One failed section must not invalidate already loaded overview or other sections.

## Canonical ownership

`owner_parcel_relation` active rows remain the canonical current ownership model. `land_parcel.owner_id` is a compatibility projection only and must not be used to collapse a multi-owner parcel to one canonical owner.

The nested owner summary intentionally avoids expanding unrestricted co-owner contact PII. Contact/workflow details remain subject to existing backend authorization boundaries.

## Spatial and generation consistency

- Cadastre geometry comes only from the currently published `MARU_CADASTRE` generation.
- Parcel detail summary returns the cadastre generation id and source date used for geometry.
- ETAK/admin facts use the currently published generation of each approved source.
- Every GIS fact carries its own provenance; values from different sources must not be presented with a fabricated shared timestamp.
- A failed refresh does not erase the last-known-good published generation.

## Parcel facts

Approved ETAK layers are exposed through a generic `layerFacts` contract derived from the checked-in geospatial manifest. The UI must render common fact semantics rather than introducing a new hard-coded component for every layer.

Current bounded metrics:

- polygon layers: intersecting feature count, overlap area and parcel percentage;
- line layers: intersecting feature count and intersection length;
- administrative layers: county, municipality and settlement lookup.

A future layer may be included only after it is approved in the geospatial manifest and has a defined business/caveat contract.

## Map/detail selection

The map owns the transient spatial highlight source. The detail panel owns the semantic selection (for example, “eraldis 12”). The page coordinates them through one `MapDetailGeometrySelection` state.

- selecting a sub-part/detail object updates the transient GeoJSON highlight;
- closing/changing the parcel clears transient highlight state;
- basemap and persistent layer preferences are not cleared;
- parcel facts may request visibility of an already-approved Map Layer Registry/ETAK layer through the existing overlay control.

No national GeoJSON dataset is created for detail highlighting.

## Missing, zero and unavailable semantics

These states are distinct:

- `0`: the source was available and the measured value is zero;
- missing/unknown: the source did not provide a value;
- unavailable: no current published source generation or request failure;
- stale/degraded: last-known-good data exists but freshness policy indicates caution;
- unauthorized: enforced by backend authorization and never represented as a fake zero value.

## Performance contract

- Overview latency must not grow linearly with notification/sub-part history length.
- Bulk sections use bounded keyset pages.
- Geometry and raw attributes are fetched only for explicit selections.
- GIS facts are loaded as a separate section and run server-side spatial queries against indexed local generations.
- The browser never downloads national vector datasets as one GeoJSON payload.

## Failure model

Each section owns its loading/error/empty state. A timeout or 5xx in notifications, forestry or GIS context leaves the base parcel workspace usable and offers a section-level retry.

## Testing expectations

At minimum cover:

- geometry parsing for GeoJSON and WKT Polygon/MultiPolygon;
- programmatic reveal of an approved context layer without provider URL exposure;
- multi-owner summary semantics;
- bounded paging for detail lists;
- partial section failure while overview stays visible;
- parcel → sub-part/notification geometry highlight → clear/close flow.
