from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Iterable

from forestiq_ingest.http.client import HttpRequestContext, HttpSourceClient
from forestiq_ingest.pipelines.base import PipelineContext, SourceMetadata, SourceRecord, fingerprint
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode


PERSONAL_CODE = re.compile(r"\b(\d{11})\b")
NAME_AND_CODE = re.compile(
    r"surnud\s+([A-ZÄÖÜÕŠŽa-zäöüõšž][^()]*?)\s*\(isikukood\s+(\d{11})\)",
    re.IGNORECASE,
)
DATE_PATTERN = re.compile(r"(\d{1,2}\.\d{1,2}\.\d{4})")
DEADLINE_PATTERN = re.compile(
    r"tõestamise kavandatav tähtpäev on (\d{1,2}\.\d{1,2}\.\d{4})",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class InheritanceNoticeConfig:
    uri_template: str
    lookback_days: int = 31
    overlap_days: int = 1
    max_days_per_run: int = 31


class InheritanceNoticeAdapter:
    spec = SourceAdapterSpec(
        job_key="INHERITANCE_NOTICES",
        source_key="official_inheritance_notices",
        dataset_key="inheritance-notices",
        mode=SourceMode.INCREMENTAL,
        pipeline_factory=object,
        config_keys=("INHERITANCE_SOURCE_URI_TEMPLATE",),
        credential_keys=("INHERITANCE_SOURCE_API_TOKEN",),
    )

    def __init__(self, config: InheritanceNoticeConfig, http_client: HttpSourceClient, api_token: str | None = None):
        self.config = config
        self.http_client = http_client
        self.api_token = api_token

    def source_identity(self, context: PipelineContext) -> SourceIdentity:
        return SourceIdentity(self.spec.source_key, self.spec.dataset_key, version=context.source_version)

    def days_to_fetch(self, last_completed: date | None, today: date) -> list[date]:
        if last_completed is None:
            start = today - timedelta(days=self.config.lookback_days)
        else:
            start = last_completed - timedelta(days=self.config.overlap_days)
        days = []
        current = start
        while current <= today and len(days) < self.config.max_days_per_run:
            days.append(current)
            current += timedelta(days=1)
        return days

    def acquire(self, context: PipelineContext, days: Iterable[date]) -> Iterable[Iterable[dict]]:
        headers = {"Authorization": f"Bearer {self.api_token}"} if self.api_token else {}
        for batch_no, day in enumerate(days, start=1):
            request_context = HttpRequestContext(
                str(context.run_id),
                context.job_key,
                self.spec.source_key,
                batch_no,
                day.isoformat(),
            )
            path = self.config.uri_template.format(day.year, day.month, day.day)
            body = self.http_client.get_bytes(path, headers=headers, context=request_context)
            prefix = body.lstrip()[:64].lower()
            if prefix.startswith(b"<!doctype html") or prefix.startswith(b"<html"):
                raise ValueError("official inheritance source returned HTML instead of XML")
            yield [{"announcement_date": day.isoformat(), "xml": body}]

    def normalize(self, context: PipelineContext, rows: Iterable[dict]) -> Iterable[SourceRecord]:
        for row in rows:
            announcement_date = date.fromisoformat(str(row["announcement_date"]))
            xml = row["xml"]
            xml_bytes = xml if isinstance(xml, bytes) else str(xml).encode("utf-8")
            for parsed in _parse_notices(xml_bytes, announcement_date):
                payload = {
                    "notice_number": parsed["notice_number"],
                    "source_url": parsed["source_url"],
                    "announcement_date": parsed["announcement_date"],
                    "death_date": parsed["death_date"],
                    "certification_deadline": parsed["certification_deadline"],
                    "notary_name": parsed["notary_name"],
                    "notary_phone": parsed["notary_phone"],
                    "personal_code": parsed["personal_code"],
                    "deceased_name": parsed["deceased_name"],
                }
                yield SourceRecord(
                    SourceMetadata(
                        self.spec.source_key,
                        parsed["notice_number"],
                        context.run_id,
                        source_hash=fingerprint(payload),
                    ),
                    payload,
                )


def _parse_notices(xml: bytes, announcement_date: date) -> list[dict]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        raise ValueError("official inheritance XML parsing failed") from exc

    result: list[dict] = []
    for element in root.iter():
        if _local_name(element.tag) != "teadaanne":
            continue
        notice = _parse_notice(element, announcement_date)
        if notice is not None:
            result.append(notice)
    return result


def _parse_notice(element: ET.Element, announcement_date: date) -> dict | None:
    notice_number = _child_text(element, "teate_number")
    raw = _child_text(element, "kinnitatud_sisu")
    notary = _nested_text(element, "andmeandja", "nimi") or _child_text(element, "andmeandja_nimi")
    if not notice_number or (not notary and not raw):
        return None

    affected = _child(element, "puudutatud_isik")
    deceased_name = ""
    personal_code = ""
    if affected is not None:
        deceased_name = _normalize(f"{_child_text(affected, 'eesnimi')} {_child_text(affected, 'nimi')}")
        personal_code = _valid_code(_child_text(affected, "kood"))

    raw_identity = NAME_AND_CODE.search(_normalize(raw))
    if raw_identity:
        deceased_name = deceased_name or _normalize(raw_identity.group(1))
        personal_code = personal_code or _valid_code(raw_identity.group(2))
    if not personal_code:
        match = PERSONAL_CODE.search(raw)
        if match:
            personal_code = _valid_code(match.group(1))

    birth_date: date | None = None
    death_date: date | None = None
    deadline: date | None = None
    inputs = _child(element, "sisendid")
    if inputs is not None:
        for row in _children(inputs, "sisendirida"):
            key = _child_text(row, "nimi")
            value = _parse_estonian_date(_child_text(row, "vaartus"))
            if key == "parandaja_surmakuupaev":
                death_date = value
            elif key == "parandaja_sunnikuupaev":
                birth_date = value
            elif key == "toestamise_tahtpaev":
                deadline = value

    if deadline is None:
        match = DEADLINE_PATTERN.search(raw)
        if match:
            deadline = _parse_estonian_date(match.group(1))

    return {
        "announcement_date": announcement_date.isoformat(),
        "notice_number": notice_number,
        "source_url": _child_text(element, "url") or None,
        "notary_name": notary or None,
        "notary_phone": _nested_text(element, "andmeandja_kontaktandmed", "telefon") or None,
        "deceased_name": deceased_name or None,
        "personal_code": personal_code or None,
        "birth_date": birth_date.isoformat() if birth_date else None,
        "death_date": death_date.isoformat() if death_date else None,
        "certification_deadline": deadline.isoformat() if deadline else None,
    }


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].split(":")[-1]


def _child(element: ET.Element, name: str) -> ET.Element | None:
    for child in list(element):
        if _local_name(child.tag) == name:
            return child
    return None


def _children(element: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in list(element) if _local_name(child.tag) == name]


def _child_text(element: ET.Element, name: str) -> str:
    child = _child(element, name)
    return _normalize("".join(child.itertext())) if child is not None else ""


def _nested_text(element: ET.Element, parent: str, child: str) -> str:
    parent_element = _child(element, parent)
    return _child_text(parent_element, child) if parent_element is not None else ""


def _normalize(value: str | None) -> str:
    return " ".join((value or "").split())


def _valid_code(value: str | None) -> str:
    normalized = re.sub(r"\D", "", value or "")
    return normalized if re.fullmatch(r"\d{11}", normalized) else ""


def _parse_estonian_date(value: str | None) -> date | None:
    match = DATE_PATTERN.search(value or "")
    if not match:
        return None
    try:
        return datetime.strptime(match.group(1), "%d.%m.%Y").date()
    except ValueError:
        return None
