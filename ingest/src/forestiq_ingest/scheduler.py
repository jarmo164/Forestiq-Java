from __future__ import annotations

import argparse
import os
import signal
import sys
import time
from typing import Sequence

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.runtime_health import record_runtime_heartbeat
from forestiq_ingest.database.scheduler import enqueue_due_jobs
from forestiq_ingest.logging import configure_logging


_SHOULD_STOP = False


def _request_stop(_signum: int, _frame: object) -> None:
    global _SHOULD_STOP
    _SHOULD_STOP = True


def _max_catchup_windows() -> int:
    raw = os.getenv("FORESTIQ_INGEST_MAX_CATCHUP_WINDOWS", "2")
    try:
        return max(1, min(int(raw), 100))
    except ValueError:
        return 2


def run_once(settings: IngestSettings) -> int:
    logger = configure_logging(settings.log_level, component=settings.component, worker_id=settings.worker_id)
    if not settings.database_url:
        logger.error("DATABASE_URL or INGEST_DATABASE_URL is required for scheduler execution")
        return 2

    try:
        with psycopg.connect(settings.database_url) as connection:
            cycle = enqueue_due_jobs(
                connection,
                enabled_job_keys=settings.enabled_job_keys,
                max_catchup_windows=_max_catchup_windows(),
            )
    except psycopg.Error as exc:
        logger.error("ingestion scheduler database cycle failed", extra={"error_type": type(exc).__name__})
        return 1

    if not cycle.leader_acquired:
        logger.info("ingestion scheduler cycle skipped because another replica owns the leader lock")
        return 0

    logger.info(
        "ingestion scheduler cycle finished",
        extra={
            "owned_jobs": cycle.owned_jobs,
            "enqueued": cycle.enqueued,
            "not_due": cycle.not_due,
            "unavailable_scope": cycle.unavailable_scope,
        },
    )
    return 0


def run_forever(settings: IngestSettings) -> int:
    signal.signal(signal.SIGTERM, _request_stop)
    signal.signal(signal.SIGINT, _request_stop)

    logger = configure_logging(settings.log_level, component=settings.component, worker_id=settings.worker_id)
    logger.info("ingestion scheduler ready")

    next_heartbeat = 0.0
    poll_seconds = max(1, settings.poll_interval_seconds)
    while not _SHOULD_STOP:
        cycle_started = time.monotonic()
        result = run_once(settings)
        if result != 0:
            logger.error("ingestion scheduler cycle failed", extra={"exit_code": result})

        now = time.monotonic()
        if now >= next_heartbeat:
            if not record_runtime_heartbeat(settings):
                logger.error("ingestion scheduler heartbeat failed")
            next_heartbeat = now + max(5, settings.heartbeat_seconds)

        elapsed = time.monotonic() - cycle_started
        sleep_seconds = max(0.1, poll_seconds - elapsed)
        time.sleep(sleep_seconds)

    logger.info("ingestion scheduler stopped")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ForestIQ ingestion scheduler")
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args(argv)

    settings = IngestSettings.from_env(component="scheduler")
    if args.once:
        return run_once(settings)

    return run_forever(settings)


if __name__ == "__main__":
    sys.exit(main())
