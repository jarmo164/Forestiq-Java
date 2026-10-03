from forestiq_ingest.config import IngestSettings


def test_settings_reads_database_url_alias(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("INGEST_DATABASE_URL", "postgresql://example/db")
    monkeypatch.setenv("FORESTIQ_INGEST_ENABLED_JOB_KEYS", "metsaregister, cadastre ")

    settings = IngestSettings.from_env()

    assert settings.database_url == "postgresql://example/db"
    assert settings.enabled_job_keys == ("metsaregister", "cadastre")


def test_settings_rejects_invalid_integer(monkeypatch):
    monkeypatch.setenv("FORESTIQ_INGEST_POLL_INTERVAL_SECONDS", "soon")

    try:
        IngestSettings.from_env()
    except ValueError as exc:
        assert "FORESTIQ_INGEST_POLL_INTERVAL_SECONDS" in str(exc)
    else:
        raise AssertionError("invalid poll interval should raise ValueError")
