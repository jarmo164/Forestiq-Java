import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROVIDER_CONFIG_KEYS = (
    "METSIS_PORTFOLIO_URI",
    "METSIS_PORTFOLIO_AUTHORIZATION",
    "METSIS_PORTFOLIO_ORGANIZATION_ID",
    "WEASEL_API_BASE_URL",
    "WEASEL_API_ACCESS_TOKEN",
    "WEASEL_ORGANIZATION_ID",
    "INHERITANCE_SOURCE_BASE_URL",
    "INHERITANCE_SOURCE_API_TOKEN",
    "INHERITANCE_SOURCE_URI_TEMPLATE",
)


def compose_service(text: str, name: str) -> str:
    match = re.search(rf"^  {re.escape(name)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:\n|^volumes:)", text, re.M | re.S)
    if not match:
        raise AssertionError(f"service {name} is missing")
    return match.group(0)


def render_service(text: str, name: str) -> str:
    match = re.search(rf"^  - type: [^\n]+\n    name: {re.escape(name)}\n(?P<body>.*?)(?=^  - type:|^databases:)", text, re.M | re.S)
    if not match:
        raise AssertionError(f"Render service {name} is missing")
    return match.group(0)


class IngestionDeploymentContractTest(unittest.TestCase):
    def test_local_compose_isolated_runtime(self):
        text = (ROOT / "compose.yaml").read_text()
        api = compose_service(text, "api")
        scheduler = compose_service(text, "ingest-scheduler")
        worker = compose_service(text, "ingest-worker")
        self.assertIn('["python", "-m", "forestiq_ingest.scheduler"]', scheduler)
        self.assertIn('["python", "-m", "forestiq_ingest.worker"]', worker)
        self.assertIn("INGEST_DATABASE_USER", scheduler)
        self.assertIn("INGEST_DATABASE_USER", worker)
        self.assertNotIn("POSTGRES_USER:", scheduler)
        for key in PROVIDER_CONFIG_KEYS:
            self.assertNotIn(key, api)
            self.assertNotIn(key, scheduler)
            self.assertIn(key, worker)
        self.assertNotIn("forestiq_ingest.adapters", scheduler)
        self.assertIn("stop_grace_period: 5m", worker)
        self.assertIn("forestiq_ingest.healthcheck", worker)

    def test_production_compose_uses_operator_managed_ingest_url(self):
        text = (ROOT / "compose.prod.yaml").read_text()
        api = compose_service(text, "api")
        scheduler = compose_service(text, "ingest-scheduler")
        worker = compose_service(text, "ingest-worker")
        self.assertIn("INGEST_DATABASE_URL:?", scheduler)
        self.assertIn("INGEST_DATABASE_URL:?", worker)
        self.assertIn("stop_grace_period: 5m", scheduler)
        self.assertIn("stop_grace_period: 5m", worker)
        for key in PROVIDER_CONFIG_KEYS:
            self.assertNotIn(key, api)
            self.assertNotIn(key, scheduler)
            self.assertIn(key, worker)

    def test_render_blueprint_has_two_python_workers(self):
        text = (ROOT / "render.yaml").read_text()
        api = render_service(text, "forestiq-api")
        scheduler = render_service(text, "forestiq-ingest-scheduler")
        worker = render_service(text, "forestiq-ingest-worker")
        for service in (scheduler, worker):
            self.assertIn("type: worker", service)
            self.assertIn("runtime: docker", service)
            self.assertIn("rootDir: ingest", service)
            self.assertIn("maxShutdownDelaySeconds: 300", service)
            self.assertRegex(service, r"- key: DATABASE_URL\n        sync: false")
        self.assertIn("forestiq_ingest.scheduler", scheduler)
        self.assertIn("forestiq_ingest.worker", worker)
        for key in PROVIDER_CONFIG_KEYS:
            self.assertNotIn(key, api)
            self.assertNotIn(key, scheduler)
            self.assertIn(key, worker)


if __name__ == "__main__":
    unittest.main()
