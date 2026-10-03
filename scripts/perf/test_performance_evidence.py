#!/usr/bin/env python3
from __future__ import annotations

import unittest

from performance_evidence import build_evidence


class PerformanceEvidenceTest(unittest.TestCase):
    def query(self, commit: str = "abc") -> dict:
        return {
            "status": "PASS",
            "commit": commit,
            "environment": {"postgresVersion": "17.6", "migrationVersion": "71"},
        }

    def active_map(self, commit: str = "abc") -> dict:
        return {
            "status": "PASS",
            "commit": commit,
            "environment": {"postgresVersion": "17.6", "migrationVersion": "71"},
        }

    def mvt(self, commit: str = "abc") -> dict:
        return {
            "passed": True,
            "commit": commit,
            "environment": {"postgresVersion": "17.6", "migrationVersion": "71"},
            "catalogVersion": "2026-09-07",
            "generationId": "11111111-1111-1111-1111-111111111111",
        }

    def test_performance_profile_binds_both_artifacts(self) -> None:
        result = build_evidence(
            profile="performance",
            commit="abc",
            command="python scripts/perf/run_release_performance_gate.py",
            query_plan=self.query(),
            active_map=self.active_map(),
            mvt=self.mvt(),
            query_plan_hash="qhash",
            mvt_hash="mhash",
        )
        self.assertEqual("PASS", result["decision"])
        self.assertEqual("71", result["environment"]["migrationVersion"])
        self.assertEqual("2026-09-07", result["environment"]["catalogVersion"])
        self.assertEqual("qhash", result["artifacts"]["queryPlan"]["sha256"])

    def test_commit_mismatch_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "commit mismatch"):
            build_evidence(
                profile="performance",
                commit="expected",
                command="python scripts/perf/run_release_performance_gate.py",
                query_plan=self.query("other"),
                active_map=self.active_map("expected"),
                mvt=self.mvt("expected"),
            )

    def test_failed_artifact_fails_closed(self) -> None:
        query = self.query()
        query["status"] = "FAIL"
        with self.assertRaisesRegex(ValueError, "not PASS"):
            build_evidence(
                profile="performance",
                commit="abc",
                command="python scripts/perf/run_release_performance_gate.py",
                query_plan=query,
                active_map=self.active_map(),
                mvt=self.mvt(),
            )

    def test_standard_profile_does_not_require_artifacts(self) -> None:
        result = build_evidence(
            profile="standard",
            commit="abc",
            command="classifier-only",
            query_plan=None,
            active_map=None,
            mvt=None,
        )
        self.assertEqual("NOT_REQUIRED", result["decision"])


if __name__ == "__main__":
    unittest.main()
