from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import httpx
import psycopg
from psycopg.conninfo import conninfo_to_dict

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.worker.execution import ExecutionResult


SOURCE_KEY = "MARU_CADASTRE"
DEFAULT_URL = "https://s3.pilw.io/rp-kemit-kataster/ANDMED/Eesti_KATASTER_GPKG.zip"
REQUIRED_FIELDS = {"tunnus", "hkood", "mk_nimi", "ov_nimi", "ay_nimi", "oiguslik_alus", "eksport"}
SAFE_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,62}$")
EPSG_PATTERN = re.compile(
    r'(?:ID\["EPSG",\s*(\d+)\]|AUTHORITY\["EPSG",\s*"(\d+)"\])',
    re.IGNORECASE,
)
ZIP_SIGNATURE = b"PK\x03\x04"


@dataclass(frozen=True)
class Snapshot:
    path: Path
    checksum: str
    bytes: int
    source_version: str | None


@dataclass(frozen=True)
class Manifest:
    layer_name: str
    code_field: str
    source_srid: int
    provider_feature_count: int
    schema_fingerprint: str


@dataclass(frozen=True)
class StageValidation:
    total_rows: int
    missing_codes: int
    duplicate_codes: int
    invalid_geometries: int
    repaired_geometries: int
    wrong_srid: int
    outside_estonia: int
    provider_feature_count: int
    source_date: date | None

    @property
    def publishable(self) -> bool:
        return (
            self.missing_codes == 0
            and self.duplicate_codes == 0
            and self.invalid_geometries == 0
            and self.wrong_srid == 0
            and self.outside_estonia == 0
            and (
                self.provider_feature_count < 0
                or self.provider_feature_count == self.total_rows
            )
            and self.source_date is not None
        )


def execute_cadastre(
    connection: psycopg.Connection,
    job: ClaimedJob,
    settings: IngestSettings,
) -> ExecutionResult:
    if not settings.database_url:
        raise RuntimeError("DATABASE_URL or INGEST_DATABASE_URL is required for cadastre ingestion")

    source_run_id = _start_source_run(connection, job.trigger_type)
    connection.commit()

    stage_table: str | None = None
    snapshot: Snapshot | None = None
    try:
        snapshot = _download_snapshot(settings)
        _record_artifact(connection, source_run_id, snapshot)
        connection.commit()

        if _accepted_checksum_exists(connection, snapshot.checksum):
            _finish_unchanged(connection, source_run_id)
            connection.commit()
            return ExecutionResult(
                state="SUCCEEDED",
                metrics={
                    "scope": "ALL_ESTONIA",
                    "sourceKey": SOURCE_KEY,
                    "changed": False,
                    "published": False,
                    "unchanged": True,
                    "artifactChecksum": snapshot.checksum,
                },
                message="Cadastre snapshot checksum is already published; no new generation was created.",
                bytes_read=snapshot.bytes,
                source_version=snapshot.source_version,
                source_checksum=snapshot.checksum,
            )
        connection.rollback()

        gpkg = _extract_geopackage(snapshot.path)
        try:
            manifest = _inspect_geopackage(gpkg)
            stage_table = _import_to_stage(gpkg, manifest, source_run_id, settings.database_url)
        finally:
            shutil.rmtree(gpkg.parent, ignore_errors=True)

        validation = _validate_stage(connection, stage_table, manifest)
        minimum_rows = _env_int("MARU_CADASTRE_MINIMUM_ROWS", 500_000, 1, 20_000_000)
        if validation.total_rows < minimum_rows or not validation.publishable:
            raise RuntimeError(
                "Cadastre staging validation failed: "
                + json.dumps(_validation_dict(validation), sort_keys=True)
            )

        previous_generation, previous_rows, previous_fixture = _current_generation(connection)
        if previous_fixture:
            previous_rows = 0
        max_drift = _env_float("MARU_CADASTRE_MAX_ROW_COUNT_DRIFT_RATIO", 0.15, 0.0001, 0.99)
        if previous_rows > 0:
            drift = abs(validation.total_rows - previous_rows) / previous_rows
            if drift > max_drift:
                raise RuntimeError(
                    f"Cadastre row-count drift {drift:.4f} exceeds allowed {max_drift:.4f} "
                    f"(previous={previous_rows}, new={validation.total_rows})"
                )

        generation_id = _create_generation(
            connection,
            source_run_id,
            snapshot,
            manifest,
            validation.source_date,
        )
        _insert_canonical(connection, stage_table, manifest.code_field, generation_id)
        counters = _compare_generation(connection, generation_id, previous_generation)
        _record_run_counters(
            connection,
            source_run_id,
            validation.total_rows,
            validation.repaired_geometries,
            counters,
        )
        _mark_validated(
            connection,
            generation_id,
            validation,
            manifest,
            previous_rows,
            counters,
        )
        connection.commit()

        _publish_generation(connection, source_run_id, generation_id)
        connection.commit()
        _analyze_best_effort(connection)

        return ExecutionResult(
            state="SUCCEEDED",
            metrics={
                "scope": "ALL_ESTONIA",
                "sourceKey": SOURCE_KEY,
                "changed": True,
                "published": True,
                "unchanged": False,
                "artifactChecksum": snapshot.checksum,
                "rows": validation.total_rows,
                "inserted": counters["inserted"],
                "updated": counters["updated"],
                "unchangedRows": counters["unchanged"],
                "removed": counters["removed"],
                "generationId": str(generation_id),
            },
            message="New Maa- ja Ruumiamet cadastre generation was validated and published atomically.",
            rows_read=validation.total_rows,
            rows_staged=validation.total_rows,
            rows_written=validation.total_rows,
            bytes_read=snapshot.bytes,
            source_version=snapshot.source_version,
            source_checksum=snapshot.checksum,
            published_generation_id=generation_id,
        )
    except Exception as exc:
        connection.rollback()
        _finish_failed_source_run(connection, source_run_id, _safe_error(exc))
        connection.commit()
        raise
    finally:
        if stage_table:
            _drop_stage_best_effort(connection, stage_table)
        if snapshot is not None and _env_bool("MARU_CADASTRE_DELETE_AFTER_IMPORT", False):
            try:
                snapshot.path.unlink(missing_ok=True)
            except OSError:
                pass


