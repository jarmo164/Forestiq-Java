#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_POLICY = ROOT / ".github" / "forestiq-repository-policy.json"


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def evaluate(policy: dict, repository: dict, protection: dict) -> list[str]:
    failures: list[str] = []

    merge = policy["merge"]
    for key in ("allow_auto_merge", "allow_squash_merge", "allow_merge_commit", "allow_rebase_merge"):
        if repository.get(key) != merge[key]:
            failures.append(f"repository {key}={repository.get(key)!r}, expected {merge[key]!r}")

    expected_checks = sorted(policy["required_status_checks"]["contexts"])
    actual_checks = sorted((protection.get("required_status_checks") or {}).get("contexts") or [])
    if actual_checks != expected_checks:
        failures.append(f"required status checks={actual_checks}, expected {expected_checks}")

    strict = (protection.get("required_status_checks") or {}).get("strict")
    if strict is not policy["required_status_checks"]["strict"]:
        failures.append(f"strict status checks={strict!r}")

    admins = (protection.get("enforce_admins") or {}).get("enabled")
    if admins is not policy["enforce_admins"]:
        failures.append(f"enforce_admins={admins!r}")

    reviews = protection.get("required_pull_request_reviews")
    if policy["pull_request"]["required"] and not isinstance(reviews, dict):
        failures.append("pull request review/protection block is missing")
    elif isinstance(reviews, dict):
        if reviews.get("required_approving_review_count") != policy["pull_request"]["required_approving_review_count"]:
            failures.append("required approving review count drifted")

    conversation = (protection.get("required_conversation_resolution") or {}).get("enabled")
    if conversation is not policy["required_conversation_resolution"]:
        failures.append(f"required_conversation_resolution={conversation!r}")

    force_push = (protection.get("allow_force_pushes") or {}).get("enabled")
    if force_push is not policy["allow_force_pushes"]:
        failures.append(f"allow_force_pushes={force_push!r}")

    deletions = (protection.get("allow_deletions") or {}).get("enabled")
    if deletions is not policy["allow_deletions"]:
        failures.append(f"allow_deletions={deletions!r}")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate live GitHub repository governance against ForestIQ policy.")
    parser.add_argument("--repository-json", type=Path, required=True)
    parser.add_argument("--protection-json", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()

    policy = load(args.policy)
    failures = evaluate(policy, load(args.repository_json), load(args.protection_json))
    lines = [
        "# ForestIQ repository governance audit",
        "",
        f"- Result: **{'PASS' if not failures else 'FAIL'}**",
        f"- Protected branch: `{policy['branch']}`",
        "",
    ]
    if failures:
        lines += ["## Drift", ""] + [f"- {failure}" for failure in failures]
    else:
        lines.append("Live repository settings match the committed ForestIQ governance policy.")

    text = "\n".join(lines) + "\n"
    if args.report:
        args.report.write_text(text, encoding="utf-8")
        print(args.report)
    else:
        print(text, end="")

    if args.json_report:
        payload = {
            "schemaVersion": 1,
            "result": "PASS" if not failures else "FAIL",
            "branch": policy["branch"],
            "requiredStatusChecks": sorted(policy["required_status_checks"]["contexts"]),
            "failures": failures,
        }
        args.json_report.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(args.json_report)

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
