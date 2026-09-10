#requires -Version 5.1
<#
.SYNOPSIS
  Demo controller for the file-based Billing Reconciliation Temporal app.
  Windows PowerShell 5.1 compatible.

.EXAMPLE
  .\scripts\demo.ps1
  .\scripts\demo.ps1 start
  .\scripts\demo.ps1 files
  .\scripts\demo.ps1 process
  .\scripts\demo.ps1 process billing-demo.csv
  .\scripts\demo.ps1 retry
  .\scripts\demo.ps1 data
  .\scripts\demo.ps1 data 24000
  .\scripts\demo.ps1 status
  .\scripts\demo.ps1 resolve
  .\scripts\demo.ps1 resolve all
  .\scripts\demo.ps1 resolve batch
  .\scripts\demo.ps1 resolve txn
  .\scripts\demo.ps1 quickstart
  .\scripts\demo.ps1 quickstart 5000
#>
param(
    [Parameter(Position = 0)]
    [string]$Command = "menu",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

if (-not $PSScriptRoot) {
    $PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
}

function EnvOr([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

function Join-All {
    param([Parameter(Mandatory = $true)][string[]]$Parts)
    $path = $Parts[0]
    for ($i = 1; $i -lt $Parts.Count; $i++) {
        $path = Join-Path -Path $path -ChildPath $Parts[$i]
    }
    return $path
}

function ArgAt([int]$Index) {
    if ($null -eq $script:Rest -or $Index -ge @($script:Rest).Count) { return "" }
    return [string](@($script:Rest)[$Index])
}

function Test-Interactive {
    try {
        return -not [Console]::IsInputRedirected
    } catch {
        return $true
    }
}

function Say([string]$Message)  { Write-Host ("> {0}" -f $Message) -ForegroundColor Cyan }
function Ok([string]$Message)   { Write-Host ("OK {0}" -f $Message) -ForegroundColor Green }
function Warn([string]$Message) { Write-Host ("! {0}" -f $Message) -ForegroundColor Yellow }
function Err([string]$Message)  { Write-Host ("ERR {0}" -f $Message) -ForegroundColor Red }
function Hr {
    Write-Host "------------------------------------------------------------" -ForegroundColor White
}

if ($null -eq $Rest) { $script:Rest = @() } else { $script:Rest = @($Rest) }
$script:AppPort = EnvOr "APP_PORT" "8080"
$script:Rows = 1200000
if (-not [string]::IsNullOrWhiteSpace($env:ROWS)) { $script:Rows = [int]$env:ROWS }
$script:BatchSize = 100000
if (-not [string]::IsNullOrWhiteSpace($env:BATCH_SIZE)) { $script:BatchSize = [int]$env:BATCH_SIZE }
$script:MaxParallel = 12
if (-not [string]::IsNullOrWhiteSpace($env:MAX_PARALLEL)) { $script:MaxParallel = [int]$env:MAX_PARALLEL }
$script:Stem = EnvOr "STEM" "billing-demo"
$script:TemporalTarget = EnvOr "TEMPORAL_TARGET" "127.0.0.1:7233"
$script:TemporalNamespace = EnvOr "TEMPORAL_NAMESPACE" "default"
$script:TemporalApiKey = EnvOr "TEMPORAL_API_KEY" ""
$script:TemporalEnableHttps = EnvOr "TEMPORAL_ENABLE_HTTPS" "false"
$script:TemporalIdentity = EnvOr "TEMPORAL_IDENTITY" ""
$script:TemporalUi = EnvOr "TEMPORAL_UI" "http://localhost:8088"

$script:Root = (Resolve-Path (Join-Path -Path $PSScriptRoot -ChildPath "..")).Path
Set-Location -LiteralPath $script:Root

$script:SftpRoot = EnvOr "BILLING_SFTP_ROOT" (Join-Path -Path $script:Root -ChildPath "sftp")
$script:HomeDir = EnvOr "BILLING_HOME_DIR" (Join-Path -Path $script:SftpRoot -ChildPath "home")
$script:DestDir = EnvOr "BILLING_DESTINATION_DIR" (Join-Path -Path $script:SftpRoot -ChildPath "destination")
$script:Base = "http://localhost:{0}/api/reconciliation" -f $script:AppPort
$script:Jar = Join-All @($script:Root, "target", "billing-reconciliation-1.0.0-SNAPSHOT.jar")
$script:StateDir = Join-Path -Path $env:TEMP -ChildPath "billing-demo"
$script:WfFile = Join-Path -Path $script:StateDir -ChildPath "wf"
$script:PidFile = Join-Path -Path $script:StateDir -ChildPath "app.pid"
$script:AppLog = Join-Path -Path $script:StateDir -ChildPath "app.log"
$script:AppErr = Join-Path -Path $script:StateDir -ChildPath "app.err"
$script:Coordinator = EnvOr "BILLING_COORDINATOR_ID" "billing-reconciliation-coordinator"
$script:SelectedFile = ""

New-Item -ItemType Directory -Force -Path $script:StateDir | Out-Null

function Format-JsonText([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Text }
    try {
        $obj = $Text | ConvertFrom-Json
        return ($obj | ConvertTo-Json -Depth 8)
    } catch {
        return $Text
    }
}

function Read-HttpErrorBody($Exception) {
    try {
        $response = $Exception.Exception.Response
        if ($null -eq $response) { return $Exception.Exception.Message }
        $stream = $response.GetResponseStream()
        if ($null -eq $stream) { return $Exception.Exception.Message }
        $reader = New-Object System.IO.StreamReader($stream)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Close()
        }
    } catch {
        return $Exception.Exception.Message
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Body = ""
    )
    $uri = "{0}{1}" -f $script:Base, $Path
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        Write-Host ("curl -X {0} {1} -d '{2}'" -f $Method, $uri, $Body) -ForegroundColor White
    } else {
        Write-Host ("curl -X {0} {1}" -f $Method, $uri) -ForegroundColor White
    }
    try {
        if ([string]::IsNullOrWhiteSpace($Body)) {
            $resp = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -TimeoutSec 60
        } else {
            $resp = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -TimeoutSec 60 `
                -ContentType "application/json; charset=utf-8" -Body $Body
        }
        $text = [string]$resp.Content
        Write-Host (Format-JsonText $text)
        return $text
    } catch {
        $text = Read-HttpErrorBody $_
        Write-Host (Format-JsonText $text)
        return $text
    }
}

function Test-AppUp {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 `
            -Uri ("http://localhost:{0}/actuator/health" -f $script:AppPort)
        return ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300)
    } catch {
        return $false
    }
}

function Need-App {
    if (Test-AppUp) { return $true }
    Err ("app not running on :{0} - run 'up' or docker compose up -d" -f $script:AppPort)
    return $false
}

function Get-Wf {
    if (Test-Path -LiteralPath $script:WfFile) {
        $id = (Get-Content -LiteralPath $script:WfFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($id)) { return $id.Trim() }
    }
    return $script:Coordinator
}

function Get-GlCompanion([string]$Name) {
    if ($Name.StartsWith("billing-")) {
        return ("gl-{0}" -f $Name.Substring("billing-".Length))
    }
    return ("gl-{0}" -f $Name)
}

function Get-HomeCsvNames {
    New-Item -ItemType Directory -Force -Path $script:HomeDir | Out-Null
    $files = @(Get-ChildItem -LiteralPath $script:HomeDir -File -Filter "*.csv" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "gl-*.csv" } |
        Sort-Object Name |
        ForEach-Object { $_.Name })
    return @($files)
}

