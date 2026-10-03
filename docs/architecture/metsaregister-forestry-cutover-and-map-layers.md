# Metsaregister forestry cutover and map-layer architecture

Status: accepted for the #267–#271 implementation wave (2026-09-07).

## 1. Ownership and canonical data boundary

ForestIQ treats Metsaregister data in three distinct responsibilities. They must not be collapsed into one table or one map transport.

| Responsibility | Owner | Storage / transport | Generation semantics |
|---|---|---|---|
| Provider mirror and lineage | Metsaregister WFS adapter | `metsaregister_wfs_layer`, `metsaregister_wfs_generation`, `metsaregister_wfs_feature` | immutable per-layer WFS generations |
| ForestIQ queryable forestry domain | ForestIQ | `METSAREGISTER_FORESTRY` plus `metsaregister_forestry_*` canonical views | one atomic composite generation containing exact provider-layer generation UUIDs |
| Display-only provider overlays | provider | server-side WMS proxy | no local national business-data mirror |

The provider mirror is staging/lineage, not an application read model. New business reads use the composite ForestIQ forestry generation once the first one has been published.

### Canonical keys

- Composite generation: `geospatial_generation.id` for source key `METSAREGISTER_FORESTRY`.
- Provider member: `(layer_name, layer_generation_id)` in `metsaregister_forestry_generation_member`.
- Provider feature: `(generation_id, feature_key)` in `metsaregister_wfs_feature`.
- Notification business identity: provider notification/source identity is retained in the canonical view; the compatibility REST API derives a deterministic 64-bit cursor ID from `(layer_name, feature_key)` rather than copying rows into a second tenant table.
- Compartment business identity: provider `source_id`/`feature_key`, with child rows joined only inside the same composite generation.

### CRS policy

The current provider mirror stores normalized geometry as EPSG:4326. The map read path transforms only the bounded tile candidate set to EPSG:3857 for `ST_AsMVTGeom`. ForestIQ's broader GIS/domain canonical CRS remains EPSG:3301 where a persisted analytical/domain geometry is required. No browser-facing API may depend on provider axis order.

## 2. Atomic cutover and rollback

`METSAREGISTER_FORESTRY` is the only publication pointer used by new forestry map and detail reads.

Publication is allowed only when every enabled `LOCAL_DOMAIN_DATA` layer in `metsaregister-layer-manifest.json` has a last-known-good provider generation. The bundle checksum is calculated from the ordered set of `(layer, generation UUID, row count)` members. Publishing changes `geospatial_source_current` atomically.

A failed refresh never replaces the current generation. Previously published provider generations referenced by a ForestIQ composite generation are retention-protected.

Rollback changes only the `METSAREGISTER_FORESTRY` current pointer to a retained `RETIRED` generation. It does not redownload WFS data and does not rebuild business rows. This makes rollback deterministic and fast.

### Cutover gate

Before a new implementation wave is considered migrated, query `metsaregister_forestry_cutover_comparison` and require for every local layer:

- `generation_matches = true`
- `row_count_matches = true`
- no expected manifest layer missing from the canonical generation

After the first canonical publication, an empty canonical result is authoritative. The REST adapter does **not** silently fall back to legacy tenant projections, because that could combine different generations in one user session.

## 3. Legacy asset disposition

| Asset | Disposition | Removal condition |
|---|---|---|
| `metsaregister_wfs_layer` | KEEP | provider catalogue/health control plane |
| `metsaregister_wfs_generation` | KEEP | provider lineage + resumable generation history |
| `metsaregister_wfs_feature` | KEEP | immutable raw/local source rows used by canonical projections and MVT |
| `cadastre_notification` forestry-imported rows | TRANSITION | stop writing after canonical publication; remove only after consumers and history jobs are verified against canonical API |
| `forest_registry_feature` | TRANSITION | stop writing after canonical publication; remove after all detail/evaluation consumers use canonical forestry views |
| WMS/WFS national mirrors for `EXTERNAL_MAP` layers | DELETE/DO NOT CREATE | provider WMS is authoritative; local storage is not a business requirement |
| old hard-coded frontend Metsaregister source lists | DELETE | descriptor API is the only frontend layer catalogue |

No legacy table is dropped in the same release that introduces the canonical read path. Cleanup is a separate destructive migration after production observation proves there are no remaining readers/writers.

## 4. Forestry vector-tile contract

`backend/src/main/resources/forestry-tile-catalog.json` is the checked-in contract for locally served forestry map layers.

Each MVT layer defines:

