# ForestIQ production deployment on Render

This runbook defines the minimum Render setup required for production data ingestion. The production API must remain continuously available because scheduled registry, MetsIS/Forestek, Weasel and inheritance jobs run inside the Spring process.

## Production resource baseline

The repository `render.yaml` intentionally uses paid resources for the production services:

- `forestiq-api`: Render `starter` web service. This is the ingestion scheduler/worker process and must not idle-suspend.
- `forestiq-keycloak`: Render `starter` web service so authentication and server-side Keycloak administration do not depend on a cold start.
- `forestiq-web`: Render `starter` web service for predictable production UI availability.
- `forestiq-db`: Render Postgres `basic-256mb` or larger. Do not use Free Postgres for production data.

Paid Render Postgres is required because production data and ingestion cursors must survive indefinitely and have managed recovery capability. Increase database compute/storage independently when volume requires it.

## Scheduled feeds enabled in production

The production Blueprint enables these switches deliberately:

| Feed | Environment switch | Production state |
| --- | --- | --- |
| Maa- ja Ruumiamet / Metsaregister refresh | `REGISTRY_AUTO_REFRESH_ENABLED` | `true` |
| MetsIS/Forestek owner-cadastre snapshot | `METSIS_PORTFOLIO_AUTO_SYNC_ENABLED` | `true` |
| Weasel ownership transitions | `WEASEL_AUTO_REFRESH_ENABLED` | `true` |
| Ametlikud Teadaanded inheritance notices | `INHERITANCE_SOURCE_AUTO_REFRESH_ENABLED` | `true` |

Do not enable automatic ingestion in preview/test services unless a test explicitly requires it. Render preview generation is disabled in the production Blueprint.

## Secrets and provider configuration

Provider credentials and tenant identifiers must be configured in Render, never committed to Git.

The Blueprint declares these as `sync: false`, so they remain Render-managed values:

- `METSIS_PORTFOLIO_URI`
- `METSIS_PORTFOLIO_AUTHORIZATION`
- `METSIS_PORTFOLIO_ORGANIZATION_ID`
- `WEASEL_API_BASE_URL`
- `WEASEL_API_ACCESS_TOKEN`
- `WEASEL_ORGANIZATION_ID`

The registry WFS endpoints use the public defaults from `application.yml`. The inheritance feed uses the direct Ametlikud Teadaanded URI template by default and does not require a repository secret.

Never put provider tokens, passwords, bearer headers or organization-specific secrets directly in `render.yaml`, `.env` files committed to Git, issue comments or CI logs.

## Deployment procedure

Production services have `autoDeployTrigger: off`; a push to `main` never starts a Render production deploy by itself.

Use **Actions → Production deploy** and provide the exact `main` commit SHA. The workflow verifies that SHA and canonical CI, then builds API, web and ingest images once for `linux/amd64`. It pushes them to GHCR, scans the exact pushed digest, writes SPDX SBOM evidence, publishes provenance/SBOM attestations, keyless-signs the digest, and finally promotes only `repository@sha256:...` references to Render.

Render must have a GitHub Container Registry credential capable of pulling this private repository's GHCR packages. Configure `RENDER_API_KEY` and `RENDER_IMAGE_SERVICE_MAP_JSON` only in the protected `production` environment. The service map JSON contains `api`, `web`, `ingest_scheduler` and `ingest_worker` Render service IDs.

The workflow does not deploy `latest` and does not ask Render to rebuild application source.

### Release identity and traceability

Every production attempt has one immutable ID: `forestiq-<GitHub run number>-<12-char commit>`. The same ID and full commit SHA are embedded into API, web and ingestion images. API exposes them from `/actuator/info`; web exposes them from `/api/build-info`. The production smoke suite verifies both public values before the release can succeed.

After smoke passes, `assemble_release_manifest.py` writes `release-manifest.json`. It contains the release ID, full commit SHA, Flyway migration version, workflow-run URL, all three exact image digests, Render service/deploy IDs, previous live deploy IDs as deterministic rollback targets, and the successful smoke evidence. The manifest is retained as a 365-day Actions artifact.

The final job creates a GitHub Release and tag using the same release ID, attaches the manifest, targets the exact deployed commit, and uses GitHub-generated release notes from the merged PR/issue history. A release therefore has one traceable path from source commit → image digest → migration → Render deploy → smoke evidence → rollback target.

### Database rollout contract

Flyway has one production owner: the protected `Production deploy` workflow. Before any image is promoted it runs the migration suite against disposable PostGIS twice: a clean database and an N-1 schema upgraded to N. Future migrations from V65 onward are subject to `config/migration-policy.json`; destructive contract-phase SQL fails unless it carries an explicit reviewed `forestiq:migration-exception` reason.

After immutable image assembly and before Render promotion, the production job runs `migrateDatabase` using `FORESTIQ_PRODUCTION_FLYWAY_URL`, `FORESTIQ_PRODUCTION_FLYWAY_USERNAME` and `FORESTIQ_PRODUCTION_FLYWAY_PASSWORD`. A migration or validation failure stops the release before application promotion.

Runtime identities are separate: `forestiq_api_login` inherits `forestiq_api`, `forestiq_ingest_login` inherits `forestiq_ingest`, and `forestiq_keycloak_login` owns the dedicated `keycloak` schema. The Flyway/database-owner identity is not configured in API runtime. Bootstrap or rotate runtime roles with `scripts/release/bootstrap-production-database-roles.sql`, then supply those credentials only through Render's `sync: false` values. The release workflow verifies role membership, non-superuser/non-BYPASSRLS flags and Keycloak schema ownership before promotion.

