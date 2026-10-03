# ForestIQ data source inventory

Date: 2026-09-15

This document summarizes the external and file-based data sources that can populate or support the ForestIQ PostgreSQL database. It is based on the current repository configuration, manifests, runbooks and the local ForestIQ database source/generation state.

## Summary

ForestIQ separates provider ingestion into two classes:

- Canonical writers publish application data through durable PostgreSQL state, usually with a current generation pointer.
- Diagnostic, file-download or display-only paths may fetch provider data, but must not publish competing canonical application data.

The primary rule is: one public data class has one canonical writer. Live WFS calls are allowed for diagnostics, manual file downloads and staging evidence unless the source contract explicitly says the live path is canonical.

## Data sources

| Data source | URL / endpoint | Data received | PostgreSQL target | How to use |
| --- | --- | --- | --- | --- |
| Maa- ja Ruumiamet cadastre snapshot | `https://s3.pilw.io/rp-kemit-kataster/ANDMED/Eesti_KATASTER_GPKG.zip` | Nationwide cadastral GeoPackage: cadastral code, boundary geometry, area and provider attributes | `geospatial_source_run`, `geospatial_generation`, `geospatial_cadastre_parcel`, `geospatial_source_current`, domain/search/map projections | Canonical cadastral source. Use the snapshot download/check/import workflow and publish a validated `MARU_CADASTRE` generation. Do not use nationwide WFS as the steady-state mass import path. |
| Maa-amet / Maa- ja Ruumiamet cadastre WFS | `https://gsavalik.envir.ee/geoserver/kataster/wfs` | Targeted cadastral lookup / legacy WFS diagnostics | Diagnostic evidence and legacy migration artifacts only | Use only for diagnostics or bounded migration work. It must not overwrite canonical parcel geometry or act as a normal mass refresh writer. |
| Maa- ja Ruumiamet basemap tiles | `https://tiles.maaamet.ee/tm/tms/1.0.0` | Basemap tile layers | Not persisted as provider data in PostgreSQL | Browser map background. Use through the map UI/control layer, not as a database ingestion path. |
| Maa- ja Ruumiamet IN-ADS / gazetteer | `https://aks.geoportaal.ee/inaks/inaadress/gazetteer` | Address and place search results | Usually response-only; not a canonical data store | Use as a map/search helper. Do not treat search responses as canonical parcel or address history. |
| ETAK WFS layers | `https://gsavalik.envir.ee/geoserver/etak/wfs` | ETAK wooded vegetation, wetlands, standing water, flowing water, roads, arable land, open areas and buildings | `geospatial_source`, `geospatial_source_run`, `geospatial_generation`, context-layer generation/MVT storage | Context and analysis layers under `MARU_ETAK_*`. They do not replace Metsaregister forestry data or legal land-use classifications. In the checked local DB these sources were registered but had no current generation. |
| Administrative boundaries | `https://teenus.maaamet.ee/ows/ajakohane-haldusjaotus` | County, municipality and settlement polygons | `geospatial_source`, `geospatial_generation`, context-layer generation/MVT storage | Map context layers under `MARU_ADMIN_*`. Use for spatial context, not as tenant/domain ownership data. In the checked local DB these sources were registered but had no current generation. |
| Metsaregister WFS canonical mirrored layers | `https://gsavalik.envir.ee/geoserver/metsaregister/ows` | Public forestry data: active notifications, archived notifications, compartments, compartment elements, damage, peculiarities and classifiers | `metsaregister_wfs_layer`, `metsaregister_wfs_generation`, `metsaregister_wfs_feature`, projection tables such as `metsaregister_wfs_notification`, `metsaregister_wfs_compartment`, and the published `METSAREGISTER_FORESTRY` domain generation | Use the Java `MetsaregisterWfsMirrorService` admin/scheduler mirror as the normal writer. `scripts/metsaregister` is retained for offline/emergency generation import and must not run concurrently with the Java mirror. |
| Metsaregister WMS / display-only map overlays | `https://gsavalik.envir.ee/geoserver/metsaregister/ows` | Visual context layers such as protected areas, Natura 2000, protection forest, harvest-maturity themes, MKE/MUE and nesting-peace matrix | Usually not persisted as queryable business data; configured through layer manifest as external map overlays | Use as visual context in the map. Do not build business rules on these layers unless a layer is explicitly approved for local persistence. |
| EELIS WFS | `https://gsavalik.envir.ee/geoserver/eelis/wfs` | EELIS public layers, for example `eelis:natura_elupaik`; downloaded as CSV pages or GeoPackage pages | Files plus `manifest.tsv`; no production PostgreSQL import by default | Use `scripts/eelis/download_wfs_layer.py` for operator-run downloads. Compare `resultType=hits` before/after. Do not import into production tables until the approved layer list, sensitive-data policy and storage target are accepted. |
| MetsIS / Forestek portfolio API | `METSIS_PORTFOLIO_URI`, for example `https://<metsis-or-forestek-host>/api/owners-test` | Owner code/name/type and active cadastral parcel list | `owner`, `land_parcel`, `owner_parcel_relation`, `metsis_portfolio_sync_checkpoint`, work/data-signal tables | Use through `OWNER_CADASTRE_RELATIONS`. This is the canonical portfolio source for active owner-cadastre relations, but not final legal proof of ownership. Store only the operational projection, not the full provider response. |
| Ametlikud Teadaanded inheritance notices | `https://www.ametlikudteadaanded.ee/ee/-/parimisteated/parimismenetluse-algatamine/%04d/%02d/%02d/xml` | Date-based inheritance notice XML feed | `external_inheritance_notice`, `inheritance_case`, heir/contact/workflow event tables | Use through `INHERITANCE_NOTICES` as inheritance workflow decision support. The raw XML/full notice text is not retained. It does not replace legal inheritance or ownership verification. |
| Weasel ownership transitions | `WEASEL_API_BASE_URL` + `/owning-transitions` | Cadastral ownership transition events | `ownership_transition_event`, `weasel_transition_sync_checkpoint`, `data_change_signal` | Use through `WEASEL_OWNERSHIP_TRANSITIONS` as a sales/data-quality signal. It does not automatically rewrite ForestIQ owner records. Requires server-only token and `WEASEL_ORGANIZATION_ID`. |
| RIK Kinnistusraamat SOAP | `https://kinnistusraamat.rik.ee/krteenused/kr_teenus.asmx?WSDL` | Contracted land-register lookups, currently represented by the `objektide_arv` SOAP operation | Registry-service lookup result/audit surface; no mass provider dataset import | Opt-in integration requiring a service agreement and credentials. Use for due-diligence checks, not scheduled bulk ingestion. |
| Manual owner/import CSV | User-provided file; no external URL | Owner and contact import rows, matched fields and validation results | `owner`, import preview/audit tables and related domain tables after commit | Use via the UI import workflow. It is not an automatic external source and should not bypass preview/validation. |

