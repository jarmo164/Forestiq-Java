from __future__ import annotations

import argparse
import signal
import sys
import threading
import time
import uuid
from typing import Sequence

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import claim_next_job, complete_job, heartbeat_job
from forestiq_ingest.database.runtime_health import record_runtime_heartbeat
from forestiq_ingest.logging import configure_logging
from forestiq_ingest.worker.execution import ExecutionResult, execute_claimed_job


_SHOULD_STOP = False


def _request_stop(_signum: int, _frame: object) -> None:
    global _SHOULD_STOP
    _SHOULD_STOP = True


def run_dummy_job(settings: IngestSettings, job_key: str = "DUMMY_HEALTHCHECK") -> int:
    correlation_id = str(uuid.uuid4())
    logger = configure_logging(
        settings.log_level,
        component=settings.component,
        worker_id=settings.worker_id,
        job_key=job_key,
        correlation_id=correlation_id,
    )
    logger.info("dummy ingestion job started")
    settings.temp_dir.mkdir(parents=True, exist_ok=True)
    logger.info("dummy ingestion job finished")
    return 0


class _LeaseKeeper:
    def __init__(self, settings: IngestSettings, run_id: uuid.UUID):
        self.settings = settings
        self.run_id = run_id
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def __enter__(self) -> "_LeaseKeeper":
        if not self.settings.database_url:
            return self
        self._thread = threading.Thread(target=self._run, name=f"ingest-lease-{self.run_id}", daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _run(self) -> None:
        interval = max(5, min(self.settings.heartbeat_seconds, max(5, self.settings.lease_seconds // 3)))
        while not self._stop.wait(interval):
            try:
                with psycopg.connect(self.settings.database_url) as connection:
                    heartbeat_job(
                        connection,
                        run_id=self.run_id,
                        worker_id=self.settings.worker_id,
                        lease_seconds=self.settings.lease_seconds,
                    )
            except psycopg.Error:
                # The main execution path remains authoritative. A later missed
                # lease heartbeat makes the durable row reclaimable.
                continue


def _safe_failure(exc: BaseException) -> str:
    message = str(exc) or type(exc).__name__
    safe = " ".join(message.replace("\r", " ").replace("\n", " ").split())
    return safe[:500]


def _claim_and_execute(connection: psycopg.Connection, settings: IngestSettings) -> tuple[bool, int]:
    job = claim_next_job(
        connection,
        worker_id=settings.worker_id,
        lease_seconds=settings.lease_seconds,
        enabled_job_keys=settings.enabled_job_keys,
    )
    if job is None:
        return False, 0

    # Publish the lease before provider/ETL work starts so crash recovery and
    # heartbeat renewal are real, rather than being hidden in one long DB tx.
    connection.commit()

    job_logger = configure_logging(
        settings.log_level,
        component=settings.component,
        worker_id=settings.worker_id,
        job_key=job.job_key,
        run_id=str(job.id),
        correlation_id=str(uuid.uuid4()),
    )
    job_logger.info("claimed ingestion job")

    try:
        with _LeaseKeeper(settings, job.id):
            result = execute_claimed_job(connection, job, settings)
    except BaseException as exc:
        if isinstance(exc, (KeyboardInterrupt, SystemExit)):
            raise
        # An executor may fail while PostgreSQL has the connection in aborted
        # transaction state. Reset it before recording the durable FAILED result.
        try:
            connection.rollback()
        except psycopg.Error:
            pass
        result = ExecutionResult(
            state="FAILED",
            metrics={"contractVersion": job.contract_version, "attempts": job.attempts},
            message="Python ingestion execution failed.",
            error_summary=_safe_failure(exc),
        )
        job_logger.exception("ingestion job execution failed")

    complete_job(
        connection,
        run_id=job.id,
        worker_id=settings.worker_id,
        state=result.state,
        metrics=result.metrics,
        message=result.message,
        error_summary=result.error_summary,
        rows_read=result.rows_read,
        rows_staged=result.rows_staged,
        rows_written=result.rows_written,
        rows_rejected=result.rows_rejected,
        bytes_read=result.bytes_read,
        bytes_staged=result.bytes_staged,
        bytes_written=result.bytes_written,
        source_version=result.source_version,
        source_checksum=result.source_checksum,
        published_generation_id=result.published_generation_id,
    )
    connection.commit()

    if result.state in {"SUCCEEDED", "PARTIAL"}:
        job_logger.info("completed ingestion job", extra={"state": result.state})
        return True, 0

    job_logger.warning("ingestion job finished unsuccessfully", extra={"state": result.state})
    return True, 1


def claim_once(settings: IngestSettings) -> int:
    logger = configure_logging(settings.log_level, component=settings.component, worker_id=settings.worker_id)
    if not settings.database_url:
        logger.error("DATABASE_URL or INGEST_DATABASE_URL is required for --claim-once")
        return 2

    try:
        with psycopg.connect(settings.database_url) as connection:
            processed, exit_code = _claim_and_execute(connection, settings)
    except psycopg.Error as exc:
        logger.error("ingestion queue database operation failed", extra={"error_type": type(exc).__name__})
        return 1

    if not processed:
        logger.info("no ingestion job available")
    return exit_code


def run_forever(settings: IngestSettings) -> int:
    signal.signal(signal.SIGTERM, _request_stop)
    signal.signal(signal.SIGINT, _request_stop)

    logger = configure_logging(settings.log_level, component=settings.component, worker_id=settings.worker_id)
    logger.info(
        "ingestion worker ready",
        extra={"enabled_job_keys": ",".join(settings.enabled_job_keys) if settings.enabled_job_keys else "all"},
    )

    next_heartbeat = 0.0
    idle_sleep = max(1, settings.poll_interval_seconds)
    while not _SHOULD_STOP:
        processed = False
        try:
            if not settings.database_url:
                logger.error("DATABASE_URL or INGEST_DATABASE_URL is required for worker execution")
                time.sleep(idle_sleep)
                continue
            with psycopg.connect(settings.database_url) as connection:
                processed, _exit_code = _claim_and_execute(connection, settings)
        except psycopg.Error as exc:
            logger.error("ingestion worker database cycle failed", extra={"error_type": type(exc).__name__})

        now = time.monotonic()
        if now >= next_heartbeat:
            if not record_runtime_heartbeat(settings):
                logger.error("ingestion worker heartbeat failed")
            next_heartbeat = now + max(5, settings.heartbeat_seconds)

        # Drain available work without an artificial sleep; back off only when
        # the durable queue is empty or the DB cycle failed.
        if not processed:
            time.sleep(idle_sleep)

    logger.info("ingestion worker stopped")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ForestIQ ingestion worker")
    parser.add_argument("--run-dummy-job", action="store_true")
    parser.add_argument("--claim-once", action="store_true", help="Claim and execute one PostgreSQL queue row")
    parser.add_argument("--once", action="store_true", help="Run one deterministic no-provider smoke cycle and exit")
    args = parser.parse_args(argv)

    settings = IngestSettings.from_env(component="worker")

    if args.claim_once:
        return claim_once(settings)

    if args.run_dummy_job or args.once:
        return run_dummy_job(settings)

    return run_forever(settings)


if __name__ == "__main__":
    sys.exit(main())