function Format-Size([long]$Bytes) {
    if ($Bytes -ge 1MB) { return ("{0:N1} MB" -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ("{0:N1} KB" -f ($Bytes / 1KB)) }
    return ("{0} B" -f $Bytes)
}

function Show-HomeFiles {
    $files = @(Get-HomeCsvNames)
    if ($files.Count -eq 0) {
        Warn ("no billing CSV files in {0}" -f $script:HomeDir)
        Write-Host "  generate one with:  .\scripts\demo.ps1 data"
        return $false
    }
    Write-Host ("  {0,-4} {1,-32} {2,10}  {3}" -f "#", "FILE", "SIZE", "READY")
    $i = 1
    foreach ($f in $files) {
        $full = Join-Path -Path $script:HomeDir -ChildPath $f
        $item = Get-Item -LiteralPath $full
        $checksum = "checksum=no"
        if (Test-Path -LiteralPath ($full + ".sha256")) { $checksum = "checksum=yes" }
        $gl = Get-GlCompanion $f
        if (Test-Path -LiteralPath (Join-Path -Path $script:HomeDir -ChildPath $gl)) {
            $gl = "gl=$gl"
        } else {
            $gl = "gl=MISSING"
        }
        Write-Host ("  {0,-4} {1,-32} {2,10}  {3}  {4}" -f ("{0})" -f $i), $f, (Format-Size $item.Length), $checksum, $gl)
        $i++
    }
    return $true
}

function Select-HomeFile([string]$Requested) {
    $files = @(Get-HomeCsvNames)
    if ($files.Count -eq 0) {
        Err ("SFTP home is empty ({0}). Run: .\scripts\demo.ps1 data" -f $script:HomeDir)
        return $false
    }
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $Requested = [IO.Path]::GetFileName($Requested)
        $full = Join-Path -Path $script:HomeDir -ChildPath $Requested
        if (-not (Test-Path -LiteralPath $full)) {
            Err ("not in SFTP home: {0}" -f $Requested)
            [void](Show-HomeFiles)
            return $false
        }
        $script:SelectedFile = $Requested
        return $true
    }
    if ($files.Count -eq 1 -and -not (Test-Interactive)) {
        $script:SelectedFile = $files[0]
        return $true
    }
    Say ("Files in SFTP home ({0})" -f $script:HomeDir)
    if (-not (Show-HomeFiles)) { return $false }
    if ($files.Count -eq 1) {
        $script:SelectedFile = $files[0]
        Ok ("selected {0}" -f $script:SelectedFile)
        return $true
    }
    $sel = Read-Host "  file # (or name)"
    if ($sel -match '^[0-9]+$') {
        $idx = [int]$sel
        if ($idx -ge 1 -and $idx -le $files.Count) {
            $script:SelectedFile = $files[$idx - 1]
            Ok ("selected {0}" -f $script:SelectedFile)
            return $true
        }
    }
    $named = Join-Path -Path $script:HomeDir -ChildPath $sel
    if (-not [string]::IsNullOrWhiteSpace($sel) -and (Test-Path -LiteralPath $named)) {
        $script:SelectedFile = $sel
        Ok ("selected {0}" -f $script:SelectedFile)
        return $true
    }
    Err "invalid selection"
    return $false
}

