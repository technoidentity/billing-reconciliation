# Demo controller for the Billing Reconciliation Temporal app (Windows PowerShell).
#
#   .\scripts\demo.ps1              # interactive menu
#   .\scripts\demo.ps1 up           # start Postgres + app (Temporal must already be reachable)
#   .\scripts\demo.ps1 data         # load ROWS dummy transactions (includes invalid + GL mismatches)
#   .\scripts\demo.ps1 run          # trigger a reconciliation run
#   .\scripts\demo.ps1 status       # stack + current-run status
#   .\scripts\demo.ps1 resolve      # interactive discrepancy resolution
#   .\scripts\demo.ps1 restart      # restart the app (worker + API); workflows resume durably
#   .\scripts\demo.ps1 stop         # stop app + Postgres
#   .\scripts\demo.ps1 down         # stop + wipe Postgres volume
#   .\scripts\demo.ps1 all          # up + data + run
#
# If the shell blocks scripts:
#   powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1 all
#
# Config via env (defaults are production-like 1.2M / 100K / 12 workflows):
#   $env:ROWS=1200000; $env:BATCH_SIZE=100000; $env:MAX_PARALLEL=12; $env:APP_PORT=8081
#   Quick demo:  $env:ROWS=24000; $env:BATCH_SIZE=2000; .\scripts\demo.ps1 all

param(
    [Parameter(Position = 0)]
    [string]$Command = "menu"
)

$ErrorActionPreference = "Stop"

$AppPort = if ($env:APP_PORT) { $env:APP_PORT } else { "8081" }
$Rows = if ($env:ROWS) { [int]$env:ROWS } else { 1200000 }
$BatchSize = if ($env:BATCH_SIZE) { [int]$env:BATCH_SIZE } else { 100000 }
$MaxParallel = if ($env:MAX_PARALLEL) { [int]$env:MAX_PARALLEL } else { 12 }
$TemporalTarget = if ($env:TEMPORAL_TARGET) { $env:TEMPORAL_TARGET } else { "127.0.0.1:7233" }
$TemporalNamespace = if ($env:TEMPORAL_NAMESPACE) { $env:TEMPORAL_NAMESPACE } else { "default" }
$TemporalApiKey = if ($env:TEMPORAL_API_KEY) { $env:TEMPORAL_API_KEY } else { "" }
$TemporalEnableHttps = if ($env:TEMPORAL_ENABLE_HTTPS) { $env:TEMPORAL_ENABLE_HTTPS } else { "false" }
$TemporalIdentity = if ($env:TEMPORAL_IDENTITY) { $env:TEMPORAL_IDENTITY } else { "" }
$TemporalUi = if ($env:TEMPORAL_UI) { $env:TEMPORAL_UI } else { "http://localhost:8080" }

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Base = "http://localhost:${AppPort}/api/reconciliation"
$Jar = Join-Path $Root "target\billing-reconciliation-1.0.0-SNAPSHOT.jar"
$StateDir = Join-Path $env:TEMP "billing-demo"
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$WfFile = Join-Path $StateDir "wf"
$PidFile = Join-Path $StateDir "app.pid"
$AppLog = Join-Path $StateDir "app.log"

function Write-Say  { param([string]$Message) Write-Host "▶ $Message" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Message) Write-Host "✓ $Message" -ForegroundColor Green }
function Write-Warn { param([string]$Message) Write-Host "! $Message" -ForegroundColor Yellow }
function Write-Err  { param([string]$Message) Write-Host "✗ $Message" -ForegroundColor Red }
function Write-Hr   { Write-Host "────────────────────────────────────────────────────────────" -ForegroundColor White }

function Show-Json($Object) {
    if ($null -eq $Object) { return }
    if ($Object -is [string]) {
        try { ($Object | ConvertFrom-Json) | ConvertTo-Json -Depth 20 } catch { $Object }
    } else {
        $Object | ConvertTo-Json -Depth 20
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Body = ""
    )
    $uri = "$Base$Path"
    if ($Body) {
        Write-Host "`$ Invoke-RestMethod -Method $Method $uri -Body '$Body'" -ForegroundColor White
        $resp = Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body $Body
    } else {
        Write-Host "`$ Invoke-RestMethod -Method $Method $uri" -ForegroundColor White
        $resp = Invoke-RestMethod -Method $Method -Uri $uri
    }
    Show-Json $resp
    return $resp
}

