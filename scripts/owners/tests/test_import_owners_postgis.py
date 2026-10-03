from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "import_owners_postgis.py"
spec = importlib.util.spec_from_file_location("owner_bulk_import", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def args(path: Path, **overrides):
    values = dict(
        input=path,
        encoding="utf-8-sig",
        delimiter=None,
        sheet=None,
        map=[],
        max_rows=None,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def test_csv_auto_mapping_and_duplicate_count(tmp_path):
    source = tmp_path / "owners.csv"
    source.write_text(
        "Nimi;Registrikood;E-post\nA;123;a@example.ee\nB;123;b@example.ee\nC;456;c@example.ee\n",
        encoding="utf-8",
    )
    normalized, total, invalid, duplicate_rows, selected = module.normalize_source(args(source))
    try:
        assert total == 3
        assert invalid == 0
        assert duplicate_rows == 2
        assert selected["displayName"] == "Nimi"
        assert selected["personalOrRegistryCode"] == "Registrikood"
    finally:
        normalized.unlink(missing_ok=True)


def test_csv_validation_matches_web_required_fields(tmp_path):
    source = tmp_path / "owners.csv"
    source.write_text("Nimi;Registrikood;E-post\nA;;not-an-email\n", encoding="utf-8")
    normalized, total, invalid, duplicate_rows, _ = module.normalize_source(args(source))
    try:
        assert total == 1
        assert invalid == 1
        assert duplicate_rows == 0
        content = normalized.read_text(encoding="utf-8")
        assert "Isiku- või registrikood on kohustuslik." in content
        assert "E-posti formaat ei ole korrektne." in content
    finally:
        normalized.unlink(missing_ok=True)


def test_max_rows_is_optional_smoke_limit(tmp_path):
    source = tmp_path / "owners.csv"
    source.write_text("Nimi;Registrikood\nA;1\nB;2\nC;3\n", encoding="utf-8")
    normalized, total, invalid, duplicate_rows, _ = module.normalize_source(args(source, max_rows=2))
    try:
        assert (total, invalid, duplicate_rows) == (2, 0, 0)
    finally:
        normalized.unlink(missing_ok=True)
