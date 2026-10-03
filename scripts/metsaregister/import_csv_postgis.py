#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import os
import re
import sqlite3
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator
from urllib.parse import quote

csv.field_size_limit(1024 * 1024 * 1024)

CSV_FIELDS = ("layer_name", "feature_key", "source_id", "provider_feature_id", "source_hash", "properties_json", "geometry_geojson")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MIRROR_LOCK_NAME = "forestiq:metsaregister:wfs-mirror"


class CsvImportError(RuntimeError):
    pass


@dataclass(frozen=True)
class Snapshot:
    csv_path: Path
    manifest_path: Path
    layer_name: str
    identity_fields: tuple[str, ...]
    sort_fields: tuple[str, ...]
    expected_rows: int
    pages_exported: int
    provider_number_matched: int | None
    csv_sha256: str | None


def normalize_layer_name(value: str) -> str:
    value = value.strip()
    if not value:
        raise CsvImportError("Layer name is empty")
    return value if ":" in value else f"metsaregister:{value}"


def open_csv_text(path: Path):
    if path.name.endswith(".csv.gz"):
        return gzip.open(path, "rt", encoding="utf-8", newline="")
    return path.open("r", encoding="utf-8", newline="")


def file_sha256(path: Path) -> str:
    """Return the SHA-256 of the logical CSV bytes.

    Export manifests hash the uncompressed CSV. For a later `.csv.gz` archive we
    therefore hash the decompressed stream, keeping the original manifest valid
    without weakening integrity verification.
    """
    digest = hashlib.sha256()
    opener = gzip.open if path.name.endswith(".csv.gz") else open
    with opener(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest_path_for(csv_path: Path) -> Path:
    name = csv_path.name
    if name.endswith(".csv.gz"):
        slug = name[:-7]
    elif name.endswith(".csv"):
        slug = name[:-4]
    else:
        raise CsvImportError(f"Unsupported snapshot extension: {csv_path}")
    return csv_path.with_name(f"{slug}.manifest.json")


def load_snapshot(csv_path: Path) -> Snapshot:
    manifest_path = manifest_path_for(csv_path)
    if not manifest_path.exists():
        raise CsvImportError(f"Missing sidecar manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("status") != "complete":
        raise CsvImportError(f"{manifest_path} is not a completed export")
    layer_name = normalize_layer_name(str(manifest.get("layer") or ""))
    fields = manifest.get("identity_fields")
    if not isinstance(fields, list) or not fields:
        raise CsvImportError(f"{manifest_path} has no identity_fields")
    sort_fields = manifest.get("sort_by")
    if not isinstance(sort_fields, list) or not sort_fields:
        sort_fields = fields
    rows = manifest.get("rows_exported")
    if not isinstance(rows, int) or rows < 0:
        raise CsvImportError(f"{manifest_path} has invalid rows_exported")
    matched = manifest.get("provider_number_matched")
    if matched is not None and (not isinstance(matched, int) or matched < 0):
        raise CsvImportError(f"{manifest_path} has invalid provider_number_matched")
    if matched is not None and matched != rows:
        raise CsvImportError(f"{manifest_path}: exported/provider row count mismatch")
    csv_hash = manifest.get("csv_sha256")
    if csv_hash is not None and not SHA256_RE.fullmatch(str(csv_hash)):
        raise CsvImportError(f"{manifest_path} has invalid csv_sha256")
    return Snapshot(
        csv_path,
        manifest_path,
        layer_name,
        tuple(str(value) for value in fields),
        tuple(str(value) for value in sort_fields),
        rows,
        int(manifest.get("pages_exported") or 0),
        matched,
        str(csv_hash) if csv_hash else None,
    )


def validate_snapshot(snapshot: Snapshot) -> int:
    if snapshot.csv_sha256 and file_sha256(snapshot.csv_path) != snapshot.csv_sha256:
        raise CsvImportError(f"{snapshot.csv_path}: SHA-256 differs from export manifest")
    with tempfile.NamedTemporaryFile(prefix="forestiq-wfs-keys-", suffix=".sqlite") as temp:
        with sqlite3.connect(temp.name) as db, open_csv_text(snapshot.csv_path) as handle:
            db.execute("CREATE TABLE feature_key (value TEXT PRIMARY KEY)")
            reader = csv.DictReader(handle)
            if tuple(reader.fieldnames or ()) != CSV_FIELDS:
                raise CsvImportError(f"{snapshot.csv_path} has an incompatible CSV header")
            count = 0
            for line_no, row in enumerate(reader, start=2):
                if row["layer_name"] != snapshot.layer_name:
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: unexpected layer_name")
                key = row["feature_key"]
                if not key or len(key) > 320:
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: invalid feature_key")
                if row["source_id"] != key:
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: source_id must equal feature_key")
                if not SHA256_RE.fullmatch(row["source_hash"] or ""):
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: invalid source_hash")
                try:
                    properties = json.loads(row["properties_json"])
                except json.JSONDecodeError as exc:
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: invalid properties_json") from exc
                if not isinstance(properties, dict):
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: properties_json must be an object")
                geometry = row["geometry_geojson"]
                if geometry:
                    try:
                        geometry_value = json.loads(geometry)
                    except json.JSONDecodeError as exc:
                        raise CsvImportError(f"{snapshot.csv_path}:{line_no}: invalid geometry_geojson") from exc
                    if not isinstance(geometry_value, dict):
                        raise CsvImportError(f"{snapshot.csv_path}:{line_no}: geometry_geojson must be an object")
                try:
                    db.execute("INSERT INTO feature_key(value) VALUES (?)", (key,))
                except sqlite3.IntegrityError as exc:
                    raise CsvImportError(f"{snapshot.csv_path}:{line_no}: duplicate feature_key {key!r}") from exc
                count += 1
            if count != snapshot.expected_rows:
                raise CsvImportError(f"{snapshot.csv_path}: CSV has {count} rows but manifest says {snapshot.expected_rows}")
            return count


def iter_rows(snapshot: Snapshot) -> Iterator[tuple[str, str, str, str, str | None, str | None]]:
    with open_csv_text(snapshot.csv_path) as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            geometry = row["geometry_geojson"] or None
            yield row["feature_key"], row["source_id"], row["source_hash"], row["properties_json"], geometry, geometry


def batched(iterator: Iterator[tuple[Any, ...]], size: int) -> Iterator[list[tuple[Any, ...]]]:
    batch: list[tuple[Any, ...]] = []
    for item in iterator:
        batch.append(item)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def acquire_mirror_lock(conn: Any) -> None:
    with conn.cursor() as cur:
        cur.execute("SELECT pg_try_advisory_lock(hashtext(%s))", (MIRROR_LOCK_NAME,))
        locked = bool(cur.fetchone()[0])
    if not locked:
        raise CsvImportError(
            "Metsaregister WFS mirror lock is already held; a Java scheduler/manual mirror run or another CSV import is active"
        )


def mark_import_failure(conn: Any, generation_id: str, message: str) -> None:
    safe_message = " ".join(str(message).split())[:1000]
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE metsaregister_wfs_generation "
                "SET last_failure_at=CURRENT_TIMESTAMP, last_error=%s, updated_at=CURRENT_TIMESTAMP "
                "WHERE id=%s AND state='STAGING'",
                (safe_message, generation_id),
            )
        conn.commit()
    except Exception:
        conn.rollback()


def import_snapshot(snapshot: Snapshot, database_url: str, *, batch_size: int = 5_000, replace_staging: bool = False) -> str:
    try:
        import psycopg
    except ImportError as exc:
        raise CsvImportError("psycopg is required; install scripts/metsaregister/requirements.txt") from exc
    if batch_size < 1:
        raise ValueError("batch_size must be positive")

    identity_descriptor = ",".join(snapshot.identity_fields)
    sort_descriptor = ",".join(snapshot.sort_fields)
    generation_id = str(uuid.uuid4())

    with psycopg.connect(database_url) as conn:
        acquire_mirror_lock(conn)

        with conn.cursor() as cur:
            cur.execute(
                "SELECT approved, enabled, identity_property, sort_property, current_generation_id "
                "FROM metsaregister_wfs_layer WHERE layer_name=%s",
                (snapshot.layer_name,),
            )
            layer = cur.fetchone()
            if layer is None:
                raise CsvImportError(
                    f"Layer {snapshot.layer_name} is absent from metsaregister_wfs_layer; run Flyway migrations first"
                )
            approved, enabled, identity_property, sort_property, previous_generation = layer
            if not approved or not enabled:
                raise CsvImportError(f"Layer {snapshot.layer_name} is not approved/enabled")
            if identity_property and identity_property != identity_descriptor:
                raise CsvImportError(f"Layer identity mismatch: DB={identity_property!r}, CSV={identity_descriptor!r}")
            if sort_property and sort_property != sort_descriptor:
                raise CsvImportError(f"Layer sort mismatch: DB={sort_property!r}, CSV={sort_descriptor!r}")

            cur.execute(
                "SELECT id FROM metsaregister_wfs_generation WHERE layer_name=%s AND state='STAGING'",
                (snapshot.layer_name,),
            )
            existing = cur.fetchone()
            if existing:
                if not replace_staging:
                    raise CsvImportError(
                        f"Layer {snapshot.layer_name} already has staging generation {existing[0]}; "
                        "use --replace-staging only if that checkpoint may be discarded"
                    )
                cur.execute(
                    "DELETE FROM metsaregister_wfs_generation WHERE id=%s AND state='STAGING'",
                    (existing[0],),
                )
            cur.execute(
                "INSERT INTO metsaregister_wfs_generation "
                "(id, layer_name, state, provider_number_matched) VALUES (%s, %s, 'STAGING', %s)",
                (generation_id, snapshot.layer_name, snapshot.provider_number_matched),
            )
        conn.commit()

        inserted = 0
        insert_sql = """
            INSERT INTO metsaregister_wfs_feature
                (generation_id, layer_name, feature_key, source_id, source_hash, properties, geometry)
            VALUES (
                %s, %s, %s, %s, %s, %s::jsonb,
                CASE WHEN CAST(%s AS text) IS NULL THEN NULL
                     ELSE ST_SetSRID(ST_Force2D(ST_GeomFromGeoJSON(CAST(%s AS text))), 4326)
                END
            )
        """
        try:
            for batch in batched(iter_rows(snapshot), batch_size):
                params = [
                    (generation_id, snapshot.layer_name, key, source_id, source_hash, properties, geom_null, geom_json)
                    for key, source_id, source_hash, properties, geom_null, geom_json in batch
                ]
                with conn.cursor() as cur:
                    cur.executemany(insert_sql, params)
                    inserted += len(batch)
                    cur.execute(
                        "UPDATE metsaregister_wfs_generation "
                        "SET rows_staged=%s, next_start_index=%s, pages_processed=%s, updated_at=CURRENT_TIMESTAMP "
                        "WHERE id=%s AND state='STAGING'",
                        (inserted, inserted, snapshot.pages_exported, generation_id),
                    )
                conn.commit()
        except Exception as exc:
            conn.rollback()
            mark_import_failure(conn, generation_id, exc)
            raise

        if inserted != snapshot.expected_rows:
            error = CsvImportError(f"Inserted {inserted} rows but snapshot requires {snapshot.expected_rows}")
            mark_import_failure(conn, generation_id, error)
            raise error

        try:
            with conn.transaction():
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT count(*) FROM metsaregister_wfs_feature WHERE generation_id=%s",
                        (generation_id,),
                    )
                    actual = int(cur.fetchone()[0])
                    if actual != snapshot.expected_rows:
                        raise CsvImportError(
                            f"Generation {generation_id} has {actual} rows but snapshot requires {snapshot.expected_rows}"
                        )
                    cur.execute(
                        "UPDATE metsaregister_wfs_generation "
                        "SET state='PUBLISHED', rows_staged=%s, next_start_index=%s, pages_processed=%s, "
                        "provider_number_matched=%s, completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP, "
                        "last_failure_at=NULL, last_error=NULL WHERE id=%s AND state='STAGING'",
                        (actual, actual, snapshot.pages_exported, snapshot.provider_number_matched, generation_id),
                    )
                    if cur.rowcount != 1:
                        raise CsvImportError("Staging generation disappeared before publication")
                    cur.execute(
                        "UPDATE metsaregister_wfs_layer "
                        "SET identity_property=COALESCE(identity_property, %s), "
                        "sort_property=COALESCE(sort_property, %s), provider_present=TRUE, "
                        "current_generation_id=%s, current_row_count=%s, provider_number_matched=%s, "
                        "state='HEALTHY', last_success_at=CURRENT_TIMESTAMP, last_failure_at=NULL, "
                        "last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE layer_name=%s",
                        (
                            identity_descriptor,
                            sort_descriptor,
                            generation_id,
                            actual,
                            snapshot.provider_number_matched,
                            snapshot.layer_name,
                        ),
                    )
                    if previous_generation and str(previous_generation) != generation_id:
                        cur.execute(
                            "UPDATE metsaregister_wfs_generation SET state='RETIRED', updated_at=CURRENT_TIMESTAMP "
                            "WHERE id=%s AND state='PUBLISHED'",
                            (previous_generation,),
                        )
        except Exception as exc:
            conn.rollback()
            mark_import_failure(conn, generation_id, exc)
            raise

    return generation_id


