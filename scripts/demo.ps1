param(
    [Parameter(Position = 0)]
    [string]$Command = "menu"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Get-EnvValue([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

$AppPort = Get-EnvValue "APP_PORT" "8080"
$Rows = [int](Get-EnvValue "ROWS" "1200000")
$BatchSize = [int](Get-EnvValue "BATCH_SIZE" "100000")
$MaxParallel = [int](Get-EnvValue "MAX_PARALLEL" "12")
$TemporalTarget = Get-EnvValue "TEMPORAL_TARGET" "127.0.0.1:7233"
$TemporalNamespace = Get-EnvValue "TEMPORAL_NAMESPACE" "default"
$TemporalApiKey = Get-EnvValue "TEMPORAL_API_KEY" ""
$TemporalEnableHttps = Get-EnvValue "TEMPORAL_ENABLE_HTTPS" "false"
$TemporalIdentity = Get-EnvValue "TEMPORAL_IDENTITY" ""
$TemporalUi = Get-EnvValue "TEMPORAL_UI" "http://localhost:8088"
$Base = "http://localhost:$AppPort/api/reconciliation"
$Jar = Join-Path $Root "target\billing-reconciliation-1.0.0-SNAPSHOT.jar"
$StateDir = Join-Path $env:TEMP "billing-demo"
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$WfFile = Join-Path $StateDir "wf"
$PidFile = Join-Path $StateDir "app.pid"
$AppLog = Join-Path $StateDir "app.log"
$ErrLog = Join-Path $StateDir "app.err.log"
$script:Waiting = @()

function Say([string]$Message) { Write-Host "> $Message" -ForegroundColor Cyan }
function Ok([string]$Message) { Write-Host "OK $Message" -ForegroundColor Green }
function Warn([string]$Message) { Write-Host "! $Message" -ForegroundColor Yellow }
function Err([string]$Message) { Write-Host "ERROR $Message" -ForegroundColor Red }
function Hr { Write-Host ("-" * 60) }
function Num-Batches { return [int][Math]::Ceiling($Rows / $BatchSize) }
function Show-Json($Object) {
    if ($null -eq $Object) { return }
    if ($Object -is [string]) { try { $Object | ConvertFrom-Json | ConvertTo-Json -Depth 20 } catch { $Object } }
    else { $Object | ConvertTo-Json -Depth 20 }
}
function Invoke-Api([string]$Method, [string]$Path, [string]$Body) {
    $uri = "$Base$Path"
    if ([string]::IsNullOrEmpty($Body)) { $response = Invoke-RestMethod -Method $Method -Uri $uri }
    else { $response = Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body $Body }
    Show-Json $response
    return $response
}
function Test-AppUp {
    try { Invoke-WebRequest "http://localhost:$AppPort/actuator/health" -UseBasicParsing -TimeoutSec 2 | Out-Null; return $true }
    catch { return $false }
}
function Assert-AppUp {
    if (-not (Test-AppUp)) { Err "app not running on :$AppPort - run 'up' first"; return $false }
    return $true
}
function Get-Wf { if (Test-Path $WfFile) { return (Get-Content -Raw $WfFile).Trim() }; return "" }
function Test-TemporalReachable {
    $parts = $TemporalTarget.Split(":")
    $hostName = $parts[0]; $port = 7233
    if ($parts.Count -gt 1) { $port = [int]$parts[1] }
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect($hostName, $port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(2000, $false) -and $client.Connected
        $client.Close(); return $ok
    } catch { return $false }
}
function Get-ChildStep([string]$ChildId) {
    try { $r = Invoke-RestMethod "$Base/batches/$ChildId/step" -TimeoutSec 10; if ($r.currentStep) { return [string]$r.currentStep } } catch { }
    return "(pending)"
}
function Get-ChildIds([string]$ChildId) {
    try { $r = Invoke-RestMethod "$Base/batches/$ChildId/problems" -TimeoutSec 10; if ($r.txnIds) { return @($r.txnIds) } } catch { }
    return @()
}
function Scan-Children([string]$Wf) {
    $script:Waiting = @()
    for ($n = 1; $n -le (Num-Batches); $n++) {
        $id = "$Wf-batch-$n"; $step = Get-ChildStep $id
        if ($step -eq "WAITING_FOR_SIGNAL") {
            $ids = (Get-ChildIds $id) -join ","
            Write-Host ("  batch-{0,-4} WAITING  ids={1}" -f $n,$ids) -ForegroundColor Yellow
            $script:Waiting += $n
        } else { Write-Host ("  batch-{0,-4} {1}" -f $n,$step) }
    }
}
function Pick-Batch {
    if ($script:Waiting.Count -eq 1) { return $script:Waiting[0] }
    Write-Host ("  waiting batches: " + ($script:Waiting -join " "))
    $choice = Read-Host "  batch #"; $number = 0
    if ([int]::TryParse($choice, [ref]$number) -and ($script:Waiting -contains $number)) { return $number }
    Warn "not a waiting batch"; return $null
}
function Pick-Id([int]$BatchNo, [string]$Wf) {
    $items = @(Get-ChildIds "$Wf-batch-$BatchNo")
    if ($items.Count -eq 0) { Warn "no ids in batch $BatchNo"; return $null }
    for ($i=0; $i -lt $items.Count; $i++) { Write-Host ("    {0}) {1}" -f ($i+1),$items[$i]) }
    $choice = Read-Host "  id #"; $number = 0
    if ([int]::TryParse($choice,[ref]$number) -and $number -ge 1 -and $number -le $items.Count) { return $items[$number-1] }
    Warn "bad selection"; return $null
}
function Start-App {
    if (Test-AppUp) { Ok "app already running on :$AppPort"; return $true }
    if (-not (Test-Path $Jar)) {
        Say "building jar..."; mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { Err "build failed"; return $false }
    }
    Say "starting app (worker + API) on :$AppPort (log -> $AppLog)"
    $env:SERVER_PORT=$AppPort; $env:DB_HOST="localhost"; $env:DB_PORT="5432"; $env:DB_NAME="billing"; $env:DB_USER="billing"; $env:DB_PASSWORD="billing"
    $env:TEMPORAL_TARGET=$TemporalTarget; $env:TEMPORAL_NAMESPACE=$TemporalNamespace; $env:TEMPORAL_API_KEY=$TemporalApiKey; $env:TEMPORAL_ENABLE_HTTPS=$TemporalEnableHttps; $env:TEMPORAL_IDENTITY=$TemporalIdentity
    $env:VENDOR_API_URL="http://localhost:$AppPort"; $env:CUSTOMER_API_URL="http://localhost:$AppPort"; $env:BILLING_BATCH_SIZE="$BatchSize"; $env:BILLING_MAX_PARALLEL_BATCHES="$MaxParallel"
    $proc = Start-Process java -ArgumentList @("-jar",$Jar) -RedirectStandardOutput $AppLog -RedirectStandardError $ErrLog -WindowStyle Hidden -PassThru
    Set-Content $PidFile $proc.Id
    for ($i=0; $i -lt 60; $i++) { if (Test-AppUp) { Ok "app UP (pid $($proc.Id))"; return $true }; Start-Sleep 1 }
    Err "app failed to start; tail $AppLog"; return $false
}
function Stop-App {
    $stopped = $false
    if (Test-Path $PidFile) { try { Stop-Process -Id (Get-Content -Raw $PidFile).Trim() -Force; $stopped=$true; Ok "app stopped" } catch { }; Remove-Item $PidFile -Force -ErrorAction SilentlyContinue }
    if (-not $stopped) { Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like "*billing-reconciliation*" } | ForEach-Object { Stop-Process $_.ProcessId -Force -ErrorAction SilentlyContinue; $stopped=$true }; if ($stopped) { Ok "app stopped (by jar match)" } }
    if (-not $stopped) { Warn "no app process found" }
}
function Cmd-Up {
    Say "Preflight"; if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Err "docker not found"; return }
    if (-not (Test-TemporalReachable)) { Err "Temporal not reachable at $TemporalTarget - start your Temporal stack first"; return }
    Ok "Temporal reachable at $TemporalTarget"; docker compose up -d postgresql | Out-Null
    do { docker exec billing-postgres pg_isready -U postgres 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 1 } while ($true)
    Ok "billing-postgres ready"; Start-App | Out-Null
}
function Cmd-Data { Say "Loading $Rows transactions (batch-size $BatchSize -> $(Num-Batches) workflows)"; docker exec billing-postgres pg_isready -U postgres 2>$null | Out-Null; if ($LASTEXITCODE -ne 0) { Err "Postgres not up - run 'up' first"; return }; & (Join-Path $Root "scripts\insert-dummy-data.ps1") -Rows $Rows }
function Cmd-Run { if (-not (Assert-AppUp)) { return }; Say "Trigger reconciliation"; $r=Invoke-Api "POST" "/start" ""; $wf=[string]$r.workflowId; if (-not $wf) { Err "no workflowId (run 'data' first)"; return }; Set-Content $WfFile $wf; Ok "parent workflow: $wf"; Start-Sleep 10; Scan-Children $wf }
function Cmd-Status { Hr; Say "Stack"; if (Test-AppUp) { Ok "app UP on :$AppPort" } else { Warn "app DOWN" }; docker inspect -f "{{.State.Status}}" billing-postgres 2>$null | Out-Null; if ($LASTEXITCODE -eq 0) { Ok ("billing-postgres: " + (docker inspect -f "{{.State.Status}}" billing-postgres)) } else { Warn "billing-postgres: not running" }; $wf=Get-Wf; if (-not $wf) { Warn "no run started yet"; return }; Invoke-Api "GET" "/$wf/progress" "" | Out-Null; Scan-Children $wf }
function Cmd-Restart { Say "Restarting app"; Stop-App; Start-Sleep 2; Start-App | Out-Null }
function Cmd-Resolve {
    if (-not (Assert-AppUp)) { return }; $wf=Get-Wf; if (-not $wf) { Err "no run - run 'run' first"; return }
    while ($true) {
        Scan-Children $wf; Hr; Write-Host "1) COMPENSATE whole batch`n2) CONTINUE whole batch`n3) Resolve single id`n4) Correct txn + CONTINUE`n5) Parent fan-out`n6) Refresh`nb) Back"
        switch (Read-Host "  >") {
            "1" { if ($script:Waiting.Count -eq 0) { Warn "none waiting"; continue }; $b=Pick-Batch; if ($null -ne $b) { Invoke-Api "POST" "/batches/$wf-batch-$b/resolve" '{"decision":"COMPENSATE"}' | Out-Null } }
            "2" { if ($script:Waiting.Count -eq 0) { Warn "none waiting"; continue }; $b=Pick-Batch; if ($null -ne $b) { Invoke-Api "POST" "/batches/$wf-batch-$b/resolve" '{"decision":"CONTINUE"}' | Out-Null } }
            "3" { if ($script:Waiting.Count -eq 0) { Warn "none waiting"; continue }; $b=Pick-Batch; if ($null -eq $b) { continue }; $id=Pick-Id $b $wf; if ($null -eq $id) { continue }; $d=Read-Host "decision [COMPENSATE/CONTINUE]"; if ($d.ToUpper() -ne "CONTINUE") { $d="COMPENSATE" }; Invoke-Api "POST" "/batches/$wf-batch-$b/resolve" (ConvertTo-Json @{txnId=$id;decision=$d}) | Out-Null }
            "4" { if ($script:Waiting.Count -eq 0) { Warn "none waiting"; continue }; $b=Pick-Batch; if ($null -eq $b) { continue }; $id=Pick-Id $b $wf; if ($null -eq $id) { continue }; Invoke-Api "POST" "/txns/$id/correct" "{}" | Out-Null; Invoke-Api "POST" "/batches/$wf-batch-$b/resolve" (ConvertTo-Json @{txnId=$id;decision="CONTINUE"}) | Out-Null }
            "5" { $d=Read-Host "fan-out decision [COMPENSATE/CONTINUE]"; if ($d.ToUpper() -ne "CONTINUE") { $d="COMPENSATE" }; Invoke-Api "POST" "/$wf/resolve" (ConvertTo-Json @{decision=$d}) | Out-Null }
            "6" { continue }
            { $_ -in @("b","B") } { return }
            default { Warn "?" }
        }
    }
}
function Cmd-Stop { Say "Stopping app + Postgres"; Stop-App; docker compose stop postgresql 2>$null | Out-Null; Warn "schedule remains on Temporal" }
function Cmd-Down { if ((Read-Host "This wipes the billing Postgres volume. Continue? [y/N]") -ne "y") { Warn "cancelled"; return }; Cmd-Stop; docker compose down -v 2>$null | Out-Null; Remove-Item $WfFile -Force -ErrorAction SilentlyContinue }
function Cmd-All { Cmd-Up; if ($?) { Cmd-Data; Cmd-Run } }
function Menu {
    while ($true) { Hr; Write-Host "Billing Reconciliation - demo"; Write-Host "rows=$Rows batch=$BatchSize -> $(Num-Batches) workflows | app :$AppPort | temporal $TemporalTarget"; Write-Host "1) up  2) data  3) run  4) status  5) resolve  6) restart  7) stop  8) all  q) quit"; switch (Read-Host "  >") { "1" { Cmd-Up }; "2" { Cmd-Data }; "3" { Cmd-Run }; "4" { Cmd-Status }; "5" { Cmd-Resolve }; "6" { Cmd-Restart }; "7" { Cmd-Stop }; "8" { Cmd-All }; { $_ -in @("q","Q") } { return }; default { Warn "?" } } }
}
$Command = $Command.TrimStart("-")
switch ($Command) { "up" { Cmd-Up }; "data" { Cmd-Data }; "run" { Cmd-Run }; "status" { Cmd-Status }; "resolve" { Cmd-Resolve }; "restart" { Cmd-Restart }; "stop" { Cmd-Stop }; "down" { Cmd-Down }; "all" { Cmd-All }; "menu" { Menu }; "" { Menu }; "help" { Get-Content $PSCommandPath | Select-Object -First 25 }; default { Err "unknown command: $Command"; exit 1 } }
