#!/usr/bin/env python3
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CLASSIFIER = ROOT / "scripts/ci/classify-changes.sh"


def classify(*paths: str) -> dict[str, str]:
    with tempfile.NamedTemporaryFile(delete=False) as output:
        output_path = output.name
    env = os.environ.copy()
    env["GITHUB_OUTPUT"] = output_path
    env["FORESTIQ_CHANGED_FILES"] = "\n".join(paths)
    try:
        subprocess.run(
            ["bash", str(CLASSIFIER), "pull_request", "base", "head"],
            cwd=ROOT,
            env=env,
            check=True,
            text=True,
            capture_output=True,
        )
        values: dict[str, str] = {}
        for line in Path(output_path).read_text(encoding="utf-8").splitlines():
            key, value = line.split("=", 1)
            values[key] = value
        return values
    finally:
        Path(output_path).unlink(missing_ok=True)


class ClassifyChangesTest(unittest.TestCase):
    def test_docs_are_none_profile(self) -> None:
        result = classify("docs/architecture.md")
        self.assertEqual("none", result["performance_profile"])
        self.assertEqual("false", result["heavy"])
        self.assertEqual("true", result["docs_only"])

    def test_regular_non_backend_change_is_standard(self) -> None:
        result = classify("frontend/app/page.tsx")
        self.assertEqual("standard", result["performance_profile"])
        self.assertEqual("false", result["heavy"])

    def test_migration_requires_performance_evidence(self) -> None:
        result = classify("backend/src/main/resources/db/migration/V999__perf.sql")
        self.assertEqual("performance", result["performance_profile"])
        self.assertEqual("true", result["heavy"])
        self.assertEqual("backend-integration,metsaregister-integration", result["required_integration_tests"])
        self.assertEqual("query-plan,mvt", result["required_performance_evidence"])

    def test_map_repository_is_performance_sensitive(self) -> None:
        result = classify("backend/src/main/java/ee/forestiq/cadastre/GenerationMapTileRepository.java")
        self.assertEqual("performance", result["performance_profile"])
        self.assertEqual("true", result["heavy"])

    def test_unknown_backend_main_path_fails_closed(self) -> None:
        result = classify("backend/src/main/java/ee/forestiq/newmodule/UnknownProductionPath.java")
        self.assertEqual("performance", result["performance_profile"])
        self.assertEqual("true", result["heavy"])

    def test_package_move_fails_closed_for_old_and_new_paths(self) -> None:
        result = classify(
            "backend/src/main/java/ee/forestiq/cadastre/OldTileRepository.java",
            "backend/src/main/java/ee/forestiq/newmap/NewTileRepository.java",
        )
        self.assertEqual("performance", result["performance_profile"])
        self.assertEqual("true", result["heavy"])


if __name__ == "__main__":
    unittest.main()
