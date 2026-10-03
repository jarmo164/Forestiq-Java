from __future__ import annotations

from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any

from forestiq_ingest.pipelines.base import SourceRecord


class ValidationFailure(ValueError):
    pass


@dataclass(frozen=True)
class RowCountExpectation:
    min_rows: int = 1
    max_rows: int | None = None


def require_fields(records: Iterable[SourceRecord], fields: set[str]) -> None:
    missing: list[str] = []
    for record in records:
        absent = sorted(field for field in fields if record.payload.get(field) in (None, ""))
        if absent:
            missing.append(f"{record.metadata.source_id}: {','.join(absent)}")
    if missing:
        raise ValidationFailure("required fields missing: " + "; ".join(missing[:20]))


def reject_duplicate_source_ids(records: Iterable[SourceRecord]) -> None:
    counts = Counter(record.metadata.source_id for record in records)
    duplicates = sorted(source_id for source_id, count in counts.items() if count > 1)
    if duplicates:
        raise ValidationFailure("duplicate source_id values: " + ", ".join(duplicates[:20]))


def assert_row_count(records: Iterable[SourceRecord], expectation: RowCountExpectation) -> None:
    count = sum(1 for _ in records)
    if count < expectation.min_rows:
        raise ValidationFailure(f"row count {count} is below expected minimum {expectation.min_rows}")
    if expectation.max_rows is not None and count > expectation.max_rows:
        raise ValidationFailure(f"row count {count} exceeds expected maximum {expectation.max_rows}")


def assert_schema(records: Iterable[SourceRecord], allowed_fields: set[str]) -> None:
    drift: dict[str, set[str]] = {}
    for record in records:
        unexpected = set(record.payload) - allowed_fields
        if unexpected:
            drift[record.metadata.source_id] = unexpected
    if drift:
        first = next(iter(drift.items()))
        raise ValidationFailure(f"schema drift for {first[0]}: {sorted(first[1])}")


def assert_geometry(records: Iterable[SourceRecord], is_valid: Callable[[Any], bool], geometry_field: str = "geometry") -> None:
    invalid = [
        record.metadata.source_id
        for record in records
        if geometry_field in record.payload and not is_valid(record.payload[geometry_field])
    ]
    if invalid:
        raise ValidationFailure("invalid geometry for source_id values: " + ", ".join(invalid[:20]))
