# ForestIQ Docker Postgres data inventory

Date: 2026-09-09
Source: local Docker container `forestiq-postgres-1`, database `forestiq`
Method: read-only SQL inspection with aggregate counts, schema metadata, sizes and foreign-key relationships. No row-level personal data was sampled or exported.

## Database summary

The local Docker database is a PostgreSQL 17.5 database with PostGIS 3.5.2 enabled.

| Item | Value |
| --- | ---: |
| Database size | 37 GB |
| Application tables in `public` | 100 |
| Application views in `public` | 22 |
| PostGIS/system schemas | `public`, `tiger`, `topology` |
| Installed extensions | `postgis`, `postgis_topology`, `postgis_tiger_geocoder`, `pgcrypto`, `pg_stat_statements`, `fuzzystrmatch`, `plpgsql` |

Most of the database volume is not ordinary CRM data. It is geospatial registry data: Metsaregister WFS features, cadastral geometry generations, parcel render tables and registry synchronization history.

## Largest tables

The table size numbers come from PostgreSQL relation sizes. The row counts in this table are planner estimates, so they are suitable for scale analysis but not for audit-grade totals.

| Table | Estimated rows | Size |
| --- | ---: | ---: |
| `metsaregister_wfs_feature` | 17,741,026 | 17 GB |
| `external_dataset_feature` | 749,803 | 2,293 MB |
| `land_parcel_overview_contribution` | 3,876,197 | 1,888 MB |
| `land_parcel` | 787,735 | 1,729 MB |
| `geospatial_cadastre_parcel` | 778,434 | 1,563 MB |
| `owner_parcel_relation` | 1,149,315 | 1,321 MB |
| `cadastre_sub_part` | 1,769,321 | 1,170 MB |
| `land_parcel_render` | 777,776 | 857 MB |
| `registry_sync_log` | 779,196 | 842 MB |
| `land_parcel_render_owner` | 578,567 | 660 MB |
| `data_change_signal` | 1,149,313 | 631 MB |
| `geospatial_cadastre_domain_link` | 787,310 | 509 MB |
| `geospatial_cadastre_domain_event` | 1,575,295 | 455 MB |
| `owner` | 549,120 | 443 MB |
| `cadastre_notification` | 953,481 | 306 MB |
| `forest_registry_feature` | 48,738 | 94 MB |

Index footprint is also significant. The largest index groups are on `metsaregister_wfs_feature` at about 4,366 MB, `owner_parcel_relation` at about 1,073 MB, `land_parcel` at about 968 MB, `land_parcel_overview_contribution` at about 814 MB and `registry_sync_log` at about 712 MB.

## Exact row counts for core datasets

These counts were collected with `count(*)` for selected tables.

| Area | Table | Rows |
| --- | --- | ---: |
| Tenant | `organization` | 1 |
| Owners | `owner` | 549,128 |
| Parcels | `land_parcel` | 787,738 |
| Ownership links | `owner_parcel_relation` | 1,149,319 |
| Cadastre generation | `geospatial_cadastre_parcel` | 778,434 |
| Cadastre generation metadata | `geospatial_generation` | 3 |
| Metsaregister layer config | `metsaregister_wfs_layer` | 23 |
| Metsaregister WFS generations | `metsaregister_wfs_generation` | 16 |
| Metsaregister WFS feature store | `metsaregister_wfs_feature` | 18,481,901 |
| Legacy external dataset feature store | `external_dataset_feature` | 778,330 |
| Legacy external dataset snapshot runs | `external_dataset_snapshot_run` | 1 |
| Forest registry features | `forest_registry_feature` | 48,860 |
| Forest sub-parts | `cadastre_sub_part` | 1,769,321 |
| Forest notifications | `cadastre_notification` | 953,524 |
| Registry freshness/history | `registry_sync_log` | 779,211 |
| Change signals | `data_change_signal` | 1,149,743 |
| Inheritance cases | `inheritance_case` | 153 |
| External inheritance notices | `external_inheritance_notice` | 278 |
| Integration runs | `integration_sync_run` | 106 |
| Staging tables | `stg_forestiq_*` | 0 currently persisted rows |

## Main data domains

### Tenant, owners and parcel ownership

This local database has one tenant in `organization`. The owner and parcel model is large:

