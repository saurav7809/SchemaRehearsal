# SchemaRehearsal

**Safe Testing for Database Changes.**

Rehearse a PostgreSQL migration on a disposable copy of a test fixture. Inspect Flyway execution, schema changes, application query compatibility, and risk findings in a downloadable report.

For a single-node internal deployment, follow the [Windows production guide](docs/production.md). The production profile keeps registration and SQL execution off by default. Enabling execution requires a dedicated rehearsal PostgreSQL cluster.

## Run locally on Windows 11

Install Java 21, Maven, Node.js 22 or later, Python 3, PowerShell 7, and the PostgreSQL 18 command-line tools. Confirm the tools are on PATH.

```powershell
java -version
mvn.cmd -version
node --version
python --version
psql --version
```

Follow the [PostgreSQL setup guide](docs/windows.md) to create a small loopback-only cluster using your installed PostgreSQL 18 binaries. Use the passwords you select during that setup. The guide also explains how to use an existing dedicated development cluster.

From the repository root, set up the analyzer.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r analysis\requirements.txt
```

In the backend PowerShell window, from the repository root:

```powershell
Copy-Item .\backend\env.example.ps1 .\.local\backend-env.ps1
notepad .\.local\backend-env.ps1
. .\.local\backend-env.ps1
Set-Location backend
mvn.cmd spring-boot:run
```

The environment script prompts for role passwords. Set and retain the AES key as described in the setup guide. Spring Boot creates the metadata schema with Flyway on its first startup.

In another PowerShell window, from the repository root:

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev
```

Open [SchemaRehearsal](http://127.0.0.1:5173). Vite forwards same-origin API requests to the loopback Spring server on port 8080. The SQL analyzer is a short-lived Python subprocess and requires no separate server.

## Demo

1. Register, then open **Projects → Load demo**. The demo creates a project with its fixture connection, two old application probes, and five SQL migrations.
2. Open **Database → Inspect** to see the baseline. Open **Compatibility** to review the saved probes or add a new application SELECT.
3. Select a migration, validate it, and choose **Run rehearsal**. Each demo migration starts from the initial fixture.
4. Review the workflow, SQLSTATE evidence, risk factors, and schema changes. Use **Download PDF** to export the report.

| Migration | Expected result |
| --- | --- |
| `01_safe.sql` | Adds a nullable column. Succeeds with low risk. |
| `02_breaking.sql` | Drops `users.email`. Flyway succeeds and the old customer query regresses. |
| `03_not_null.sql` | Fails with SQLSTATE `23502` because an existing display name is NULL. |
| `04_invalid_cast.sql` | Fails with SQLSTATE `22P02` because an existing identifier contains letters. |
| `05_drop_orders.sql` | Drops `orders`. Flyway succeeds and the order query regresses. |

To rehearse changes together, select multiple Flyway SQL files in a single upload. For example, pair `V1__add_notes.sql` with `V2__rename_notes.sql`. Each run applies the selected bundle to one fresh clone in Flyway order. Open the report to see which files applied, which file failed, and which files remained pending.

## Verification

Run from the repository root.

```powershell
mvn.cmd -f backend/pom.xml test
.\.venv\Scripts\python.exe -m pytest analysis -q
npm.cmd --prefix frontend run build
.\scripts\smoke.ps1
```

The smoke script needs a running backend and a disposable development cluster. It registers test accounts and leaves results available for inspection. It checks the original demo, versioned and repeatable migration ordering, a conflict that occurs only when files run together, duplicate Flyway version rejection, and upload size and file count limits. Use `-BaseUrl http://127.0.0.1:5173` to test through the Vite proxy.

- [Windows and PostgreSQL setup](docs/windows.md)
- [Production deployment and operations](docs/production.md)
- [Architecture, data model, and security boundaries](docs/architecture.md)
- [REST API and Postman instructions](docs/api.md)