def discover_csv_inputs(values: list[Path]) -> list[Path]:
    results: list[Path] = []
    for value in values:
        if value.is_dir():
            results.extend(sorted(value.glob("*.csv")))
            results.extend(sorted(value.glob("*.csv.gz")))
        elif value.is_file():
            results.append(value)
        else:
            raise CsvImportError(f"Input does not exist: {value}")

    unique: list[Path] = []
    seen: set[Path] = set()
    for path in results:
        resolved = path.resolve()
        if ".partial." in path.name:
            continue
        if resolved not in seen:
            unique.append(path)
            seen.add(resolved)
    if not unique:
        raise CsvImportError("No CSV snapshots found")
    return unique


def load_compose_database_url(env_path: Path) -> str:
    if not env_path.exists():
        raise CsvImportError(f"Compose env file does not exist: {env_path}")

    values: dict[str, str] = {}
    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key.strip()] = value

    missing = [key for key in ("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD") if not values.get(key)]
    if missing:
        raise CsvImportError(f"{env_path} is missing required keys: {', '.join(missing)}")

    host = values.get("LOCAL_PORT_HOST") or "localhost"
    if host in {"0.0.0.0", "::"}:
        host = "localhost"
    port = values.get("POSTGRES_PORT") or "5432"
    user = quote(values["POSTGRES_USER"], safe="")
    password = quote(values["POSTGRES_PASSWORD"], safe="")
    database = quote(values["POSTGRES_DB"], safe="")
    return f"postgresql://{user}:{password}@{host}:{port}/{database}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Import completed Metsaregister CSV snapshots into ForestIQ PostGIS.")
    parser.add_argument("inputs", type=Path, nargs="+", help="CSV file(s) or directories from export_wfs_csv.py")
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL"))
    parser.add_argument(
        "--compose-env",
        type=Path,
        help="Read POSTGRES_* values from a local Compose .env file instead of passing a database URL.",
    )
    parser.add_argument("--batch-size", type=int, default=5_000)
    parser.add_argument("--replace-staging", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="Validate files without connecting to Postgres.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.compose_env:
        try:
            args.database_url = load_compose_database_url(args.compose_env)
        except Exception as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 2
    if not args.dry_run and not args.database_url:
        print("ERROR: DATABASE_URL, --database-url or --compose-env is required", file=sys.stderr)
        return 2
    try:
        paths = discover_csv_inputs(args.inputs)
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    failures: list[str] = []
    for path in paths:
        try:
            snapshot = load_snapshot(path)
            count = validate_snapshot(snapshot)
            if args.dry_run:
                print(f"{snapshot.layer_name}: valid snapshot, {count} rows")
                continue
            generation = import_snapshot(
                snapshot,
                args.database_url,
                batch_size=args.batch_size,
                replace_staging=args.replace_staging,
            )
            print(f"{snapshot.layer_name}: imported {count} rows, generation {generation}")
        except Exception as exc:
            failures.append(f"{path}: {exc}")
            print(f"ERROR {path}: {exc}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