def _start_source_run(connection: psycopg.Connection, trigger_type: str) -> UUID:
    normalized = trigger_type if trigger_type in {"SCHEDULED", "MANUAL", "RECOVERY", "BOOTSTRAP"} else "MANUAL"
    run_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO geospatial_source_run
                (id, source_key, trigger_type, status, started_at)
            SELECT %s, source_key, %s, 'RUNNING', CURRENT_TIMESTAMP
              FROM geospatial_source
             WHERE source_key=%s
            """,
            (run_id, normalized, SOURCE_KEY),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("MARU_CADASTRE geospatial source is not registered")
    return run_id


def _download_snapshot(settings: IngestSettings) -> Snapshot:
    url = (os.getenv("MARU_CADASTRE_SNAPSHOT_URL") or DEFAULT_URL).strip()
    if not (url.startswith("https://") or url.startswith("http://127.0.0.1") or url.startswith("http://localhost")):
        raise RuntimeError("MARU_CADASTRE_SNAPSHOT_URL must use HTTPS outside loopback tests")

    download_dir = Path(
        os.getenv("GEOSPATIAL_DOWNLOAD_DIR")
        or str(settings.temp_dir / "cadastre")
    )
    download_dir.mkdir(parents=True, exist_ok=True)

    connect_timeout = _env_float("MARU_CADASTRE_CONNECT_TIMEOUT_SECONDS", 15.0, 1.0, 300.0)
    request_timeout = _env_float("MARU_CADASTRE_REQUEST_TIMEOUT_SECONDS", 900.0, 5.0, 7200.0)
    minimum_free = _env_int("GEOSPATIAL_MINIMUM_FREE_BYTES", 536_870_912, 0, 10_000_000_000_000)
    user_agent = (os.getenv("MARU_CADASTRE_USER_AGENT") or "ForestIQ/2.0").strip()

    if shutil.disk_usage(download_dir).free < minimum_free:
        raise RuntimeError("Insufficient free disk space for cadastre snapshot")

    digest = hashlib.sha256()
    fd, partial_name = tempfile.mkstemp(prefix="cadastre-", suffix=".zip.partial", dir=download_dir)
    os.close(fd)
    partial = Path(partial_name)
    bytes_written = 0
    source_version: str | None = None
    try:
        timeout = httpx.Timeout(
            connect=connect_timeout,
            read=request_timeout,
            write=request_timeout,
            pool=connect_timeout,
        )
        with httpx.Client(timeout=timeout, follow_redirects=True, headers={"User-Agent": user_agent}) as client:
            with client.stream(
                "GET",
                url,
                headers={"Accept": "application/zip,application/octet-stream,*/*;q=0.1"},
            ) as response:
                response.raise_for_status()
                if response.url.scheme != "https" and response.url.host not in {"localhost", "127.0.0.1", "::1"}:
                    raise RuntimeError("Cadastre snapshot redirect left HTTPS")
                declared = int(response.headers.get("Content-Length", "-1"))
                if declared == 0:
                    raise RuntimeError("Cadastre snapshot provider returned an empty artifact")
                if declared > 0 and shutil.disk_usage(download_dir).free < minimum_free + declared:
                    raise RuntimeError("Insufficient free disk space for declared cadastre snapshot")
                source_version = response.headers.get("ETag") or response.headers.get("Last-Modified")
                with partial.open("wb") as output:
                    for chunk in response.iter_bytes(1024 * 1024):
                        if not chunk:
                            continue
                        output.write(chunk)
                        digest.update(chunk)
                        bytes_written += len(chunk)
                if declared >= 0 and bytes_written != declared:
                    raise RuntimeError(
                        f"Cadastre snapshot size mismatch: expected {declared}, got {bytes_written}"
                    )
        if bytes_written == 0:
            raise RuntimeError("Cadastre snapshot provider returned an empty artifact")
        with partial.open("rb") as handle:
            if handle.read(4) != ZIP_SIGNATURE:
                raise RuntimeError("Cadastre snapshot artifact is not a ZIP file")

        checksum = digest.hexdigest()
        completed = download_dir / f"cadastre-{checksum[:16]}.zip"
        if completed.exists():
            partial.unlink(missing_ok=True)
        else:
            partial.replace(completed)
        return Snapshot(completed, checksum, bytes_written, source_version)
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def _extract_geopackage(zip_path: Path) -> Path:
    max_bytes = _env_int("MARU_CADASTRE_MAX_UNCOMPRESSED_BYTES", 2_147_483_648, 1, 20_000_000_000)
    max_entries = _env_int("MARU_CADASTRE_MAX_ARCHIVE_ENTRIES", 32, 1, 10_000)
    work_dir = Path(tempfile.mkdtemp(prefix="cadastre-gpkg-", dir=zip_path.parent))
    target = work_dir / "cadastre.gpkg"
    total = 0
    gpkg_entries = 0
    try:
        with zipfile.ZipFile(zip_path) as archive:
            infos = archive.infolist()
            if len(infos) > max_entries:
                raise RuntimeError("Cadastre snapshot contains too many archive entries")
            for info in infos:
                if info.is_dir() or not info.filename.lower().endswith(".gpkg"):
                    continue
                gpkg_entries += 1
                if gpkg_entries > 1:
                    raise RuntimeError("Cadastre snapshot must contain exactly one GeoPackage")
                with archive.open(info) as source, target.open("xb") as output:
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > max_bytes:
                            raise RuntimeError("Cadastre GeoPackage exceeds configured uncompressed size limit")
                        output.write(chunk)
        if gpkg_entries != 1 or total == 0:
            raise RuntimeError("Cadastre snapshot must contain one non-empty GeoPackage")
        with target.open("rb") as handle:
            if handle.read(16) != b"SQLite format 3\x00":
                raise RuntimeError("Extracted cadastre artifact is not a GeoPackage/SQLite container")
        return target
    except Exception:
        shutil.rmtree(work_dir, ignore_errors=True)
        raise


def _inspect_geopackage(gpkg: Path) -> Manifest:
    ogrinfo = (os.getenv("GDAL_OGRINFO") or "ogrinfo").strip()
    timeout = _env_int("GDAL_INSPECT_TIMEOUT_SECONDS", 60, 1, 3600)
    inventory = _run_command([ogrinfo, "-ro", "-so", "-al", "-json", str(gpkg)], timeout)
    payload = json.loads(inventory)
    layers = payload.get("layers")
    if not isinstance(layers, list) or not layers:
        raise RuntimeError("Cadastre GeoPackage contains no vector layers")

    configured_layer = (os.getenv("MARU_CADASTRE_LAYER") or "").strip() or None
    code_field = (os.getenv("MARU_CADASTRE_CODE_FIELD") or "tunnus").strip().lower()
    _require_identifier(code_field)

    selected: dict[str, Any] | None = None
    for layer in layers:
        if not isinstance(layer, dict):
            continue
        name = str(layer.get("name") or "")
        if configured_layer and name != configured_layer:
            continue
        fields = {
            str(field.get("name") or "").lower()
            for field in (layer.get("fields") or [])
            if isinstance(field, dict)
        }
        geometries = layer.get("geometryFields") or []
        polygon = any(
            isinstance(item, dict)
            and (
                "polygon" in str(item.get("type") or "").lower()
                or str(item.get("type") or "").lower() == "geometry"
            )
            for item in geometries
        )
        if code_field in fields and REQUIRED_FIELDS.issubset(fields) and polygon:
            if selected is not None and configured_layer is None:
                raise RuntimeError("Multiple cadastre-like layers found; set MARU_CADASTRE_LAYER")
            selected = layer

    if selected is None:
        raise RuntimeError("No cadastre polygon layer with the required provider schema was found")

    layer_name = str(selected.get("name") or "")
    _require_identifier(layer_name)
    summary = _run_command([ogrinfo, "-ro", "-so", str(gpkg), layer_name], timeout)
    srid = _extract_srid(summary)
    allowed = {
        int(value.strip())
        for value in (os.getenv("MARU_CADASTRE_ALLOWED_SOURCE_SRIDS") or "3301").split(",")
        if value.strip()
    }
    if srid not in allowed:
        raise RuntimeError(f"Cadastre source CRS EPSG:{srid} is not approved; allowed={sorted(allowed)}")

    provider_count = int(selected.get("featureCount", -1))
    fingerprint_source = (
        json.dumps(selected, separators=(",", ":"))
        + f"\nsourceSrid={srid}\ncodeField={code_field}"
    )
    schema_fingerprint = hashlib.sha256(fingerprint_source.encode("utf-8")).hexdigest()
    return Manifest(layer_name, code_field, srid, provider_count, schema_fingerprint)


def _import_to_stage(gpkg: Path, manifest: Manifest, run_id: UUID, database_url: str) -> str:
    ogr2ogr = (os.getenv("GDAL_OGR2OGR") or "ogr2ogr").strip()
    timeout = _env_int("GDAL_IMPORT_TIMEOUT_SECONDS", 3600, 1, 14_400)
    table = f"cadastre_stage_{str(run_id).replace('-', '')}"
    _require_identifier(table)
    conn = conninfo_to_dict(database_url)
    dbname = conn.get("dbname") or conn.get("database")
    if not dbname:
        raise RuntimeError("Cadastre GDAL import requires a PostgreSQL database name")

    env = os.environ.copy()
    mapping = {
        "PGHOST": conn.get("host"),
        "PGPORT": conn.get("port"),
        "PGDATABASE": dbname,
        "PGUSER": conn.get("user"),
        "PGPASSWORD": conn.get("password"),
        "PGSSLMODE": conn.get("sslmode"),
    }
    for key, value in mapping.items():
        if value is not None and str(value) != "":
            env[key] = str(value)

    command = [
        ogr2ogr,
        "-f", "PostgreSQL",
        f"PG:dbname='{dbname}'",
        str(gpkg),
        manifest.layer_name,
        "-nln", f"ingest.{table}",
        "-overwrite",
        "-lco", "GEOMETRY_NAME=geom",
        "-lco", "FID=ogr_fid",
        "-nlt", "PROMOTE_TO_MULTI",
        "-dim", "XY",
        "-t_srs", "EPSG:3301",
        "-gt", "65536",
    ]
    _run_command(command, timeout, env=env)
    return table


def _validate_stage(
    connection: psycopg.Connection,
    stage_table: str,
    manifest: Manifest,
) -> StageValidation:
    table = _qualified_stage(stage_table)
    code = _quoted(manifest.code_field)
    query = f"""
        SELECT count(*)::bigint AS total_rows,
               count(*) FILTER (
                   WHERE NULLIF(btrim({code}::text), '') IS NULL
               )::bigint AS missing_codes,
               (
                   count(*) FILTER (WHERE NULLIF(btrim({code}::text), '') IS NOT NULL)
                   - count(DISTINCT NULLIF(btrim({code}::text), ''))
               )::bigint AS duplicate_codes,
               count(*) FILTER (
                   WHERE geom IS NULL
                      OR ST_IsEmpty(geom)
                      OR GeometryType(geom) NOT IN ('POLYGON', 'MULTIPOLYGON')
                      OR (
                          NOT ST_IsValid(geom)
                          AND (
                              ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                              OR NOT ST_IsValid(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                          )
                      )
               )::bigint AS invalid_geometries,
               count(*) FILTER (
                   WHERE geom IS NOT NULL
                     AND NOT ST_IsEmpty(geom)
                     AND GeometryType(geom) IN ('POLYGON', 'MULTIPOLYGON')
                     AND NOT ST_IsValid(geom)
                     AND NOT ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
                     AND ST_IsValid(ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3))
               )::bigint AS repaired_geometries,
               count(*) FILTER (
                   WHERE geom IS NOT NULL AND ST_SRID(geom) <> 3301
               )::bigint AS wrong_srid,
               count(*) FILTER (
                   WHERE geom IS NOT NULL
                     AND ST_SRID(geom) = 3301
                     AND NOT ST_CoveredBy(
                         ST_Envelope(geom),
                         ST_MakeEnvelope(250000, 6300000, 850000, 6700000, 3301)
                     )
               )::bigint AS outside_estonia_guard,
               min(eksport::date) AS export_min,
               max(eksport::date) AS export_max
          FROM {table}
    """
    with connection.cursor() as cursor:
        cursor.execute(query)
        row = cursor.fetchone()
    if row is None:
        raise RuntimeError("Cadastre staging validation returned no result")
    export_min, export_max = row[7], row[8]
    source_date = export_min if export_min is not None and export_min == export_max else None
    return StageValidation(
        total_rows=int(row[0] or 0),
        missing_codes=int(row[1] or 0),
        duplicate_codes=int(row[2] or 0),
        invalid_geometries=int(row[3] or 0),
        repaired_geometries=int(row[4] or 0),
        wrong_srid=int(row[5] or 0),
        outside_estonia=int(row[6] or 0),
        provider_feature_count=manifest.provider_feature_count,
        source_date=source_date,
    )


def _record_artifact(connection: psycopg.Connection, run_id: UUID, snapshot: Snapshot) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET source_version=%s,
                   artifact_checksum=%s,
                   artifact_bytes=%s,
                   updated_at=CURRENT_TIMESTAMP
             WHERE id=%s AND status='RUNNING'
            """,
            (snapshot.source_version, snapshot.checksum, snapshot.bytes, run_id),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Active cadastre source run disappeared before artifact recording")


