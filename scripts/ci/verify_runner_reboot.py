#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def evaluate(before: dict, after: dict) -> list[str]:
    failures: list[str] = []

    for label, item in (("before", before), ("after", after)):
        if item.get("acceptance") != "PASS":
            failures.append(f"{label} acceptance is not PASS")
        if item.get("runnerName") != "forestiq-mac-studio":
            failures.append(f"{label} runnerName is {item.get('runnerName')!r}")
        if item.get("runnerOs") != "macOS" or item.get("runnerArch") != "ARM64":
            failures.append(f"{label} runner context is not macOS/ARM64")

    before_boot = int(before.get("bootEpoch") or 0)
    after_boot = int(after.get("bootEpoch") or 0)
    if before_boot <= 0 or after_boot <= 0:
        failures.append("boot epoch is missing")
    elif after_boot <= before_boot:
        failures.append("after evidence does not prove a later system boot")

    if str(after.get("runId")) == str(before.get("runId")):
        failures.append("before and after evidence use the same workflow run")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Mac Studio runner recovery across a real reboot.")
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()

    before = load(args.before)
    after = load(args.after)
    failures = evaluate(before, after)
    passed = not failures

    lines = [
        "# ForestIQ runner reboot recovery evidence",
        "",
        f"- Result: **{'PASS' if passed else 'FAIL'}**",
        f"- Runner: `{after.get('runnerName', 'unknown')}`",
        f"- Before run: `{before.get('runId', 'unknown')}`",
        f"- After run: `{after.get('runId', 'unknown')}`",
        f"- Before boot epoch: `{before.get('bootEpoch', 'unknown')}`",
        f"- After boot epoch: `{after.get('bootEpoch', 'unknown')}`",
        "",
    ]
    if failures:
        lines += ["## Blocking evidence", ""] + [f"- {failure}" for failure in failures]
    else:
        lines.append("The runner returned healthy after a later Mac Studio boot without reusing the same workflow run.")

    rendered = "\n".join(lines) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
        print(args.report)
    else:
        print(rendered, end="")

    if args.json_report:
        payload = {
            "schemaVersion": 1,
            "result": "PASS" if passed else "FAIL",
            "runnerName": after.get("runnerName"),
            "beforeRunId": str(before.get("runId")),
            "afterRunId": str(after.get("runId")),
            "beforeBootEpoch": int(before.get("bootEpoch") or 0),
            "afterBootEpoch": int(after.get("bootEpoch") or 0),
            "failures": failures,
        }
        args.json_report.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(args.json_report)

    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
