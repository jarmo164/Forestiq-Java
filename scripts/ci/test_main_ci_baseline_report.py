import unittest

from scripts.ci.main_ci_baseline_report import evaluate


class MainCiBaselineReportTest(unittest.TestCase):
    def test_same_sha_requires_all_canonical_workflows(self):
        sha = "abc123"
        runs = [
            {"workflowName": "CI", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T09:00:00Z"},
            {"workflowName": "Heavy CI", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T09:01:00Z"},
            {"workflowName": "Ingestion Contract", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T09:02:00Z"},
        ]
        ready, failures, _ = evaluate(runs, sha)
        self.assertTrue(ready)
        self.assertEqual(failures, [])

    def test_old_green_run_does_not_prove_current_main(self):
        runs = [
            {"workflowName": "CI", "headSha": "old", "conclusion": "success", "createdAt": "2026-09-22T08:00:00Z"},
        ]
        ready, failures, _ = evaluate(runs, "new")
        self.assertFalse(ready)
        self.assertTrue(any("missing run" in failure for failure in failures))

    def test_latest_run_for_same_sha_must_be_green(self):
        sha = "abc123"
        runs = [
            {"workflowName": "CI", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T08:00:00Z"},
            {"workflowName": "CI", "headSha": sha, "conclusion": "failure", "createdAt": "2026-09-22T09:00:00Z"},
            {"workflowName": "Heavy CI", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T09:01:00Z"},
            {"workflowName": "Ingestion Contract", "headSha": sha, "conclusion": "success", "createdAt": "2026-09-22T09:02:00Z"},
        ]
        ready, failures, _ = evaluate(runs, sha)
        self.assertFalse(ready)
        self.assertIn("CI: latest conclusion is failure", failures)


if __name__ == "__main__":
    unittest.main()
