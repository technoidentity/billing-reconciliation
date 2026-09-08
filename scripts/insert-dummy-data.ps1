# Seeds billing source tables with "bad" rows the pipeline is meant to catch:
#   - amount = 0 on every 5,000th transaction  (schema INVALID)
#   - GL amount + 417 on id % 10000 = 1         (discrepancy after match)
#
# Usage (from the repo root, PowerShell 5.1+):
#   .\scripts\insert-dummy-data.ps1
#   .\scripts\insert-dummy-data.ps1 24000
#
# If the shell blocks scripts:
#   powershell -ExecutionPolicy Bypass -File .\scripts\insert-dummy-data.ps1

param(
    [int]$Rows = 1200000
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Test-PostgresReady {
    docker compose exec -T postgresql pg_isready -U postgres 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

if (-not (Test-PostgresReady)) {
    Write-Host "Postgres is not running. Start the stack first:"
    Write-Host "  docker compose up -d postgresql"
    exit 1
}

Write-Host "Applying schema updates..."
docker compose cp scripts/migrate-schema.sql postgresql:/tmp/migrate-schema.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose exec -T postgresql psql -U postgres -d billing -f /tmp/migrate-schema.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Inserting $Rows billing transactions (plus vendors, customers, and GL rows)..."
docker compose cp scripts/insert-dummy-data.sql postgresql:/tmp/insert-dummy-data.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose exec -T postgresql psql -U postgres -d billing -v "rows=$Rows" -f /tmp/insert-dummy-data.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Done."
