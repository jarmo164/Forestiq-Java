import tempfile
import unittest
from pathlib import Path

from scripts.validate_api_ingest_boundary import (
    render_env_keys,
    service_block,
    validate_api_deployment,
)


class ApiIngestBoundaryTest(unittest.TestCase):
    def test_current_production_blueprint_keeps_ingest_secrets_out_of_api(self):
        validate_api_deployment(Path("render.yaml"), Path("backend/Dockerfile"))

    def test_service_parser_scopes_environment_to_requested_service(self):
        text = """
services:
  - type: web
    name: forestiq-api
    envVars:
      - key: DATABASE_URL
        value: x
  - type: worker
    name: forestiq-ingest-worker
    envVars:
      - key: WEASEL_API_ACCESS_TOKEN
        value: secret
"""
        self.assertEqual(render_env_keys(service_block(text, "forestiq-api")), {"DATABASE_URL"})

    def test_ingest_secret_in_api_fails_closed(self):
        render = """
services:
  - type: web
    name: forestiq-api
    envVars:
      - key: FORESTIQ_RUNTIME_ROLE
        value: api
      - key: WEASEL_API_ACCESS_TOKEN
        value: secret
"""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            render_path = root / "render.yaml"
            dockerfile = root / "Dockerfile"
            render_path.write_text(render, encoding="utf-8")
            dockerfile.write_text("FROM eclipse-temurin:21-jre-alpine\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "WEASEL_API_ACCESS_TOKEN"):
                validate_api_deployment(render_path, dockerfile)

    def test_gdal_in_api_image_fails_closed(self):
        render = """
services:
  - type: web
    name: forestiq-api
    envVars:
      - key: FORESTIQ_RUNTIME_ROLE
        value: api
"""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            render_path = root / "render.yaml"
            dockerfile = root / "Dockerfile"
            render_path.write_text(render, encoding="utf-8")
            dockerfile.write_text("RUN apk add --no-cache gdal-bin\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "gdal-bin"):
                validate_api_deployment(render_path, dockerfile)


if __name__ == "__main__":
    unittest.main()
