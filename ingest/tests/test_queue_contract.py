from datetime import date
from uuid import UUID

import pytest

from forestiq_ingest.database.queue import claim_next_job, complete_job, update_job_state


class CursorStub:
    def __init__(self):
        self.rowcount = 0
        self.calls = []

    def execute(self, query, params=None):
        self.calls.append((query, params))
        self.rowcount = 1

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class ConnectionStub:
    def __init__(self):
        self.cursor_stub = CursorStub()

    def cursor(self, *_, **__):
        return self.cursor_stub


def test_update_job_state_accepts_only_active_states():
    connection = ConnectionStub()
    assert update_job_state(
        connection,
        run_id=UUID("00000000-0000-0000-0000-000000000001"),
        worker_id="worker-a",
        state="VALIDATING",
    )

    query, params = connection.cursor_stub.calls[0]
    assert "state IN ('RUNNING', 'VALIDATING', 'PUBLISHING')" in query
    assert params[0] == "VALIDATING"


def test_complete_job_bounds_error_summary_and_sets_counters():
    connection = ConnectionStub()
    assert complete_job(
        connection,
        run_id=UUID("00000000-0000-0000-0000-000000000001"),
        worker_id="worker-a",
        state="SUCCEEDED",
        metrics={"rows": 3},
        message="done",
        error_summary="x" * 600,
        rows_read=5,
        rows_staged=4,
        rows_written=3,
        rows_rejected=2,
        bytes_read=10,
        bytes_staged=9,
        bytes_written=8,
        source_version="v1",
        source_date=date(2026, 9, 17),
        source_checksum="abc",
    )

    _query, params = connection.cursor_stub.calls[0]
    assert params[0] == "SUCCEEDED"
    assert params[4] == 5
    assert params[7] == 2
    assert len(params[3]) == 500


def test_complete_job_rejects_non_terminal_state():
    with pytest.raises(ValueError):
        complete_job(
            ConnectionStub(),
            run_id=UUID("00000000-0000-0000-0000-000000000001"),
            worker_id="worker-a",
            state="RUNNING",
        )


class EmptyClaimCursorStub(CursorStub):
    def fetchone(self):
        return None


class EmptyClaimConnectionStub(ConnectionStub):
    def __init__(self):
        self.cursor_stub = EmptyClaimCursorStub()


def test_python_claim_filters_to_python_owned_job_keys():
    connection = EmptyClaimConnectionStub()

    assert claim_next_job(
        connection,
        worker_id="python-worker-a",
        lease_seconds=300,
        enabled_job_keys=("METSAREGISTER_WFS_MIRROR",),
    ) is None

    query, params = connection.cursor_stub.calls[0]
    assert "integration_job_ownership owner" in query
    assert "owner.worker_type = 'python-ingest-worker'" in query
    assert "run.job_key = ANY" in query
    assert params["job_keys"] == ["METSAREGISTER_WFS_MIRROR"]
