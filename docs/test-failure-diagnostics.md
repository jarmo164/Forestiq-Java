# CI test failure diagnostics standard

ForestIQ keeps failure diagnostics for enough time to investigate a broken pull request or main-branch run without first reproducing the failure locally. Successful runs should avoid uploading large diagnostics unless a workflow explicitly needs a release artifact.

## Standard artifact policy

| Verification area | Required failure evidence | Artifact source | Retention |
|---|---|---|---|
| Backend Java unit tests | JUnit XML, HTML test report and JaCoCo report when generated | `backend/build/test-results/test/`, `backend/build/reports/tests/test/`, `backend/build/reports/jacoco/test/` | 14 days |
| Backend Java integration tests | JUnit XML and HTML test report for PostGIS/Testcontainers/application-startup tests | `backend/build/test-results/integrationTest/`, `backend/build/reports/tests/integrationTest/` | 14 days |
| Frontend unit/component tests | Coverage output and test-run diagnostics produced by the frontend tooling | `frontend/coverage/` | 14 days |
| Metsaregister CSV tests | Pytest JUnit XML for failing Python/PostGIS test runs | `artifacts/metsaregister/pytest.xml` | 14 days |
| OpenAPI generated-client contract | Generated OpenAPI JSON, generated frontend client files and API startup log | `backend/build/openapi/forestiq-openapi.json`, `frontend/lib/generated/`, `openapi-backend.log` | 14 days |
| Compose E2E | Playwright report, test result traces/screenshots, Compose service state and service logs | `frontend/playwright-report/`, `frontend/test-results/`, `artifacts/e2e/` | 14 days |
| Ingestion deployment contract | Unit-test output and Compose model validation output | `artifacts/ingestion-contract/` | 14 days |
| Dependency audits | Dependency-check reports and ecosystem-specific audit output retained in the job log | `backend/build/reports/dependency-check-report.*` | 14 days |

## Redaction and secret safety

Failure diagnostics must never include production credentials or reusable secrets.

Use these rules when adding or changing a workflow:

- Prefer deterministic CI-only credentials such as `contract-only`, `forestiq_e2e_password` or local test users.
- Do not print GitHub secrets, production environment variables, access tokens or private keys.
- Store generated `.env` files with restrictive permissions and delete them during cleanup.
- Keep service logs scoped to CI-only Compose projects and local test credentials.
- Treat browser traces and screenshots as potentially sensitive if a test starts using non-synthetic data.

## When to add a new diagnostic artifact

Add a failure-only artifact when all of these are true:

- The failing command produces a report, trace, service log or generated file that materially speeds up diagnosis.
- The artifact does not contain production data or secrets.
- The artifact has bounded retention.
- The artifact is named with `${{ github.run_id }}` and `${{ github.run_attempt }}` or another run-unique suffix.

Do not upload dependency caches, build directories, container layers or complete workspaces as diagnostics. Keep those in caches or rebuild them when needed.

## Local reproduction handoff

A failed CI run should provide enough information to answer:

- Which verification layer failed?
- Which test, generated file or service failed first?
- Was the failure a code regression, fixture drift, dependency/audit issue or infrastructure failure?
- Which local `make test-*` command should reproduce the problem?

If a workflow fails before any job step starts, record that as runner or GitHub Actions infrastructure evidence in the pull request rather than treating it as a test-layer failure.
