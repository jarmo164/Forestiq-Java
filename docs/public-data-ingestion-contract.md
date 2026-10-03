# Public data ingestion contract

ForestIQ has one canonical writer per public source class. A non-canonical importer may keep
staging evidence or migration artifacts, but it must not publish competing application data.

## Source ownership matrix

| Source class | Canonical command or owner | Storage target | Writes canonical application data | Scheduler/admin entry point | Rollback behaviour | Non-canonical paths |
| --- | --- | --- | --- | --- | --- | --- |
| Maa- ja Ruumiamet cadastre nationwide data | `CadastreSnapshotDownloadCli` followed by `CadastreGenerationImportService` | `geospatial_source_run`, `geospatial_generation`, generation-scoped cadastre tables | Yes, by publishing a validated `MARU_CADASTRE` generation | Cadastre snapshot check/import control plane | Switch `geospatial_source_current` back to a retained known-good generation | Old WFS bootstrap is migration-only and disabled by default |
| Maa- ja Ruumiamet parcel action | Local current-generation lookup; optional targeted WFS diagnostic only | Published cadastre generation for canonical reads; diagnostic evidence only for WFS probes | No direct live-WFS overwrite | Parcel UI/API diagnostic action when retained | No publication change; failed diagnostics leave current generation unchanged | `MAAAMET_WFS` per-parcel refresh must not become a canonical writer |
| Metsaregister complete public WFS layers | Java `MetsaregisterWfsMirrorService` | `metsaregister_wfs_layer`, `metsaregister_wfs_generation`, `metsaregister_wfs_feature` | Yes, for provider mirror lineage only; business reads use published forestry generations | `/api/v1/admin/integrations/metsaregister-wfs` and scheduled mirror job | Keep last-known-good layer generation; failed mirror run does not publish | `scripts/metsaregister` is offline/emergency import tooling only |
| EELIS public WFS layers | `scripts/eelis/download_wfs_layer.py` | Files plus `manifest.tsv` | No | Operator-run file download only | Re-run from page files; compare before/after hits | No production import until approved layer/storage decision in #325 |
| Forestek/MetsIS owner-cadastre portfolio | `OWNER_CADASTRE_RELATIONS` integration job | Tenant ownership and relation tables | Yes, tenant business relations | Integration scheduler/admin job and migration bootstrap runner | Re-run source snapshot; failed run must not delete existing relations | Not a public source replacement for cadastre geometry |
| Ametlikud Teadaanded inheritance notices | `INHERITANCE_NOTICES` integration job | `external_inheritance_notice` archive and inheritance workflow tables | Yes, inheritance workflow state | Integration scheduler/admin job and migration bootstrap runner | Notices are upserted by notice number | Not related to public geospatial generation ownership |

## Rules

1. A public source may have only one active canonical writer.
2. Canonical public geospatial data is selected by source/generation pointers, not by latest
   row timestamp or per-parcel WFS audit history.
3. Live WFS requests are acceptable for diagnostics, file downloads or staging evidence only
   unless the source contract explicitly names them as the canonical writer.
4. A failed public-source import must leave the previous known-good published generation
   readable.
5. Legacy `external_dataset_*` snapshots are migration artifacts. Their active endpoints and
   public WFS projection paths are disabled by default; see
   [`external-dataset-legacy-inventory.md`](external-dataset-legacy-inventory.md).

## Operator commands

### Cadastre

Use the cadastre snapshot workflow documented in
[`docs/geospatial/cadastre-wfs-to-snapshot-migration.md`](geospatial/cadastre-wfs-to-snapshot-migration.md).
Do not use the first-run public WFS bootstrap for normal cadastre refreshes.

Freshness for cadastre is derived from the current `MARU_CADASTRE` source generation when it
exists. Old `registry_sync_log.registry_name='MAAAMET_WFS'` rows remain readable as legacy WFS
evidence only; new per-parcel Maa-amet WFS actions are diagnostic and must not make parcel data
appear canonically fresh.

### Metsaregister

Use the Java admin/scheduler mirror as the canonical normal-operation writer. The Python
`scripts/metsaregister` path is retained for offline/emergency generation import only and must
not run concurrently with the Java mirror against the same database.

### EELIS

Download layer files with:

```bash
python scripts/eelis/download_wfs_layer.py \
  eelis:natura_elupaik \
  sys_id \
  ./data/eelis/natura_elupaik_attrs \
  --page-size 100000 \
  --properties sys_id,versioon,id,pohityyp,andmestik,invent_kpv
```

EELIS downloads are not imported into ForestIQ production tables until issue #325 approves the
layer list, sensitive-data policy and storage target.

### Legacy migration bootstrap

`scripts/bootstrap_external_data.sh` no longer runs the old public WFS catalogue loader unless
`FORESTIQ_BOOTSTRAP_LEGACY_PUBLIC_DATASETS_ENABLED=true` is set explicitly. That flag is for
bounded migration/recovery work only. It must not be used as a normal deployment bootstrap or
scheduled refresh mechanism.