function Signal-File([string]$Signal, [string]$File, [string]$Async) {
    [void](Invoke-Api -Method POST -Path "/start")
    $script:Coordinator | Set-Content -LiteralPath $script:WfFile -Encoding ASCII
    if ($Async -eq "true") {
        $payload = @{ fileName = $File; async = $true } | ConvertTo-Json -Compress
    } else {
        $payload = @{ fileName = $File } | ConvertTo-Json -Compress
    }
    [void](Invoke-Api -Method POST -Path ("/files/{0}" -f $Signal) -Body $payload)
    Write-Host ("  UI: {0}/namespaces/{1}/workflows/{2}" -f $script:TemporalUi, $script:TemporalNamespace, $script:Coordinator) -ForegroundColor Cyan
}

function Test-Temporal {
    $parts = $script:TemporalTarget.Split(":")
    if ($parts.Count -lt 2) { return $false }
    $hostName = $parts[0]
    $port = 0
    if (-not [int]::TryParse($parts[1], [ref]$port)) { return $false }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($hostName, $port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(2000, $false)
        if (-not $ok) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-CommandExists([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Invoke-Python {
    param([Parameter(Mandatory = $true)][string[]]$PythonArgs)
    if (Test-CommandExists "python") {
        & python @PythonArgs
        return $LASTEXITCODE
    }
    if (Test-CommandExists "py") {
        $all = @("-3") + @($PythonArgs)
        & py @all
        return $LASTEXITCODE
    }
    Err "python not found (install Python 3 and ensure python or py is on PATH)"
    return 1
}

function Start-DemoApp {
    if (Test-AppUp) {
        Ok ("app already running on :{0}" -f $script:AppPort)
        return $true
    }
    if (-not (Test-Path -LiteralPath $script:Jar)) {
        Say "building jar..."
        if (-not (Test-CommandExists "mvn")) {
            Err "mvn not found"
            return $false
        }
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $script:Jar)) {
            Err "build failed"
            return $false
        }
    }
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $javaCmd) {
        Err "java not found"
        return $false
    }
    $java = $javaCmd.Source
    if ([string]::IsNullOrWhiteSpace($java)) { $java = "java" }
    New-Item -ItemType Directory -Force -Path $script:HomeDir, $script:DestDir, (Join-Path -Path $script:DestDir -ChildPath "reference") | Out-Null
    Say ("starting app (worker + API) on :{0} (log -> {1})" -f $script:AppPort, $script:AppLog)

    $env:SERVER_PORT = [string]$script:AppPort
    $env:TEMPORAL_TARGET = $script:TemporalTarget
    $env:TEMPORAL_NAMESPACE = $script:TemporalNamespace
    $env:TEMPORAL_API_KEY = $script:TemporalApiKey
    $env:TEMPORAL_ENABLE_HTTPS = $script:TemporalEnableHttps
    $env:TEMPORAL_IDENTITY = $script:TemporalIdentity
    $env:VENDOR_API_URL = "http://localhost:{0}" -f $script:AppPort
    $env:CUSTOMER_API_URL = "http://localhost:{0}" -f $script:AppPort
    $env:BILLING_BATCH_SIZE = [string]$script:BatchSize
    $env:BILLING_MAX_PARALLEL_BATCHES = [string]$script:MaxParallel
    $env:BILLING_FILES_MODE = "sftp"
    $env:BILLING_SFTP_ROOT = $script:SftpRoot
    $env:SFTP_HOST = "localhost"
    $env:SFTP_PORT = "2222"
    $env:SFTP_USER = "billing"
    $env:SFTP_PASSWORD = "billing"
    $env:SFTP_HOME_DIR = "home"
    $env:SFTP_DESTINATION_DIR = "destination"

    if (Test-Path -LiteralPath $script:AppLog) { Remove-Item -LiteralPath $script:AppLog -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $script:AppErr) { Remove-Item -LiteralPath $script:AppErr -Force -ErrorAction SilentlyContinue }

    $proc = Start-Process -FilePath $java -ArgumentList @("-jar", $script:Jar) `
        -WorkingDirectory $script:Root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $script:AppLog -RedirectStandardError $script:AppErr
    [string]$proc.Id | Set-Content -LiteralPath $script:PidFile -Encoding ASCII

    for ($i = 0; $i -lt 60; $i++) {
        if (Test-AppUp) { break }
        Start-Sleep -Seconds 1
    }
    if (Test-AppUp) {
        Ok ("app UP (pid {0})" -f $proc.Id)
        return $true
    }
    Err ("app failed to start; see {0} and {1}" -f $script:AppLog, $script:AppErr)
    return $false
}

function Stop-DemoApp {
    $stopped = $false
    if (Test-Path -LiteralPath $script:PidFile) {
        $pidText = (Get-Content -LiteralPath $script:PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        $pidNum = 0
        if ($null -ne $pidText -and [int]::TryParse(([string]$pidText).Trim(), [ref]$pidNum)) {
            $proc = Get-Process -Id $pidNum -ErrorAction SilentlyContinue
            if ($null -ne $proc) {
                Stop-Process -Id $pidNum -Force -ErrorAction SilentlyContinue
                $stopped = $true
                Ok ("app stopped (pid {0})" -f $pidNum)
            }
        }
        Remove-Item -LiteralPath $script:PidFile -Force -ErrorAction SilentlyContinue
    }
    if (-not $stopped) {
        $matches = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like "*billing-reconciliation-1.0.0-SNAPSHOT.jar*" })
        foreach ($m in $matches) {
            Stop-Process -Id $m.ProcessId -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
        if ($stopped) { Ok "app stopped (by jar match)" }
    }
    if (-not $stopped) {
        Warn "no local app process found (Docker app may still be running)"
    }
}

function Wait-Sftp {
    for ($i = 0; $i -lt 30; $i++) {
        & docker compose exec -T sftp pgrep sshd 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Command-Up {
    Say "Preflight"
    if (-not (Test-CommandExists "docker")) {
        Err "docker not found"
        return $false
    }
    if (Test-Temporal) {
        Ok ("Temporal reachable at {0}" -f $script:TemporalTarget)
    } else {
        Err ("Temporal not reachable at {0} - start your Temporal stack first" -f $script:TemporalTarget)
        return $false
    }
    New-Item -ItemType Directory -Force -Path $script:HomeDir, $script:DestDir | Out-Null
    Say "Docker SFTP (atmoz/sftp on :2222, user billing / billing)"
    & docker compose up -d sftp
    [void](Wait-Sftp)
    Ok "billing-sftp ready (sftp://billing@localhost:2222/home and /destination)"
    if (-not (Start-DemoApp)) { return $false }
    return (Command-Start)
}

function Command-Start {
    if (-not (Need-App)) { return $false }
    Say ("Start long-running coordinator ({0})" -f $script:Coordinator)
    $resp = Invoke-Api -Method POST -Path "/start"
    $wf = $script:Coordinator
    try {
        $obj = $resp | ConvertFrom-Json
        if ($obj.workflowId) { $wf = [string]$obj.workflowId }
    } catch { }
    if ([string]::IsNullOrWhiteSpace($wf)) { $wf = $script:Coordinator }
    $wf | Set-Content -LiteralPath $script:WfFile -Encoding ASCII
    Ok ("coordinator {0} (idempotent if already running)" -f $wf)
    Write-Host ("  UI: {0}/namespaces/{1}/workflows/{2}" -f $script:TemporalUi, $script:TemporalNamespace, $wf) -ForegroundColor Cyan
    return $true
}

function Command-Restart {
    Say "Restarting app (worker + API)"
    Stop-DemoApp
    Start-Sleep -Seconds 2
    if (-not (Start-DemoApp)) { return $false }
    return (Command-Start)
}

function Set-RowCount([string]$Given) {
    if (-not [string]::IsNullOrWhiteSpace($Given)) {
        if ($Given -match '^[1-9][0-9]*$') {
            $script:Rows = [int]$Given
            return $true
        }
        Err ("rows must be a positive integer, got: {0}" -f $Given)
        return $false
    }
    if (Test-Interactive) {
        $inputRows = Read-Host ("  How many rows to generate [{0}]" -f $script:Rows)
        if (-not [string]::IsNullOrWhiteSpace($inputRows)) {
            if ($inputRows -match '^[1-9][0-9]*$') {
                $script:Rows = [int]$inputRows
            } else {
                Err ("rows must be a positive integer, got: {0}" -f $inputRows)
                return $false
            }
        }
    }
    return $true
}

function Command-Data([string]$RowsArg) {
    if (-not (Set-RowCount $RowsArg)) { return $false }
    Say ("Generating {0} CSV transactions (new file in home; existing files are kept)" -f $script:Rows)
    $py = Join-Path -Path $PSScriptRoot -ChildPath "generate-sample-files.py"
    $code = Invoke-Python @(
        $py,
        "--rows", [string]$script:Rows,
        "--home", $script:HomeDir,
        "--reference", (Join-Path -Path $script:DestDir -ChildPath "reference"),
        "--stem", $script:Stem
    )
    if ($code -ne 0) { return $false }
    Ok ("files ready in Docker SFTP home ({0}) - {1} rows" -f $script:HomeDir, $script:Rows)
    [void](Show-HomeFiles)
    return $true
}

function Command-Files {
    Say "Available billing files in SFTP home"
    return (Show-HomeFiles)
}

function Command-Run([string]$FileArg) {
    if (-not (Need-App)) { return $false }
    if (-not (Select-HomeFile $FileArg)) { return $false }
    Say ("Process {0} (Signal 1 - copy home -> destination, then validate)" -f $script:SelectedFile)
    Signal-File "available" $script:SelectedFile
    return $true
}

function Command-Retry([string]$FileArg) {
    if (-not (Need-App)) { return $false }
    if (-not (Select-HomeFile $FileArg)) { return $false }
    Say ("Retry corrected file {0} (Signal 2)" -f $script:SelectedFile)
    Signal-File "retry" $script:SelectedFile
    return $true
}

function Command-Status {
    Hr
    Say "Containers"
    if (Test-CommandExists "docker") {
        & docker compose ps --format "table {{.Name}}`t{{.Status}}`t{{.Ports}}" 2>$null
        if ($LASTEXITCODE -ne 0) { & docker compose ps }
    } else {
        Warn "docker not found"
    }
    Hr
    Say "Stack"
    if (Test-AppUp) {
        Ok ("app UP on :{0}" -f $script:AppPort)
    } else {
        Warn "app DOWN"
    }
    Write-Host ("  temporal: {0} (ns {1})" -f $script:TemporalTarget, $script:TemporalNamespace)
    Write-Host ("  sftp home: {0}" -f $script:HomeDir)
    Write-Host ("  sftp destination: {0}" -f $script:DestDir)
    [void](Show-HomeFiles)
    $wf = Get-Wf
    Hr
    Say ("Coordinator {0}" -f $wf)
    if (-not (Test-AppUp)) { return $true }
    [void](Invoke-Api -Method GET -Path ("/{0}/progress" -f $wf))
    return $true
}

function Get-JsonProperty([string]$Text, [string]$Name) {
    try {
        $obj = $Text | ConvertFrom-Json
        $value = $obj.$Name
        if ($null -eq $value) { return "" }
        return [string]$value
    } catch {
        return ""
    }
}

function Select-ChildBatch([string]$Requested) {
    $wf = Get-Wf
    $json = ""
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 -Uri ("{0}/{1}/progress" -f $script:Base, $wf)
        $json = [string]$resp.Content
    } catch {
        $json = Read-HttpErrorBody $_
    }
    $children = @()
    try {
        $data = $json | ConvertFrom-Json
        $raw = $data.childWorkflowIds
        if ($null -ne $raw) { $children = @($raw) }
    } catch { }
    $clean = @()
    foreach ($item in $children) {
        if (-not [string]::IsNullOrWhiteSpace([string]$item)) { $clean += [string]$item }
    }
    $children = $clean

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        if ($Requested -match '^[0-9]+$') {
            $idx = [int]$Requested
            if ($idx -ge 1 -and $idx -le $children.Count) { return $children[$idx - 1] }
        }
        return $Requested
    }
    if ($children.Count -eq 0) {
        Warn "no child workflow ids on coordinator progress yet"
        $Requested = Read-Host "  child workflow id"
        if ([string]::IsNullOrWhiteSpace($Requested)) {
            Err "child workflow id is required"
            return $null
        }
        return $Requested
    }
    Say ("Child batches for {0}" -f $wf)
    $i = 1
    foreach ($c in $children) {
        $step = ""
        try {
            $stepResp = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 `
                -Uri ("{0}/batches/{1}/step" -f $script:Base, $c)
            $step = Get-JsonProperty ([string]$stepResp.Content) "currentStep"
        } catch { }
        if ([string]::IsNullOrWhiteSpace($step)) {
            Write-Host ("  {0}) {1}" -f $i, $c)
        } else {
            Write-Host ("  {0}) {1}  [{2}]" -f $i, $c, $step)
        }
        $i++
    }
    if ($children.Count -eq 1) {
        Ok ("selected {0}" -f $children[0])
        return $children[0]
    }
    $sel = Read-Host "  batch # (or child workflow id)"
    if ($sel -match '^[0-9]+$') {
        $idx = [int]$sel
        if ($idx -ge 1 -and $idx -le $children.Count) { return $children[$idx - 1] }
    }
    if (-not [string]::IsNullOrWhiteSpace($sel)) { return $sel }
    Err "invalid batch selection"
    return $null
}

