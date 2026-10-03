import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.release.assemble_image_manifest import assemble_manifest
from scripts.release.render_image_promotion import promote


class ImmutableReleaseSupplyChainTest(unittest.TestCase):
    def test_manifest_requires_all_three_exact_digests(self):
        commit = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for key, char in (("api", "a"), ("web", "b"), ("ingest", "c")):
                digest = "sha256:" + char * 64
                (root / f"{key}.json").write_text(json.dumps({
                    "image": key,
                    "repository": f"ghcr.io/example/{key}",
                    "digest": digest,
                    "reference": f"ghcr.io/example/{key}@{digest}",
                    "commit": commit,
                }), encoding="utf-8")
            manifest = assemble_manifest(commit, root)
            self.assertEqual(set(manifest["images"]), {"api", "web", "ingest"})
            self.assertTrue(all("@sha256:" in item["reference"] for item in manifest["images"].values()))

    def test_missing_image_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                assemble_manifest("a" * 40, Path(directory))

    @patch("scripts.release.render_image_promotion._wait_deploy")
    @patch("scripts.release.render_image_promotion._previous_live_deploy")
    @patch("scripts.release.render_image_promotion._request")
    def test_render_promotion_uses_digest_and_records_rollback_target(self, request, previous, wait):
        request.side_effect = [
            {}, {"id": "dep-new-api"},
            {}, {"id": "dep-new-web"},
            {}, {"id": "dep-new-scheduler"},
            {}, {"id": "dep-new-worker"},
        ]
        previous.side_effect = [
            {"id": "dep-old-api"},
            {"id": "dep-old-web"},
            {"id": "dep-old-scheduler"},
            {"id": "dep-old-worker"},
        ]
        wait.return_value = {"status": "live"}
        digest = "sha256:" + "f" * 64
        manifest = {
            "releaseId": "sha-aaaaaaaaaaaa",
            "commit": "a" * 40,
            "images": {
                key: {"reference": f"ghcr.io/example/{key}@{digest}"}
                for key in ("api", "web", "ingest")
            },
        }
        mapping = {
            "api": "srv-api",
            "web": "srv-web",
            "ingest_scheduler": "srv-scheduler",
            "ingest_worker": "srv-worker",
        }
        result = promote(manifest, mapping, "token")
        self.assertEqual(len(result["deployments"]), 4)
        self.assertTrue(all(item["status"] == "live" for item in result["deployments"]))
        self.assertTrue(all(str(item["previousDeployId"]).startswith("dep-old-") for item in result["deployments"]))
        bodies = [call.args[3] for call in request.call_args_list if len(call.args) > 3 and call.args[3]]
        refs = [body.get("imageUrl") or body.get("image", {}).get("name") for body in bodies]
        self.assertTrue(all(ref and "@sha256:" in ref for ref in refs))


if __name__ == "__main__":
    unittest.main()
