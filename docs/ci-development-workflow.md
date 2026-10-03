# Development and verification workflow

## Current verification model

GitHub Actions is the canonical automated verification path for ForestIQ. Pull requests use two layers:

- **CI** — fast, path-aware verification. Trusted same-repository PRs and `main` use the repository-scoped Mac Studio so hosted-runner outages cannot suppress required checks; fork PRs remain on GitHub-hosted runners.
- **Heavy CI** — system-level verification on the Mac Studio self-hosted runner for trusted code. It also runs automatically on every `main` push. Fork code never reaches the persistent runner without the explicit exact-SHA approval flow.

Repository rules should require the two stable aggregate checks documented in `docs/ci-required-checks.md`:

- `CI / fast-quality-gate`
- `Heavy CI / heavy-quality-gate`

Conditional child jobs may be skipped. The aggregate gates remain deterministic, so a skipped implementation job does not leave branch protection permanently Pending.

## Path-aware execution

`scripts/ci/classify-changes.sh` classifies pull-request changes before expensive jobs start.

| Changed area | Verification |
| --- | --- |
| `backend/**` | Backend fast checks; heavy verification when infrastructure-sensitive files also change |
| `frontend/**` | Frontend lint, typecheck and unit/component tests |
| `ingest/**`, Metsaregister tooling | Python ingestion tests and relevant contracts |
| `infra/**`, `compose*.yaml`, Dockerfiles | Heavy CI |
| `.github/workflows/**`, `scripts/ci/**` | CI configuration sanity plus full Heavy CI |
| Observability configuration | Observability validation |
| Documentation only | Lightweight security/classification and aggregate gates |

Unknown source-code paths fail safe by selecting the broad fast suite.

## Pull request lifecycle

1. Start from a GitHub issue and branch from current `main`.
2. Make focused commits.
3. Run relevant local checks.
4. Open a pull request against `main`.
5. Let the canonical fast checks finish on the trusted Mac Studio runner; fork PR fast checks stay GitHub-hosted.
6. When classification requires it, let Heavy CI run on the Mac Studio runner.
7. Merge only when the configured aggregate gates are green.
8. Keep the issue linked from the PR for traceability.

## Local verification commands

The root `Makefile` provides the standard entry points:

| Command | Purpose |
| --- | --- |
| `make test-fast` | Normal pre-push verification |
| `make test-backend` | Java unit tests and coverage gate |
| `make test-backend-integration` | Java/PostGIS integration tests |
| `make test-frontend` | Frontend lint, typecheck, unit/component tests and build |
| `make test-ingest` | Pure Python ingestion tests and ingestion contracts |
| `make test-heavy` | Backend/PostGIS, ingestion/PostGIS, Metsaregister/PostGIS, API contract and browser E2E |
| `make test-e2e` | Compose-backed Playwright E2E |
| `make check-security` | Dependency audits |
| `make check-ci-boundaries` | Static contract for hosted-fast vs Mac Studio-heavy runner ownership |
| `make check-all` | Full local verification |

### Backend

~~~bash
make test-backend
make test-backend-integration
~~~

For schema changes also regenerate jOOQ sources:

~~~bash
cd backend
./gradlew generateJooq --no-daemon
~~~

### Frontend

~~~bash
make test-frontend
~~~

The fast frontend layer is ESLint + TypeScript + Vitest coverage. Playwright belongs to Heavy CI.

### Ingestion

Pure Python ingestion tests do not require PostGIS:

~~~bash
cd ingest
python -m pip install -e ".[test]"
pytest -m "not integration"
~~~

PostGIS-dependent tests are explicitly marked `integration` and belong to Heavy CI:

~~~bash
cd ingest
TEST_POSTGIS_URL=postgresql://forestiq:forestiq@127.0.0.1:55432/forestiq pytest -m integration
~~~

### E2E

The reusable E2E workflow defaults to the Mac Studio labels:

- `self-hosted`
- `macOS`
- `ARM64`
- `forestiq`
- `heavy-ci`

It starts an isolated Compose project, waits for PostGIS, Keycloak, MinIO, API and frontend health, seeds deterministic fixtures, runs authenticated Playwright tests, uploads diagnostics on failure, and always performs scoped cleanup.

## Heavy CI

Heavy CI is selected for Compose, Dockerfile, infrastructure and workflow changes. Its self-hosted jobs currently cover:

- backend integration / PostGIS;
- ingestion integration / PostGIS;
- Metsaregister integration / PostGIS;
- OpenAPI contract / generated client drift;
- authenticated Playwright / Compose E2E;
- backend, frontend and ingest Docker image builds;
- Trivy CRITICAL image scanning.

The Docker images are never pushed by verification jobs. CI-created images are run-scoped and cleanup is performed by `scripts/ci/cleanup-self-hosted.sh`.

## Failure diagnostics

Diagnostics are uploaded only on failure unless a release artifact is explicitly required. Retention is bounded to 14 days for the current test diagnostics.

