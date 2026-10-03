from __future__ import annotations

from contextlib import contextmanager
from typing import Iterator

import psycopg
from psycopg_pool import ConnectionPool

from forestiq_ingest.config import IngestSettings


@contextmanager
def connection_pool(settings: IngestSettings) -> Iterator[ConnectionPool]:
    if not settings.database_url:
        raise RuntimeError("DATABASE_URL or INGEST_DATABASE_URL is required")

    pool = ConnectionPool(settings.database_url, min_size=1, max_size=5, open=True)
    try:
        yield pool
    finally:
        pool.close()


def check_database(settings: IngestSettings) -> bool:
    if not settings.database_url:
        return False

    try:
        with psycopg.connect(settings.database_url, connect_timeout=5) as connection:
            with connection.cursor() as cursor:
                cursor.execute("select 1")
                return cursor.fetchone() == (1,)
    except psycopg.Error:
        return False
