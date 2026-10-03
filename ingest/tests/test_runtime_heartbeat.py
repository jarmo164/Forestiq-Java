from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from forestiq_ingest.database.runtime_health import record_runtime_heartbeat


def test_runtime_heartbeat_upserts_without_exposing_secrets():
    cursor = MagicMock()
    connection = MagicMock()
    connection.cursor.return_value.__enter__.return_value = cursor
    connector = MagicMock()
    connector.__enter__.return_value = connection

    settings = SimpleNamespace(
        database_url="postgresql://user:secret@example/db",
        component="worker",
        worker_id="worker-1",
        enabled_job_keys=("OWNER_CADASTRE_RELATIONS",),
        poll_interval_seconds=2,
        heartbeat_seconds=15,
    )

    with patch("forestiq_ingest.database.runtime_health.psycopg.connect", return_value=connector):
        assert record_runtime_heartbeat(settings) is True

    sql = cursor.execute.call_args.args[0]
    params = cursor.execute.call_args.args[1]
    assert "ON CONFLICT" in sql
    assert params[0] == "worker"
    assert params[1] == "worker-1"
    assert "secret" not in params[2]