def _accepted_checksum_exists(connection: psycopg.Connection, checksum: str) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT 1
              FROM geospatial_generation
             WHERE source_key=%s
               AND artifact_checksum=%s
               AND status IN ('VALIDATED','PUBLISHED','RETIRED')
             LIMIT 1
            """,
            (SOURCE_KEY, checksum),
        )
        return cursor.fetchone() is not None


def _finish_unchanged(connection: psycopg.Connection, run_id: UUID) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET status='SUCCESS',
                   completed_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP,
                   error_summary=NULL
             WHERE id=%s AND status='RUNNING'
            """,
            (run_id,),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Active cadastre source run disappeared during unchanged completion")


def _current_generation(connection: psycopg.Connection) -> tuple[UUID | None, int, bool]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.generation_id,
                   g.row_count,
                   COALESCE((g.validation_summary->>'fixture')::boolean, FALSE)
              FROM geospatial_source_current c
              JOIN geospatial_generation g ON g.id=c.generation_id AND g.source_key=c.source_key
             WHERE c.source_key=%s
            """,
            (SOURCE_KEY,),
        )
        row = cursor.fetchone()
    if row is None:
        return None, 0, False
    return row[0], int(row[1] or 0), bool(row[2])


def _create_generation(
    connection: psycopg.Connection,
    run_id: UUID,
    snapshot: Snapshot,
    manifest: Manifest,
    source_date: date | None,
) -> UUID:
    generation_id = uuid4()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO geospatial_generation (
                id, source_key, run_id, status, source_date, source_version,
                artifact_checksum, artifact_bytes, schema_fingerprint,
                source_srid, canonical_srid
            )
            VALUES (%s, %s, %s, 'STAGING', %s, %s, %s, %s, %s, %s, 3301)
            """,
            (
                generation_id,
                SOURCE_KEY,
                run_id,
                source_date,
                snapshot.source_version,
                snapshot.checksum,
                snapshot.bytes,
                manifest.schema_fingerprint,
                manifest.source_srid,
            ),
        )
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET source_date=%s,
                   schema_fingerprint=%s,
                   updated_at=CURRENT_TIMESTAMP
             WHERE id=%s AND status='RUNNING'
            """,
            (source_date, manifest.schema_fingerprint, run_id),
        )
        cursor.execute(
            """
            INSERT INTO geospatial_generation_event
                (source_key, generation_id, run_id, event_type, reason)
            VALUES (%s, %s, %s, 'CREATED', 'Staging generation created')
            """,
            (SOURCE_KEY, generation_id, run_id),
        )
    return generation_id


def _insert_canonical(
    connection: psycopg.Connection,
    stage_table: str,
    code_field: str,
    generation_id: UUID,
) -> None:
    table = _qualified_stage(stage_table)
    code = _quoted(code_field)
    query = f"""
        INSERT INTO geospatial_cadastre_parcel
            (source_key, generation_id, cadastral_code, provider_feature_id,
             source_attributes, source_area_m2, geom)
        SELECT 'MARU_CADASTRE',
               %s,
               btrim({code}::text),
               NULL,
               to_jsonb(s) - 'geom',
               ST_Area(canonical_geom),
               ST_Multi(canonical_geom)::geometry(MultiPolygon, 3301)
          FROM (
               SELECT stage.*,
                      ST_CollectionExtract(ST_MakeValid(ST_Force2D(geom)), 3) AS canonical_geom
                 FROM {table} stage
          ) s
    """
    with connection.cursor() as cursor:
        cursor.execute(query, (generation_id,))
        inserted = cursor.rowcount
        cursor.execute(
            "SELECT count(*)::bigint FROM geospatial_cadastre_parcel WHERE generation_id=%s",
            (generation_id,),
        )
        actual = int(cursor.fetchone()[0] or 0)
    if actual != inserted:
        raise RuntimeError("Canonical cadastre generation row count does not match inserted rows")


def _compare_generation(
    connection: psycopg.Connection,
    generation_id: UUID,
    previous_generation: UUID | None,
) -> dict[str, int]:
    if previous_generation is None:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT count(*)::bigint FROM geospatial_cadastre_parcel WHERE generation_id=%s",
                (generation_id,),
            )
            count = int(cursor.fetchone()[0] or 0)
        return {"inserted": count, "updated": 0, "unchanged": 0, "removed": 0}

    with connection.cursor() as cursor:
        cursor.execute(
            """
            WITH cur AS (
                SELECT cadastral_code,
                       source_attributes - 'eksport' - 'ogr_fid' AS comparable_attributes,
                       geom
                  FROM geospatial_cadastre_parcel
                 WHERE generation_id=%s
            ), old AS (
                SELECT cadastral_code,
                       source_attributes - 'eksport' - 'ogr_fid' AS comparable_attributes,
                       geom
                  FROM geospatial_cadastre_parcel
                 WHERE generation_id=%s
            )
            SELECT count(*) FILTER (WHERE old.cadastral_code IS NULL)::bigint,
                   count(*) FILTER (
                       WHERE cur.cadastral_code IS NOT NULL
                         AND old.cadastral_code IS NOT NULL
                         AND (
                           cur.comparable_attributes IS DISTINCT FROM old.comparable_attributes
                           OR NOT ST_Equals(cur.geom, old.geom)
                         )
                   )::bigint,
                   count(*) FILTER (
                       WHERE cur.cadastral_code IS NOT NULL
                         AND old.cadastral_code IS NOT NULL
                         AND cur.comparable_attributes IS NOT DISTINCT FROM old.comparable_attributes
                         AND ST_Equals(cur.geom, old.geom)
                   )::bigint,
                   count(*) FILTER (WHERE cur.cadastral_code IS NULL)::bigint
              FROM cur
              FULL OUTER JOIN old USING (cadastral_code)
            """,
            (generation_id, previous_generation),
        )
        row = cursor.fetchone()
    return {
        "inserted": int(row[0] or 0),
        "updated": int(row[1] or 0),
        "unchanged": int(row[2] or 0),
        "removed": int(row[3] or 0),
    }


def _record_run_counters(
    connection: psycopg.Connection,
    run_id: UUID,
    rows: int,
    repaired: int,
    counters: dict[str, int],
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET rows_read=%s,
                   rows_staged=%s,
                   rows_inserted=%s,
                   rows_updated=%s,
                   rows_unchanged=%s,
                   rows_removed=%s,
                   rows_rejected=0,
                   geometries_repaired=%s,
                   updated_at=CURRENT_TIMESTAMP
             WHERE id=%s AND status='RUNNING'
            """,
            (
                rows,
                rows,
                counters["inserted"],
                counters["updated"],
                counters["unchanged"],
                counters["removed"],
                repaired,
                run_id,
            ),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Active cadastre source run disappeared during counter update")


