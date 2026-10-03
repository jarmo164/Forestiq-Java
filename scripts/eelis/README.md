# EELIS WFS downloads

Python tooling for downloading one public EELIS WFS layer at a time without the ForestIQ Java
backend. The downloader uses the canonical endpoint:

```text
https://gsavalik.envir.ee/geoserver/eelis/wfs
```

It first stores a `resultType=hits` response, downloads stable WFS 2.0 pages with `count`,
`startIndex` and `sortBy`, writes each page as a separate file, and checks `numberMatched`
again after the run. If the count changes, the run fails so a mixed-time snapshot is not
treated as clean evidence.

## Setup

```bash
python3 -m venv .venv-eelis
source .venv-eelis/bin/activate
pip install -r scripts/eelis/requirements.txt
```

## Consumer decision

EELIS data is currently approved as file evidence only. ForestIQ does not import these files
into production tables or expose them in the UI until a later source-generation/domain-table
contract is accepted.

The approved representative layer presets live in `approved_layers.json`:

- `natura_elupaik_attributes` downloads selected attributes only and excludes geometry.
- `natura_elupaik_geopackage` downloads GeoPackage pages for operator-led spatial analysis.

Both presets keep `production_import_enabled=false`. Sensitive or legally restricted data must
not be enabled for production import or UI display from this tooling alone.

## Fast attribute CSV

```bash
python scripts/eelis/download_wfs_layer.py \
  eelis:natura_elupaik \
  sys_id \
  ./data/eelis/natura_elupaik_attrs \
  --page-size 100000 \
  --property sys_id \
  --property versioon \
  --property id \
  --property pohityyp \
  --property andmestik \
  --property invent_kpv
```

Existing non-empty `page_*.csv` files are reused, so a failed run can be resumed by repeating
the same command. Use `--overwrite` only when intentionally discarding previous pages.

For a bounded live smoke, add `--max-pages 1` and a temporary output directory. This validates
the current upstream endpoint without downloading the full layer.

## GeoPackage pages

For spatial analysis, use GeoPackage instead of CSV with WKT geometry:

```bash
python scripts/eelis/download_wfs_layer.py \
  eelis:natura_elupaik \
  sys_id \
  ./data/eelis/natura_elupaik_gpkg \
  --format geopkg \
  --page-size 25000
```

Each page is a standalone `.gpkg`; merge later with QGIS or GDAL instead of building one huge
download response.

## Outputs

- `hits_before.xml`
- `hits_after.xml`
- `manifest.tsv`
- `page_000000000000.csv` or `.gpkg`

`data/` is git-ignored; do not commit downloaded provider data.

## Validate a download

Run dry-run validation before treating downloaded pages as evidence:

```bash
python scripts/eelis/validate_download.py \
  ./data/eelis/natura_elupaik_attrs \
  --layer natura_elupaik_attributes
```

For a bounded `--max-pages` smoke, add `--allow-partial`:

```bash
python scripts/eelis/validate_download.py \
  /tmp/forestiq-eelis-smoke \
  --layer natura_elupaik_attributes \
  --allow-partial
```

The validator checks the approved layer contract, before/after `numberMatched`, manifest rows,
HTTP status, page offsets, file extensions and non-empty page files. It refuses any contract
that enables production import.

## Tests

```bash
pip install -r scripts/eelis/requirements-dev.txt
pytest -q scripts/eelis/tests
```
