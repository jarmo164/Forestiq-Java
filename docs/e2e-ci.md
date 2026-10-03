# Playwright E2E CI

The CI `Playwright E2E` job runs the critical browser smoke flows against the real Docker Compose stack:

- Postgres/PostGIS with Flyway demo data.
- Keycloak with `infra/keycloak/forestiq-realm.json`.
- MinIO bucket setup.
- Spring API.
- Next.js frontend.

The deterministic browser user is imported by Keycloak:

- Username: `admin`
- Password: `forestiq`
- Realm: `forestiq-dev`
- Organization: `11111111-1111-1111-1111-111111111111`

The suite covers the OIDC login entry point, unauthenticated redirect, real Keycloak login, owner search/detail, activity-note mutation, My Work, sales workspace, map, parcel registry controls, and deal-panel smoke coverage.

Run locally against an already started Compose stack:

```bash
cd frontend
PLAYWRIGHT_BASE_URL=http://127.0.0.1:3000 E2E_USERNAME=admin E2E_PASSWORD=forestiq pnpm test:e2e
```

Without `PLAYWRIGHT_BASE_URL`, Playwright starts the local standalone frontend itself. That mode is useful for the login page smoke test, but the critical authenticated flows require the full Compose stack.
