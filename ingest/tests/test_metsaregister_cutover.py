from decimal import Decimal

import pytest

from forestiq_ingest.sources.metsaregister_cutover import evaluate_cutover, require_cutover_ready
from forestiq_ingest.sources.metsaregister_shadow import LayerShadowStats


def layer(name: str, *, rows: int = 10, python_rows: int = 10, ids: int = 10, bounds=True, metadata=True):
    return LayerShadowStats(
        name,
        "java-generation",
        "python-generation",
        rows,
        python_rows,
        ids,
        bounds,
        metadata,
    )


def test_cutover_requires_full_parity_and_two_successful_runs():
    evidence = evaluate_cutover(
        [layer("metsaregister:eraldis"), layer("metsaregister:teatis")],
        successful_python_runs=2,
        notification_samples_match=True,
        forest_plan_samples_match=True,
    )

    assert evidence.ready is True
    assert evidence.failures() == []
    require_cutover_ready(evidence)


def test_cutover_fails_closed_on_any_parity_gap():
    evidence = evaluate_cutover(
        [layer("metsaregister:eraldis", python_rows=9, ids=9)],
        successful_python_runs=1,
        notification_samples_match=False,
        forest_plan_samples_match=True,
    )

    assert evidence.ready is False
    assert "layer:metsaregister:eraldis" in evidence.failures()
    assert "successful_python_runs:<2" in evidence.failures()
    assert "notification_samples:mismatch" in evidence.failures()
    with pytest.raises(RuntimeError, match="cutover evidence is incomplete"):
        require_cutover_ready(evidence)


def test_cutover_requires_at_least_one_layer():
    evidence = evaluate_cutover(
        [],
        successful_python_runs=2,
        notification_samples_match=True,
        forest_plan_samples_match=True,
    )

    assert evidence.ready is False
    assert evidence.failures() == ["layers:missing"]
