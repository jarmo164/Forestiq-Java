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


def evaluate(policy: dict, protection: dict) -> list[str]:
    failures: list[str] = []

    expected = sorted(policy["required_status_checks"]["contexts"])
    actual = sorted((protection.get("required_status_checks") or {}).get("contexts") or [])
    if actual != expected:
        failures.append(f"required status checks={actual}, expected {expected}")

    if (protection.get("required_status_checks") or {}).get("strict") is not policy["required_status_checks"]["strict"]:
        failures.append("strict/up-to-date branch requirement drifted")

    if (protection.get("enforce_admins") or {}).get("enabled") is not policy["enforce_admins"]:
        failures.append("admin enforcement drifted")

    reviews = protection.get("required_pull_request_reviews")
    if policy["pull_request"]["required"] and not isinstance(reviews, dict):
        failures.append("pull request protection is missing")

    if (protection.get("required_conversation_resolution") or {}).get("enabled") is not policy["required_conversation_resolution"]:
        failures.append("conversation resolution policy drifted")

    if (protection.get("allow_force_pushes") or {}).get("enabled") is not policy["allow_force_pushes"]:
        failures.append("force-push policy drifted")

    if (protection.get("allow_deletions") or {}).get("enabled") is not policy["allow_deletions"]:
        failures.append("branch deletion policy drifted")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate live main branch protection against ForestIQ policy.")
    parser.add_argument("protection_json", type=Path)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()

    policy = load(args.policy)
    failures = evaluate(policy, load(args.protection_json))
    passed = not failures

    lines = [
        "# ForestIQ main branch protection evidence",
        "",
        f"- Result: **{'PASS' if passed else 'FAIL'}**",
        f"- Branch: `{policy['branch']}`",
        "",
    ]
    if failures:
        lines += ["## Blocking evidence", ""] + [f"- {failure}" for failure in failures]
    else:
        lines.append("Live main branch protection matches the committed ForestIQ policy.")

    rendered = "\n".join(lines) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
        print(args.report)
    else:
        print(rendered, end="")

    if args.json_report:
        args.json_report.write_text(
            json.dumps({
                "schemaVersion": 1,
                "result": "PASS" if passed else "FAIL",
                "branch": policy["branch"],
                "requiredStatusChecks": sorted(policy["required_status_checks"]["contexts"]),
                "failures": failures,
            }, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(args.json_report)

    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
