# Terrain and elevation data decision

**Status:** Accepted for prototype / production import deferred  
**Date:** 2026-09-08  
**Issues:** #233, #254  
**Architecture parent:** #225

## Decision

ForestIQ will **not** import a nationwide LiDAR point cloud or a nationwide raster dataset into PostGIS as part of the current product baseline.

For parcel-level terrain intelligence the preferred source is Maa- ja Ruumiamet **DTM (Digital Terrain Model)** in EPSG:3301 / EH2000. The default analytical resolution is **5 m**. A 10 m DTM is acceptable for coarse portfolio screening; 1 m data is reserved for bounded, explicitly justified parcel/map-sheet analysis.

The target production shape, if terrain metrics are activated, is:

```text
Maa- ja Ruumiamet DTM GeoTIFF
  -> bounded download / object-storage copy
  -> terrain worker (windowed raster reads, EPSG:3301)
  -> parcel metrics + source provenance
  -> small ForestIQ read model / domain API
```

Large raster or LiDAR payloads must not travel through normal synchronous REST responses.

## User stories retained

Terrain metrics are justified only for bounded forestry/land evaluation workflows:

1. **Evaluator / qualification:** before field work or valuation, flag parcels whose terrain may materially affect access, harvesting or logistics.
2. **Portfolio screening:** rank parcels that need manual review because terrain is unusually steep or elevation varies materially inside the parcel.

The initial retained metrics are:

- minimum elevation;
- maximum elevation;
- mean elevation;
- mean slope;
- maximum slope.

Aspect distribution, raw LiDAR-derived metrics and canopy-height analysis are **not** part of the first production scope. CHM/nDSM may be evaluated later under a separate forestry-data-quality use case.

## Official source inventory

Maa- ja Ruumiamet publishes elevation data as open data.

Relevant products as checked on 2026-09-08:

- DTM: 1 m, 5 m and 10 m GeoTIFF/XYZ map-sheet products;
- nationwide DTM 5 m GeoTIFF: approximately **7.7 GB**;
- nationwide DTM 10 m GeoTIFF: approximately **2 GB**;
- nationwide DTM 25 m GeoTIFF: approximately **342.9 MB**;
- DSM/nDSM: 1 m and 5 m;
- CHM: 4 m for newer data and 10 m for older data;
- raw LiDAR data is available separately.

The official elevation-data documentation states:

- horizontal CRS: **EPSG:3301 (L-EST97)**;
- vertical height system: **EH2000**;
- current DEM products are derived from ALS generations and source generation/date must be retained with results.

Source references:

- https://geoportaal.maaamet.ee/est/ruumiandmed/korgusandmed/korgusmudelid-p508.html
- https://geoportaal.maaamet.ee/est/Ruumiandmed/Korgusandmed/Laadi-korgusandmed-alla-p614.html
- https://geoportaal.maaamet.ee/eng/Spatial-Data/Elevation-data-p308.html

ForestIQ must store the actual provider/source date or generation of the raster used for each calculated metric set. The repository must never contain downloaded national raster/LiDAR data.

## Alternatives

### A. Provider map-sheet access + ephemeral processing

Download only map sheets intersecting requested parcels, process in a worker, then discard or keep in a bounded cache.

**Advantages**

- minimum stored volume;
- good fit for rare terrain calculations;
- no national raster lifecycle to operate.

**Disadvantages**

- provider availability affects first calculation;
- repeated portfolios may redownload the same sheets;
- map-sheet selection and cache lifecycle add operational code.

**Use when:** terrain remains an occasional feature.

### B. Nationwide 5 m DTM in object storage + windowed worker reads

Keep the official 5 m GeoTIFF generation in an object-storage-compatible boundary and use GDAL/rasterio windowed reads in a background worker.

**Advantages**

- deterministic calculations independent of provider uptime after ingestion;
- 7.7 GB is operationally modest;
- easy rollback by object/source generation;
- no need to teach the transactional database raster semantics.

**Disadvantages**

- requires source-generation download/checksum lifecycle;
- worker needs raster tooling and temporary disk/cache capacity.

**Use when:** terrain calculations become a normal evaluation/portfolio capability.

### C. Nationwide raster in PostGIS Raster

Store national DTM tiles in the application database and calculate metrics with database raster functions.

**Advantages**

- SQL-visible spatial joins;
- one database security/backup boundary.

**Disadvantages**

- increases database size, backup/restore time and operational coupling;
- raster workload competes with interactive cadastral/CRM queries;
- adds a database capability that ForestIQ does not otherwise need.

