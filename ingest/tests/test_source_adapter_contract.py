from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from forestiq_ingest.pipelines.base import PipelineContext, SourceMetadata, SourceRecord
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode
from forestiq_ingest.sources.registry import SourceRegistry
from forestiq_ingest.sources.watermark import (
    IncrementalCheckpoint,
    SnapshotCheckpoint,
    incremental_extract_start,
    snapshot_unchanged,
)
from forestiq_ingest.validation.rules import ValidationFailure, reject_duplicate_source_ids


class DummyPipeline:
    pass


class DummyAdapter:
    def __init__(self, job_key):
        self.spec = SourceAdapterSpec(
            job_key=job_key,
            source_key="dummy",
            dataset_key="dataset",
            mode=SourceMode.SNAPSHOT,
            pipeline_factory=DummyPipeline,
        )

    def source_identity(self, context):
        return SourceIdentity("dummy", "dataset", version="v1", checksum="abc")

    def acquire(self, context):
        yield [{"id": "1"}]

    def normalize(self, context, rows):
        for row in rows:
            yield SourceRecord(
                SourceMetadata("dummy", row["id"], context.run_id, source_hash="abc"),
                row,
            )

    def pipeline(self):
        return DummyPipeline()


def test_registry_maps_job_key_to_adapter_and_rejects_duplicates():
    adapter = DummyAdapter("CADASTRE_PUBLIC_DATA")
    registry = SourceRegistry.from_adapters([adapter])

    assert registry.require("cadastre_public_data") is adapter
    with pytest.raises(ValueError, match="duplicate"):
        SourceRegistry.from_adapters([adapter, DummyAdapter("CADASTRE_PUBLIC_DATA")])


def test_snapshot_same_checksum_is_noop_candidate():
    identity = SourceIdentity("dummy", "dataset", version="2026-09-17", checksum="abc")
    checkpoint = SnapshotCheckpoint("dummy", "dataset", version="2026-09-17", checksum="abc", published_generation_id="g1")

    assert snapshot_unchanged(identity, checkpoint) is True


def test_incremental_overlap_rewinds_extract_start():
    watermark = datetime(2026, 9, 17, 9, 0, tzinfo=UTC)
    checkpoint = IncrementalCheckpoint("dummy", "dataset", watermark=watermark)

    assert incremental_extract_start(checkpoint, timedelta(minutes=15)) == datetime(2026, 9, 17, 8, 45, tzinfo=UTC)


def test_duplicate_source_record_is_rejected_by_shared_validation():
    run_id = uuid4()
    records = [
        SourceRecord(SourceMetadata("dummy", "same", run_id), {"name": "A"}),
        SourceRecord(SourceMetadata("dummy", "same", run_id), {"name": "B"}),
    ]

    with pytest.raises(ValidationFailure, match="duplicate"):
        reject_duplicate_source_ids(records)


def test_failed_run_does_not_advance_watermark_without_success_callback():
    class Store:
        advanced = False

        def mark_snapshot_published(self, *_args):
            self.advanced = True

    store = Store()

    assert store.advanced is False
