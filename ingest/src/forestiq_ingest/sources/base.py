from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
from enum import StrEnum
from typing import Any, Iterable, Protocol

from forestiq_ingest.pipelines.base import IngestionPipeline, PipelineContext, SourceRecord


class SourceMode(StrEnum):
    SNAPSHOT = "SNAPSHOT"
    INCREMENTAL = "INCREMENTAL"


class RetryClass(StrEnum):
    RETRYABLE = "RETRYABLE"
    NON_RETRYABLE = "NON_RETRYABLE"
    OPERATOR_ACTION = "OPERATOR_ACTION"


@dataclass(frozen=True)
class SourceIdentity:
    source_key: str
    dataset_key: str
    version: str | None = None
    checksum: str | None = None
    schema_fingerprint: str | None = None


@dataclass(frozen=True)
class SourceAdapterSpec:
    job_key: str
    source_key: str
    dataset_key: str
    mode: SourceMode
    pipeline_factory: type[IngestionPipeline]
    config_keys: tuple[str, ...] = ()
    credential_keys: tuple[str, ...] = ()
    freshness_target: timedelta | None = None
    retry_class: RetryClass = RetryClass.RETRYABLE
    overlap_window: timedelta = timedelta(0)


class SourceAdapter(Protocol):
    spec: SourceAdapterSpec

    def source_identity(self, context: PipelineContext) -> SourceIdentity: ...

    def acquire(self, context: PipelineContext) -> Iterable[Iterable[dict[str, Any]]]: ...

    def normalize(self, context: PipelineContext, rows: Iterable[dict[str, Any]]) -> Iterable[SourceRecord]: ...

    def pipeline(self) -> IngestionPipeline: ...