**Decision:** rejected for the current architecture.

### D. Precomputed parcel metrics only

Calculate metrics after a cadastral/source generation changes and retain only compact parcel metrics plus provenance in PostgreSQL.

**Advantages**

- fastest interactive reads;
- tiny payloads;
- no raster work on user request threads.

**Disadvantages**

- requires recalculation scheduling and stale-data semantics;
- full-country recalculation is wasteful if terrain is rarely used.

**Decision:** recommended read-model shape once the feature is productized, fed by A or B.

## Data volume and planning cost

Known official compressed dataset sizes are enough to rule out a large-data platform as a prerequisite:

| Product | Published size | Planning implication |
| --- | ---: | --- |
| Nationwide DTM 25 m | ~342.9 MB | coarse experiments only |
| Nationwide DTM 10 m | ~2 GB | cheap portfolio screening |
| Nationwide DTM 5 m | ~7.7 GB | preferred production analytical source |
| Nationwide DSM 5 m generations | ~2.9–3.2 GB | separate surface-height use case |
| Nationwide nDSM 5 m generations | ~2.5–3.5 GB | separate surface-height use case |

For architecture comparison only, using a planning assumption of **€0.025/GB-month** for commodity object storage (not a vendor quote):

- 5 m DTM storage: about **€0.19/month** before request/egress costs;
- 10 m DTM storage: about **€0.05/month** before request/egress costs.

Therefore storage cost is not the main decision factor. Worker CPU, data refresh, observability and product value matter more.

## Prototype

The repository contains:

- `scripts/terrain/parcel_terrain_metrics.py`
- `scripts/terrain/requirements.txt`

The prototype:

- accepts a local DTM GeoTIFF;
- requires raster and parcel geometry in EPSG:3301;
- clips processing to one Polygon/MultiPolygon;
- calculates min/max/mean elevation;
- calculates mean/max slope from raster gradients;
- emits source, source date, resolution, horizontal CRS and vertical datum in JSON;
- fails closed on CRS or declared-resolution mismatch;
- includes a deterministic in-memory self-test.

Run the deterministic test:

```bash
python3 -m venv .venv-terrain
. .venv-terrain/bin/activate
pip install -r scripts/terrain/requirements.txt
python scripts/terrain/parcel_terrain_metrics.py --self-test
```

Expected fixture result:

```json
{
  "horizontal_crs": "EPSG:3301",
  "vertical_datum": "EH2000",
  "resolution_m": 5.0,
  "sample_cells": 36,
  "elevation_m": {"min": 100.0, "max": 115.0, "mean": 107.5},
  "slope_degrees": {"mean": 24.095, "max": 24.095, "sample_cells": 36}
}
```

Run against a real downloaded DTM and one EPSG:3301 parcel geometry:

```bash
python scripts/terrain/parcel_terrain_metrics.py \
  --dtm var/terrain/dtm_5m.tif \
  --parcel-geojson var/terrain/parcel.geojson \
  --parcel-crs EPSG:3301 \
  --resolution-m 5 \
  --source-date "<provider generation/date>"
```

## Accuracy and limitations

- DTM represents terrain, not canopy/building surface.
- Slope is derived from the selected grid resolution; 5 m and 10 m results are not interchangeable for steep/local features.
- Raster edge/nodata cells are excluded; a parcel with no valid cells fails explicitly.
- The prototype does not reproject input. This is intentional: ForestIQ canonical parcel geometry is already EPSG:3301, preventing silent axis/CRS mistakes.
- Real production validation should compare a small flat, rolling and steep parcel set against GIS-tool reference calculations before enabling persisted metrics.

## Security, privacy and operations

- elevation rasters are public source data, but parcel work queues and ownership/business context remain ForestIQ-authorized data;
- worker logs must use source generation and aggregate counters, not owner/person identifiers;
- downloaded `.tif/.tiff/.las/.laz` files belong in runtime/object storage, never Git;
- production artifacts need checksum, source date, acquisition time and last-known-good generation semantics;
- a failed terrain refresh must not invalidate cadastral/domain data or block the main application.

## Final architecture decision

1. **Do not start a production-scale national LiDAR import.**
2. **Do not introduce PostGIS Raster now.**
3. Use **5 m DTM** as the default terrain-analysis source if the product capability is activated.
4. Start with bounded worker processing; move to a nationwide object-storage copy only when repeated usage justifies it.
5. Persist compact parcel metrics + provenance for interactive use rather than serving rasters synchronously.
6. Keep #233 optional: it may be enabled later without blocking the delivered cadastral/map architecture.
