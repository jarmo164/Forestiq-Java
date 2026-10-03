from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from uuid import UUID

import psycopg

from forestiq_ingest.database.staging import CopyStageSpec, copy_stage_rows
from forestiq_ingest.pipelines.base import PipelineContext, SourceMetadata, SourceRecord, fingerprint
from forestiq_ingest.sources.base import SourceAdapterSpec, SourceIdentity, SourceMode


ESTONIA_EPSG3301_BOUNDS = (300_000.0, 6_300_000.0, 800_000.0, 6_700_000.0)


@dataclass(frozen=True)
class GeospatialSnapshotConfig:
    source_key: str
    provider: str
    dataset_key: str
    display_name: str
    path: Path
    layer: str | None = None
    expected_srid: int = 3301
    canonical_srid: int = 3301
    chunk_size: int = 10_000


class GeospatialSnapshotAdapter:
    def __init__(self, config: GeospatialSnapshotConfig, job_key: str = "CADASTRE_PUBLIC_DATA"):
        self.config = config
        self.spec = SourceAdapterSpec(
            job_key=job_key,
            source_key=config.source_key,
            dataset_key=config.dataset_key,
            mode=SourceMode.SNAPSHOT,
            pipeline_factory=object,
            config_keys=("CADASTRE_SNAPSHOT_PATH",),
        )

    def source_identity(self, context: PipelineContext) -> SourceIdentity:
        checksum = file_checksum(self.config.path)
        return SourceIdentity(
            source_key=self.config.source_key,
            dataset_key=self.config.dataset_key,
            version=context.source_version,
            checksum=checksum,
        )

    def normalize_rows(self, context: PipelineContext, rows: Iterable[dict]) -> Iterable[SourceRecord]:
        for row in rows:
            source_id = str(row.get("source_id") or row.get("tunnus") or row.get("cadastral_code") or row.get("id"))
            payload = dict(row)
            yield SourceRecord(
                metadata=SourceMetadata(
                    source=self.config.source_key,
                    source_id=source_id,
                    ingest_run_id=context.run_id,
                    source_hash=fingerprint(payload),
                ),
                payload=payload,
            )


def file_checksum(path: Path, *, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def validate_estonia_bounds(bounds: tuple[float, float, float, float]) -> None:
    min_x, min_y, max_x, max_y = bounds
    est_min_x, est_min_y, est_max_x, est_max_y = ESTONIA_EPSG3301_BOUNDS
    if min_x < est_min_x or max_x > est_max_x or min_y < est_min_y or max_y > est_max_y:
        raise ValueError(f"geometry bounds {bounds} are outside Estonia EPSG:3301 envelope")


def stage_geospatial_features(
    connection: psycopg.Connection,
    *,
    source_key: str,
    generation_id: UUID,
    records: Iterable[SourceRecord],
) -> int:
    def copy_rows() -> Iterable[tuple]:
        for record in records:
            geometry_wkb = record.payload.pop("geometry_wkb", None)
            yield (
                source_key,
                generation_id,
                record.metadata.source_id,
                record.metadata.source_hash,
                json.dumps(record.payload, separators=(",", ":")),
                geometry_wkb,
            )

    return copy_stage_rows(
        connection,
        CopyStageSpec(
            create_temp_table_sql="""
            CREATE TEMP TABLE IF NOT EXISTS geospatial_feature_load (
                source_key VARCHAR(128) NOT NULL,
                generation_id UUID NOT NULL,
                source_id VARCHAR(300),
                source_hash VARCHAR(128) NOT NULL,
                properties JSONB NOT NULL,
                geometry_wkb BYTEA
            ) ON COMMIT DROP
            """,
            truncate_sql="TRUNCATE geospatial_feature_load",
            copy_sql="""
            COPY geospatial_feature_load
                (source_key, generation_id, source_id, source_hash, properties, geometry_wkb)
            FROM STDIN
            """,
            load_sql="""
            INSERT INTO geospatial_feature
                (source_key, generation_id, source_id, source_hash, properties, geom)
            SELECT source_key,
                   generation_id,
                   source_id,
                   source_hash,
                   properties,
                   CASE WHEN geometry_wkb IS NULL THEN NULL ELSE ST_SetSRID(ST_GeomFromWKB(geometry_wkb), 3301) END
              FROM geospatial_feature_load
            ON CONFLICT (generation_id, source_id) WHERE source_id IS NOT NULL DO UPDATE SET
                source_hash = EXCLUDED.source_hash,
                properties = EXCLUDED.properties,
                geom = EXCLUDED.geom
            """,
        ),
        copy_rows(),
    )
