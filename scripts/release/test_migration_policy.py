import json, tempfile, unittest
from pathlib import Path
from scripts.release.validate_migration_policy import validate

POLICY={
    "enforceFromVersion":"65",
    "exceptionMarker":"forestiq:migration-exception",
    "forbiddenPatterns":[r"\bDROP\s+TABLE\b",r"\bDROP\s+COLUMN\b",r"\bTRUNCATE\b"],
}

class MigrationPolicyTest(unittest.TestCase):
    def test_expand_migration_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,"V65__expand.sql").write_text("ALTER TABLE owner ADD COLUMN IF NOT EXISTS sample text;",encoding="utf-8")
            self.assertEqual(validate(Path(directory),POLICY),[])
    def test_destructive_migration_fails_without_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,"V65__contract.sql").write_text("ALTER TABLE owner DROP COLUMN sample;",encoding="utf-8")
            self.assertEqual(
                validate(Path(directory),POLICY),
                ["V65__contract.sql: destructive contract-phase SQL requires 'forestiq:migration-exception' with a reviewed compatibility reason"],
            )
    def test_reviewed_exception_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,"V65__contract.sql").write_text("-- forestiq:migration-exception N-1 reader removed after two releases\nALTER TABLE owner DROP COLUMN sample;",encoding="utf-8")
            self.assertEqual(validate(Path(directory),POLICY),[])
    def test_invalid_versioned_filename_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,"V65_contract.sql").write_text("SELECT 1;",encoding="utf-8")
            self.assertEqual(
                validate(Path(directory),POLICY),
                ["V65_contract.sql: invalid versioned migration filename"],
            )
if __name__=="__main__": unittest.main()
