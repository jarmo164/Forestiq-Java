from pathlib import Path

import pytest

from forestiq_ingest.sources.disposition import load_source_dispositions


MANIFEST = Path(__file__).parents[1] / "source-dispositions.json"


def test_rik_and_metsis_source_dispositions_are_complete_and_safe():
    sources = {item.source_key: item for item in load_source_dispositions(MANIFEST)}

    assert set(sources) == {
        "metsis_owner_portfolio",
        "rik_cadastre_count",
        "official_inheritance_notices",
        "weasel_ownership_transitions",
    }

    assert sources["metsis_owner_portfolio"].classification == "ETL_ONLY"
    assert sources["metsis_owner_portfolio"].target_job_key == "OWNER_CADASTRE_RELATIONS"
    assert sources["metsis_owner_portfolio"].credential_owner == "ingest"

    assert sources["rik_cadastre_count"].classification == "REAL_TIME_DOMAIN_OPERATION"
    assert sources["rik_cadastre_count"].target_job_key is None
    assert sources["rik_cadastre_count"].credential_owner == "api"
    assert sources["rik_cadastre_count"].scheduler_allowed_in_api is False


def test_manifest_validator_rejects_api_owned_etl_credentials(tmp_path):
    bad = tmp_path / "source-dispositions.json"
    bad.write_text(
        """{
          "schemaVersion": 1,
          "sources": [{
            "sourceKey": "bad_etl",
            "classification": "ETL_ONLY",
            "currentJavaOwner": "Legacy",
            "targetJobKey": "BAD_JOB",
            "credentialOwner": "api",
            "schedulerAllowedInApi": false
          }]
        }""",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="credentials must be ingest-owned"):
        load_source_dispositions(bad)
