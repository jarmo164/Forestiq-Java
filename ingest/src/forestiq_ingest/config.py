from __future__ import annotations

import os
import socket
from dataclasses import dataclass
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None or raw_value == "":
        return default
    try:
        return int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


def _env_bool(name: str, default: bool = False) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None or raw_value == "":
        return default
    return raw_value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _env_csv(name: str) -> tuple[str, ...]:
    raw_value = os.getenv(name, "")
    return tuple(value.strip() for value in raw_value.split(",") if value.strip())


@dataclass(frozen=True)
class IngestSettings:
    database_url: str | None
    component: str
    worker_id: str
    enabled_job_keys: tuple[str, ...]
    poll_interval_seconds: int
    heartbeat_seconds: int
    lease_seconds: int
    temp_dir: Path
    log_level: str
    healthcheck_require_db: bool

    @classmethod
    def from_env(cls, component: str | None = None) -> "IngestSettings":
        configured_component = component or os.getenv("FORESTIQ_INGEST_COMPONENT") or "worker"
        legacy_poll_interval = _env_int("FORESTIQ_INGEST_POLL_INTERVAL_SECONDS", 15)
        if configured_component == "scheduler":
            poll_interval = _env_int("FORESTIQ_INGEST_SCHEDULER_POLL_SECONDS", legacy_poll_interval)
        elif configured_component == "worker":
            poll_interval = _env_int("FORESTIQ_INGEST_WORKER_POLL_SECONDS", legacy_poll_interval)
        else:
            poll_interval = legacy_poll_interval
        return cls(
            database_url=os.getenv("DATABASE_URL") or os.getenv("INGEST_DATABASE_URL"),
            component=configured_component,
            worker_id=os.getenv("FORESTIQ_INGEST_WORKER_ID") or socket.gethostname(),
            enabled_job_keys=_env_csv("FORESTIQ_INGEST_ENABLED_JOB_KEYS"),
            poll_interval_seconds=poll_interval,
            heartbeat_seconds=_env_int("FORESTIQ_INGEST_HEARTBEAT_SECONDS", 30),
            lease_seconds=_env_int("FORESTIQ_INGEST_LEASE_SECONDS", 300),
            temp_dir=Path(os.getenv("FORESTIQ_INGEST_TEMP_DIR", "/tmp/forestiq-ingest")),
            log_level=os.getenv("FORESTIQ_INGEST_LOG_LEVEL", "INFO").upper(),
            healthcheck_require_db=_env_bool("FORESTIQ_INGEST_HEALTHCHECK_REQUIRE_DB"),
        )
