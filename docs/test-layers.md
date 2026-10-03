# ForestIQ test layers and ownership

ForestIQ uses explicit test layers so fast pull-request feedback stays cheap while database, browser and image verification stays on the Mac Studio heavy runner.

| Layer | Primary suites | Infrastructure | CI owner |
| --- | --- | --- | --- |
| Static | ESLint, TypeScript, observability/config validation | None | `CI` / GitHub-hosted |
| Unit | Java JUnit, frontend Vitest, Python `pytest -m "not integration"` | None | `CI` / GitHub-hosted |
| Contract | Ingestion deployment boundary/contracts | No full stack | `CI` / GitHub-hosted |
| API contract | OpenAPI generation + committed TypeScript client drift | Disposable PostGIS + Spring API | `Heavy CI` / Mac Studio |
| Integration | Java/PostGIS, Python/PostGIS, Metsaregister/PostGIS | Disposable PostGIS | `Heavy CI` / Mac Studio |
| E2E | Authenticated Playwright critical business flows | Compose: PostGIS, Keycloak, MinIO, API, frontend | `Heavy CI` / Mac Studio |
| Image/security | Service image build + Trivy CRITICAL scan | Docker | `Heavy CI` / Mac Studio |

## Rules

- Unit tests must not require Docker Compose, Keycloak, MinIO or a real PostgreSQL server.
- Python tests that open a real PostgreSQL/PostGIS connection must use the `integration` pytest marker.
- Browser tests are reserved for critical cross-service user/business flows; component behavior belongs in Vitest.
- API URL, request and response drift is checked by regenerating the committed OpenAPI client and requiring a clean Git diff.
- Database integration uses disposable run-scoped PostGIS containers and the shared scoped cleanup script.
- Failed heavy jobs upload diagnostics before cleanup. Successful jobs do not retain large diagnostics.
- New suites must be assigned to a layer before being added to a required aggregate gate.

## Local commands

Fast Python ingestion:

~~~bash
make test-ingest
~~~

Database integration requires a disposable/local PostGIS URL:

~~~bash
TEST_POSTGIS_URL=postgresql://forestiq:forestiq@127.0.0.1:55432/forestiq \
  make check-ingest-integration check-metsaregister-integration
~~~

Full heavy verification locally:

~~~bash
TEST_POSTGIS_URL=postgresql://forestiq:forestiq@127.0.0.1:55432/forestiq \
OPENAPI_URL=http://127.0.0.1:8080/api/openapi \
  make test-heavy
~~~

The GitHub Heavy CI workflow provisions its own PostGIS containers and API process, so it does not depend on a developer database.

## Required gates

Branch protection should depend on aggregate gates rather than individual conditional jobs:

- `CI / fast-quality-gate`
- `Heavy CI / heavy-quality-gate`

The heavy aggregate gate includes backend integration, ingestion integration, Metsaregister integration, OpenAPI contract, Playwright E2E and Docker build/security verification whenever heavy execution is selected.
