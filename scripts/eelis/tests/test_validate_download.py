from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import pytest

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from validate_download import (  # noqa: E402
    EelisValidationError,
    load_contract,
    validate_download,
)


def write_contract(path: Path) -> None:
    path.write_text(
        json.dumps(
            {
                "layers": {
                    "test_attrs": {
                        "type_name": "eelis:test",
                        "sort_field": "sys_id",
                        "format": "csv",
                        "page_size": 2,
                        "storage_target": "files_only",
                        "production_import_enabled": False,
                    }
                }
            }
        ),
        encoding="utf-8",
    )


def write_download(output_dir: Path, *, count: int = 3, rows: list[dict[str, str]] | None = None) -> None:
    output_dir.mkdir()
    (output_dir / "hits_before.xml").write_text(f'<wfs:FeatureCollection numberMatched="{count}" />', encoding="utf-8")
    (output_dir / "hits_after.xml").write_text(f'<wfs:FeatureCollection numberMatched="{count}" />', encoding="utf-8")
    rows = rows or [
        {
            "run_started": "2026-09-09T00:00:00Z",
            "type_name": "eelis:test",
            "sort_field": "sys_id",
            "page_size": "2",
            "offset": "0",
            "file": "page_000000000000.csv",
            "http_code": "200",
            "transfer_bytes": "12",
            "seconds": "0.1",
            "reused_existing_file": "false",
        },
        {
            "run_started": "2026-09-09T00:00:00Z",
            "type_name": "eelis:test",
            "sort_field": "sys_id",
            "page_size": "2",
            "offset": "2",
            "file": "page_000000000002.csv",
            "http_code": "200",
            "transfer_bytes": "8",
            "seconds": "0.1",
            "reused_existing_file": "false",
        },
    ]
    with (output_dir / "manifest.tsv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    for row in rows:
        (output_dir / row["file"]).write_text("sys_id\n1\n", encoding="utf-8")


def test_load_contract_rejects_production_import(tmp_path: Path) -> None:
    layers = tmp_path / "layers.json"
    layers.write_text(
        json.dumps(
            {
                "layers": {
                    "bad": {
                        "type_name": "eelis:test",
                        "sort_field": "sys_id",
                        "format": "csv",
                        "page_size": 2,
                        "storage_target": "files_only",
                        "production_import_enabled": True,
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(EelisValidationError, match="production import"):
        load_contract(layers, "bad")


def test_validate_download_accepts_complete_manifest(tmp_path: Path) -> None:
    layers = tmp_path / "layers.json"
    write_contract(layers)
    output = tmp_path / "download"
    write_download(output)

    result = validate_download(output, load_contract(layers, "test_attrs"))

    assert result == {"matched": 3, "pages": 2}


def test_validate_download_rejects_partial_without_explicit_smoke_flag(tmp_path: Path) -> None:
    layers = tmp_path / "layers.json"
    write_contract(layers)
    output = tmp_path / "download"
    write_download(output, count=3, rows=[
        {
            "run_started": "2026-09-09T00:00:00Z",
            "type_name": "eelis:test",
            "sort_field": "sys_id",
            "page_size": "2",
            "offset": "0",
            "file": "page_000000000000.csv",
            "http_code": "200",
            "transfer_bytes": "12",
            "seconds": "0.1",
            "reused_existing_file": "false",
        }
    ])

    with pytest.raises(EelisValidationError, match="expected 2 page"):
        validate_download(output, load_contract(layers, "test_attrs"))

    assert validate_download(output, load_contract(layers, "test_attrs"), allow_partial=True) == {
        "matched": 3,
        "pages": 1,
    }


def test_validate_download_rejects_wrong_contract_metadata(tmp_path: Path) -> None:
    layers = tmp_path / "layers.json"
    write_contract(layers)
    output = tmp_path / "download"
    write_download(output, rows=[
        {
            "run_started": "2026-09-09T00:00:00Z",
            "type_name": "eelis:other",
            "sort_field": "sys_id",
            "page_size": "2",
            "offset": "0",
            "file": "page_000000000000.csv",
            "http_code": "200",
            "transfer_bytes": "12",
            "seconds": "0.1",
            "reused_existing_file": "false",
        }
    ])

    with pytest.raises(EelisValidationError, match="type_name"):
        validate_download(output, load_contract(layers, "test_attrs"), allow_partial=True)
