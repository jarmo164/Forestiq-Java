import pytest

from forestiq_ingest.sources.geospatial_cutover import (
    GeospatialDatasetParity,
    evaluate_cutover,
    require_cutover_ready,
)


def dataset(
    key: str,
    *,
    current_rows: int = 100,
    python_rows: int = 100,
    identity=True,
    bounds=True,
    sample=True,
):
    return GeospatialDatasetParity(
        key,
        current_rows,
        python_rows,
        identity,
        bounds,
        sample,
    )


def test_geospatial_cutover_requires_all_safety_proofs():
    evidence = evaluate_cutover(
        [dataset("cadastre"), dataset("etak-road")],
        failed_import_preserved_current_generation=True,
        unchanged_snapshot_was_noop=True,
        java_read_model_compatible=True,
        successful_python_runs=2,
    )

    assert evidence.ready is True
    assert evidence.failures() == []
    require_cutover_ready(evidence)


def test_geospatial_cutover_fails_closed_on_partial_import_or_checksum_gap():
    evidence = evaluate_cutover(
        [dataset("cadastre", python_rows=99)],
        failed_import_preserved_current_generation=False,
        unchanged_snapshot_was_noop=False,
        java_read_model_compatible=True,
        successful_python_runs=1,
    )

    assert evidence.ready is False
    assert "dataset:cadastre" in evidence.failures()
    assert "failed_import:last_known_good_not_proven" in evidence.failures()
    assert "checksum:no_op_not_proven" in evidence.failures()
    assert "successful_python_runs:<2" in evidence.failures()

    with pytest.raises(RuntimeError, match="cutover evidence is incomplete"):
        require_cutover_ready(evidence)


def test_geospatial_cutover_requires_java_read_model_compatibility():
    evidence = evaluate_cutover(
        [dataset("cadastre")],
        failed_import_preserved_current_generation=True,
        unchanged_snapshot_was_noop=True,
        java_read_model_compatible=False,
        successful_python_runs=2,
    )

    assert evidence.ready is False
    assert evidence.failures() == ["java_read_model:compatibility_not_proven"]
