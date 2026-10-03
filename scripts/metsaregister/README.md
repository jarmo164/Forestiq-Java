# Metsaregister WFS CSV tools

Offline Python tooling for downloading the public Metsaregister WFS mirror to CSV snapshots and loading those snapshots into ForestIQ PostGIS. The scripts use the same deterministic identities and generation-safe publication model as the Java ingestion path.

The Java `MetsaregisterWfsMirrorService` is the canonical normal-operation writer because it
is connected to the ForestIQ admin UI, scheduler, layer manifest and source-generation state.
Use these Python scripts only for offline evidence capture, emergency generation import or
operator-led recovery when the Java mirror is intentionally stopped.

Tracked by #162, #163, #164 and #165.

## Setup

From the repository root:

```bash
python3 -m venv .venv-metsaregister
source .venv-metsaregister/bin/activate
pip install -r scripts/metsaregister/requirements.txt
```

On Windows PowerShell activate with:

```powershell
.\.venv-metsaregister\Scripts\Activate.ps1
```

## Export one layer to CSV

```bash
python scripts/metsaregister/export_wfs_csv.py \
  --layer metsaregister:eraldis \
  --output-dir ./data/metsaregister
```

Layer names without the `metsaregister:` prefix are accepted too.

## Export every supported public layer

```bash
python scripts/metsaregister/export_wfs_csv.py \
  --all \
  --output-dir ./data/metsaregister
```

The checked-in `layers.json` currently contains all 23 provider-advertised layers observed on 2026-09-01. `metsaregister:raadatud_metsateatised` is recorded but disabled because anonymous `GetFeature` returned HTTP 401. `--all` skips disabled/unapproved layers.

Large layers are read with WFS 2.0 `count` + `startIndex`, deterministic `sortBy`, bounded retries and an on-disk duplicate-key index. Rows are appended directly to CSV, so a multi-million-row layer is not held in memory.

Each export produces:

- `<layer>.partial.csv` while work is in progress;
- `<layer>.manifest.json` with checkpoint, identity fields, row counts and status;
- `<layer>.keys.sqlite` as a temporary on-disk uniqueness index;
- `<layer>.csv` only after the provider/local count is complete and verified.

If a run stops, execute the same command again. The exporter rebuilds its checkpoint from the partial CSV and resumes from that WFS `startIndex`. Use `--overwrite` to deliberately discard an existing snapshot and start from zero.

## CSV contract

Columns are:

`layer_name, feature_key, source_id, provider_feature_id, source_hash, properties_json, geometry_geojson`

`feature_key` matches the Java mirror identity format, including length-prefixed composite identities. `properties_json` is canonical JSON. `geometry_geojson` is blank for non-spatial rows and canonical GeoJSON for spatial rows. The completed manifest contains a SHA-256 of the logical uncompressed CSV.

## Validate before importing

No database connection is needed for a dry run:

```bash
python scripts/metsaregister/import_csv_postgis.py \
  ./data/metsaregister \
  --dry-run
```

This checks manifest completion, CSV SHA-256, row count, source hashes, JSON structure and duplicate feature keys.

## Import into local ForestIQ PostGIS

With the local Docker database from the repository `.env`:

```bash
python scripts/metsaregister/import_csv_postgis.py \
  ./data/metsaregister \
  --compose-env .env \
  --batch-size 5000
```

Or import one file:

```bash
python scripts/metsaregister/import_csv_postgis.py \
  ./data/metsaregister/metsaregister__eraldis.csv
```

The importer requires Flyway migrations to be applied. It streams bounded batches into a new `STAGING` row in `metsaregister_wfs_generation`. The layer's `current_generation_id` is switched only after every row is present and verified. The previous published generation is then marked `RETIRED`. A failed import therefore cannot replace last-known-good data.

The importer acquires the PostgreSQL session advisory lock `forestiq:metsaregister:wfs-mirror`, exactly the same lock used by the canonical Java WFS mirror job. If the scheduler, a manual Java mirror run or another CSV import already owns that lock, the importer fails immediately instead of racing a second publication. Stop the Java scheduler/admin mirror deliberately before using the Python importer against a production database.

If the layer already has a resumable staging generation, the importer refuses to mix the CSV with it. Use `--replace-staging` only when you have explicitly decided that the existing Java/Python checkpoint can be discarded.

## Compress completed snapshots

For long-term storage, completed CSV files may be gzip-compressed after export. Keep the sidecar manifest beside the compressed file:

```bash
gzip ./data/metsaregister/metsaregister__eraldis.csv
python scripts/metsaregister/import_csv_postgis.py \
  ./data/metsaregister/metsaregister__eraldis.csv.gz \
  --dry-run
```

The importer hashes the decompressed CSV stream, so the exporter-created `csv_sha256` remains valid after gzip compression. Do not gzip `.partial.csv` files because they are resumable working files. Store national exports outside the Git checkout or on a dedicated data volume; generated `.csv`, `.csv.gz`, partial files and temporary key indexes must never be committed.

## Remote database

Use `DATABASE_URL` from the target environment or pass `--database-url`. Never put database passwords, WFS credentials or generated CSV files in Git.

## Tests

```bash
pip install -r scripts/metsaregister/requirements-dev.txt
pytest -q scripts/metsaregister/tests
```

The CI tests use mocked WFS responses plus a local PostGIS service. Normal CI never downloads the live national datasets. It covers paging, resume, transient retry, provider count drift, pagination no-progress, duplicate identities, gzip integrity, spatial/null geometry, failed publication safety, staging replacement, repeated snapshot imports and the shared Java/Python mirror lock.

## Manual live smoke

Use a small anonymous layer to verify the current upstream service without putting a live government dependency into normal CI:

```bash
rm -rf /tmp/forestiq-metsaregister-smoke
python scripts/metsaregister/export_wfs_csv.py \
  --layer metsaregister:kl_rinne \
  --output-dir /tmp/forestiq-metsaregister-smoke \
  --page-size 100 \
  --overwrite

python scripts/metsaregister/import_csv_postgis.py \
  /tmp/forestiq-metsaregister-smoke \
  --dry-run
```

`.github/workflows/metsaregister-wfs-probe.yml` also contains a deliberately separate live `csv-smoke` job. It validates anonymous `resultType=hits` access for every approved/enabled catalogue layer and performs the small `kl_rinne` WFS-to-CSV smoke. It is not part of normal CI and can be run manually when upstream validation is needed.

For an operational full export, use `--all` on a host with enough disk space and allow it to resume after provider interruptions. The exporter verifies every completed layer against its WFS `numberMatched` value when the provider supplies one.

## Operational notes

- Keep large CSV files on a volume with enough free disk space; several layers contain hundreds of thousands or millions of rows.
- A completed CSV is immutable evidence of one provider snapshot. Keep its sidecar manifest with it.
- If `numberMatched` changes during a WFS run, export fails closed instead of publishing a mixed-time snapshot.
- If duplicate deterministic identities appear, export/import fails rather than silently overwriting data.
- Import validation happens before PostGIS writes. PostGIS geometry parsing is the final authority for malformed geometry.
- Old `RETIRED` WFS generations are cleaned by the application retention policy; current and staging generations are not removed by that cleanup.
