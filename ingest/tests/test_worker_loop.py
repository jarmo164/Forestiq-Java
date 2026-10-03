from uuid import UUID

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob
from forestiq_ingest.worker import __main__ as worker_main
from forestiq_ingest.worker.execution import ExecutionResult, execute_claimed_job, executor_for


RUN_ID = UUID("22222222-2222-2222-2222-222222222222")
ORG_ID = UUID("11111111-1111-1111-1111-111111111111")


class ConnectionStub:
    def __init__(self):
        self.commits = 0

    def commit(self):
        self.commits += 1


class NoopLeaseKeeper:
    def __init__(self, *_args, **_kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


def _settings(tmp_path):
    base = IngestSettings.from_env(component="worker")
    return IngestSettings(
        database_url="postgresql://example/db",
        component="worker",
        worker_id="python-worker-1",
        enabled_job_keys=(),
        poll_interval_seconds=base.poll_interval_seconds,
        heartbeat_seconds=base.heartbeat_seconds,
        lease_seconds=base.lease_seconds,
        temp_dir=tmp_path,
        log_level=base.log_level,
        healthcheck_require_db=False,
    )


def test_claim_is_committed_before_executor_and_completion(monkeypatch, tmp_path):
    connection = ConnectionStub()
    job = ClaimedJob(RUN_ID, ORG_ID, "OWNER_CADASTRE_RELATIONS", "MANUAL", {}, 1, 1)
    events = []

    monkeypatch.setattr(worker_main, "claim_next_job", lambda *_args, **_kwargs: job)
    monkeypatch.setattr(worker_main, "_LeaseKeeper", NoopLeaseKeeper)

    def execute(_connection, _job, _settings):
        events.append(("execute", connection.commits))
        return ExecutionResult(state="SUCCEEDED", message="done")

    monkeypatch.setattr(worker_main, "execute_claimed_job", execute)

    def complete(*_args, **_kwargs):
        events.append(("complete", connection.commits))
        return True

    monkeypatch.setattr(worker_main, "complete_job", complete)

    processed, exit_code = worker_main._claim_and_execute(connection, _settings(tmp_path))

    assert processed is True
    assert exit_code == 0
    assert events == [("execute", 1), ("complete", 1)]
    assert connection.commits == 2


def test_unregistered_real_job_fails_closed(tmp_path):
    job = ClaimedJob(RUN_ID, ORG_ID, "UNREGISTERED_SOURCE", "MANUAL", {}, 1, 1)

    result = execute_claimed_job(ConnectionStub(), job, _settings(tmp_path))

    assert result.state == "FAILED"
    assert "No Python adapter registered" in result.error_summary


def test_empty_queue_does_not_commit(monkeypatch, tmp_path):
    connection = ConnectionStub()
    monkeypatch.setattr(worker_main, "claim_next_job", lambda *_args, **_kwargs: None)

    processed, exit_code = worker_main._claim_and_execute(connection, _settings(tmp_path))

    assert processed is False
    assert exit_code == 0
    assert connection.commits == 0


def test_all_external_ingestion_job_keys_have_python_executors():
    external_keys = {
        "CADASTRE_PUBLIC_DATA",
        "FOREST_NOTIFICATIONS",
        "FOREST_PLAN",
        "METSAREGISTER_WFS_MIRROR",
        "OWNER_CADASTRE_RELATIONS",
        "WEASEL_OWNERSHIP_TRANSITIONS",
        "INHERITANCE_NOTICES",
    }

    assert all(executor_for(job_key) is not None for job_key in external_keys)
    assert executor_for("METSAREGISTER_FORESTRY_CANONICAL") is None
