import unittest

from scripts.build_owner_flows_cutover_evidence import build


def source(job_key):
    return {
        "jobKey": job_key,
        "currentCount": 10,
        "pythonCount": 10,
        "stableIdsMatch": True,
        "selectedBusinessCasesMatch": True,
        "successfulPythonRuns": 2,
    }


class OwnerFlowsCutoverEvidenceCliTest(unittest.TestCase):
    def ready_raw(self):
        return {
            "sources": [
                source("OWNER_CADASTRE_RELATIONS"),
                source("WEASEL_OWNERSHIP_TRANSITIONS"),
                source("INHERITANCE_NOTICES"),
            ],
            "multiOwnerPreserved": True,
            "relationEndReactivatePreserved": True,
            "manualOverridePreserved": True,
            "retryIdempotent": True,
            "failedRunPreservedWatermark": True,
        }

    def test_ready_evidence(self):
        evidence = build(self.ready_raw(), "abcdef123456")
        self.assertEqual(evidence["status"], "READY")
        self.assertTrue(evidence["rollbackVerified"])
        self.assertEqual(evidence["successfulPythonRuns"], 2)

    def test_missing_source_fails_closed(self):
        raw = self.ready_raw()
        raw["sources"] = raw["sources"][:2]
        evidence = build(raw, "abcdef123456")
        self.assertEqual(evidence["status"], "PENDING")
        self.assertTrue(any("INHERITANCE_NOTICES:missing" in item for item in evidence["failures"]))

    def test_manual_override_regression_fails_closed(self):
        raw = self.ready_raw()
        raw["manualOverridePreserved"] = False
        evidence = build(raw, "abcdef123456")
        self.assertIn("manualOverridePreserved:false", evidence["failures"])


if __name__ == "__main__":
    unittest.main()