The detailed convention is in `docs/test-failure-diagnostics.md`.

## Hosted-minute controls

The repository treats GitHub-hosted capacity as the untrusted/fork boundary, not as a prerequisite for trusted canonical delivery. Trusted PR/main verification uses the repository-scoped Mac Studio. The repository reduces hosted Actions consumption through these rules:

- fork PRs stay on GitHub-hosted runners and cannot execute automatically on the persistent Mac;
- trusted same-repository PR/main checks use the Mac Studio, avoiding hosted-minute and hosted-capacity dependency;
- changed-path classification prevents unrelated suites from running;
- `cancel-in-progress` cancels obsolete PR runs;
- dependency caches are reused where safe;
- artifacts are uploaded mainly on failure and have bounded retention;
- Playwright, PostGIS-heavy integration and Docker image verification run on the self-hosted heavy layer;
- documentation-only changes do not allocate the Mac Studio runner.

## CI usage and monthly review

Review CI usage at least monthly and whenever hosted minutes increase unexpectedly.

- [ ] Review GitHub Actions usage and identify the most expensive hosted jobs.
- [ ] Confirm docs-only and single-area PRs are being filtered correctly.
- [ ] Verify obsolete runs are cancelled after new commits.
- [ ] Verify artifact retention remains bounded.
- [ ] Confirm `fast-quality-gate` and `heavy-quality-gate` are still emitted consistently.
- [ ] Review failed E2E jobs for flaky selectors, timing assumptions or fixture drift.
- [ ] Review Mac Studio disk usage and ForestIQ CI Docker resources.
- [ ] Move tests to a cheaper layer when they do not require full integration/E2E infrastructure.

Record recurring waste or instability as a GitHub issue instead of relying on repeated manual reruns.

## Main branch protection

The intended repository rule is:

- pull request required;
- `CI / fast-quality-gate` required;
- `Heavy CI / heavy-quality-gate` required;
- force-push disabled;
- branch deletion disabled;
- review/conversation policy explicitly defined.

Applying the repository rules requires repository administration permission and is tracked separately by #456.

## Temporary CI outage

A runner or GitHub Actions outage is not a permanent replacement for CI. If an exceptional merge is unavoidable, record the outage and local verification evidence in the PR and rerun the canonical checks as soon as service is restored.

Do not describe an unverified commit as CI-green.

## Hosted runner outage behavior

Trusted same-repository PRs and `main` no longer depend on GitHub-hosted runner capacity for the canonical aggregate gates. Their CI, security, ingestion-contract and Heavy CI execution is routed to the repository-scoped Mac Studio.

Fork PRs intentionally remain GitHub-hosted. If hosted capacity is unavailable, fork checks stay pending/failing rather than running untrusted code on the persistent runner. Branch protection must remain fail-closed.

The manual self-hosted fallback remains diagnostic/recovery tooling; it is not a bypass for the canonical required checks. See `docs/ci-baseline-recovery.md`.

## Runner-boundary drift guard

`python scripts/ci/validate-runner-boundaries.py` is part of `make test-fast`. It verifies that trusted canonical CI can run on the repository-scoped Mac Studio, fork PRs remain hosted, Heavy CI keeps its trust boundary, aggregate gate names stay stable, and reusable E2E defaults to the heavy runner.

This guard protects the #430 architecture even when workflow files are edited later.

## Machine-readable workload ownership

`config/ci-workload-ownership.json` is the source of truth for #430. It declares which jobs belong to GitHub-hosted fast CI and which jobs belong to Mac Studio Heavy CI, together with aggregate gate names and the untrusted-PR rule.

`scripts/ci/validate-runner-boundaries.py` consumes this policy during `make test-fast`. A later workflow edit therefore fails if a fast job moves to self-hosted execution, a required heavy workload disappears, runner labels drift, or the fork trust boundary is removed.

The live Mac Studio availability itself remains tracked by #431.


## Fork pull requests and Heavy CI

Fork PRs always run hosted fast checks without repository secrets.

If changed-path classification says `heavy=false` (for example documentation-only work), `heavy-quality-gate` succeeds without allocating the Mac Studio.

If a fork PR changes code that requires Heavy CI, its normal PR run remains blocked from self-hosted execution. A maintainer must:

1. inspect the PR and copy its current full 40-character head SHA;
2. open **Actions → Heavy CI → Run workflow** on the trusted base repository;
3. enter the fork PR number and that exact SHA;
4. run with `run_heavy=true`.

The hosted classifier retrieves the PR from GitHub, rejects a changed/mismatched head SHA, requires an open fork PR, and resolves GitHub's exact server-side merge commit. Every self-hosted job checks out that immutable merge commit; E2E receives the same verified ref.

A later push to the fork invalidates the old approval because the approved head SHA no longer matches. Heavy CI references no production secrets; deployment secrets remain in protected deployment workflows/environments.
