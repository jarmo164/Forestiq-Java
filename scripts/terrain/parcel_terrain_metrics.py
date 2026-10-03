#!/usr/bin/env python3
"""Prototype parcel terrain metrics for Maa- ja Ruumiamet DTM GeoTIFF.

This tool is intentionally outside the ForestIQ request path. It evaluates a
bounded EPSG:3301 parcel polygon against a local DTM GeoTIFF and emits compact
JSON metrics suitable for architecture/feasibility validation.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np
import rasterio
from rasterio.io import DatasetReader, MemoryFile
from rasterio.mask import mask
from rasterio.transform import from_origin

CANONICAL_EPSG = 3301
DEFAULT_VERTICAL_DATUM = "EH2000"
DEFAULT_SOURCE = "Maa- ja Ruumiamet"


def _extract_geometry(payload: dict[str, Any]) -> dict[str, Any]:
    payload_type = payload.get("type")
    if payload_type == "FeatureCollection":
        features = payload.get("features") or []
        if len(features) != 1:
            raise ValueError("FeatureCollection must contain exactly one parcel feature")
        geometry = features[0].get("geometry")
    elif payload_type == "Feature":
        geometry = payload.get("geometry")
    else:
        geometry = payload

    if not isinstance(geometry, dict) or geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        raise ValueError("Parcel must be a GeoJSON Polygon or MultiPolygon")
    return geometry


def _load_geometry(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return _extract_geometry(json.load(handle))


def _assert_spatial_contract(dataset: DatasetReader, parcel_crs: str) -> None:
    if parcel_crs.upper() != f"EPSG:{CANONICAL_EPSG}":
        raise ValueError(
            f"Prototype accepts parcel geometry only in EPSG:{CANONICAL_EPSG}; "
            f"received {parcel_crs}"
        )
    epsg = dataset.crs.to_epsg() if dataset.crs else None
    if epsg != CANONICAL_EPSG:
        raise ValueError(
            f"DTM must use EPSG:{CANONICAL_EPSG}; raster reports {dataset.crs!s}"
        )


def _valid_slope_degrees(elevation: np.ma.MaskedArray, x_res: float, y_res: float) -> np.ndarray:
    filled = elevation.astype("float64").filled(np.nan)
    if filled.shape[0] < 2 or filled.shape[1] < 2:
        return np.array([], dtype="float64")
    grad_y, grad_x = np.gradient(filled, y_res, x_res)
    slope = np.degrees(np.arctan(np.hypot(grad_x, grad_y)))
    return slope[np.isfinite(slope)]


def compute_metrics(
    dataset: DatasetReader,
    geometry: dict[str, Any],
    *,
    parcel_crs: str,
    source_date: str,
    source_label: str,
    vertical_datum: str,
    declared_resolution_m: float | None,
) -> dict[str, Any]:
    _assert_spatial_contract(dataset, parcel_crs)

    clipped, transform = mask(
        dataset,
        [geometry],
        crop=True,
        filled=False,
        indexes=1,
        all_touched=False,
    )
    elevation = np.ma.masked_invalid(clipped)

    values = elevation.compressed().astype("float64")
    if values.size == 0:
        raise ValueError("Parcel does not overlap valid DTM cells")

    x_res = abs(float(transform.a))
    y_res = abs(float(transform.e))
    actual_resolution_m = max(x_res, y_res)

    if declared_resolution_m is not None and not math.isclose(
        actual_resolution_m, declared_resolution_m, rel_tol=0.02, abs_tol=0.05
    ):
        raise ValueError(
            f"Declared resolution {declared_resolution_m:g} m does not match "
            f"raster resolution {actual_resolution_m:g} m"
        )

    slopes = _valid_slope_degrees(elevation, x_res, y_res)

    return {
        "source": source_label,
        "source_date": source_date,
        "horizontal_crs": f"EPSG:{CANONICAL_EPSG}",
        "vertical_datum": vertical_datum,
        "resolution_m": round(actual_resolution_m, 6),
        "sample_cells": int(values.size),
        "elevation_m": {
            "min": round(float(np.min(values)), 3),
            "max": round(float(np.max(values)), 3),
            "mean": round(float(np.mean(values)), 3),
        },
        "slope_degrees": {
            "mean": round(float(np.mean(slopes)), 3) if slopes.size else None,
            "max": round(float(np.max(slopes)), 3) if slopes.size else None,
            "sample_cells": int(slopes.size),
        },
    }


def run_self_test() -> dict[str, Any]:
    # 5 m grid with a stable planar rise: +1 m east and +2 m south per cell.
    data = np.array(
        [[100 + x + 2 * y for x in range(6)] for y in range(6)],
        dtype="float32",
    )
    transform = from_origin(500000.0, 6500000.0, 5.0, 5.0)
    geometry = {
        "type": "Polygon",
        "coordinates": [[
            [500000.0, 6500000.0],
            [500030.0, 6500000.0],
            [500030.0, 6499970.0],
            [500000.0, 6499970.0],
            [500000.0, 6500000.0],
        ]],
    }

    with MemoryFile() as memfile:
        with memfile.open(
            driver="GTiff",
            width=data.shape[1],
            height=data.shape[0],
            count=1,
            dtype=data.dtype,
            crs=f"EPSG:{CANONICAL_EPSG}",
            transform=transform,
            nodata=-9999.0,
        ) as dataset:
            dataset.write(data, 1)
            result = compute_metrics(
                dataset,
                geometry,
                parcel_crs=f"EPSG:{CANONICAL_EPSG}",
                source_date="synthetic-self-test",
                source_label="ForestIQ terrain prototype fixture",
                vertical_datum=DEFAULT_VERTICAL_DATUM,
                declared_resolution_m=5.0,
            )

    assert result["sample_cells"] == 36, result
    assert result["elevation_m"] == {"min": 100.0, "max": 115.0, "mean": 107.5}, result
    expected_slope = math.degrees(math.atan(math.hypot(1.0 / 5.0, 2.0 / 5.0)))
    assert abs(result["slope_degrees"]["mean"] - expected_slope) < 0.01, result
    assert abs(result["slope_degrees"]["max"] - expected_slope) < 0.01, result
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dtm", type=Path, help="Local Maa- ja Ruumiamet DTM GeoTIFF")
    parser.add_argument("--parcel-geojson", type=Path, help="One EPSG:3301 parcel Polygon/MultiPolygon")
    parser.add_argument("--parcel-crs", default=f"EPSG:{CANONICAL_EPSG}")
    parser.add_argument("--source-date", help="Provider/source date or flight-generation label")
    parser.add_argument("--source-label", default=DEFAULT_SOURCE)
    parser.add_argument("--vertical-datum", default=DEFAULT_VERTICAL_DATUM)
    parser.add_argument("--resolution-m", type=float, help="Expected raster resolution; validates metadata")
    parser.add_argument("--self-test", action="store_true", help="Run deterministic in-memory prototype test")
    return parser


def main() -> int:
    args = build_parser().parse_args()

    if args.self_test:
        print(json.dumps(run_self_test(), ensure_ascii=False, indent=2))
        return 0

    missing = [
        flag
        for flag, value in (
            ("--dtm", args.dtm),
            ("--parcel-geojson", args.parcel_geojson),
            ("--source-date", args.source_date),
        )
        if not value
    ]
    if missing:
        raise SystemExit("Missing required arguments: " + ", ".join(missing))

    geometry = _load_geometry(args.parcel_geojson)
    with rasterio.open(args.dtm) as dataset:
        result = compute_metrics(
            dataset,
            geometry,
            parcel_crs=args.parcel_crs,
            source_date=args.source_date,
            source_label=args.source_label,
            vertical_datum=args.vertical_datum,
            declared_resolution_m=args.resolution_m,
        )

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, rasterio.errors.RasterioError) as exc:
        print(f"terrain prototype failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
