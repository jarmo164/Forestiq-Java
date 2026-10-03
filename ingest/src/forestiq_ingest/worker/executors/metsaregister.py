from __future__ import annotations

import os
from dataclasses import asdict
from typing import Any

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.http.client import HttpClientConfig, HttpSourceClient
from forestiq_ingest.http.retry import RetryPolicy
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.metsaregister_mirror import MetsaregisterWfsMirrorPipeline
from forestiq_ingest.sources.metsaregister_wfs import MetsaregisterWfsAdapter, MetsaregisterWfsConfig
from forestiq_ingest.worker.execution import ExecutionResult


def execute_metsaregister_mirror(
    connection: psycopg.Connection,
    job: ClaimedJob,
    _settings: IngestSettings,
) -> ExecutionResult:
    base_url = (
        os.getenv("METSAREGISTER_WFS_URL")
        or os.getenv("METSAREGISTER_WFS_BASE_URL")
        or ""
    ).strip()
    if not base_url:
        raise RuntimeError("METSAREGISTER_WFS_URL is required for Python Metsaregister ingestion")

    requested_layer = _text(job.parameters.get("layer"))
    full_refresh = (
        _text(job.parameters.get("mode")).lower() == "full"
        or _bool(job.parameters.get("force"))
    )
    default_max_layers = 50 if full_refresh else 3
    max_layers = _bounded_int(job.parameters.get("maxLayers"), default_max_layers, 1, 50)
    page_size = _env_int("METSAREGISTER_WFS_PAGE_SIZE", 10_000, 1, 1_000_000)
    pages_per_run = _env_int("METSAREGISTER_WFS_BULK_PAGES_PER_RUN", 10, 1, 100)
    timeout = float(_env_int("METSAREGISTER_WFS_TIMEOUT_SECONDS", 15, 1, 300))
    max_attempts = _env_int("METSAREGISTER_WFS_MAX_ATTEMPTS", 3, 1, 5)

    layers = _select_layers(
        connection,
        requested_layer=requested_layer or None,
        full_refresh=full_refresh,
        max_layers=max_layers,
    )
    connection.rollback()

    outcomes: list[dict[str, Any]] = []
    total_rows = 0
    total_pages = 0
    failures = 0
    incomplete = 0

    for layer in layers:
        client = HttpSourceClient(
            base_url=base_url,
            config=HttpClientConfig(
                connect_timeout_seconds=timeout,
                read_timeout_seconds=timeout,
                write_timeout_seconds=timeout,
                pool_timeout_seconds=min(timeout, 30),
                max_response_bytes=_env_int(
                    "METSAREGISTER_WFS_MAX_RESPONSE_BYTES",
                    100_000_000,
                    1_000_000,
                    500_000_000,
                ),
            ),
            retry_policy=RetryPolicy(max_attempts=max_attempts),
        )
        adapter = MetsaregisterWfsAdapter(
            MetsaregisterWfsConfig(
                base_url=base_url,
                type_name=layer["layer_name"],
                page_size=page_size,
                max_pages=pages_per_run,
                identity_property=layer["identity_property"],
                sort_property=layer["sort_property"],
            ),
            client,
        )
        pipeline = MetsaregisterWfsMirrorPipeline(adapter)
        context = PipelineContext(job.id, job.job_key, adapter.spec.source_key)
        try:
            result = pipeline.run_layer(connection, context)
            total_rows += result.rows_this_run
            total_pages += result.pages_this_run
            if not result.complete:
                incomplete += 1
            outcomes.append(
                {
                    "layer": result.layer_name,
                    "generationId": str(result.generation_id),
                    "rowsThisRun": result.rows_this_run,
                    "rowsStagedTotal": result.rows_staged,
                    "pagesThisRun": result.pages_this_run,
                    "pagesProcessedTotal": result.pages_processed,
                    "complete": result.complete,
                }
            )
        except Exception as exc:
            failures += 1
            outcomes.append(
                {
                    "layer": layer["layer_name"],
                    "complete": False,
                    "error": _safe_error(exc),
                }
            )
        finally:
            client.client.close()

    remaining = _remaining_layers(connection)
    connection.rollback()
    backlog = failures > 0 or incomplete > 0 or remaining > 0

    metrics = {
        "scope": "PROVIDER_GLOBAL",
        "layersAttempted": len(layers),
        "layerFailures": failures,
        "layersIncomplete": incomplete,
        "pages": total_pages,
        "rows": total_rows,
        "remainingLayers": remaining,
        "backlogRemaining": backlog,
        "perLayer": outcomes,
    }

    if failures > 0:
        return ExecutionResult(
            state="PARTIAL",
            metrics=metrics,
            message=(
                f"Metsaregister mirror progressed; {failures} layer(s) failed and "
                "last-known-good generations remain active."
            ),
            rows_read=total_rows,
            rows_staged=total_rows,
            rows_written=total_rows,
            rows_rejected=failures,
        )
    if backlog:
        return ExecutionResult(
            state="PARTIAL",
            metrics=metrics,
            message="Metsaregister mirror checkpointed safely; remaining layers/pages will continue next run.",
            rows_read=total_rows,
            rows_staged=total_rows,
            rows_written=total_rows,
        )
    return ExecutionResult(
        state="SUCCEEDED",
        metrics=metrics,
        message="All selected Metsaregister LOCAL_DOMAIN_DATA layers are current in the Python mirror.",
        rows_read=total_rows,
        rows_staged=total_rows,
        rows_written=total_rows,
    )