def _mark_validated(
    connection: psycopg.Connection,
    generation_id: UUID,
    validation: StageValidation,
    manifest: Manifest,
    previous_rows: int,
    counters: dict[str, int],
) -> None:
    summary = {
        "layer": manifest.layer_name,
        "codeField": manifest.code_field,
        "sourceSrid": manifest.source_srid,
        "canonicalSrid": 3301,
        "schemaFingerprint": manifest.schema_fingerprint,
        "rows": validation.total_rows,
        "providerFeatureCount": validation.provider_feature_count,
        "previousRows": previous_rows,
        "missingCodes": validation.missing_codes,
        "duplicateCodes": validation.duplicate_codes,
        "invalidGeometries": validation.invalid_geometries,
        "repairedGeometries": validation.repaired_geometries,
        "wrongSrid": validation.wrong_srid,
        "outsideEstoniaGuard": validation.outside_estonia,
        "estoniaGuard": "L-EST97_COARSE_ENVELOPE_V1",
        "sourceDate": validation.source_date.isoformat() if validation.source_date else None,
        **counters,
    }
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_generation
               SET status='VALIDATED',
                   row_count=%s,
                   rejected_row_count=0,
                   repaired_geometry_count=%s,
                   validation_summary=%s::jsonb,
                   validated_at=CURRENT_TIMESTAMP
             WHERE id=%s AND status='STAGING'
            """,
            (
                validation.total_rows,
                validation.repaired_geometries,
                json.dumps(summary, separators=(",", ":")),
                generation_id,
            ),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Cadastre generation could not be validated")
        cursor.execute(
            """
            INSERT INTO geospatial_generation_event
                (source_key, generation_id, run_id, event_type, reason)
            SELECT source_key, id, run_id, 'VALIDATED', 'Generation validation completed'
              FROM geospatial_generation
             WHERE id=%s
            """,
            (generation_id,),
        )


def _publish_generation(
    connection: psycopg.Connection,
    run_id: UUID,
    generation_id: UUID,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT source_key FROM geospatial_source WHERE source_key=%s FOR UPDATE",
            (SOURCE_KEY,),
        )
        if cursor.fetchone() is None:
            raise RuntimeError("MARU_CADASTRE source disappeared during publish")
        cursor.execute(
            """
            SELECT status
              FROM geospatial_generation
             WHERE id=%s AND source_key=%s
             FOR UPDATE
            """,
            (generation_id, SOURCE_KEY),
        )
        target = cursor.fetchone()
        if target is None or target[0] != "VALIDATED":
            raise RuntimeError("Cadastre publish target must be VALIDATED")

        cursor.execute(
            "SELECT generation_id FROM geospatial_source_current WHERE source_key=%s FOR UPDATE",
            (SOURCE_KEY,),
        )
        current = cursor.fetchone()
        previous = current[0] if current else None
        if previous is not None and previous != generation_id:
            cursor.execute(
                """
                UPDATE geospatial_generation
                   SET status='RETIRED', retired_at=CURRENT_TIMESTAMP
                 WHERE id=%s AND source_key=%s AND status='PUBLISHED'
                """,
                (previous, SOURCE_KEY),
            )
            cursor.execute(
                """
                INSERT INTO geospatial_generation_event
                    (source_key, generation_id, event_type, actor_subject, reason)
                VALUES (%s, %s, 'RETIRED', 'python-ingest-worker', 'Retired by new publication')
                """,
                (SOURCE_KEY, previous),
            )

        cursor.execute(
            """
            UPDATE geospatial_generation
               SET status='PUBLISHED',
                   published_at=COALESCE(published_at, CURRENT_TIMESTAMP),
                   retired_at=NULL
             WHERE id=%s AND source_key=%s
            """,
            (generation_id, SOURCE_KEY),
        )
        cursor.execute(
            """
            INSERT INTO geospatial_source_current (source_key, generation_id, published_at)
            VALUES (%s, %s, CURRENT_TIMESTAMP)
            ON CONFLICT (source_key) DO UPDATE SET
                generation_id=EXCLUDED.generation_id,
                published_at=EXCLUDED.published_at
            """,
            (SOURCE_KEY, generation_id),
        )
        cursor.execute(
            """
            INSERT INTO geospatial_generation_event
                (source_key, generation_id, run_id, event_type, actor_subject, reason)
            VALUES (
                %s, %s, %s, 'PUBLISHED', 'python-ingest-worker',
                'Verified nationwide Maa- ja Ruumiamet GeoPackage publication'
            )
            """,
            (SOURCE_KEY, generation_id, run_id),
        )
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET status='SUCCESS',
                   completed_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP,
                   error_summary=NULL
             WHERE id=%s AND status='RUNNING'
            """,
            (run_id,),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Cadastre source run could not be completed after publish")


