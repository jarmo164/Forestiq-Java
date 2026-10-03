#!/usr/bin/env python3
"""Synchronize one EELIS WFS 2.0 layer into ForestIQ's generation-safe PostGIS store.

The script reads the public EELIS WFS endpoint directly, stages one complete layer
as a PostGIS generation, validates it, and atomically publishes it only when the
provider's count is stable. It never exposes a partial import through
geospatial_source_current.

Examples:
  # Inspect a source layer only; no database connection is made.
  python3 scripts/sync_eelis_wfs_to_postgis.py --layer eelis:natura_elupaik --dry-run

  # Import one ForestIQ thematic source.
  FORESTIQ_DATABASE_URL='postgresql://forestiq:...@localhost:5432/forestiq' \
    python3 scripts/sync_eelis_wfs_to_postgis.py \
      --layer eelis:natura_elupaik \
      --source-key EELIS_NATURA_ELUPAIK \
      --page-size 10000 \
      --best-effort-snapshot

  # Continue a temporary provider/database failure from its committed page boundary.
  python3 scripts/sync_eelis_wfs_to_postgis.py \
    --layer eelis:natura_elupaik \
    --best-effort-snapshot

Requires Flyway migration V60 or later and `psycopg[binary]` from
scripts/requirements-eelis-wfs.txt.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import sys
import time
import uuid
import xml.etree.ElementTree as element_tree
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from urllib import error, parse, request

try:
    import psycopg
    from psycopg.types.json import Jsonb
except ImportError as exc:  # pragma: no cover - exercised only in an unconfigured runtime.
    raise SystemExit(
        "Missing database dependency. Install with: "
        "python3 -m pip install -r scripts/requirements-eelis-wfs.txt"
    ) from exc


DEFAULT_ENDPOINT = "https://gsavalik.envir.ee/geoserver/eelis/wfs"
CANONICAL_SRID = 3301
MAX_PAGE_SIZE = 100_000
MAX_ERROR_MESSAGE = 1_500
SAFE_LAYER = re.compile(r"^eelis:[A-Za-z][A-Za-z0-9_]{0,150}$")
SAFE_FIELD = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,119}$")
SAFE_SOURCE_KEY = re.compile(r"^[A-Z][A-Z0-9_]{1,127}$")
XSD_NAMESPACE = "{http://www.w3.org/2001/XMLSchema}"
LOGGER = logging.getLogger(__name__)


class SyncError(RuntimeError):
    """A deterministic import failure that should mark the staging generation failed."""


class ProviderUnavailable(RuntimeError):
    """A temporary provider/transport failure that leaves staging resumable."""


@dataclass(frozen=True)
class LayerSchema:
    """Validated metadata required to ingest one WFS feature type."""

    layer_name: str
    fields: tuple[tuple[str, str, bool], ...]
    geometry_field: str
    fingerprint: str


@dataclass(frozen=True)
class ImportState:
    """Durable generation cursor loaded from ForestIQ PostGIS."""

    run_id: uuid.UUID
    generation_id: uuid.UUID
    next_start_index: int
    expected_records: int
    pages_processed: int
    rows_staged: int


@dataclass(frozen=True)
class FeatureRow:
    """One validated GeoJSON feature prepared for a parameterized PostGIS insert."""

    feature_id: str
    source_id: str | None
    properties_json: str
    geometry_json: str


class EelisWfsClient:
    """Small dependency-free WFS 2.0 client with bounded retries and error output."""

    def __init__(
        self,
        endpoint: str,
        timeout_seconds: int,
        max_attempts: int,
        min_request_interval_ms: int,
    ) -> None:
        self.endpoint = endpoint.rstrip("?")
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts
        self.min_request_interval_seconds = min_request_interval_ms / 1000
        self._last_request_at = 0.0

    def describe_feature_type(self, layer_name: str) -> LayerSchema:
        body = self._get_bytes(
            {
                "service": "WFS",
                "version": "2.0.0",
                "request": "DescribeFeatureType",
                "typeNames": layer_name,
            },
            accept="application/xml,text/xml;q=0.9,*/*;q=0.1",
        )
        return parse_schema(layer_name, body)

    def count_features(self, layer_name: str) -> int:
        body = self._get_bytes(
            {
                "service": "WFS",
                "version": "2.0.0",
                "request": "GetFeature",
                "typeNames": layer_name,
                "resultType": "hits",
            },
            accept="application/xml,text/xml;q=0.9,*/*;q=0.1",
        )
        try:
            root = element_tree.fromstring(body)
        except element_tree.ParseError as exc:
            raise SyncError("EELIS resultType=hits response was not valid XML") from exc
        matched = root.attrib.get("numberMatched")
        return parse_nonnegative_integer(matched, "numberMatched from EELIS hits response")

    def get_page(
        self,
        layer_name: str,
        sort_field: str,
        start_index: int,
        page_size: int,
        source_srid: int,
    ) -> dict[str, Any]:
        body = self._get_bytes(
            {
                "service": "WFS",
                "version": "2.0.0",
                "request": "GetFeature",
                "typeNames": layer_name,
                "count": str(page_size),
                "startIndex": str(start_index),
                "sortBy": f"{sort_field} A",
                "srsName": f"EPSG:{source_srid}",
                # EELIS advertises application/geo+json but rejects it. application/json is verified.
                "outputFormat": "application/json",
            },
            accept="application/json,*/*;q=0.1",
        )
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as exc:
            raise SyncError("EELIS GetFeature response was not valid JSON") from exc
        if not isinstance(payload, dict):
            raise SyncError("EELIS GetFeature response root must be a JSON object")
        return payload

    def _get_bytes(self, parameters: dict[str, str], *, accept: str) -> bytes:
        self._respect_request_interval()
        separator = "&" if "?" in self.endpoint else "?"
        url = f"{self.endpoint}{separator}{parse.urlencode(parameters)}"
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                req = request.Request(
                    url,
                    headers={
                        "Accept": accept,
                        "User-Agent": "ForestIQ-EELIS-WFS-sync/1.0",
                    },
                    method="GET",
                )
                with request.urlopen(req, timeout=self.timeout_seconds) as response:
                    self._last_request_at = time.monotonic()
                    return response.read()
            except error.HTTPError as exc:
                self._last_request_at = time.monotonic()
                # Authentication, not-found and malformed-request failures are not transient.
                if 400 <= exc.code < 500 and exc.code != 429:
                    raise SyncError(f"EELIS request returned non-retryable HTTP {exc.code}") from exc
                last_error = exc
            except (error.URLError, TimeoutError, OSError) as exc:
                self._last_request_at = time.monotonic()
                last_error = exc

            if attempt < self.max_attempts:
                delay = min(30.0, 1.0 * (2 ** (attempt - 1)))
                LOGGER.warning("EELIS request failed on attempt %s/%s; retrying in %.1fs", attempt, self.max_attempts, delay)
                time.sleep(delay)

        detail = type(last_error).__name__ if last_error is not None else "unknown transport error"
        raise ProviderUnavailable(
            f"EELIS request failed after {self.max_attempts} attempt(s): {detail}"
        ) from last_error

    def _respect_request_interval(self) -> None:
        wait = self.min_request_interval_seconds - (time.monotonic() - self._last_request_at)
        if wait > 0:
            time.sleep(wait)


def parse_schema(layer_name: str, xml_body: bytes) -> LayerSchema:
    """Parse and fingerprint the XSD without relying on provider XML formatting."""
    try:
        root = element_tree.fromstring(xml_body)
    except element_tree.ParseError as exc:
        raise SyncError("DescribeFeatureType response was not valid XML") from exc

    fields: list[tuple[str, str, bool]] = []
    geometry_fields: list[str] = []
    for element in root.iter(f"{XSD_NAMESPACE}element"):
        name = element.attrib.get("name")
        field_type = element.attrib.get("type", "")
        if not name or name == layer_name.split(":", 1)[-1]:
            continue
        nullable = element.attrib.get("nillable", "false").lower() == "true"
        fields.append((name, field_type, nullable))
        if "GeometryPropertyType" in field_type:
            geometry_fields.append(name)

    if not fields:
        raise SyncError(f"EELIS schema for {layer_name} contained no feature fields")
    if len(geometry_fields) != 1:
        raise SyncError(
            f"EELIS schema for {layer_name} must contain exactly one geometry field; found {geometry_fields}"
        )

    canonical_fields = tuple(sorted(fields, key=lambda item: item[0]))
    canonical_document = json.dumps(
        {"layer": layer_name, "fields": canonical_fields, "geometry": geometry_fields[0]},
        separators=(",", ":"),
        sort_keys=True,
    )
    return LayerSchema(
        layer_name=layer_name,
        fields=canonical_fields,
        geometry_field=geometry_fields[0],
        fingerprint=hashlib.sha256(canonical_document.encode("utf-8")).hexdigest(),
    )


def parse_nonnegative_integer(value: Any, label: str) -> int:
    if isinstance(value, bool):
        raise SyncError(f"{label} must be a non-negative integer, not boolean")
    try:
        result = int(value)
    except (TypeError, ValueError) as exc:
        raise SyncError(f"{label} was missing or not an integer: {value!r}") from exc
    if result < 0 or str(result) != str(value).strip():
        raise SyncError(f"{label} must be a non-negative integer: {value!r}")
    return result


def derive_source_key(layer_name: str) -> str:
    suffix = layer_name.split(":", 1)[1].upper()
    source_key = "EELIS_" + re.sub(r"[^A-Z0-9]+", "_", suffix).strip("_")
    if not SAFE_SOURCE_KEY.fullmatch(source_key):
        raise SyncError(f"Cannot derive a safe ForestIQ source key from {layer_name!r}")
    return source_key


def bounded_message(error_value: BaseException | str) -> str:
    text = str(error_value).replace("\n", " ").replace("\r", " ").strip()
    if len(text) <= MAX_ERROR_MESSAGE:
        return text
    return text[: MAX_ERROR_MESSAGE - 16] + " …[truncated]"


def validate_arguments(args: argparse.Namespace) -> None:
    if not SAFE_LAYER.fullmatch(args.layer):
        raise SystemExit("--layer must be an EELIS layer name such as eelis:natura_elupaik")
    if not SAFE_FIELD.fullmatch(args.sort_field):
        raise SystemExit("--sort-field must be a simple source attribute name")
    if not SAFE_SOURCE_KEY.fullmatch(args.source_key):
        raise SystemExit("--source-key must use uppercase letters, numbers and underscores")
    if not 1 <= args.page_size <= MAX_PAGE_SIZE:
        raise SystemExit(f"--page-size must be between 1 and {MAX_PAGE_SIZE}")
    if args.max_pages < 1:
        raise SystemExit("--max-pages must be at least 1")
    if args.timeout_seconds < 1 or args.max_attempts < 1:
        raise SystemExit("--timeout-seconds and --max-attempts must be positive")
    if args.min_request_interval_ms < 0:
        raise SystemExit("--min-request-interval-ms cannot be negative")
    if args.source_srid < 1:
        raise SystemExit("--source-srid must be positive")
    if args.source_srid != CANONICAL_SRID:
        raise SystemExit(
            f"This ForestIQ importer currently accepts EELIS EPSG:{CANONICAL_SRID} output only; "
            f"received --source-srid={args.source_srid}"
        )
    if args.database_url and args.database_url.startswith("jdbc:"):
        raise SystemExit("--database-url must be a PostgreSQL URI, not a JDBC URL")
    if not args.dry_run and not args.best_effort_snapshot:
        raise SystemExit(
            "EELIS declares PagingIsTransactionSafe=FALSE. Pass --best-effort-snapshot "
            "to acknowledge that equal feature counts cannot prove an immutable provider snapshot."
        )
    if args.allow_empty and not args.empty_reason.strip():
        raise SystemExit("--allow-empty requires a non-empty --empty-reason for the audit trail")


def require_database_url(args: argparse.Namespace) -> str:
    value = args.database_url or os.environ.get("FORESTIQ_DATABASE_URL") or os.environ.get("DATABASE_URL")
    if not value:
        raise SyncError(
            "Database URL is required. Set FORESTIQ_DATABASE_URL or pass --database-url "
            "(example: postgresql://user:password@localhost:5432/forestiq)."
        )
    if value.startswith("jdbc:"):
        raise SyncError("Database URL must be a PostgreSQL URI, not a JDBC URL")
    return value


def assert_schema_is_compatible(schema: LayerSchema, sort_field: str) -> None:
    names = {name for name, _, _ in schema.fields}
    if sort_field not in names:
        raise SyncError(
            f"EELIS schema drift or incorrect configuration: sort field {sort_field!r} "
            f"does not exist in {schema.layer_name}"
        )


def rows_from_page(payload: dict[str, Any], sort_field: str) -> list[FeatureRow]:
    features = payload.get("features")
    if not isinstance(features, list):
        raise SyncError("EELIS GeoJSON response did not contain a features array")

    metadata_count = payload.get("numberReturned")
    if metadata_count is not None and parse_nonnegative_integer(metadata_count, "numberReturned") != len(features):
        raise SyncError("EELIS page numberReturned did not match its GeoJSON feature count")

    seen_ids: set[str] = set()
    result: list[FeatureRow] = []
    for index, feature in enumerate(features):
        if not isinstance(feature, dict):
            raise SyncError(f"EELIS feature {index} was not a JSON object")
        feature_id = feature.get("id")
        properties = feature.get("properties")
        geometry = feature.get("geometry")
        if not isinstance(feature_id, str) or not feature_id or len(feature_id) > 300:
            raise SyncError(f"EELIS feature {index} has an invalid feature id")
        if feature_id in seen_ids:
            raise SyncError(f"EELIS page contains duplicate feature id {feature_id!r}")
        seen_ids.add(feature_id)
        if not isinstance(properties, dict):
            raise SyncError(f"EELIS feature {feature_id!r} has no properties object")
        if not isinstance(geometry, dict) or not geometry.get("type") or "coordinates" not in geometry:
            raise SyncError(f"EELIS feature {feature_id!r} has no usable GeoJSON geometry")
        source_id = properties.get(sort_field)
        if source_id is None:
            raise SyncError(
                f"EELIS feature {feature_id!r} has no {sort_field!r} value required for stable paging"
            )
        source_id = str(source_id)
        if len(source_id) > 300:
            raise SyncError(f"EELIS feature {feature_id!r} has an oversized {sort_field} value")
        result.append(
            FeatureRow(
                feature_id=feature_id,
                source_id=source_id,
                properties_json=json.dumps(properties, ensure_ascii=False, separators=(",", ":")),
                geometry_json=json.dumps(geometry, ensure_ascii=False, separators=(",", ":")),
            )
        )
    return result


def validate_page_count(payload: dict[str, Any], expected_records: int) -> None:
    page_matched = payload.get("numberMatched")
    if page_matched is not None:
        matched = parse_nonnegative_integer(page_matched, "page numberMatched")
        if matched != expected_records:
            raise SyncError(
                f"EELIS source changed during download: expected numberMatched={expected_records}, got {matched}"
            )


def validate_geojson_crs(payload: dict[str, Any], expected_srid: int) -> None:
    """Fail closed if EELIS did not honor the requested explicit output CRS."""
    crs = payload.get("crs")
    if not isinstance(crs, dict):
        raise SyncError("EELIS GeoJSON response did not declare a CRS")
    properties = crs.get("properties")
    if not isinstance(properties, dict):
        raise SyncError("EELIS GeoJSON CRS had no properties object")
    name = properties.get("name")
    accepted = {f"EPSG:{expected_srid}", f"urn:ogc:def:crs:EPSG::{expected_srid}"}
    if name not in accepted:
        raise SyncError(
            f"EELIS GeoJSON CRS {name!r} did not match requested EPSG:{expected_srid}"
        )


def assert_empty_source_is_allowed(provider_count: int, args: argparse.Namespace) -> None:
    if provider_count == 0 and not args.allow_empty:
        raise SyncError(
            "EELIS returned zero features. Refusing to replace a potentially populated generation; "
            "investigate the provider and explicitly pass --allow-empty --empty-reason '<reason>' "
            "only when an empty layer is expected."
        )


def register_source(
    conn: psycopg.Connection[Any],
    *,
    source_key: str,
    layer_name: str,
    display_name: str,
) -> None:
    with conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT provider, dataset_key, source_kind, canonical_srid
              FROM geospatial_source
             WHERE source_key = %s
             FOR UPDATE
            """,
            (source_key,),
        )
        existing = cursor.fetchone()
        if existing is not None:
            expected = ("EELIS", layer_name, "WFS", CANONICAL_SRID)
            actual = (existing[0], existing[1], existing[2], existing[3])
            if actual != expected:
                raise SyncError(
                    f"Existing source key {source_key} is bound to provider/dataset/kind/CRS={actual}; "
                    f"refusing to rebind it to {expected}. Use a new --source-key or a reviewed migration."
                )
            return
        cursor.execute(
            """
            INSERT INTO geospatial_source (
              source_key, provider, dataset_key, display_name, source_kind, config_key,
              refresh_interval_seconds, freshness_target_seconds,
              expected_source_srid, canonical_srid, enabled
            ) VALUES (
              %(source_key)s, 'EELIS', %(layer_name)s, %(display_name)s, 'WFS',
              'forestiq.geospatial.eelis.endpoint', 604800, 691200, %(source_srid)s, %(canonical_srid)s, TRUE
            )
            ON CONFLICT (source_key) DO NOTHING
            """,
            {
                "source_key": source_key,
                "layer_name": layer_name,
                "display_name": display_name,
                "source_srid": CANONICAL_SRID,
                "canonical_srid": CANONICAL_SRID,
            },
        )
        cursor.execute(
            """
            SELECT provider, dataset_key, source_kind, canonical_srid
              FROM geospatial_source
             WHERE source_key = %s
             FOR UPDATE
            """,
            (source_key,),
        )
        persisted = cursor.fetchone()
        expected = ("EELIS", layer_name, "WFS", CANONICAL_SRID)
        actual = None if persisted is None else (persisted[0], persisted[1], persisted[2], persisted[3])
        if actual != expected:
            raise SyncError(
                f"Source key {source_key} did not retain expected provider/dataset/kind/CRS={expected}; "
                f"actual={actual}"
            )


