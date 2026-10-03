#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
PIN = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"^\s*uses:\s*([^\s#]+)", re.MULTILINE)

def main() -> int:
    failures: list[str] = []
    checked = 0
    for path in sorted([*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")]):
        text = path.read_text(encoding="utf-8")
        for target in USES.findall(text):
            if target.startswith("./") or target.startswith("docker://"):
                continue
            checked += 1
            if "@" not in target:
                failures.append(f"{path.relative_to(ROOT)}: action has no ref: {target}")
                continue
            action, ref = target.rsplit("@", 1)
            if not PIN.fullmatch(ref):
                failures.append(f"{path.relative_to(ROOT)}: mutable action ref: {action}@{ref}")
    if failures:
        for failure in failures:
            print("FAIL " + failure)
        return 1
    print(f"GitHub Actions pin contract is valid ({checked} external references).")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