- `owner` stores land-owner master data, including display name, personal or registry code, contact fields, status, assignment subject, full-text search vector and source fields. This table contains personal data and must not be exported casually.
- `land_parcel` stores domain parcel rows scoped by `organization_id`, with cadastral code, owner link, boundary geometry, address fields, area breakdowns and pricing fields.
- `owner_parcel_relation` is the many-to-many ownership link between owners and parcels. It includes source metadata, active/deactivated state and manual override audit fields.
- `data_change_signal` records registry-derived changes attached to an organization, owner and/or parcel.
- `owner_activity`, reminders, saved workbaskets, deal cases, documents and contracts form the operational sales/workflow layer around the same owner and parcel entities.

The dominant structural invariant is tenant scoping through `organization_id`. Many tables use composite foreign keys that pair `organization_id` with the domain entity id so that cross-tenant references cannot be represented accidentally.

### Geospatial cadastre generation

The current cadastral geometry path is generation-based.

- `geospatial_source`, `geospatial_source_run`, `geospatial_generation` and `geospatial_source_current` model source runs, generated datasets and the currently published generation.
- `geospatial_cadastre_parcel` stores cadastral geometries and source attributes per generation.
- `geospatial_cadastre_domain_link`, `geospatial_cadastre_domain_event`, replacement candidates and reconciliation tables connect generated geometries back to domain `land_parcel` rows.
- `land_parcel_render`, `land_parcel_render_owner`, `land_parcel_overview_cell` and `land_parcel_overview_contribution` are read/rendering support structures for map and overview use cases.

Current published generations:

| Source | Generation id | Status | Source date | Rows | Published at UTC |
| --- | --- | --- | --- | ---: | --- |
| `MARU_CADASTRE` | `e1c0724b-09df-4b1f-b0e3-ef086c5bfd9e` | `PUBLISHED` | 2026-09-09 | 778,431 | 2026-09-09 04:43:41 |
| `METSAREGISTER_FORESTRY` | `a6506565-8a8a-4270-b4a5-5b6436bcbdcd` | `PUBLISHED` | 2026-09-09 | 11,402,812 | 2026-09-09 04:30:52 |

The generation model is the cleanest structure for large registry datasets because a new dataset can be staged, validated, published and retired without mutating the active read model row by row.

### Metsaregister WFS data

Metsaregister data is the largest part of the database.

- `metsaregister_wfs_layer` stores provider layer metadata: approval/enabled state, schema fingerprint, identity/sort/geometry properties, counts, current generation id, verification state and consumption mode.
- `metsaregister_wfs_generation` stores per-layer generation progress: `next_start_index`, processed pages, staged rows, provider `numberMatched`, timestamps and errors.
- `metsaregister_wfs_feature` stores the generated WFS feature data by generation and layer. It has `properties` as JSONB and PostGIS `geometry`.
- Views such as `metsaregister_wfs_current_feature`, `metsaregister_wfs_compartment`, `metsaregister_wfs_notification` and the `metsaregister_forestry_*` views expose current data in domain-shaped read models.

Layer state summary:

| State | Approved | Enabled | Consumption mode | Layers |
| --- | --- | --- | --- | ---: |
| `HEALTHY` | false | false | `EXTERNAL_MAP` | 12 |
| `HEALTHY` | true | true | `LOCAL_DOMAIN_DATA` | 10 |
| `PENDING_SCHEMA` | false | false | `DISABLED` | 1 |

Generation state summary:

| State | Generations | Rows staged | Provider matched |
| --- | ---: | ---: | ---: |
| `PUBLISHED` | 10 | 11,402,812 | 11,402,812 |
| `RETIRED` | 5 | 3,359,089 | 3,359,089 |
| `FAILED` | 1 | 3,720,000 | 6,784,330 |

The failed generation was abandoned because the provider `numberMatched` changed during staging. That is expected operationally for a live WFS provider and is exactly why generation-based publish/retire semantics are important.

### Forest registry domain tables

The application also has domain-specific forest registry tables:

- `forest_registry_feature` stores normalized forest registry features by organization and cadastral code.
- `cadastre_sub_part` stores forest sub-part records with sub-part code, tree type, area and polygon text.
- `cadastre_notification` stores forest/cadastre notification records with notification number, work code, decision, dates, source identifiers and generation metadata.

These tables are smaller than the raw Metsaregister WFS store but still large enough to matter operationally: about 1.77 million sub-parts and 953 thousand notifications.

### Legacy external dataset store

The legacy external dataset path is still present:

