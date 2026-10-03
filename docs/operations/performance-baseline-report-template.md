# Production Performance Baseline Report

Window: YYYY-MM-DD HH:mm to YYYY-MM-DD HH:mm
Environment: production / staging-prodlike
Data volume summary:
Traffic summary:
Prepared by:

## Executive Summary

| Area | Status | Evidence | Follow-up |
| --- | --- | --- | --- |
| API latency |  |  |  |
| PostgreSQL |  |  |  |
| Map tiles |  |  |  |
| Background ingestion |  |  |  |
| Container resources |  |  |  |

## API Route Groups

| Route group | Requests | Error rate | p50 ms | p95 ms | p99 ms | Notes |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Owners |  |  |  |  |  |  |
| Ownership relations |  |  |  |  |  |  |
| Parcel detail |  |  |  |  |  |  |
| Map tiles |  |  |  |  |  |  |
| Admin integrations |  |  |  |  |  |  |

## PostgreSQL

| Signal | Baseline | Evidence | Action |
| --- | --- | --- | --- |
| Slow queries |  |  |  |
| Locks/waits |  |  |  |
| Connection pressure |  |  |  |
| Temp I/O |  |  |  |
| Dead tuples/autovacuum |  |  |  |

## Background Jobs

| Job family | Runs | Success | p95 duration | Failures | API impact |
| --- | ---: | ---: | ---: | --- | --- |
| Metsaregister |  |  |  |  |  |
| Cadastre/geospatial |  |  |  |  |  |
| Owner/MetsIS/Weasel/inheritance |  |  |  |  |  |

## Candidate Bottlenecks

| Priority | Workflow | Evidence | Suspected cause | Target | Child issue |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |

## Deferred Optimizations

List ideas that are explicitly not justified by this baseline.
