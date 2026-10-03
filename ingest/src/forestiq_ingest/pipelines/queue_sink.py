from __future__ import annotations

from uuid import UUID

import psycopg

from forestiq_ingest.database.queue import complete_job, update_job_state
from forestiq_ingest.pipelines.base import PipelineCounters, PipelineRunSink, PipelineState


class QueueRunSink(PipelineRunSink):
    def __init__(self, connection: psycopg.Connection, *, run_id: UUID, worker_id: str):
        self.connection = connection
        self.run_id = run_id
        self.worker_id = worker_id

    def transition(self, state: PipelineState, counters: PipelineCounters) -> None:
        if state in {PipelineState.RUNNING, PipelineState.VALIDATING, PipelineState.PUBLISHING}:
            update_job_state(self.connection, run_id=self.run_id, worker_id=self.worker_id, state=state.value)

    def complete_success(self, counters: PipelineCounters, message: str, published_generation_id: UUID | None = None) -> None:
        complete_job(
            self.connection,
            run_id=self.run_id,
            worker_id=self.worker_id,
            state=PipelineState.SUCCEEDED.value,
            message=message,
            metrics={"rowsRead": counters.rows_read, "rowsWritten": counters.rows_written},
            rows_read=counters.rows_read,
            rows_staged=counters.rows_staged,
            rows_written=counters.rows_written,
            rows_rejected=counters.rows_rejected,
            bytes_read=counters.bytes_read,
            bytes_staged=counters.bytes_staged,
            bytes_written=counters.bytes_written,
            published_generation_id=published_generation_id,
        )

    def complete_failure(self, counters: PipelineCounters, message: str, error_summary: str) -> None:
        complete_job(
            self.connection,
            run_id=self.run_id,
            worker_id=self.worker_id,
            state=PipelineState.FAILED.value,
            message=message,
            error_summary=error_summary,
            metrics={"rowsRead": counters.rows_read, "rowsRejected": counters.rows_rejected},
            rows_read=counters.rows_read,
            rows_staged=counters.rows_staged,
            rows_written=counters.rows_written,
            rows_rejected=counters.rows_rejected,
            bytes_read=counters.bytes_read,
            bytes_staged=counters.bytes_staged,
            bytes_written=counters.bytes_written,
        )