def _finish_failed_source_run(
    connection: psycopg.Connection,
    run_id: UUID,
    error: str,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE geospatial_source_run
               SET status='FAILED',
                   error_summary=%s,
                   completed_at=CURRENT_TIMESTAMP,
                   updated_at=CURRENT_TIMESTAMP
             WHERE id=%s AND status IN ('RUNNING','QUEUED')
            """,
            (error[:2000], run_id),
        )
        cursor.execute(
            """
            UPDATE geospatial_generation
               SET status='FAILED',
                   failed_at=CURRENT_TIMESTAMP,
                   failure_summary=%s
             WHERE run_id=%s
               AND status IN ('STAGING','VALIDATED')
            RETURNING id
            """,
            (error[:2000], run_id),
        )
        failed_generation = cursor.fetchone()
        if failed_generation is not None:
            cursor.execute(
                """
                INSERT INTO geospatial_generation_event
                    (source_key, generation_id, run_id, event_type, reason)
                VALUES (%s, %s, %s, 'FAILED', %s)
                """,
                (SOURCE_KEY, failed_generation[0], run_id, error[:1000]),
            )


def _drop_stage_best_effort(connection: psycopg.Connection, stage_table: str) -> None:
    try:
        connection.rollback()
        with connection.cursor() as cursor:
            cursor.execute(f"DROP TABLE IF EXISTS {_qualified_stage(stage_table)}")
        connection.commit()
    except psycopg.Error:
        connection.rollback()


def _analyze_best_effort(connection: psycopg.Connection) -> None:
    try:
        with connection.cursor() as cursor:
            cursor.execute("ANALYZE geospatial_cadastre_parcel")
        connection.commit()
    except psycopg.Error:
        connection.rollback()


def _validation_dict(value: StageValidation) -> dict[str, Any]:
    return {
        "rows": value.total_rows,
        "missingCodes": value.missing_codes,
        "duplicateCodes": value.duplicate_codes,
        "invalidGeometries": value.invalid_geometries,
        "repairedGeometries": value.repaired_geometries,
        "wrongSrid": value.wrong_srid,
        "outsideEstoniaGuard": value.outside_estonia,
        "providerFeatureCount": value.provider_feature_count,
        "sourceDate": value.source_date.isoformat() if value.source_date else None,
    }


def _run_command(command: list[str], timeout_seconds: int, *, env: dict[str, str] | None = None) -> str:
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout_seconds,
            env=env,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"GDAL command exceeded timeout {timeout_seconds}s") from exc
    output = result.stdout or ""
    if result.returncode != 0:
        raise RuntimeError(
            "GDAL command failed: " + _redact(output)[:2000]
        )
    return output


def _extract_srid(text: str) -> int:
    found: int | None = None
    for match in EPSG_PATTERN.finditer(text):
        raw = match.group(1) or match.group(2)
        found = int(raw)
    if found is None or found <= 0:
        raise RuntimeError("Cadastre layer CRS has no resolvable EPSG identifier")
    return found


def _require_identifier(value: str) -> str:
    if not SAFE_IDENTIFIER.fullmatch(value):
        raise RuntimeError(f"Unsafe SQL/GDAL identifier: {value}")
    return value


def _quoted(value: str) -> str:
    return '"' + _require_identifier(value) + '"'


def _qualified_stage(table: str) -> str:
    return f'"ingest".{_quoted(table)}'


def _redact(value: str) -> str:
    return re.sub(
        r"(?i)(password|authorization|token|secret)\s*[:=]\s*[^,;\s]+",
        r"\1=[redacted]",
        value,
    )


def _safe_error(exc: BaseException) -> str:
    return _redact(" ".join((str(exc) or type(exc).__name__).split()))[:1500]


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


def _env_bool(name: str, default: bool) -> bool:
    raw = (os.getenv(name) or "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}
