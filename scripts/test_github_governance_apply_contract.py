import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "scripts" / "apply-github-governance.sh").read_text(encoding="utf-8")


class GovernanceApplyContractTest(unittest.TestCase):
    def test_applies_branch_protection_before_merge_policy(self):
        protection = SCRIPT.index("configure-main-protection.sh")
        merge_policy = SCRIPT.index("safe-auto-merge.sh")
        self.assertLess(protection, merge_policy)

    def test_exports_live_repository_and_protection_state(self):
        self.assertIn('gh api "repos/$repo" > "$repo_json"', SCRIPT)
        self.assertIn('gh api "repos/$repo/branches/main/protection" > "$protection_json"', SCRIPT)
        self.assertIn("github_repository_governance_audit.py", SCRIPT)

    def test_does_not_use_admin_merge_bypass(self):
        self.assertNotIn("gh pr merge", SCRIPT)
        self.assertNotIn("--admin", SCRIPT)

    def test_supports_read_only_verify_mode(self):
        self.assertIn('mode="${2:-apply}"', SCRIPT)
        self.assertIn("verify)", SCRIPT)


if __name__ == "__main__":
    unittest.main()
