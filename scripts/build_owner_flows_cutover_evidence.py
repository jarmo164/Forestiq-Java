#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

REQUIRED_KEYS = {
    "OWNER_CADASTRE_RELATIONS",
    "WEASEL_OWNERSHIP_TRANSITIONS",
    "INHERITANCE_NOTICES",
}


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Owner-flow input must be a JSON object.")
    return value


def build(raw: dict, repository_sha: str) -> dict:
    sources = raw.get("sources")
    if not isinstance(sources, list):
        raise ValueError("sources must be a list")

    failures: list[str] = []
    normalized = []
    present: set[str] = set()
    successful_runs: list[int] = []

    for source in sources:
        key = str(source.get("jobKey", "")).strip()
        present.add(key)
        current_count = int(source.get("currentCount", -1))
        python_count = int(source.get("pythonCount", -1))
        stable_ids_match = source.get("stableIdsMatch") is True
        business_cases_match = source.get("selectedBusinessCasesMatch") is True
        runs = int(source.get("successfulPythonRuns", 0))
        successful_runs.append(runs)

        passed = (
            key in REQUIRED_KEYS
            and current_count >= 0
            and python_count == current_count
            and stable_ids_match
            and business_cases_match
            and runs >= 2
        )
        if not passed:
            failures.append(f"source:{key or '<missing>'}")
        normalized.append({
            "jobKey": key,
            "currentCount": current_count,
            "pythonCount": python_count,
            "stableIdsMatch": stable_ids_match,
            "selectedBusinessCasesMatch": business_cases_match,
            "successfulPythonRuns": runs,
            "passed": passed,
        })

    for missing in sorted(REQUIRED_KEYS - present):
        failures.append(f"source:{missing}:missing")

    multi_owner = raw.get("multiOwnerPreserved") is True
    end_reactivate = raw.get("relationEndReactivatePreserved") is True
    manual_override = raw.get("manualOverridePreserved") is True
    retry_idempotent = raw.get("retryIdempotent") is True
    watermark_safe = raw.get("failedRunPreservedWatermark") is True

    checks = {
        "multiOwnerPreserved": multi_owner,
        "relationEndReactivatePreserved": end_reactivate,
        "manualOverridePreserved": manual_override,
        "retryIdempotent": retry_idempotent,
        "failedRunPreservedWatermark": watermark_safe,
    }
    for key, passed in checks.items():
        if not passed:
            failures.append(f"{key}:false")

    min_runs = min(successful_runs) if successful_runs and REQUIRED_KEYS == present else 0
    rollback_verified = retry_idempotent and watermark_safe
    ready = not failures

    return {
        "source": "owner_flows",
        "issue": 365,
        "disposition": "PYTHON_ETL",
        "status": "READY" if ready else "PENDING",
        "repositorySha": repository_sha,
        "parityReady": ready,
        "successfulPythonRuns": min_runs,
        "rollbackVerified": rollback_verified,
        "failures": failures,
        **checks,
        "sources": normalized,
        "notes": str(raw.get("notes", "")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build sanitized owner/Weasel/inheritance cutover evidence for #365.")
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
