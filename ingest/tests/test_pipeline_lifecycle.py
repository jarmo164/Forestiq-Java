from __future__ import annotations

from uuid import UUID, uuid4

import pytest

from forestiq_ingest.pipelines.base import (
    PipelineContext,
    PipelineRunner,
    PipelineState,
    SourceMetadata,
    SourceRecord,
)
from forestiq_ingest.validation.rules import ValidationFailure, reject_duplicate_source_ids, require_fields


class Sink:
    def __init__(self):
        self.states = []
        self.completed = None
        self.failed = None

    def transition(self, state, counters):
        self.states.append((state, counters.rows_read, counters.rows_staged))

    def complete_success(self, counters, message, published_generation_id=None):
        self.completed = (counters, message, published_generation_id)

    def complete_failure(self, counters, message, error_summary):
        self.failed = (counters, message, error_summary)


class FakePipeline:
    def __init__(self, records, *, fail_validation=False, fail_prepare=False, already_published=False):
        self.records = records
        self.fail_validation = fail_validation
        self.fail_prepare = fail_prepare
        self._already_published = already_published
        self.current_generation = "old"
        self.checkpointed = False
        self.published = False

    def extract(self, context):
        for index in range(0, len(self.records), context.chunk_size):
            yield self.records[index:index + context.chunk_size]

    def stage(self, context, records):
        return len(list(records))

    def validate(self, context):
        if self.fail_validation:
            raise ValidationFailure("required fields missing")
        require_fields(self.records, {"name"})
        reject_duplicate_source_ids(self.records)

    def already_published(self, context):
        return self._already_published

    def transform(self, context):
        return self.records

    def prepare_load(self, context, records):
        if self.fail_prepare:
            raise RuntimeError("interrupted before publish")
        return records

    def publish(self, context, records):
        list(records)
        self.published = True
        self.current_generation = "new"
        return UUID("00000000-0000-0000-0000-000000000999")

    def checkpoint(self, context, published_generation_id):
        assert self.published
        self.checkpointed = True


def record(source_id, payload):
    return SourceRecord(
        metadata=SourceMetadata(source="test-source", source_id=source_id, ingest_run_id=uuid4()),
        payload=payload,
    )


def context(chunk_size=2):
    return PipelineContext(
        run_id=uuid4(),
        job_key="DUMMY_HEALTHCHECK",
        source="test-source",
        source_version="2026-09-17",
        source_checksum="abc",
        chunk_size=chunk_size,
    )


def test_successful_publish_advances_generation_and_checkpoint_after_validation():
    pipeline = FakePipeline([record("1", {"name": "A"}), record("2", {"name": "B"})])
    sink = Sink()

    counters = PipelineRunner(pipeline, sink).run(context(chunk_size=1))

    assert counters.rows_read == 2
    assert counters.rows_staged == 2
    assert counters.rows_written == 2
    assert pipeline.current_generation == "new"
    assert pipeline.checkpointed is True
    assert sink.states[-1][0] == PipelineState.PUBLISHING
    assert sink.completed[2] == UUID("00000000-0000-0000-0000-000000000999")


def test_validation_failure_keeps_current_generation_unchanged():
    pipeline = FakePipeline([record("1", {"name": ""})], fail_validation=True)
    sink = Sink()

    with pytest.raises(ValidationFailure):
        PipelineRunner(pipeline, sink).run(context())

    assert pipeline.current_generation == "old"
    assert pipeline.published is False
    assert pipeline.checkpointed is False
    assert sink.failed[0].rows_rejected == 1


def test_interruption_before_publish_keeps_current_generation_unchanged():
    pipeline = FakePipeline([record("1", {"name": "A"})], fail_prepare=True)
    sink = Sink()

    with pytest.raises(RuntimeError):
        PipelineRunner(pipeline, sink).run(context())

    assert pipeline.current_generation == "old"
    assert pipeline.published is False
    assert pipeline.checkpointed is False



def test_retry_same_source_identity_skips_publish_when_already_published():
    pipeline = FakePipeline([record("1", {"name": "A"})], already_published=True)
    sink = Sink()

    counters = PipelineRunner(pipeline, sink).run(context())

    assert counters.rows_read == 1
    assert counters.rows_written == 0
    assert pipeline.current_generation == "old"
    assert pipeline.published is False
    assert pipeline.checkpointed is False
    assert sink.completed[1] == "Source version already published."

def test_extract_chunk_must_respect_configured_limit():
    class BadChunkPipeline(FakePipeline):
        def extract(self, context):
            yield [record("1", {"name": "A"}), record("2", {"name": "B"})]

    pipeline = BadChunkPipeline([])
    sink = Sink()

    with pytest.raises(Exception, match="chunk_size"):
        PipelineRunner(pipeline, sink).run(context(chunk_size=1))
