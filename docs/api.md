# REST API

In local development, use `http://127.0.0.1:8080` or the Vite proxy. For production, use the configured HTTPS origin. Responses are JSON except PDF downloads and empty mutation responses.

## Authentication in Postman

1. Enable the cookie jar and call `GET /api/auth/csrf`. Keep its session cookie.
2. Add the returned `headerName` and `token` as a request header on every POST, PUT, and DELETE. Refresh the token after authentication changes.
3. With registration enabled, send `{"email":"you@example.test","password":"your chosen password"}` to `POST /api/auth/register`. Send the credentials of an existing account to `POST /api/auth/login`. Keep the replacement session cookie and fetch a new CSRF token.
4. Check the session with `GET /api/auth/me`. End it with `POST /api/auth/logout`.

Use the same host spelling for every request. Common error statuses are 400 for invalid input, 401 for missing or invalid authentication, 403 for CSRF rejection or disabled registration, 404 for missing or unowned resources, 409 for state conflicts, 413 for oversized uploads, 429 for authentication throttling or a full rehearsal queue, and 503 when execution is disabled or the queue is unavailable.

## Projects and baselines

| Method and path | Body or behavior |
| --- | --- |
| POST `/api/projects` | `name`, `description`, `postgresVersion` (string `18`) |
| GET `/api/projects` | List owned projects |
| GET, PUT, DELETE `/api/projects/{id}` | Read, update, delete. PUT takes the same fields as POST. |
| POST `/api/projects/demo` | Create an owned demo project |
| POST `/api/databases/test-connection` | `host`, integer `port`, `databaseName`, `username`, `password` |
| GET `/api/projects/{id}/database` | Read connection fields or fixture defaults. The response omits the password. |
| POST `/api/projects/{id}/database` | Save a connection using the same fields. Response omits the password. |
| GET `/api/projects/{id}/schema` | Inspect public schema objects of the saved connection |
| GET `/api/projects/{id}/baselines` | List saved project baselines with `id`, `projectId`, `name`, `createdAt`, and `sourceRunId` |
| DELETE `/api/projects/{id}/baselines/{baselineId}` | Remove a saved baseline from future use. Returns HTTP 204 or HTTP 409 while the project has active rehearsals. Historical reports keep the baseline identity. |

The shared fixture is always the default rehearsal source. Saved baselines are immutable snapshots of the resulting schema and data from successful rehearsals. A project can keep up to five. A baseline ID is scoped to its project and requests using a missing or different project's baseline return HTTP 404.

## Migrations and probes

| Method and path | Body or behavior |
| --- | --- |
| POST `/api/projects/{id}/migrations/upload` | Repeat multipart field `file` for 1–20 UTF-8 SQL files, up to 256 KiB of SQL total |
| GET `/api/projects/{id}/migrations` | List uploads |
| GET `/api/migrations/{id}` | Read one migration or bundle, with `files` entries containing `name` and `sql` in upload order |
| POST `/api/migrations/{id}/validate` | Return `valid`, `error`, `statementCount`, and static `findings`, which may include `filename` |
| POST `/api/migrations/{id}/test` | Queue a rehearsal, returning HTTP 202 and a run ID. Optional JSON fields `baselineId`, `saveBaselineAs`, and `lockImpactTable` select a source, request a saved result, and enable lock sampling. |
| GET, POST `/api/projects/{id}/queries` | List or add. POST accepts `name`, `generation` (OLD or NEW), `sql`, and an optional `assertion`. |
| DELETE `/api/projects/{id}/queries/{probe}` | Delete an owned query |

A single upload accepts a plain SQL filename. Every file in a multi-file bundle must use a Flyway name such as `V1__add_notes.sql`, `V1_2__backfill_notes.sql`, or `R__refresh_views.sql`. Use versions above 0 and unique Flyway versions and repeatable descriptions. Duplicate or equivalent versions are rejected with HTTP 400. Validation allows up to 100 statements across the bundle. The top-level `sql` field contains the first uploaded file for compatibility with single-file clients. Read `files` for the full bundle.

