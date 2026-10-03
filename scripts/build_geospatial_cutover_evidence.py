#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Geospatial input must be a JSON object.")
    return value


def build(raw: dict, repository_sha: str) -> dict:
    datasets = raw.get("datasets")
    if not isinstance(datasets, list) or not datasets:
        raise ValueError("datasets must be a non-empty list")

    failures: list[str] = []
    normalized = []
    for dataset in datasets:
        key = str(dataset.get("datasetKey", "")).strip()
        current_count = int(dataset.get("currentRowCount", -1))
        python_count = int(dataset.get("pythonRowCount", -1))
        identity_match = dataset.get("identityMatch") is True
        bounds_match = dataset.get("geometryBoundsMatch") is True
        sample_match = dataset.get("sampleMatch") is True
        passed = (
            bool(key)
            and current_count >= 0
            and python_count == current_count
            and identity_match
            and bounds_match
            and sample_match
        )
        if not passed:
            failures.append(f"dataset:{key or '<missing>'}")
        normalized.append({
            "datasetKey": key,
            "currentRowCount": current_count,
            "pythonRowCount": python_count,
            "identityMatch": identity_match,
            "geometryBoundsMatch": bounds_match,
            "sampleMatch": sample_match,
            "passed": passed,
        })

    rollback_verified = raw.get("failedImportPreservedCurrentGeneration") is True
    unchanged_noop = raw.get("unchangedSnapshotWasNoop") is True
    read_model_compatible = raw.get("javaReadModelCompatible") is True
    successful_runs = int(raw.get("successfulPythonRuns", 0))

    if not rollback_verified:
        failures.append("failedImportPreservedCurrentGeneration:false")
    if not unchanged_noop:
        failures.append("unchangedSnapshotWasNoop:false")
    if not read_model_compatible:
        failures.append("javaReadModelCompatible:false")
    if successful_runs < 2:
        failures.append("successfulPythonRuns:<2")

    ready = not failures
    return {
        "source": "cadastre_geospatial",
        "issue": 364,
        "disposition": "PYTHON_ETL",
        "status": "READY" if ready else "PENDING",
        "repositorySha": repository_sha,
        "parityReady": ready,
        "successfulPythonRuns": successful_runs,
        "rollbackVerified": rollback_verified,
        "unchangedSnapshotWasNoop": unchanged_noop,
        "javaReadModelCompatible": read_model_compatible,
        "datasets": normalized,
        "failures": failures,
        "notes": str(raw.get("notes", "")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build sanitized cadastre/geospatial cutover evidence for #364.")
    parser.add_argument("input", type=Path)
    parser.add_argument("--repository-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()

    evidence = build(load(args.input), args.repository_sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(args.output)
    if args.require_ready and evidence["status"] != "READY":
        for failure in evidence["failures"]:
            print(f"FAIL {failure}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