function Get-NumBatches {
    return [int][Math]::Ceiling($Rows / $BatchSize)
}

function Test-AppUp {
    try {
        Invoke-WebRequest -Uri "http://localhost:${AppPort}/actuator/health" -UseBasicParsing -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Assert-AppUp {
    if (-not (Test-AppUp)) {
        Write-Err "app not running on :${AppPort} — run 'up' first"
        return $false
    }
    return $true
}

function Get-WorkflowId {
    if (Test-Path $WfFile) { return (Get-Content -Raw $WfFile).Trim() }
    return ""
}

function Get-ChildStep([string]$ChildId) {
    try {
        $resp = Invoke-RestMethod -Uri "$Base/batches/$ChildId/step" -TimeoutSec 10
        if ($resp.currentStep) { return [string]$resp.currentStep }
    } catch { }
    return "(pending)"
}

function Get-ChildProblemIds([string]$ChildId) {
    try {
        $resp = Invoke-RestMethod -Uri "$Base/batches/$ChildId/problems" -TimeoutSec 10
        if ($resp.txnIds) { return @($resp.txnIds) }
    } catch { }
    return @()
}

function Scan-Children([string]$Wf) {
    $script:Waiting = @()
    $total = Get-NumBatches
    for ($n = 1; $n -le $total; $n++) {
        $childId = "${Wf}-batch-${n}"
        $step = Get-ChildStep $childId
        if ($step -eq "WAITING_FOR_SIGNAL") {
            $ids = (Get-ChildProblemIds $childId) -join ","
            Write-Host ("  batch-{0,-4} WAITING  ids={1}" -f $n, $ids) -ForegroundColor Yellow
            $script:Waiting += $n
        } else {
            Write-Host ("  batch-{0,-4} {1}" -f $n, $step)
        }
    }
}

function Select-WaitingBatch {
    if ($script:Waiting.Count -eq 1) { return $script:Waiting[0] }
    Write-Host "  waiting batches: $($script:Waiting -join ' ')"
    $picked = Read-Host "  batch #"
    $batchNo = 0
    if ([int]::TryParse($picked, [ref]$batchNo) -and ($script:Waiting -contains $batchNo)) {
        return $batchNo
    }
    Write-Warn "not a waiting batch"
    return $null
}

function Select-TxnId([int]$BatchNo, [string]$Wf) {
    $arr = @(Get-ChildProblemIds "${Wf}-batch-${BatchNo}")
    if ($arr.Count -eq 0) {
        Write-Warn "no ids in batch $BatchNo"
        return $null
    }
    for ($i = 0; $i -lt $arr.Count; $i++) {
        Write-Host ("    {0}) {1}" -f ($i + 1), $arr[$i])
    }
    $sel = Read-Host "  id #"
    $idx = 0
    if (-not [int]::TryParse($sel, [ref]$idx)) {
        Write-Warn "bad selection"
        return $null
    }
    if ($idx -ge 1 -and $idx -le $arr.Count) { return $arr[$idx - 1] }
    Write-Warn "bad selection"
    return $null
}

function Start-App {
    if (Test-AppUp) {
        Write-Ok "app already running on :${AppPort}"
        return $true
    }
    if (-not (Test-Path $Jar)) {
        Write-Say "building jar…"
        mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Write-Err "build failed"
            return $false
        }
    }
    Write-Say "starting app (worker + API) on :${AppPort} (log → ${AppLog})"
    $env:SERVER_PORT = $AppPort
    $env:DB_HOST = "localhost"
    $env:DB_PORT = "5432"
    $env:DB_NAME = "billing"
    $env:DB_USER = "billing"
    $env:DB_PASSWORD = "billing"
    $env:TEMPORAL_TARGET = $TemporalTarget
    $env:TEMPORAL_NAMESPACE = $TemporalNamespace
    $env:TEMPORAL_API_KEY = $TemporalApiKey
    $env:TEMPORAL_ENABLE_HTTPS = $TemporalEnableHttps
    $env:TEMPORAL_IDENTITY = $TemporalIdentity
    $env:VENDOR_API_URL = "http://localhost:${AppPort}"
    $env:CUSTOMER_API_URL = "http://localhost:${AppPort}"
    $env:BILLING_BATCH_SIZE = "$BatchSize"
    $env:BILLING_MAX_PARALLEL_BATCHES = "$MaxParallel"

    $errLog = Join-Path $StateDir "app.err.log"
    $proc = Start-Process -FilePath "java" -ArgumentList @("-jar", $Jar) `
        -RedirectStandardOutput $AppLog -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
    Set-Content -Path $PidFile -Value $proc.Id
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-AppUp) { break }
        Start-Sleep -Seconds 1
    }
    if (Test-AppUp) {
        Write-Ok "app UP (pid $($proc.Id))"
        return $true
    }
    Write-Err "app failed to start; tail ${AppLog}"
    return $false
}

function Stop-App {
    $stopped = $false
    if (Test-Path $PidFile) {
        $procId = (Get-Content -Raw $PidFile).Trim()
        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
            Write-Ok "app stopped (pid $procId)"
            $stopped = $true
        } catch { }
        Remove-Item $PidFile -ErrorAction SilentlyContinue
    }
    if (-not $stopped) {
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like "*billing-reconciliation*" } |
            ForEach-Object {
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
                $stopped = $true
            }
        if ($stopped) { Write-Ok "app stopped (by jar match)" }
    }
    if (-not $stopped) { Write-Warn "no app process found" }
}

function Test-TemporalReachable {
    $parts = $TemporalTarget.Split(":")
    $hostName = $parts[0]
    $port = if ($parts.Count -gt 1) { [int]$parts[1] } else { 7233 }
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect($hostName, $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(2000, $false)
        if ($ok -and $client.Connected) {
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Invoke-CmdUp {
    Write-Say "Preflight"
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Err "docker not found"
        return $false
    }
    if (Test-TemporalReachable) {
        Write-Ok "Temporal reachable at ${TemporalTarget}"
    } else {
        Write-Err "Temporal not reachable at ${TemporalTarget} — start your Temporal stack first"
        return $false
    }
    if ($TemporalApiKey) { Write-Ok "Temporal Cloud mode (API key set, TLS auto-on)" }

    Write-Say "Billing Postgres"
    docker compose up -d postgresql | Out-Null
    do {
        docker exec billing-postgres pg_isready -U postgres 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    } while ($true)
    Write-Ok "billing-postgres ready"
    return (Start-App)
}

function Invoke-CmdRestart {
    Write-Say "Restarting app (worker + API)"
    Stop-App
    Start-Sleep -Seconds 2
    Start-App | Out-Null
}

function Invoke-CmdData {
    Write-Say "Loading $Rows transactions (batch-size $BatchSize → $(Get-NumBatches) workflows)"
    docker exec billing-postgres pg_isready -U postgres 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Postgres not up — run 'up' first"
        return
    }
    & (Join-Path $Root "scripts\insert-dummy-data.ps1") -Rows $Rows
}

function Invoke-CmdRun {
    if (-not (Assert-AppUp)) { return }
    Write-Say "Trigger reconciliation (manual equivalent of the 8:00 AM schedule)"
    $resp = Invoke-RestMethod -Method Post -Uri "$Base/start"
    Show-Json $resp
    $wf = [string]$resp.workflowId
    if (-not $wf) {
        Write-Err "no workflowId (empty table? run 'data')"
        return
    }
    Set-Content -Path $WfFile -Value $wf
    Write-Ok "parent workflow: $wf"
    Write-Host "  UI: ${TemporalUi}/namespaces/${TemporalNamespace}/workflows?query=WorkflowId%20STARTS_WITH%20%22${wf}%22" -ForegroundColor Cyan
    Write-Say "waiting for children to reach validate→match…"
    Start-Sleep -Seconds 10
    Scan-Children $wf
    Write-Host ""
    $waitingLabel = if ($script:Waiting.Count) { $script:Waiting -join " " } else { "none" }
    Write-Ok "waiting children: $waitingLabel"
}

function Invoke-CmdStatus {
    Write-Hr
    Write-Say "Stack"
    if (Test-AppUp) {
        $pidLabel = if (Test-Path $PidFile) { (Get-Content -Raw $PidFile).Trim() } else { "?" }
        Write-Ok "app UP on :${AppPort} (pid $pidLabel)"
    } else {
        Write-Warn "app DOWN"
    }
    docker inspect -f "{{.State.Status}}" billing-postgres 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $st = docker inspect -f "{{.State.Status}}" billing-postgres
        Write-Ok "billing-postgres: $st"
    } else {
        Write-Warn "billing-postgres: not running"
    }
    $cloud = if ($TemporalApiKey) { "  [cloud/api-key]" } else { "" }
    Write-Host "  temporal: ${TemporalTarget} (ns ${TemporalNamespace})$cloud"
    $wf = Get-WorkflowId
    if (-not $wf) {
        Write-Warn "no run started yet"
        Write-Hr
        return
    }
    Write-Hr
    Write-Say "Run $wf"
    if (-not (Test-AppUp)) { return }
    Invoke-Api GET "/$wf/progress" | Out-Null
    Write-Host ""
    Write-Say "Children"
    Scan-Children $wf
}

function Invoke-CmdResolve {
    if (-not (Assert-AppUp)) { return }
    $wf = Get-WorkflowId
    if (-not $wf) {
        Write-Err "no run — run 'run' first"
        return
    }
    while ($true) {
        Write-Hr
        Scan-Children $wf | Out-Null
        $waitingLabel = if ($script:Waiting.Count) { $script:Waiting -join " " } else { "none" }
        Write-Say "Resolve  (workflow $wf; waiting: $waitingLabel)"
        Write-Host @"
  1) COMPENSATE a whole batch        (workflow auto-aligns all ids to GL → completes)
  2) CONTINUE a whole batch          (re-check DB after you fixed it; unfixed ids stay waiting)
  3) Resolve a SINGLE id             (choose COMPENSATE or CONTINUE)
  4) Correct a txn in DB + CONTINUE  (app fixes one id via /correct, then re-check)
  5) Parent fan-out to all waiting   (choose COMPENSATE or CONTINUE)
  6) Show children + progress
  b) Back
"@
        $c = Read-Host "  >"
        Write-Host ""
        switch ($c) {
            "1" {
                if ($script:Waiting.Count -eq 0) { Write-Warn "none waiting"; continue }
                $b1 = Select-WaitingBatch
                if ($null -eq $b1) { continue }
                Invoke-Api POST "/batches/${wf}-batch-${b1}/resolve" '{"decision":"COMPENSATE"}' | Out-Null
                Start-Sleep -Seconds 5
            }
            "2" {
                if ($script:Waiting.Count -eq 0) { Write-Warn "none waiting"; continue }
                $b2 = Select-WaitingBatch
                if ($null -eq $b2) { continue }
                Write-Warn "fix the data in the DB first, then this re-checks the whole batch"
                Invoke-Api POST "/batches/${wf}-batch-${b2}/resolve" '{"decision":"CONTINUE"}' | Out-Null
                Start-Sleep -Seconds 5
            }
            "3" {
                if ($script:Waiting.Count -eq 0) { Write-Warn "none waiting"; continue }
                $b3 = Select-WaitingBatch
                if ($null -eq $b3) { continue }
                $id3 = Select-TxnId $b3 $wf
                if (-not $id3) { continue }
                $dec3 = Read-Host "  decision [COMPENSATE/continue]"
                if ($dec3.ToUpper() -eq "CONTINUE") { $dec3 = "CONTINUE" } else { $dec3 = "COMPENSATE" }
                Invoke-Api POST "/batches/${wf}-batch-${b3}/resolve" "{`"txnId`":`"${id3}`",`"decision`":`"${dec3}`"}" | Out-Null
                Start-Sleep -Seconds 5
            }
            "4" {
                if ($script:Waiting.Count -eq 0) { Write-Warn "none waiting"; continue }
                $b4 = Select-WaitingBatch
                if ($null -eq $b4) { continue }
                $id4 = Select-TxnId $b4 $wf
                if (-not $id4) { continue }
                Invoke-Api GET "/txns/${id4}" | Out-Null
                Invoke-Api POST "/txns/${id4}/correct" "{}" | Out-Null
                Invoke-Api POST "/batches/${wf}-batch-${b4}/resolve" "{`"txnId`":`"${id4}`",`"decision`":`"CONTINUE`"}" | Out-Null
                Start-Sleep -Seconds 5
            }
            "5" {
                $dec5 = Read-Host "  fan-out decision [COMPENSATE/continue]"
                if ($dec5.ToUpper() -eq "CONTINUE") { $dec5 = "CONTINUE" } else { $dec5 = "COMPENSATE" }
                Invoke-Api POST "/${wf}/resolve" "{`"decision`":`"${dec5}`"}" | Out-Null
                Start-Sleep -Seconds 6
            }
            "6" {
                Scan-Children $wf
                Write-Host ""
                Invoke-Api GET "/$wf/progress" | Out-Null
            }
            { $_ -in @("b", "B") } { return }
            default { Write-Warn "?" }
        }
    }
}

