#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REQUIREMENTS = ROOT / "config" / "ingestion-cutover-requirements.json"


class EvidenceError(ValueError):
    pass


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EvidenceError(f"missing evidence file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise EvidenceError(f"invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceError(f"evidence must be an object: {path}")
    return value


def validate_source(requirement: dict, evidence: dict) -> list[str]:
    failures: list[str] = []
    key = requirement["key"]

    if evidence.get("source") != key:
        failures.append(f"{key}: source identity mismatch")
    if evidence.get("issue") != requirement["issue"]:
        failures.append(f"{key}: issue reference mismatch")
    if evidence.get("disposition") != requirement["required_disposition"]:
        failures.append(f"{key}: disposition mismatch")

    status = evidence.get("status")
    if status not in {"READY", "EXCEPTION"}:
        failures.append(f"{key}: status must be READY or EXCEPTION")

    if not isinstance(evidence.get("repositorySha"), str) or len(evidence["repositorySha"]) < 7:
        failures.append(f"{key}: repositorySha is missing")

    if status == "READY":
        if evidence.get("parityReady") is not True:
            failures.append(f"{key}: parityReady is not true")
        if int(evidence.get("successfulPythonRuns", 0)) < 2:
            failures.append(f"{key}: fewer than 2 successful Python runs")
        if evidence.get("rollbackVerified") is not True:
            failures.append(f"{key}: rollback/recovery proof missing")
    elif status == "EXCEPTION":
        if requirement["required_disposition"] != "MIXED":
            failures.append(f"{key}: exception is not allowed for PYTHON_ETL source")
        if not evidence.get("exceptionReason"):
            failures.append(f"{key}: exceptionReason is required")
        if evidence.get("authorizationBound") is not True:
            failures.append(f"{key}: real-time exception must be authorization-bound")

    return failures


def validate_bundle(requirements: dict, evidence_dir: Path) -> list[str]:
    failures: list[str] = []
    for requirement in requirements["sources"]:
        path = evidence_dir / requirement["evidence_file"]
        try:
            evidence = load_json(path)
        except EvidenceError as exc:
            failures.append(str(exc))
            continue
        failures.extend(validate_source(requirement, evidence))
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate ForestIQ source-by-source ingestion cutover evidence.")
    parser.add_argument("--requirements", type=Path, default=DEFAULT_REQUIREMENTS)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()

    requirements = load_json(args.requirements)
    failures = validate_bundle(requirements, args.evidence_dir)
    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1

    print("PASS ingestion source cutover bundle")
    for source in requirements["sources"]:
        print(f"ready={source['key']} issue=#{source['issue']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