- stable layer key and Mapbox source-layer name;
- min/max zoom;
- feature budget and encoded byte budget;
- p95 latency target;
- allowed feature properties only;
- authorization scope;
- visual style kind;
- explicit low-zoom policy.

The repository first spatially bounds candidates with `ST_TileEnvelope`, uses the GiST geometry index, checks the feature cap, and only then encodes MVT. A tile above the feature or byte budget fails explicitly instead of returning a silently truncated national view.

Low zoom is deliberately hidden for detailed forestry layers. The browser never downloads national forestry GeoJSON.

### Cache identity

Local MVT URL and ETag include:

`layer key + immutable forestry generation UUID + z/x/y + authorization dimension`

Responses are `private, immutable` and vary by `Authorization`. A tile from an older generation therefore cannot be mistaken for the current generation after publish or rollback.

## 5. Map Layer Registry contract

`GET /api/v1/map/metsaregister/layers` is the safe descriptor API used by MapLibre. It is authenticated.

The frontend descriptor may expose only information needed to construct the map UX:

- stable key/title/provider/category;
- business mode and transport (`MVT`, `WMS`, later `WMTS` under the same contract);
- safe ForestIQ tile/proxy URL template;
- CRS and zoom range;
- default order, opacity and visibility;
- queryable/mandatory flags;
- attribution and freshness target;
- style kind, cache policy and published generation ID where applicable;
- availability state.

It must never expose provider credentials, tokens, arbitrary upstream URLs, unrestricted WMS parameters or internal member-generation mappings.

Checked-in manifests own relatively static policy/presentation metadata. Operational freshness, runs and publication state stay in the source/generation control plane and are referenced rather than duplicated in the map registry.

Invalid duplicate keys, unsupported local-map modes, invalid zoom ranges, missing attribution, unsupported tile properties or missing local-layer policy fail configuration validation instead of producing a partially trusted map definition.

## 6. Map workspace and preferences

The MapLibre layer workspace is descriptor-driven and groups at least:

- ForestIQ queryable forestry layers;
- Metsaregister external display layers.

Core parcel/base-map sources remain map-workspace infrastructure and are intentionally not represented as fake Metsaregister provider layers. The registry contract is transport-neutral so those can be added as first-class ForestIQ descriptors without changing the control model.

For registry-controlled layers users may change visibility, opacity and order without rebuilding the map or losing the current parcel selection. Preferences are persisted in `localStorage` (`forestiq.map-layer-preferences.v1`) for normal navigation/session continuity.

Mandatory/security-controlled layers are policy-owned: stored user preferences cannot turn them off or weaken their authorization.

An unavailable external WMS layer surfaces a non-blocking warning and does not disable local MVT or parcel/domain workflows.

Queryable MVT payloads contain only small selection properties such as `cadastralCode`. Clicking a forestry feature opens the existing parcel/detail API; detailed forestry attributes are not duplicated into every vector tile.

## 7. Serving technology decision and benchmark gate

Initial implementation: **Spring Boot + PostGIS MVT**.

Reasoning:

- ForestIQ already owns the authorization and generation control plane in Spring;
- PostGIS already stores the immutable geometry and has the required GiST indexes;
- generation-aware cache identity is simple and explicit;
- no extra deployment/service failure domain is introduced before measurements justify it.

A dedicated tile process/service or precomputed tile set is a scale option, not the default architecture. Move away from Spring/PostGIS only when the checked-in forestry MVT benchmark demonstrates a repeated budget miss that is attributable to tile encoding/query isolation rather than missing indexes or invalid layer budgets.

The benchmark must cover sparse and dense tiles in low/mid/detail zoom bands, capture p50/p95/p99 latency, response bytes, feature count and `EXPLAIN (ANALYZE, BUFFERS)` evidence, and verify that the GiST index path is used for representative non-empty tiles.

Rollback for a tile-serving component change is independent from data rollback: the descriptor route can be pointed back to the Spring/PostGIS implementation while retaining the same generation-keyed URL/cache contract.

## 8. Acceptance invariants

The following are release invariants for #267–#271:

1. Map and detail reads resolve through the same published `METSAREGISTER_FORESTRY` source pointer after canonical cutover.
2. Partial WFS staging data cannot become current.
3. A retained generation can be rolled back without provider access.
4. The browser never needs full-national forestry GeoJSON.
5. Tile overflow is explicit, never silent truncation.
6. Protected/local map paths require authentication before encoding data.
7. Frontend descriptors contain no upstream secrets/open-proxy capability.
8. External map failure is isolated from local/domain workflows.
9. User preferences cannot override mandatory/security policy.
10. Legacy duplicate projections have an explicit removal condition and are not allowed to become a permanent second source of truth.