def acquire_source_lock(conn: psycopg.Connection[Any], source_key: str) -> None:
    with conn.cursor() as cursor:
        cursor.execute("SELECT pg_try_advisory_lock(hashtext(%s))", (source_key,))
        locked = cursor.fetchone()[0]
    if not locked:
        raise SyncError(f"Another ForestIQ import currently holds the advisory lock for {source_key}")


def release_source_lock(conn: psycopg.Connection[Any], source_key: str) -> None:
    with conn.cursor() as cursor:
        cursor.execute("SELECT pg_advisory_unlock(hashtext(%s))", (source_key,))


def find_staging_generation(
    conn: psycopg.Connection[Any], source_key: str
) -> tuple[ImportState, str] | None:
    with conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT g.run_id, g.id, g.next_start_index, g.provider_number_matched,
                   g.pages_processed, g.row_count, g.schema_fingerprint
              FROM geospatial_generation g
             WHERE g.source_key = %s
               AND g.status = 'STAGING'
             ORDER BY g.created_at DESC
             LIMIT 1
            """,
            (source_key,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    if row[3] is None:
        raise SyncError(f"Staging generation for {source_key} has no provider count and cannot safely resume")
    return (
        ImportState(
            run_id=row[0],
            generation_id=row[1],
            next_start_index=row[2],
            expected_records=row[3],
            pages_processed=row[4],
            rows_staged=row[5],
        ),
        row[6] or "",
    )


def create_generation(
    conn: psycopg.Connection[Any],
    *,
    source_key: str,
    expected_records: int,
    schema: LayerSchema,
    source_srid: int,
) -> ImportState:
    run_id = uuid.uuid4()
    generation_id = uuid.uuid4()
    with conn.transaction():  # noqa: SIM117 - cursor lifetime is intentionally scoped inside the transaction.
        with conn.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO geospatial_source_run (
                  id, source_key, trigger_type, status, schema_fingerprint, started_at
                ) VALUES (%s, %s, 'MANUAL', 'RUNNING', %s, CURRENT_TIMESTAMP)
                """,
                (run_id, source_key, schema.fingerprint),
            )
            cursor.execute(
                """
                INSERT INTO geospatial_generation (
                  id, source_key, run_id, status, schema_fingerprint, source_srid,
                  canonical_srid, provider_number_matched, next_start_index, pages_processed
                ) VALUES (%s, %s, %s, 'STAGING', %s, %s, %s, %s, 0, 0)
                """,
                (
                    generation_id,
                    source_key,
                    run_id,
                    schema.fingerprint,
                    source_srid,
                    CANONICAL_SRID,
                    expected_records,
                ),
            )
            record_event(cursor, source_key, generation_id, run_id, "CREATED", "EELIS WFS generation created")
    return ImportState(run_id, generation_id, 0, expected_records, 0, 0)


