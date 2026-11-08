# Windows and PostgreSQL setup

Use PowerShell 7. Set `JAVA_HOME` to the Java 21 installation directory and add its `bin` to PATH if Maven currently uses another version. If PostgreSQL tools are missing from PATH, run:

```powershell
$env:Path = "C:\Program Files\PostgreSQL\18\bin;$env:Path"
```

## Create a dedicated PostgreSQL 18 cluster

Run from the repository root. The commands use the locally installed binaries, an isolated data directory, and port 55432. Choose a setup password when prompted by `initdb`. Keep this administrator password separate from the application passwords.

```powershell
New-Item -ItemType Directory -Force .local | Out-Null
$pgData = Join-Path (Get-Location) '.local\pgdata'
$pgLog = Join-Path (Get-Location) '.local\postgres.log'
initdb -D $pgData -U sr_setup -W --auth=scram-sha-256 --encoding=UTF8
pg_ctl -D $pgData -l $pgLog -o "-h 127.0.0.1 -p 55432 -c shared_buffers=32MB -c max_connections=24" -w start
psql -X -h 127.0.0.1 -p 55432 -U sr_setup -d postgres -f database/setup.sql
```

`setup.sql` prompts for the four dedicated role passwords. Choose distinct passwords and retain them. Run the provisioning SQL once on a new cluster. Add the authentication restrictions at the beginning of the cluster's `pg_hba.conf`, before broader rules.

```powershell
$hba = Join-Path $pgData 'pg_hba.conf'
$rules = Get-Content database/pg_hba.rules -Raw
$existing = Get-Content $hba -Raw
Set-Content -LiteralPath $hba -Value ($rules + "`r`n" + $existing) -Encoding ascii
pg_ctl -D $pgData reload
pg_isready -h 127.0.0.1 -p 55432
```

Verify that `sr_reader` can connect to the fixture and `sr_worker` cannot connect there. The worker attempt should be rejected by PostgreSQL.

```powershell
psql -X -h 127.0.0.1 -p 55432 -U sr_reader -d sr_fixture -c "select count(*) from users"
psql -X -h 127.0.0.1 -p 55432 -U sr_worker -d sr_fixture -c "select 1"
```

To use an existing **dedicated development cluster**, run the same setup SQL as its administrator, install the authentication rules before its broader rules, reload, and set the actual port in the backend environment script. Place the app and database on loopback. Never provision these roles on a production cluster.

## Generate and retain the encryption key

Generate the key once and store the output in a password manager. Supply the same value when prompted by the environment script on every restart. Losing it makes existing encrypted credentials unreadable.

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

See the [README](../README.md) for backend and frontend startup. If you use a manual project, enter the configured cluster port, database `sr_fixture`, username `sr_reader`, and that role's password. **Load demo** fills in the baseline for you.

## Stop and restart

Stop both app processes with Ctrl+C. Stop the dedicated database after the API has exited.

```powershell
pg_ctl -D $pgData -m fast -w stop
```

On restart, run the `pg_ctl ... start` command with the same data directory and port. Reuse the role passwords and encryption key.
