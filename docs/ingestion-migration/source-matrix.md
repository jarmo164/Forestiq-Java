# Ingestion source migration matrix

Status date: 2026-09-24

This matrix is the source-of-truth for Epic #361. It lists every value in `IntegrationJobKey` on the baseline commit and the upstream calls that are intentional Java exceptions. Implementation and routing are tracked separately. `CODE_READY` means the Python executor, checkpoint/idempotency contract and validation path exist in the repository, while production routing still remains on Java. A source may move from `CODE_READY` to `SHADOW`/`CUT_OVER` only when the evidence in `parity-and-cutover.md` is attached to the corresponding run.

## Routing invariant

For one `IntegrationJobKey` in one environment, exactly one executor may publish canonical data. Shadow execution may extract, stage, validate and compare, but it must not move a canonical generation pointer or advance a durable watermark. `JAVA` remains the rollback route until production-shaped parity and recovery evidence exists.

## Active job keys

| Job key | Classification | Purpose | Current executor / scheduler | Target | Credential boundary | State / rollback |
| --- | --- | --- | --- | --- | --- | --- |
| `CADASTRE_PUBLIC_DATA` | `ETL_ONLY` | Nationwide Maa- ja Ruumiamet GPKG snapshot | Java `RegistryIntegrationJobConfiguration`; Java integration queue/scheduler | Python snapshot adapter | Public endpoint; configuration moves to ingest runtime at cutover | `CODE_READY`; routing remains Java until live shadow evidence; retain current `MARU_CADASTRE` generation on rollback |
| `FOREST_NOTIFICATIONS` | `ETL_ONLY` | Per-cadastre notification projection and catch-up | Java `RegistryIntegrationJobConfiguration` + `RegistryBulkRecoveryService` | Python Metsaregister adapter | Public WFS configuration in ingest runtime after cutover | `CODE_READY`; routing remains Java until live shadow evidence; Java handler/current batch remains rollback path |
| `FOREST_PLAN` | `ETL_ONLY` | Per-cadastre forest-plan/compartment projection and catch-up | Java `RegistryIntegrationJobConfiguration` + `RegistryBulkRecoveryService` | Python Metsaregister adapter | Public WFS configuration in ingest runtime after cutover | `CODE_READY`; routing remains Java until live shadow evidence; Java handler/current batch remains rollback path |
| `METSAREGISTER_WFS_MIRROR` | `ETL_ONLY` | Provider-global approved `LOCAL_DOMAIN_DATA` WFS mirror | Java `MetsaregisterWfsMirrorJobConfiguration` + `MetsaregisterWfsMirrorService` | Python WFS adapter | Public WFS endpoint/rate policy in ingest runtime | `CODE_READY`; routing remains Java until live shadow evidence; keep Java mirror routable and retain published generation |
| `OWNER_CADASTRE_RELATIONS` | `ETL_ONLY` | MetsIS/Forestek owner portfolio snapshot | Java `OwnerCadastreIntegrationJob` + `MetsisPortfolioSyncService` | Python owner-portfolio adapter | `METSIS_PORTFOLIO_*` only in ingest runtime after cutover | `CODE_READY`; routing remains Java until live shadow evidence; retain previous complete snapshot/relation state |
| `WEASEL_OWNERSHIP_TRANSITIONS` | `ETL_ONLY` | Incremental ownership-transition signal feed | Java `WeaselTransitionIntegrationJob` | Python Weasel adapter | `WEASEL_*` only in ingest runtime after cutover | `CODE_READY`; routing remains Java until live shadow evidence; rollback uses last committed cursor plus overlap |
| `INHERITANCE_NOTICES` | `ETL_ONLY` | Date-based inheritance notice feed | Java `InheritanceNoticeIntegrationJob` | Python inheritance adapter | `INHERITANCE_SOURCE_*` only in ingest runtime after cutover | `CODE_READY`; routing remains Java until live shadow evidence; rollback uses last completed date plus rolling overlap |

Baseline assertion: the seven rows above exactly match `backend/src/main/java/ee/forestiq/integration/IntegrationJobKey.java` at commit `57995b6620cd60b688d0353e54d0c60947295580`.

## Per-job implementation and evidence details

`PENDING_EVIDENCE` is deliberate: no production count or checksum is invented from repository fixtures. The fields must be populated from the first controlled Java baseline run and then frozen with the run ID.

