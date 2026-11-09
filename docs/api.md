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

## Migrations and probes

| Method and path | Body or behavior |
| --- | --- |
| POST `/api/projects/{id}/migrations/upload` | Repeat multipart field `file` for 1–20 UTF-8 SQL files, up to 256 KiB of SQL total |
| GET `/api/projects/{id}/migrations` | List uploads |
| GET `/api/migrations/{id}` | Read one migration or bundle, with `files` entries containing `name` and `sql` in upload order |
| POST `/api/migrations/{id}/validate` | Return `valid`, `error`, `statementCount`, and static `findings`, which may include `filename` |
| POST `/api/migrations/{id}/test` | Queue a rehearsal, returning HTTP 202 and a run ID |
| GET, POST `/api/projects/{id}/queries` | List or add. POST accepts `name`, `generation` (OLD or NEW), and `sql`. |
| DELETE `/api/projects/{id}/queries/{probe}` | Delete an owned query |

A single upload accepts a plain SQL filename. Every file in a multi-file bundle must use a Flyway name such as `V1__add_notes.sql`, `V1_2__backfill_notes.sql`, or `R__refresh_views.sql`. Use versions above 0 and unique Flyway versions and repeatable descriptions. Duplicate or equivalent versions are rejected with HTTP 400. Validation allows up to 100 statements across the bundle. The top-level `sql` field contains the first uploaded file for compatibility with single-file clients. Read `files` for the full bundle.

In Postman, choose **Body → form-data** and add one **File** row named `file` per SQL file. A PowerShell client can send repeated fields with `-Form @{ file = @((Get-Item .\V1__add_notes.sql), (Get-Item .\V2__rename_notes.sql)) }`. [PowerShell multipart forms](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/invoke-restmethod#-form)

## Tests and reports

| Method and path | Behavior |
| --- | --- |
| GET `/api/tests/{id}`, `/api/reports/{id}` | Poll run status and read detailed evidence |
| GET `/api/projects/{id}/tests`, `/api/projects/{id}/reports` | Project history |
| GET `/api/reports` | List owned run summaries |
| GET `/api/reports/{id}/download` | Download the completed report as a PDF |

Run status moves through QUEUED and RUNNING to SUCCEEDED or FAILED. The body includes status, score, timestamps, duration, and a report with workflow and evidence. A successful migration may have a HIGH or CRITICAL compatibility score.

`report.migrationFiles` lists files in Flyway execution order. Each entry contains `name`, `status` (`APPLIED`, `FAILED`, or `PENDING`), `durationMs`, and `error`. Duration and error may be null. Historical reports may lack this field. Flyway applies versioned migrations in numerical order and then repeatable migrations in description order. [Flyway migration ordering](https://documentation.red-gate.com/fd/migrations-271585107.html)