function Invoke-CmdStop {
    Write-Say "Stopping app + Postgres"
    Stop-App
    docker compose stop postgresql 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Ok "billing-postgres stopped" } else { Write-Warn "postgres not running" }
    Write-Warn "schedule 'daily-billing-reconciliation' stays on Temporal — remove from UI if unwanted"
}

function Invoke-CmdDown {
    $a = Read-Host "This wipes the billing Postgres volume. Continue? [y/N]"
    if ($a -ne "y") {
        Write-Warn "cancelled"
        return
    }
    Invoke-CmdStop
    docker compose down -v 2>$null | Out-Null
    Write-Ok "stack down, volume removed"
    Remove-Item $WfFile -ErrorAction SilentlyContinue
}

function Invoke-CmdAll {
    if (Invoke-CmdUp) {
        Invoke-CmdData
        Invoke-CmdRun
    }
}

function Show-Menu {
    while ($true) {
        Write-Hr
        Write-Host "  Billing Reconciliation — demo" -ForegroundColor White
        Write-Host "  rows=$Rows batch=$BatchSize → $(Get-NumBatches) workflows | app :${AppPort} | temporal ${TemporalTarget}"
        Write-Hr
        Write-Host @"
  1) up       — start Postgres + app
  2) data     — load $Rows rows (invalid amounts + GL mismatches)
  3) run      — trigger reconciliation
  4) status   — stack + run status
  5) resolve  — resolve discrepancies
  6) restart  — restart app (worker + API)
  7) stop     — stop app + Postgres
  8) all      — up + data + run
  q) quit
