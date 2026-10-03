from __future__ import annotations

import os
from datetime import UTC, datetime
from uuid import UUID

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.http.client import HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.metsis_owner_portfolio import (
    MetsisOwnerPortfolioAdapter,
    MetsisOwnerPortfolioConfig,
    stage_owner_portfolio_records,
)
from forestiq_ingest.worker.execution import ExecutionResult


def execute_owner_portfolio(
    connection: psycopg.Connection,
    job: ClaimedJob,
    _settings: IngestSettings,
) -> ExecutionResult:
    uri = (os.getenv("METSIS_PORTFOLIO_URI") or "").strip()
    authorization = (os.getenv("METSIS_PORTFOLIO_AUTHORIZATION") or "").strip()
    configured_org = (os.getenv("METSIS_PORTFOLIO_ORGANIZATION_ID") or "").strip()
    if not uri or not authorization or not configured_org:
        raise RuntimeError(
            "METSIS_PORTFOLIO_URI, METSIS_PORTFOLIO_AUTHORIZATION and "
            "METSIS_PORTFOLIO_ORGANIZATION_ID are required"
        )
    try:
        organization_id = UUID(configured_org)
    except ValueError as exc:
        raise RuntimeError("METSIS_PORTFOLIO_ORGANIZATION_ID must be a UUID") from exc
    if organization_id != job.organization_id:
        raise RuntimeError("MetsIS provider account is not bound to the queued organization")

    page_size = _env_int("METSIS_PORTFOLIO_PAGE_SIZE", 1000, minimum=1, maximum=10_000)
    max_pages = _env_int("METSIS_PORTFOLIO_MAX_PAGES_PER_RUN", 10_000, minimum=1, maximum=100_000)
    adapter = MetsisOwnerPortfolioAdapter(
        MetsisOwnerPortfolioConfig(uri, organization_id, page_size=page_size, max_pages=max_pages),
        HttpSourceClient(base_url=uri),
        authorization,
    )
    context = PipelineContext(job.id, job.job_key, adapter.spec.source_key)

    _cleanup_staging(connection, job.id)
    connection.commit()

    rows_read = 0
    rows_staged = 0
    pages = 0
    try:
        for page in adapter.acquire(context):
            pages += 1
            records = list(adapter.normalize(context, page))
            rows_read += len(records)
            rows_staged += stage_owner_portfolio_records(
                connection,
                organization_id,
                job.id,
                records,
                row_number_start=rows_staged,
            )
            connection.commit()

        # The ingest DB role intentionally has no direct CRM/domain DML rights.
        # Flyway owns this narrow SECURITY DEFINER function and preserves the
        # existing Java reconciliation semantics without broadening privileges.
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT forestiq_ingest_apply_owner_portfolio(%s, %s)",
                (job.id, organization_id),
            )
            applied = cursor.fetchone()[0] or {}
        connection.commit()

        added = int(applied.get("relationsAdded", 0))
        closed = int(applied.get("relationsClosed", 0))
        return ExecutionResult(
            state="SUCCEEDED",
            metrics={
                "provider": "metsis",
                "pages": pages,
                "rows": rows_read,
                "owners": int(applied.get("owners", 0)),
                "relationsAdded": added,
                "relationsClosed": closed,
                "scope": "TENANT",
            },
            message="Owner-cadastre snapshot synchronized by Python ingestion.",
            rows_read=rows_read,
            rows_staged=rows_staged,
            rows_written=added + closed,
        )
    except Exception as exc:
        connection.rollback()
        _cleanup_staging(connection, job.id)
        _persist_failure_checkpoint(connection, organization_id, _safe_error(exc))
        connection.commit()
        raise
    finally:
        adapter.http_client.client.close()


def _cleanup_staging(connection: psycopg.Connection, run_id: UUID) -> None:
    with connection.cursor() as cursor:
        cursor.execute("DELETE FROM owner_parcel_snapshot_row WHERE run_id=%s", (run_id,))
        cursor.execute("DELETE FROM owner_snapshot_row WHERE run_id=%s", (run_id,))
        cursor.execute("DELETE FROM owner_portfolio_import_stage WHERE run_id=%s", (run_id,))


def _persist_failure_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    error: str,
) -> None:
    now = datetime.now(UTC)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO metsis_portfolio_sync_checkpoint (
                organization_id, last_failure_at, last_error, updated_at
            )
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (organization_id) DO UPDATE SET
                last_failure_at=EXCLUDED.last_failure_at,
                last_error=EXCLUDED.last_error,
                updated_at=EXCLUDED.updated_at
            """,
            (organization_id, now, error, now),
        )


def _env_int(name: str, default: int, *, minimum: int, maximum: int) -> int:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    return max(minimum, min(value, maximum))


def _safe_error(exc: BaseException) -> str:
    return " ".join((str(exc) or type(exc).__name__).split())[:500]
