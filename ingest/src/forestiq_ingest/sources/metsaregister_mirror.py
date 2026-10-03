from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import psycopg

from forestiq_ingest.database.staging import CopyStageSpec, copy_stage_rows
from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.metsaregister_wfs import MetsaregisterWfsAdapter


@dataclass(frozen=True)
class MetsaregisterLayerRun:
    layer_name: str
    generation_id: UUID
    rows_staged: int
    pages_processed: int
    complete: bool
    rows_this_run: int
    pages_this_run: int


@dataclass(frozen=True)
class _Generation:
    id: UUID
    next_start_index: int
    pages_processed: int
    rows_staged: int
    provider_number_matched: int | None


class MetsaregisterWfsMirrorPipeline:
    """Generation-safe, resumable Metsaregister WFS mirror ingestion.

    Every provider page commits independently together with its durable
    checkpoint. The current layer pointer changes only after the final page and
    exact provider count validation have succeeded. A failed refresh therefore
    never replaces the last-known-good generation.
    """

    def __init__(self, adapter: MetsaregisterWfsAdapter):
        self.adapter = adapter

    def run_layer(
        self,
        connection: psycopg.Connection,
        context: PipelineContext,
        *,
        start_index: int | None = None,
    ) -> MetsaregisterLayerRun:
        layer_name = self.adapter.config.type_name
        generation = self._load_or_create_generation(connection, layer_name, start_index=start_index)
        self._mark_syncing(connection, layer_name)
        connection.commit()

        pages_this_run = 0
        rows_this_run = 0
        last_page_complete = False

        try:
            for page in self.adapter.acquire_pages(context, start_index=generation.next_start_index):
                payload, features, number_returned, number_matched = _decode_geojson_page(page.body)
                if number_returned != len(features):
                    raise ValueError(
                        f"Metsaregister WFS numberReturned mismatch for {layer_name}: "
                        f"reported={number_returned}, actual={len(features)}"
                    )
                provider_matched = _merge_provider_count(
                    generation.provider_number_matched,
                    number_matched,
                )
                if provider_matched is not None and number_matched is not None and provider_matched != number_matched:
                    raise ValueError(
                        f"Metsaregister WFS numberMatched changed during generation for {layer_name}"
                    )

                self._stage_features(
                    connection,
                    generation.id,
                    layer_name,
                    features,
                    identity_descriptor=self.adapter.config.identity_property,
                )
                pages_this_run += 1
                rows_this_run += number_returned
                total_pages = generation.pages_processed + pages_this_run
                total_rows = generation.rows_staged + rows_this_run
                next_start_index = page.start_index + number_returned

                complete = (
                    number_returned == 0
                    or number_returned < self.adapter.config.page_size
                    or (provider_matched is not None and next_start_index >= provider_matched)
                )

                last_page_complete = complete

                self._checkpoint_generation(
                    connection,
                    generation.id,
                    next_start_index,
                    total_pages,
                    total_rows,
                    provider_matched,
                )
                connection.commit()

                if complete:
                    if provider_matched is not None and total_rows != provider_matched:
                        raise ValueError(
                            f"Metsaregister WFS completed with {total_rows} rows but provider "
                            f"reported {provider_matched} for {layer_name}"
                        )
                    self._publish_generation(
                        connection,
                        layer_name,
                        generation.id,
                        total_rows,
                        provider_matched,
                    )
                    connection.commit()
                    return MetsaregisterLayerRun(
                        layer_name,
                        generation.id,
                        total_rows,
                        total_pages,
                        True,
                        rows_this_run,
                        pages_this_run,
                    )

            total_pages = generation.pages_processed + pages_this_run
            total_rows = generation.rows_staged + rows_this_run

            # The adapter intentionally uses a bounded page window. Hitting that
            # window without an end-of-data signal is a recoverable PARTIAL run:
            # keep the immutable STAGING generation and resume from the durable
            # next_start_index on the next worker claim.
            if pages_this_run >= self.adapter.config.max_pages and not last_page_complete:
                return MetsaregisterLayerRun(
                    layer_name,
                    generation.id,
                    total_rows,
                    total_pages,
                    False,
                    rows_this_run,
                    pages_this_run,
                )

            # Otherwise the iterator stopped because the next page was explicitly
            # empty; publishing is safe even when the provider omitted
            # numberMatched.
            self._publish_generation(
                connection,
                layer_name,
                generation.id,
                total_rows,
                generation.provider_number_matched,
            )
            connection.commit()
            return MetsaregisterLayerRun(
                layer_name,
                generation.id,
                total_rows,
                total_pages,
                True,
                rows_this_run,
                pages_this_run,
            )
        except Exception as exc:
            connection.rollback()
            self._mark_failed(connection, layer_name, generation.id, exc)
            connection.commit()
            raise

    def _load_or_create_generation(
        self,
        connection: psycopg.Connection,
        layer_name: str,
        *,
        start_index: int | None,
    ) -> _Generation:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, next_start_index, pages_processed, rows_staged, provider_number_matched
                  FROM metsaregister_wfs_generation
                 WHERE layer_name=%s AND state='STAGING'
                 ORDER BY started_at ASC
                 LIMIT 1
                """,
                (layer_name,),
            )
            row = cursor.fetchone()
            if row:
                return _Generation(
                    id=row[0],
                    next_start_index=int(row[1] or 0),
                    pages_processed=int(row[2] or 0),
                    rows_staged=int(row[3] or 0),
                    provider_number_matched=int(row[4]) if row[4] is not None else None,
                )
            cursor.execute(
                """
                INSERT INTO metsaregister_wfs_generation (layer_name, next_start_index)
                VALUES (%s, %s)
                RETURNING id
                """,
                (layer_name, max(0, int(start_index or 0))),
            )
            generation_id = cursor.fetchone()[0]
        connection.commit()
        return _Generation(generation_id, max(0, int(start_index or 0)), 0, 0, None)

    def _stage_features(
        self,
        connection: psycopg.Connection,
        generation_id: UUID,
        layer_name: str,
        features: list[dict[str, Any]],
        *,
        identity_descriptor: str | None,
    ) -> None:
        if not features:
            return

        rows = []
        for index, feature in enumerate(features):
            if not isinstance(feature, dict):
                raise ValueError("Metsaregister WFS feature must be an object")
            properties = feature.get("properties") or {}
            if not isinstance(properties, dict):
                raise ValueError("Metsaregister WFS feature properties must be an object")
            geometry = feature.get("geometry")
            feature_key = _feature_key(feature, properties, identity_descriptor, index)
            if len(feature_key) > 320:
                raise ValueError(f"Metsaregister WFS feature identity exceeds 320 chars in {layer_name}")
            source_hash = _feature_hash(feature)
            rows.append(
                (
                    generation_id,
                    layer_name,
                    feature_key,
                    feature_key,
                    source_hash,
                    json.dumps(properties, separators=(",", ":")),
                    json.dumps(geometry, separators=(",", ":")) if geometry else None,
                )
            )

        copy_stage_rows(
            connection,
            CopyStageSpec(
                create_temp_table_sql="""
                CREATE TEMP TABLE IF NOT EXISTS metsaregister_wfs_feature_load (
                    generation_id UUID NOT NULL,
                    layer_name VARCHAR(160) NOT NULL,
                    feature_key VARCHAR(320) NOT NULL,
                    source_id VARCHAR(320),
                    source_hash CHAR(64) NOT NULL,
                    properties JSONB NOT NULL,
                    geometry_json TEXT
                ) ON COMMIT DROP
                """,
                truncate_sql="TRUNCATE metsaregister_wfs_feature_load",
                copy_sql="""
                COPY metsaregister_wfs_feature_load
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry_json)
                FROM STDIN
                """,
                load_sql="""
                INSERT INTO metsaregister_wfs_feature
                    (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
                SELECT generation_id,
                       layer_name,
                       feature_key,
                       source_id,
                       source_hash,
                       properties,
                       CASE
                           WHEN geometry_json IS NULL THEN NULL
                           ELSE ST_Transform(
                               ST_SetSRID(ST_Force2D(ST_GeomFromGeoJSON(geometry_json)), 4326),
                               3301
                           )
                       END
                  FROM metsaregister_wfs_feature_load
                ON CONFLICT (generation_id, feature_key) DO UPDATE SET
                    source_id = EXCLUDED.source_id,
                    source_hash = EXCLUDED.source_hash,
                    properties = EXCLUDED.properties,
                    geometry = EXCLUDED.geometry
                """,
            ),
            rows,
        )

    def _checkpoint_generation(
        self,
        connection: psycopg.Connection,
        generation_id: UUID,
        next_start_index: int,
        pages_processed: int,
        rows_staged: int,
        provider_number_matched: int | None,
    ) -> None:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE metsaregister_wfs_generation
                   SET next_start_index=%s,
                       pages_processed=%s,
                       rows_staged=%s,
                       provider_number_matched=%s,
                       last_failure_at=NULL,
                       last_error=NULL,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=%s AND state='STAGING'
                """,
                (
                    next_start_index,
                    pages_processed,
                    rows_staged,
                    provider_number_matched,
                    generation_id,
                ),
            )
            if cursor.rowcount != 1:
                raise RuntimeError("Metsaregister staging generation is no longer writable")

    def _publish_generation(
        self,
        connection: psycopg.Connection,
        layer_name: str,
        generation_id: UUID,
        rows_staged: int,
        provider_number_matched: int | None,
    ) -> None:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT current_generation_id FROM metsaregister_wfs_layer WHERE layer_name=%s FOR UPDATE",
                (layer_name,),
            )
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError(f"Unknown Metsaregister WFS layer: {layer_name}")
            previous = row[0]

            cursor.execute(
                """
                UPDATE metsaregister_wfs_generation
                   SET state='PUBLISHED',
                       rows_staged=%s,
                       provider_number_matched=%s,
                       completed_at=CURRENT_TIMESTAMP,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=%s AND state='STAGING'
                """,
                (rows_staged, provider_number_matched, generation_id),
            )
            if cursor.rowcount != 1:
                raise RuntimeError("Metsaregister generation could not be published")

            cursor.execute(
                """
                UPDATE metsaregister_wfs_layer
                   SET current_generation_id=%s,
                       current_row_count=%s,
                       provider_number_matched=%s,
                       state='HEALTHY',
                       last_success_at=CURRENT_TIMESTAMP,
                       last_failure_at=NULL,
                       last_error=NULL,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE layer_name=%s
                """,
                (generation_id, rows_staged, provider_number_matched, layer_name),
            )
            if previous is not None and previous != generation_id:
                cursor.execute(
                    """
                    UPDATE metsaregister_wfs_generation
                       SET state='RETIRED', updated_at=CURRENT_TIMESTAMP
                     WHERE id=%s AND state='PUBLISHED'
                    """,
                    (previous,),
                )

    def _mark_syncing(self, connection: psycopg.Connection, layer_name: str) -> None:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE metsaregister_wfs_layer
                   SET state='SYNCING', updated_at=CURRENT_TIMESTAMP
                 WHERE layer_name=%s
                   AND approved=TRUE
                   AND enabled=TRUE
                   AND provider_present=TRUE
                   AND consumption_mode='LOCAL_DOMAIN_DATA'
                   AND state NOT IN ('PENDING_SCHEMA','UNSUPPORTED','SCHEMA_DRIFT')
                """,
                (layer_name,),
            )
            if cursor.rowcount != 1:
                raise RuntimeError(f"Metsaregister layer is not ingestible: {layer_name}")

    def _mark_failed(
        self,
        connection: psycopg.Connection,
        layer_name: str,
        generation_id: UUID,
        exc: Exception,
    ) -> None:
        message = " ".join((str(exc) or type(exc).__name__).split())[:1000]
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE metsaregister_wfs_generation
                   SET last_failure_at=CURRENT_TIMESTAMP,
                       last_error=%s,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=%s AND state='STAGING'
                """,
                (message, generation_id),
            )
            cursor.execute(
                """
                UPDATE metsaregister_wfs_layer
                   SET state='FAILED',
                       last_failure_at=CURRENT_TIMESTAMP,
                       last_error=%s,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE layer_name=%s
                """,
                (message, layer_name),
            )


