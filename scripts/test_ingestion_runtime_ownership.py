import copy
import json
import unittest
from pathlib import Path

from scripts.validate_ingestion_runtime_ownership import validate_texts

ROOT = Path(__file__).resolve().parents[1]


class IngestionRuntimeOwnershipTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = json.loads((ROOT / "config" / "ingestion-runtime-ownership.json").read_text(encoding="utf-8"))
        cls.render = (ROOT / "render.yaml").read_text(encoding="utf-8")
        cls.compose = (ROOT / "compose.prod.yaml").read_text(encoding="utf-8")
        cls.dockerfile = (ROOT / "backend" / "Dockerfile").read_text(encoding="utf-8")
        cls.status = (ROOT / "docs" / "architecture" / "ingestion-migration-status-matrix.md").read_text(encoding="utf-8")

    def test_current_repository_matches_ownership_contract(self):
        validate_texts(self.policy, self.render, self.compose, self.dockerfile, self.status)

    def test_provider_secret_leak_into_api_fails_closed(self):
        render = self.render.replace(
            "      - key: DATABASE_POOL_SIZE\n        value: \"5\"",
            "      - key: DATABASE_POOL_SIZE\n        value: \"5\"\n"
            "      - key: WEASEL_API_ACCESS_TOKEN\n        sync: false",
        )
        with self.assertRaisesRegex(ValueError, "Render API contains ingestion/provider keys"):
            validate_texts(self.policy, render, self.compose, self.dockerfile, self.status)

    def test_gdal_in_api_image_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "ingestion-only tooling"):
            validate_texts(
                self.policy,
                self.render,
                self.compose,
                self.dockerfile + "\nRUN apk add --no-cache gdal-bin\n",
                self.status,
            )

    def test_java_realtime_exception_must_remain_explicit(self):
        altered = self.status.replace("RIK object count SOAP call", "removed exception")
        with self.assertRaisesRegex(ValueError, "Java real-time exception"):
            validate_texts(self.policy, self.render, self.compose, self.dockerfile, altered)


if __name__ == "__main__":
    unittest.main()
