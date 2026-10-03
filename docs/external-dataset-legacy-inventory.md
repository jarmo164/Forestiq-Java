# Legacy `external_dataset_*` inventory

`external_dataset_snapshot_run`, `external_dataset_feature` and `external_dataset_current`
are retained only as migration artifacts. New public geospatial work must use the source
generation model described in `docs/public-data-ingestion-contract.md`.

## Reads and writes

| Component | Tables | Current status | Target state |
| --- | --- | --- | --- |
| `PublicDatasetBootstrapService.synchronizeAll()` | writes `external_dataset_snapshot_run`, `external_dataset_feature`, `external_dataset_current` | Migration-only; guarded by `forestiq.bootstrap.legacy-public-datasets-enabled=false` by default | Remove after any retained legacy snapshots are migrated or discarded |
| `PublicDatasetBootstrapService.projectToTenantTables()` | reads `external_dataset_current` and `external_dataset_feature`; writes `land_parcel`, `cadastre_notification`, `forest_registry_feature`, `registry_sync_log` | Migration-only; called only behind the same legacy flag | Remove after old bootstrap recovery is retired |
| `CadastreCsvImportService.importCsv()` | writes `external_dataset_snapshot_run`, `external_dataset_feature`, `external_dataset_current` | Migration-only implementation retained for bounded recovery | Replace with `CadastreGenerationImportService` if CSV remains a supported import format |
| `CadastreCsvImportController` | exposes `CadastreCsvImportService` | Disabled by default; endpoint exists only when `forestiq.bootstrap.legacy-public-datasets-enabled=true` | Remove with `CadastreCsvImportService` |
| `CadastreGeometryNormalizationIntegrationTest` | inserts/reads `external_dataset_feature/current` | Legacy migration coverage for historical geometry normalization | Remove when tables are dropped |
| Flyway migrations `V21`, `V28`, `V42` | create/alter/normalize legacy tables | Historical migrations; cannot be deleted from an existing Flyway chain | Supersede with a future drop migration after production retention/rollback approval |

## Decision

Do not migrate `external_dataset_*` into the generation model row-for-row. The target model
already exists:

- cadastre: `MARU_CADASTRE` source generations;
- Metsaregister provider mirror: `metsaregister_wfs_generation` and `metsaregister_wfs_feature`;
- EELIS: files-only until an import contract is approved.

The legacy tables may remain physically present until a later drop migration is planned, but
normal deployments must not expose active writers to `external_dataset_current`.

## Drop migration gate

A future removal migration must document:

1. production row counts and table sizes;
2. whether any retained snapshot is needed for rollback or audit;
3. expected lock behaviour for dropping foreign keys, triggers and tables;
4. a rollback plan that restores only the removed legacy tables, not the whole database;
5. confirmation that no deployed process has the legacy flag enabled.
