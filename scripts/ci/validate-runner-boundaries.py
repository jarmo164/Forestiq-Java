#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
OWNERSHIP = ROOT / "config" / "ci-workload-ownership.json"


def read(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise SystemExit(f"CI runner-boundary contract failed: {message}")


def main() -> None:
    policy = json.loads(OWNERSHIP.read_text(encoding="utf-8"))
    fast = read(Path(policy["fast"]["workflow"]).name)
    heavy = read(Path(policy["heavy"]["workflow"]).name)
    security = read("security.yml")
    ingestion = read("ingestion-contract.yml")
    e2e = read("e2e-compose.yml")

    trusted_labels = '["' + '","'.join(policy["fast"]["trusted_runner_labels"]) + '"]'
    fork_guard = "github.event.pull_request.head.repo.full_name != github.repository"

    require(fast, f'name: {policy["fast"]["aggregate_gate"]}', "fast aggregate gate is missing")
    require(fast, "workflow_dispatch:", "fast CI must remain manually runnable")
    require(fast, fork_guard, "fast CI must distinguish fork PRs from trusted execution")
    require(fast, policy["fast"]["untrusted_pr_runner"], "fork PR hosted runner is missing")
    require(fast, trusted_labels, "trusted fast CI must target the repository-scoped Mac runner")
    require(fast, "Verify CI runner trust boundary", "trusted/untrusted runner preflight is missing")
    for job in policy["fast"]["jobs"]:
        require(fast, f"  {job}:", f"fast job ownership drifted: {job}")

    require(security, "workflow_call:", "full security gate must remain reusable from canonical CI")
    require(security, fork_guard, "security workflow must preserve the fork trust boundary")
    require(security, trusted_labels, "trusted security jobs must remain available on the Mac runner")
    require(ingestion, fork_guard, "ingestion contract must preserve the fork trust boundary")
    require(ingestion, trusted_labels, "trusted ingestion contract must remain available on the Mac runner")

    require(heavy, f'name: {policy["heavy"]["aggregate_gate"]}', "heavy aggregate gate is missing")
    require(heavy, "workflow_dispatch:", "Heavy CI must remain manually runnable")
    require(heavy, "push:\n    branches:\n      - main", "Heavy CI must run automatically on main")
    labels = ", ".join(policy["heavy"]["runner_labels"])
    require(heavy, f"runs-on: [{labels}]", "heavy implementation jobs must target the ForestIQ Mac Studio labels")
    for job in policy["heavy"]["jobs"]:
        require(heavy, f"  {job}:", f"heavy job ownership drifted: {job}")

    if policy["heavy"]["untrusted_prs_allowed"] is False:
        require(heavy, "github.event.pull_request.head.repo.full_name", "Heavy CI must keep the fork/untrusted PR trust boundary")
        require(heavy, "ubuntu-latest", "fork Heavy CI classifier/gate must stay hosted")
        require(heavy, trusted_labels, "trusted Heavy CI classifier/gate must target the Mac runner")

    require(e2e, 'default: \'["self-hosted","macOS","ARM64","forestiq","heavy-ci"]\'', "reusable E2E must default to the heavy runner")
    require(e2e, "if: ${{ always() }}", "E2E cleanup must run even after failure")

    print("ForestIQ trusted/untrusted CI runner-boundary contract is valid.")


if __name__ == "__main__":
    main()
