#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import re
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import requests


DEFAULT_EELIS_WFS_URL = "https://gsavalik.envir.ee/geoserver/eelis/wfs"
RETRYABLE_STATUS = {429, 500, 502, 503, 504}
MAX_PAGE_SIZE = 100_000


class WfsDownloadError(RuntimeError):
    pass


@dataclass(frozen=True)
class PageResult:
    offset: int
    path: Path
    http_status: int
    transfer_bytes: int
    elapsed_seconds: float
    reused_existing_file: bool = False


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_number_matched(xml: str) -> int:
    try:
        root = ET.fromstring(xml)
        value = root.attrib.get("numberMatched")
    except ET.ParseError:
        match = re.search(r'numberMatched="([0-9]+)"', xml)
        value = match.group(1) if match else None
    if value is None or not value.isdigit():
        raise WfsDownloadError("WFS hits response did not contain numeric numberMatched")
    return int(value)


def validate_type_name(value: str) -> str:
    value = value.strip()
    if not re.fullmatch(r"[A-Za-z_][\w.-]*:[A-Za-z_][\w.-]*", value):
        raise ValueError("type name must use a namespace prefix, for example eelis:natura_elupaik")
    return value


def validate_property_list(values: Iterable[str]) -> str | None:
    fields = [value.strip() for value in values if value.strip()]
    if not fields:
        return None
    for field in fields:
        if not re.fullmatch(r"[A-Za-z_][\w.-]*", field):
            raise ValueError(f"invalid WFS property name: {field}")
    return ",".join(fields)


