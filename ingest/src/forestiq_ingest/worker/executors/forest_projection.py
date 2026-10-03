from __future__ import annotations

import hashlib
import json
import os
import time
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any
from uuid import UUID, uuid4

import psycopg

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.http.client import HttpClientConfig, HttpRequestContext, HttpSourceClient
from forestiq_ingest.http.retry import RetryPolicy
from forestiq_ingest.worker.execution import ExecutionResult


JOB_CONFIG = {
    "FOREST_NOTIFICATIONS": ("METSAREGISTER_TEATISED", "metsaregister:teatis"),
    "FOREST_PLAN": ("METSAREGISTER_ERALDISED", "metsaregister:eraldis"),
}


def execute_forest_projection(
    connection: psycopg.Connection,
    job: ClaimedJob,
    _settings: IngestSettings,
) -> ExecutionResult:
    if job.job_key not in JOB_CONFIG:
        raise RuntimeError(f"unsupported forest projection job {job.job_key}")
    registry, type_name = JOB_CONFIG[job.job_key]

    base_url = (
        os.getenv("METSAREGISTER_WFS_URL")
        or os.getenv("METSAREGISTER_WFS_BASE_URL")
        or ""
    ).strip()
    if not base_url:
        raise RuntimeError("METSAREGISTER_WFS_URL is required")

    checkpoint = _load_or_create_checkpoint(connection, job.organization_id, registry)
    if checkpoint is None:
        return ExecutionResult(
            state="SUCCEEDED",
            metrics={"cadastres": 0, "pages": 0, "records": 0, "attempted": False},
            message=f"No stale {registry} parcel projections require refresh.",
        )
    connection.commit()

    client = HttpSourceClient(
        base_url=base_url,
        config=HttpClientConfig(
            connect_timeout_seconds=_env_float("METSAREGISTER_WFS_TIMEOUT_SECONDS", 15.0, 1.0, 300.0),
            read_timeout_seconds=_env_float("METSAREGISTER_WFS_TIMEOUT_SECONDS", 15.0, 1.0, 300.0),
            max_response_bytes=_env_int(
                "METSAREGISTER_WFS_MAX_RESPONSE_BYTES",
                100_000_000,
                1_000_000,
                500_000_000,
            ),
        ),
        retry_policy=RetryPolicy(
            max_attempts=_env_int("METSAREGISTER_WFS_MAX_ATTEMPTS", 3, 1, 5)
        ),
    )

    page_size = _env_int("METSAREGISTER_WFS_PAGE_SIZE", 10_000, 1, 1_000_000)
    max_pages = _env_int("METSAREGISTER_WFS_BULK_PAGES_PER_RUN", 10, 1, 100)
    min_interval = _env_float("METSAREGISTER_WFS_MIN_REQUEST_INTERVAL_MS", 100.0, 0.0, 60_000.0) / 1000.0
    sort_field = (os.getenv("METSAREGISTER_WFS_SORT_FIELD") or "sys_id").strip()
    _validate_sort_field(sort_field)

    pages = 0
    rows = 0
    complete = False
    try:
        for _ in range(max_pages):
            body = client.get_bytes(
                "",
                params={
                    "service": "WFS",
                    "version": "2.0.0",
                    "request": "GetFeature",
                    "typeNames": type_name,
                    "outputFormat": "application/json",
                    "srsName": "EPSG:4326",
                    "CQL_FILTER": _cql_filter(checkpoint["cadastral_codes"]),
                    "sortBy": ",".join(f"{field.strip()} A" for field in sort_field.split(",")),
                    "count": page_size,
                    "startIndex": checkpoint["next_start_index"],
                },
                context=HttpRequestContext(
                    run_id=str(job.id),
                    job_key=job.job_key,
                    source=registry,
                    batch_no=checkpoint["pages_processed"] + 1,
                    source_cursor=str(checkpoint["next_start_index"]),
                ),
            )
            payload = json.loads(body.decode("utf-8"))
            features = payload.get("features")
            if not isinstance(payload, dict) or not isinstance(features, list):
                raise RuntimeError("Metsaregister projection response is not a GeoJSON FeatureCollection")

            actual = len(features)
            reported = _numeric(payload.get("numberReturned"), actual)
            if reported != actual:
                raise RuntimeError("Metsaregister numberReturned does not match feature count")
            number_matched = _numeric(payload.get("numberMatched"), -1)

            _stage_page(
                connection,
                checkpoint,
                features,
            )
            checkpoint["pages_processed"] += 1
            checkpoint["next_start_index"] += actual
            checkpoint["records_staged"] = _staged_count(
                connection,
                checkpoint["batch_id"],
            )
            _checkpoint(connection, checkpoint)
            connection.commit()

            pages += 1
            rows += actual
            complete = (
                actual == 0
                or actual < page_size
                or (number_matched >= 0 and checkpoint["next_start_index"] >= number_matched)
            )
            if complete:
                break
            if min_interval > 0:
                time.sleep(min_interval)

        if not complete:
            return ExecutionResult(
                state="PARTIAL",
                metrics={
                    "cadastres": len(checkpoint["cadastral_codes"]),
                    "pages": pages,
                    "records": rows,
                    "attempted": True,
                    "backlogRemaining": True,
                    "batchId": str(checkpoint["batch_id"]),
                    "nextStartIndex": checkpoint["next_start_index"],
                },
                message=f"{registry} batch checkpointed and will resume from the next WFS page.",
                rows_read=rows,
                rows_staged=rows,
            )

        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT forestiq_ingest_apply_registry_bulk_batch(%s, %s, %s)",
                (job.organization_id, registry, checkpoint["batch_id"]),
            )
            applied = cursor.fetchone()[0] or {}
        connection.commit()

        return ExecutionResult(
            state="SUCCEEDED",
            metrics={
                "cadastres": int(applied.get("cadastres", len(checkpoint["cadastral_codes"]))),
                "pages": pages,
                "records": int(applied.get("records", rows)),
                "attempted": True,
                "backlogRemaining": False,
                "batchId": str(checkpoint["batch_id"]),
            },
            message=f"{registry} tenant projections refreshed by Python ingestion.",
            rows_read=rows,
            rows_staged=rows,
            rows_written=int(applied.get("records", rows)),
        )
    except Exception as exc:
        connection.rollback()
        _mark_failure(connection, checkpoint, exc)
        connection.commit()
        raise
    finally:
        client.client.close()


