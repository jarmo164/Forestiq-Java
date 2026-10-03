#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
POLICY = ROOT / "config" / "self-hosted-runner-security.json"
ACCEPTANCE = ROOT / "scripts" / "ci" / "runner-acceptance.sh"


def evaluate(policy: dict, workflows: dict[str, str], acceptance_script: str) -> list[str]:
    failures: list[str] = []
    allowed = set(policy["allowed_heavy_workflows"])

    for name, text in workflows.items():
        if policy["forbid_pull_request_target"] and "pull_request_target:" in text:
            failures.append(f"{name}: pull_request_target is forbidden")

        targets_heavy = "heavy-ci" in text
        if targets_heavy and name not in allowed:
            failures.append(f"{name}: workflow targets heavy-ci but is not allowlisted")

        if targets_heavy and policy["require_read_only_contents"]:
            if "permissions:\n  contents: read" not in text:
                failures.append(f"{name}: self-hosted workflow must declare contents: read")

        if targets_heavy and policy["forbid_repository_secrets_on_self_hosted_tests"]:
            if "${{ secrets." in text:
                failures.append(f"{name}: self-hosted test workflow references repository secrets")

    required = policy["require_fork_trust_gate_in"]
    heavy = workflows.get(required, "")
    if "github.event.pull_request.head.repo.full_name" not in heavy:
        failures.append(f"{required}: fork trust classifier is missing")
    if "needs.changes.outputs.trusted == 'true'" not in heavy:
        failures.append(f"{required}: self-hosted jobs are not guarded by trusted=true")

    if policy["require_non_admin_acceptance"]:
        if "Dedicated non-admin account" not in acceptance_script:
            failures.append("runner acceptance no longer checks the non-admin account boundary")

    if policy.get("forbidden_environment_names"):
        if "runner_environment_security.py" not in acceptance_script:
            failures.append("runner acceptance no longer checks forbidden credential environment names")

    return failures


def main() -> int:
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    workflows = {
        path.name: path.read_text(encoding="utf-8")
        for path in WORKFLOWS.glob("*.yml")
    }
    failures = evaluate(policy, workflows, ACCEPTANCE.read_text(encoding="utf-8"))
    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1

    print("ForestIQ self-hosted runner security contract is valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