class EelisWfsDownloader:
    def __init__(
        self,
        *,
        source_url: str = DEFAULT_EELIS_WFS_URL,
        timeout_seconds: float = 900.0,
        max_attempts: int = 4,
        min_request_interval_seconds: float = 0.1,
        session: requests.Session | None = None,
    ) -> None:
        self.source_url = source_url
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max(1, max_attempts)
        self.min_request_interval_seconds = max(0.0, min_request_interval_seconds)
        self.session = session or requests.Session()

    def download_layer(
        self,
        *,
        type_name: str,
        sort_field: str,
        page_size: int,
        output_dir: Path,
        output_format: str = "csv",
        property_list: str | None = None,
        overwrite: bool = False,
        max_pages: int | None = None,
    ) -> tuple[int, int, list[PageResult]]:
        if page_size < 1 or page_size > MAX_PAGE_SIZE:
            raise ValueError(f"page_size must be between 1 and {MAX_PAGE_SIZE}")
        if max_pages is not None and max_pages < 1:
            raise ValueError("max_pages must be positive when provided")
        type_name = validate_type_name(type_name)
        if not re.fullmatch(r"[A-Za-z_][\w.-]*", sort_field):
            raise ValueError("sort field must be a single WFS property name")
        if output_format not in {"csv", "geopkg"}:
            raise ValueError("output_format must be csv or geopkg")

        output_dir.mkdir(parents=True, exist_ok=True)
        before_xml = self._fetch_hits_xml(type_name)
        (output_dir / "hits_before.xml").write_text(before_xml, encoding="utf-8")
        matched_before = parse_number_matched(before_xml)

        manifest_path = output_dir / "manifest.tsv"
        if not manifest_path.exists() or overwrite:
            manifest_path.write_text(
                "run_started\ttype_name\tsort_field\tpage_size\toffset\tfile\t"
                "http_code\ttransfer_bytes\tseconds\treused_existing_file\n",
                encoding="utf-8",
            )

        run_started = utc_now_iso()
        results: list[PageResult] = []
        extension = "csv" if output_format == "csv" else "gpkg"
        for page_number, offset in enumerate(range(0, matched_before, page_size), start=1):
            if max_pages is not None and page_number > max_pages:
                break
            page = output_dir / f"page_{offset:012d}.{extension}"
            if page.exists() and page.stat().st_size > 0 and not overwrite:
                result = PageResult(offset, page, 200, page.stat().st_size, 0.0, True)
            else:
                result = self._download_page(
                    type_name=type_name,
                    sort_field=sort_field,
                    page_size=page_size,
                    offset=offset,
                    output_format=output_format,
                    property_list=property_list,
                    page_path=page,
                )
            results.append(result)
            self._append_manifest(manifest_path, run_started, type_name, sort_field, page_size, result)
            if self.min_request_interval_seconds:
                time.sleep(self.min_request_interval_seconds)

        after_xml = self._fetch_hits_xml(type_name)
        (output_dir / "hits_after.xml").write_text(after_xml, encoding="utf-8")
        matched_after = parse_number_matched(after_xml)
        if matched_before != matched_after:
            raise WfsDownloadError(
                f"numberMatched changed during download: before={matched_before}, after={matched_after}"
            )
        return matched_before, matched_after, results

    def _fetch_hits_xml(self, type_name: str) -> str:
        response = self._request(
            {
                "service": "WFS",
                "version": "2.0.0",
                "request": "GetFeature",
                "typeNames": type_name,
                "resultType": "hits",
            },
            stream=False,
        )
        return response.text

    def _download_page(
        self,
        *,
        type_name: str,
        sort_field: str,
        page_size: int,
        offset: int,
        output_format: str,
        property_list: str | None,
        page_path: Path,
    ) -> PageResult:
        params = {
            "service": "WFS",
            "version": "2.0.0",
            "request": "GetFeature",
            "typeNames": type_name,
            "count": str(page_size),
            "startIndex": str(offset),
            "sortBy": f"{sort_field} A",
            "outputFormat": "csv" if output_format == "csv" else "geopkg",
        }
        if property_list:
            params["propertyName"] = property_list

        started = time.monotonic()
        response = self._request(params, stream=True)
        temp_path = page_path.with_suffix(page_path.suffix + ".tmp")
        transfer_bytes = 0
        with temp_path.open("wb") as handle:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    handle.write(chunk)
                    transfer_bytes += len(chunk)
        if transfer_bytes == 0:
            temp_path.unlink(missing_ok=True)
            raise WfsDownloadError(f"WFS returned an empty page at startIndex={offset}")
        temp_path.replace(page_path)
        return PageResult(
            offset=offset,
            path=page_path,
            http_status=response.status_code,
            transfer_bytes=transfer_bytes,
            elapsed_seconds=time.monotonic() - started,
        )

    def _request(self, params: dict[str, str], *, stream: bool) -> requests.Response:
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                response = self.session.get(
                    self.source_url,
                    params=params,
                    timeout=self.timeout_seconds,
                    stream=stream,
                    headers={"Accept-Encoding": "gzip, deflate"},
                )
                if response.status_code in RETRYABLE_STATUS:
                    raise requests.HTTPError(f"HTTP {response.status_code}", response=response)
                response.raise_for_status()
                return response
            except requests.RequestException as exc:
                last_error = exc
                retryable = (
                    not isinstance(exc, requests.HTTPError)
                    or exc.response is not None
                    and exc.response.status_code in RETRYABLE_STATUS
                )
                if attempt >= self.max_attempts or not retryable:
                    break
                time.sleep(min(8.0, 0.5 * (2 ** (attempt - 1))))
        raise WfsDownloadError(f"WFS request failed after {self.max_attempts} attempt(s): {last_error}")

    @staticmethod
    def _append_manifest(
        manifest_path: Path,
        run_started: str,
        type_name: str,
        sort_field: str,
        page_size: int,
        result: PageResult,
    ) -> None:
        with manifest_path.open("a", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
            writer.writerow(
                [
                    run_started,
                    type_name,
                    sort_field,
                    page_size,
                    result.offset,
                    result.path.name,
                    result.http_status,
                    result.transfer_bytes,
                    f"{result.elapsed_seconds:.3f}",
                    str(result.reused_existing_file).lower(),
                ]
            )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Download one EELIS WFS layer as resumable pages.")
    parser.add_argument("type_name", help="WFS typeName, for example eelis:natura_elupaik")
    parser.add_argument("sort_field", help="Stable WFS property used for sortBy, for example sys_id")
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--page-size", type=int, default=100_000)
    parser.add_argument("--format", choices=["csv", "geopkg"], default="csv")
    parser.add_argument("--property", action="append", default=[], help="CSV property to include; repeatable.")
    parser.add_argument("--source-url", default=DEFAULT_EELIS_WFS_URL)
    parser.add_argument("--timeout", type=float, default=900.0)
    parser.add_argument("--max-attempts", type=int, default=4)
    parser.add_argument("--max-pages", type=int, help="Safety cap for smoke tests and partial exports.")
    parser.add_argument("--min-request-interval", type=float, default=0.1)
    parser.add_argument("--overwrite", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        property_list = validate_property_list(args.property)
        downloader = EelisWfsDownloader(
            source_url=args.source_url,
            timeout_seconds=args.timeout,
            max_attempts=args.max_attempts,
            min_request_interval_seconds=args.min_request_interval,
        )
        before, after, pages = downloader.download_layer(
            type_name=args.type_name,
            sort_field=args.sort_field,
            page_size=args.page_size,
            output_dir=args.output_dir,
            output_format=args.format,
            property_list=property_list,
            overwrite=args.overwrite,
            max_pages=args.max_pages,
        )
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"numberMatched before: {before}")
    print(f"numberMatched after:  {after}")
    print(f"pages: {len(pages)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
