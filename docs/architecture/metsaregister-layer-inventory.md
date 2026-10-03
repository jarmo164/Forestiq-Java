# Metsaregister map/data inventory

Status: factual inventory for issues #262–#266. Snapshot date: 2026-09-06.

## Current path and coupling

The current implementation discovers GeoServer feature types with `MetsaregisterWfsMetadataClient` and reconciles them into `metsaregister_wfs_layer`. `MetsaregisterWfsSchemaService` records the source CRS/schema and deterministic identity/sort fields. `MetsaregisterWfsMirrorJobConfiguration` schedules the provider-global mirror; `MetsaregisterWfsMirrorService` pages complete WFS layers into generation-scoped `metsaregister_wfs_feature` rows. Geometry is normalized to EPSG:4326 and a generation is published only after a complete count-checked read. V39 exposes last-known-good projections. Admin status is exposed under `/api/v1/admin/integrations/metsaregister-wfs` and rendered by the Next.js `admin/metsaregister-wfs` page.

Before #263/#266, `approved + enabled + provider_present` acted both as provider-catalogue state and as ingestion authorization. V37 initially approved the catalogue wholesale, so display/context layers could be mirrored even when ForestIQ had no business-query need. This is the coupling removed by the manifest.

The map itself is MapLibre (`frontend/components/map/parcel-map.tsx`). ForestIQ parcel geometry is served as MVT from `/api/v1/map/land-parcels/...`; the current base/orthophoto sources are external raster tiles. No current user workflow requires national Metsaregister overlay geometry to be duplicated merely to draw it.

## Workflow dependencies

| Provider layer | Current path / local object | User/business workflow | Queryable data required? | Map-only requirement? | Local persistence reason | Migration candidate |
|---|---|---|---|---|---|---|
| `teatis` | WFS mirror -> `metsaregister_wfs_notification` | Current forest notifications on parcel/evaluation context | Yes | Optional | operational query, parcel join, last-known-good | `KEEP_LOCAL` |
| `teatis_arhiiv` | WFS mirror -> `metsaregister_wfs_notification` | historical notification context | Yes | Optional | history/query | `KEEP_LOCAL` |
| `eraldis` | WFS mirror -> `metsaregister_wfs_compartment` / `_detail` | forest plan/compartment evaluation | Yes | Optional | core domain entity and parcel join | `KEEP_LOCAL` |
| `eraldis_element` | WFS mirror -> `_compartment_element` / `_detail` | stand composition | Yes | No | child domain rows | `KEEP_LOCAL` |
| `isearasused` | WFS mirror -> `_compartment_peculiarity` / `_detail` | compartment peculiarities | Yes | Optional | queryable child attributes | `KEEP_LOCAL` |
| `kahjustused` | WFS mirror -> `_compartment_damage` / `_detail` | damage cause/severity in evaluation | Yes | Optional | queryable business attributes | `KEEP_LOCAL` |
| `kl_kasvukoht` | WFS mirror -> `_classifier` | forest-plan labels | Yes | No | small joinable reference data | `KEEP_LOCAL` |
| `kl_omandivorm` | WFS mirror -> `_classifier` | forest-plan labels | Yes | No | small joinable reference data | `KEEP_LOCAL` |
| `kl_rinne` | WFS mirror -> `_classifier` | stand-composition labels | Yes | No | small joinable reference data | `KEEP_LOCAL` |
| `kl_puuliik` | WFS mirror -> `_classifier` | stand-composition labels | Yes | No | small joinable reference data | `KEEP_LOCAL` |
| `katsealad` | generic WFS feature only | protected-area visual context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `natura_2000_alad` | generic WFS feature only | Natura visual context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `yp_kaitsemets` | generic WFS feature only | protection-forest visual context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mr__teema_raie_diameeter` | generic WFS feature only | harvest-maturity visual analysis | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mr__teema_raie_liik` | generic WFS feature only | harvest-maturity visual analysis | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mr__teema_raie_taius` | generic WFS feature only | harvest-maturity visual analysis | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mr__teema_raie_vanus` | generic WFS feature only | harvest-maturity visual analysis | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mke_arhiiv` | generic WFS feature only | historical forest-protection visual context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mue` | generic WFS feature only | forest-renewal expertise context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `kuusekooreyrask_mke` | generic WFS feature only | bark-beetle/protection context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `mke` | generic WFS feature only | cutting-recommendation/protection context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `pesitsusrahu_maatriks` | V38 enabled generic WFS mirror | nesting-peace context | No current local query | Yes | none identified | `EXTERNAL_MAP` |
| `raadatud_metsateatised` | catalogue only; anonymous GetFeature returned 401 | no production workflow | No | No | access not approved | `RETIRE`/disabled |

## CRS, storage and refresh facts

- Provider metadata stores `default_crs`; the current catalogue expects Metsaregister source data in EPSG:3301.
- Full-layer WFS reads request GeoJSON in EPSG:4326; `metsaregister_wfs_feature.geometry` is `geometry(Geometry, 4326)` with a GiST index.
- Tenant data is not duplicated: national provider rows are global and V39 applies tenant scope when a query begins from `land_parcel.organization_id`.
- Generation staging/checkpointing keeps the previous generation readable through failed refreshes.
- Existing refresh policy is 6 h for current notifications, daily for core compartment/notification data and weekly for classifiers. Those intervals remain relevant only to `LOCAL_DOMAIN_DATA` after #266.
- Map rendering and business data are separate concerns: a WMS/WMTS image is visual context; it is not a substitute for queryable notification/compartment rows. Conversely, public WFS availability alone is not a persistence justification.

## Authentication and trust boundary

Public Metsaregister WFS/WMS requests are server-originated. The browser must not receive a configurable upstream proxy URL. `raadatud_metsateatised` is explicitly disabled because provider discovery did not imply anonymous read authorization. Newly discovered provider layers are catalogue facts only and must default to no local ingestion until added to the checked-in manifest.

## Decisions carried into #263–#266

The machine-readable `metsaregister-layer-manifest.json` is the policy authority. `LOCAL_DOMAIN_DATA` is the only mode that authorizes national local ingestion. `EXTERNAL_MAP` is rendered through the bounded server-side map gateway. `BOUNDED_QUERY` is reserved for future viewport/parcel-scoped data reads. Discovery may update presence/schema metadata but must never broaden storage scope.
