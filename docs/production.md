# Production deployment on Windows

Use this setup for a single-node internal installation serving authenticated, trusted operators. Build the application into a Spring Boot JAR and static React assets. Put a TLS reverse proxy in front of the loopback API, and isolate the metadata PostgreSQL instance from the rehearsal PostgreSQL instance.

Keep SQL execution off until the acceptance checks below pass. Enable it only for reviewed SQL and sanitized fixtures. SQL parsing and PostgreSQL role restrictions reduce risk, but the execution instance still shares the Windows kernel with the API. Keep execution disabled for an internet-facing or untrusted-user deployment. The [local demo](windows.md) can continue using a single PostgreSQL instance.

## 1. Build a release

Use Java 21, Maven, Node.js 22 or later, Python 3, PowerShell 7, and PostgreSQL 18 tools. From the repository root, run:

```powershell
mvn.cmd -f backend/pom.xml verify
if ($LASTEXITCODE) { throw 'Backend build failed' }

npm.cmd --prefix frontend ci
if ($LASTEXITCODE) { throw 'Frontend install failed' }
npm.cmd --prefix frontend run build
if ($LASTEXITCODE) { throw 'Frontend build failed' }

python -m venv .venv
if ($LASTEXITCODE) { throw 'Python environment creation failed' }
.\.venv\Scripts\python.exe -m pip install -r analysis/requirements.txt
if ($LASTEXITCODE) { throw 'Analyzer dependency install failed' }
.\.venv\Scripts\python.exe -m pytest analysis scripts -q
if ($LASTEXITCODE) { throw 'Python tests failed' }
```

Retain the JAR from `backend/target`, `frontend/dist`, the analyzer source and pinned dependencies, and the deployment and database files as one versioned release. Recreate the virtual environment on the deployment host at its final path. Scan the release dependencies against your vulnerability policy and retain the scan result and artifact hashes.

## 2. Provision separate PostgreSQL instances

Allocate persistent directories outside the source checkout and any user sync folder. Run the databases, API, and proxy under dedicated non-administrator Windows service accounts. Give each PostgreSQL account access only to its own data and log directories. Put rehearsal data on a dedicated capacity-limited volume so its writes cannot fill the metadata volume. The paths below are examples. Run each `initdb` and the corresponding PostgreSQL service under the intended database service identity.

```powershell
$metadataData = 'D:\SchemaRehearsal\metadata'
$rehearsalData = 'E:\SchemaRehearsal\rehearsal'
if ((Test-Path -LiteralPath $metadataData) -or (Test-Path -LiteralPath $rehearsalData)) {
    throw 'Choose new PostgreSQL data directories'
}

initdb -D $metadataData -U sr_meta_setup -W --auth=scram-sha-256 --encoding=UTF8
if ($LASTEXITCODE) { throw 'Metadata initdb failed' }
initdb -D $rehearsalData -U sr_exec_setup -W --auth=scram-sha-256 --encoding=UTF8
if ($LASTEXITCODE) { throw 'Rehearsal initdb failed' }

Copy-Item -LiteralPath database\pg_hba.metadata.conf -Destination (Join-Path $metadataData 'pg_hba.conf') -Force
Copy-Item -LiteralPath database\pg_hba.rehearsal.conf -Destination (Join-Path $rehearsalData 'pg_hba.conf') -Force

Add-Content -LiteralPath (Join-Path $metadataData 'postgresql.conf') -Encoding ascii -Value @(
    "listen_addresses = '127.0.0.1'",
    'port = 55431',
    'max_connections = 24'
)
Add-Content -LiteralPath (Join-Path $rehearsalData 'postgresql.conf') -Encoding ascii -Value @(
    "listen_addresses = '127.0.0.1'",
    'port = 55432',
    'max_connections = 24',
    'max_prepared_transactions = 0'
)

pg_ctl -D $metadataData -l 'D:\SchemaRehearsal\metadata.log' -w start
if ($LASTEXITCODE) { throw 'Metadata startup failed' }
pg_ctl -D $rehearsalData -l 'E:\SchemaRehearsal\rehearsal.log' -w start
if ($LASTEXITCODE) { throw 'Rehearsal startup failed' }

psql -X -h 127.0.0.1 -p 55431 -U sr_meta_setup -d postgres -f database/setup-metadata.sql
if ($LASTEXITCODE) { throw 'Metadata bootstrap failed' }
psql -X -h 127.0.0.1 -p 55432 -U sr_exec_setup -d postgres -f database/setup-rehearsal.sql
if ($LASTEXITCODE) { throw 'Rehearsal bootstrap failed' }
```

