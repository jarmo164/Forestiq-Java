from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Mapping
from uuid import UUID

from psycopg import Connection


SCHEDULER_LOCK_NAME = "forestiq:ingest:scheduler"
SCHEDULER_SUBJECT = "python-ingest-scheduler"


@dataclass(frozen=True)
class ScheduleSpec:
    job_key: str
    interval_seconds: int
    organization_scope: str
    organization_env: str | None = None


@dataclass(frozen=True)
class SchedulerCycle:
    leader_acquired: bool
    owned_jobs: int
    enqueued: int
    not_due: int
    unavailable_scope: int


# External-source schedules only. METSAREGISTER_FORESTRY_CANONICAL intentionally
# stays out of this list: it is a ForestIQ-internal derived projection, not a
# provider/source acquisition job.
DEFAULT_SCHEDULES: tuple[ScheduleSpec, ...] = (
    ScheduleSpec("CADASTRE_PUBLIC_DATA", 86_400, "coordinator"),
    ScheduleSpec("FOREST_NOTIFICATIONS", 21_600, "all"),
    ScheduleSpec("FOREST_PLAN", 21_600, "all"),
    ScheduleSpec("METSAREGISTER_WFS_MIRROR", 21_600, "coordinator"),
    ScheduleSpec("OWNER_CADASTRE_RELATIONS", 86_400, "configured", "METSIS_PORTFOLIO_ORGANIZATION_ID"),
    ScheduleSpec("WEASEL_OWNERSHIP_TRANSITIONS", 21_600, "configured", "WEASEL_ORGANIZATION_ID"),
    ScheduleSpec("INHERITANCE_NOTICES", 1_800, "all"),
)


def enqueue_due_jobs(
    connection: Connection,
    *,
    enabled_job_keys: tuple[str, ...] = (),
    max_catchup_windows: int = 2,
    now: datetime | None = None,
    environ: Mapping[str, str] | None = None,
) -> SchedulerCycle:
    """Enqueue due Python-owned source jobs without contacting providers.

    Ownership is deliberately stricter than the worker claim rule. A source is
    scheduled here only after both worker_type and trigger_owner have been
    switched to Python. That makes the source-by-source cutover fail closed and
    prevents Java and Python schedulers from actively owning the same job key.
    """

    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    env = environ or os.environ
    max_windows = max(1, min(int(max_catchup_windows), 100))

    if not _try_leader_lock(connection):
        return SchedulerCycle(False, 0, 0, 0, 0)

    owned = _python_scheduler_owned_keys(connection, enabled_job_keys)
    specs = {spec.job_key: _with_interval_override(spec, env) for spec in DEFAULT_SCHEDULES}

    enqueued = 0
    not_due = 0
    unavailable_scope = 0

    for job_key in sorted(owned):
        spec = specs.get(job_key)
        if spec is None:
            # Unknown/new source keys fail closed until a versioned schedule is
            # added. The worker may still execute manual Admin-triggered runs.
            unavailable_scope += 1
            continue

        organizations = _organizations_for_spec(connection, spec, env)
        if not organizations:
            unavailable_scope += 1
            continue

        for organization_id in organizations:
            last_queued_at = _latest_scheduled_at(connection, organization_id, job_key)
            missed_windows = _missed_windows(last_queued_at, now, spec.interval_seconds)
            if missed_windows == 0:
                not_due += 1
                continue

            bounded_windows = min(missed_windows, max_windows)
            parameters = {
                "scheduler": {
                    "version": 1,
                    "scheduledAt": now.isoformat(),
                    "intervalSeconds": spec.interval_seconds,
                    "missedWindows": bounded_windows,
                    "catchupTruncated": missed_windows > max_windows,
                }
            }
            if _enqueue(connection, organization_id, job_key, parameters):
                enqueued += 1

    return SchedulerCycle(True, len(owned), enqueued, not_due, unavailable_scope)


def _try_leader_lock(connection: Connection) -> bool:
    with connection.cursor() as cursor:
        cursor.execute("SELECT pg_try_advisory_xact_lock(hashtext(%s))", (SCHEDULER_LOCK_NAME,))
        row = cursor.fetchone()
    return bool(row and row[0])


def _python_scheduler_owned_keys(connection: Connection, enabled_job_keys: tuple[str, ...]) -> set[str]:
    params: list[object] = []
    enabled_filter = ""
    if enabled_job_keys:
        enabled_filter = " AND job_key = ANY(%s::text[])"
        params.append(list(enabled_job_keys))

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT job_key
              FROM integration_job_ownership
             WHERE worker_type = 'python-ingest-worker'
               AND trigger_owner = 'python-ingest-scheduler'
            """ + enabled_filter,
            tuple(params) if params else None,
        )
        return {row[0] for row in cursor.fetchall()}


def _organizations_for_spec(
    connection: Connection,
    spec: ScheduleSpec,
    environ: Mapping[str, str],
) -> list[UUID]:
    if spec.organization_scope == "configured":
        raw = (environ.get(spec.organization_env or "") or "").strip()
        if raw:
            try:
                return [UUID(raw)]
            except ValueError:
                return []
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM organization ORDER BY id LIMIT 2")
            rows = cursor.fetchall()
        return [rows[0][0]] if len(rows) == 1 else []

    limit = " LIMIT 1" if spec.organization_scope == "coordinator" else ""
    with connection.cursor() as cursor:
        cursor.execute("SELECT id FROM organization ORDER BY id" + limit)
        return [row[0] for row in cursor.fetchall()]


def _latest_scheduled_at(connection: Connection, organization_id: UUID, job_key: str) -> datetime | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT queued_at
              FROM integration_sync_run
             WHERE organization_id = %s
               AND job_key = %s
               AND trigger_type = 'SCHEDULED'
             ORDER BY queued_at DESC
             LIMIT 1
            """,
            (organization_id, job_key),
        )
        row = cursor.fetchone()
    return row[0] if row else None


def _missed_windows(last_queued_at: datetime | None, now: datetime, interval_seconds: int) -> int:
    if last_queued_at is None:
        return 1
    if last_queued_at.tzinfo is None:
        last_queued_at = last_queued_at.replace(tzinfo=timezone.utc)
    elapsed = max(0.0, (now - last_queued_at).total_seconds())
    if elapsed < interval_seconds:
        return 0
    return max(1, int(elapsed // interval_seconds))


def _enqueue(connection: Connection, organization_id: UUID, job_key: str, parameters: dict) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO integration_sync_run (
                id,
                organization_id,
                job_key,
                trigger_type,
                requested_by_subject,
                state,
                parameters,
                contract_version
            )
            VALUES (
                gen_random_uuid(),
                %s,
                %s,
                'SCHEDULED',
                %s,
                'QUEUED',
                %s::jsonb,
                1
            )
            ON CONFLICT DO NOTHING
            """,
            (
                organization_id,
                job_key,
                SCHEDULER_SUBJECT,
                json.dumps(parameters, separators=(",", ":")),
            ),
        )
        return cursor.rowcount == 1


def _with_interval_override(spec: ScheduleSpec, environ: Mapping[str, str]) -> ScheduleSpec:
    key = f"FORESTIQ_INGEST_SCHEDULE_{spec.job_key}_SECONDS"
    raw = (environ.get(key) or "").strip()
    if not raw:
        return spec
    try:
        value = int(raw)
    except ValueError:
        return spec
    if value < 60:
        return spec
    return ScheduleSpec(spec.job_key, value, spec.organization_scope, spec.organization_env)
