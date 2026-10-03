import unittest

from scripts.build_metsaregister_cutover_evidence import build


class MetsaregisterCutoverEvidenceCliTest(unittest.TestCase):
    def test_ready_evidence(self):
        evidence = build({
            "layers": [{
                "layerName": "forest",
                "javaGenerationId": "j1",
                "pythonGenerationId": "p1",
                "javaRowCount": 10,
                "pythonRowCount": 10,
                "matchingStableIds": 10,
                "geometryBoundsMatch": True,
                "sourceMetadataMatch": True,
            }],
            "successfulPythonRuns": 2,
            "notificationSamplesMatch": True,
            "forestPlanSamplesMatch": True,
            "rollbackVerified": True,
        }, "abcdef123456")
        self.assertEqual(evidence["status"], "READY")
        self.assertTrue(evidence["parityReady"])
        self.assertEqual(evidence["failures"], [])

    def test_row_mismatch_fails_closed(self):
        evidence = build({
            "layers": [{
                "layerName": "forest",
                "javaRowCount": 10,
                "pythonRowCount": 9,
                "matchingStableIds": 9,
                "geometryBoundsMatch": True,
                "sourceMetadataMatch": True,
            }],
            "successfulPythonRuns": 2,
            "notificationSamplesMatch": True,
            "forestPlanSamplesMatch": True,
            "rollbackVerified": True,
        }, "abcdef123456")
        self.assertEqual(evidence["status"], "PENDING")
        self.assertIn("layer:forest", evidence["failures"])


if __name__ == "__main__":
    unittest.main()
