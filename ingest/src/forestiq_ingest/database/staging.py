from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

import psycopg


@dataclass(frozen=True)
class CopyStageSpec:
    create_temp_table_sql: str
    truncate_sql: str
    copy_sql: str
    load_sql: str


def copy_stage_rows(
    connection: psycopg.Connection,
    spec: CopyStageSpec,
    rows: Iterable[Sequence[Any]],
) -> int:
    """Stage rows through a temp table and publish them with a set-based SQL statement."""

    count = 0
    with connection.cursor() as cursor:
        cursor.execute(spec.create_temp_table_sql)
        cursor.execute(spec.truncate_sql)
        with cursor.copy(spec.copy_sql) as copy:
            for count, row in enumerate(rows, start=1):
                copy.write_row(tuple(row))

        if count:
            cursor.execute(spec.load_sql)
    return count
