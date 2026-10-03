from __future__ import annotations

import os
from datetime import UTC, datetime, timedelta
from uuid import UUID

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.http.client import HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.watermark import IncrementalCheckpoint
from forestiq_ingest.sources.weasel_ownership import WeaselOwnershipAdapter, WeaselOwnershipConfig
from forestiq_ingest.worker.execution import ExecutionResult


SYSTEM_SUBJECT = "system-weasel-sync"


def execute_weasel(
    connection: psycopg.Connection,
    job: ClaimedJob,
    _settings: IngestSettings,
) -> ExecutionResult:
    base_url = (os.getenv("WEASEL_API_BASE_URL") or "").strip()
    access_token = (os.getenv("WEASEL_API_ACCESS_TOKEN") or "").strip()
    configured_org = (os.getenv("WEASEL_ORGANIZATION_ID") or "").strip()
    if not base_url or not access_token or not configured_org:
        raise RuntimeError(
            "WEASEL_API_BASE_URL, WEASEL_API_ACCESS_TOKEN and WEASEL_ORGANIZATION_ID are required"
        )
    try:
        organization_id = UUID(configured_org)
    except ValueError as exc:
        raise RuntimeError("WEASEL_ORGANIZATION_ID must be a UUID") from exc
    if organization_id != job.organization_id:
        raise RuntimeError("Weasel provider token is not bound to the queued organization")

    config = WeaselOwnershipConfig(
        base_url=base_url,
        organization_id=organization_id,
        page_size=_env_int("WEASEL_PAGE_SIZE", 100, 1, 250),
        max_pages=_env_int("WEASEL_MAX_PAGES_PER_RUN", 10, 1, 1000),
        overlap_hours=_env_int("WEASEL_OVERLAP_HOURS", 6, 1, 168),
        initial_lookback_days=_env_int("WEASEL_INITIAL_LOOKBACK_DAYS", 14, 1, 365),
    )
    adapter = WeaselOwnershipAdapter(config, HttpSourceClient(base_url=base_url), access_token)
    context = PipelineContext(job.id, job.job_key, adapter.spec.source_key)

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT cursor_time, window_end, next_page
              FROM weasel_transition_sync_checkpoint
             WHERE organization_id=%s
            """,
            (organization_id,),
        )
        checkpoint_row = cursor.fetchone()
    connection.rollback()

    cursor_time = checkpoint_row[0] if checkpoint_row else None
    resume_window_end = checkpoint_row[1] if checkpoint_row else None
    resume_page = checkpoint_row[2] if checkpoint_row else None
    end = resume_window_end if resume_window_end is not None and resume_page is not None else datetime.now(UTC)
    if end.tzinfo is None:
        end = end.replace(tzinfo=UTC)
    start = (
        cursor_time - timedelta(hours=config.overlap_hours)
        if cursor_time is not None
        else end - timedelta(days=config.initial_lookback_days)
    )
    start_page = max(1, int(resume_page)) if resume_window_end is not None and resume_page is not None else 1

    pages = 0
    rows_read = 0
    matched = 0
    imported = 0
    last_page_no = start_page - 1
    last_has_more = False
    try:
        for page in adapter.fetch_pages(
            context,
            from_time=start,
            to_time=end,
            start_page=start_page,
        ):
            pages += 1
            last_page_no = page.page_no
            last_has_more = page.has_more
            records = list(adapter.normalize(context, page.rows))
            rows_read += len(records)
            page_matched = 0
            page_imported = 0
            for record in records:
                was_matched, was_imported = _import_transition(
                    connection,
                    organization_id,
                    record.payload,
                )
                if was_matched:
                    page_matched += 1
                if was_imported:
                    page_imported += 1
            connection.commit()
            matched += page_matched
            imported += page_imported

        if last_has_more:
            next_page = last_page_no + 1
            message = (
                "Weasel run reached the bounded page limit; the same extraction "
                f"window will resume from page {next_page}."
            )
            _persist_partial_checkpoint(connection, organization_id, end, next_page, message)
            connection.commit()
            return ExecutionResult(
                state="PARTIAL",
                metrics={
                    "provider": "weasel",
                    "pages": pages,
                    "matched": matched,
                    "imported": imported,
                    "nextPage": next_page,
                    "backlogRemaining": True,
                },
                message=message,
                rows_read=rows_read,
                rows_written=imported,
            )

        _persist_success_checkpoint(connection, organization_id, end)
        connection.commit()
        return ExecutionResult(
            state="SUCCEEDED",
            metrics={
                "provider": "weasel",
                "pages": pages,
                "matched": matched,
                "imported": imported,
                "backlogRemaining": False,
            },
            message="Weasel ownership transitions synchronized by Python ingestion.",
            rows_read=rows_read,
            rows_written=imported,
        )
    except Exception as exc:
        connection.rollback()
        _persist_failure_checkpoint(connection, organization_id, _safe_error(exc))
        connection.commit()
        raise
    finally:
        adapter.http_client.client.close()


def _import_transition(
    connection: psycopg.Connection,
    organization_id: UUID,
    transition: dict,
) -> tuple[bool, bool]:
    external_id = int(transition["external_id"])
    cadastral_code = str(transition["cadastral_code"])
    occurred_at = _parse_instant(str(transition["transition_at"]))
    old_owners = [str(value) for value in transition.get("old_owner_identifiers") or []]
    new_owners = [str(value) for value in transition.get("new_owner_identifiers") or []]

    # Domain/audit tables remain protected from direct forestiq_ingest DML.
    # The migration-owned SECURITY DEFINER function exposes exactly one
    # normalized ownership-transition apply operation.
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT forestiq_ingest_apply_weasel_transition(
                %s, %s, %s, %s, %s::text[], %s::text[]
            )
            """,
            (
                organization_id,
                external_id,
                cadastral_code,
                occurred_at,
                old_owners,
                new_owners,
            ),
        )
        result = cursor.fetchone()[0] or {}

    return int(result.get("matched", 0)) > 0, int(result.get("imported", 0)) > 0