def resume_generation(
    conn: psycopg.Connection[Any],
    state: ImportState,
    staged_schema_fingerprint: str,
    current_schema: LayerSchema,
    current_provider_count: int,
) -> ImportState:
    if staged_schema_fingerprint != current_schema.fingerprint:
        raise SyncError(
            "EELIS schema changed since the staging generation began; do not resume mixed schemas. "
            "Use --discard-staging after reviewing the source change."
        )
    if state.expected_records != current_provider_count:
        raise SyncError(
            "EELIS feature count changed since the staging generation began; do not resume a mixed snapshot. "
            "Use --discard-staging and start a fresh import."
        )
    with conn.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET status = 'RUNNING', error_summary = NULL, started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = %s
            """,
            (state.run_id,),
        )
    return state


def discard_staging_generation(conn: psycopg.Connection[Any], source_key: str) -> None:
    existing = find_staging_generation(conn, source_key)
    if existing is None:
        return
    state, _ = existing
    message = "Staging generation explicitly discarded by operator"
    with conn.transaction():  # noqa: SIM117 - cursor lifetime is intentionally scoped inside the transaction.
        with conn.cursor() as cursor:
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status = 'FAILED', failed_at = CURRENT_TIMESTAMP, failure_summary = %s
                 WHERE id = %s AND status = 'STAGING'
                """,
                (message, state.generation_id),
            )
            cursor.execute(
                """
                UPDATE geospatial_source_run
                   SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                       error_summary = %s, updated_at = CURRENT_TIMESTAMP
                 WHERE id = %s
                """,
                (message, state.run_id),
            )
            record_event(cursor, source_key, state.generation_id, state.run_id, "FAILED", message)


