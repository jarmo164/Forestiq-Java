import pytest

from forestiq_ingest.sources.owner_source_cutover import (
    OwnerSourceParity,
    evaluate_cutover,
    require_cutover_ready,
)


def source(job_key: str, *, current=10, python=10, ids=True, cases=True):
    return OwnerSourceParity(job_key, current, python, ids, cases)


def all_sources():
    return [
        source("OWNER_CADASTRE_RELATIONS"),
        source("WEASEL_OWNERSHIP_TRANSITIONS"),
        source("INHERITANCE_NOTICES"),
    ]


def test_owner_source_cutover_requires_business_semantics_and_parity():
    evidence = evaluate_cutover(
        all_sources(),
        multi_owner_preserved=True,
        relation_end_reactivate_preserved=True,
        manual_override_preserved=True,
        retry_idempotent=True,
        failed_run_preserved_watermark=True,
        successful_python_runs_per_source=2,
    )

    assert evidence.ready is True
    assert evidence.failures() == []
    require_cutover_ready(evidence)


def test_owner_source_cutover_fails_when_a_source_is_missing():
    evidence = evaluate_cutover(
        all_sources()[:-1],
        multi_owner_preserved=True,
        relation_end_reactivate_preserved=True,
        manual_override_preserved=True,
        retry_idempotent=True,
        failed_run_preserved_watermark=True,
        successful_python_runs_per_source=2,
    )

    assert evidence.ready is False
    assert "source:INHERITANCE_NOTICES:missing" in evidence.failures()


def test_owner_source_cutover_fails_on_watermark_or_domain_regression():
    evidence = evaluate_cutover(
        [
            source("OWNER_CADASTRE_RELATIONS"),
            source("WEASEL_OWNERSHIP_TRANSITIONS", python=9),
            source("INHERITANCE_NOTICES"),
        ],
        multi_owner_preserved=True,
        relation_end_reactivate_preserved=False,
        manual_override_preserved=False,
        retry_idempotent=False,
        failed_run_preserved_watermark=False,
        successful_python_runs_per_source=1,
    )

    assert evidence.ready is False
    assert "source:WEASEL_OWNERSHIP_TRANSITIONS" in evidence.failures()
    assert "domain:end_reactivate" in evidence.failures()
    assert "domain:manual_override" in evidence.failures()
    assert "retry:not_idempotent" in evidence.failures()
    assert "watermark:advanced_on_failure" in evidence.failures()

    with pytest.raises(RuntimeError, match="cutover evidence is incomplete"):
        require_cutover_ready(evidence)
