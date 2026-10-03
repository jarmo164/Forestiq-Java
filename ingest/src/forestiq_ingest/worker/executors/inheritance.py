from __future__ import annotations

import os
from datetime import date, datetime, timedelta
from urllib.parse import urlsplit
from uuid import UUID
from zoneinfo import ZoneInfo

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.http.client import HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.inheritance_notices import InheritanceNoticeAdapter, InheritanceNoticeConfig
from forestiq_ingest.worker.execution import ExecutionResult


def execute_inheritance_notices(
    connection: psycopg.Connection,
    job: ClaimedJob,
    _settings: IngestSettings,
) -> ExecutionResult:
    uri_template = (os.getenv("INHERITANCE_SOURCE_URI_TEMPLATE") or "").strip()
    if not uri_template:
        raise RuntimeError("INHERITANCE_SOURCE_URI_TEMPLATE is required")
    api_token = (os.getenv("INHERITANCE_SOURCE_API_TOKEN") or "").strip() or None
    base_url = (os.getenv("INHERITANCE_SOURCE_BASE_URL") or "").strip() or _origin(uri_template)

    max_days = _env_int("INHERITANCE_MAX_BACKFILL_DAYS", 31, 1, 366)
    zone_id = (os.getenv("INHERITANCE_SOURCE_ZONE_ID") or "Europe/Tallinn").strip()
    today = datetime.now(ZoneInfo(zone_id)).date()
    adapter = InheritanceNoticeAdapter(
        InheritanceNoticeConfig(
            uri_template=uri_template,
            lookback_days=max_days,
            overlap_days=1,
            max_days_per_run=max_days,
        ),
        HttpSourceClient(base_url=base_url),
        api_token,
    )
    context = PipelineContext(job.id, job.job_key, adapter.spec.source_key)

    days, previous_cursor, last_past_day, catchup_remaining = _day_plan(
        connection,
        job,
        today,
        max_days,
    )

    checked_days = 0
    parsed = 0
    matched = 0
    imported = 0
    skipped = 0
    failed = 0
    rows_read = 0

    try:
        for chunk in adapter.acquire(context, days):
            checked_days += 1
            records = list(adapter.normalize(context, chunk))
            parsed += len(records)
            rows_read += len(records)
            feed_date = date.fromisoformat(str(chunk[0]["announcement_date"])) if chunk else today

            _archive_notices(connection, feed_date, records)
            connection.commit()

            for record in records:
                payload = record.payload
                personal_code = str(payload.get("personal_code") or "")
                if len(personal_code) != 11 or not personal_code.isdigit():
                    skipped += 1
                    continue

                try:
                    with connection.cursor() as cursor:
                        cursor.execute(
                            """
                            SELECT forestiq_ingest_apply_inheritance_notice(
                                %s, %s, %s, %s, %s, %s, %s, %s, %s
                            )
                            """,
                            (
                                job.organization_id,
                                payload["notice_number"],
                                payload.get("source_url"),
                                _as_date(payload.get("announcement_date")),
                                _as_date(payload.get("death_date")),
                                _as_date(payload.get("certification_deadline")),
                                payload.get("notary_name"),
                                payload.get("notary_phone"),
                                personal_code,
                            ),
                        )
                        outcome = cursor.fetchone()[0] or {}
                    connection.commit()
                except Exception:
                    connection.rollback()
                    failed += 1
                    continue

                if not bool(outcome.get("matched", False)):
                    skipped += 1
                    continue
                matched += 1
                if bool(outcome.get("imported", False)):
                    imported += 1
                else:
                    skipped += 1

        metrics = {
            "checkedDays": checked_days,
            "parsed": parsed,
            "matched": matched,
            "imported": imported,
            "skipped": skipped,
            "failed": failed,
        }
        if job.trigger_type == "SCHEDULED":
            completed = last_past_day if failed == 0 and last_past_day is not None else previous_cursor
            if completed is not None:
                metrics["lastCompletedDate"] = completed.isoformat()
            metrics["catchupRemaining"] = catchup_remaining

        state = "PARTIAL" if failed > 0 else "SUCCEEDED"
        message = (
            "Some inheritance notices failed and the same period will be retried."
            if failed > 0
            else "Inheritance notices checked and matched by Python ingestion."
        )
        return ExecutionResult(
            state=state,
            metrics=metrics,
            message=message,
            rows_read=rows_read,
            rows_written=imported,
            rows_rejected=failed,
        )
    finally:
        adapter.http_client.client.close()


