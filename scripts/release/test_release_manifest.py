import unittest

from scripts.release.assemble_release_manifest import assemble_release_manifest
from scripts.release.production_smoke import smoke
from unittest.mock import patch


class ReleaseManifestTest(unittest.TestCase):
    def sample(self):
        release_id="forestiq-123-aaaaaaaaaaaa"
        commit="a"*40
        digest="sha256:"+"f"*64
        images={
            "schemaVersion":1,
            "releaseId":release_id,
            "commit":commit,
            "images":{
                key:{
                    "image":key,
                    "repository":f"ghcr.io/example/{key}",
                    "digest":digest,
                    "reference":f"ghcr.io/example/{key}@{digest}",
                    "commit":commit,
                }
                for key in ("api","web","ingest")
            },
        }
        deployments={
            "schemaVersion":1,
            "releaseId":release_id,
            "commit":commit,
            "deployments":[
                {
                    "role":"api",
                    "serviceId":"srv-api",
                    "image":"api",
                    "imageReference":f"ghcr.io/example/api@{digest}",
                    "previousDeployId":"dep-old-api",
                    "deployId":"dep-new-api",
                    "status":"live",
                }
            ],
        }
        smoke_data={"schemaVersion":1,"releaseId":release_id,"commit":commit,"status":"passed","checks":[]}
        return release_id,commit,images,deployments,smoke_data

    def test_manifest_contains_traceability_and_rollback_target(self):
        release_id,commit,images,deployments,smoke_data=self.sample()
        result=assemble_release_manifest(
            release_id=release_id,
            commit=commit,
            image_manifest=images,
            deployments=deployments,
            smoke=smoke_data,
            migration_log="ForestIQ migration complete: version=65 executed=1 success=true\n",
            workflow_run_url="https://github.com/jarmo164/ForestIQ/actions/runs/123",
        )
        self.assertEqual(result["releaseId"],release_id)
        self.assertEqual(result["commit"],commit)
        self.assertEqual(result["migrationVersion"],"65")
        self.assertEqual(result["rollbackTargets"]["api"]["deployId"],"dep-old-api")
        self.assertIn("@sha256:",result["images"]["api"]["reference"])

    def test_failed_smoke_cannot_be_released(self):
        release_id,commit,images,deployments,smoke_data=self.sample()
        smoke_data["status"]="failed"
        with self.assertRaises(ValueError):
            assemble_release_manifest(
                release_id=release_id,
                commit=commit,
                image_manifest=images,
                deployments=deployments,
                smoke=smoke_data,
                migration_log="ForestIQ migration complete: version=65 executed=1 success=true\n",
                workflow_run_url="https://github.com/jarmo164/ForestIQ/actions/runs/123",
            )

    @patch("scripts.release.production_smoke._get")
    def test_smoke_validates_api_and_web_build_metadata(self,get):
        release_id,commit,*_=self.sample()
        get.side_effect=[
            (200,{}),
            (200,{"status":"UP"}),
            (200,{"issuer":"https://kc.example/realms/forestiq"}),
            (200,[{"key":"x"}]),
            (200,{"forestiq":{"releaseId":release_id,"commitSha":commit}}),
            (200,{"releaseId":release_id,"commitSha":commit}),
        ]
        result=smoke(
            "https://web.example",
            "https://api.example",
            "https://kc.example",
            "forestiq",
            "token",
            release_id,
            commit,
        )
        self.assertEqual(result["status"],"passed")
        self.assertEqual(result["releaseId"],release_id)


if __name__=="__main__":
    unittest.main()