def persist_page(
    conn: psycopg.Connection[Any],
    *,
    source_key: str,
    state: ImportState,
    page_rows: Sequence[FeatureRow],
    next_start_index: int,
    source_srid: int,
) -> ImportState:
    if not page_rows:
        raise SyncError("Cannot persist an empty EELIS WFS page")
    values = [
        (
            source_key,
            state.generation_id,
            row.feature_id,
            row.source_id,
            Jsonb(json.loads(row.properties_json)),
            row.geometry_json,
            source_srid,
            CANONICAL_SRID,
        )
        for row in page_rows
    ]
    with conn.transaction():  # noqa: SIM117 - cursor lifetime is intentionally scoped inside the transaction.
        with conn.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO geospatial_feature (
                  source_key, generation_id, feature_id, source_id, properties, geom
                )
                SELECT %s, %s, %s, %s, %s,
                       ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(%s::json), %s), %s))
                """,
                values,
            )
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET next_start_index = %s,
                       pages_processed = pages_processed + 1,
                       row_count = row_count + %s
                 WHERE id = %s AND status = 'STAGING'
                """,
                (next_start_index, len(page_rows), state.generation_id),
            )
            if cursor.rowcount != 1:
                raise SyncError("Staging generation was not available while writing an EELIS page")
            cursor.execute(
                """
                UPDATE geospatial_source_run
                   SET rows_read = rows_read + %s,
                       rows_staged = rows_staged + %s,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = %s AND status = 'RUNNING'
                """,
                (len(page_rows), len(page_rows), state.run_id),
            )
            if cursor.rowcount != 1:
                raise SyncError("Source run was not RUNNING while writing an EELIS page")

    return ImportState(
        run_id=state.run_id,
        generation_id=state.generation_id,
        next_start_index=next_start_index,
        expected_records=state.expected_records,
        pages_processed=state.pages_processed + 1,
        rows_staged=state.rows_staged + len(page_rows),
    )


