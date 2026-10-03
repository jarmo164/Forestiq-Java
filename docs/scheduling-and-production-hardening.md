# Scheduling and production hardening

ForestIQ uses one recurring-job model: Spring `@Scheduled` workers coordinated with PostgreSQL advisory locks. Quartz is not part of the runtime architecture.

## Recurring workers

Every external-data worker delegates to the shared integration runner before doing external or state-changing work. The runner records the run, then acquires a `DistributedJobLock` scoped to organization and job key. The lock is a PostgreSQL session advisory lock held on a dedicated JDBC connection for the complete duration of the job. If another application replica already owns the lock, the run ends as `SKIPPED`.

| Worker | Feature switch | Job key |
| --- | --- | --- |
| Registry public-data refresh | `forestiq.registry.auto-refresh.enabled` | `CADASTRE_PUBLIC_DATA`, `FOREST_NOTIFICATIONS`, `FOREST_PLAN` |
| Official inheritance-notice refresh | `forestiq.inheritance-source.auto-refresh-enabled` | `INHERITANCE_NOTICES` |
| MetsIS portfolio synchronization | `forestiq.metsis-portfolio.auto-sync-enabled` | `OWNER_CADASTRE_RELATIONS` |
| Weasel ownership-transition synchronization | `forestiq.weasel.auto-refresh-enabled` | `WEASEL_OWNERSHIP_TRANSITIONS` |

The registry recovery service also keeps its transaction-scoped PostgreSQL advisory lock as defense in depth for manual and scheduled recovery entry points.

When adding a new recurring worker:

1. Use `@Scheduled`; do not add another scheduler framework.
2. Keep the worker disabled by default when it calls an optional/external source.
3. Register an `IntegrationJobHandler` and invoke it from the worker through `IntegrationJobService.runScheduledAll(...)`.
4. Keep the work bounded so one failed provider or oversized data set cannot monopolize the scheduler indefinitely.
5. Log a skipped lock acquisition and the outcome of an executed run without logging credentials or source payloads.

## Production profile

Production deployments must activate the `prod` Spring profile. `application-prod.yml` removes development fallbacks for the core infrastructure that must be explicitly configured:

- PostgreSQL URL, username and password;
- Redis host;
- RabbitMQ host, username and password;
- Keycloak issuer/JWK endpoints;
- allowed CORS origins;
- object storage endpoint, bucket and credentials;
- Keycloak admin endpoint, realm, client and secret.

`ProductionConfigurationGuard` runs only in the production profile and rejects known development secrets/local URLs. It additionally validates optional external integrations when their automatic worker is enabled:

- inheritance notices require the direct XML URI template, user agent and time zone;
- MetsIS portfolio sync requires URI, authorization and organization id;
- Weasel auto refresh requires base URL, access token and organization id.

Optional integrations may remain unconfigured only while their automatic execution is disabled.

## API validation

Request DTO validation belongs at the HTTP boundary. Controllers use `@Valid`, Bean Validation constraints and typed enums/date-times so invalid required inputs are rejected before workflow services run.

`ApiExceptionHandler` converts:

- `MethodArgumentNotValidException` to HTTP 400 `ProblemDetail` with field errors;
- method/constraint violations to HTTP 400 `ProblemDetail`;
- malformed JSON, invalid enum values and malformed date-times to HTTP 400 `ProblemDetail`.

Domain preconditions such as stale workflow transitions remain explicit 409/412 responses and are not represented as request-shape validation errors.
