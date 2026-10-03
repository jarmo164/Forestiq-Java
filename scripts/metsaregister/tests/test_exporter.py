from __future__ import annotations

import csv
import json
import sys
from pathlib import Path
from typing import Any

import pytest
import requests

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from export_wfs_csv import CSV_FIELDS, WfsExportError, WfsExporter  # noqa: E402


class FakeResponse:
    def __init__(self, payload: dict[str, Any], status_code: int = 200) -> None:
        self._payload = payload
        self.status_code = status_code

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)

    def json(self) -> dict[str, Any]:
        return self._payload


class FakeSession:
    def __init__(self, responses: list[Any]) -> None:
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def get(self, _url: str, *, params: dict[str, Any], timeout: float) -> FakeResponse:
        self.calls.append({"params": dict(params), "timeout": timeout})
        if not self.responses:
            raise AssertionError("Unexpected WFS request")
        value = self.responses.pop(0)
        if isinstance(value, Exception):
            raise value
        if isinstance(value, FakeResponse):
            return value
        return FakeResponse(value)


def feature(sys_id: int, x: float | None = None) -> dict[str, Any]:
    geometry = None if x is None else {"type": "Point", "coordinates": [x, 59.0]}
    return {
        "type": "Feature",
        "id": f"test.{sys_id}",
        "properties": {"sys_id": sys_id, "name": f"row-{sys_id}"},
        "geometry": geometry,
    }


def config() -> dict[str, Any]:
    return {"name": "metsaregister:test", "identity": ["sys_id"], "approved": True, "enabled": True}


def test_exports_multiple_pages_and_manifest(tmp_path: Path) -> None:
    session = FakeSession([
        {"numberMatched": 3, "features": [feature(1, 24.0), feature(2)]},
        {"numberMatched": 3, "features": [feature(3, 25.0)]},
    ])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=session,
    )
    path = exporter.export_layer(config())

    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert [row["feature_key"] for row in rows] == ["1", "2", "3"]
    assert tuple(rows[0].keys()) == CSV_FIELDS
    assert rows[1]["geometry_geojson"] == ""
    manifest = json.loads((tmp_path / "metsaregister__test.manifest.json").read_text(encoding="utf-8"))
    assert manifest["status"] == "complete"
    assert manifest["rows_exported"] == 3
    assert manifest["provider_number_matched"] == 3
    assert session.calls[0]["params"]["startIndex"] == "0"
    assert session.calls[1]["params"]["startIndex"] == "2"
    assert session.calls[0]["params"]["sortBy"] == "sys_id A"


def test_resume_rebuilds_checkpoint_from_partial_csv(tmp_path: Path) -> None:
    first_session = FakeSession([
        {"numberMatched": 3, "features": [feature(1), feature(2)]},
        requests.ConnectionError("provider down"),
    ])
    first = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=first_session,
    )
    with pytest.raises(WfsExportError):
        first.export_layer(config())
    assert (tmp_path / "metsaregister__test.partial.csv").exists()

    second_session = FakeSession([{"numberMatched": 3, "features": [feature(3)]}])
    second = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=second_session,
    )
    path = second.export_layer(config())
    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert [row["feature_key"] for row in rows] == ["1", "2", "3"]
    assert second_session.calls[0]["params"]["startIndex"] == "2"


def test_resume_allows_provider_count_growth(tmp_path: Path) -> None:
    first_session = FakeSession([
        {"numberMatched": 3, "features": [feature(1), feature(2)]},
        requests.ConnectionError("provider down"),
    ])
    first = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=first_session,
    )
    with pytest.raises(WfsExportError):
        first.export_layer(config())

    second_session = FakeSession([{"numberMatched": 4, "features": [feature(3), feature(4)]}])
    second = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=second_session,
    )
    path = second.export_layer(config())

    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert [row["feature_key"] for row in rows] == ["1", "2", "3", "4"]
    manifest = json.loads((tmp_path / "metsaregister__test.manifest.json").read_text(encoding="utf-8"))
    assert manifest["provider_number_matched"] == 4


def test_duplicate_identity_fails_before_csv_publication(tmp_path: Path) -> None:
    session = FakeSession([{"numberMatched": 2, "features": [feature(1), feature(1)]}])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=session,
    )
    with pytest.raises(WfsExportError, match="duplicate/unstable"):
        exporter.export_layer(config())
    assert not (tmp_path / "metsaregister__test.csv").exists()


def test_feature_id_can_participate_in_identity_without_sort_by(tmp_path: Path) -> None:
    session = FakeSession([{"numberMatched": 1, "features": [feature(1)]}])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=10,
        max_attempts=1,
        min_request_interval=0,
        session=session,
    )

    path = exporter.export_layer({**config(), "identity": ["sys_id", "__feature_id"]})

    with path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert rows[0]["feature_key"] == "6:sys_id=1:1;12:__feature_id=6:test.1;"
    assert session.calls[0]["params"]["sortBy"] == "sys_id A"


def test_retries_transient_http_503_then_completes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("export_wfs_csv.time.sleep", lambda _seconds: None)
    session = FakeSession([
        FakeResponse({}, status_code=503),
        {"numberMatched": 1, "features": [feature(1)]},
    ])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=10,
        max_attempts=2,
        min_request_interval=0,
        session=session,
    )

    path = exporter.export_layer(config())

    assert path.exists()
    assert len(session.calls) == 2
    assert session.calls[0]["params"]["startIndex"] == "0"
    assert session.calls[1]["params"]["startIndex"] == "0"


def test_number_matched_change_fails_closed(tmp_path: Path) -> None:
    session = FakeSession([
        {"numberMatched": 3, "features": [feature(1), feature(2)]},
        {"numberMatched": 4, "features": [feature(3), feature(4)]},
    ])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=session,
    )

    with pytest.raises(WfsExportError, match="numberMatched changed"):
        exporter.export_layer(config())

    assert not (tmp_path / "metsaregister__test.csv").exists()
    assert (tmp_path / "metsaregister__test.partial.csv").exists()


def test_non_progressing_page_fails_closed(tmp_path: Path) -> None:
    session = FakeSession([{"numberMatched": 3, "features": []}])
    exporter = WfsExporter(
        "https://example.test/wfs",
        tmp_path,
        page_size=2,
        max_attempts=1,
        min_request_interval=0,
        session=session,
    )

    with pytest.raises(WfsExportError, match="pagination made no progress"):
        exporter.export_layer(config())

    assert not (tmp_path / "metsaregister__test.csv").exists()
