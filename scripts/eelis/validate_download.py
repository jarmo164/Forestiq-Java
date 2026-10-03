#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from download_wfs_layer import WfsDownloadError, parse_number_matched


DEFAULT_LAYERS_FILE = Path(__file__).with_name("approved_layers.json")


class EelisValidationError(RuntimeError):
    pass


@dataclass(frozen=True)
class LayerContract:
    key: str
    type_name: str
    sort_field: str
    page_size: int
    output_format: str
    storage_target: str
    production_import_enabled: bool

    @property
    def extension(self) -> str:
        return "gpkg" if self.output_format == "geopkg" else self.output_format


def load_contract(path: Path, key: str) -> LayerContract:
    raw = json.loads(path.read_text(encoding="utf-8"))
    layers: dict[str, Any] = raw.get("layers", {})
    if key not in layers:
        raise EelisValidationError(f"unknown approved EELIS layer key: {key}")
    layer = layers[key]
    if layer.get("storage_target") != "files_only":
        raise EelisValidationError("only files_only EELIS contracts are currently supported")
    if layer.get("production_import_enabled") is not False:
        raise EelisValidationError("EELIS production import must remain disabled until a consumer is approved")
    return LayerContract(
        key=key,
        type_name=str(layer["type_name"]),
        sort_field=str(layer["sort_field"]),
        page_size=int(layer["page_size"]),
        output_format=str(layer["format"]),
        storage_target=str(layer["storage_target"]),
        production_import_enabled=bool(layer["production_import_enabled"]),
    )


def validate_download(output_dir: Path, contract: LayerContract, *, allow_partial: bool = False) -> dict[str, int]:
    hits_before = parse_number_matched((output_dir / "hits_before.xml").read_text(encoding="utf-8"))
    hits_after = parse_number_matched((output_dir / "hits_after.xml").read_text(encoding="utf-8"))
    if hits_before != hits_after:
        raise EelisValidationError(f"numberMatched changed during download: before={hits_before}, after={hits_after}")

    manifest_path = output_dir / "manifest.tsv"
    if not manifest_path.exists():
        raise EelisValidationError("manifest.tsv is missing")
    with manifest_path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    if not rows:
        raise EelisValidationError("manifest.tsv contains no downloaded pages")

    expected_pages = math.ceil(hits_before / contract.page_size) if hits_before else 0
    if not allow_partial and len(rows) != expected_pages:
        raise EelisValidationError(f"expected {expected_pages} page(s), found {len(rows)}")
    if allow_partial and len(rows) > expected_pages:
        raise EelisValidationError(f"found {len(rows)} page(s), expected at most {expected_pages}")

    expected_offset = 0
    for row in rows:
        if row["type_name"] != contract.type_name:
            raise EelisValidationError(f"manifest type_name does not match contract: {row['type_name']}")
        if row["sort_field"] != contract.sort_field:
            raise EelisValidationError(f"manifest sort_field does not match contract: {row['sort_field']}")
        if int(row["page_size"]) != contract.page_size:
            raise EelisValidationError(f"manifest page_size does not match contract: {row['page_size']}")
        if int(row["offset"]) != expected_offset:
            raise EelisValidationError(f"unexpected page offset {row['offset']}; expected {expected_offset}")
        if row["http_code"] != "200":
            raise EelisValidationError(f"page offset {row['offset']} did not return HTTP 200")

        page_path = output_dir / row["file"]
        if page_path.suffix != "." + contract.extension:
            raise EelisValidationError(f"page has wrong extension for contract: {page_path.name}")
        if not page_path.exists() or page_path.stat().st_size <= 0:
            raise EelisValidationError(f"page file is missing or empty: {page_path.name}")
        if int(row["transfer_bytes"]) <= 0:
            raise EelisValidationError(f"page transfer_bytes is not positive: {row['offset']}")
        expected_offset += contract.page_size

    return {"matched": hits_before, "pages": len(rows)}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate an EELIS WFS paged download without importing it.")
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--layer", required=True, help="Key from scripts/eelis/approved_layers.json")
    parser.add_argument("--layers-file", type=Path, default=DEFAULT_LAYERS_FILE)
    parser.add_argument("--allow-partial", action="store_true", help="Allow --max-pages smoke downloads.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        result = validate_download(
            args.output_dir,
            load_contract(args.layers_file, args.layer),
            allow_partial=args.allow_partial,
        )
    except (OSError, KeyError, ValueError, WfsDownloadError, EelisValidationError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"validated layer={args.layer} matched={result['matched']} pages={result['pages']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