def _day_plan(
    connection: psycopg.Connection,
    job: ClaimedJob,
    today: date,
    max_days: int,
) -> tuple[list[date], date | None, date | None, bool]:
    parameters = job.parameters or {}
    if "from" in parameters:
        start = date.fromisoformat(str(parameters["from"]))
        end = date.fromisoformat(str(parameters.get("to") or parameters["from"]))
        count = (end - start).days + 1
        if count < 1 or count > max_days or end > today:
            raise ValueError("Inheritance notice date range is not allowed")
        return ([start + timedelta(days=offset) for offset in range(count)], None, None, False)

    cursor = _scheduled_cursor(connection, job.organization_id, job.id)
    if cursor is None:
        cursor = today - timedelta(days=2)
    first_missing = cursor + timedelta(days=1)
    yesterday = today - timedelta(days=1)
    past_capacity = max(1, max_days - 1)
    planned: list[date] = []
    last_past_day: date | None = None

    if first_missing <= yesterday:
        missing = (yesterday - first_missing).days + 1
        take = min(missing, past_capacity)
        planned.extend(first_missing + timedelta(days=offset) for offset in range(take))
        last_past_day = first_missing + timedelta(days=take - 1)

    for overlap_day in (yesterday, today):
        if overlap_day not in planned:
            planned.append(overlap_day)

    remaining = last_past_day is not None and last_past_day < yesterday
    return planned, cursor, last_past_day, remaining


def _scheduled_cursor(
    connection: psycopg.Connection,
    organization_id: UUID,
    current_run_id: UUID,
) -> date | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT metrics->>'lastCompletedDate'
              FROM integration_sync_run
             WHERE organization_id=%s
               AND job_key='INHERITANCE_NOTICES'
               AND trigger_type='SCHEDULED'
               AND state IN ('SUCCEEDED','PARTIAL')
               AND id<>%s
               AND metrics->>'lastCompletedDate' IS NOT NULL
             ORDER BY finished_at DESC NULLS LAST, queued_at DESC
             LIMIT 1
            """,
            (organization_id, current_run_id),
        )
        row = cursor.fetchone()
    connection.rollback()
    if not row or not row[0]:
        return None
    try:
        return date.fromisoformat(str(row[0]))
    except ValueError:
        return None


def _archive_notices(
    connection: psycopg.Connection,
    feed_date: date,
    records: list,
) -> None:
    with connection.cursor() as cursor:
        for record in records:
            payload = record.payload
            cursor.execute(
                """
                INSERT INTO external_inheritance_notice (
                    notice_number, feed_date, announcement_date, source_url, personal_code,
                    death_date, certification_deadline, notary_name, notary_phone
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (notice_number) DO UPDATE SET
                    feed_date=EXCLUDED.feed_date,
                    announcement_date=EXCLUDED.announcement_date,
                    source_url=EXCLUDED.source_url,
                    personal_code=EXCLUDED.personal_code,
                    death_date=EXCLUDED.death_date,
                    certification_deadline=EXCLUDED.certification_deadline,
                    notary_name=EXCLUDED.notary_name,
                    notary_phone=EXCLUDED.notary_phone,
                    last_seen_at=CURRENT_TIMESTAMP
                """,
                (
                    payload["notice_number"],
                    feed_date,
                    _as_date(payload.get("announcement_date")),
                    payload.get("source_url"),
                    payload.get("personal_code"),
                    _as_date(payload.get("death_date")),
                    _as_date(payload.get("certification_deadline")),
                    payload.get("notary_name"),
                    payload.get("notary_phone"),
                ),
            )


def _as_date(value: object) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value))


def _origin(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme and parsed.netloc:
        return f"{parsed.scheme}://{parsed.netloc}"
    return "https://www.ametlikudteadaanded.ee"


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    return max(minimum, min(value, maximum))
