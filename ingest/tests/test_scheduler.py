from datetime import datetime, timedelta, timezone
from uuid import UUID

from forestiq_ingest.database import scheduler


ORG_ID = UUID("11111111-1111-1111-1111-111111111111")
NOW = datetime(2026, 9, 24, 9, 0, tzinfo=timezone.utc)


def test_missed_windows_is_bounded_by_schedule_interval():
    assert scheduler._missed_windows(None, NOW, 3600) == 1
    assert scheduler._missed_windows(NOW - timedelta(minutes=59), NOW, 3600) == 0
    assert scheduler._missed_windows(NOW - timedelta(hours=3, minutes=5), NOW, 3600) == 3


def test_scheduler_requires_python_worker_and_scheduler_ownership(monkeypatch):
    captured = {}

    monkeypatch.setattr(scheduler, "_try_leader_lock", lambda _connection: True)
    monkeypatch.setattr(
        scheduler,
        "_python_scheduler_owned_keys",
        lambda _connection, _enabled: {"OWNER_CADASTRE_RELATIONS"},
    )
    monkeypatch.setattr(
        scheduler,
        "_organizations_for_spec",
        lambda _connection, _spec, _env: [ORG_ID],
    )
    monkeypatch.setattr(
        scheduler,
        "_latest_scheduled_at",
        lambda _connection, _org, _job: NOW - timedelta(days=5),
    )

    def enqueue(_connection, organization_id, job_key, parameters):
        captured.update(
            organization_id=organization_id,
            job_key=job_key,
            parameters=parameters,
        )
        return True

    monkeypatch.setattr(scheduler, "_enqueue", enqueue)

    cycle = scheduler.enqueue_due_jobs(
        object(),
        enabled_job_keys=("OWNER_CADASTRE_RELATIONS",),
        max_catchup_windows=2,
        now=NOW,
        environ={"METSIS_PORTFOLIO_ORGANIZATION_ID": str(ORG_ID)},
    )

    assert cycle.leader_acquired is True
    assert cycle.owned_jobs == 1
    assert cycle.enqueued == 1
    assert captured["organization_id"] == ORG_ID
    assert captured["job_key"] == "OWNER_CADASTRE_RELATIONS"
    metadata = captured["parameters"]["scheduler"]
    assert metadata["version"] == 1
    assert metadata["missedWindows"] == 2
    assert metadata["catchupTruncated"] is True


def test_scheduler_loser_does_not_read_or_enqueue_owned_jobs(monkeypatch):
    monkeypatch.setattr(scheduler, "_try_leader_lock", lambda _connection: False)

    def should_not_run(*_args, **_kwargs):
        raise AssertionError("non-leader scheduler must not inspect or enqueue jobs")

    monkeypatch.setattr(scheduler, "_python_scheduler_owned_keys", should_not_run)

    cycle = scheduler.enqueue_due_jobs(object(), now=NOW)

    assert cycle.leader_acquired is False
    assert cycle.enqueued == 0


def test_unknown_python_owned_job_fails_closed(monkeypatch):
    monkeypatch.setattr(scheduler, "_try_leader_lock", lambda _connection: True)
    monkeypatch.setattr(
        scheduler,
        "_python_scheduler_owned_keys",
        lambda _connection, _enabled: {"NEW_UNVERSIONED_SOURCE"},
    )

    cycle = scheduler.enqueue_due_jobs(object(), now=NOW)

    assert cycle.enqueued == 0
    assert cycle.unavailable_scope == 1


def test_restart_within_same_execution_window_does_not_enqueue_duplicate(monkeypatch):
    last_scheduled_at = NOW - timedelta(minutes=10)
    enqueue_calls = []

    monkeypatch.setattr(scheduler, "_try_leader_lock", lambda _connection: True)
    monkeypatch.setattr(
        scheduler,
        "_python_scheduler_owned_keys",
        lambda _connection, _enabled: {"OWNER_CADASTRE_RELATIONS"},
    )
    monkeypatch.setattr(
        scheduler,
        "_organizations_for_spec",
        lambda _connection, _spec, _env: [ORG_ID],
    )
    monkeypatch.setattr(
        scheduler,
        "_latest_scheduled_at",
        lambda _connection, _org, _job: last_scheduled_at,
    )
    monkeypatch.setattr(
        scheduler,
        "_enqueue",
        lambda *_args, **_kwargs: enqueue_calls.append(True) or True,
    )

    cycle = scheduler.enqueue_due_jobs(
        object(),
        enabled_job_keys=("OWNER_CADASTRE_RELATIONS",),
        now=NOW,
        environ={"METSIS_PORTFOLIO_ORGANIZATION_ID": str(ORG_ID)},
    )

    assert cycle.leader_acquired is True
    assert cycle.enqueued == 0
    assert cycle.not_due == 1
    assert enqueue_calls == []


def test_schedule_metadata_marks_trigger_contract_without_provider_payload(monkeypatch):
    captured = {}

    monkeypatch.setattr(scheduler, "_try_leader_lock", lambda _connection: True)
    monkeypatch.setattr(
        scheduler,
        "_python_scheduler_owned_keys",
        lambda _connection, _enabled: {"OWNER_CADASTRE_RELATIONS"},
    )
    monkeypatch.setattr(
        scheduler,
        "_organizations_for_spec",
        lambda _connection, _spec, _env: [ORG_ID],
    )
    monkeypatch.setattr(
        scheduler,
        "_latest_scheduled_at",
        lambda _connection, _org, _job: None,
    )

    def enqueue(_connection, organization_id, job_key, parameters):
        captured.update(
            organization_id=organization_id,
            job_key=job_key,
            parameters=parameters,
        )
        return True

    monkeypatch.setattr(scheduler, "_enqueue", enqueue)

    cycle = scheduler.enqueue_due_jobs(
        object(),
        enabled_job_keys=("OWNER_CADASTRE_RELATIONS",),
        now=NOW,
        environ={"METSIS_PORTFOLIO_ORGANIZATION_ID": str(ORG_ID)},
    )

    assert cycle.enqueued == 1
    assert captured["organization_id"] == ORG_ID
    assert captured["job_key"] == "OWNER_CADASTRE_RELATIONS"
    assert set(captured["parameters"]) == {"scheduler"}
    assert captured["parameters"]["scheduler"]["version"] == 1
    assert captured["parameters"]["scheduler"]["scheduledAt"] == NOW.isoformat()