| Job key | Java handler / client locations | Source, staging and canonical/read-model tables | Checkpoint and idempotency | Baseline evidence | Current → target state |
| --- | --- | --- | --- | --- | --- |
| `CADASTRE_PUBLIC_DATA` | `RegistryIntegrationJobConfiguration`; `CadastreSnapshotCheckService`; `CadastreGenerationImportService`; `CadastreGdalAdapter` | Source artifact: official GPKG ZIP. Generation-bound stage/load: `geospatial_source_run`, `geospatial_generation`, `geospatial_cadastre_parcel`. Publish pointer/read models: `geospatial_source_current`, parcel/search/map projections. | Artifact SHA-256/version; identical checksum is no-op. Pointer moves only after validation and complete load. | Rows: `PENDING_EVIDENCE`; checksum/version: `PENDING_EVIDENCE`; synthetic fixture: `cadastre.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `FOREST_NOTIFICATIONS` | `RegistryIntegrationJobConfiguration`; `RegistryBulkRecoveryService`; `RegistryDataRepository`; `MetsaregisterWfsClient` | Source: `metsaregister:teatis`. Recovery stage/audit: `registry_sync_log`, `registry_bulk_sync_feature`, `cadastre_notification`; canonical/read: `forest_registry_feature`, notification API projections. | Per-cadastre/page resume and batch snapshot; stable provider feature identity; partial batch cannot replace prior known-good parcel snapshot. | Rows: `PENDING_EVIDENCE`; stable-ID checksum: `PENDING_EVIDENCE`; synthetic fixture: `metsaregister.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `FOREST_PLAN` | `RegistryIntegrationJobConfiguration`; `RegistryBulkRecoveryService`; `RegistryDataRepository`; `MetsaregisterWfsClient` | Source: `metsaregister:eraldis` family. Recovery stage/audit: `registry_sync_log`, `registry_bulk_sync_feature`; canonical/read: `forest_registry_feature` and forest-plan/compartment projections. | Same per-cadastre/page resume contract as notifications; stable feature IDs and complete batch publication. | Rows: `PENDING_EVIDENCE`; stable-ID checksum: `PENDING_EVIDENCE`; synthetic fixture: `metsaregister.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `METSAREGISTER_WFS_MIRROR` | `MetsaregisterWfsMirrorJobConfiguration`; `MetsaregisterWfsMirrorService`; `MetsaregisterWfsClient`; manifest/schema/catalog services | Source: manifest-approved WFS layers. Stage/generation: `metsaregister_wfs_layer`, `metsaregister_wfs_generation`, `metsaregister_wfs_feature`. Canonical/read: current WFS projections and `METSAREGISTER_FORESTRY` generation via `metsaregister_forestry_generation_member`. | Layer generation, page/startIndex and stable feature ID; only a complete validated layer may become current. | Per-layer rows/schema/ID checksum: `PENDING_EVIDENCE`; fixture: `metsaregister.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `OWNER_CADASTRE_RELATIONS` | `OwnerCadastreIntegrationJob`; `MetsisPortfolioClient`; `MetsisPortfolioSyncService` | Source: MetsIS/Forestek owner snapshot. Stage: `owner_portfolio_import_stage`, `owner_snapshot_row`, `owner_parcel_snapshot_row`. Canonical/read: `owner`, `land_parcel`, `owner_parcel_relation`, `ownership_relation_conflict`, `data_change_signal`. | Provider pagination until explicit completion; `metsis_portfolio_sync_checkpoint`; deterministic owner/cadastre keys; incomplete snapshot never reconciles canonical relations. | Owners/relations/source version: `PENDING_EVIDENCE`; fixture: `owner-flows.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `WEASEL_OWNERSHIP_TRANSITIONS` | `WeaselTransitionIntegrationJob`; `WeaselOwnershipClient`; `OwnershipTransitionService` | Source: `/owning-transitions`. Stage/checkpoint: provider pages plus `weasel_transition_sync_checkpoint`. Canonical/signal: `ownership_transition_event`, `data_change_signal`; owner relations are not silently rewritten. | Stable `source_event_id`; cursor plus overlap window; cursor advances only after canonical commit; duplicate source event is a no-op. | Events/cursor/source-ID checksum: `PENDING_EVIDENCE`; fixture: `owner-flows.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |
| `INHERITANCE_NOTICES` | `InheritanceNoticeIntegrationJob`; `InheritanceOfficialNoticeClient`; `InheritanceNoticeParser`; `InheritanceNoticeArchiveRepository`; `InheritanceNoticeIngestionService` | Source: date-based official XML. Archive/stage: `external_inheritance_notice`, `inheritance_notice_sync`, `inheritance_notice_import`. Canonical/read: `inheritance_case`, `inheritance_heir`, `inheritance_case_event` and workflow projections. | Date cursor from completed run metrics plus yesterday/today overlap; notice number is idempotency key; failed day cannot advance last-completed date. | Notices/date cursor/source-ID checksum: `PENDING_EVIDENCE`; fixture: `owner-flows.synthetic.json`; evidence: `SYNTHETIC_ONLY`. | `CODE_READY` → `SHADOW` (live evidence pending) |

