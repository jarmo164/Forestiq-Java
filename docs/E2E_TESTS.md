# ForestIQ authenticated E2E tests

The authenticated Playwright suite has a self-contained local profile. It starts an isolated Docker Compose project with PostGIS, Keycloak, MinIO, the API and the frontend, loads deterministic E2E data, installs the Chromium test browser and then runs Playwright.

## Critical business flows

Mandatory Playwright coverage is reserved for workflows where the real browser, authentication layer, API and database must work together. Do not add a browser test when the behavior can be proven with a unit, component, contract or integration test.

| Critical flow | Current coverage | Fixture/data expectation | Why it stays E2E |
|---|---|---|---|
| Login, logout and authenticated session handling | `frontend/tests/e2e/login.spec.ts`, shared auth helpers | Versioned Keycloak realm users from `infra/keycloak/forestiq-realm.json` | Proves browser auth redirect, token/session handling and frontend route access together. |
| Role authorization boundaries | `frontend/tests/e2e/role-boundaries.spec.ts` | Admin, manager, caller and evaluator test users with deterministic roles | Proves real UI navigation and API authorization do not expose protected areas to the wrong role. |
| Manager assignment to caller queue and caller activity/status update | `frontend/tests/e2e/critical-flows.spec.ts` | Seeded CRM/deal records from `scripts/e2e-seed-all.sql` and `scripts/e2e-seed.sql` | Proves the core sales workflow across UI, API, persistence and role-specific queues. |
| Owner overview / 360 view | `frontend/tests/e2e/owner-360.spec.ts` | Seeded owner, parcel and relationship records | Proves the user can open the main owner context with joined domain data. |
| Owner onboarding | `frontend/tests/e2e/owner-onboarding.spec.ts` | Deterministic onboarding input and seeded base state | Proves a high-value user creation/change flow through browser, API validation and persistence. |
| Ownership relations | `frontend/tests/e2e/ownership-relations.spec.ts` | Multi-owner and relation-state seed data | Proves relationship visibility and state transitions that are easy to break across domain joins. |
| Map-first parcel workflow | `frontend/tests/e2e/map-first.spec.ts`, `frontend/tests/e2e/map-search-basemap.spec.ts` | Seeded cadastral parcels and stable map/search expectations | Proves the map, search UI and parcel context work together in a real browser. |
| Metsaregister overlays and registry visibility | `frontend/tests/e2e/metsaregister-overlays.spec.ts` | Seeded registry/geospatial records, no live external services | Proves registry data becomes visible in the user-facing map workflow after ingestion/publish semantics. |

## E2E scope rules

A new Playwright test should meet all of these conditions:

- It covers a user-visible workflow that crosses at least two application boundaries, such as browser plus API plus database, or browser plus Keycloak.
- It uses deterministic seed data committed to the repository.
- It does not depend on live external systems, production credentials or timing from scheduled jobs.
- Its failure gives information that lower-level tests cannot provide.
- It is stable enough to be part of a required quality gate.

Prefer lower-level tests for formatting rules, field-level validation, isolated API behavior, pure data transforms, generated-client typing and table/filter variations. If an E2E test only checks one component or one endpoint, convert it to a unit, component, contract or integration test instead.

## Run

From `frontend/`:

```bash
pnpm test:e2e:compose
```

`pnpm test:e2e` is kept as a compatibility alias for the same full Compose-backed E2E run. Use `pnpm test:e2e:playwright` only when the isolated stack is already running.

Or from the repository root:

```bash
bash scripts/run-e2e.sh
```

The runner uses the Compose project name `forestiq-e2e` and separate host ports (`13000` frontend, `18080` API, `18180` Keycloak, `15432` PostgreSQL). Its volumes are deleted after the run, so it does not reuse or delete the normal ForestIQ development database.

## Prerequisites

The command checks these before starting:

- Docker CLI and a running Docker daemon;
- `curl`;
- `pnpm`.

A missing prerequisite fails immediately with an `E2E prerequisite failed` message instead of waiting for a browser locator timeout. Service readiness is checked explicitly for Keycloak, the API readiness endpoint and the frontend login page before Playwright starts.

## Deterministic test identity and data

Keycloak imports `infra/keycloak/forestiq-realm.json`, which contains the E2E admin, manager, caller and evaluator users used by the Playwright login helper. PostgreSQL is seeded with `scripts/e2e-seed-all.sql` and `scripts/e2e-seed.sql` after Flyway has completed through API startup.

The local E2E credentials in `scripts/e2e-compose.defaults` are development-only values for the isolated stack. They must never be reused in a deployed environment.

## Failure diagnostics and cleanup

On failure the runner prints Compose service state and recent logs for PostgreSQL, Keycloak, Keycloak setup, API and frontend. By default the isolated stack and volumes are removed after the test. To keep the failed stack temporarily for local debugging:

```bash
E2E_KEEP_STACK=true pnpm test:e2e:compose
```

Playwright writes its HTML report with `open: never`; no report server is left running and the command always returns the final test exit code.