def validate_generation(
    conn: psycopg.Connection[Any], source_key: str, state: ImportState
) -> dict[str, int]:
    with conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT count(*)::bigint,
                   count(*) FILTER (WHERE geom IS NULL)::bigint,
                   count(*) FILTER (WHERE ST_IsEmpty(geom))::bigint,
                   count(*) FILTER (WHERE NOT ST_IsValid(geom))::bigint,
                   count(*) FILTER (WHERE ST_SRID(geom) <> %s)::bigint,
                   count(*) FILTER (WHERE ST_NDims(geom) <> 2)::bigint
              FROM geospatial_feature
             WHERE source_key = %s AND generation_id = %s
            """,
            (CANONICAL_SRID, source_key, state.generation_id),
        )
        row = cursor.fetchone()
    result = {
        "rowCount": row[0],
        "nullGeometryCount": row[1],
        "emptyGeometryCount": row[2],
        "invalidGeometryCount": row[3],
        "wrongSridCount": row[4],
        "wrongDimensionCount": row[5],
    }
    if result["rowCount"] != state.expected_records:
        raise SyncError(
            f"Staged row count {result['rowCount']} does not equal EELIS numberMatched {state.expected_records}"
        )
    failures = {key: value for key, value in result.items() if key != "rowCount" and value != 0}
    if failures:
        raise SyncError(f"PostGIS geometry validation failed: {failures}")
    return result


def state_source_key(conn: psycopg.Connection[Any], generation_id: uuid.UUID) -> str:
    """Read the source key under which a generation was created; avoid caller-provided scope."""
    with conn.cursor() as cursor:
        cursor.execute("SELECT source_key FROM geospatial_generation WHERE id = %s", (generation_id,))
        row = cursor.fetchone()
    if row is None:
        raise SyncError("Staging generation disappeared before validation")
    return row[0]


def publish_generation(
    conn: psycopg.Connection[Any], source_key: str, state: ImportState, validation: dict[str, int]
) -> None:
    summary = {
        "expectedRecords": state.expected_records,
        "pagesProcessed": state.pages_processed,
        **validation,
    }
    with conn.transaction():  # noqa: SIM117 - cursor lifetime is intentionally scoped inside the transaction.
        with conn.cursor() as cursor:
            # This is the ForestIQ cross-process publication boundary. An advisory lock
            # alone cannot coordinate a Python importer with the Java worker/API.
            cursor.execute(
                "SELECT source_key FROM geospatial_source WHERE source_key = %s FOR UPDATE",
                (source_key,),
            )
            if cursor.fetchone() is None:
                raise SyncError(f"Cannot publish unknown ForestIQ source {source_key}")
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status = 'VALIDATED', validated_at = CURRENT_TIMESTAMP,
                       row_count = %s, validation_summary = %s
                 WHERE id = %s AND source_key = %s AND status = 'STAGING'
                """,
                (validation["rowCount"], Jsonb(summary), state.generation_id, source_key),
            )
            if cursor.rowcount != 1:
                raise SyncError("Staging generation was not available for validation")
            record_event(cursor, source_key, state.generation_id, state.run_id, "VALIDATED", "Row and geometry checks passed")

            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP
                 WHERE source_key = %s AND status = 'PUBLISHED'
                """,
                (source_key,),
            )
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
                 WHERE id = %s AND source_key = %s AND status = 'VALIDATED'
                """,
                (state.generation_id, source_key),
            )
            if cursor.rowcount != 1:
                raise SyncError("Validated generation was not available for publication")
            cursor.execute(
                """
                INSERT INTO geospatial_source_current (source_key, generation_id, published_at)
                VALUES (%s, %s, CURRENT_TIMESTAMP)
                ON CONFLICT (source_key) DO UPDATE SET
                  generation_id = EXCLUDED.generation_id,
                  published_at = EXCLUDED.published_at
                """,
                (source_key, state.generation_id),
            )
            record_event(cursor, source_key, state.generation_id, state.run_id, "PUBLISHED", "EELIS generation published")
            cursor.execute(
                """
                UPDATE geospatial_source_run
                   SET status = 'SUCCESS', rows_inserted = %s, completed_at = CURRENT_TIMESTAMP,
                       error_summary = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE id = %s
                """,
                (validation["rowCount"], state.run_id),
            )


