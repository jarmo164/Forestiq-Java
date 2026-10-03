#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
STATUS=ROOT/"config"/"ci-cd-governance-status.json"
ALLOWED=("code_merged","configured","live_verified")

def validate(data: dict) -> list[str]:
    failures=[]
    order=data.get("status_order")
    if order!=list(ALLOWED):
        failures.append("status_order must be code_merged -> configured -> live_verified")
    seen=set()
    for item in data.get("issues",[]):
        number=item.get("issue")
        if number in seen:
            failures.append(f"duplicate issue #{number}")
        seen.add(number)
        status=item.get("status")
        if status not in ALLOWED:
            failures.append(f"#{number}: invalid status {status}")
            continue
        code=item.get("codeEvidence") or []
        if not code:
            failures.append(f"#{number}: codeEvidence is required")
        live=item.get("liveEvidence")
        if status=="live_verified" and not live:
            failures.append(f"#{number}: live_verified requires liveEvidence")
        if item.get("state")=="closed" and item.get("blocking"):
            failures.append(f"#{number}: closed issue cannot keep a blocking reason")
        if item.get("state")=="open" and not item.get("blocking"):
            failures.append(f"#{number}: open issue must explain blocking evidence")
    return failures

def main()->int:
    data=json.loads(STATUS.read_text(encoding="utf-8"))
    failures=validate(data)
    if failures:
        for failure in failures:
            print("FAIL "+failure)
        return 1
    print("CI/CD governance status model is internally consistent.")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
