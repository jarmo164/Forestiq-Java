import unittest

from scripts.ci.validate_self_hosted_security import evaluate


POLICY = {
    "allowed_heavy_workflows": ["heavy.yml", "acceptance.yml"],
    "forbid_pull_request_target": True,
    "forbid_repository_secrets_on_self_hosted_tests": True,
    "require_read_only_contents": True,
    "require_fork_trust_gate_in": "heavy.yml",
    "require_non_admin_acceptance": True,
}


def valid_workflows():
    return {
        "heavy.yml": """name: Heavy
on:
  pull_request:
permissions:
  contents: read
jobs:
  changes:
    runs-on: ubuntu-latest
    steps:
      - run: echo github.event.pull_request.head.repo.full_name
  test:
    if: needs.changes.outputs.trusted == 'true'
    runs-on: [self-hosted, macOS, ARM64, forestiq, heavy-ci]
""",
        "acceptance.yml": """name: Acceptance
on:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  acceptance:
    runs-on: [self-hosted, macOS, ARM64, forestiq, heavy-ci]
""",
    }


class SelfHostedSecurityContractTest(unittest.TestCase):
    def test_valid_contract_passes(self):
        self.assertEqual(
            evaluate(POLICY, valid_workflows(), "Dedicated non-admin account"),
            [],
        )

    def test_unallowlisted_heavy_workflow_fails(self):
        workflows = valid_workflows()
        workflows["surprise.yml"] = "permissions:\n  contents: read\nruns-on: [self-hosted, heavy-ci]"
        failures = evaluate(POLICY, workflows, "Dedicated non-admin account")
        self.assertTrue(any("not allowlisted" in failure for failure in failures))

    def test_repository_secret_on_self_hosted_workflow_fails(self):
        workflows = valid_workflows()
        workflows["acceptance.yml"] += "\nenv:\n  TOKEN: ${{ secrets.PROD_TOKEN }}\n"
        failures = evaluate(POLICY, workflows, "Dedicated non-admin account")
        self.assertTrue(any("repository secrets" in failure for failure in failures))

    def test_pull_request_target_fails(self):
        workflows = valid_workflows()
        workflows["heavy.yml"] = workflows["heavy.yml"].replace("pull_request:", "pull_request_target:")
        failures = evaluate(POLICY, workflows, "Dedicated non-admin account")
        self.assertTrue(any("pull_request_target" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