"@
        $c = Read-Host "  >"
        Write-Host ""
        switch ($c) {
            "1" { Invoke-CmdUp | Out-Null }
            "2" { Invoke-CmdData }
            "3" { Invoke-CmdRun }
            "4" { Invoke-CmdStatus }
            "5" { Invoke-CmdResolve }
            "6" { Invoke-CmdRestart }
            "7" { Invoke-CmdStop }
            "8" { Invoke-CmdAll }
            { $_ -in @("q", "Q") } { return }
            default { Write-Warn "?" }
        }
    }
}

$Command = $Command.TrimStart("-")
switch ($Command) {
    "up"      { Invoke-CmdUp | Out-Null }
    "data"    { Invoke-CmdData }
    "run"     { Invoke-CmdRun }
    "status"  { Invoke-CmdStatus }
    "resolve" { Invoke-CmdResolve }
    "restart" { Invoke-CmdRestart }
    "stop"    { Invoke-CmdStop }
    "down"    { Invoke-CmdDown }
    "all"     { Invoke-CmdAll }
    { $_ -in @("menu", "") } { Show-Menu }
    { $_ -in @("h", "help") } {
        Get-Content $PSCommandPath | Select-Object -Skip 1 -First 28 | ForEach-Object { $_ -replace '^# ?', '' }
    }
    default {
        Write-Err "unknown command: $Command"
        exit 1
    }
}
