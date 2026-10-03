#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Metsaregister input must be a JSON object.")
    return value


def build(raw: dict, repository_sha: str) -> dict:
    layers = raw.get("layers")
    if not isinstance(layers, list) or not layers:
        raise ValueError("layers must be a non-empty list")

    failures: list[str] = []
    normalized = []
    for layer in layers:
        name = str(layer.get("layerName", "")).strip()
        java_count = int(layer.get("javaRowCount", -1))
        python_count = int(layer.get("pythonRowCount", -1))
        matching_ids = int(layer.get("matchingStableIds", -1))
        bounds_match = layer.get("geometryBoundsMatch") is True
        metadata_match = layer.get("sourceMetadataMatch") is True
        passed = (
            bool(name)
            and java_count >= 0
            and python_count == java_count
            and matching_ids == java_count
            and bounds_match
            and metadata_match
        )
        if not passed:
            failures.append(f"layer:{name or '<missing>'}")
        normalized.append({
            "layerName": name,
            "javaGenerationId": layer.get("javaGenerationId"),
            "pythonGenerationId": layer.get("pythonGenerationId"),
            "javaRowCount": java_count,
            "pythonRowCount": python_count,
            "matchingStableIds": matching_ids,
            "geometryBoundsMatch": bounds_match,
            "sourceMetadataMatch": metadata_match,
            "passed": passed,
        })

    successful_runs = int(raw.get("successfulPythonRuns", 0))
    notification_match = raw.get("notificationSamplesMatch") is True
    forest_plan_match = raw.get("forestPlanSamplesMatch") is True
    rollback_verified = raw.get("rollbackVerified") is True

    if successful_runs < 2:
        failures.append("successfulPythonRuns:<2")
    if not notification_match:
        failures.append("notificationSamplesMatch:false")
    if not forest_plan_match:
        failures.append("forestPlanSamplesMatch:false")
    if not rollback_verified:
        failures.append("rollbackVerified:false")

    ready = not failures
    return {
        "source": "metsaregister",
        "issue": 363,
        "disposition": "PYTHON_ETL",
        "status": "READY" if ready else "PENDING",
        "repositorySha": repository_sha,
        "parityReady": ready,
        "successfulPythonRuns": successful_runs,
        "rollbackVerified": rollback_verified,
        "failures": failures,
        "notificationSamplesMatch": notification_match,
        "forestPlanSamplesMatch": forest_plan_match,
        "layers": normalized,
        "notes": str(raw.get("notes", "")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build sanitized Metsaregister cutover evidence for #363.")
    parser.add_argument("input", type=Path, help="Sanitized shadow-comparison JSON")
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
