import json
import unittest
from pathlib import Path
from scripts.validate_render_blueprint_contract import evaluate

ROOT=Path(__file__).resolve().parents[1]

class RenderBlueprintContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text=(ROOT/"render.yaml").read_text(encoding="utf-8")
        cls.contract=json.loads((ROOT/"config"/"render-env-contract.json").read_text(encoding="utf-8"))

    def test_current_blueprint_matches_contract(self):
        self.assertEqual(evaluate(self.text,self.contract),[])

    def test_missing_required_env_fails(self):
        altered=self.text.replace("      - key: NEXT_PUBLIC_MAPTILER_KEY\n        sync: false\n","")
        self.assertTrue(any("NEXT_PUBLIC_MAPTILER_KEY" in item for item in evaluate(altered,self.contract)))

    def test_runtime_database_identities_are_separate(self):
        api=self.text.split("name: forestiq-api",1)[1].split("name: forestiq-ingest-scheduler",1)[0]
        keycloak=self.text.split("name: forestiq-keycloak",1)[1].split("name: forestiq-api",1)[0]
        self.assertIn("key: DATABASE_USERNAME\n        sync: false",api)
        self.assertIn("key: SPRING_FLYWAY_ENABLED\n        value: \"false\"",api)
        self.assertIn("key: KC_DB_SCHEMA\n        value: keycloak",keycloak)

    def test_unknown_service_reference_fails(self):
        altered=self.text.replace("name: forestiq-api\n          envVarKey: RENDER_EXTERNAL_URL","name: missing-api\n          envVarKey: RENDER_EXTERNAL_URL",1)
        self.assertTrue(any("unknown fromService target" in item for item in evaluate(altered,self.contract)))

if __name__=="__main__":
    unittest.main()
