# API and Ingestion Database Boundaries

Status: accepted

## Decision

ForestIQ uses the existing backend Flyway migration path as the single schema-migration authority:

- migration location: `backend/src/main/resources/db/migration`
- migration owner: the application migration credential configured for Spring Flyway
- Python ingestion: no Alembic or parallel DB migration runner unless a future ADR explicitly changes this

The database is split into three logical responsibilities:

| Area | Purpose | Current implementation |
| --- | --- | --- |
| `core` | Canonical ForestIQ domain data | Schema marker exists; current tables remain in `public` during migration |
| `app` | API-facing views, materialized views and read models | Schema marker exists; current tables remain in `public` during migration |
| `ingest` | Source run state, provider staging and raw payloads | Schema marker exists; current staging/source tables remain in `public` during migration |

Existing tables are not moved yet because generated Java/jOOQ reads currently target `public`. New ingestion tables should prefer the `ingest` schema once adapters are migrated.

## Runtime Roles

| Role | May read | May write | Must not write |
| --- | --- | --- | --- |
| `forestiq_api` | `core`, `app`, and existing `public` read models | API-owned CRM, deals, notifications, documents, workbaskets, plus enqueue/update run rows | Provider staging/raw tables such as cadastre import stage, WFS features and notification import stage |
| `forestiq_ingest` | Existing app context needed for source publication | Run state, source checkpoints, provider staging/raw tables and source-owned canonical publish tables | Users/auth, CRM activities, deal and contract management, reminders, workbasket membership and other API-owned business records |
| migration owner | All schemas | DDL and grants through Flyway only | Normal application/runtime work |

## Credential Rules

Production credentials must be separated:

- API runtime uses the `forestiq_api` credential or an equivalent least-privilege credential.
- Python ingestion uses the `forestiq_ingest` credential.
- Flyway may use a separate migration credential through `FLYWAY_DATABASE_USERNAME` and `FLYWAY_DATABASE_PASSWORD`.
- Provider secrets are worker-only and must not be injected into the API container.

Local Compose creates `forestiq_api` and `forestiq_ingest` login roles through `infra/postgres/init/20-ingest-role.sh`. The V61 Flyway migration also creates missing NOLOGIN role shells for test databases and applies the grants.

## Guardrails

The V61 migration includes negative privilege assertions:

- `forestiq_api` cannot insert into provider staging.
- `forestiq_api` cannot update raw geospatial feature rows.
- `forestiq_ingest` cannot insert CRM activity rows.
- `forestiq_ingest` cannot update protected contract rows.

These checks fail migration immediately if future grants accidentally broaden the runtime roles.
