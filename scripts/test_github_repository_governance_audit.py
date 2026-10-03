import unittest

from scripts.github_repository_governance_audit import evaluate


POLICY = {
    "required_status_checks": {"strict": True, "contexts": ["fast-quality-gate", "heavy-quality-gate"]},
    "enforce_admins": True,
    "pull_request": {"required": True, "required_approving_review_count": 0},
    "required_conversation_resolution": True,
    "allow_force_pushes": False,
    "allow_deletions": False,
    "merge": {
        "allow_auto_merge": True,
        "allow_squash_merge": True,
        "allow_merge_commit": False,
        "allow_rebase_merge": False,
    },
}


def repo():
    return {
        "allow_auto_merge": True,
        "allow_squash_merge": True,
        "allow_merge_commit": False,
        "allow_rebase_merge": False,
    }


def protection():
    return {
        "required_status_checks": {
            "strict": True,
            "contexts": ["heavy-quality-gate", "fast-quality-gate"],
        },
        "enforce_admins": {"enabled": True},
        "required_pull_request_reviews": {"required_approving_review_count": 0},
        "required_conversation_resolution": {"enabled": True},
        "allow_force_pushes": {"enabled": False},
        "allow_deletions": {"enabled": False},
    }


class RepositoryGovernanceAuditTest(unittest.TestCase):
    def test_matching_live_state_passes(self):
        self.assertEqual(evaluate(POLICY, repo(), protection()), [])

    def test_missing_required_gate_fails(self):
        current = protection()
        current["required_status_checks"]["contexts"] = ["fast-quality-gate"]
        failures = evaluate(POLICY, repo(), current)
        self.assertTrue(any("required status checks" in failure for failure in failures))

    def test_auto_merge_drift_fails(self):
        current = repo()
        current["allow_auto_merge"] = False
        failures = evaluate(POLICY, current, protection())
        self.assertTrue(any("allow_auto_merge" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