- `external_dataset_snapshot_run` has one completed snapshot run for `MAAAMET_CADASTRE`.
- `external_dataset_feature` contains 778,330 features, all with geometry present.
- `external_dataset_current` points consumers to the current snapshot.

This path overlaps conceptually with the newer generation-based cadastre pipeline. It should be treated as legacy or compatibility data unless a specific consumer still depends on it. It is large enough to be visible in storage: about 2.3 GB table size plus about 440 MB indexes.

### Registry synchronization and operational history

`registry_sync_log` contains about 779 thousand rows and 842 MB of data. It records registry requests by organization/cadastral code, source registry, status, update count, raw response and actor subject.

Current status summary:

| Registry | Status | Rows |
| --- | --- | ---: |
| `MAAAMET_WFS` | `SUCCESS` | 778,113 |
| `MAAAMET_WFS` | `FAILED` | 28 |
| `METSAREGISTER_ERALDISED` | `SUCCESS` | 61 |
| `METSAREGISTER_TEATISED` | `SUCCESS` | 1,009 |

This table is operational history/freshness evidence, not the canonical spatial source. Because it stores raw responses, it can grow quickly and may contain provider payloads that should be retained and pruned according to a clear retention policy.

### Import staging tables

The `stg_forestiq_*` tables currently report zero persisted rows:

- `stg_forestiq_eraldis`
- `stg_forestiq_teatis`
- `stg_forestiq_kataster_boundaries`
- `stg_forestiq_registry_features`
- `stg_forestiq_rik_katastrid`

These tables are staging/import structures rather than durable application read models. Their presence indicates batch import support even when no staging data remains after the current run.

### Inheritance and workflow data

The database contains a smaller inheritance workflow:

- `inheritance_case`
- `inheritance_case_event`
- `inheritance_heir`
- `external_inheritance_notice`
- `inheritance_notice_import`
- `inheritance_notice_sync`

There are 153 current inheritance cases and 278 external inheritance notices. These tables link back to `organization` and `owner` and should be treated as personal-data-bearing workflow data.

## Relationship structure

The schema uses UUID primary keys for core domain objects and a mixture of UUID and provider keys for generated registry data.

Key relationship patterns:

- Most business tables reference `organization.id`.
- Owner-scoped tables reference `owner.id` and often also `owner.organization_id` through composite constraints.
- Parcel-scoped tables reference `land_parcel.id` and often also `land_parcel.organization_id`.
- Deal, document and contract tables attach workflow state to `owner`, `land_parcel` and `organization`.
- Geospatial generated data references `geospatial_generation` by `source_key` and `generation_id`.
- `geospatial_source_current` and `external_dataset_current` are pointer tables that select the currently active generation/snapshot.
- Metsaregister layer/generation/feature tables use `layer_name`, `generation_id`, `feature_key` and provider identifiers rather than tenant-scoped owner/parcel keys.

Tables with the densest foreign-key structure include `contract_signing_state`, `contract_version_audit`, `data_quality_issue`, `owner_activity`, `reminder_event`, `contract`, `data_change_signal`, `deal_case_parcel`, `deal_evaluation`, `geospatial_cadastre_domain_event`, `owner_parcel_relation` and saved workbasket tables.

## Important observations

1. The database is geospatial-data heavy. The top raw and derived registry tables account for most of the 37 GB footprint.
2. `metsaregister_wfs_feature` is the largest table by a wide margin and should be the first place to check when Docker/Postgres storage grows.
3. The cadastre model has two eras side by side: legacy `external_dataset_*` snapshot storage and newer `geospatial_*` generation storage. The newer generation/current-pointer structure is better suited for safe cutovers.
4. Owner and parcel tables contain business-critical and personal-data-bearing records. Debugging and documentation should continue to use counts/schema only unless there is an explicit data-handling task.
5. `registry_sync_log` is large and contains raw provider responses. It should have a documented retention policy because it is neither the canonical source nor small operational metadata.
6. The local database has old failed/superseded registry load history, including one failed Metsaregister WFS generation and retired generations. That is useful for operations, but it should not be confused with the current published data.

## Suggested follow-up work

- Decide whether `external_dataset_*` is still needed after generation-based cadastre import is fully canonical.
- Add or confirm retention rules for `registry_sync_log`, retired Metsaregister generations and failed generation feature rows.
- Keep DB inventory scripts aggregate-only so they can be run safely on developer machines without exporting personal data.
- Monitor the size of `metsaregister_wfs_feature` and its indexes after every full WFS import.
