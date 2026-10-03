from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Iterable


@dataclass(frozen=True)
class LayerShadowStats:
    layer_name: str
    java_generation_id: str | None
    python_generation_id: str | None
    java_row_count: int
    python_row_count: int
    matching_stable_ids: int
    geometry_bounds_match: bool
    source_metadata_match: bool

    @property
    def row_delta(self) -> int:
        return self.python_row_count - self.java_row_count

    @property
    def passed(self) -> bool:
        return (
            self.row_delta == 0
            and self.geometry_bounds_match
            and self.source_metadata_match
            and self.matching_stable_ids == self.java_row_count
        )


def summarize_shadow(stats: Iterable[LayerShadowStats]) -> dict[str, object]:
    rows = list(stats)
    return {
        "layers": len(rows),
        "passed": sum(1 for row in rows if row.passed),
        "failed": [row.layer_name for row in rows if not row.passed],
        "rowDeltaTotal": sum(row.row_delta for row in rows),
    }


def row_count_matches(java_count: int, python_count: int) -> bool:
    return java_count == python_count


def bounds_match(java_bounds: tuple[Decimal, Decimal, Decimal, Decimal] | None, python_bounds: tuple[Decimal, Decimal, Decimal, Decimal] | None) -> bool:
    return java_bounds == python_bounds