Choose unique passwords at each prompt. Use `setup-metadata.sql` and `setup-rehearsal.sql` once on new instances. They grant the API roles read access to the PostgreSQL system identifier so startup can distinguish the two clusters. Keep the administrator passwords in the operator vault and supply only the runtime credentials to the API. The metadata application role owns its database for Flyway bootstrap and upgrades. Protect it accordingly.

Check the effective HBA rules and confirm that unauthorized connections fail. These templates replace the complete `pg_hba.conf` for each dedicated instance. PostgreSQL evaluates the first matching rule. On Windows, new connections can see changes to this file immediately. [PostgreSQL authentication rules](https://www.postgresql.org/docs/18/auth-pg-hba-conf.html)

```powershell
psql -X -h 127.0.0.1 -p 55431 -U sr_meta_setup -d postgres -c 'SELECT line_number, error FROM pg_hba_file_rules WHERE error IS NOT NULL'
psql -X -h 127.0.0.1 -p 55432 -U sr_exec_setup -d postgres -c 'SELECT line_number, error FROM pg_hba_file_rules WHERE error IS NOT NULL'
psql -X -h 127.0.0.1 -p 55431 -U sr_app -d sr_metadata -c 'SELECT system_identifier FROM pg_catalog.pg_control_system()'
psql -X -h 127.0.0.1 -p 55432 -U sr_broker -d sr_control -c 'SELECT system_identifier FROM pg_catalog.pg_control_system()'
psql -X -h 127.0.0.1 -p 55432 -U sr_worker -d sr_fixture -c 'SELECT 1'
```

The first two queries should return zero errors, the identifiers should differ, and the worker login to the fixture should be rejected. The rehearsal instance is reserved for `sr_control`, `sr_fixture`, PostgreSQL's built-in databases, disposable `sr_run_*` databases, and broker-owned `sr_baseline_*` databases with connections disabled. Keep business databases and the `sr_app` role on other instances. Keep the rehearsal instance free of extensions or role memberships that permit file access, outbound connections, or privilege escalation. Only the read-only `sr_reader` role should connect to the shared fixture from the application. Restrict the rehearsal service account's filesystem and outbound network access with host policy.

## 3. Configure and run the API

Set these non-secret environment values for the API service. Replace the paths and ports to match your installation. `SR_METADATA_URL` uses the exact loopback JDBC URL format shown below. The API and management listeners use loopback.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'production'
$env:SR_METADATA_URL = 'jdbc:postgresql://127.0.0.1:55431/sr_metadata'
$env:SR_PG_PORT = '55432'
$env:SR_MANAGEMENT_PORT = '8081'
$env:SR_PYTHON = 'D:\SchemaRehearsal\release\.venv\Scripts\python.exe'
$env:SR_ANALYZER = 'D:\SchemaRehearsal\release\analysis\analyze.py'
$env:SR_REGISTRATION_ENABLED = 'false'
$env:SR_EXECUTION_ENABLED = 'false'
```

Supply the following values through a secret manager or the service runner's protected environment injection. Avoid command-line arguments, committed files, machine-wide environment variables, and proxy logs for secrets.

| Variable | Value |
| --- | --- |
| `SR_APP_PASSWORD` | Metadata `sr_app` password |
| `SR_BROKER_PASSWORD` | Rehearsal `sr_broker` password |
| `SR_WORKER_PASSWORD` | Rehearsal `sr_worker` password |
| `SR_READER_PASSWORD` | Rehearsal `sr_reader` password |
| `SR_ENCRYPTION_KEY` | Base64-encoded 32 random bytes. Retain this key separately from database backups to decrypt restored fixture credentials. |

Run the JAR with the configured Java 21 executable. Create the temporary directory below and restrict it to the API service identity and authorized administrators. The analyzer and Flyway write transient SQL files there. Use a Windows service runner for automatic startup and bounded restart-on-failure. Preserve and rotate its logs. Start PostgreSQL before the API.

```powershell
java '-Djava.io.tmpdir=D:\SchemaRehearsal\api-tmp' -jar D:\SchemaRehearsal\release\schema-rehearsal-1.0.0.jar
```

Provision named accounts with restricted ingress. Temporarily set `SR_REGISTRATION_ENABLED=true` and restart the API while only an operator can reach the proxy. Create the required accounts, restore the disabled setting, restart, and verify that anonymous registration is rejected before granting team access. Built-in account sessions and the run executor are process-local, so deploy one API process per metadata database and plan for reauthentication after restarts.

## 4. Terminate TLS and serve the release

The [Caddy template](../deployment/Caddyfile) serves the built static bundle and forwards `/api` requests unchanged to the loopback API. Use a supported, patched Caddy build that supports `request_body` and a certificate issued for the internal DNS name. Configure the following values in the proxy service's own environment.

```powershell
$env:SR_SITE = 'rehearsal.example.com'
$env:SR_TLS_CERT = 'D:/SchemaRehearsal/tls/rehearsal-chain.pem'
$env:SR_TLS_KEY = 'D:/SchemaRehearsal/tls/rehearsal-key.pem'
$env:SR_WEB_ROOT = 'D:/SchemaRehearsal/release/frontend/dist'
caddy validate --config deployment/Caddyfile --adapter caddyfile
if ($LASTEXITCODE) { throw 'Proxy configuration is invalid' }
caddy run --config deployment/Caddyfile --adapter caddyfile
```

Run the proxy under its own service identity. Limit its filesystem access to the frontend release, TLS material, and its logs. Renew the certificate before expiry. The template disables Caddy's admin listener, so apply configuration or certificate changes with a controlled service restart. Use the host firewall and private ingress controls to allow HTTPS only from the operator network or VPN. Leave ports 8080, 8081, 55431, and 55432 inaccessible from other machines. The production UI and API share a single HTTPS origin. Use your access gateway to enforce organizational MFA and revoke ingress for departing operators. [Caddy configuration validation](https://caddyserver.com/docs/command-line#caddy-validate) · [Caddy Windows service guide](https://caddyserver.com/docs/running#windows-service)

## 5. Verify and operate

Poll these endpoints from the host or its monitoring agent.

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8081/actuator/health/readiness
```

Use liveness to detect a failed process and readiness to detect dependencies that prevent service. A healthy metadata-only deployment may report ready with execution disabled. Check execution separately in the application. Treat a readiness failure, exhausted disk, pending-run buildup, repeated HTTP errors, or a clone cleanup failure as an operational alert.

Before admitting operators or enabling execution, record the following release checks.

- Confirm that the HTTPS hostname, certificate chain, browser security headers, and secure session cookies work and that the API, management endpoint, and databases are reachable only on loopback.
- Confirm both health endpoints respond on the management listener and `/actuator/health/readiness` is blocked at the proxy.
- Confirm login, logout, CSRF rejection, registration disabled, project ownership checks, and the report download through the HTTPS origin. Apply gateway-level rate limiting for your ingress. The default production profile uses Caddy's loopback address as the peer, so clients share the API's per-address authentication budget.
- Verify the database allowlist, role isolation, separate system identifiers, and constrained rehearsal volume. Confirm the PostgreSQL instances use unique credentials.
- Run [the smoke test](../scripts/smoke.ps1) only on a disposable staging installation with test account registration and execution explicitly enabled. Exercise single-file migrations, a dependent Flyway bundle, file-level failure evidence, a breaking query, a result assertion failure with the same query shape, two saved baselines, and PDF download. Confirm disposable databases and deleted staging baseline clones are reclaimed.
- Measure sustained load with representative fixture sizes. Record acceptance limits for latency, throughput, and disk consumption. Account for up to five saved copies of the schema and data per project, an active run clone, a new snapshot during promotion, and WAL. Set a storage quota and alert on available capacity. Set backup recovery objectives and validate a restore before the first release.
- Explicitly set `SR_EXECUTION_ENABLED=true` and restart only after the isolation checks pass for that environment. Confirm that a test rehearsal runs on the rehearsal instance. Leave execution disabled if isolation or trusted-operator controls cannot be verified.

For an incident, restrict ingress, preserve logs and reports, and restart in execution-disabled mode. Review the rehearsal server logs and inspect leftover databases before re-enabling execution. The application uses reserved run names and owner checks to reclaim abandoned runs. Saved clone cleanup during a subsequent rehearsal is limited to reserved database names tracked for reclamation by the matching metadata database. Avoid broad manual database deletions. For a planned upgrade, restrict ingress, let submitted rehearsals finish, take backups, stop the API, then install the new release and check readiness before reopening access. Interrupted runs require resubmission.

For pull request checks, use a separate internal staging project and the approval-protected [GitHub workflow](github-ci.md). Bind its CI token to a saved baseline with sanitized data.

## Back up and restore

Back up metadata and any approved fixture on an access-controlled, encrypted destination separate from the databases. Use a PostgreSQL 18 backup client and a protected passfile or credential provider for scheduled execution. Metadata dumps contain account hashes, SQL, reports, encrypted database credentials, and references to saved baselines. Retain the release artifacts and encryption key in separately protected storage. The following logical dumps cover metadata and the shared fixture. Use the paired strategy below when retaining named baselines.

```powershell
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
pg_dump -h 127.0.0.1 -p 55431 -U sr_meta_setup -d sr_metadata -Fc -f "F:\RehearsalBackups\metadata-$stamp.dump"
if ($LASTEXITCODE) { throw 'Metadata backup failed' }
pg_dump -h 127.0.0.1 -p 55432 -U sr_exec_setup -d sr_fixture -Fc -f "F:\RehearsalBackups\fixture-$stamp.dump"
if ($LASTEXITCODE) { throw 'Fixture backup failed' }
```

Pause rehearsals during fixture backup so the backup session does not compete with cloning. Test logical restoration on fresh staging PostgreSQL instances with the same role names, bootstrapped HBA rules, and the matching application release. Restore into those staging databases with `pg_restore --exit-on-error --single-transaction`. A seeded fixture restore also needs `--clean --if-exists` so the restored tables can replace the bootstrap seed. Keep the backed-up ownership and grants, and provide the original encryption key and matching reader password when validating restored connections. [PostgreSQL backup and restore](https://www.postgresql.org/docs/18/backup.html)

Saved baselines are full PostgreSQL databases with connections disabled. To preserve them, drain rehearsals and stop the API, then capture the metadata and rehearsal clusters as a matched backup set using your approved physical PostgreSQL backup process. For filesystem copies, stop both PostgreSQL instances and capture both complete clusters, including any external WAL or tablespaces. Restore the pair in staging and verify baseline lists, historical reports, and a rehearsal against a restored saved version before allowing production use. Record actual recovery time and test an approved WAL/PITR strategy if your recovery-point target is shorter than the backup interval. [PostgreSQL filesystem backups](https://www.postgresql.org/docs/18/backup-file.html) · [PostgreSQL point-in-time recovery](https://www.postgresql.org/docs/18/continuous-archiving.html)