def _load_or_create_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    registry: str,
) -> dict[str, Any] | None:
    existing = _load_checkpoint(connection, organization_id, registry)
    if existing is not None:
        return existing

    max_age_hours = _env_int("METSAREGISTER_FOREST_MAX_AGE_HOURS", 24, 1, 24 * 30)
    batch_size = _env_int("METSAREGISTER_WFS_BULK_BATCH_SIZE", 50, 1, 100)
    cutoff = datetime.now(UTC) - timedelta(hours=max_age_hours)

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT p.cadastral_code
              FROM land_parcel p
             WHERE p.organization_id=%s
               AND NOT EXISTS (
                    SELECT 1
                      FROM registry_sync_log l
                     WHERE l.organization_id=p.organization_id
                       AND l.cadastral_code=p.cadastral_code
                       AND l.registry_name=%s
                       AND l.status='SUCCESS'
                       AND l.created_at >= %s
               )
             ORDER BY (
                    SELECT max(a.created_at)
                      FROM registry_sync_log a
                     WHERE a.organization_id=p.organization_id
                       AND a.cadastral_code=p.cadastral_code
                       AND a.registry_name=%s
             ) ASC NULLS FIRST,
             p.updated_at ASC NULLS FIRST,
             p.cadastral_code ASC
             LIMIT %s
            """,
            (organization_id, registry, cutoff, registry, batch_size),
        )
        codes = [row[0] for row in cursor.fetchall()]

    if not codes:
        connection.rollback()
        return None

    batch_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO registry_bulk_sync_checkpoint (
                organization_id, registry_name, batch_id, cadastral_codes,
                next_start_index, pages_processed, records_staged
            )
            VALUES (%s, %s, %s, %s::jsonb, 0, 0, 0)
            ON CONFLICT (organization_id, registry_name) DO NOTHING
            """,
            (
                organization_id,
                registry,
                batch_id,
                json.dumps(codes, separators=(",", ":")),
            ),
        )
    connection.commit()
    return _load_checkpoint(connection, organization_id, registry)


