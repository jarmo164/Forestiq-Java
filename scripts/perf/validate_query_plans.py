#!/usr/bin/env python3
"""Validate ForestIQ PostgreSQL EXPLAIN JSON output for scale regressions."""

from __future__ import annotations

import argparse
import json
import statistics
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MARKER_PREFIX = "__FORESTIQ_PLAN__:"
REQUIRED_PLANS = {
    "owner_search",
    "cadastral_lookup",
    "protected_mvt",
    "stale_registry",
    "durable_queue",
}
CRITICAL_SEQ_SCAN_TABLES = {
    "owner_search": {"owner"},
    "cadastral_lookup": {"land_parcel"},
    "protected_mvt": {"land_parcel", "land_parcel_render"},
    "stale_registry": {"registry_sync_log"},
    "durable_queue": {"integration_sync_run"},
}
REPRESENTATIVE_ROWS = 10_000
MAX_UNBOUNDED_SORT_ROWS = 100_000


class PlanValidationError(RuntimeError):
    pass


@dataclass(frozen=True)
class SeqScan:
    relation: str
    scanned_rows: float
    actual_rows: float
    rows_removed: float
    loops: float


@dataclass(frozen=True)
class PlanSummary:
    planning_ms: float | None
    execution_ms: float | None
    seq_scans: list[SeqScan]
    indexes: list[str]
    shared_read_blocks: int
    shared_hit_blocks: int
    max_sort_rows: float
    has_predicate: bool


def parse_marked_explains(text: str) -> dict[str, Any]:
    plans: dict[str, Any] = {}
    current_name: str | None = None
    buffer: list[str] = []

    def flush() -> None:
        nonlocal current_name, buffer
        if current_name is None:
            return
        raw = "\n".join(buffer).strip()
        if not raw:
            raise PlanValidationError(f"{current_name}: EXPLAIN JSON output is empty")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise PlanValidationError(
                f"{current_name}: invalid EXPLAIN JSON at line {exc.lineno}: {exc.msg}"
            ) from exc
        if not isinstance(payload, list) or not payload or not isinstance(payload[0], dict):
            raise PlanValidationError(f"{current_name}: unexpected EXPLAIN JSON shape")
        plans[current_name] = payload[0]
        buffer = []

    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith(MARKER_PREFIX):
            flush()
            current_name = stripped[len(MARKER_PREFIX):].strip()
            if not current_name:
                raise PlanValidationError("empty plan marker")
            if current_name in plans:
                raise PlanValidationError(f"duplicate plan marker: {current_name}")
            continue
        if current_name is not None:
            buffer.append(line)
    flush()
    return plans


