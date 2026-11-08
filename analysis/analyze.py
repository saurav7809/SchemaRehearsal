"""Bounded PostgreSQL SQL policy and static findings. Input and output are JSON."""
import json
import logging
import sys

import sqlglot
from sqlglot import exp
from sqlglot.errors import SqlglotError

logging.getLogger("sqlglot").setLevel(logging.ERROR)

MIGRATION_ROOTS = (exp.Alter, exp.Create, exp.Drop, exp.TruncateTable,
                   exp.Insert, exp.Update, exp.Delete)
OBJECT_KINDS = {"TABLE", "INDEX", "VIEW", "MATERIALIZED VIEW", "SEQUENCE", "TYPE", "ENUM"}
BLOCKED_FUNCTIONS = {"set_config", "setseed", "query_to_xml", "query_to_xmlschema",
                     "query_to_xml_and_xmlschema", "ts_rewrite"}


def inspect(sql, probe=False):
    try:
        statements = [s for s in sqlglot.parse(sql, read="postgres") if s is not None]
    except (SqlglotError, RecursionError) as error:
        return {"valid": False, "error": str(error)[:600], "findings": []}

    if not statements or (probe and len(statements) != 1) or len(statements) > 100:
        return rejected("A probe needs one SELECT. A migration needs 1–100 statements.")
    for statement in statements:
        if probe:
            if not isinstance(statement, (exp.Select, exp.SetOperation)):
                return rejected("Compatibility probes must be a single read-only SELECT.")
            if any(isinstance(node, (*MIGRATION_ROOTS, exp.Into, exp.Lock, exp.Command))
                   for node in statement.walk()):
                return rejected("Compatibility probes cannot write data or lock rows.")
        elif (not isinstance(statement, MIGRATION_ROOTS)
              or isinstance(statement, (exp.Create, exp.Drop, exp.Alter))
              and statement.args.get("kind") not in OBJECT_KINDS
              or statement.find(exp.Command)):
            return rejected("Unsupported statement. Use supported schema changes or INSERT, UPDATE, and DELETE.")
        for function in statement.find_all(exp.Anonymous):
            name = function.name.lower()
            if name in BLOCKED_FUNCTIONS or name.startswith(("pg_", "lo_", "dblink")):
                return rejected("Database control, external access and dynamic SQL functions are unsupported.")
        for table in statement.find_all(exp.Table):
            if table.catalog or (table.db and table.db.lower() != "public"):
                return rejected("Only objects in the public schema are supported.")
            if table.name.lower() == "flyway_schema_history":
                return rejected("Flyway history is reserved.")
    return {"valid": True, "error": None,
            "findings": [] if probe else [issue for s in statements for issue in findings(s)]}


def rejected(message):
    return {"valid": False, "error": message, "findings": []}


def findings(statement):
    sql = statement.sql(dialect="postgres")[:1200]
    table = next(statement.find_all(exp.Table), None)
    table_name = table.sql(dialect="postgres") if table is not None else None

    def issue(title, severity, evidence, recommendation, column=None):
        return {"source": "STATIC", "title": title, "severity": severity,
                "table": table_name, "column": column, "statement": sql,
                "evidence": evidence, "recommendation": recommendation}

    if isinstance(statement, exp.Drop) and statement.kind == "TABLE":
        yield issue("Table removal", "CRITICAL", "DROP TABLE removes the relation and its rows.",
                    "Move readers and writers to a replacement table, verify compatibility, then remove the original in a later release.")
    if isinstance(statement, exp.TruncateTable):
        yield issue("Table truncation", "CRITICAL", "TRUNCATE removes all rows from its target tables.",
                    "Archive required rows and use a bounded, reviewed deletion plan.")
    if isinstance(statement, (exp.Delete, exp.Update)) and not statement.args.get("where"):
        yield issue("Unbounded data change", "HIGH", "The statement has no WHERE clause.",
                    "Scope the change to verified rows and rehearse a batched backfill.")
    if isinstance(statement, exp.Create) and statement.kind == "INDEX" and not statement.args.get("concurrently"):
        yield issue("Index build may block writes", "MEDIUM", "The index build does not use CONCURRENTLY.",
                    "Measure lock duration on representative data and consider a separate concurrent index migration.")
    if isinstance(statement, exp.Alter):
        for action in statement.args.get("actions", []):
            if isinstance(action, exp.Drop):
                if action.kind == "COLUMN":
                    column = next(iter(action.args.get("tables") or []), None)
                    yield issue("Column removal", "HIGH", "Existing data and queries may depend on the dropped column.",
                                "Add a replacement column, backfill, switch readers and writers, verify both versions, then drop the old column.",
                                column.name if column is not None else None)
                else:
                    yield issue("Constraint removal", "MEDIUM", "The migration removes a schema constraint.",
                                "Verify dependent queries and establish equivalent data integrity before removal.")
            if isinstance(action, (exp.RenameColumn, exp.AlterRename)):
                yield issue("Identifier renamed", "HIGH", "Queries using the original identifier may break.",
                            "Stage the change with a compatibility view or replacement column and migrate callers in phases.",
                            action.this.name if isinstance(action, exp.RenameColumn) else None)
            if isinstance(action, exp.AlterColumn) and action.args.get("dtype"):
                yield issue("Column type conversion", "HIGH", "Existing values may fail conversion and the table may be rewritten.",
                            "Profile values, add a parallel column, backfill with checked conversions and switch readers after verification.", action.name)
            if ((isinstance(action, exp.AlterColumn) and action.args.get("allow_null") is False)
                    or isinstance(action, exp.ColumnDef) and action.find(exp.NotNullColumnConstraint)):
                yield issue("NOT NULL enforcement", "MEDIUM", "Existing rows must satisfy the new nullability requirement.",
                            "Backfill nulls, verify the data, and enforce the constraint in a later migration.", action.name)


if __name__ == "__main__":
    request = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    sys.stdout.buffer.write(json.dumps(inspect(request["sql"], request.get("probe", False))).encode("utf-8"))
