from forestiq_ingest.database.staging import CopyStageSpec, copy_stage_rows


class FakeCopy:
    def __init__(self):
        self.rows = []

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False

    def write_row(self, row):
        self.rows.append(row)


class FakeCursor:
    def __init__(self):
        self.statements = []
        self.copy_context = FakeCopy()

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False

    def execute(self, sql):
        self.statements.append(" ".join(sql.split()))

    def copy(self, sql):
        self.statements.append("COPY: " + " ".join(sql.split()))
        return self.copy_context


class FakeConnection:
    def __init__(self):
        self.cursor_context = FakeCursor()

    def cursor(self):
        return self.cursor_context


def test_copy_stage_rows_executes_create_copy_and_load_for_rows():
    connection = FakeConnection()

    count = copy_stage_rows(
        connection,
        CopyStageSpec(
            create_temp_table_sql="CREATE TEMP TABLE load_table (id text)",
            truncate_sql="TRUNCATE load_table",
            copy_sql="COPY load_table (id) FROM STDIN",
            load_sql="INSERT INTO target SELECT id FROM load_table",
        ),
        [("a",), ("b",)],
    )

    assert count == 2
    assert connection.cursor_context.copy_context.rows == [("a",), ("b",)]
    assert connection.cursor_context.statements == [
        "CREATE TEMP TABLE load_table (id text)",
        "TRUNCATE load_table",
        "COPY: COPY load_table (id) FROM STDIN",
        "INSERT INTO target SELECT id FROM load_table",
    ]


def test_copy_stage_rows_skips_load_when_no_rows():
    connection = FakeConnection()

    count = copy_stage_rows(
        connection,
        CopyStageSpec(
            create_temp_table_sql="CREATE TEMP TABLE load_table (id text)",
            truncate_sql="TRUNCATE load_table",
            copy_sql="COPY load_table (id) FROM STDIN",
            load_sql="INSERT INTO target SELECT id FROM load_table",
        ),
        [],
    )

    assert count == 0
    assert connection.cursor_context.statements == [
        "CREATE TEMP TABLE load_table (id text)",
        "TRUNCATE load_table",
        "COPY: COPY load_table (id) FROM STDIN",
    ]
