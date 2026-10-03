import unittest
from unittest.mock import patch

from scripts.release.production_smoke import smoke
from scripts.release.render_rollback import rollback


class ProductionReleaseVerificationTest(unittest.TestCase):
    @patch("scripts.release.production_smoke._get")
    def test_smoke_requires_all_critical_surfaces(self, get):
        get.side_effect = [
            (200, {"bytes": 10}),
            (200, {"status": "UP"}),
            (200, {"issuer": "https://kc.example/realms/forestiq"}),
            (200, [{"key": "OWNER_CADASTRE_RELATIONS"}]),
        ]
        result = smoke(
            "https://web.example",
            "https://api.example",
            "https://kc.example",
            "forestiq",
            "redacted-token",
        )
        self.assertEqual(result["status"], "passed")

    @patch("scripts.release.production_smoke._get")
    def test_failed_business_query_fails_smoke(self, get):
        get.side_effect = [
            (200, {}),
            (200, {"status": "UP"}),
            (200, {"issuer": "https://kc.example/realms/forestiq"}),
            (401, {"error": "http-401"}),
        ]
        result = smoke("https://web.example", "https://api.example", "https://kc.example", "forestiq", "token")
        self.assertEqual(result["status"], "failed")

    @patch("scripts.release.render_rollback._wait_deploy")
    @patch("scripts.release.render_rollback._request")
    def test_rollback_uses_recorded_previous_deploy_ids(self, request, wait):
        request.side_effect = [
            {"id": "dep-rb-worker"},
            {"id": "dep-rb-api"},
        ]
        wait.side_effect = [
            {"status": "live"},
            {"status": "live"},
        ]
        data = {
            "releaseId": "sha-test",
            "commit": "a" * 40,
            "deployments": [
                {"role": "api", "serviceId": "srv-api", "previousDeployId": "dep-old-api"},
                {"role": "worker", "serviceId": "srv-worker", "previousDeployId": "dep-old-worker"},
            ],
        }
        result = rollback(data, "token")
        self.assertEqual([item["role"] for item in result["rollback"]], ["worker", "api"])
        bodies = [call.args[3] for call in request.call_args_list]
        self.assertEqual(bodies[0]["deployId"], "dep-old-worker")
        self.assertEqual(bodies[1]["deployId"], "dep-old-api")


if __name__ == "__main__":
    unittest.main()
