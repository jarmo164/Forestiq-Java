# Terrain prototype benchmark

**Date:** 2026-09-08  
**Related:** #233, #254  
**Prototype:** `scripts/terrain/parcel_terrain_metrics.py`

## Purpose

Measure the bounded parcel calculation cost and the 5 m versus 10 m resolution tradeoff before any production raster architecture is introduced.

This is a deterministic developer-runtime benchmark, not a production SLO. It uses an in-memory EPSG:3301 GeoTIFF with a smooth plane plus low-frequency terrain undulation and square parcel windows. The same calculation path used by the prototype is exercised: raster clip, nodata masking, elevation statistics and slope gradient calculation.

## Results

Each case was executed 20 times after the in-memory raster was created.

| Resolution | Parcel window | Sample cells | Median calculation | p95 calculation |
| --- | ---: | ---: | ---: | ---: |
| 5 m | 500 × 500 m | 10,000 | 3.32 ms | 8.19 ms |
| 5 m | 1,000 × 1,000 m | 40,000 | 6.96 ms | 13.67 ms |
| 5 m | 3,000 × 3,000 m | 360,000 | 41.10 ms | 67.35 ms |
| 10 m | 500 × 500 m | 2,500 | 3.15 ms | 27.01 ms* |
| 10 m | 1,000 × 1,000 m | 10,000 | 3.02 ms | 5.08 ms |
| 10 m | 3,000 × 3,000 m | 90,000 | 7.53 ms | 14.97 ms |

`*` The 500 m / 10 m p95 result contained runtime noise; median and larger-window results are more representative of the algorithmic difference.

For the 1 km parcel, 10 m uses one quarter of the cells and reduced measured median compute from ~7 ms to ~3 ms. For the 3 km parcel it reduced cells from 360k to 90k and measured median compute from ~41 ms to ~7.5 ms.

## Resolution accuracy check

On the same smooth synthetic terrain surface, 5 m and 10 m produced nearly identical parcel slope statistics for the tested windows:

- 500 m window mean slope: 0.682° (5 m) vs 0.681° (10 m);
- 1 km window mean slope: 0.789° (5 m) vs 0.788° (10 m);
- 3 km window mean slope: 0.918° (5 m) vs 0.919° (10 m).

This does **not** prove equivalence on sharp embankments, ditches or small steep features. It demonstrates that 10 m is suitable for coarse screening on smooth terrain, while 5 m retains the preferred default for parcel evaluation.

## Preprocessing cost

The prototype itself requires no preprocessing beyond having a valid local GeoTIFF and EPSG:3301 parcel geometry. Production source preparation would consist of:

1. download/checksum the selected Maa- ja Ruumiamet DTM generation;
2. keep the raster in runtime/object storage rather than Git or PostgreSQL;
3. use windowed reads per parcel/batch;
4. persist only compact metrics and source provenance where interactive reuse is justified.

The published nationwide compressed source sizes (~7.7 GB for 5 m and ~2 GB for 10 m) make both approaches operationally small compared with a national raw LiDAR mirror.

## Decision reinforced by benchmark

- **5 m DTM:** default for parcel-level terrain metrics.
- **10 m DTM:** acceptable for coarse portfolio screening when throughput matters more than local detail.
- **1 m DTM/LiDAR:** bounded specialist analysis only; no national production import is justified by current user stories.
- Computation must stay in a worker/batch boundary; interactive APIs should read persisted compact metrics rather than synchronously processing national raster data.

Before enabling persisted production metrics, run the same tool against a small real Maa- ja Ruumiamet parcel set representing flat, rolling and steep terrain and compare results with a GIS reference calculation. That validation is an activation gate, not a reason to introduce national raster storage now.
