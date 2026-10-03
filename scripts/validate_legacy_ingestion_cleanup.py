#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PLAN = ROOT / "config" / "legacy-ingestion-cleanup-plan.json"


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def evaluate_cleanup(plan: dict, evidence_by_source: dict[str, dict]) -> tuple[list[str], list[str]]:
    eligible: list[str] = []
    blocked: list[str] = []

    for source in plan["sources"]:
        key = source["key"]
        evidence = evidence_by_source.get(key)
        if evidence is None:
            blocked.append(f"{key}: evidence missing")
            continue

        status = evidence.get("status")
        if status == "READY":
            if evidence.get("parityReady") is not True:
                blocked.append(f"{key}: parityReady is not true")
                continue
            if int(evidence.get("successfulPythonRuns", 0)) < 2:
                blocked.append(f"{key}: fewer than 2 successful Python runs")
                continue
            if evidence.get("rollbackVerified") is not True:
                blocked.append(f"{key}: rollback/recovery proof missing")
                continue
            eligible.append(key)
            continue

        if status == "EXCEPTION":
            retained = source.get("retain", [])
            if retained:
                blocked.append(
                    f"{key}: Java exception retained ({', '.join(retained)}); full cleanup is not eligible"
                )
            else:
                blocked.append(f"{key}: unexpected Java exception")
            continue

        blocked.append(f"{key}: status is {status or 'missing'}")

    return eligible, blocked


def load_evidence(plan: dict, evidence_dir: Path) -> dict[str, dict]:
    evidence: dict[str, dict] = {}
    for source in plan["sources"]:
        path = evidence_dir / source["evidence_file"]
        if path.exists():
            evidence[source["key"]] = read_json(path)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description="Preflight legacy Java ingestion cleanup for #366.")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
    parser.add_argument("--source", action="append", dest="sources")
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args()

    plan = read_json(args.plan)
    if args.sources:
        wanted = set(args.sources)
        selected = [source for source in plan["sources"] if source["key"] in wanted]
        missing = wanted - {source["key"] for source in selected}
        if missing:
            print("FAIL unknown cleanup source(s): " + ", ".join(sorted(missing)))
            return 2
        plan = {**plan, "sources": selected}

    evidence = load_evidence(plan, args.evidence_dir)
    eligible, blocked = evaluate_cleanup(plan, evidence)

    for key in eligible:
        source = next(item for item in plan["sources"] if item["key"] == key)
        candidates = ", ".join(source.get("java_candidates", [])) or "no Java deletion candidate"
        print(f"ELIGIBLE {key}: {candidates}")

    for reason in blocked:
        print(f"BLOCKED {reason}")

    if args.require_ready and blocked:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
