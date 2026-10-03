import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "scripts" / "ci" / "capture_runner_reboot_evidence.sh").read_text(encoding="utf-8")


class RunnerRebootCaptureContractTest(unittest.TestCase):
    def test_downloads_two_distinct_workflow_runs(self):
        self.assertIn('gh run download "$before_run"', SCRIPT)
        self.assertIn('gh run download "$after_run"', SCRIPT)

    def test_uses_acceptance_json_and_reboot_verifier(self):
        self.assertIn("acceptance.json", SCRIPT)
        self.assertIn("verify_runner_reboot.py", SCRIPT)
        self.assertIn("--json-report", SCRIPT)

    def test_does_not_restart_or_modify_runner(self):
        self.assertNotIn("svc.sh", SCRIPT)
        self.assertNotIn("install-self-hosted-runner", SCRIPT)


if __name__ == "__main__":
    unittest.main()
