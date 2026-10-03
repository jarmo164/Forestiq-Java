from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "import_cleaned_contacts.py"
spec = importlib.util.spec_from_file_location("owner_cleaned_contacts", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def test_parse_line_keeps_primary_and_deduplicated_phone_list():
    row = module.parse_line("456;Mari Mets;+372 5555 0000,55550000,+372 5555 0000;\n", 1)

    assert row is not None
    assert row.code == "456"
    assert row.display_name == "Mari Mets"
    assert row.primary_phone == "+37255550000"
    assert row.all_phones == "+37255550000, 55550000"


def test_parse_line_ignores_rows_without_phone():
    assert module.parse_line("456;Mari Mets;;\n", 1) is None


def test_normalize_source_writes_only_phone_rows(tmp_path):
    source = tmp_path / "andmed_puhastatud.csv"
    output = tmp_path / "normalized.csv"
    source.write_text(
        "1;A;555, 555;\n"
        "2;B;;\n"
        "1;A2;666;\n",
        encoding="utf-8",
    )

    total, with_phone, duplicate_phone_rows, too_long_info = module.normalize_source(source, output)

    assert (total, with_phone, duplicate_phone_rows, too_long_info) == (3, 2, 1, 0)
    content = output.read_text(encoding="utf-8")
    assert "personal_or_registry_code,display_name,primary_phone,all_phones" in content
    assert "1,A,555,555" in content
    assert "2,B" not in content