def _load_checkpoint(
    connection: psycopg.Connection,
    organization_id: UUID,
    registry: str,
) -> dict[str, Any] | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT batch_id, cadastral_codes, next_start_index,
                   pages_processed, records_staged
              FROM registry_bulk_sync_checkpoint
             WHERE organization_id=%s AND registry_name=%s
            """,
            (organization_id, registry),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    codes = row[1] if isinstance(row[1], list) else json.loads(row[1])
    return {
        "organization_id": organization_id,
        "registry": registry,
        "batch_id": row[0],
        "cadastral_codes": [str(value) for value in codes],
        "next_start_index": int(row[2] or 0),
        "pages_processed": int(row[3] or 0),
        "records_staged": int(row[4] or 0),
    }


def _stage_page(
    connection: psycopg.Connection,
    checkpoint: dict[str, Any],
    features: list[dict[str, Any]],
) -> None:
    allowed_codes = set(checkpoint["cadastral_codes"])
    with connection.cursor() as cursor:
        for feature in features:
            if not isinstance(feature, dict):
                raise RuntimeError("Metsaregister WFS feature must be an object")
            props = feature.get("properties")
            if not isinstance(props, dict):
                continue
            cadastral_code = _text(props.get("katastri_nr"))
            if not cadastral_code or cadastral_code not in allowed_codes:
                raise RuntimeError("Metsaregister WFS returned a feature outside the requested cadastral batch")

            source_id = _first_text_or_number(props, "sys_id", "id")
            title = _first_text(props, "teatise_nr", "eraldise_nr", "kinnistu_nimetus", "kinnistu_nr")
            source_hash = _canonical_hash(props)
            feature_key = source_id or source_hash
            cursor.execute(
                """
                INSERT INTO registry_bulk_sync_feature (
                    batch_id, organization_id, registry_name, cadastral_code,
                    feature_key, source_id, title, work_code, decision,
                    area, volume, source_hash
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (batch_id, cadastral_code, feature_key) DO UPDATE SET
                    source_id=EXCLUDED.source_id,
                    title=EXCLUDED.title,
                    work_code=EXCLUDED.work_code,
                    decision=EXCLUDED.decision,
                    area=EXCLUDED.area,
                    volume=EXCLUDED.volume,
                    source_hash=EXCLUDED.source_hash
                """,
                (
                    checkpoint["batch_id"],
                    checkpoint["organization_id"],
                    checkpoint["registry"],
                    cadastral_code,
                    feature_key,
                    source_id,
                    title,
                    _text(props.get("too_kood")),
                    _text(props.get("otsus")),
                    _decimal(props.get("pindala")),
                    _decimal(props.get("raiutav_maht") if props.get("raiutav_maht") is not None else props.get("tagavara_y_ha")),
                    source_hash,
                ),
            )


def _checkpoint(connection: psycopg.Connection, checkpoint: dict[str, Any]) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE registry_bulk_sync_checkpoint
               SET next_start_index=%s,
                   pages_processed=%s,
                   records_staged=%s,
                   last_failure_at=NULL,
                   last_error=NULL,
                   updated_at=CURRENT_TIMESTAMP
             WHERE organization_id=%s
               AND registry_name=%s
               AND batch_id=%s
            """,
            (
                checkpoint["next_start_index"],
                checkpoint["pages_processed"],
                checkpoint["records_staged"],
                checkpoint["organization_id"],
                checkpoint["registry"],
                checkpoint["batch_id"],
            ),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Registry bulk checkpoint disappeared during WFS paging")


def _staged_count(connection: psycopg.Connection, batch_id: UUID) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT count(*)::bigint FROM registry_bulk_sync_feature WHERE batch_id=%s",
            (batch_id,),
        )
        return int(cursor.fetchone()[0] or 0)


def _mark_failure(
    connection: psycopg.Connection,
    checkpoint: dict[str, Any],
    exc: BaseException,
) -> None:
    message = " ".join((str(exc) or type(exc).__name__).split())[:500]
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE registry_bulk_sync_checkpoint
               SET last_failure_at=CURRENT_TIMESTAMP,
                   last_error=%s,
                   updated_at=CURRENT_TIMESTAMP
             WHERE organization_id=%s
               AND registry_name=%s
               AND batch_id=%s
            """,
            (
                message,
                checkpoint["organization_id"],
                checkpoint["registry"],
                checkpoint["batch_id"],
            ),
        )


def _cql_filter(codes: list[str]) -> str:
    if not codes:
        raise RuntimeError("Registry bulk WFS batch cannot be empty")
    return "katastri_nr IN (" + ",".join("'" + code.replace("'", "''") + "'" for code in codes) + ")"


def _validate_sort_field(value: str) -> None:
    for field in value.split(","):
        field = field.strip()
        if not field or not field.replace("_", "a").isalnum() or field[0].isdigit():
            raise RuntimeError("Metsaregister WFS sort field is invalid")


def _canonical_hash(value: dict[str, Any]) -> str:
    encoded = json.dumps(
        value,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _text(value: object) -> str | None:
    if value is None:
        return None
    text = str(value)
    return text if text != "" else None


def _first_text(value: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        result = _text(value.get(key))
        if result:
            return result
    return None


def _first_text_or_number(value: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        raw = value.get(key)
        if isinstance(raw, (str, int)) and str(raw) != "":
            return str(raw)
    return None


def _decimal(value: object) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _numeric(value: object, fallback: int) -> int:
    if isinstance(value, bool):
        return fallback
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return fallback
    return fallback


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    return max(minimum, min(value, maximum))


def _env_float(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be numeric") from exc
    return max(minimum, min(value, maximum))
