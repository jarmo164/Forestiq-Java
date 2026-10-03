from __future__ import annotations

import json

import psycopg

from forestiq_ingest.config import IngestSettings


def record_runtime_heartbeat(settings: IngestSettings) -> bool:
    if not settings.database_url:
        return False

    details = {
        "workerId": settings.worker_id,
        "enabledJobKeys": list(settings.enabled_job_keys),
        "pollIntervalSeconds": settings.poll_interval_seconds,
        "heartbeatSeconds": settings.heartbeat_seconds,
    }
    try:
        with psycopg.connect(settings.database_url, connect_timeout=5) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO ingestion_runtime_heartbeat (
                        component,
                        instance_id,
                        started_at,
                        last_seen_at,
                        details
                    )
                    VALUES (%s, %s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %s::jsonb)
                    ON CONFLICT (component, instance_id)
                    DO UPDATE SET
                        last_seen_at = CURRENT_TIMESTAMP,
                        details = EXCLUDED.details
                    """,
                    (
                        settings.component,
                        settings.worker_id,
                        json.dumps(details, separators=(",", ":")),
                    ),
                )
        return True
    except psycopg.Error:
        return False