1. Merge a CI-green change to `main`.
2. Sync/apply the Render Blueprint.
3. Supply or verify every `sync: false` value in Render before considering ingestion healthy.
4. Confirm `forestiq-db` is on a paid plan and healthy before the API rollout becomes live.
5. Confirm `forestiq-keycloak`, `forestiq-api` and `forestiq-web` are running on always-on paid instances.
6. Confirm `/actuator/health/readiness` is healthy on the API.
7. Sign in as an admin and open the integrations view.
8. Verify every intended production feed is `ENABLED`, not `DISABLED` or `MISCONFIGURED`.
9. Record the latest successful run timestamp for each feed.
10. Leave the application without user traffic and verify at least two consecutive scheduled cycles complete for the shortest practical scheduled feeds. For longer schedules, temporarily use an approved shorter production verification interval and restore the normal interval after verification.
11. Confirm new scheduled runs appear without anyone opening the web UI.

## Automated production verification

After production has had enough time to execute at least two normal scheduler cycles, use the read-only verification script or the GitHub Actions workflow instead of manually inspecting database rows.

The verifier checks all six production job keys:

- `CADASTRE_PUBLIC_DATA`
- `FOREST_NOTIFICATIONS`
- `FOREST_PLAN`
- `OWNER_CADASTRE_RELATIONS`
- `WEASEL_OWNERSHIP_TRANSITIONS`
- `INHERITANCE_NOTICES`

For every feed it requires:

1. `configurationState == ENABLED`;
2. health state is `HEALTHY` or `DEGRADED` (not `STALE`, `DISABLED` or `MISCONFIGURED`);
3. at least two completed `SCHEDULED` runs exist in recent history;
4. the latest two completed scheduled runs are both `SUCCEEDED`.

The check reads existing history, so it does **not** keep a GitHub runner alive for a 6- or 24-hour schedule.

### GitHub Actions evidence

Configure these repository secrets temporarily or through the repository's approved production secret-management process:

- `FORESTIQ_PRODUCTION_API_URL` — external production API base URL;
- `FORESTIQ_PRODUCTION_BEARER_TOKEN` — a current production `ORG_ADMIN` access token for the organization being verified.

Then run **Actions → Production Ingestion Verification → Run workflow**. The default requirement is two successful scheduled cycles per feed. The workflow uploads `production-ingestion-verification-<run-id>` as a 90-day evidence artifact and also writes the result to the workflow summary.

The bearer token is sent only in the HTTP `Authorization` header and is never printed by the verifier. Because normal Keycloak access tokens expire, replace/remove the repository secret after the verification window rather than treating it as a permanent credential.

### Local verification

The same check can be run from an operator workstation with a current admin token:

```bash
export FORESTIQ_PRODUCTION_API_URL="https://<production-api-host>"
export FORESTIQ_PRODUCTION_BEARER_TOKEN="<current-org-admin-access-token>"
python scripts/verify-production-ingestion.py
```

A non-zero exit code means the production-ingestion acceptance evidence is not yet valid. Fix the reported feed/configuration/run state and rerun the check; do not close the production-readiness issue based only on HTTP readiness.

## Data-flow verification

For each feed, verify both scheduling and data movement:

- registry: a scheduled run is created and `latestSuccess` advances;
- MetsIS/Forestek: snapshot paging completes and the owner-cadastre checkpoint advances only after a complete snapshot;
- Weasel: the ownership transition window/cursor advances only after valid pages are processed;
- inheritance: `lastCompletedDate` advances and catch-up backlog decreases after downtime.

A successful HTTP health endpoint alone is not sufficient evidence that production ingestion is healthy.

## Post-deploy verification and rollback

Promotion waits for each Render deploy to reach `live`; the workflow records the previous live deploy ID before changing the service. A release is not successful until all post-deploy checks pass:

1. web login surface responds;
2. API readiness reports `UP`;
3. Keycloak realm discovery returns the expected issuer;
4. an authenticated `ORG_ADMIN` request to `/api/v1/admin/integrations` succeeds;
5. both dedicated Python runtime components have a database heartbeat no older than 180 seconds.

The protected production environment therefore also provides `FORESTIQ_PRODUCTION_WEB_URL`, `FORESTIQ_PRODUCTION_API_URL`, `FORESTIQ_PRODUCTION_KEYCLOAK_URL` and a short-lived `FORESTIQ_PRODUCTION_BEARER_TOKEN`.

If heartbeat or smoke verification fails after promotion, `render_rollback.py` calls Render's rollback endpoint for every promoted service, in reverse deployment order, using the exact previous live deploy IDs captured before promotion. The workflow then runs the HTTP/auth smoke suite against the rollback state. The original release remains failed even when rollback succeeds.

Do not reverse a schema migration during application rollback. V65+ migrations are expand-compatible by default so the previous application release remains usable against the expanded schema; a later contract migration requires an explicit reviewed compatibility exception.

If the database itself must be recovered, use Render Postgres point-in-time recovery and validate the restored database before repointing services. Do not overwrite the only recoverable copy during investigation.

## Capacity notes

The initial paid plans are a minimum baseline, not a final sizing decision. Watch:

- API CPU/memory during WFS bulk runs;
- Hikari connection saturation;
- Postgres CPU, RAM, disk growth and slow queries;
- integration executor queue saturation;
- provider backlog/cursor lag;
- job duration compared with schedule interval.

Scale the API and database before normal ingestion duration approaches the scheduling interval or database resource pressure becomes sustained.