## Current local source state observed

| Source key / layer group | Current state observed locally |
| --- | --- |
| `MARU_CADASTRE` | Registered and has a current published generation. |
| `METSAREGISTER_FORESTRY` | Registered and has a current published generation. |
| `MARU_ETAK_*` | Registered as generation sources, but no current generation was present in the checked local DB. |
| `MARU_ADMIN_*` | Registered as generation sources, but no current generation was present in the checked local DB. |
| `metsaregister:teatis` | Enabled, approved, healthy, current generation present. |
| `metsaregister:teatis_arhiiv` | Enabled, approved, healthy, current generation present. |
| `metsaregister:eraldis` | Enabled, approved, healthy, current generation present. |
| `metsaregister:eraldis_element` | Enabled, approved, healthy, current generation present. |
| `metsaregister:isearasused` | Enabled, approved, healthy, current generation present. |
| `metsaregister:kahjustused` | Enabled, approved, healthy, current generation present. |
| Metsaregister classifiers | Enabled, approved, healthy, current generation present. |
| Metsaregister external map overlays | Present in manifest mostly as display-only or disabled/non-approved local persistence. |

## Operator entry points

| Task | Entry point |
| --- | --- |
| Run configured integrations | Admin UI: `Haldus -> Andmeallikad ja sünkroonimine`; API `POST /api/v1/admin/integrations/{jobKey}/runs` |
| Cadastre snapshot download/check | `CadastreSnapshotDownloadCli`, `CadastreSnapshotCheckService` |
| Cadastre generation import | `CadastreGenerationImportService` |
| Metsaregister Java mirror | Admin/scheduler through `MetsaregisterWfsMirrorService` and `/api/v1/admin/integrations/metsaregister-wfs` surfaces |
| Metsaregister offline/emergency mirror | `scripts/metsaregister/export_wfs_csv.py` and `scripts/metsaregister/import_csv_postgis.py` |
| EELIS file download | `scripts/eelis/download_wfs_layer.py` |
| MetsIS / Forestek sync | `OWNER_CADASTRE_RELATIONS` integration job |
| Weasel transition sync | `WEASEL_OWNERSHIP_TRANSITIONS` integration job |
| Inheritance notice sync | `INHERITANCE_NOTICES` integration job |

## Safety rules

1. Do not enable two canonical writers for the same public source class.
2. Failed imports must leave the previous known-good published generation readable.
3. Provider credentials and bearer tokens are server-only configuration values.
4. Generated CSV, GeoPackage, downloaded ZIP and temporary provider artifacts must not be committed.
5. Public geospatial data should be selected through source/generation pointers, not by latest row timestamp.
6. Display-only WMS overlays are map context, not queryable business facts.
