#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import statistics
from collections import defaultdict
from pathlib import Path

REQUIRED = (
    "session",
    "role",
    "scenario",
    "variant",
    "success",
    "seconds",
    "help",
    "errors",
    "recovery",
    "confidence",
    "observation",
)
ALLOWED_ROLES = {"CALLER", "EVALUATOR", "SALES_MANAGER", "ORG_ADMIN"}
ALLOWED_SUCCESS = {"completed", "completed_with_help", "failed"}
FORBIDDEN_COLUMNS = {
    "name", "full_name", "email", "phone", "personal_code", "registry_code",
    "customer", "password", "token", "authorization", "recording_url",
}


def load(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        fields = [field.strip() for field in (reader.fieldnames or [])]
        missing = [field for field in REQUIRED if field not in fields]
        if missing:
            raise ValueError(f"Missing required columns: {', '.join(missing)}")
        unsafe = [field for field in fields if field.lower() in FORBIDDEN_COLUMNS]
        if unsafe:
            raise ValueError("Do not store participant/customer identifiers in the evidence CSV: " + ", ".join(unsafe))
        rows = list(reader)

    if not rows:
        raise ValueError("Usability evidence CSV is empty.")

    for line, row in enumerate(rows, start=2):
        if row["role"] not in ALLOWED_ROLES:
            raise ValueError(f"Row {line}: unsupported role {row['role']!r}.")
        if row["success"] not in ALLOWED_SUCCESS:
            raise ValueError(f"Row {line}: unsupported success value {row['success']!r}.")
        seconds = float(row["seconds"])
        errors = int(row["errors"])
        confidence = int(row["confidence"])
        if seconds < 0 or errors < 0 or confidence not in range(1, 6):
            raise ValueError(f"Row {line}: invalid timing/errors/confidence values.")
    return rows


def _median(values: list[float]) -> str:
    return f"{statistics.median(values):.1f}" if values else "—"


def report(rows: list[dict[str, str]]) -> tuple[str, bool]:
    sessions = sorted({row["session"] for row in rows})
    roles = sorted({row["role"] for row in rows})
    by_scenario: dict[str, list[dict[str, str]]] = defaultdict(list)
    by_variant: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)

    for row in rows:
        by_scenario[row["scenario"]].append(row)
        if row["variant"].strip():
            by_variant[(row["scenario"], row["variant"])].append(row)

    completed_gate = len(sessions) >= 5 and len(roles) >= 3

    lines = [
        "# ForestIQ role-based usability baseline",
        "",
        f"- Sessions: **{len(sessions)}**",
        f"- Role profiles: **{len(roles)}** ({', '.join(roles)})",
        f"- Minimum sample gate: **{'PASS' if completed_gate else 'INCOMPLETE'}**",
        "",
        "## Scenario metrics",
        "",
        "| Scenario | Tasks | Completion % | Median seconds | Help % | Median errors | Median confidence |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]

    for scenario in sorted(by_scenario):
        group = by_scenario[scenario]
        completed = sum(row["success"] != "failed" for row in group)
        helped = sum(row["help"] != "none" for row in group)
        times = [float(row["seconds"]) for row in group]
        errors = [float(row["errors"]) for row in group]
        confidence = [float(row["confidence"]) for row in group]
        lines.append(
            f"| {scenario} | {len(group)} | {100 * completed / len(group):.1f} | "
            f"{_median(times)} | {100 * helped / len(group):.1f} | {_median(errors)} | {_median(confidence)} |"
        )

    lines += ["", "## Variant evidence", ""]
    if not by_variant:
        lines.append("No variant rows recorded.")
    else:
        lines += [
            "| Scenario | Variant | Tasks | Completion % | Median seconds | Median confidence |",
            "| --- | --- | ---: | ---: | ---: | ---: |",
        ]
        for (scenario, variant), group in sorted(by_variant.items()):
            completed = sum(row["success"] != "failed" for row in group)
            lines.append(
                f"| {scenario} | {variant} | {len(group)} | "
                f"{100 * completed / len(group):.1f} | "
                f"{_median([float(row['seconds']) for row in group])} | "
                f"{_median([float(row['confidence']) for row in group])} |"
            )

    lines += [
        "",
        "## Completion gate",
        "",
        f"- [{'x' if len(sessions) >= 5 else ' '}] At least 5 anonymous sessions",
        f"- [{'x' if len(roles) >= 3 else ' '}] At least 3 role profiles",
        "- [ ] High-impact findings linked to existing/new backlog issues",
        "- [ ] Two critical-flow variant decisions documented with selected and rejected alternative",
        "",
        "The script validates sample size and aggregate metrics only. Human review is still required for backlog links and product decisions.",
        "",
    ]
    return "\n".join(lines), completed_gate


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate anonymous ForestIQ usability sessions.")
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-sample", action="store_true")
    args = parser.parse_args()

    text, sample_ok = report(load(args.input))
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        print(args.output)
    else:
        print(text)

    if args.require_sample and not sample_ok:
        raise SystemExit("Usability sample gate is incomplete: need >=5 sessions and >=3 roles.")


if __name__ == "__main__":
    main()
