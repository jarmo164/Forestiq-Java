from __future__ import annotations

import csv
import gzip
import hashlib
import json
import shutil
import sys
from pathlib import Path

import pytest

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from import_csv_postgis import (  # noqa: E402
    CSV_FIELDS,
    CsvImportError,
    load_compose_database_url,
    load_snapshot,
    validate_snapshot,
)


def write_snapshot(tmp_path: Path, *, duplicate: bool = False) -> Path:
    path = tmp_path / "metsaregister__test.csv"
    rows = [
        {
            "layer_name": "metsaregister:test",
            "feature_key": "1",
            "source_id": "1",
            "provider_feature_id": "test.1",
            "source_hash": "a" * 64,
            "properties_json": '{"name":"A","sys_id":1}',
            "geometry_geojson": '{"coordinates":[24.0,59.0],"type":"Point"}',
        },
        {
            "layer_name": "metsaregister:test",
            "feature_key": "1" if duplicate else "2",
            "source_id": "1" if duplicate else "2",
            "provider_feature_id": "test.2",
            "source_hash": "b" * 64,
            "properties_json": '{"name":"B","sys_id":2}',
            "geometry_geojson": "",
        },
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = {
        "version": 1,
        "status": "complete",
        "layer": "metsaregister:test",
        "identity_fields": ["sys_id"],
        "rows_exported": 2,
        "pages_exported": 1,
        "provider_number_matched": 2,
        "csv_sha256": digest,
    }
    (tmp_path / "metsaregister__test.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return path


def test_valid_snapshot_round_trip_contract(tmp_path: Path) -> None:
    path = write_snapshot(tmp_path)
    snapshot = load_snapshot(path)
    assert snapshot.layer_name == "metsaregister:test"
    assert snapshot.identity_fields == ("sys_id",)
    assert validate_snapshot(snapshot) == 2


def test_gzip_snapshot_preserves_original_manifest_integrity(tmp_path: Path) -> None:
    path = write_snapshot(tmp_path)
    gzip_path = tmp_path / "metsaregister__test.csv.gz"
    with path.open("rb") as source, gzip.open(gzip_path, "wb") as target:
        shutil.copyfileobj(source, target)
    path.unlink()

    snapshot = load_snapshot(gzip_path)
    assert snapshot.layer_name == "metsaregister:test"
    assert validate_snapshot(snapshot) == 2


def test_duplicate_key_fails_closed(tmp_path: Path) -> None:
    path = write_snapshot(tmp_path, duplicate=True)
    snapshot = load_snapshot(path)
    with pytest.raises(CsvImportError, match="duplicate feature_key"):
        validate_snapshot(snapshot)


def test_changed_csv_fails_hash_validation(tmp_path: Path) -> None:
    path = write_snapshot(tmp_path)
    path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
    snapshot = load_snapshot(path)
    with pytest.raises(CsvImportError, match="SHA-256"):
        validate_snapshot(snapshot)


def test_compose_env_builds_local_database_url_without_printing_secret(tmp_path: Path) -> None:
    env_path = tmp_path / ".env"
    env_path.write_text(
        "\n".join(
            [
                "POSTGRES_DB=forestiq",
                "POSTGRES_USER=forestiq",
                "POSTGRES_PASSWORD='pass word/@'",
                "POSTGRES_PORT=15432",
                "LOCAL_PORT_HOST=0.0.0.0",
            ]
        ),
        encoding="utf-8",
    )

    assert load_compose_database_url(env_path) == (
        "postgresql://forestiq:pass%20word%2F%40@localhost:15432/forestiq"
    )
