from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from hashlib import sha256
from typing import Any, Iterable, Protocol
from uuid import UUID


class PipelineState(StrEnum):
    RUNNING = "RUNNING"
    VALIDATING = "VALIDATING"
    PUBLISHING = "PUBLISHING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class SourceMetadata:
    source: str
    source_id: str
    ingest_run_id: UUID
    source_updated_at: str | None = None
    source_hash: str | None = None
    published_generation_id: UUID | None = None


@dataclass(frozen=True)
class SourceRecord:
    metadata: SourceMetadata
    payload: dict[str, Any]


@dataclass
class PipelineCounters:
    rows_read: int = 0
    rows_staged: int = 0
    rows_written: int = 0
    rows_rejected: int = 0
    bytes_read: int = 0
    bytes_staged: int = 0
    bytes_written: int = 0


@dataclass(frozen=True)
class PipelineContext:
    run_id: UUID
    job_key: str
    source: str
    source_version: str | None = None
    source_checksum: str | None = None
    chunk_size: int = 1_000


class PipelineRunSink(Protocol):
    def transition(self, state: PipelineState, counters: PipelineCounters) -> None: ...

    def complete_success(self, counters: PipelineCounters, message: str, published_generation_id: UUID | None = None) -> None: ...

    def complete_failure(self, counters: PipelineCounters, message: str, error_summary: str) -> None: ...


class GenerationPublisher(Protocol):
    def already_published(self, context: PipelineContext) -> bool: ...

    def publish(self, context: PipelineContext, records: Iterable[SourceRecord]) -> UUID | None: ...


class IngestionPipeline(Protocol):
    def extract(self, context: PipelineContext) -> Iterable[Iterable[SourceRecord]]: ...

    def stage(self, context: PipelineContext, records: Iterable[SourceRecord]) -> int: ...

    def validate(self, context: PipelineContext) -> None: ...

    def transform(self, context: PipelineContext) -> Iterable[SourceRecord]: ...

    def prepare_load(self, context: PipelineContext, records: Iterable[SourceRecord]) -> Iterable[SourceRecord]: ...

    def publish(self, context: PipelineContext, records: Iterable[SourceRecord]) -> UUID | None: ...

    def checkpoint(self, context: PipelineContext, published_generation_id: UUID | None) -> None: ...


class PipelineExecutionError(RuntimeError):
    pass


class PipelineRunner:
    def __init__(self, pipeline: IngestionPipeline, sink: PipelineRunSink):
        self.pipeline = pipeline
        self.sink = sink

    def run(self, context: PipelineContext) -> PipelineCounters:
        counters = PipelineCounters()
        try:
            self.sink.transition(PipelineState.RUNNING, counters)
            for chunk in self.pipeline.extract(context):
                batch = list(_bounded_chunk(chunk, context.chunk_size))
                counters.rows_read += len(batch)
                counters.bytes_read += _payload_bytes(batch)
                counters.rows_staged += self.pipeline.stage(context, batch)
                self.sink.transition(PipelineState.RUNNING, counters)

            self.sink.transition(PipelineState.VALIDATING, counters)
            self.pipeline.validate(context)

            already_published = getattr(self.pipeline, "already_published", None)
            if callable(already_published) and already_published(context):
                self.sink.complete_success(counters, "Source version already published.", None)
                return counters

            transformed = self.pipeline.transform(context)
            prepared = self.pipeline.prepare_load(context, transformed)

            self.sink.transition(PipelineState.PUBLISHING, counters)
            published_generation_id = self.pipeline.publish(context, _counting_records(prepared, counters))

            self.pipeline.checkpoint(context, published_generation_id)
            self.sink.complete_success(counters, "Ingestion pipeline completed.", published_generation_id)
            return counters
        except Exception as exception:
            counters.rows_rejected = max(counters.rows_rejected, counters.rows_read - counters.rows_written)
            self.sink.complete_failure(counters, "Ingestion pipeline failed.", _safe_error(exception))
            raise


def fingerprint(payload: dict[str, Any]) -> str:
    encoded = repr(sorted(payload.items())).encode("utf-8")
    return sha256(encoded).hexdigest()


def _bounded_chunk(records: Iterable[SourceRecord], chunk_size: int) -> Iterable[SourceRecord]:
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    count = 0
    for record in records:
        if count >= chunk_size:
            raise PipelineExecutionError("extract chunk exceeded configured chunk_size")
        count += 1
        yield record


def _counting_records(records: Iterable[SourceRecord], counters: PipelineCounters) -> Iterable[SourceRecord]:
    for record in records:
        counters.rows_written += 1
        counters.bytes_written += len(repr(record.payload).encode("utf-8"))
        yield record


def _payload_bytes(records: Iterable[SourceRecord]) -> int:
    return sum(len(repr(record.payload).encode("utf-8")) for record in records)


def _safe_error(exception: Exception) -> str:
    message = str(exception) or exception.__class__.__name__
    message = " ".join(message.split())
    return message[:500]
