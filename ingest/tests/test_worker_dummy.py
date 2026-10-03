from forestiq_ingest.worker.__main__ import run_dummy_job
from forestiq_ingest.config import IngestSettings


def test_dummy_job_creates_temp_dir(tmp_path):
    settings = IngestSettings.from_env(component="worker")
    settings = IngestSettings(
        database_url=settings.database_url,
        component=settings.component,
        worker_id=settings.worker_id,
        enabled_job_keys=settings.enabled_job_keys,
        poll_interval_seconds=settings.poll_interval_seconds,
        heartbeat_seconds=settings.heartbeat_seconds,
        lease_seconds=settings.lease_seconds,
        temp_dir=tmp_path / "runtime",
        log_level=settings.log_level,
        healthcheck_require_db=settings.healthcheck_require_db,
    )

    assert run_dummy_job(settings) == 0
    assert settings.temp_dir.exists()
