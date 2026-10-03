#!/usr/bin/env python3
"""Run the ForestIQ PostGIS scale fixture and calibrated critical query-plan gate."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

from validate_query_plans import (
    aggregate_runs,
    compare_to_baseline,
    report_payload,
    validate_output,
)

PERF_ORG = "99999999-9999-9999-9999-999999999999"


def run_psql(command: list[str], *, stdout_path: Path | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if stdout_path is not None:
        stdout_path.parent.mkdir(parents=True, exist_ok=True)
        stdout_path.write_text(result.stdout, encoding="utf-8")
    if result.returncode != 0:
        detail = result.stderr.strip() or "psql returned a non-zero exit status"
        raise RuntimeError(detail)
    return result


def scalar(database_url: str, sql: str) -> str:
    return run_psql([
        "psql", database_url, "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", sql
    ]).stdout.strip()


def commit_sha(repo_root: Path) -> str:
    env = os.environ.get("GITHUB_SHA", "").strip()
    if env:
        return env
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repo_root, text=True, capture_output=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else "unknown"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", default=os.environ.get("DATABASE_URL", ""))
    parser.add_argument("--seed", action="store_true")
    parser.add_argument("--owner-count", type=int, default=250_000)
    parser.add_argument("--parcel-count", type=int, default=2_000_000)
    parser.add_argument("--organization-id", default=PERF_ORG)
    parser.add_argument("--tile-z", type=int, default=10)
    parser.add_argument("--tile-x", type=int, default=575)
    parser.add_argument("--tile-y", type=int, default=310)
    parser.add_argument("--seq-scan-threshold", type=int, default=100_000)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--max-regression-percent", type=float, default=15.0)
    parser.add_argument("--output-dir", type=Path, default=Path("build/perf"))
    args = parser.parse_args()

    if not args.database_url.strip():
        print("ForestIQ query-plan gate: ERROR: DATABASE_URL/--database-url is required")
        return 2
    if not shutil.which("psql"):
        print("ForestIQ query-plan gate: ERROR: psql is not installed or not on PATH")
        return 2
    if args.owner_count < 1 or args.parcel_count < 1 or args.runs < 5:
        print("ForestIQ query-plan gate: ERROR: positive fixture sizes and at least 5 runs are required")
        return 2

    repo_root = Path(__file__).resolve().parents[2]
    seed_sql = repo_root / "scripts/perf/seed_postgis.sql"
    explain_sql = repo_root / "scripts/perf/explain_critical_queries.sql"
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_output = args.output_dir / "query-plan-report.json"

    try:
        if args.seed:
            print(
                "Seeding isolated performance tenant "
                f"{args.organization_id}: owners={args.owner_count}, parcels={args.parcel_count}"
            )
            run_psql([
                "psql", args.database_url, "-X", "-v", "ON_ERROR_STOP=1",
                "-v", f"organization_id={args.organization_id}",
                "-v", f"owner_count={args.owner_count}",
                "-v", f"parcel_count={args.parcel_count}",
                "-f", str(seed_sql),
            ])

        run_summaries = []
        all_violations: list[str] = []
        last_summaries = None
        print(f"Capturing {args.runs} calibrated EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) runs")
        for index in range(1, args.runs + 1):
            raw_output = args.output_dir / f"query-plans-run-{index}.json.txt"
            result = run_psql([
                "psql", args.database_url, "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                "-v", f"organization_id={args.organization_id}",
                "-v", f"tile_z={args.tile_z}",
                "-v", f"tile_x={args.tile_x}",
                "-v", f"tile_y={args.tile_y}",
                "-f", str(explain_sql),
            ], stdout_path=raw_output)
            summaries, violations = validate_output(
                result.stdout, seq_scan_threshold=args.seq_scan_threshold
            )
            run_summaries.append(summaries)
            last_summaries = summaries
            all_violations.extend(f"run {index}: {item}" for item in violations)

        aggregate = aggregate_runs(run_summaries)
        baseline_identity = None
        if args.baseline:
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            baseline_identity = {
                "source": str(args.baseline),
                "commit": baseline.get("commit"),
                "postgresVersion": baseline.get("environment", {}).get("postgresVersion"),
                "migrationVersion": baseline.get("environment", {}).get("migrationVersion"),
            }
            current_pg = scalar(args.database_url, "SHOW server_version")
            current_migration = scalar(
                args.database_url,
                "SELECT COALESCE(max(version),'none') FROM flyway_schema_history WHERE success"
            )
            if baseline_identity["postgresVersion"] not in (None, current_pg):
                all_violations.append("baseline PostgreSQL version does not match current environment")
            if baseline_identity["migrationVersion"] not in (None, current_migration):
                all_violations.append("baseline Flyway migration version does not match current environment")
            all_violations.extend(compare_to_baseline(
                aggregate,
                baseline,
                max_regression_fraction=args.max_regression_percent / 100.0,
            ))
        else:
            current_pg = scalar(args.database_url, "SHOW server_version")
            current_migration = scalar(
                args.database_url,
                "SELECT COALESCE(max(version),'none') FROM flyway_schema_history WHERE success"
            )

        fixture_counts = json.loads(scalar(args.database_url, f"""
            SELECT json_build_object(
                'owners', (SELECT count(*) FROM owner WHERE organization_id='{args.organization_id}'::uuid),
                'parcels', (SELECT count(*) FROM land_parcel WHERE organization_id='{args.organization_id}'::uuid)
            )::text
        """))

        report = report_payload(
            str(args.output_dir), last_summaries or {}, all_violations, args.seq_scan_threshold
        )
        report["commit"] = commit_sha(repo_root)
        report["runs"] = args.runs
        report["aggregate"] = aggregate
        report["baseline"] = baseline_identity
        report["maxRegressionPercent"] = args.max_regression_percent
        report["environment"] = {
            "postgresVersion": current_pg,
            "migrationVersion": current_migration,
        }
        report["fixture"] = {
            "organizationId": args.organization_id,
            "seededByThisRun": args.seed,
            "requestedOwners": args.owner_count if args.seed else None,
            "requestedParcels": args.parcel_count if args.seed else None,
            "actualOwners": fixture_counts.get("owners"),
            "actualParcels": fixture_counts.get("parcels"),
            "tile": {"z": args.tile_z, "x": args.tile_x, "y": args.tile_y},
        }
        report["status"] = "PASS" if not all_violations else "FAIL"
        report["violations"] = all_violations
        report_output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

        print(f"ForestIQ query-plan gate: {report['status']}")
        print(f"- report: {report_output}")
        for name, values in sorted(aggregate.items()):
            print(
                f"- {name}: median_execution={values['medianExecutionMs']:.2f} ms, "
                f"median_shared_reads={values['medianSharedReadBlocks']:.0f}"
            )
        for violation in all_violations:
            print(f"FAIL: {violation}")
        return 1 if all_violations else 0
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(f"ForestIQ query-plan gate: ERROR: {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
