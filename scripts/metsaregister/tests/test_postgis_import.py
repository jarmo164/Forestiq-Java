from __future__ import annotations

import csv
import hashlib
import json
import os
import sys
import uuid
from pathlib import Path

import psycopg
import pytest

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from import_csv_postgis import (  # noqa: E402
    CSV_FIELDS,
    MIRROR_LOCK_NAME,
    CsvImportError,
    import_snapshot,
    load_snapshot,
    validate_snapshot,
)

TEST_POSTGIS_URL = os.getenv("TEST_POSTGIS_URL")
pytestmark = pytest.mark.skipif(not TEST_POSTGIS_URL, reason="TEST_POSTGIS_URL is not configured")


def reset_schema() -> None:
    with psycopg.connect(TEST_POSTGIS_URL, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("CREATE EXTENSION IF NOT EXISTS postgis")
            cur.execute("DROP TABLE IF EXISTS metsaregister_wfs_feature")
            cur.execute("DROP TABLE IF EXISTS metsaregister_wfs_generation")
            cur.execute("DROP TABLE IF EXISTS metsaregister_wfs_layer")
            cur.execute("""
                CREATE TABLE metsaregister_wfs_layer (
                    layer_name varchar(160) PRIMARY KEY,
                    approved boolean NOT NULL,
                    enabled boolean NOT NULL,
                    provider_present boolean NOT NULL DEFAULT false,
                    state varchar(32) NOT NULL,
                    identity_property varchar(120),
                    sort_property varchar(120),
                    current_generation_id uuid,
                    current_row_count bigint NOT NULL DEFAULT 0,
                    provider_number_matched bigint,
                    last_success_at timestamptz,
                    last_failure_at timestamptz,
                    last_error varchar(1000),
                    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)
            cur.execute("""
                CREATE TABLE metsaregister_wfs_generation (
                    id uuid PRIMARY KEY,
                    layer_name varchar(160) NOT NULL,
                    state varchar(24) NOT NULL,
                    next_start_index bigint NOT NULL DEFAULT 0,
                    pages_processed int NOT NULL DEFAULT 0,
                    rows_staged bigint NOT NULL DEFAULT 0,
                    provider_number_matched bigint,
                    completed_at timestamptz,
                    last_failure_at timestamptz,
                    last_error varchar(1000),
                    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)
            cur.execute("""
                CREATE TABLE metsaregister_wfs_feature (
                    generation_id uuid NOT NULL,
                    layer_name varchar(160) NOT NULL,
                    feature_key varchar(320) NOT NULL,
                    source_id varchar(320),
                    source_hash char(64) NOT NULL,
                    properties jsonb NOT NULL,
                    geometry geometry(Geometry, 4326),
                    PRIMARY KEY (generation_id, feature_key)
                )
            """)
            cur.execute(
                "INSERT INTO metsaregister_wfs_layer "
                "(layer_name, approved, enabled, state, identity_property, sort_property) "
                "VALUES ('metsaregister:test', true, true, 'READY', 'sys_id', 'sys_id')"
            )


def write_snapshot(
    tmp_path: Path,
    version: int,
    *,
    first_geometry: str = '{"coordinates":[24.0,59.0],"type":"Point"}',
) -> Path:
    path = tmp_path / "metsaregister__test.csv"
    rows = [
        {
            "layer_name": "metsaregister:test",
            "feature_key": str(version * 10 + 1),
            "source_id": str(version * 10 + 1),
            "provider_feature_id": f"test.{version * 10 + 1}",
            "source_hash": "a" * 64,
            "properties_json": json.dumps(
                {"sys_id": version * 10 + 1, "name": f"A-{version}"}, separators=(",", ":")
            ),
            "geometry_geojson": first_geometry,
        },
        {
            "layer_name": "metsaregister:test",
            "feature_key": str(version * 10 + 2),
            "source_id": str(version * 10 + 2),
            "provider_feature_id": f"test.{version * 10 + 2}",
            "source_hash": "b" * 64,
            "properties_json": json.dumps(
                {"sys_id": version * 10 + 2, "name": f"B-{version}"}, separators=(",", ":")
            ),
            "geometry_geojson": "",
        },
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    manifest = {
        "version": 1,
        "status": "complete",
        "layer": "metsaregister:test",
        "identity_fields": ["sys_id"],
        "rows_exported": 2,
        "pages_exported": 1,
        "provider_number_matched": 2,
        "csv_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }
    (tmp_path / "metsaregister__test.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_import_publishes_complete_generation_and_retires_previous(tmp_path: Path) -> None:
    reset_schema()
    first_path = write_snapshot(tmp_path, 1)
    first = load_snapshot(first_path)
    assert validate_snapshot(first) == 2
    first_generation = import_snapshot(first, TEST_POSTGIS_URL, batch_size=1)

    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT current_generation_id, current_row_count, state "
            "FROM metsaregister_wfs_layer WHERE layer_name='metsaregister:test'"
        )
        current_id, current_rows, state = cur.fetchone()
        assert str(current_id) == first_generation
        assert current_rows == 2
        assert state == "HEALTHY"
        cur.execute(
            "SELECT state, rows_staged FROM metsaregister_wfs_generation WHERE id=%s",
            (first_generation,),
        )
        assert cur.fetchone() == ("PUBLISHED", 2)
        cur.execute(
            "SELECT ST_AsText(geometry) FROM metsaregister_wfs_feature "
            "WHERE generation_id=%s AND geometry IS NOT NULL",
            (first_generation,),
        )
        assert cur.fetchone()[0] == "POINT(24 59)"

    second_path = write_snapshot(tmp_path, 2)
    second = load_snapshot(second_path)
    assert validate_snapshot(second) == 2
    second_generation = import_snapshot(second, TEST_POSTGIS_URL, batch_size=2)

    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT current_generation_id, current_row_count "
            "FROM metsaregister_wfs_layer WHERE layer_name='metsaregister:test'"
        )
        current_id, current_rows = cur.fetchone()
        assert str(current_id) == second_generation
        assert current_rows == 2
        cur.execute("SELECT state FROM metsaregister_wfs_generation WHERE id=%s", (first_generation,))
        assert cur.fetchone()[0] == "RETIRED"
        cur.execute("SELECT state FROM metsaregister_wfs_generation WHERE id=%s", (second_generation,))
        assert cur.fetchone()[0] == "PUBLISHED"


def test_reimport_same_snapshot_keeps_current_generation_duplicate_free(tmp_path: Path) -> None:
    reset_schema()
    snapshot = load_snapshot(write_snapshot(tmp_path, 1))
    first_generation = import_snapshot(snapshot, TEST_POSTGIS_URL, batch_size=1)
    second_generation = import_snapshot(snapshot, TEST_POSTGIS_URL, batch_size=2)

    assert first_generation != second_generation
    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT current_generation_id, current_row_count "
            "FROM metsaregister_wfs_layer WHERE layer_name='metsaregister:test'"
        )
        current_id, current_rows = cur.fetchone()
        assert str(current_id) == second_generation
        assert current_rows == 2
        cur.execute(
            "SELECT count(*), count(DISTINCT feature_key) "
            "FROM metsaregister_wfs_feature WHERE generation_id=%s",
            (second_generation,),
        )
        assert cur.fetchone() == (2, 2)
        cur.execute(
            "SELECT count(*) FROM metsaregister_wfs_generation "
            "WHERE layer_name='metsaregister:test' AND state='PUBLISHED'"
        )
        assert cur.fetchone()[0] == 1
        cur.execute("SELECT state FROM metsaregister_wfs_generation WHERE id=%s", (first_generation,))
        assert cur.fetchone()[0] == "RETIRED"


def test_import_refuses_to_race_java_or_another_csv_mirror_run(tmp_path: Path) -> None:
    reset_schema()
    snapshot = load_snapshot(write_snapshot(tmp_path, 1))

    with psycopg.connect(TEST_POSTGIS_URL, autocommit=True) as blocker:
        with blocker.cursor() as cur:
            cur.execute("SELECT pg_advisory_lock(hashtext(%s))", (MIRROR_LOCK_NAME,))
        try:
            with pytest.raises(CsvImportError, match="mirror lock is already held"):
                import_snapshot(snapshot, TEST_POSTGIS_URL, batch_size=2)
        finally:
            with blocker.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(hashtext(%s))", (MIRROR_LOCK_NAME,))

    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT count(*) FROM metsaregister_wfs_generation WHERE layer_name='metsaregister:test'"
        )
        assert cur.fetchone()[0] == 0


def test_malformed_geometry_never_replaces_last_known_good_generation(tmp_path: Path) -> None:
    reset_schema()
    good = load_snapshot(write_snapshot(tmp_path, 1))
    good_generation = import_snapshot(good, TEST_POSTGIS_URL, batch_size=2)

    malformed = load_snapshot(
        write_snapshot(
            tmp_path,
            2,
            first_geometry='{"coordinates":[24.0,59.0],"type":"DefinitelyNotAGeoJsonGeometry"}',
        )
    )
    assert validate_snapshot(malformed) == 2
    with pytest.raises(Exception):
        import_snapshot(malformed, TEST_POSTGIS_URL, batch_size=2)

    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT current_generation_id, current_row_count "
            "FROM metsaregister_wfs_layer WHERE layer_name='metsaregister:test'"
        )
        current_id, current_rows = cur.fetchone()
        assert str(current_id) == good_generation
        assert current_rows == 2
        cur.execute(
            "SELECT count(*) FROM metsaregister_wfs_generation "
            "WHERE layer_name='metsaregister:test' AND state='STAGING' AND last_error IS NOT NULL"
        )
        assert cur.fetchone()[0] == 1


def test_existing_staging_generation_requires_explicit_replacement(tmp_path: Path) -> None:
    reset_schema()
    abandoned = str(uuid.uuid4())
    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO metsaregister_wfs_generation "
            "(id, layer_name, state, rows_staged, next_start_index) "
            "VALUES (%s, 'metsaregister:test', 'STAGING', 123, 123)",
            (abandoned,),
        )
        conn.commit()

    snapshot = load_snapshot(write_snapshot(tmp_path, 1))
    with pytest.raises(CsvImportError, match="already has staging generation"):
        import_snapshot(snapshot, TEST_POSTGIS_URL)

    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT state, rows_staged FROM metsaregister_wfs_generation WHERE id=%s",
            (abandoned,),
        )
        assert cur.fetchone() == ("STAGING", 123)

    published = import_snapshot(snapshot, TEST_POSTGIS_URL, replace_staging=True, batch_size=2)
    with psycopg.connect(TEST_POSTGIS_URL) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM metsaregister_wfs_generation WHERE id=%s", (abandoned,))
        assert cur.fetchone()[0] == 0
        cur.execute(
            "SELECT current_generation_id FROM metsaregister_wfs_layer WHERE layer_name='metsaregister:test'"
        )
        assert str(cur.fetchone()[0]) == published
