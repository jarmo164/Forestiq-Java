#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest

from validate_query_plans import (PlanValidationError, aggregate_runs, compare_to_baseline, validate_output)


REQUIRED = (
    "owner_search",
    "cadastral_lookup",
    "protected_mvt",
    "stale_registry",
    "durable_queue",
)


def explain(name: str, relation: str, *, actual_rows: int = 1, removed: int = 0) -> str:
    payload = [
        {
            "Plan": {
                "Node Type": "Seq Scan",
                "Relation Name": relation,
                "Actual Rows": actual_rows,
                "Rows Removed by Filter": removed,
                "Actual Loops": 1,
            },
            "Planning Time": 0.1,
            "Execution Time": 0.2,
        }
    ]
    return f"__FORESTIQ_PLAN__:{name}\n{json.dumps(payload)}"


def valid_fixture() -> str:
    relations = {
        "owner_search": "owner",
        "cadastral_lookup": "land_parcel",
        "protected_mvt": "land_parcel",
        "stale_registry": "registry_sync_log",
        "durable_queue": "integration_sync_run",
    }
    return "\n".join(explain(name, relations[name]) for name in REQUIRED)


class ValidateQueryPlansTest(unittest.TestCase):
    def test_accepts_bounded_small_scans(self) -> None:
        summaries, violations = validate_output(valid_fixture())
        self.assertEqual(set(REQUIRED), set(summaries))
        self.assertEqual([], violations)

    def test_rejects_large_critical_sequential_scan(self) -> None:
        text = valid_fixture().replace(
            explain("owner_search", "owner"),
            explain("owner_search", "owner", actual_rows=5, removed=150_000),
        )
        _, violations = validate_output(text)
        self.assertTrue(any("owner_search" in item and "owner" in item for item in violations))

    def test_requires_every_critical_plan(self) -> None:
        text = valid_fixture().split("__FORESTIQ_PLAN__:durable_queue", 1)[0]
        with self.assertRaises(PlanValidationError):
            validate_output(text)

    def test_five_run_aggregate_uses_median_and_baseline_regression(self) -> None:
        runs = []
        for execution in (10.0, 11.0, 12.0, 13.0, 100.0):
            text = valid_fixture().replace('"Execution Time": 0.2', f'"Execution Time": {execution}')
            summaries, violations = validate_output(text)
            self.assertEqual([], violations)
            runs.append(summaries)

        aggregate = aggregate_runs(runs)
        self.assertEqual(12.0, aggregate["owner_search"]["medianExecutionMs"])
        baseline = {
            "plans": {
                name: {"medianExecutionMs": 10.0, "medianSharedReadBlocks": 0.0}
                for name in REQUIRED
            }
        }
        violations = compare_to_baseline(aggregate, baseline, max_regression_fraction=0.15)
        self.assertTrue(any("owner_search" in item and "regressed" in item for item in violations))


if __name__ == "__main__":
    unittest.main()
