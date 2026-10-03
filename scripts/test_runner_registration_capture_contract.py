import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "scripts" / "capture-runner-registration-evidence.sh").read_text(encoding="utf-8")


class RunnerRegistrationCaptureContractTest(unittest.TestCase):
    def test_uses_repository_scoped_runner_endpoint(self):
        self.assertIn('gh api "repos/$repo/actions/runners"', SCRIPT)
        self.assertNotIn("/orgs/", SCRIPT)

    def test_runs_machine_checkable_registration_audit(self):
        self.assertIn("github_runner_registration_audit.py", SCRIPT)
        self.assertIn("--runner-name", SCRIPT)
        self.assertIn("--report", SCRIPT)

    def test_does_not_modify_runner_registration(self):
        self.assertNotIn("registration-token", SCRIPT)
        self.assertNotIn("--method POST", SCRIPT)
        self.assertNotIn("--method DELETE", SCRIPT)


if __name__ == "__main__":
    unittest.main()
