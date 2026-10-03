# Metsaregister canonical forestry cutover

Status: implementation contract for issue #267. Epic #225 and the layer manifest remain authoritative over legacy mirror assumptions.

## Target ownership

ForestIQ keeps two deliberately separate local concerns:

1. **Provider/raw staging** — `metsaregister_wfs_layer`, `metsaregister_wfs_generation` and `metsaregister_wfs_feature` remain the resumable WFS ingestion boundary. They preserve provider payload, schema evidence, paging checkpoints and last-known-good layer generations. They are not an application read contract.
2. **Canonical forestry domain** — logical geospatial source `METSAREGISTER_FORESTRY`, `forestry_generation_source` and `forestry_domain_feature` form the application-owned read model. One published composite generation freezes the exact raw generation used for every manifest-approved `LOCAL_DOMAIN_DATA` layer.

The canonical CRS is **EPSG:3301**. Provider/raw WFS geometry remains EPSG:4326 until canonical projection. Provider JSON is retained in `attributes` for detail/audit use, while frequently used business fields are extracted into typed columns.

## Generation contract

A canonical generation is publishable only when all manifest-approved local-domain layers have a complete current raw `PUBLISHED` generation and raw feature count matches the mirror checkpoint count.

The composite artifact checksum is deterministic over:

- canonical schema version;
- layer-manifest version;
- sorted layer names;
- exact raw mirror generation UUID for every local layer;
- source row count for each layer.

The projector copies from those explicit UUIDs, never from `metsaregister_wfs_current_feature`. Consequently a layer pointer changing during or after projection cannot change the generation being built.

Publication uses the generic `geospatial_source_run` / `geospatial_generation` / `geospatial_source_current` control plane. A failed build leaves the previous published composite generation current. A previously accepted identical source set is reused: a retired generation is rolled back, while an interrupted validated generation can be published without rebuilding.

## Canonical read models

`forestry_current_feature` is the single generation-resolved base view. Domain views are:

- `forestry_current_notification` — current + archive notifications with explicit `archived` semantics;
- `forestry_current_compartment` — forest-plan/compartment parent rows;
- `forestry_current_compartment_element` — stand composition rows;
- `forestry_current_compartment_peculiarity` — peculiarities;
- `forestry_current_compartment_damage` — damages;
- `forestry_current_classifier` — growth-site, ownership-form, storey and tree-species classifiers;
- `forestry_current_compartment_detail` — parent plus child collections from the **same composite generation**.

Canonical feature identity is `(generation_id, layer_name, source_feature_key)`. The numeric `id` is an API paging/detail handle, not source identity. It may change when a new generation is published, so clients must not persist it as a business identifier.

## Existing workflow cutover

`CadastreRepository` is the user-facing read boundary. After `METSAREGISTER_FORESTRY` has a published current generation:

- notification list/page reads use `forestry_current_notification`;
- forest-plan list/page/raw reads use `forestry_current_compartment`;
- organization access remains enforced by requiring the cadastral code to exist in that tenant's `land_parcel` table;
- raw geometry is transformed back to EPSG:4326 GeoJSON only for the existing detail API compatibility contract.

Before the first canonical publication only, the repository may fall back to the legacy tenant tables. Once a canonical current pointer exists, changing data means publishing or rolling back a canonical generation — not silently falling back to a different source.

The existing per-cadastre refresh endpoints remain **transition compatibility**. They may update legacy tenant rows for verification/audit, but those rows are no longer authoritative after canonical cutover. Operational refresh of canonical forestry data is source-wide and generation-safe; the admin canonical endpoint exposes explicit refresh/status/rollback.

## Comparison and rollback

`GET /api/v1/admin/integrations/metsaregister-forestry/canonical` returns:

- current canonical generation;
- manifest version;
- readiness;
- whether the canonical source-generation set matches every current raw layer;
- missing/invalid raw layers;
- drifted layer generation IDs and row-count evidence.

`POST .../refresh` creates/publishes a new generation only when the complete raw set differs. `POST .../rollback/{generationId}` uses the generic atomic rollback path and records a generation event.

Before cutover is considered healthy, compare every canonical layer row count with the frozen raw generation count. Publication is rejected on any mismatch. Application detail aggregation is additionally generation-scoped so child tables cannot cross generations.

## Last-known-good retention

Canonical rows are immutable after validation. `PUBLISHED` and `VALIDATED` rows cannot be deleted through feature cleanup. A database guard also prevents deletion of the only retained `RETIRED` `METSAREGISTER_FORESTRY` generation, preserving one immediate rollback target. Older retired generations may be removed only when another retired known-good generation remains.

Because `forestry_generation_source` holds `ON DELETE RESTRICT` references to raw mirror generations, raw generations that back a retained canonical generation cannot be cleaned first. Cleanup order is therefore canonical-retention decision -> canonical generation cleanup -> unreferenced raw mirror cleanup.

## Legacy disposition

| Asset | Disposition | Reason / exit condition |
| --- | --- | --- |
| `metsaregister_wfs_layer` | **KEEP** | provider discovery, schema/health and ingestion policy evidence |
| `metsaregister_wfs_generation` | **KEEP** | resumable per-layer raw generations and canonical lineage |
| `metsaregister_wfs_feature` | **KEEP / RAW ONLY** | raw staging; not an application-domain read API |
| V39 `metsaregister_wfs_*` views | **TRANSITION** | compatibility/diagnostics; new application readers must use canonical forestry views |
| `cadastre_notification` current-provider use | **TRANSITION** | legacy per-tenant refresh/history compatibility; canonical current notifications replace source authority |
| `forest_registry_feature` | **TRANSITION** | legacy per-tenant forest-plan compatibility; canonical compartment rows replace source authority |
| `notification_import_run/stage` | **TRANSITION** | older tenant import path; retire after canonical operational window proves parity |
| per-cadastre Metsaregister refresh jobs | **TRANSITION** | verification/compatibility only after canonical cutover; do not use as canonical publication |

No legacy table is dropped in the same release as read cutover. Removal requires an observed rollback window, row-count/semantic parity evidence and a code search proving no production read path remains.

## Verification checklist

- all ten manifest-approved `LOCAL_DOMAIN_DATA` layers are represented in `forestry_generation_source`;
- canonical total and per-layer counts equal the exact raw generations referenced by the publication;
- canonical geometry has SRID 3301;
- notification current/archive semantics match existing user behavior;
- compartment children in detail views share the parent's canonical generation;
- tenant A cannot read a cadastral code that exists only in tenant B;
- a failed raw or canonical refresh leaves the previous canonical generation readable;
- rollback changes `geospatial_source_current` without reconstructing domain rows;
- the only retired known-good canonical generation cannot be deleted;
- V39/raw mirror structures are not used by new canonical application reads.
