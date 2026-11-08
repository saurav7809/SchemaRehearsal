# Dot-source from the repository root. Copy to .local before editing.
$env:SR_PG_PORT = '55432'
$env:SR_METADATA_URL = "jdbc:postgresql://127.0.0.1:$env:SR_PG_PORT/sr_metadata"
$env:SR_PYTHON = (Resolve-Path .\.venv\Scripts\python.exe).Path
$env:SR_ANALYZER = (Resolve-Path .\analysis\analyze.py).Path
$env:SR_APP_PASSWORD = Read-Host 'sr_app password' -MaskInput
$env:SR_BROKER_PASSWORD = Read-Host 'sr_broker password' -MaskInput
$env:SR_WORKER_PASSWORD = Read-Host 'sr_worker password' -MaskInput
$env:SR_READER_PASSWORD = Read-Host 'sr_reader password' -MaskInput
$env:SR_ENCRYPTION_KEY = Read-Host 'Saved Base64 encryption key' -MaskInput