In Postman, choose **Body → form-data** and add one **File** row named `file` per SQL file. A PowerShell client can send repeated fields with `-Form @{ file = @((Get-Item .\V1__add_notes.sql), (Get-Item .\V2__rename_notes.sql)) }`. [PowerShell multipart forms](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/invoke-restmethod#-form)

Send `{"baselineId":17,"saveBaselineAs":"Release 3.2"}` to run against saved version 17 and retain the result if the rehearsal succeeds. Omit `baselineId` to use the shared fixture. Omit `saveBaselineAs` for a transient rehearsal. Labels can have up to 80 characters.

### Query result assertions

An assertion applies to the migrated result of an OLD or NEW query. Supply `assertion` with a `kind` and an integer JSON number in `expected`. Omit the assertion for execution and result-shape checks alone.

| Kind | Expected value | Migrated result |
| --- | --- | --- |
| `ROW_COUNT_EQUALS` | 0–100 | The query returns exactly that many rows. Use a scalar `COUNT(*)` query for larger totals. |
| `SCALAR_EQUALS` | −9,007,199,254,740,991 to 9,007,199,254,740,991 | The query returns one row and one non-null exact integer in a `smallint`, `integer`, `bigint`, or `numeric` column. The integer equals the expectation. |

This OLD query checks the total of a seeded order.

```json
{
  "name": "Order total",
  "generation": "OLD",
  "sql": "SELECT total_cents FROM orders WHERE id=1",
  "assertion": {"kind": "SCALAR_EQUALS", "expected": 2499}
}
```

Read the outcome in `report.compatibility[].migrated.assertion`, which contains `kind`, `expected`, `status` (`PASS` or `FAIL`), and `reason`. The comparison's existing execution and shape status is separate. A query can retain the same shape and fail its assertion. Assertion evidence uses expectations, outcomes, and bounded counts. Scalar values and result rows stay out of that evidence.

## Tests and reports

| Method and path | Behavior |
| --- | --- |
| GET `/api/tests/{id}`, `/api/reports/{id}` | Poll run status and read detailed evidence |
| GET `/api/projects/{id}/tests`, `/api/projects/{id}/reports` | Project history |
| GET `/api/reports` | List owned run summaries |
| GET `/api/reports/{id}/download` | Download the completed report as a PDF |

Run status moves through QUEUED and RUNNING to SUCCEEDED or FAILED. The body includes status, score, timestamps, duration, and a report with workflow and evidence. A successful migration may have a HIGH or CRITICAL compatibility score.

`report.migrationFiles` lists files in Flyway execution order. Each entry contains `name`, `status` (`APPLIED`, `FAILED`, or `PENDING`), `durationMs`, and `error`. Duration and error may be null. Historical reports may lack this field. Flyway applies versioned migrations in numerical order and then repeatable migrations in description order. [Flyway migration ordering](https://documentation.red-gate.com/fd/migrations-271585107.html)

`report.baseline` records the source `id`, `name`, and `sourceRunId`. For the shared fixture, use its `name` and treat missing IDs as null. A successful run that saves a baseline includes the new baseline in `report.savedBaseline`. Older reports may lack both fields.

### Lock-impact sampling

For a manual rehearsal, submit `{"lockImpactTable":"users"}` to sample contention on `public.users`. Use an unqualified lower-case table name matching `[a-z_][a-z0-9_]{0,62}`. Invalid identifiers return HTTP 400. The queued report records the selection as `lockImpactTable`.

During Flyway execution, a bounded sampler on a separate connection requests a PostgreSQL `ROW EXCLUSIVE` lock on the named table in a short transaction and rolls it back. This is the table-level lock mode normally taken by writes. The sampler issues no row modifications. [PostgreSQL lock modes](https://www.postgresql.org/docs/18/explicit-locking.html#LOCKING-TABLES)

The final report adds `lockImpact` with `table`, `status` (`MEASURED` or `UNAVAILABLE`), `attempts`, `blockedAttempts`, `timedOutAttempts`, `maxWaitMs`, and a `note`. Each immediate `NOWAIT` request counts as an attempt. A conflict counts as blocked and triggers a retry with a 500 ms lock timeout. Retries that hit that limit count as timed out. The maximum wait is the longest retry round trip measured by the sampler, including timeouts. [PostgreSQL lock timeouts](https://www.postgresql.org/docs/18/runtime-config-client.html#GUC-LOCK-TIMEOUT)

`UNAVAILABLE` can indicate an absent table or an incomplete probe. Zero blocked attempts means the sampler observed no conflict in its window. Interpret the result as table-level evidence from one rehearsal clone. The sample is advisory and independent of migration success and the CI verdict.

## CI token management

Use the owner's browser session for these endpoints. Send a CSRF token for writes.

| Method and path | Behavior |
| --- | --- |
| GET `/api/projects/{id}/ci-token` | Read `configured`, `baselineId`, `baselineName`, and `createdAt` |
| POST `/api/projects/{id}/ci-token` | Issue or rotate the token for an owned, published `baselineId`. Returns the settings and the bearer `token` once. |
| DELETE `/api/projects/{id}/ci-token` | Revoke the token. Returns HTTP 204. |

A project has one active CI token. The token remains valid until rotation, revocation, or removal of its baseline. Store it in the CI environment's secrets and send it in the `Authorization: Bearer <token>` header for CI requests. See the [GitHub setup guide](github-ci.md) for the workflow.

## CI rehearsals

Send `Authorization: Bearer <token>` with every request. The token fixes the project and saved baseline for the submission.

| Method and path | Behavior |
| --- | --- |
| POST `/api/ci/rehearsals` | Submit multipart `file` parts using the migration upload limits. Include `repository` as `owner/repo`, `pullRequest` as a positive integer, and `commit` as the 40-character lowercase hexadecimal head SHA. Returns HTTP 202 and the run result view. |
| GET `/api/ci/rehearsals/{id}` | Read the result for a CI run belonging to the token's project and baseline. |

The result view has `runId`, `status`, `verdict` (`PENDING`, `PASS`, or `FAIL`), `reason`, `failedFile`, `sqlState`, `compatibilityRegressions`, `assertionFailures`, and `reportPath`. The failed filename and SQLSTATE can be null. A failed rehearsal, regression in an OLD query, or failed result assertion yields `FAIL`. Full reports use the usual owner session. An invalid or revoked token returns HTTP 401, and an unavailable run returns HTTP 404.