def _persist_success_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    cursor_time: datetime,
) -> None:
    now = datetime.now(UTC)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO weasel_transition_sync_checkpoint (
                organization_id, cursor_time, window_end, next_page,
                last_success_at, last_failure_at, last_error, updated_at
            )
            VALUES (%s, %s, NULL, NULL, %s, NULL, NULL, %s)
            ON CONFLICT (organization_id) DO UPDATE SET
                cursor_time=EXCLUDED.cursor_time,
                window_end=NULL,
                next_page=NULL,
                last_success_at=EXCLUDED.last_success_at,
                last_failure_at=NULL,
                last_error=NULL,
                updated_at=EXCLUDED.updated_at
            """,
            (organization_id, cursor_time, now, now),
        )


def _persist_partial_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    window_end: datetime,
    next_page: int,
    message: str,
) -> None:
    fallback = window_end - timedelta(days=14)
    now = datetime.now(UTC)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO weasel_transition_sync_checkpoint (
                organization_id, cursor_time, window_end, next_page, last_error, updated_at
            )
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (organization_id) DO UPDATE SET
                window_end=EXCLUDED.window_end,
                next_page=EXCLUDED.next_page,
                last_error=EXCLUDED.last_error,
                updated_at=EXCLUDED.updated_at
            """,
            (organization_id, fallback, window_end, next_page, _safe_text(message), now),
        )


def _persist_failure_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    error: str,
) -> None:
    fallback = datetime.now(UTC) - timedelta(days=14)
    now = datetime.now(UTC)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO weasel_transition_sync_checkpoint (
                organization_id, cursor_time, last_failure_at, last_error, updated_at
            )
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (organization_id) DO UPDATE SET
                last_failure_at=EXCLUDED.last_failure_at,
                last_error=EXCLUDED.last_error,
                updated_at=EXCLUDED.updated_at
            """,
            (organization_id, fallback, now, _safe_text(error), now),
        )


def _parse_instant(value: str) -> datetime:
    normalized = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError("Weasel createdTime is not a valid timestamp") from exc
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    return max(minimum, min(value, maximum))


def _safe_text(value: str) -> str:
    return " ".join(value.split())[:500]


def _safe_error(exc: BaseException) -> str:
    return _safe_text(str(exc) or type(exc).__name__)
