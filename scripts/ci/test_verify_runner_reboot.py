import unittest

from scripts.ci.verify_runner_reboot import evaluate


def evidence(boot, run, status="PASS"):
    return {
        "acceptance": status,
        "runnerName": "forestiq-mac-studio",
        "runnerOs": "macOS",
        "runnerArch": "ARM64",
        "bootEpoch": boot,
        "runId": run,
    }


class RunnerRebootEvidenceTest(unittest.TestCase):
    def test_later_boot_and_distinct_green_runs_pass(self):
        self.assertEqual(evaluate(evidence(100, "1"), evidence(200, "2")), [])

    def test_same_boot_does_not_prove_recovery(self):
        failures = evaluate(evidence(100, "1"), evidence(100, "2"))
        self.assertIn("after evidence does not prove a later system boot", failures)

    def test_failed_after_run_blocks_acceptance(self):
        failures = evaluate(evidence(100, "1"), evidence(200, "2", "FAIL"))
        self.assertIn("after acceptance is not PASS", failures)


if __name__ == "__main__":
    unittest.main()
