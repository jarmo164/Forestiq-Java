# Legacy first-run external data bootstrap

This is a migration-only runner. For canonical public-source ownership and current operator
commands, start with [`public-data-ingestion-contract.md`](public-data-ingestion-contract.md).

The old public WFS catalogue import is disabled by default. It runs only when
`FORESTIQ_BOOTSTRAP_LEGACY_PUBLIC_DATASETS_ENABLED=true` is set explicitly for bounded
migration or recovery work.

ForestIQ should run a one-shot external-data bootstrap after PostgreSQL/Flyway is available and before normal application schedulers are enabled. The bootstrap is deliberately separate from the incremental scheduled integrations.

## What it imports

1. **Maa-amet / Kataster WFS** — complete `kataster:ky_kehtiv` dataset when the legacy public flag is enabled.
2. **Metsaregister WFS** — all public WFS datasets currently published by Keskkonnaagentuur when the legacy public flag is enabled, not only the two layers used by ForestIQ's incremental registry sync:
   - `metsaregister:eraldis`
   - `metsaregister:eraldis_element`
   - `metsaregister:isearasused`
   - `metsaregister:kahjustused`
   - `metsaregister:katsealad`
   - `metsaregister:kl_kasvukoht`
   - `metsaregister:kl_omandivorm`
   - `metsaregister:kl_rinne`
   - `metsaregister:kl_puuliik`
   - `metsaregister:mr__teema_raie_diameeter`
   - `metsaregister:mr__teema_raie_liik`
   - `metsaregister:mr__teema_raie_taius`
   - `metsaregister:mr__teema_raie_vanus`
   - `metsaregister:natura_2000_alad`
   - `metsaregister:yp_kaitsemets`
   - `metsaregister:mke_arhiiv`
   - `metsaregister:mue`
   - `metsaregister:kuusekooreyrask_mke`
   - `metsaregister:mke`
   - `metsaregister:teatis_arhiiv`
   - `metsaregister:teatis`
3. **MetsIS / Forestek** — complete configured owner-to-cadastre portfolio snapshot.
4. **Weasel ownership transitions** — imported when the existing Weasel integration is configured.
5. **Ametlikud Teadaanded inheritance notices** — every parsed notice from the latest 365 days by default. The complete feed is archived first; notices matching ForestIQ owners are additionally imported into the operational inheritance workflow.

RIK Kinnistusraamat is intentionally not treated as a nationwide bootstrap feed. The current RIK integration is a contracted per-subject SOAP lookup (`objektide_arv`), not a bulk-dataset interface.

## Storage model

When explicitly enabled for migration, nationwide public WFS data is stored once in shared snapshot tables instead of being duplicated for every ForestIQ organization:

- `external_dataset_snapshot_run` — progress/checkpoint and failure information.
- `external_dataset_feature` — raw GeoJSON feature properties/geometry for a snapshot.
- `external_dataset_current` — atomically points each source to the latest complete snapshot.
- `external_inheritance_notice` — shared archive of every parsed inheritance notice downloaded during the configured backfill window.

A WFS page and its `next_start_index` checkpoint are committed in the same database transaction. Page rows are written as a database batch. A failed or interrupted import therefore resumes from the last committed page. The provider's one-request row limit is not used as the dataset limit: the importer repeatedly calls WFS 2.0 `count` + `startIndex` until the complete dataset has been received. Deterministic `sortBy` ordering is retained on the operational cadastral/forest layers where the existing integration already defines a stable sort key. Auxiliary Metsaregister layers are validated against WFS `numberMatched` before a snapshot can be published.

A short WFS page is never assumed to mean end-of-data because GeoServer can enforce a lower server-side page size than the requested `count`. If `numberMatched` is unavailable, the importer continues until it receives an empty page. If a completed download's distinct staged-row count does not match `numberMatched`, the partial snapshot is cleared and its checkpoint is reset for a clean retry instead of publishing incomplete data.

Only after all pages of one source are present is that snapshot published as current. Previous complete feature snapshots are removed by default after the new one is published.