function Command-Resolve([string]$Mode, [string]$ChildHint, [string]$TxnHint) {
    if (-not (Need-App)) { return $false }
    $wf = Get-Wf
    if ([string]::IsNullOrWhiteSpace($Mode) -and (Test-Interactive)) {
        Say "COMPENSATE - choose a scope"
        Write-Host "  1) all waiting children"
        Write-Host "  2) one batch"
        Write-Host "  3) one txn id (inside a batch)"
        $choice = Read-Host "  >"
        switch -Regex ($choice) {
            '^(1|all|a|A)$' { $Mode = "all" }
            '^(2|batch|b|B)$' { $Mode = "batch" }
            '^(3|txn|id|t|T)$' { $Mode = "txn" }
            default {
                Err "invalid choice"
                return $false
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($Mode)) { $Mode = "all" }
    switch ($Mode.ToLowerInvariant()) {
        "all" {
            Say "COMPENSATE all waiting children"
            [void](Invoke-Api -Method POST -Path ("/{0}/resolve" -f $wf) -Body '{"decision":"COMPENSATE"}')
            return $true
        }
        "batch" {
            $child = Select-ChildBatch $ChildHint
            if ([string]::IsNullOrWhiteSpace($child)) { return $false }
            Say ("COMPENSATE batch {0}" -f $child)
            [void](Invoke-Api -Method POST -Path ("/batches/{0}/resolve" -f $child) -Body '{"decision":"COMPENSATE"}')
            return $true
        }
        "txn" {
            $child = Select-ChildBatch $ChildHint
            if ([string]::IsNullOrWhiteSpace($child)) { return $false }
            Say ("Problems in {0}" -f $child)
            [void](Invoke-Api -Method GET -Path ("/batches/{0}/problems" -f $child))
            $txn = $TxnHint
            if ([string]::IsNullOrWhiteSpace($txn)) {
                $txn = Read-Host "  txn id to COMPENSATE"
            }
            if ([string]::IsNullOrWhiteSpace($txn)) {
                Err "txn id is required"
                return $false
            }
            $payload = @{ decision = "COMPENSATE"; txnId = $txn } | ConvertTo-Json -Compress
            Say ("COMPENSATE txn {0} in {1}" -f $txn, $child)
            [void](Invoke-Api -Method POST -Path ("/batches/{0}/resolve" -f $child) -Body $payload)
            return $true
        }
        default {
            Err "usage: resolve [all|batch|txn]"
            return $false
        }
    }
}

function Command-Stop {
    Say "Stopping local app + SFTP"
    Stop-DemoApp
    if (Test-CommandExists "docker") {
        & docker compose stop sftp 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) { Ok "sftp stopped" } else { Warn "sftp not running" }
    }
    return $true
}

function Command-Down {
    [void](Command-Stop)
    if (Test-CommandExists "docker") {
        & docker compose down 1>$null 2>$null
        if ($LASTEXITCODE -eq 0) { Ok "stack down" }
    }
    Remove-Item -LiteralPath $script:WfFile -Force -ErrorAction SilentlyContinue
    return $true
}

function Command-Quickstart([string]$RowsArg) {
    if (-not (Command-Up)) { return $false }
    if (-not (Command-Data $RowsArg)) { return $false }
    return (Command-Run ("{0}.csv" -f $script:Stem))
}

function Command-Clear {
    Say "Clearing SFTP home + destination"
    if ($script:HomeDir) {
        Get-ChildItem -LiteralPath $script:HomeDir -Force -ErrorAction SilentlyContinue |
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($script:DestDir) {
        Get-ChildItem -LiteralPath $script:DestDir -Force -ErrorAction SilentlyContinue |
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Force -Path $script:HomeDir, $script:DestDir | Out-Null
    Ok ("cleared {0} and {1}" -f $script:HomeDir, $script:DestDir)
    [void](Show-HomeFiles)
    return $true
}

# Failure demo: signal a missing/typed filename (async) so validate fails red and the coordinator continues.
function Command-Fail([string]$NameArg) {
    if (-not (Need-App)) { return $false }
    $name = if ([string]::IsNullOrWhiteSpace($NameArg)) { "does-not-exist.csv" } else { $NameArg }
    Say ("Failure demo - signal '{0}' (async); validate goes red and retries; coordinator continues" -f $name)
    Signal-File "available" $name "true"
    Write-Host "  Open the coordinator in the UI: the validate activity shows red attempts, then the file"
    Write-Host "  is parked in WAITING_FOR_CORRECTION while other files keep processing."
    return $true
}

# Durability demo: generate a large file, process async so locate/validate retries until it lands.
function Command-Large([string]$RowsArg) {
    if (-not (Need-App)) { return $false }
    $rows = if ([string]::IsNullOrWhiteSpace($RowsArg)) { "800000" } else { $RowsArg }
    Say ("Large-file durability demo - generate {0} rows, then process async (locate retries while copying)" -f $rows)
    $py = Join-Path -Path $PSScriptRoot -ChildPath "generate-sample-files.py"
    $code = Invoke-Python @(
        $py,
        "--rows", [string]$rows,
        "--home", $script:HomeDir,
        "--reference", (Join-Path -Path $script:DestDir -ChildPath "reference"),
        "--stem", "bigfile"
    )
    if ($code -ne 0) { return $false }
    Signal-File "available" "bigfile.csv" "true"
    Write-Host "  Watch locate/validate retry in the UI until the large file finishes copying, then it processes."
    Write-Host "  (Tip: set BILLING_FILES_COPY_DELAY on the app to force retries even for small files.)"
    return $true
}

function Show-Usage {
    Write-Host @"
Demo controller for the file-based Billing Reconciliation Temporal app.
Windows PowerShell 5.1.

  .\scripts\demo.ps1                 # interactive menu
  .\scripts\demo.ps1 start           # start the long-running coordinator (idempotent)
  .\scripts\demo.ps1 files           # list billing CSVs in sftp\home
  .\scripts\demo.ps1 process         # pick a file from home and process it
  .\scripts\demo.ps1 process billing-demo.csv
  .\scripts\demo.ps1 retry           # pick a file and send Signal 2
  .\scripts\demo.ps1 clear           # delete all files in sftp\home and sftp\destination
  .\scripts\demo.ps1 fail [name]     # signal a missing/typed file -> validate fails red, coordinator continues
  .\scripts\demo.ps1 large [rows]    # generate a large file, process async -> locate retries (durability)
  .\scripts\demo.ps1 data            # prompt for row count, generate into sftp\home
  .\scripts\demo.ps1 data 24000      # generate that many rows into sftp\home
  .\scripts\demo.ps1 status          # containers + coordinator progress
  .\scripts\demo.ps1 resolve         # COMPENSATE: all / one batch / one txn id
  .\scripts\demo.ps1 resolve all
  .\scripts\demo.ps1 resolve batch
  .\scripts\demo.ps1 resolve txn
  .\scripts\demo.ps1 quickstart      # up + generate sample + process billing-demo.csv
  .\scripts\demo.ps1 quickstart 5000
"@
}

function Show-Menu {
    while ($true) {
        Hr
        Write-Host "  Billing Reconciliation - file demo" -ForegroundColor White
        Write-Host ("  sftp home={0} | dest={1} | app :{2} | temporal {3}" -f $script:HomeDir, $script:DestDir, $script:AppPort, $script:TemporalTarget)
        Hr
        Write-Host ("  1) up       - start SFTP + app + long-running coordinator")
        Write-Host ("  s) start    - start long-running coordinator")
        Write-Host ("  2) data     - generate CSV (asks how many rows) into home")
        Write-Host ("  3) files    - list files available in SFTP home")
        Write-Host ("  4) process file - select a file from home and process it")
        Write-Host ("  5) status   - containers + coordinator progress")
        Write-Host ("  6) resolve  - COMPENSATE all waiting, one batch, or one txn id")
        Write-Host ("  7) retry    - select a corrected file (Signal 2)")
        Write-Host ("  8) restart  - restart app")
        Write-Host ("  9) stop     - stop app + SFTP")
        Write-Host ("  c) clear    - delete all files in home + destination")
        Write-Host ("  f) fail     - signal a missing/typed filename (validate fails red, coordinator continues)")
        Write-Host ("  L) large    - generate a large file and process async (locate retries -> durability)")
        Write-Host ("  b) quickstart - start stack, generate sample, process {0}.csv" -f $script:Stem)
        Write-Host ("  q) quit")
        $c = Read-Host "  >"
        Write-Host ""
        switch -Regex ($c) {
            '^1$'     { [void](Command-Up) }
            '^[sS]$'  { [void](Command-Start) }
            '^2$'     { [void](Command-Data "") }
            '^3$'     { [void](Command-Files) }
            '^4$'     { [void](Command-Run "") }
            '^5$'     { [void](Command-Status) }
            '^6$'     { [void](Command-Resolve "" "" "") }
            '^7$'     { [void](Command-Retry "") }
            '^8$'     { [void](Command-Restart) }
            '^9$'     { [void](Command-Stop) }
            '^[cC]$'  { [void](Command-Clear) }
            '^[fF]$'  { [void](Command-Fail "") }
            '^L$'     { [void](Command-Large "") }
            '^[bB]$'  { [void](Command-Quickstart "") }
            '^[qQ]$'  { return }
            default   { Warn "?" }
        }
    }
}

# PowerShell 5.1 parses "-jar" style tokens; strip a leading -- from the command name.
if ($Command.StartsWith("--")) { $Command = $Command.Substring(2) }
$Command = $Command.Trim()
if ([string]::IsNullOrWhiteSpace($Command)) { $Command = "menu" }

$ok = $true
switch ($Command.ToLowerInvariant()) {
    "menu"       { Show-Menu }
    "process"    { $ok = Command-Run (ArgAt 0) }
    "run"        { $ok = Command-Run (ArgAt 0) }
    "all"        { $ok = Command-Quickstart (ArgAt 0) }
    "quickstart" { $ok = Command-Quickstart (ArgAt 0) }
    "up"         { $ok = Command-Up }
    "start"      { $ok = Command-Start }
    "data"       { $ok = Command-Data (ArgAt 0) }
    "files"      { $ok = Command-Files }
    "status"     { $ok = Command-Status }
    "resolve"    { $ok = Command-Resolve (ArgAt 0) (ArgAt 1) (ArgAt 2) }
    "retry"      { $ok = Command-Retry (ArgAt 0) }
    "restart"    { $ok = Command-Restart }
    "stop"       { $ok = Command-Stop }
    "down"       { $ok = Command-Down }
    "clear"      { $ok = Command-Clear }
    "fail"       { $ok = Command-Fail (ArgAt 0) }
    "large"      { $ok = Command-Large (ArgAt 0) }
    "help"       { Show-Usage }
    "h"          { Show-Usage }
    default {
        Err ("unknown command: {0}" -f $Command)
        Show-Usage
        $ok = $false
    }
}

if (-not $ok) { exit 1 }
exit 0