## Upstream calls outside the job-key enum

| Call | Classification | Disposition | Guardrail |
| --- | --- | --- | --- |
| RIK `objektide_arv` SOAP in `ExternalRegistryService` | `REAL_TIME_DOMAIN_OPERATION` | `JAVA_EXCEPTION` | Contracted, authorization-bound due-diligence lookup. It is not a scheduler/bulk ETL path and must not consume an ingest-only credential. Keep `RIK_KINNISTUSRAAMAT_*` in the API runtime only while this exception is active. |
| Targeted Maa- ja Ruumiamet cadastral WFS lookup | `REAL_TIME_DOMAIN_OPERATION` | `JAVA_EXCEPTION` during migration | Bounded, user/request-driven lookup only. It must not become the nationwide canonical writer. |
| Basemap tiles and IN-ADS gazetteer | `REAL_TIME_DOMAIN_OPERATION` | `JAVA_EXCEPTION` | Display/search helper; never publishes canonical parcel generations. |
| Display-only Metsaregister WMS overlays | `REAL_TIME_DOMAIN_OPERATION` | `JAVA_EXCEPTION` | Map context only; business rules and canonical writes are prohibited. |
| EELIS download scripts | `RETIRED` from production ingestion until approved | `RETIRED` | Operator evidence only; no production table target exists yet. |

## Source contracts and parity

### Metsaregister family

- Source identity: approved layer name plus stable provider feature identity (`feature_id`/`sys_id`) and provider schema fingerprint.
- Paging: end only on explicit provider completion, empty page, or validated `numberMatched` completion; a configured page ceiling is a terminal visible failure.
- Canonical targets: `metsaregister_wfs_generation`, `metsaregister_wfs_feature`, projection tables, and the published `METSAREGISTER_FORESTRY` generation.
- Parity fields: per-layer count, stable IDs, schema hash, CRS, geometry validity/bounds, notification/compartment samples, pages and provider matched count.
- Publish: generation-safe. A failed or partial refresh cannot replace the current generation.

### Cadastre and context geospatial family

- Source identity: artifact URL, immutable content checksum/version, source key and snapshot timestamp.
- Unchanged checksum is a successful no-op and must not rewrite canonical rows.
- Canonical targets: `geospatial_source_run`, `geospatial_generation`, `geospatial_cadastre_parcel`, `geospatial_source_current` and existing read projections.
- Parity fields: row count, cadastral-code uniqueness, stable sample IDs, checksum, EPSG:3301 geometry validity and Estonia bounds.
- Publish: atomically change the source-current pointer only after bulk stage/load and validation.

### Owner, Weasel and inheritance family

- MetsIS/Forestek is a full portfolio snapshot. Provider page size is not a completion signal; the adapter follows provider pagination until the provider says complete.
- Owner relations preserve multi-owner cardinality, source provenance, manual-vs-provider conflict policy, end/reactivate history and immutable audit records.
- Weasel is incremental. The committed event cursor advances only after canonical commit; retries use stable source event IDs and a six-hour overlap by default.
- Inheritance uses a date cursor plus yesterday/today overlap. A failed day is retried and cannot advance the last-completed date.
- Parity fields: source IDs, active relation set, ended/reactivated relations, duplicate suppression, event/notices counts and selected business cases.

## Credential and cleanup rule

Java provider clients/handlers and API-side provider credentials are intentionally retained as rollback paths while every source is `CODE_READY`. Cleanup #366 is unlocked source-by-source only after the matrix state is `CUT_OVER`, the #370 checklist is complete, at least two successful Python-owned runs are recorded, and a tested rollback route is attached. `METSAREGISTER_FORESTRY_CANONICAL` is an internal derived projection and remains Java-owned because it performs no external provider ingestion.