def walk_nodes(value: Any):
    if isinstance(value, dict):
        if "Node Type" in value:
            yield value
        for child in value.values():
            yield from walk_nodes(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_nodes(child)


def _number_or_none(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)


def summarize_plan(payload: dict[str, Any]) -> PlanSummary:
    seq_scans: list[SeqScan] = []
    indexes: set[str] = set()
    shared_read_blocks = 0
    shared_hit_blocks = 0
    max_sort_rows = 0.0
    has_predicate = False

    for node in walk_nodes(payload.get("Plan", {})):
        node_type = str(node.get("Node Type", ""))
        relation = str(node.get("Relation Name", ""))
        index_name = node.get("Index Name")
        if index_name:
            indexes.add(str(index_name))
        shared_read_blocks += int(node.get("Shared Read Blocks") or 0)
        shared_hit_blocks += int(node.get("Shared Hit Blocks") or 0)
        if node_type in {"Sort", "Incremental Sort"}:
            max_sort_rows = max(
                max_sort_rows,
                float(node.get("Actual Rows") or 0) * max(float(node.get("Actual Loops") or 1), 1),
            )
        if any(key in node for key in ("Filter", "Index Cond", "Recheck Cond", "Hash Cond", "Join Filter")):
            has_predicate = True

        if node_type == "Seq Scan" and relation:
            loops = float(node.get("Actual Loops") or 1)
            actual_rows = float(node.get("Actual Rows") or 0)
            rows_removed = float(node.get("Rows Removed by Filter") or 0)
            scanned_rows = (actual_rows + rows_removed) * max(loops, 1)
            seq_scans.append(
                SeqScan(
                    relation=relation,
                    scanned_rows=scanned_rows,
                    actual_rows=actual_rows,
                    rows_removed=rows_removed,
                    loops=loops,
                )
            )

    return PlanSummary(
        planning_ms=_number_or_none(payload.get("Planning Time")),
        execution_ms=_number_or_none(payload.get("Execution Time")),
        seq_scans=seq_scans,
        indexes=sorted(indexes),
        shared_read_blocks=shared_read_blocks,
        shared_hit_blocks=shared_hit_blocks,
        max_sort_rows=max_sort_rows,
        has_predicate=has_predicate,
    )


def validate_output(
    text: str,
    *,
    seq_scan_threshold: int = 100_000,
) -> tuple[dict[str, PlanSummary], list[str]]:
    plans = parse_marked_explains(text)
    missing = REQUIRED_PLANS - plans.keys()
    if missing:
        raise PlanValidationError(
            "missing required plan(s): " + ", ".join(sorted(missing))
        )

    summaries = {name: summarize_plan(payload) for name, payload in plans.items()}
    violations: list[str] = []

    for name, tables in CRITICAL_SEQ_SCAN_TABLES.items():
        summary = summaries[name]
        critical_scanned = 0.0
        for scan in summary.seq_scans:
            if scan.relation in tables:
                critical_scanned += scan.scanned_rows
                if scan.scanned_rows >= seq_scan_threshold:
                    violations.append(
                        f"{name}: sequential scan of {scan.relation} touched "
                        f"{scan.scanned_rows:.0f} rows (threshold {seq_scan_threshold})"
                    )

        # Representative plans must demonstrate an index path and predicate.
        # Tiny fixtures are allowed to use PostgreSQL's legitimate Seq Scan choice.
        if critical_scanned >= REPRESENTATIVE_ROWS and not summary.indexes:
            violations.append(f"{name}: representative plan contains no index path")
        if critical_scanned >= REPRESENTATIVE_ROWS and not summary.has_predicate:
            violations.append(f"{name}: representative plan contains no bounded predicate")
        if summary.max_sort_rows >= MAX_UNBOUNDED_SORT_ROWS:
            violations.append(
                f"{name}: sort materialized {summary.max_sort_rows:.0f} rows "
                f"(limit {MAX_UNBOUNDED_SORT_ROWS})"
            )

    return summaries, violations


def aggregate_runs(runs: list[dict[str, PlanSummary]]) -> dict[str, dict[str, float]]:
    if len(runs) < 1:
        raise PlanValidationError("at least one query-plan run is required")
    for run in runs:
        missing = REQUIRED_PLANS - run.keys()
        if missing:
            raise PlanValidationError("run missing plans: " + ", ".join(sorted(missing)))

    result: dict[str, dict[str, float]] = {}
    for name in sorted(REQUIRED_PLANS):
        execution = [r[name].execution_ms for r in runs if r[name].execution_ms is not None]
        reads = [float(r[name].shared_read_blocks) for r in runs]
        result[name] = {
            "medianExecutionMs": statistics.median(execution) if execution else 0.0,
            "medianSharedReadBlocks": statistics.median(reads),
        }
    return result


def compare_to_baseline(
    aggregate: dict[str, dict[str, float]],
    baseline: dict[str, Any],
    *,
    max_regression_fraction: float = 0.15,
) -> list[str]:
    violations: list[str] = []
    baseline_plans = baseline.get("aggregate") or baseline.get("plans", {})
    for name in sorted(REQUIRED_PLANS):
        reference = baseline_plans.get(name)
        if not isinstance(reference, dict):
            raise PlanValidationError(f"baseline missing plan: {name}")
        for metric in ("medianExecutionMs", "medianSharedReadBlocks"):
            old = float(reference.get(metric, 0) or 0)
            current = float(aggregate[name][metric])
            if old <= 0:
                continue
            limit = old * (1.0 + max_regression_fraction)
            if current > limit:
                violations.append(
                    f"{name}: {metric} regressed {((current / old) - 1.0) * 100:.1f}% "
                    f"({current:.2f} > {limit:.2f})"
                )
    return violations


def report_payload(
    source: str,
    summaries: dict[str, PlanSummary],
    violations: list[str],
    seq_scan_threshold: int,
) -> dict[str, Any]:
    return {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "seqScanThresholdRows": seq_scan_threshold,
        "representativeRows": REPRESENTATIVE_ROWS,
        "maxSortRows": MAX_UNBOUNDED_SORT_ROWS,
        "status": "PASS" if not violations else "FAIL",
        "violations": violations,
        "plans": {
            name: {
                "planningMs": summary.planning_ms,
                "executionMs": summary.execution_ms,
                "seqScans": [asdict(scan) for scan in summary.seq_scans],
                "indexes": summary.indexes,
                "sharedReadBlocks": summary.shared_read_blocks,
                "sharedHitBlocks": summary.shared_hit_blocks,
                "maxSortRows": summary.max_sort_rows,
                "hasPredicate": summary.has_predicate,
            }
            for name, summary in sorted(summaries.items())
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--seq-scan-threshold", type=int, default=100_000)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    try:
        text = args.input.read_text(encoding="utf-8")
        summaries, violations = validate_output(
            text, seq_scan_threshold=args.seq_scan_threshold
        )
        report = report_payload(
            str(args.input), summaries, violations, args.seq_scan_threshold
        )
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

        print(f"ForestIQ query-plan gate: {report['status']}")
        for name, summary in sorted(summaries.items()):
            execution = (
                f"{summary.execution_ms:.2f} ms"
                if summary.execution_ms is not None
                else "n/a"
            )
            print(
                f"- {name}: execution={execution}, indexes={len(summary.indexes)}, "
                f"reads={summary.shared_read_blocks}, seq_scans={len(summary.seq_scans)}"
            )
        for violation in violations:
            print(f"FAIL: {violation}")
        return 1 if violations else 0
    except (OSError, PlanValidationError, ValueError) as exc:
        print(f"ForestIQ query-plan gate: ERROR: {exc}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
