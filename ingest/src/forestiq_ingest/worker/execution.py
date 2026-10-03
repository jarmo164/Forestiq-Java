from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from psycopg import Connection

from forestiq_ingest.config import IngestSettings
from forestiq_ingest.database.queue import ClaimedJob


@dataclass(frozen=True)
class ExecutionResult:
    state: str = "SUCCEEDED"
    metrics: dict[str, Any] = field(default_factory=dict)
    message: str | None = None
    error_summary: str | None = None
    rows_read: int = 0
    rows_staged: int = 0
    rows_written: int = 0
    rows_rejected: int = 0
    bytes_read: int = 0
    bytes_staged: int = 0
    bytes_written: int = 0
    source_version: str | None = None
    source_checksum: str | None = None
    published_generation_id: Any | None = None


Executor = Callable[[Connection, ClaimedJob, IngestSettings], ExecutionResult]


_EXECUTORS: dict[str, Executor] = {}
_BUILTINS_LOADED = False


def register_executor(job_key: str, executor: Executor) -> None:
    if not job_key or not job_key.strip():
        raise ValueError("job_key is required")
    _EXECUTORS[job_key.strip()] = executor


def _load_builtin_executors() -> None:
    global _BUILTINS_LOADED
    if _BUILTINS_LOADED:
        return
    from forestiq_ingest.worker.executors.cadastre import execute_cadastre
    from forestiq_ingest.worker.executors.forest_projection import execute_forest_projection
    from forestiq_ingest.worker.executors.inheritance import execute_inheritance_notices
    from forestiq_ingest.worker.executors.metsaregister import execute_metsaregister_mirror
    from forestiq_ingest.worker.executors.owner_portfolio import execute_owner_portfolio
    from forestiq_ingest.worker.executors.weasel import execute_weasel

    register_executor("CADASTRE_PUBLIC_DATA", execute_cadastre)
    register_executor("METSAREGISTER_WFS_MIRROR", execute_metsaregister_mirror)
    register_executor("OWNER_CADASTRE_RELATIONS", execute_owner_portfolio)
    register_executor("WEASEL_OWNERSHIP_TRANSITIONS", execute_weasel)
    register_executor("FOREST_NOTIFICATIONS", execute_forest_projection)
    register_executor("FOREST_PLAN", execute_forest_projection)
    register_executor("INHERITANCE_NOTICES", execute_inheritance_notices)
    _BUILTINS_LOADED = True


def executor_for(job_key: str) -> Executor | None:
    _load_builtin_executors()
    return _EXECUTORS.get(job_key)


def execute_claimed_job(
    connection: Connection,
    job: ClaimedJob,
    settings: IngestSettings,
) -> ExecutionResult:
    if job.job_key == "DUMMY_HEALTHCHECK":
        return ExecutionResult(
            metrics={"contractVersion": job.contract_version, "attempts": job.attempts},
            message="Python ingestion queue contract smoke run completed.",
        )

    executor = executor_for(job.job_key)
    if executor is None:
        return ExecutionResult(
            state="FAILED",
            metrics={"contractVersion": job.contract_version, "attempts": job.attempts},
            message="Python adapter is not registered for this job key yet.",
            error_summary=f"No Python adapter registered for {job.job_key}",
        )

    return executor(connection, job, settings)
