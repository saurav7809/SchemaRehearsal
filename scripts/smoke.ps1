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

function Start-Rehearsal([long]$MigrationId) {
    $queued = Request POST "/migrations/$MigrationId/test"
    $run = $queued
    $deadline = (Get-Date).AddSeconds(90)
    while ($run.status -in 'QUEUED','RUNNING' -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $run = Request GET "/tests/$($queued.id)"
    }
    Assert ($run.status -in 'SUCCEEDED','FAILED') "Run $($run.id) never finished"
    Assert (-not $run.report.cleanupError) "Cleanup failed for run $($run.id)"
    $run
}

function Assert-RunFiles($Run, [string[]]$Names, [string[]]$States) {
    $files = @($Run.report.migrationFiles)
    Assert ($files.Count -eq $Names.Count) "Wrong file count for run $($Run.id)"
    for ($i = 0; $i -lt $Names.Count; $i++) {
        $file = $files[$i]
        Assert ($file.name -eq $Names[$i] -and $file.status -eq $States[$i]) "Wrong file order or status for run $($Run.id) at index $i"
        if ($file.status -in 'APPLIED','FAILED') {
            Assert ($null -ne $file.durationMs -and $file.durationMs -ge 0) "Attempted file $($file.name) is missing its duration"
        } else {
            Assert ($null -eq $file.durationMs) "Pending file $($file.name) has an execution duration"
        }
    }
}

function Assert-Pdf([long]$RunId) {
    $pdf = Invoke-WebRequest -Uri "$BaseUrl/api/reports/$RunId/download" -WebSession $session
    $magic = [Text.Encoding]::ASCII.GetString($pdf.Content,0,5)
    Assert ($magic -eq '%PDF-') "Invalid PDF download for run $RunId"
}

function New-SqlFile([string]$Directory, [string]$Name, [string]$Sql) {
    $path = Join-Path $Directory $Name
    Set-Content -LiteralPath $path -Value $Sql -Encoding utf8
    Get-Item -LiteralPath $path
}

function Assert-UploadRejected([string]$Project, [System.IO.FileInfo[]]$Files, [int]$Status) {
    $before = (Request GET "$Project/migrations").Count
    try {
        Request POST "$Project/migrations/upload" -Form @{ file=$Files } | Out-Null
        throw "Upload should have returned HTTP $Status"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne $Status) { throw }
    }
    Assert ((Request GET "$Project/migrations").Count -eq $before) 'Rejected bundle left behind a migration'
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
Assert ($uploaded.files.Count -eq 1 -and $uploaded.files[0].name -eq $sample.Name) 'Single-file upload is missing its file entry'
$fixtures = @($fixtures | Where-Object name -ne '01_safe.sql') + @($uploaded)

foreach ($migration in ($fixtures | Sort-Object name)) {
    $validation = Request POST "/migrations/$($migration.id)/validate"
    Assert $validation.valid "Validation failed for $($migration.name): $($validation.error)"
    $run = Start-Rehearsal $migration.id
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
    Assert-Pdf $run.id
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
    $indexRun = Start-Rehearsal $indexMigration.id
    Assert ($indexRun.status -eq 'SUCCEEDED') "Versioned Flyway migration failed: $($indexRun.report.error)"
    Assert ($indexRun.report.findings.title -contains 'New foreign key lacks a covering B-tree') 'Missing index evidence'
    Write-Output 'Versioned Flyway upload and catalog-backed index evidence passed.'
} finally {
    if (Test-Path -LiteralPath $indexFile) { Remove-Item -LiteralPath $indexFile }
    Remove-Item -LiteralPath $indexFixture.FullName
}

