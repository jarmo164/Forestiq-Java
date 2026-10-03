from forestiq_ingest.config import IngestSettings
from forestiq_ingest.healthcheck import build_health_payload, main


def test_healthcheck_skips_provider_calls(monkeypatch, capsys):
    monkeypatch.delenv("FORESTIQ_INGEST_HEALTHCHECK_REQUIRE_DB", raising=False)

    exit_code = main(["--component", "worker"])

    output = capsys.readouterr().out
    assert exit_code == 0
    assert '"status":"ok"' in output
    assert '"provider_checks":"skipped"' in output


def test_db_health_can_be_required_without_url():
    settings = IngestSettings.from_env(component="worker")

    exit_code, payload = build_health_payload(settings, max_age_seconds=120, require_db=True)

    assert exit_code == 1
    assert payload["status"] == "degraded"
    assert payload["database"] == "unavailable"
