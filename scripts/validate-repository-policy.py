#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / ".github" / "forestiq-repository-policy.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"Repository policy contract failed: {message}")


def main() -> None:
    policy = json.loads(POLICY.read_text(encoding="utf-8"))

    require(policy["branch"] == "main", "protected branch must be main")
    require(
        policy["required_status_checks"]["contexts"]
        == ["fast-quality-gate", "heavy-quality-gate"],
        "required aggregate gate names drifted",
    )
    require(policy["required_status_checks"]["strict"] is True, "required checks must be strict")
    require(policy["pull_request"]["required"] is True, "pull requests must remain required")
    require(policy["allow_force_pushes"] is False, "force pushes must remain disabled")
    require(policy["allow_deletions"] is False, "main deletion must remain disabled")
    require(policy["merge"]["allow_auto_merge"] is True, "auto-merge policy must remain enabled")
    require(policy["merge"]["allow_squash_merge"] is True, "squash merge must remain enabled")
    require(policy["merge"]["allow_merge_commit"] is False, "merge commits must remain disabled")
    require(policy["merge"]["allow_rebase_merge"] is False, "rebase merge must remain disabled")

    fast = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    heavy = (ROOT / ".github" / "workflows" / "heavy-verification.yml").read_text(encoding="utf-8")
    protect = (ROOT / "scripts" / "configure-main-protection.sh").read_text(encoding="utf-8")
    auto = (ROOT / "scripts" / "safe-auto-merge.sh").read_text(encoding="utf-8")

    for check in policy["required_status_checks"]["contexts"]:
        require(f"name: {check}" in (fast + heavy), f"workflow does not emit {check}")

    require("forestiq-repository-policy.json" in protect, "protection script must consume policy file")
    require("forestiq-repository-policy.json" in auto, "auto-merge script must consume policy file")
    merge_commands = [
        line for line in auto.splitlines()
        if re.match(r"^\s*gh\s+pr\s+merge\b", line)
    ]
    require(merge_commands, "auto-merge helper must issue a pull request merge command")
    require(
        all("--admin" not in command for command in merge_commands),
        "auto-merge must never use admin bypass",
    )

    print("ForestIQ repository policy contract is valid.")


if __name__ == "__main__":
    main()
