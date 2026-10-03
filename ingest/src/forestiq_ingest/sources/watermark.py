from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Protocol

from forestiq_ingest.sources.base import SourceIdentity


@dataclass(frozen=True)
class SnapshotCheckpoint:
    source_key: str
    dataset_key: str
    version: str | None
    checksum: str | None
    published_generation_id: str | None


@dataclass(frozen=True)
class IncrementalCheckpoint:
    source_key: str
    dataset_key: str
    watermark: datetime | None


class WatermarkStore(Protocol):
    def snapshot_checkpoint(self, source_key: str, dataset_key: str) -> SnapshotCheckpoint | None: ...

    def incremental_checkpoint(self, source_key: str, dataset_key: str) -> IncrementalCheckpoint | None: ...

    def mark_snapshot_published(self, identity: SourceIdentity, published_generation_id: str | None) -> None: ...

    def mark_incremental_published(self, identity: SourceIdentity, watermark: datetime) -> None: ...


def snapshot_unchanged(identity: SourceIdentity, checkpoint: SnapshotCheckpoint | None) -> bool:
    if checkpoint is None:
        return False
    if identity.checksum and checkpoint.checksum:
        return identity.checksum == checkpoint.checksum
    if identity.version and checkpoint.version:
        return identity.version == checkpoint.version
    return False


def incremental_extract_start(checkpoint: IncrementalCheckpoint | None, overlap_window: timedelta) -> datetime | None:
    if checkpoint is None or checkpoint.watermark is None:
        return None
    return checkpoint.watermark - overlap_window


def publish_snapshot_after_success(store: WatermarkStore, identity: SourceIdentity, published_generation_id: str | None) -> None:
    store.mark_snapshot_published(identity, published_generation_id)


def publish_incremental_after_success(store: WatermarkStore, identity: SourceIdentity, watermark: datetime) -> None:
    store.mark_incremental_published(identity, watermark)
