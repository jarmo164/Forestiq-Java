#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sqlite3
import sys
import time
from pathlib import Path
from typing import Any

import requests

try:
    from .common import atomic_write_json, canonical_json, feature_key, layer_config, layer_slug, load_layer_manifest, normalize_layer_name, source_hash, utc_now_iso
except ImportError:
    from common import atomic_write_json, canonical_json, feature_key, layer_config, layer_slug, load_layer_manifest, normalize_layer_name, source_hash, utc_now_iso

CSV_FIELDS = ("layer_name", "feature_key", "source_id", "provider_feature_id", "source_hash", "properties_json", "geometry_geojson")
RETRYABLE_STATUS = {429, 500, 502, 503, 504}


class WfsExportError(RuntimeError):
    pass


def parse_number_matched(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class WfsExporter:
    def __init__(self, source_url: str, output_dir: Path, *, page_size: int = 10_000, timeout_seconds: float = 60.0, max_attempts: int = 4, min_request_interval: float = 0.1, session: requests.Session | None = None) -> None:
        if page_size < 1:
            raise ValueError("page_size must be positive")
        self.source_url = source_url
        self.output_dir = output_dir
        self.page_size = page_size
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max(1, max_attempts)
        self.min_request_interval = max(0.0, min_request_interval)
        self.session = session or requests.Session()

    def export_layer(self, config: dict[str, Any], *, overwrite: bool = False, resume: bool = True) -> Path:
        layer_name = normalize_layer_name(config["name"])
        identity_fields = [str(value) for value in config["identity"]]
        sort_fields = [field for field in identity_fields if not field.startswith("__")]
        sort_by = ",".join(f"{field} A" for field in sort_fields)
        slug = layer_slug(layer_name)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        final_csv = self.output_dir / f"{slug}.csv"
        partial_csv = self.output_dir / f"{slug}.partial.csv"
        manifest_path = self.output_dir / f"{slug}.manifest.json"
        key_db_path = self.output_dir / f"{slug}.keys.sqlite"

        if final_csv.exists() and not overwrite:
            raise WfsExportError(f"{final_csv} already exists; use --overwrite to replace it")
        if overwrite:
            for path in (final_csv, partial_csv, manifest_path, key_db_path):
                path.unlink(missing_ok=True)
        if partial_csv.exists() and not resume:
            raise WfsExportError(f"{partial_csv} exists; use resume or --overwrite")

        start_index, existing_rows = self._prepare_partial(partial_csv, key_db_path, layer_name)
        previous: dict[str, Any] = {}
        if manifest_path.exists() and start_index > 0:
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
            if previous.get("layer") != layer_name:
                raise WfsExportError("Partial manifest belongs to another layer")
        expected_count = previous.get("provider_number_matched") if isinstance(previous.get("provider_number_matched"), int) else None
        allow_provider_count_growth = start_index > 0 and expected_count is not None
        state = {
            "version": 1,
            "status": "partial",
            "layer": layer_name,
            "source_url": self.source_url,
            "identity_fields": identity_fields,
            "sort_by": sort_fields,
            "page_size": self.page_size,
            "started_at": previous.get("started_at") or utc_now_iso(),
            "updated_at": utc_now_iso(),
            "completed_at": None,
            "next_start_index": start_index,
            "rows_exported": existing_rows,
            "pages_exported": int(previous.get("pages_exported") or 0),
            "provider_number_matched": expected_count,
            "csv_file": final_csv.name,
        }
        atomic_write_json(manifest_path, state)

        append = partial_csv.exists() and partial_csv.stat().st_size > 0
        with partial_csv.open("a", encoding="utf-8", newline="") as handle, sqlite3.connect(key_db_path) as key_db:
            writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
            if not append:
                writer.writeheader()
                handle.flush()
                os.fsync(handle.fileno())
            next_start = start_index
            while True:
                payload = self._fetch_page(layer_name, sort_by, next_start)
                features = payload.get("features")
                if not isinstance(features, list):
                    raise WfsExportError(f"{layer_name}: WFS response has no feature list")
                number_matched = parse_number_matched(payload.get("numberMatched"))
                if number_matched is not None:
                    if expected_count is None:
                        expected_count = number_matched
                    elif allow_provider_count_growth and number_matched > expected_count:
                        expected_count = number_matched
                        allow_provider_count_growth = False
                    elif expected_count != number_matched:
                        raise WfsExportError(f"{layer_name}: numberMatched changed from {expected_count} to {number_matched}")

                rows: list[dict[str, str]] = []
                for feature in features:
                    if not isinstance(feature, dict):
                        raise WfsExportError(f"{layer_name}: invalid feature payload")
                    properties = feature.get("properties")
                    if not isinstance(properties, dict):
                        raise WfsExportError(f"{layer_name}: feature has no properties object")
                    properties_for_key = dict(properties)
                    properties_for_key["__feature_id"] = str(feature.get("id") or "")
                    try:
                        key = feature_key(properties_for_key, identity_fields)
                    except ValueError as exc:
                        raise WfsExportError(f"{layer_name}: {exc}") from exc
                    geometry = feature.get("geometry")
                    if geometry is not None and not isinstance(geometry, dict):
                        raise WfsExportError(f"{layer_name}: feature {key} has invalid geometry")
                    rows.append({
                        "layer_name": layer_name,
                        "feature_key": key,
                        "source_id": key,
                        "provider_feature_id": str(feature.get("id") or ""),
                        "source_hash": source_hash(feature),
                        "properties_json": canonical_json(properties),
                        "geometry_geojson": "" if geometry is None else canonical_json(geometry),
                    })

                self._reserve_keys(key_db, layer_name, rows)
                writer.writerows(rows)
                handle.flush()
                os.fsync(handle.fileno())
                key_db.commit()
                records = len(rows)
                new_start = next_start + records
                state["pages_exported"] += 1
                state["rows_exported"] = new_start
                state["next_start_index"] = new_start
                state["provider_number_matched"] = expected_count
                state["updated_at"] = utc_now_iso()
                atomic_write_json(manifest_path, state)

                complete = (expected_count is not None and new_start >= expected_count) or (expected_count is None and records < self.page_size)
                if complete:
                    if expected_count is not None and new_start != expected_count:
                        raise WfsExportError(f"{layer_name}: exported {new_start} rows but provider reported {expected_count}")
                    break
                if records == 0:
                    raise WfsExportError(f"{layer_name}: pagination made no progress at startIndex={next_start}")
                next_start = new_start
                if self.min_request_interval:
                    time.sleep(self.min_request_interval)

        os.replace(partial_csv, final_csv)
        state["status"] = "complete"
        state["completed_at"] = utc_now_iso()
        state["updated_at"] = state["completed_at"]
        state["csv_sha256"] = file_sha256(final_csv)
        atomic_write_json(manifest_path, state)
        key_db_path.unlink(missing_ok=True)
        return final_csv

    def _prepare_partial(self, partial_csv: Path, key_db_path: Path, layer_name: str) -> tuple[int, int]:
        key_db_path.unlink(missing_ok=True)
        with sqlite3.connect(key_db_path) as db:
            db.execute("CREATE TABLE feature_key (value TEXT PRIMARY KEY)")
            if not partial_csv.exists() or partial_csv.stat().st_size == 0:
                return 0, 0
            count = 0
            with partial_csv.open("r", encoding="utf-8", newline="") as handle:
                reader = csv.DictReader(handle)
                if tuple(reader.fieldnames or ()) != CSV_FIELDS:
                    raise WfsExportError(f"{partial_csv} has an incompatible CSV header")
                for row in reader:
                    if row["layer_name"] != layer_name:
                        raise WfsExportError(f"{partial_csv} contains another layer")
                    try:
                        db.execute("INSERT INTO feature_key(value) VALUES (?)", (row["feature_key"],))
                    except sqlite3.IntegrityError as exc:
                        raise WfsExportError(f"{partial_csv} contains duplicate feature key {row['feature_key']!r}") from exc
                    count += 1
            db.commit()
            return count, count

    @staticmethod
    def _reserve_keys(db: sqlite3.Connection, layer_name: str, rows: list[dict[str, str]]) -> None:
        try:
            db.executemany("INSERT INTO feature_key(value) VALUES (?)", ((row["feature_key"],) for row in rows))
        except sqlite3.IntegrityError as exc:
            db.rollback()
            raise WfsExportError(f"{layer_name}: duplicate/unstable feature identity detected") from exc

    def _fetch_page(self, layer_name: str, sort_by: str, start_index: int) -> dict[str, Any]:
        params = {
            "service": "WFS", "version": "2.0.0", "request": "GetFeature", "typeNames": layer_name,
            "outputFormat": "application/json", "srsName": "EPSG:4326", "count": str(self.page_size),
            "startIndex": str(start_index), "sortBy": sort_by,
        }
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                response = self.session.get(self.source_url, params=params, timeout=self.timeout_seconds)
                if response.status_code in RETRYABLE_STATUS:
                    raise requests.HTTPError(f"HTTP {response.status_code}", response=response)
                response.raise_for_status()
                payload = response.json()
                if not isinstance(payload, dict):
                    raise WfsExportError("WFS response is not a JSON object")
                return payload
            except (requests.RequestException, ValueError) as exc:
                last_error = exc
                retryable = not isinstance(exc, requests.HTTPError) or (exc.response is not None and exc.response.status_code in RETRYABLE_STATUS)
                if attempt >= self.max_attempts or not retryable:
                    break
                time.sleep(min(8.0, 0.5 * (2 ** (attempt - 1))))
        raise WfsExportError(f"{layer_name}: WFS page startIndex={start_index} failed after {self.max_attempts} attempt(s): {last_error}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Export Metsaregister WFS layers to resumable CSV snapshots.")
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--layer", action="append", help="Layer name; repeat for multiple layers.")
    selection.add_argument("--all", action="store_true", help="Export every enabled layer in layers.json.")
    parser.add_argument("--output-dir", type=Path, default=Path("data/metsaregister"))
    parser.add_argument("--manifest", type=Path, help="Override scripts/metsaregister/layers.json")
    parser.add_argument("--source-url", help="Override WFS URL from layers.json")
    parser.add_argument("--page-size", type=int, default=10_000)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--max-attempts", type=int, default=4)
    parser.add_argument("--min-request-interval", type=float, default=0.1)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--no-resume", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    manifest = load_layer_manifest(args.manifest)
    source_url = args.source_url or manifest["source_url"]
    selected = [layer for layer in manifest["layers"] if layer.get("approved", True) and layer.get("enabled", True)] if args.all else [layer_config(manifest, name) for name in args.layer]
    exporter = WfsExporter(source_url, args.output_dir, page_size=args.page_size, timeout_seconds=args.timeout, max_attempts=args.max_attempts, min_request_interval=args.min_request_interval)
    failures: list[str] = []
    for config in selected:
        name = config["name"]
        if not config.get("approved", True) or not config.get("enabled", True):
            failures.append(f"{name}: layer is disabled/not approved in layers.json")
            continue
        try:
            path = exporter.export_layer(config, overwrite=args.overwrite, resume=not args.no_resume)
            print(f"{name}: exported to {path}")
        except Exception as exc:
            failures.append(f"{name}: {exc}")
            print(f"ERROR {name}: {exc}", file=sys.stderr)
    if failures:
        print(f"{len(failures)} layer export(s) failed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
