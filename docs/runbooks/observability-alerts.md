# Observability Alert Runbook

## ForestIQApiReadinessFailure
Impact: users may be unable to use API-backed workflows.
First checks: API health, recent deploy, database connectivity and Keycloak/JWK reachability.
Safe recovery: restart API after dependencies are healthy or roll back the last deploy.
Escalation: backend owner, then platform owner.
Postmortem trigger: outage longer than 15 minutes or repeated twice in 24 hours.

## ForestIQApiHigh5xxRate
Impact: users see failed requests or broken workflows.
First checks: API/BFF dashboard, API logs in Loki, Sentry and database pool pressure.
Safe recovery: disable the failing scheduled job, roll back a bad release or scale after saturation is confirmed.
Escalation: backend owner.
Postmortem trigger: sustained breach longer than 30 minutes.

## ForestIQIntegrationWorkerHeartbeatStale
Impact: external data may stop refreshing while the UI serves old data.
First checks: worker status, integration_sync_run active rows, worker logs and lease timestamps.
Safe recovery: restart the worker and allow stale leases to expire before adding replicas.
Escalation: ingest owner.
Postmortem trigger: missed scheduled source window or manual intervention.

## ForestIQIntegrationQueueBacklog
Impact: manual and scheduled data refreshes may be delayed.
First checks: queue depth by job key, oldest queued run, active worker count and provider latency.
Safe recovery: pause noisy schedules, add a worker if DB capacity allows, or cancel repeated failing runs.
Escalation: ingest owner and platform owner when DB saturation is involved.
Postmortem trigger: backlog above threshold for more than one hour.

## ForestIQProviderFailures
Impact: one or more external sources may be stale or incomplete.
First checks: failing job key, provider status, malformed payload evidence and retry history.
Safe recovery: keep last-known-good data published and do not advance cursors or watermarks on failed runs.
Escalation: ingest owner; provider contact if upstream is unavailable.
Postmortem trigger: same provider fails three scheduled windows in a row.

## ForestIQGeospatialFreshnessLag
Impact: map/detail views may show stale GIS facts.
First checks: source generation status, latest successful run, source checksum/version and failed validation reason.
Safe recovery: keep current published generation active and rerun ingestion after provider health is confirmed.
Escalation: geospatial owner.
Postmortem trigger: source freshness breach affects customer-visible decisions.
