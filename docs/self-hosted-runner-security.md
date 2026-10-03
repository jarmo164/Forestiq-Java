# Self-hosted runner security boundary

The ForestIQ Mac Studio is persistent infrastructure. Treat it as a CI worker, not as a general-purpose developer session.

## Trust boundary

Heavy CI may target the Mac Studio only when workflow code comes from the ForestIQ repository itself. Fork pull requests are classified on a GitHub-hosted runner and must never allocate `heavy-ci`.

`pull_request_target` is forbidden for build/test workflows targeting the self-hosted machine.

## Machine-readable security contract

`config/self-hosted-runner-security.json` defines the allowed workflows and security invariants for the persistent runner. `scripts/ci/validate_self_hosted_security.py` runs in `make test-fast` and fails when:

- a new workflow starts targeting `heavy-ci` without being allowlisted;
- any workflow uses `pull_request_target`;
- a self-hosted test workflow loses `permissions: contents: read`;
- a self-hosted test workflow references GitHub repository secrets;
- Heavy CI loses the fork trust classifier or the `trusted == true` job guard;
- the live acceptance script stops checking the dedicated non-admin account boundary;
- the live runner environment contains forbidden production/provider credential variable names.

## Runner scope and account

Register the runner at repository scope for `jarmo164/ForestIQ`. Run unattended CI under a dedicated non-admin account such as `forestiq-ci`, with access only to the runner workspace and required Docker runtime.

The account must not contain personal browser sessions, personal keychain credentials, production SSH keys or application passwords.

## Secret boundary

Heavy CI uses ephemeral local test values for PostGIS, Keycloak and MinIO. Production/provider credentials belong only to deployment/ingestion environments and must not be referenced by normal self-hosted test workflows. The acceptance run checks only environment-variable names against the committed deny-list; it never prints or reads credential values.

Deployment workflows that genuinely require production secrets should use protected GitHub Environments and must not share the `heavy-ci` test execution path.

## Cleanup

Every persistent self-hosted workload must use ForestIQ-scoped cleanup with `if: always()`. See `scripts/ci/cleanup-self-hosted.sh`.

## Live closure evidence for #442

Code-side security invariants are enforced automatically. To close #442, retain:

1. a passing repository runner-registration audit from #440;
2. a passing **Self-hosted runner acceptance** JSON showing the non-admin account boundary;
3. a successful same-repository Heavy CI run;
4. evidence that a fork/untrusted PR cannot allocate the self-hosted implementation jobs.

Do not treat static workflow checks as proof of the actual macOS account or repository runner scope.
