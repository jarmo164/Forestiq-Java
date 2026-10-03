import importlib.util
import pathlib
import sys
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("verify-production-ingestion.py")
SPEC = importlib.util.spec_from_file_location("verify_production_ingestion", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VerificationFunctionsTest(unittest.TestCase):
    def test_consecutive_scheduled_successes_ignores_manual(self):
        runs = [
            {"trigger": "MANUAL", "state": "SUCCEEDED"},
            {"trigger": "SCHEDULED", "state": "SUCCEEDED"},
            {"trigger": "SCHEDULED", "state": "SUCCEEDED"},
            {"trigger": "SCHEDULED", "state": "FAILED"},
            {"trigger": "SCHEDULED", "state": "SUCCEEDED"},
        ]
        self.assertEqual(2, MODULE.consecutive_scheduled_successes(runs))

    def test_generation_is_read_from_metrics_json(self):
        job = {"latestSuccess": {"metrics": '{"publishedGeneration":"g-42"}'}}
        self.assertEqual("g-42", MODULE.published_generation(job))

    def test_operational_state_fails_closed(self):
        failures = MODULE.verify_operational_state({"queueDepth": 0})
        self.assertIn("operations: workerHeartbeatAgeSeconds is missing", failures)
        self.assertIn("operations: worker heartbeat is not healthy", failures)


if __name__ == "__main__":
    unittest.main()
