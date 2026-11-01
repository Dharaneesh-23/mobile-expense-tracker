[CmdletBinding()]
param([int]$Port=8000)
$ErrorActionPreference='Stop'
$backendRoot=Join-Path (Split-Path $PSScriptRoot) 'backend'
Push-Location $backendRoot
try {
    if(-not (Test-Path -LiteralPath '.env')){throw 'Configure the ignored backend/.env first.'}
    & .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port $Port
} finally {Pop-Location}
