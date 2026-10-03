# Production performance evidence

Related: #208

ForestIQ performance tuning is driven by representative production telemetry, not by the retired local 16 GB workstation profile.

## Evidence format

Export one aggregate row per time window and workflow. Required CSV columns:

- `window_start`
- `workflow`
- `sample_count`
- `p50_ms`
- `p95_ms`
- `p99_ms`
- `error_rate_percent`

Optional aggregate columns:

- `api_cpu_percent`
- `api_memory_mb`
- `db_p95_ms`
- `db_pool_pressure_percent`
- `mvt_cache_hit_percent`
- `background_job_p95_ms`
- `response_p95_bytes`

Do not export request/response bodies, SQL text, names, addresses, emails, phone numbers, bearer tokens or other credentials/PII.

## Generate the baseline report

~~~bash
python scripts/production_performance_report.py production-performance.csv \
  --output production-performance-baseline.md
~~~

The generated report is deliberately descriptive. It does not invent universal SLOs or recommend architecture changes from one measurement.

## Required workflow coverage

Capture representative evidence for at least:

- owner/search and ownership relations;
- parcel detail;
- map/MVT requests;
- critical deal/workdesk flows;
- background ingestion/sync jobs.

Include PostgreSQL pressure and container CPU/memory where available.

## Tuning decision

Only create an optimization implementation issue when a repeatable live bottleneck exists. Record:

1. affected workflow;
2. baseline p95/p99 and error rate;
3. resource/DB evidence;
4. suspected bottleneck;
5. measurable target;
6. before/after production-safe result.

This repository tooling prepares and validates the evidence. #208 remains open until representative live telemetry has actually been captured and reviewed.
