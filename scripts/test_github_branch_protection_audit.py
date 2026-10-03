import unittest

from scripts.github_branch_protection_audit import evaluate


POLICY = {
    "branch": "main",
    "required_status_checks": {"strict": True, "contexts": ["fast-quality-gate", "heavy-quality-gate"]},
    "enforce_admins": True,
    "pull_request": {"required": True},
    "required_conversation_resolution": True,
    "allow_force_pushes": False,
    "allow_deletions": False,
}


def protection():
    return {
        "required_status_checks": {"strict": True, "contexts": ["heavy-quality-gate", "fast-quality-gate"]},
        "enforce_admins": {"enabled": True},
        "required_pull_request_reviews": {"required_approving_review_count": 0},
        "required_conversation_resolution": {"enabled": True},
        "allow_force_pushes": {"enabled": False},
        "allow_deletions": {"enabled": False},
    }


class BranchProtectionAuditTest(unittest.TestCase):
    def test_matching_policy_passes(self):
        self.assertEqual(evaluate(POLICY, protection()), [])

    def test_missing_pr_protection_fails(self):
        current = protection()
        current["required_pull_request_reviews"] = None
        self.assertIn("pull request protection is missing", evaluate(POLICY, current))

    def test_force_push_drift_fails(self):
        current = protection()
        current["allow_force_pushes"]["enabled"] = True
        self.assertIn("force-push policy drifted", evaluate(POLICY, current))


if __name__ == "__main__":
    unittest.main()
