from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from forestiq_ingest.sources.base import SourceAdapter


@dataclass(frozen=True)
class SourceRegistry:
    adapters: dict[str, SourceAdapter]

    @classmethod
    def from_adapters(cls, adapters: Iterable[SourceAdapter]) -> "SourceRegistry":
        by_key: dict[str, SourceAdapter] = {}
        for adapter in adapters:
            job_key = adapter.spec.job_key.upper()
            if job_key in by_key:
                raise ValueError(f"duplicate source adapter for job key {job_key}")
            by_key[job_key] = adapter
        return cls(by_key)

    def require(self, job_key: str) -> SourceAdapter:
        normalized = job_key.upper()
        try:
            return self.adapters[normalized]
        except KeyError as exc:
            raise KeyError(f"no source adapter registered for job key {normalized}") from exc

    def enabled_for_python(self, job_keys: Iterable[str]) -> "SourceRegistry":
        allowed = {key.upper() for key in job_keys}
        return SourceRegistry({key: adapter for key, adapter in self.adapters.items() if key in allowed})