def mark_partial(conn: psycopg.Connection[Any], state: ImportState, exc: BaseException) -> None:
    with conn.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET status = 'PARTIAL', error_summary = %s, updated_at = CURRENT_TIMESTAMP
             WHERE id = %s
            """,
            (bounded_message(exc), state.run_id),
        )


def best_effort_mark_partial(conn: psycopg.Connection[Any], state: ImportState, exc: BaseException) -> None:
    """Record a retryable failure when possible without masking the original outage."""
    try:
        mark_partial(conn, state, exc)
    except psycopg.Error:
        LOGGER.warning("Could not persist PARTIAL state after PostgreSQL connection failure")


def best_effort_mark_failed(
    conn: psycopg.Connection[Any], source_key: str, state: ImportState, exc: BaseException
) -> None:
    """Record deterministic data/SQL failures; retain the original error if storage is unavailable."""
    try:
        mark_failed(conn, source_key, state, exc)
    except psycopg.Error:
        LOGGER.warning("Could not persist FAILED state after PostgreSQL failure")


def mark_failed(conn: psycopg.Connection[Any], source_key: str, state: ImportState, exc: BaseException) -> None:
    message = bounded_message(exc)
    with conn.transaction():  # noqa: SIM117 - cursor lifetime is intentionally scoped inside the transaction.
        with conn.cursor() as cursor:
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status = 'FAILED', failed_at = CURRENT_TIMESTAMP, failure_summary = %s
                 WHERE id = %s AND status IN ('STAGING', 'VALIDATED')
                """,
                (message, state.generation_id),
            )
            cursor.execute(
                """
                UPDATE geospatial_source_run
                   SET status = 'FAILED', error_summary = %s, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = %s
                """,
                (message, state.run_id),
            )
            record_event(cursor, source_key, state.generation_id, state.run_id, "FAILED", message)


