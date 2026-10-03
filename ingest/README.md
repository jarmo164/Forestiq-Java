# ForestIQ ingestion runtime

This service hosts the Python runtime for external-source ingestion jobs. Spring keeps ownership of API orchestration and enqueues work; the Python runtime owns provider adapters, staging, validation and publish steps.

## Local setup

~~~bash
cd ingest
python3.13 -m venv .venv
. .venv/bin/activate
pip install -e ".[test]"
pytest -m "not integration"
~~~

Optional geospatial dependencies live behind the `geo` extra:

~~~bash
pip install -e ".[geo,test]"
~~~

## Test layers

Pure Python unit/contract tests do not require PostgreSQL and are the default fast-CI suite:

~~~bash
cd ingest
pytest -m "not integration"
~~~

Database integration tests are explicitly marked `integration` and require a disposable PostgreSQL/PostGIS database:

~~~bash
cd ingest
TEST_POSTGIS_URL=postgresql://forestiq:forestiq@127.0.0.1:55432/forestiq \
  pytest -m integration
~~~

Keep parser, transformation and business-rule tests in the fast suite. Mark a test `@pytest.mark.integration` only when it opens a real database connection or validates PostgreSQL/PostGIS behavior.

## Entrypoints

~~~bash
python -m forestiq_ingest.healthcheck --component worker
python -m forestiq_ingest.worker --run-dummy-job
python -m forestiq_ingest.worker --claim-once
python -m forestiq_ingest.scheduler --once
~~~

The default worker and scheduler commands run continuously for Docker Compose. The dummy worker path is deterministic and makes no provider calls, so it can be used for smoke tests before source-specific adapters are enabled. The `--claim-once` command exercises the PostgreSQL queue contract without calling Java REST callbacks.

## Configuration

Configuration is read from environment variables:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` or `INGEST_DATABASE_URL` | PostgreSQL connection string for queue/runtime metadata |
| `FORESTIQ_INGEST_COMPONENT` | Component name reported in structured logs and health output |
| `FORESTIQ_INGEST_WORKER_ID` | Stable worker identity, defaults to hostname |
| `FORESTIQ_INGEST_ENABLED_JOB_KEYS` | Optional comma-separated allow-list of job keys |
| `FORESTIQ_INGEST_POLL_INTERVAL_SECONDS` | Worker/scheduler idle sleep, default `15` |
| `FORESTIQ_INGEST_HEARTBEAT_SECONDS` | Runtime heartbeat cadence, default `30` |
| `FORESTIQ_INGEST_LEASE_SECONDS` | Queue lease duration, default `300` |
| `FORESTIQ_INGEST_TEMP_DIR` | Scratch location for downloads and staging |
| `FORESTIQ_INGEST_LOG_LEVEL` | JSON logger level, default `INFO` |
| `FORESTIQ_INGEST_HEALTHCHECK_REQUIRE_DB` | Set to `true` to include PostgreSQL in health checks |

Health checks are intentionally provider-independent: they do not contact WFS, HTTP or other external source systems.
