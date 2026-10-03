import unittest

from scripts.ci.runner_environment_security import evaluate


POLICY = {
    "forbidden_environment_names": [
        "DATABASE_PASSWORD",
        "WEASEL_API_ACCESS_TOKEN",
    ]
}


class RunnerEnvironmentSecurityTest(unittest.TestCase):
    def test_clean_environment_passes(self):
        self.assertEqual(evaluate(POLICY, {"PATH", "HOME", "GITHUB_RUN_ID"}), [])

    def test_forbidden_names_fail_without_reading_values(self):
        leaked = evaluate(POLICY, {"PATH", "DATABASE_PASSWORD", "WEASEL_API_ACCESS_TOKEN"})
        self.assertEqual(leaked, ["DATABASE_PASSWORD", "WEASEL_API_ACCESS_TOKEN"])


if __name__ == "__main__":
    unittest.main()
