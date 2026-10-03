from __future__ import annotations

import csv
import sys
from pathlib import Path
from typing import Any

import pytest
import requests

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from download_wfs_layer import (  # noqa: E402
    EelisWfsDownloader,
    WfsDownloadError,
    parse_number_matched,
    validate_property_list,
    validate_type_name,
)


class FakeResponse:
    def __init__(self, *, text: str = "", body: bytes = b"", status_code: int = 200) -> None:
        self.text = text
        self.body = body
        self.status_code = status_code

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)

    def iter_content(self, chunk_size: int) -> Any:
        del chunk_size
        yield self.body


class FakeSession:
    def __init__(self, responses: list[FakeResponse]) -> None:
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def get(
        self,
        _url: str,
        *,
        params: dict[str, str],
        timeout: float,
        stream: bool,
        headers: dict[str, str],
    ) -> FakeResponse:
        self.calls.append(
            {"params": dict(params), "timeout": timeout, "stream": stream, "headers": dict(headers)}
        )
        if not self.responses:
            raise AssertionError("Unexpected WFS request")
        return self.responses.pop(0)


def hits(count: int) -> FakeResponse:
    return FakeResponse(text=f'<wfs:FeatureCollection numberMatched="{count}" />')


def test_parse_number_matched_from_hits_xml() -> None:
    assert parse_number_matched('<wfs:FeatureCollection numberMatched="80435" />') == 80435


def test_parse_number_matched_rejects_missing_count() -> None:
    with pytest.raises(WfsDownloadError, match="numberMatched"):
        parse_number_matched("<root />")


def test_validates_type_name_and_properties() -> None:
    assert validate_type_name("eelis:natura_elupaik") == "eelis:natura_elupaik"
    assert validate_property_list(["sys_id", "invent_kpv"]) == "sys_id,invent_kpv"
    with pytest.raises(ValueError, match="namespace"):
        validate_type_name("natura_elupaik")
    with pytest.raises(ValueError, match="invalid WFS property"):
        validate_property_list(["sys_id", "shape;drop"])


def test_downloads_csv_pages_and_manifest(tmp_path: Path) -> None:
    session = FakeSession(
        [
            hits(3),
            FakeResponse(body=b"sys_id,name\n1,A\n2,B\n"),
            FakeResponse(body=b"sys_id,name\n3,C\n"),
            hits(3),
        ]
    )
    downloader = EelisWfsDownloader(
        source_url="https://example.test/wfs",
        max_attempts=1,
        min_request_interval_seconds=0,
        session=session,
    )

    before, after, pages = downloader.download_layer(
        type_name="eelis:test",
        sort_field="sys_id",
        page_size=2,
        output_dir=tmp_path,
        property_list="sys_id,name",
    )

    assert before == after == 3
    assert [page.offset for page in pages] == [0, 2]
    assert (tmp_path / "page_000000000000.csv").read_text(encoding="utf-8") == "sys_id,name\n1,A\n2,B\n"
    assert session.calls[1]["params"]["outputFormat"] == "csv"
    assert session.calls[1]["params"]["propertyName"] == "sys_id,name"
    assert session.calls[2]["params"]["startIndex"] == "2"

    with (tmp_path / "manifest.tsv").open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    assert rows[0]["type_name"] == "eelis:test"
    assert rows[0]["sort_field"] == "sys_id"
    assert rows[0]["reused_existing_file"] == "false"


def test_resume_reuses_existing_nonempty_page(tmp_path: Path) -> None:
    existing = tmp_path / "page_000000000000.csv"
    existing.write_text("already here\n", encoding="utf-8")
    session = FakeSession([hits(1), hits(1)])
    downloader = EelisWfsDownloader(
        source_url="https://example.test/wfs",
        max_attempts=1,
        min_request_interval_seconds=0,
        session=session,
    )

    _, _, pages = downloader.download_layer(
        type_name="eelis:test",
        sort_field="sys_id",
        page_size=1,
        output_dir=tmp_path,
    )

    assert pages[0].reused_existing_file is True
    assert len(session.calls) == 2
    assert existing.read_text(encoding="utf-8") == "already here\n"


def test_geopackage_uses_gpkg_output_format(tmp_path: Path) -> None:
    session = FakeSession([hits(1), FakeResponse(body=b"GPKG"), hits(1)])
    downloader = EelisWfsDownloader(
        source_url="https://example.test/wfs",
        max_attempts=1,
        min_request_interval_seconds=0,
        session=session,
    )

    _, _, pages = downloader.download_layer(
        type_name="eelis:test",
        sort_field="sys_id",
        page_size=1,
        output_dir=tmp_path,
        output_format="geopkg",
    )

    assert pages[0].path.name == "page_000000000000.gpkg"
    assert session.calls[1]["params"]["outputFormat"] == "geopkg"


def test_max_pages_allows_bounded_smoke_download(tmp_path: Path) -> None:
    session = FakeSession([hits(3), FakeResponse(body=b"sys_id\n1\n"), hits(3)])
    downloader = EelisWfsDownloader(
        source_url="https://example.test/wfs",
        max_attempts=1,
        min_request_interval_seconds=0,
        session=session,
    )

    _, _, pages = downloader.download_layer(
        type_name="eelis:test",
        sort_field="sys_id",
        page_size=1,
        output_dir=tmp_path,
        max_pages=1,
    )

    assert len(pages) == 1
    assert len(session.calls) == 3


def test_changed_number_matched_fails_closed(tmp_path: Path) -> None:
    session = FakeSession([hits(1), FakeResponse(body=b"row\n"), hits(2)])
    downloader = EelisWfsDownloader(
        source_url="https://example.test/wfs",
        max_attempts=1,
        min_request_interval_seconds=0,
        session=session,
    )

    with pytest.raises(WfsDownloadError, match="numberMatched changed"):
        downloader.download_layer(
            type_name="eelis:test",
            sort_field="sys_id",
            page_size=1,
            output_dir=tmp_path,
        )