def _select_layers(
    connection: psycopg.Connection,
    *,
    requested_layer: str | None,
    full_refresh: bool,
    max_layers: int,
) -> list[dict[str, Any]]:
    where_requested = ""
    params: list[Any] = []
    if requested_layer:
        where_requested = " AND l.layer_name=%s"
        params.append(requested_layer)

    due_filter = ""
    if not requested_layer and not full_refresh:
        due_filter = """
          AND (
                EXISTS (
                    SELECT 1
                      FROM metsaregister_wfs_generation g
                     WHERE g.layer_name=l.layer_name AND g.state='STAGING'
                )
                OR l.last_success_at IS NULL
                OR l.last_success_at
                    + (l.refresh_interval_minutes * INTERVAL '1 minute') <= CURRENT_TIMESTAMP
          )
        """

    params.append(max_layers)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT l.layer_name,
                   l.identity_property,
                   l.sort_property,
                   l.refresh_priority,
                   l.last_success_at
              FROM metsaregister_wfs_layer l
             WHERE l.approved=TRUE
               AND l.enabled=TRUE
               AND l.provider_present=TRUE
               AND l.consumption_mode='LOCAL_DOMAIN_DATA'
               AND l.identity_property IS NOT NULL
               AND l.sort_property IS NOT NULL
               AND l.state NOT IN ('PENDING_SCHEMA','UNSUPPORTED','SCHEMA_DRIFT')
               {where_requested}
               {due_filter}
             ORDER BY
                   CASE WHEN EXISTS (
                       SELECT 1
                         FROM metsaregister_wfs_generation g
                        WHERE g.layer_name=l.layer_name AND g.state='STAGING'
                   ) THEN 0 ELSE 1 END,
                   l.refresh_priority ASC,
                   l.last_success_at ASC NULLS FIRST,
                   l.layer_name ASC
             LIMIT %s
            """,
            tuple(params),
        )
        rows = cursor.fetchall()

    if requested_layer and not rows:
        raise RuntimeError(
            f"Metsaregister layer is not an approved ingestible LOCAL_DOMAIN_DATA layer: {requested_layer}"
        )

    return [
        {
            "layer_name": row[0],
            "identity_property": row[1],
            "sort_property": row[2],
            "refresh_priority": row[3],
            "last_success_at": row[4],
        }
        for row in rows
    ]


def _remaining_layers(connection: psycopg.Connection) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT count(*)::int
              FROM metsaregister_wfs_layer l
             WHERE l.approved=TRUE
               AND l.enabled=TRUE
               AND l.provider_present=TRUE
               AND l.consumption_mode='LOCAL_DOMAIN_DATA'
               AND (
                    EXISTS (
                        SELECT 1 FROM metsaregister_wfs_generation g
                         WHERE g.layer_name=l.layer_name AND g.state='STAGING'
                    )
                    OR l.last_success_at IS NULL
                    OR l.last_success_at
                        + (l.refresh_interval_minutes * INTERVAL '1 minute') <= CURRENT_TIMESTAMP
               )
            """
        )
        return int(cursor.fetchone()[0] or 0)


def _text(value: object) -> str:
    return "" if value is None else str(value).strip()


def _bool(value: object) -> bool:
    if isinstance(value, bool):
        return value
    return _text(value).lower() in {"1", "true", "yes", "on"}


def _bounded_int(value: object, default: int, minimum: int, maximum: int) -> int:
    if value is None or _text(value) == "":
        return default
    try:
        parsed = int(str(value))
    except ValueError as exc:
        raise ValueError("integer parameter expected") from exc
    return max(minimum, min(parsed, maximum))


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = (os.getenv(name) or "").strip()
    return _bounded_int(raw or None, default, minimum, maximum)


def _safe_error(exc: BaseException) -> str:
    return " ".join((str(exc) or type(exc).__name__).split())[:500]
