import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class ControlledProductionDeployTest(unittest.TestCase):
    def test_render_commit_auto_deploy_is_disabled(self):
        text=(ROOT/"render.yaml").read_text(encoding="utf-8")
        self.assertNotIn("autoDeployTrigger: commit",text); self.assertGreaterEqual(text.count("autoDeployTrigger: off"),5)
    def test_workflow_builds_and_promotes_exact_image_digests(self):
        text=(ROOT/".github/workflows"/"production-deploy.yml").read_text(encoding="utf-8")
        for marker in ("git merge-base --is-ancestor","environment: production","docker/build-push-action@","actions/attest@","cosign sign --yes","render_image_promotion.py","verifyProductionRuntimeHeartbeats","production_smoke.py","render_rollback","FORESTIQ_RELEASE_ID=","FORESTIQ_COMMIT_SHA=","assemble_release_manifest.py","gh release create","--generate-notes","group: production-deploy"):
            self.assertIn(marker,text)
        self.assertNotIn("commitId",text); self.assertNotIn(":latest",text)
if __name__=="__main__": unittest.main()
