import unittest

from scripts.validate_legacy_ingestion_cleanup import evaluate_cleanup


PLAN = {
    "sources": [
        {"key": "metsaregister", "java_candidates": ["LegacyA"], "retain": []},
        {"key": "rik_metsis", "java_candidates": [], "retain": ["RIK object count SOAP call"]},
    ]
}


def ready():
    return {
        "status": "READY",
        "parityReady": True,
        "successfulPythonRuns": 2,
        "rollbackVerified": True,
    }


class LegacyIngestionCleanupTest(unittest.TestCase):
    def test_ready_source_is_eligible(self):
        eligible, blocked = evaluate_cleanup(PLAN, {
            "metsaregister": ready(),
            "rik_metsis": ready(),
        })
        self.assertEqual(eligible, ["metsaregister", "rik_metsis"])
        self.assertEqual(blocked, [])

    def test_missing_parity_blocks_cleanup(self):
        pending = ready()
        pending["parityReady"] = False
        eligible, blocked = evaluate_cleanup(PLAN, {
            "metsaregister": pending,
            "rik_metsis": ready(),
        })
        self.assertNotIn("metsaregister", eligible)
        self.assertIn("metsaregister: parityReady is not true", blocked)

    def test_documented_java_exception_is_retained(self):
        eligible, blocked = evaluate_cleanup(PLAN, {
            "metsaregister": ready(),
            "rik_metsis": {
                "status": "EXCEPTION",
                "parityReady": False,
                "successfulPythonRuns": 0,
                "rollbackVerified": True,
            },
        })
        self.assertEqual(eligible, ["metsaregister"])
        self.assertTrue(any("Java exception retained" in item for item in blocked))


if __name__ == "__main__":
    unittest.main()