After Forestek has created/refreshed the tenant owner/cadastre relations, legacy public snapshots are projected into the existing operational tables (`land_parcel`, `cadastre_notification`, `forest_registry_feature`) and corresponding successful `registry_sync_log` entries only when `FORESTIQ_BOOTSTRAP_LEGACY_PUBLIC_DATASETS_ENABLED=true`. Normal operation must use the canonical commands in the public data ingestion contract.

## Run on a server

From the repository root:

```bash
bash scripts/bootstrap_external_data.sh
```

The command starts a one-shot Spring Boot process on an ephemeral port and sets `FORESTIQ_SCHEDULING_ENABLED=false`, so normal scheduled jobs cannot race the bootstrap. With default settings it skips the legacy public WFS catalogue loader and runs only the configured private-source bootstrap work.

To run the legacy public WFS catalogue loader for a documented migration or recovery:

```bash
FORESTIQ_BOOTSTRAP_LEGACY_PUBLIC_DATASETS_ENABLED=true \
  bash scripts/bootstrap_external_data.sh
```

For a built production JAR:

```bash
FORESTIQ_BOOTSTRAP_JAR=/app/forestiq-api.jar \
  bash scripts/bootstrap_external_data.sh
```

Inside the production Docker/Render container, where the repository-level helper script is not copied into the image, run the existing entrypoint as a one-shot bootstrap process:

```bash
FORESTIQ_BOOTSTRAP_ENABLED=true \
FORESTIQ_SCHEDULING_ENABLED=false \
SERVER_PORT=0 \
METSIS_PORTFOLIO_MAX_PAGES_PER_RUN=10000 \
/app/docker-entrypoint.sh
```

Run this before the normal ForestIQ web process. Do not start it alongside an already running scheduler-enabled application process.

For an installation with multiple ForestIQ organizations, select the tenant whose Forestek service account is being bootstrapped:

```bash
FORESTIQ_BOOTSTRAP_ORGANIZATION_ID=<organization-uuid> \
  bash scripts/bootstrap_external_data.sh
```

## Required configuration

The normal database and external-integration environment variables still apply. In particular, configure the existing Forestek/MetsIS and Ametlikud Teadaanded source settings before running the bootstrap. By default private required sources are fail-fast: missing Forestek or inheritance configuration makes the command return a non-zero exit code instead of silently starting ForestIQ with incomplete data.

For development or a public-data-only recovery, this can be overridden explicitly:

```bash
FORESTIQ_BOOTSTRAP_REQUIRE_PRIVATE_SOURCES=false \
  bash scripts/bootstrap_external_data.sh
```

## Tuning large imports

Defaults are conservative and resumable:

```text
FORESTIQ_BOOTSTRAP_PAGE_SIZE=10000
FORESTIQ_BOOTSTRAP_MAX_PAGES_PER_DATASET=100000
FORESTIQ_BOOTSTRAP_MIN_REQUEST_INTERVAL_MS=100
FORESTIQ_BOOTSTRAP_INHERITANCE_DAYS=365
FORESTIQ_BOOTSTRAP_CLEANUP_PREVIOUS_SNAPSHOTS=true
METSIS_PORTFOLIO_MAX_PAGES_PER_RUN=10000
```

`FORESTIQ_BOOTSTRAP_PAGE_SIZE` is capped at 1,000,000 rows per WFS request. Keeping it at 10,000–50,000 is normally preferable because it bounds JVM memory usage and gives finer resume checkpoints for multi-million-row layers.

The server bootstrap script raises the normal Forestek/MetsIS page safety cap to 10,000 pages by default. Forestek's legacy endpoint does not accept ForestIQ's configured page size, so the client continues paging until the provider returns an empty page. Hitting the safety cap is treated as an error rather than accepting a partial owner/cadastre snapshot.

The 365-day inheritance backfill is split into windows accepted by `forestiq.inheritance-source.max-backfill-days` (31 days by default), so the operational integration retains its normal safety limit while the bootstrap covers the complete year.

## Re-running

The bootstrap is designed to be re-runnable. A failed WFS snapshot resumes from its stored checkpoint. After a successful refresh a new complete snapshot becomes current atomically; a partially downloaded replacement never replaces the previous complete snapshot. Inheritance notices are upserted by notice number, so re-running the same date range does not create duplicates.

Do not run two bootstrap processes against the same database at the same time.
