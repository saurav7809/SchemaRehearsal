param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Request {
    param([string]$Method, [string]$Path, $Body, $Form)
    $args = @{ Method=$Method; Uri="$BaseUrl/api$Path"; WebSession=$session; TimeoutSec=35 }
    if ($Method -ne 'GET') {
        $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -TimeoutSec 10
        $args.Headers = @{ $csrf.headerName = $csrf.token }
    }
    if ($null -ne $Body) { $args.ContentType='application/json'; $args.Body=($Body | ConvertTo-Json -Depth 10) }
    if ($null -ne $Form) { $args.Form=$Form }
    Invoke-RestMethod @args
}

function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function New-Session {
    $credentials = @{
        email = "smoke-$([guid]::NewGuid().ToString('N'))@example.test"
        password = [guid]::NewGuid().ToString('N')
    }
    Request POST '/auth/register' $credentials
    $user = Request POST '/auth/login' $credentials
    Assert ($user.email -eq $credentials.email) 'Authentication failed'
}

New-Session
$created = Request POST '/projects' @{ name='Smoke CRUD'; description=''; postgresVersion='18' }
$updated = Request PUT "/projects/$($created.id)" @{ name='Edited smoke project'; description=''; postgresVersion='18' }
Assert ($updated.name -eq 'Edited smoke project') 'Project update failed'
Request DELETE "/projects/$($created.id)"
$demo = Request POST '/projects/demo'
$project = "/projects/$($demo.id)"
$schema = Request GET "$project/schema"
Assert ($schema.'column public.users.email' -eq 'text NOT NULL') 'Baseline schema is missing'
$fixtures = Request GET "$project/migrations"
Assert ($fixtures.Count -eq 5) 'Demo migrations were not created'
$probe = Request POST "$project/queries" @{ name='New orders query'; generation='NEW'; sql='SELECT id FROM orders' }
Assert ($probe.generation -eq 'NEW') 'New application probe was not registered'
$sample = Get-Item -LiteralPath (Join-Path $PSScriptRoot '../backend/src/main/resources/demo/01_safe.sql')
$uploaded = Request POST "$project/migrations/upload" -Form @{ file=$sample }
Assert ($uploaded.name -eq $sample.Name) 'Multipart upload failed'
$fixtures = @($fixtures | Where-Object name -ne '01_safe.sql') + @($uploaded)

foreach ($migration in ($fixtures | Sort-Object name)) {
    $validation = Request POST "/migrations/$($migration.id)/validate"
    Assert $validation.valid "Validation failed for $($migration.name): $($validation.error)"
    $queued = Request POST "/migrations/$($migration.id)/test"
    $run = $queued
    $deadline = (Get-Date).AddSeconds(90)
    while ($run.status -in 'QUEUED','RUNNING' -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $run = Request GET "/tests/$($queued.id)"
    }
    Assert ($run.status -in 'SUCCEEDED','FAILED') 'Run never finished'
    Assert (-not $run.report.cleanupError) "Cleanup failed for run $($run.id)"
    switch ($migration.name) {
        '01_safe.sql' {
            Assert ($run.status -eq 'SUCCEEDED' -and $run.score -le 20) "Safe migration failed: $($run.report.error)"
            Assert ($run.report.changes.object -contains 'column public.users.notes') 'Schema difference missing'
            Assert ($run.report.compatibility.status -notcontains 'REGRESSION') 'Unexpected compatibility regression'
        }
        '02_breaking.sql' {
            Assert ($run.status -eq 'SUCCEEDED' -and $run.score -gt 40) 'Breaking migration had unexpected status or risk'
            Assert ($run.report.compatibility.status -contains 'REGRESSION') 'Missing old-query regression'
        }
        '03_not_null.sql' {
            Assert ($run.status -eq 'FAILED' -and $run.report.error -match '23502') 'Missing PostgreSQL NOT NULL evidence'
        }
        '04_invalid_cast.sql' {
            Assert ($run.status -eq 'FAILED' -and $run.report.error -match '22P02') 'Missing PostgreSQL cast evidence'
        }
        '05_drop_orders.sql' {
            Assert ($run.status -eq 'SUCCEEDED' -and $run.score -gt 70) 'Table removal had unexpected status or risk'
            Assert ($run.report.compatibility.status -contains 'NEW_QUERY_FAILED') 'Missing new-query failure'
        }
    }
    $pdf = Invoke-WebRequest -Uri "$BaseUrl/api/reports/$($run.id)/download" -WebSession $session
    $magic = [Text.Encoding]::ASCII.GetString($pdf.Content,0,5)
    Assert ($magic -eq '%PDF-') "Invalid PDF download for run $($run.id)"
    Write-Output ("{0,-21} {1,-10} score={2,3}  run={3}" -f $migration.name,$run.status,$run.score,$run.id)
}

$history = Request GET "$project/reports"
Assert ($history.Count -eq 5) 'Run history count is wrong'
$lastReport = $history[0].id
$indexFixture = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('rehearsal-smoke-' + [guid]::NewGuid().ToString('N')))
$indexFile = Join-Path $indexFixture.FullName 'V2__new_foreign_key.sql'
try {
    Set-Content -LiteralPath $indexFile -Value 'ALTER TABLE orders ADD COLUMN reviewer_id bigint REFERENCES users(id);' -Encoding utf8
    $indexMigration = Request POST "$project/migrations/upload" -Form @{ file=(Get-Item -LiteralPath $indexFile) }
    $indexRun = Request POST "/migrations/$($indexMigration.id)/test"
    $deadline = (Get-Date).AddSeconds(90)
    while ($indexRun.status -in 'QUEUED','RUNNING' -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $indexRun = Request GET "/tests/$($indexRun.id)"
    }
    Assert ($indexRun.status -eq 'SUCCEEDED') "Versioned Flyway migration failed: $($indexRun.report.error)"
    Assert ($indexRun.report.findings.title -contains 'New foreign key lacks a covering B-tree') 'Missing index evidence'
    Write-Output 'Versioned Flyway upload and catalog-backed index evidence passed.'
} finally {
    if (Test-Path -LiteralPath $indexFile) { Remove-Item -LiteralPath $indexFile }
    Remove-Item -LiteralPath $indexFixture.FullName
}

Request POST '/auth/logout'
New-Session
try {
    Request GET "/reports/$lastReport" | Out-Null
    throw 'A different account could access the report'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
}
Write-Output 'Smoke checks passed, including PDF downloads and project ownership.'
