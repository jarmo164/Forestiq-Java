# CI dependency cache policy

ForestIQ CI caches dependencies and build layers only to reduce repeat setup time. Caches are disposable and must never be treated as build inputs that carry correctness, secrets or deployment state.

## Current cache owners

| Area | Workflow usage | Cache key source | Notes |
|---|---|---|---|
| Gradle | `gradle/actions/setup-gradle@v4` in backend and API-contract jobs | Gradle wrapper, Gradle files and dependency metadata managed by the action | Cache may speed dependency resolution and task outputs, but a clean checkout must still build correctly. |
| pnpm | `actions/setup-node@v4` with `cache: pnpm` | `frontend/pnpm-lock.yaml` | Lockfile changes invalidate the dependency cache. |
| Python Metsaregister tests | `actions/setup-python@v5` with `cache: pip` | `scripts/metsaregister/requirements-dev.txt` | Used by the fast Metsaregister CSV test job. |
| Python security audit | `actions/setup-python@v5` with `cache: pip` | `ingest/requirements.txt`, `ingest/pyproject.toml`, `scripts/metsaregister/requirements-dev.txt` | Used only for dependency audit setup. |
| Docker image security scans | `docker/build-push-action@v6` with GitHub Actions cache | Per-image scopes such as `forestiq-api-security` | Used for scan-only image builds; images are loaded locally and not pushed. |

## Rules

- Cache keys must be derived from lockfiles, requirements files or Docker build contexts, never from secrets.
- Cache misses must not fail the build.
- A workflow must remain reproducible after clearing all caches.
- Do not cache generated `.env` files, credentials, Playwright traces or runtime data directories.
- Docker cache is allowed for verification builds only when the workflow still builds the image from the current checkout and does not push it.
- If a stale cache is suspected, rerun the workflow with cache disabled or clear the GitHub Actions cache before changing application code.

## Docker cache strategy

Security image scans build three local images: API, frontend and ingest. Each image uses its own GitHub Actions cache scope so unrelated Dockerfiles do not evict or cross-contaminate each other.

The cache is intentionally tied to verification builds:

- images are tagged only as `*:security-scan`;
- images are loaded into the runner for scanning;
- images are not pushed from the scan workflow;
- Trivy still scans the image produced by the current checkout.

When heavy Docker verification moves to the self-hosted runner, keep the same separation: use per-image scopes, clean stale local images separately, and never share production deployment credentials with cache restore steps.
