#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

REQUIRED_WORKFLOWS = ("CI", "Heavy CI", "Ingestion Contract")


def load(path: Path) -> list[dict]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list):
        raise ValueError("workflow run export must be a JSON list")
    return [item for item in value if isinstance(item, dict)]


def evaluate(runs: list[dict], main_sha: str) -> tuple[bool, list[str], dict[str, dict]]:
    failures: list[str] = []
    latest: dict[str, dict] = {}

    for workflow in REQUIRED_WORKFLOWS:
        candidates = [
            run for run in runs
            if run.get("workflowName") == workflow and run.get("headSha") == main_sha
        ]
        if not candidates:
            failures.append(f"{workflow}: missing run for {main_sha}")
            continue

        candidates.sort(key=lambda run: str(run.get("createdAt", "")), reverse=True)
        current = candidates[0]
        latest[workflow] = current
        if current.get("conclusion") != "success":
            failures.append(
                f"{workflow}: latest conclusion is {current.get('conclusion') or 'missing'}"
            )

    return not failures, failures, latest


def render(main_sha: str, ready: bool, failures: list[str], latest: dict[str, dict]) -> str:
    lines = [
        "# ForestIQ main CI baseline evidence",
        "",
        f"- Main SHA: `{main_sha}`",
        f"- Baseline: **{'GREEN' if ready else 'NOT PROVEN'}**",
        "",
        "| Workflow | Conclusion | Created | URL |",
        "| --- | --- | --- | --- |",
    ]
    for workflow in REQUIRED_WORKFLOWS:
        run = latest.get(workflow, {})
        lines.append(
            f"| {workflow} | {run.get('conclusion', 'missing')} | "
            f"{run.get('createdAt', '—')} | {run.get('url', '—')} |"
        )

    if failures:
        lines += ["", "## Blocking evidence", ""]
        lines += [f"- {failure}" for failure in failures]

    lines += [
        "",
        "A self-hosted fallback run is useful diagnostic evidence but does not make a missing canonical required workflow green.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a single ForestIQ main SHA against canonical CI workflows.")
    parser.add_argument("runs", type=Path, help="JSON exported by gh run list")
    parser.add_argument("--main-sha", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-green", action="store_true")
    args = parser.parse_args()

    ready, failures, latest = evaluate(load(args.runs), args.main_sha)
    report = render(args.main_sha, ready, failures, latest)
    if args.output:
        args.output.write_text(report, encoding="utf-8")
        print(args.output)
    else:
        print(report)

    if args.require_green and not ready:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
