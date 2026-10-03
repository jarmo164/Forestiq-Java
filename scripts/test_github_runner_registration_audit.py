import unittest

from scripts.github_runner_registration_audit import evaluate


def payload(status="online", busy=False, labels=None):
    labels = labels or ["self-hosted", "macOS", "ARM64", "forestiq", "heavy-ci"]
    return {
        "total_count": 1,
        "runners": [{
            "id": 1,
            "name": "forestiq-mac-studio",
            "status": status,
            "busy": busy,
            "labels": [{"name": label} for label in labels],
        }],
    }


class RunnerRegistrationAuditTest(unittest.TestCase):
    def test_online_idle_labeled_runner_passes(self):
        self.assertEqual(evaluate(payload()), [])

    def test_offline_runner_fails(self):
        failures = evaluate(payload(status="offline"))
        self.assertTrue(any("status" in failure for failure in failures))

    def test_missing_heavy_label_fails(self):
        failures = evaluate(payload(labels=["self-hosted", "macOS", "ARM64", "forestiq"]))
        self.assertIn("missing labels: heavy-ci", failures)

    def test_duplicate_name_fails_closed(self):
        current = payload()
        current["runners"].append(dict(current["runners"][0]))
        failures = evaluate(current)
        self.assertTrue(any("exactly one runner" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