def record_event(
    cursor: psycopg.Cursor[Any],
    source_key: str,
    generation_id: uuid.UUID,
    run_id: uuid.UUID,
    event_type: str,
    reason: str,
) -> None:
    cursor.execute(
        """
        INSERT INTO geospatial_generation_event (
          source_key, generation_id, run_id, event_type, actor_subject, reason
        ) VALUES (%s, %s, %s, %s, 'system:eelis-wfs-importer', %s)
        """,
        (source_key, generation_id, run_id, event_type, bounded_message(reason)),
    )


def run_sync(args: argparse.Namespace) -> int:
    validate_arguments(args)
    client = EelisWfsClient(
        args.endpoint,
        args.timeout_seconds,
        args.max_attempts,
        args.min_request_interval_ms,
    )

    LOGGER.info("Inspecting EELIS schema for %s", args.layer)
    schema = client.describe_feature_type(args.layer)
    assert_schema_is_compatible(schema, args.sort_field)
    provider_count = client.count_features(args.layer)
    assert_empty_source_is_allowed(provider_count, args)

    inspection = {
        "endpoint": args.endpoint,
        "layer": args.layer,
        "sourceKey": args.source_key,
        "sortField": args.sort_field,
        "geometryField": schema.geometry_field,
        "schemaFingerprint": schema.fingerprint,
        "sourceSrid": args.source_srid,
        "providerNumberMatched": provider_count,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
    }
    if args.dry_run:
        print(json.dumps(inspection, ensure_ascii=False, indent=2, sort_keys=True))
        return 0

    database_url = require_database_url(args)
    display_name = args.display_name or f"EELIS — {args.layer.split(':', 1)[1].replace('_', ' ')}"
    state: ImportState | None = None
    conn = psycopg.connect(database_url, autocommit=True)
    try:
        acquire_source_lock(conn, args.source_key)
        register_source(
            conn,
            source_key=args.source_key,
            layer_name=args.layer,
            display_name=display_name,
        )
        if args.discard_staging:
            discard_staging_generation(conn, args.source_key)

        existing = find_staging_generation(conn, args.source_key)
        if existing is not None:
            state = resume_generation(conn, existing[0], existing[1], schema, provider_count)
            LOGGER.info(
                "Resuming EELIS generation %s at startIndex=%s (%s/%s staged)",
                state.generation_id,
                state.next_start_index,
                state.rows_staged,
                state.expected_records,
            )
        else:
            state = create_generation(
                conn,
                source_key=args.source_key,
                expected_records=provider_count,
                schema=schema,
                source_srid=args.source_srid,
            )
            LOGGER.info("Created EELIS staging generation %s for %s records", state.generation_id, provider_count)

        while state.next_start_index < state.expected_records:
            if state.pages_processed >= args.max_pages:
                raise SyncError(
                    f"Safety limit reached: {args.max_pages} pages before {state.expected_records} records completed"
                )
            payload = client.get_page(
                args.layer,
                args.sort_field,
                state.next_start_index,
                args.page_size,
                args.source_srid,
            )
            validate_page_count(payload, state.expected_records)
            validate_geojson_crs(payload, args.source_srid)
            page_rows = rows_from_page(payload, args.sort_field)
            if not page_rows:
                raise SyncError(
                    f"EELIS returned an empty page at startIndex={state.next_start_index} before expected completion"
                )
            if len(page_rows) > args.page_size:
                raise SyncError("EELIS returned more features than the requested page size")
            next_start_index = state.next_start_index + len(page_rows)
            if next_start_index > state.expected_records:
                raise SyncError("EELIS page would exceed its advertised numberMatched count")
            state = persist_page(
                conn,
                source_key=args.source_key,
                state=state,
                page_rows=page_rows,
                next_start_index=next_start_index,
                source_srid=args.source_srid,
            )
            LOGGER.info(
                "Committed EELIS page %s: staged %s/%s rows",
                state.pages_processed,
                state.rows_staged,
                state.expected_records,
            )

        provider_count_after = client.count_features(args.layer)
        if provider_count_after != state.expected_records:
            raise SyncError(
                f"EELIS source changed during import: before={state.expected_records}, after={provider_count_after}"
            )
        validation = validate_generation(conn, args.source_key, state)
        publish_generation(conn, args.source_key, state, validation)
        print(
            json.dumps(
                {
                    "status": "SUCCESS",
                    "sourceKey": args.source_key,
                    "layer": args.layer,
                    "generationId": str(state.generation_id),
                    "rows": validation["rowCount"],
                    "pages": state.pages_processed,
                    "schemaFingerprint": schema.fingerprint,
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        return 0
    except ProviderUnavailable as exc:
        if state is not None:
            best_effort_mark_partial(conn, state, exc)
        raise
    except SyncError as exc:
        if state is not None:
            best_effort_mark_failed(conn, args.source_key, state, exc)
        raise
    except (psycopg.OperationalError, psycopg.InterfaceError) as exc:
        if state is not None:
            best_effort_mark_partial(conn, state, exc)
        raise ProviderUnavailable("PostgreSQL write failed; staging generation remains resumable") from exc
    except psycopg.Error as exc:
        if state is not None:
            best_effort_mark_failed(conn, args.source_key, state, exc)
        raise SyncError("PostgreSQL rejected EELIS data or state transition; staging generation was failed") from exc
    finally:
        try:
            release_source_lock(conn, args.source_key)
        except psycopg.Error:
            LOGGER.warning("Could not explicitly release Postgres advisory lock; connection close will release it")
        conn.close()


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--layer", required=True, help="EELIS typeName, for example eelis:natura_elupaik")
    parser.add_argument("--source-key", help="ForestIQ geospatial_source key; default derives from --layer")
    parser.add_argument("--display-name", help="Human-readable ForestIQ source name")
    parser.add_argument("--sort-field", default="sys_id", help="Stable EELIS sort field (default: sys_id)")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help=f"EELIS WFS endpoint (default: {DEFAULT_ENDPOINT})")
    parser.add_argument("--database-url", help="PostgreSQL URI; defaults to FORESTIQ_DATABASE_URL or DATABASE_URL")
    parser.add_argument("--page-size", type=int, default=10_000, help="Features per WFS page, 1..100000 (default: 10000)")
    parser.add_argument("--max-pages", type=int, default=10_000, help="Hard page safety limit (default: 10000)")
    parser.add_argument("--source-srid", type=int, default=CANONICAL_SRID, help="Requested EELIS CRS (currently EPSG:3301 only)")
    parser.add_argument("--timeout-seconds", type=int, default=90, help="Per-request timeout (default: 90)")
    parser.add_argument("--max-attempts", type=int, default=4, help="HTTP retry attempts (default: 4)")
    parser.add_argument("--min-request-interval-ms", type=int, default=100, help="Minimum gap between WFS calls (default: 100)")
    parser.add_argument("--discard-staging", action="store_true", help="Mark an existing incomplete generation failed before a fresh import")
    parser.add_argument(
        "--best-effort-snapshot",
        action="store_true",
        help="Acknowledge that EELIS WFS pagination is not transaction-safe and cannot produce a proven immutable snapshot",
    )
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="Allow publishing a zero-row generation only with --empty-reason",
    )
    parser.add_argument(
        "--empty-reason",
        default="",
        help="Operator audit reason required with --allow-empty",
    )
    parser.add_argument("--dry-run", action="store_true", help="Only inspect EELIS schema and count; do not connect to PostgreSQL")
    parser.add_argument("--verbose", action="store_true", help="Print detailed progress logs")
    args = parser.parse_args(argv)
    if args.source_key is None:
        try:
            args.source_key = derive_source_key(args.layer)
        except (IndexError, SyncError) as exc:
            parser.error(str(exc))
    return args


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        stream=sys.stderr,
    )
    try:
        return run_sync(args)
    except (SyncError, ProviderUnavailable) as exc:
        LOGGER.error("EELIS-to-PostGIS sync failed: %s", bounded_message(exc))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
