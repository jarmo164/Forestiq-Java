import json
import tempfile
import unittest
from pathlib import Path

from scripts.validate_ingestion_cutover_bundle import validate_bundle


REQUIREMENTS = {
    "sources": [
        {"key": "a", "issue": 1, "evidence_file": "a.json", "required_disposition": "PYTHON_ETL"},
        {"key": "mixed", "issue": 2, "evidence_file": "mixed.json", "required_disposition": "MIXED"},
    ]
}


def ready(source, issue, disposition):
    return {
        "source": source,
        "issue": issue,
        "disposition": disposition,
        "status": "READY",
        "repositorySha": "abcdef123456",
        "parityReady": True,
        "successfulPythonRuns": 2,
        "rollbackVerified": True,
    }


class IngestionCutoverBundleTest(unittest.TestCase):
    def test_complete_bundle_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "a.json").write_text(json.dumps(ready("a", 1, "PYTHON_ETL")))
            (root / "mixed.json").write_text(json.dumps(ready("mixed", 2, "MIXED")))
            self.assertEqual(validate_bundle(REQUIREMENTS, root), [])

    def test_missing_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            failures = validate_bundle(REQUIREMENTS, Path(temp))
            self.assertTrue(any("missing evidence file" in failure for failure in failures))

    def test_python_etl_source_cannot_use_exception(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "a.json").write_text(json.dumps({
                "source": "a",
                "issue": 1,
                "disposition": "PYTHON_ETL",
                "status": "EXCEPTION",
                "repositorySha": "abcdef123456",
                "exceptionReason": "not allowed",
                "authorizationBound": True,
            }))
            (root / "mixed.json").write_text(json.dumps(ready("mixed", 2, "MIXED")))
            failures = validate_bundle(REQUIREMENTS, root)
            self.assertIn("a: exception is not allowed for PYTHON_ETL source", failures)

    def test_mixed_realtime_exception_requires_authorization_boundary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "a.json").write_text(json.dumps(ready("a", 1, "PYTHON_ETL")))
            (root / "mixed.json").write_text(json.dumps({
                "source": "mixed",
                "issue": 2,
                "disposition": "MIXED",
                "status": "EXCEPTION",
                "repositorySha": "abcdef123456",
                "exceptionReason": "request-time domain operation",
                "authorizationBound": False,
            }))
            failures = validate_bundle(REQUIREMENTS, root)
            self.assertIn("mixed: real-time exception must be authorization-bound", failures)


if __name__ == "__main__":
    unittest.main()
