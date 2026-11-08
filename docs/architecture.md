# Architecture and security

React and Vite provide a same-origin workspace. The loopback Spring Boot API handles cookie sessions, CSRF, project ownership, and persistence. Spring Data JPA handles accounts and projects. JDBC handles the report store, PostgreSQL introspection, and sandbox lifecycle. SQLGlot runs through a short-lived Python subprocess receiving JSON over stdin. No SQL is interpolated into shell commands.

## Rehearsal lifecycle

1. Confirm ownership, resolve a saved baseline, validate the upload, and capture the current query list.
2. Persist a queued run and schedule it on one worker with up to five accepted submissions and two active runs per account.
3. Acquire a cluster advisory lock through the broker. Recover abandoned databases owned by the broker whose names match the reserved run format.
4. Inspect the registered fixture read-only. Clone it into a random `sr_run_` database. Apply the connection limit and grant worker access.
5. Capture the clone's public schema and run old SELECT probes. Release all probe connections before migration.
6. Apply a single SQL file using Flyway. Plain SQL is named `V1__uploaded.sql` for the run. Standard Flyway versioned and repeatable SQL names are preserved. Versioned files must be above baseline version 0.
7. Capture the migrated schema, compare definitions, and run old and new probes. Compare execution and result column names and PostgreSQL types. Rows are sampled for count only, up to 100.
8. Drop the temporary database in a finally block, compute risk, and persist the final report and audit event.

Run one API instance per metadata database. The API holds a PostgreSQL advisory lease while it owns the queue. After acquiring the lease at startup, it marks interrupted runs as failed. The next rehearsal recovers abandoned clone databases. A run can succeed at Flyway and still show a compatibility regression. A PostgreSQL failure includes the real SQLSTATE and message. Schema comparisons cover public relations, columns, constraints, and indexes. Rename-like differences can appear as a removal plus an addition. SQLGlot may separately detect explicit renames.

## Scoring

Each distinct static or observed finding contributes LOW 5, MEDIUM 12, HIGH 26, or CRITICAL 45 points. Reports show the uncapped sum, factors, and cap at 100. Bands are LOW 0–20, MEDIUM 21–40, HIGH 41–70, and CRITICAL 71–100. A newly uncovered foreign key adds a catalog-backed index warning when its referencing columns lack a valid, unfiltered B-tree prefix. A slow rehearsal adds a measured fixture warning when Flyway takes at least three seconds including startup overhead. Timing and static risk are fixture evidence, and production timing depends on production load and data volume.

## Metadata model

| Relation | Purpose and relationships |
| --- | --- |
| `accounts` | Unique email and BCrypt password hash |
| `projects` | Owner FK, name, description, PostgreSQL version, creation time |
| `database_configs` | Project PK/FK, endpoint, role, AES-GCM ciphertext with per-write nonce |
| `migrations` | Project FK, filename, UTF-8 SQL, upload time |
| `probes` | Project FK, old or new application generation, SELECT text |
| `test_runs` | Project and migration FKs, status, duration, score, and report JSONB |
| `audit_events` | Account FK, action, subject ID, timestamp |

Report JSONB contains immutable completed-run snapshots, schema differences, query comparisons, findings, recommendations, and score factors. The report and the run share an ID. Project-owned configuration, migrations, probes, and runs cascade on project deletion. Audit events retain their numeric subject. The Flyway DDL documents the indexes and constraints.

## Production security boundary

The production profile keeps registration and execution off by default and sets the session cookie to Secure. Serve the built UI and the loopback API through a single TLS origin. Keep the management listener private. Startup tests SQLGlot in isolated Python mode. Readiness checks metadata connectivity and queue ownership. With execution enabled, it also checks the rehearsal broker and cluster boundary.

Production rehearsal requires an operator-controlled PostgreSQL 18 cluster. The API compares PostgreSQL system identifiers and verifies the rehearsal role restrictions and database allowlist. The fixture may contain the built-in PL/pgSQL extension and must be free of elevated user-defined functions. Connection tests and schema inspection in production accept the configured fixture target. See the [production deployment guide](production.md) for network and volume isolation, backup, and release checks.

## Supported workloads and limits

- Use synthetic or approved test data. In the local development profile, the backend inspects local registered databases through read-only transactions. Rehearsals in both profiles require the operator-controlled `sr_fixture` and reader role. Import custom representative data out of band with an operator account. Projects share the fixture. Keep it idle during runs. The broker provisions and removes temporary databases. Flyway connects as the worker to apply the migration.
- Apply the PostgreSQL host authentication rules. They restrict the worker login to run databases and deny app roles from other cluster databases. The worker is neither database owner nor a cluster administrator. Only the worker can change the fixture objects within a disposable clone.
- SQLGlot blocks unsupported statement families, cross-schema objects, and mutating probes. PostgreSQL privileges, read-only transactions, connection limits, timeouts, and forced cleanup provide separate controls. The SQL policy covers a subset of PostgreSQL syntax. PostgreSQL is the authority on actual execution.
- Uploads are limited to one 256 KiB SQL file per migration. Probes are read-only SELECTs. Advanced Flyway callbacks, arbitrary multi-file migration chains, stored procedural code, and automatic production cloning are outside this MVP.
- Interpret fixture timings and schema differences as rehearsal observations. Test actual lock or index behavior under representative load before drawing production conclusions.
- The Windows processes share a kernel. Temp-file limits and timeouts cover specific resource use. Use host-level volume limits and keep execution restricted to trusted operators and reviewed SQL. PDF exports replace characters outside the basic Courier text repertoire.
