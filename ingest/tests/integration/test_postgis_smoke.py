import os

import psycopg
import pytest


pytestmark = pytest.mark.integration


def test_postgis_database_is_reachable_and_extension_available():
    database_url = os.getenv("TEST_POSTGIS_URL")
    if not database_url:
        pytest.skip("TEST_POSTGIS_URL is required for PostGIS integration tests")

    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT current_database()")
            assert cursor.fetchone()[0]

            cursor.execute(
                "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'postgis')"
            )
            assert cursor.fetchone()[0] is True
