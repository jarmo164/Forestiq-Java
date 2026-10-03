from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from typing import Any, Iterable
from uuid import UUID

from psycopg import Connection
from psycopg.rows import dict_row


ACTIVE_STATES = ("RUNNING", "VALIDATING", "PUBLISHING")
TERMINAL_STATES = ("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED")


@dataclass(frozen=True)
class ClaimedJob:
    id: UUID
    organization_id: UUID
    job_key: str
    trigger_type: str
    parameters: dict[str, Any]
    contract_version: int
    attempts: int


def claim_next_job(
    connection: Connection,
    *,
    worker_id: str,
    lease_seconds: int,
    enabled_job_keys: Iterable[str] = (),
) -> ClaimedJob | None:
    job_keys = tuple(enabled_job_keys)
    key_filter = "AND run.job_key = ANY(%(job_keys)s::text[])" if job_keys else ""
    query = f"""
        WITH next_run AS (
            SELECT run.id
              FROM integration_sync_run run
              JOIN integration_job_ownership owner
                ON owner.job_key = run.job_key
               AND owner.worker_type = 'python-ingest-worker'
             WHERE (
                    run.state = 'QUEUED'
                    OR (
                        run.state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')
                        AND run.lease_expires_at IS NOT NULL
                        AND run.lease_expires_at < CURRENT_TIMESTAMP
                    )
                )
               {key_filter}
             ORDER BY run.queued_at ASC, run.id ASC
             FOR UPDATE SKIP LOCKED
             LIMIT 1
        )
        UPDATE integration_sync_run run
           SET state = 'RUNNING',
               started_at = COALESCE(run.started_at, CURRENT_TIMESTAMP),
               heartbeat_at = CURRENT_TIMESTAMP,
               worker_id = %(worker_id)s,
               lease_expires_at = CURRENT_TIMESTAMP + (%(lease_seconds)s * INTERVAL '1 second'),
               attempts = run.attempts + 1
          FROM next_run
         WHERE run.id = next_run.id
         RETURNING run.id,
                   run.organization_id,
                   run.job_key,
                   run.trigger_type,
                   run.parameters,
                   run.contract_version,
                   run.attempts
    """
    params: dict[str, Any] = {"worker_id": worker_id, "lease_seconds": lease_seconds}
    if job_keys:
        params["job_keys"] = list(job_keys)

    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute(query, params)
        row = cursor.fetchone()
    if row is None:
        return None

    raw_parameters = row["parameters"]
    if isinstance(raw_parameters, str):
        parameters = json.loads(raw_parameters)
    else:
        parameters = raw_parameters or {}

    return ClaimedJob(
        id=row["id"],
        organization_id=row["organization_id"],
        job_key=row["job_key"],
        trigger_type=row["trigger_type"],
        parameters=parameters,
        contract_version=row["contract_version"],
        attempts=row["attempts"],
    )


def heartbeat_job(connection: Connection, *, run_id: UUID, worker_id: str, lease_seconds: int) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE integration_sync_run
               SET heartbeat_at = CURRENT_TIMESTAMP,
                   lease_expires_at = CURRENT_TIMESTAMP + (%s * INTERVAL '1 second')
             WHERE id = %s
               AND worker_id = %s
               AND state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')
               AND finished_at IS NULL
            """,
            (lease_seconds, run_id, worker_id),
        )
        return cursor.rowcount == 1


def update_job_state(connection: Connection, *, run_id: UUID, worker_id: str, state: str) -> bool:
    if state not in ACTIVE_STATES:
        raise ValueError(f"state must be one of {ACTIVE_STATES}")

    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE integration_sync_run
               SET state = %s,
                   heartbeat_at = CURRENT_TIMESTAMP
             WHERE id = %s
               AND worker_id = %s
               AND state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')
               AND finished_at IS NULL
            """,
            (state, run_id, worker_id),
        )
        return cursor.rowcount == 1


def complete_job(
    connection: Connection,
    *,
    run_id: UUID,
    worker_id: str,
    state: str,
    metrics: dict[str, Any] | None = None,
    message: str | None = None,
    error_summary: str | None = None,
    rows_read: int = 0,
    rows_staged: int = 0,
    rows_written: int = 0,
    rows_rejected: int = 0,
    bytes_read: int = 0,
    bytes_staged: int = 0,
    bytes_written: int = 0,
    source_version: str | None = None,
    source_date: date | None = None,
    source_checksum: str | None = None,
    published_generation_id: UUID | None = None,
) -> bool:
    if state not in TERMINAL_STATES:
        raise ValueError(f"state must be one of {TERMINAL_STATES}")

    bounded_error = _truncate(error_summary, 500)
    bounded_message = _truncate(message, 500)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE integration_sync_run
               SET state = %s,
                   metrics = %s::jsonb,
                   message = %s,
                   error_summary = %s,
                   rows_read = %s,
                   rows_staged = %s,
                   rows_written = %s,
                   rows_rejected = %s,
                   bytes_read = %s,
                   bytes_staged = %s,
                   bytes_written = %s,
                   source_version = %s,
                   source_date = %s,
                   source_checksum = %s,
                   published_generation_id = %s,
                   finished_at = CURRENT_TIMESTAMP,
                   heartbeat_at = CURRENT_TIMESTAMP,
                   lease_expires_at = NULL
             WHERE id = %s
               AND worker_id = %s
               AND state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')
               AND finished_at IS NULL
            """,
            (
                state,
                json.dumps(metrics or {}, separators=(",", ":")),
                bounded_message,
                bounded_error,
                rows_read,
                rows_staged,
                rows_written,
                rows_rejected,
                bytes_read,
                bytes_staged,
                bytes_written,
                source_version,
                source_date,
                source_checksum,
                published_generation_id,
                run_id,
                worker_id,
            ),
        )
        return cursor.rowcount == 1


def _truncate(value: str | None, limit: int) -> str | None:
    if value is None or not value.strip():
        return None
    safe = " ".join(value.split())
    return safe[:limit]
