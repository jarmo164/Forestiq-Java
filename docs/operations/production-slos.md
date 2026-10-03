# ForestIQ Production SLOs

Status: accepted
Issue: #394

| Area | Indicator | Objective | Alert |
| --- | --- | --- | --- |
| API availability | API Prometheus target and readiness are up | 99.5% monthly | ForestIQApiReadinessFailure |
| API latency | p95 HTTP latency for critical API routes | below 1.5 s over 30 min | dashboard watch |
| API errors | 5xx / all requests | below 2% for 10 min | ForestIQApiHigh5xxRate |
| Ingestion worker | latest worker poll heartbeat | below 5 min old | ForestIQIntegrationWorkerHeartbeatStale |
| Queue backlog | queued durable ingestion runs | below 25 for 15 min | ForestIQIntegrationQueueBacklog |
| Provider reliability | failed integration jobs | fewer than 3 failures / 30 min | ForestIQProviderFailures |
| Geospatial freshness | source age | below 24 h unless source-specific exception exists | ForestIQGeospatialFreshnessLag |

Error budget breaches pause non-essential feature deployment for the affected area until recovery or a follow-up issue owns the risk.

Do not add user IDs, bearer tokens, cadastral IDs, owner IDs, raw query values or private payload values as Prometheus labels.

API, BFF and ingestion diagnostics preserve correlation through request/run IDs. Durable ingestion rows remain the cross-runtime correlation record through integration_sync_run.id, job_key, worker_id, heartbeat_at and published_generation_id.

The full local profile binds Prometheus and Grafana ports through LOCAL_PORT_HOST, defaulting to 127.0.0.1. Production must keep /actuator/prometheus reachable only from the metrics collector network or an authenticated internal path.
