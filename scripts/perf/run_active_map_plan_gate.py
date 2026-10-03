#!/usr/bin/env python3
"""Capture active parcel/overview/limit/overlay PostGIS plan evidence."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
from typing import Any

from validate_query_plans import parse_marked_explains, summarize_plan

REQUIRED = {
    "parcel_detail",
    "overview_unrestricted",
    "overview_restricted",
    "tile_limit",
    "overlay_mvt",
}


def scalar(database_url: str, sql: str) -> str:
    result = subprocess.run(
        ["psql", database_url, "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", sql],
        text=True, capture_output=True, check=False,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "psql failed")
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", default=os.environ.get("DATABASE_URL", ""))
    parser.add_argument("--organization-id", default="99999999-9999-9999-9999-999999999999")
    parser.add_argument("--tile-z", type=int, default=10)
    parser.add_argument("--tile-x", type=int, default=575)
    parser.add_argument("--tile-y", type=int, default=310)
    parser.add_argument("--output", default="build/perf/active-map-plan-report.json")
    args = parser.parse_args()
    if not args.database_url:
        print("active-map-plan gate: ERROR: DATABASE_URL is required")
        return 2

    root = Path(__file__).resolve().parents[2]
    sql_path = root / "scripts/perf/explain_active_map_paths.sql"
    result = subprocess.run(
        [
            "psql", args.database_url, "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
            "-v", f"organization_id={args.organization_id}",
            "-v", f"tile_z={args.tile_z}", "-v", f"tile_x={args.tile_x}", "-v", f"tile_y={args.tile_y}",
            "-f", str(sql_path),
        ],
        text=True, capture_output=True, check=False,
    )
    if result.returncode:
        print(result.stderr)
        return 2

    plans = parse_marked_explains(result.stdout)
    missing = REQUIRED - plans.keys()
    if missing:
        print("active-map-plan gate: ERROR: missing plans " + ",".join(sorted(missing)))
        return 2

    summaries = {name: summarize_plan(plans[name]) for name in REQUIRED}
    rows = {
        "geospatial_cadastre_parcel": int(scalar(args.database_url, "SELECT count(*) FROM geospatial_cadastre_parcel") or 0),
        "geospatial_feature": int(scalar(args.database_url, "SELECT count(*) FROM geospatial_feature") or 0),
        "land_parcel_render": int(scalar(args.database_url, f"SELECT count(*) FROM land_parcel_render WHERE organization_id='{args.organization_id}'::uuid") or 0),
    }
    violations: list[str] = []

    spatial_expectations = {
        "overview_unrestricted": ("land_parcel_render", "land_parcel_render"),
        "overview_restricted": ("geospatial_cadastre_parcel", "geospatial_cadastre"),
        "tile_limit": ("geospatial_cadastre_parcel", "geospatial_cadastre"),
        "overlay_mvt": ("geospatial_feature", "geospatial_feature"),
    }
    for plan_name, (table, index_hint) in spatial_expectations.items():
        summary = summaries[plan_name]
        if rows.get(table, 0) >= 10_000:
            bad_full_scan = any(
                scan.relation == table and scan.scanned_rows >= 10_000
                for scan in summary.seq_scans
            )
            has_candidate_index = any(index_hint in index.lower() for index in summary.indexes)
            if bad_full_scan:
                violations.append(f"{plan_name}: full sequential scan on {table}")
            if not has_candidate_index:
                violations.append(f"{plan_name}: missing spatial/index candidate path for {table}")

    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True, check=False
    ).stdout.strip() or "unknown"
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "commit": commit,
        "status": "PASS" if not violations else "FAIL",
        "environment": {
            "postgresVersion": scalar(args.database_url, "SHOW server_version"),
            "migrationVersion": scalar(args.database_url, "SELECT COALESCE(max(version),'none') FROM flyway_schema_history WHERE success"),
        },
        "tileAnchor": {"z": args.tile_z, "x": args.tile_x, "y": args.tile_y},
        "rowCounts": rows,
        "violations": violations,
        "plans": {
            name: {
                "executionMs": summary.execution_ms,
                "sharedReadBlocks": summary.shared_read_blocks,
                "sharedHitBlocks": summary.shared_hit_blocks,
                "indexes": summary.indexes,
                "seqScans": [
                    {"relation": scan.relation, "scannedRows": scan.scanned_rows}
                    for scan in summary.seq_scans
                ],
            }
            for name, summary in sorted(summaries.items())
        },
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"active-map-plan gate: {report['status']} ({out})")
    for violation in violations:
        print("FAIL: " + violation)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
