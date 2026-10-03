# Continuous ingestion soak and recovery validation

This document is the repeatable validation procedure for ForestIQ EPIC #69 / issue #68. It complements the feed-specific parser, pagination, checkpoint, Flyway/PostGIS and scheduler regression tests with one multi-cycle control-plane soak test.

## Automated soak test

Run from `backend/`:

```bash
./gradlew test --tests ee.forestiq.integration.ContinuousIngestionSoakTest --no-daemon
```

The test executes repeated scheduled cycles for every production-critical job key:

- `OWNER_CADASTRE_RELATIONS`
- `CADASTRE_PUBLIC_DATA`
- `FOREST_NOTIFICATIONS`
- `FOREST_PLAN`
- `WEASEL_OWNERSHIP_TRANSITIONS`
- `INHERITANCE_NOTICES`

It uses stateful provider stubs around the real `IntegrationJobService` orchestration and deliberately injects:

- temporary provider 5xx;
- provider timeout;
- malformed provider payload;
- incomplete owner/cadastre snapshot;
- database connection loss after a durable logical write;
- page-cap/checkpoint partial completion;
- integration executor saturation;
- application restart with an orphaned `RUNNING` row;
- backlog larger than one scheduler batch;
- multi-cycle inheritance catch-up;
- retry of an already-written logical event.

The assertions require automatic recovery without manual database repair, no active/orphaned run left behind, no incomplete snapshot application, idempotent retry, and eventual backlog/cursor-lag drain to zero.

## Feed-specific safety coverage

The soak test intentionally does not duplicate every provider parser implementation. Those contracts remain covered by focused tests that run in the normal backend CI gate:

- `WeaselOwnershipClientTest` — missing/wrong `items`, invalid IDs/cadastres/timestamps and valid empty pages fail closed correctly.
- `MetsisPortfolioClientTest` — short non-empty pages continue paging, malformed rows fail closed, and only an empty provider page terminates the snapshot.
- `RegistryIntegrationJobConfigurationTest` — bounded multi-batch catch-up, partial provider failures and checkpointed bulk runs.
- `InheritanceNoticeIntegrationJobTest` — durable historical cursor plus rolling today/yesterday overlap and bounded catch-up.
- `IntegrationJobServiceTest` — advisory-lock-aware stale recovery, delayed queued work suppression, scheduler isolation and executor saturation.
- `IntegrationDataflowHealthTest` / `DataflowHealthMetricsTest` — freshness SLO, backlog/cursor health and Prometheus signals.
- `FlywayPostgisApplicationContextTest` plus generated-jOOQ verification — clean PostGIS bootstrap and schema contract verification.

Together these tests cover both the concrete adapters and the repeated multi-feed runtime behavior required by EPIC #69.

## Machine-readable report

A successful soak run writes:

`backend/build/reports/dataflow-soak/summary.json`

For every feed it records before/after backlog, logical row count, signal count, cursor-lag proxy, last terminal state, last success, attempt count and duplicate-retry count. It also records the number of automatically recovered orphan runs and observed failed/partial runs.

GitHub Actions uploads this JSON as the `dataflow-soak-report` artifact so the evidence is retained outside the ephemeral build workspace.

## Production rollout verification

The repository-side test proves recovery without production credentials. After deploying `main`, production operations should additionally verify the real environment:

1. Render keeps `forestiq-api` and its PostgreSQL database on non-sleeping/persistent plans.
2. Required provider credentials and organization IDs are configured only in Render secrets/environment settings.
3. Admin → **Andmeallikad ja sünkroonimine** shows each required production feed as `ENABLED`, not `DISABLED` or `MISCONFIGURED`.
4. Observe at least two consecutive scheduled cycles without generating user traffic.
5. Confirm each feed stays within its documented freshness SLO and that `backlogRemaining`/cursor lag decreases after any catch-up.
6. If a provider is temporarily unavailable, verify a failed/degraded run is visible and the next successful cycle recovers without manual SQL repair.

Freshness thresholds and alert response are defined in `docs/data-freshness-operations.md`.
