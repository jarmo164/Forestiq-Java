from pathlib import Path
from uuid import uuid4

import pytest

from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.geospatial_snapshot import (
    GeospatialSnapshotAdapter,
    GeospatialSnapshotConfig,
    file_checksum,
    validate_estonia_bounds,
)


def test_file_checksum_detects_unchanged_snapshot(tmp_path):
    snapshot = tmp_path / "cadastre.gpkg"
    snapshot.write_bytes(b"same snapshot")

    assert file_checksum(snapshot) == file_checksum(snapshot)


def test_bounds_inside_estonia_pass():
    validate_estonia_bounds((400000.0, 6400000.0, 500000.0, 6500000.0))


def test_bounds_outside_estonia_fail_closed():
    with pytest.raises(ValueError, match="outside Estonia"):
        validate_estonia_bounds((1.0, 2.0, 3.0, 4.0))


def test_geospatial_adapter_normalizes_source_identity(tmp_path):
    snapshot = tmp_path / "cadastre.gpkg"
    snapshot.write_bytes(b"data")
    adapter = GeospatialSnapshotAdapter(
        GeospatialSnapshotConfig(
            source_key="CADASTRE_PUBLIC_DATA",
            provider="MAA_RUUMIAMET",
            dataset_key="cadastre",
            display_name="Cadastre",
            path=snapshot,
        )
    )
    context = PipelineContext(uuid4(), "CADASTRE_PUBLIC_DATA", "CADASTRE_PUBLIC_DATA")

    records = list(adapter.normalize_rows(context, [{"tunnus": "12345:001:0001", "area": 12}]))

    assert records[0].metadata.source_id == "12345:001:0001"
    assert records[0].metadata.source_hash
