#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

REQUIRED_LABELS = {"self-hosted", "macOS", "ARM64", "forestiq", "heavy-ci"}
DEFAULT_RUNNER = "forestiq-mac-studio"


def load(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("runner export must be a JSON object")
    return value


def evaluate(payload: dict, expected_name: str = DEFAULT_RUNNER) -> list[str]:
    failures: list[str] = []
    runners = payload.get("runners")
    if not isinstance(runners, list):
        return ["runners array is missing"]

    matches = [runner for runner in runners if runner.get("name") == expected_name]
    if len(matches) != 1:
        return [f"expected exactly one runner named {expected_name}, found {len(matches)}"]

    runner = matches[0]
    if runner.get("status") != "online":
        failures.append(f"runner status={runner.get('status')!r}, expected 'online'")
    if runner.get("busy") is not False:
        failures.append(f"runner busy={runner.get('busy')!r}, expected false during audit")

    labels = {
        label.get("name")
        for label in runner.get("labels", [])
        if isinstance(label, dict) and label.get("name")
    }
    missing = sorted(REQUIRED_LABELS - labels)
    if missing:
        failures.append("missing labels: " + ", ".join(missing))

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate ForestIQ repository runner registration.")
    parser.add_argument("runner_json", type=Path)
    parser.add_argument("--runner-name", default=DEFAULT_RUNNER)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    payload = load(args.runner_json)
    failures = evaluate(payload, args.runner_name)
    text = "\n".join([
        "# ForestIQ runner registration audit",
        "",
        f"- Runner: `{args.runner_name}`",
        f"- Result: **{'PASS' if not failures else 'FAIL'}**",
        "",
        *([ "## Blocking evidence", "", *[f"- {failure}" for failure in failures] ] if failures else [
            "The repository-scoped runner is online, idle and exposes all required ForestIQ heavy-CI labels."
        ]),
        "",
    ])

    if args.report:
        args.report.write_text(text, encoding="utf-8")
        print(args.report)
    else:
        print(text)

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