def _decode_geojson_page(body: bytes) -> tuple[dict[str, Any], list[dict[str, Any]], int, int | None]:
    payload = json.loads(body.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Metsaregister WFS response root is not an object")
    features = payload.get("features")
    if not isinstance(features, list):
        raise ValueError("Metsaregister WFS response is not a GeoJSON FeatureCollection")

    number_returned = _numeric(payload.get("numberReturned"), len(features))
    number_matched = _numeric(payload.get("numberMatched"), None)
    return payload, features, number_returned, number_matched


def _numeric(value: object, fallback: int | None) -> int | None:
    if value is None:
        return fallback
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


def _merge_provider_count(existing: int | None, observed: int | None) -> int | None:
    if existing is not None:
        return existing
    return observed


def _feature_key(
    feature: dict[str, Any],
    properties: dict[str, Any],
    identity_descriptor: str | None,
    fallback_index: int,
) -> str:
    if identity_descriptor:
        fields = [field.strip() for field in identity_descriptor.split(",") if field.strip()]
        if len(fields) == 1:
            value = _identity_value(feature, properties, fields[0])
            if value is None:
                raise ValueError(f"Metsaregister WFS feature is missing identity property {fields[0]}")
            return value

        parts: list[str] = []
        present = 0
        for field in fields:
            value = _identity_value(feature, properties, field)
            if value is None:
                value = "<NULL>"
            else:
                present += 1
            parts.append(f"{len(field)}:{field}={len(value)}:{value};")
        if present == 0:
            raise ValueError("Metsaregister WFS composite identity has no present values")
        return "".join(parts)

    value = feature.get("id") or properties.get("id") or properties.get("sys_id") or properties.get("objectid")
    if value is None:
        raise ValueError(f"Metsaregister WFS feature has no deterministic identity at page row {fallback_index}")
    return str(value)


def _identity_value(feature: dict[str, Any], properties: dict[str, Any], field: str) -> str | None:
    raw = feature.get("id") if field == "__feature_id" else properties.get(field)
    if raw is None or isinstance(raw, (dict, list)):
        return None
    value = str(raw)
    return value if value.strip() else None


def _feature_hash(feature: dict[str, Any]) -> str:
    encoded = json.dumps(feature, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