$bundleFixture = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('rehearsal-bundles-' + [guid]::NewGuid().ToString('N')))
try {
    $first = New-SqlFile $bundleFixture.FullName 'V1__add_bundle_notes.sql' 'ALTER TABLE users ADD COLUMN bundle_notes text;'
    $second = New-SqlFile $bundleFixture.FullName 'V2__rename_bundle_notes.sql' 'ALTER TABLE users RENAME COLUMN bundle_notes TO bundle_summary;'
    $repeatable = New-SqlFile $bundleFixture.FullName 'R__create_bundle_view.sql' 'CREATE OR REPLACE VIEW bundle_view AS SELECT bundle_summary FROM users;'
    $bundle = Request POST "$project/migrations/upload" -Form @{ file=@($second,$repeatable,$first) }
    $stored = Request GET "/migrations/$($bundle.id)"
    Assert (($stored.files.name -join ',') -eq "$($second.Name),$($repeatable.Name),$($first.Name)") 'Stored bundle lost upload order'
    Assert ($stored.files[0].sql -match 'RENAME COLUMN' -and $stored.files[2].sql -match 'ADD COLUMN') 'Stored bundle lost file contents'
    $validation = Request POST "/migrations/$($bundle.id)/validate"
    Assert $validation.valid "Ordered bundle validation failed: $($validation.error)"
    Assert ($validation.statementCount -eq 3 -and $validation.findings.filename -contains $second.Name) 'Bundle validation is missing its statement count or file-level finding'
    $ordered = Start-Rehearsal $bundle.id
    Assert ($ordered.status -eq 'SUCCEEDED') "Reverse-uploaded bundle failed: $($ordered.report.error)"
    Assert-RunFiles $ordered @($first.Name,$second.Name,$repeatable.Name) @('APPLIED','APPLIED','APPLIED')
    Assert ($ordered.report.changes.object -contains 'column public.users.bundle_summary') 'Ordered bundle schema change is missing'
    Assert ($ordered.report.changes.object -notcontains 'column public.users.bundle_notes') 'Schema diff contains an intermediate column'
    Assert ($ordered.report.changes.object -contains 'table public.bundle_view') 'Repeatable migration view is missing'
    Assert-Pdf $ordered.id
    Write-Output 'Reverse-uploaded bundle passed in Flyway version order.'

    $collisionA = New-SqlFile $bundleFixture.FullName 'V1__add_collision_text.sql' 'ALTER TABLE users ADD COLUMN bundle_collision text;'
    $collisionB = New-SqlFile $bundleFixture.FullName 'V2__add_collision_integer.sql' 'ALTER TABLE users ADD COLUMN bundle_collision integer;'
    foreach ($file in @($collisionA,$collisionB)) {
        $alone = Request POST "$project/migrations/upload" -Form @{ file=$file }
        $aloneRun = Start-Rehearsal $alone.id
        Assert ($aloneRun.status -eq 'SUCCEEDED') "$($file.Name) failed independently: $($aloneRun.report.error)"
        Assert-RunFiles $aloneRun @($file.Name) @('APPLIED')
    }
    $afterFailure = New-SqlFile $bundleFixture.FullName 'V3__after_collision.sql' 'ALTER TABLE users ADD COLUMN bundle_after_collision text;'
    $combined = Request POST "$project/migrations/upload" -Form @{ file=@($collisionA,$collisionB,$afterFailure) }
    $failed = Start-Rehearsal $combined.id
    Assert ($failed.status -eq 'FAILED' -and $failed.report.error -match '42701') 'Expected combined migration to fail on duplicate column'
    Assert-RunFiles $failed @($collisionA.Name,$collisionB.Name,$afterFailure.Name) @('APPLIED','FAILED','PENDING')
    Assert ($failed.report.migrationFiles[1].error -match '42701') 'Missing file-level PostgreSQL error'
    Assert-Pdf $failed.id
    Write-Output 'Individually successful migrations failed together with applied, failed, and pending file evidence.'

    $unsupported = New-SqlFile $bundleFixture.FullName 'V2__unsupported.sql' 'SET statement_timeout = 0;'
    $invalid = Request POST "$project/migrations/upload" -Form @{ file=@($first,$unsupported) }
    $invalidValidation = Request POST "/migrations/$($invalid.id)/validate"
    Assert (-not $invalidValidation.valid -and $invalidValidation.error.StartsWith($unsupported.Name)) 'Invalid second file was not identified'
    $countBefore = (Request GET "$project/reports").Count
    try {
        Request POST "/migrations/$($invalid.id)/test" | Out-Null
        throw 'Unsafe SQL unexpectedly accepted for execution'
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 400) { throw }
    }
    Assert ((Request GET "$project/reports").Count -eq $countBefore) 'Rejected bundle left behind a queued run'
    Write-Output 'Invalid second file identified and rejected before queuing.'

    $duplicate = New-SqlFile $bundleFixture.FullName 'V1_0__equivalent_version.sql' 'ALTER TABLE users ADD COLUMN bundle_duplicate text;'
    Assert-UploadRejected $project @($first,$duplicate) 400
    Write-Output 'Duplicate Flyway versions rejected before persistence.'

    $boundary = @(for ($version = 1; $version -le 21; $version++) {
        New-SqlFile $bundleFixture.FullName "V${version}__boundary.sql" "ALTER TABLE users ADD COLUMN bundle_boundary_$version text;"
    })
    $twenty = Request POST "$project/migrations/upload" -Form @{ file=$boundary[0..19] }
    Assert ($twenty.files.Count -eq 20) '20-file upload boundary was rejected or truncated'
    Assert-UploadRejected $project $boundary 400

    $padding = 'x' * (132 * 1024)
    $largeA = New-SqlFile $bundleFixture.FullName 'V1__large_a.sql' "ALTER TABLE users ADD COLUMN large_a text; -- $padding"
    $largeB = New-SqlFile $bundleFixture.FullName 'V2__large_b.sql' "ALTER TABLE users ADD COLUMN large_b text; -- $padding"
    Assert-UploadRejected $project @($largeA,$largeB) 413
    Write-Output '20-file boundary accepted, 21 files rejected, and combined byte limit enforced.'
} finally {
    Get-ChildItem -LiteralPath $bundleFixture.FullName -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    Remove-Item -LiteralPath $bundleFixture.FullName
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
