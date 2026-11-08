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
| POST `/api/projects/{id}/migrations/upload` | Multipart field `file` containing UTF-8 SQL |
| GET `/api/projects/{id}/migrations` | List uploads |
| GET `/api/migrations/{id}` | Read one migration with SQL |
| POST `/api/migrations/{id}/validate` | Return `valid`, `error`, and static `findings` |
| POST `/api/migrations/{id}/test` | Queue a rehearsal, returning HTTP 202 and a run ID |
| GET, POST `/api/projects/{id}/queries` | List or add. POST accepts `name`, `generation` (OLD or NEW), and `sql`. |
| DELETE `/api/projects/{id}/queries/{probe}` | Delete an owned query |

## Tests and reports

| Method and path | Behavior |
| --- | --- |
| GET `/api/tests/{id}`, `/api/reports/{id}` | Poll run status and read detailed evidence |
| GET `/api/projects/{id}/tests`, `/api/projects/{id}/reports` | Project history |
| GET `/api/reports` | List owned run summaries |
| GET `/api/reports/{id}/download` | Download the completed report as a PDF |

Run status moves through QUEUED and RUNNING to SUCCEEDED or FAILED. The body includes status, score, timestamps, duration, and a report with workflow and evidence. A successful migration may have a HIGH or CRITICAL compatibility score.
