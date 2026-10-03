import unittest

from scripts.build_geospatial_cutover_evidence import build


class GeospatialCutoverEvidenceCliTest(unittest.TestCase):
    def test_ready_evidence(self):
        evidence = build({
            "datasets": [{
                "datasetKey": "CADASTRE_PUBLIC_DATA",
                "currentRowCount": 100,
                "pythonRowCount": 100,
                "identityMatch": True,
                "geometryBoundsMatch": True,
                "sampleMatch": True,
            }],
            "failedImportPreservedCurrentGeneration": True,
            "unchangedSnapshotWasNoop": True,
            "javaReadModelCompatible": True,
            "successfulPythonRuns": 2,
        }, "abcdef123456")
        self.assertEqual(evidence["status"], "READY")
        self.assertTrue(evidence["rollbackVerified"])

    def test_missing_noop_proof_fails_closed(self):
        evidence = build({
            "datasets": [{
                "datasetKey": "CADASTRE_PUBLIC_DATA",
                "currentRowCount": 100,
                "pythonRowCount": 100,
                "identityMatch": True,
                "geometryBoundsMatch": True,
                "sampleMatch": True,
            }],
            "failedImportPreservedCurrentGeneration": True,
            "unchangedSnapshotWasNoop": False,
            "javaReadModelCompatible": True,
            "successfulPythonRuns": 2,
        }, "abcdef123456")
        self.assertEqual(evidence["status"], "PENDING")
        self.assertIn("unchangedSnapshotWasNoop:false", evidence["failures"])


if __name__ == "__main__":
    unittest.main()
