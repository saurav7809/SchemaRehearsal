import pytest
from analyze import inspect, inspect_bundle


@pytest.mark.parametrize(("sql", "title"), [
    ("ALTER TABLE users ADD COLUMN notes text", None),
    ("ALTER TABLE users DROP COLUMN email", "Column removal"),
    ("ALTER TABLE users ALTER COLUMN display_name SET NOT NULL", "NOT NULL enforcement"),
    ("ALTER TABLE users ALTER COLUMN external_id TYPE integer USING external_id::integer", "Column type conversion"),
    ("DROP TABLE orders", "Table removal"),
    ("TRUNCATE TABLE users", "Table truncation"),
])
def test_scenarios(sql, title):
    result = inspect(sql)
    assert result["valid"]
    assert [item["title"] for item in result["findings"]] == ([] if title is None else [title])


@pytest.mark.parametrize("sql", [
    "ALTER ROLE sr_worker SUPERUSER", "SET statement_timeout = 0",
    "COPY users TO PROGRAM 'test'", "CREATE EXTENSION dblink", "DROP TABLE other.users",
    "DROP TABLE flyway_schema_history", "ALTER TABLE",
])
def test_migration_policy(sql):
    assert not inspect(sql)["valid"]


@pytest.mark.parametrize("sql", [
    "SELECT id FROM users; SELECT email FROM users",
    "WITH x AS (DELETE FROM users RETURNING *) SELECT * FROM x",
    "SELECT * INTO copy FROM users", "SELECT * FROM users FOR UPDATE",
])
def test_probe_policy(sql):
    assert not inspect(sql, probe=True)["valid"]


def test_query():
    assert inspect("SELECT id, email FROM users", probe=True)["valid"]


@pytest.mark.parametrize(("sql", "probe"), [
    ("UPDATE users SET display_name = pg_catalog.set_config('statement_timeout', '0', false)", False),
    ("SELECT pg_sleep(600)", True),
    ("SELECT pg_advisory_lock(15)", True),
    ("SELECT query_to_xml('SELECT 1', true, false, '')", True),
    ("SELECT public.dblink_connect('another_database')", True),
])
def test_blocks_database_control_and_dynamic_sql_functions(sql, probe):
    assert not inspect(sql, probe=probe)["valid"]


def test_bundle_preserves_file_evidence_and_statement_count():
    result = inspect_bundle([
        {"name": "V1__add.sql", "sql": "ALTER TABLE users ADD COLUMN notes text;"},
        {"name": "V2__drop.sql", "sql": "ALTER TABLE users DROP COLUMN email;"},
    ])
    assert result["valid"]
    assert result["statementCount"] == 2
    assert [finding["filename"] for finding in result["findings"]] == ["V2__drop.sql"]


def test_bundle_rejects_an_invalid_second_file():
    result = inspect_bundle([
        {"name": "V1__add.sql", "sql": "ALTER TABLE users ADD COLUMN notes text;"},
        {"name": "V2__unsafe.sql", "sql": "SET statement_timeout = 0;"},
    ])
    assert not result["valid"]
    assert result["error"].startswith("V2__unsafe.sql:")
    assert not result["findings"]


def test_bundle_enforces_aggregate_statement_limit():
    statement = "UPDATE users SET display_name = 'x' WHERE id = 1;"
    files = [
        {"name": "V1__one.sql", "sql": statement * 50},
        {"name": "V2__two.sql", "sql": statement * 50},
    ]
    assert inspect_bundle(files)["valid"]
    files[-1]["sql"] += statement
    assert not inspect_bundle(files)["valid"]
