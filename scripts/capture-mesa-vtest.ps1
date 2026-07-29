[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateRange(1, 60)][int]$DurationSeconds = 12,
    [string]$OutputRoot,
    [switch]$RequireVirglHost
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $projectRoot "perf-captures"
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $all = @()
    if ($Serial) { $all += @("-s", $Serial) }
    $all += $Arguments
    $output = & adb @all 2>&1
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb $($all -join ' ') failed ($exitCode):`n$($output | Out-String)"
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output | Out-String).TrimEnd()
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not on PATH"
}
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "python is not on PATH"
}
if (-not $Serial) {
    $devices = @(
        (& adb devices) |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "^\S+\s+device$" }
    )
    if ($devices.Count -ne 1) {
        throw "Specify -Serial; expected exactly one connected device, found $($devices.Count)"
    }
    $Serial = ($devices[0] -split "\s+")[0]
}
if ((Invoke-Adb @("get-state")).Output -ne "device") {
    throw "Device $Serial is not ready"
}

$stateOutput = & python (Join-Path $projectRoot "tools/agent_diagnostics.py") state `
    --serial $Serial `
    --project-root $projectRoot
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read canonical agent state"
}
$state = $stateOutput | ConvertFrom-Json
if ($state.stateMachine.posture -ne "running_x11") {
    throw "Mesa vtest capture requires running_x11; current posture=$($state.stateMachine.posture)"
}

$logcat = (Invoke-Adb @(
    "logcat", "-d", "-v", "brief", "-s", "RLT:I", "*:S"
) -AllowFailure).Output
$matches = [regex]::Matches(
    $logcat,
    "CAPTURE-START runId=([0-9a-fA-F-]{36}) backend=(\S+) mode=(\S+) targetHz=(\d+)"
)
if ($matches.Count -eq 0) {
    throw "No app-owned capture identity was found in logcat"
}
$runId = $matches[$matches.Count - 1].Groups[1].Value

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureDir = Join-Path $OutputRoot "$timestamp-$runId-mesa-vtest"
New-Item -ItemType Directory -Path $captureDir -Force | Out-Null

Write-Host "Observing canonical Mesa vtest summaries for $DurationSeconds seconds..."
Start-Sleep -Seconds $DurationSeconds

$agentDir = Join-Path $captureDir "agent-diagnostics"
& python (Join-Path $projectRoot "tools/agent_diagnostics.py") capture `
    --serial $Serial `
    --project-root $projectRoot `
    --output $agentDir
if ($LASTEXITCODE -ne 0) {
    throw "Canonical agent diagnostics capture failed ($LASTEXITCODE)"
}

$agentLogsPath = Join-Path $agentDir "logs.json"
$summaryPath = Join-Path $captureDir "mesa-vtest-summary.json"
$reportPath = Join-Path $captureDir "report.md"
& python (Join-Path $projectRoot "tools/mesa_vtest_summary.py") `
    --agent-logs $agentLogsPath `
    --run-id $runId `
    --output $summaryPath `
    --report $reportPath
if ($LASTEXITCODE -ne 0) {
    throw "Mesa vtest summary analyzer failed ($LASTEXITCODE)"
}

$summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
if ($summary.window_count -lt 1) {
    throw "No Mesa vtest summary windows reached the canonical endpoint"
}

$virglHostSummaryPath = Join-Path $captureDir "virgl-host-summary.json"
$virglHostReportPath = Join-Path $captureDir "virgl-host-report.md"
if ($RequireVirglHost) {
    & python (Join-Path $projectRoot "tools/virgl_host_summary.py") `
        --agent-logs $agentLogsPath `
        --run-id $runId `
        --output $virglHostSummaryPath `
        --report $virglHostReportPath
    if ($LASTEXITCODE -ne 0) {
        throw "VirGL host summary analyzer failed ($LASTEXITCODE)"
    }
    $virglHostSummary = Get-Content -LiteralPath $virglHostSummaryPath -Raw |
        ConvertFrom-Json
    if (-not $virglHostSummary.assignment_and_join_gate_met) {
        throw "VirGL host assignment/window join gate failed"
    }
}

$manifest = [ordered]@{
    schema_version = 1
    run_id = $runId
    capture_mode = "mesa_vtest_summary"
    duration_seconds = $DurationSeconds
    git_commit = (& git -C $projectRoot rev-parse HEAD 2>$null | Out-String).Trim()
    git_dirty = -not [string]::IsNullOrWhiteSpace(
        (& git -C $projectRoot status --porcelain 2>$null | Out-String).Trim()
    )
    device = [ordered]@{
        serial = $Serial
        model = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Output
        fingerprint = (Invoke-Adb @(
            "shell", "getprop", "ro.build.fingerprint"
        )).Output
    }
    instrumentation = [ordered]@{
        rlawt_abi = "RLT_FRAME_TELEMETRY_ABI_VERSION=1"
        mesa_record = "RLT_MESA_VTEST_V1"
        frame_window = 30
        canonical_endpoint_verified = $true
        virgl_source_modified = [bool]$RequireVirglHost
        virgl_binary_modified = [bool]$RequireVirglHost
    }
    privacy = [ordered]@{
        local_only = $true
        screenshots_collected = $false
        input_synthesized = $false
        gameplay_packets_collected = $false
    }
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $captureDir "manifest.json") -Encoding utf8

$sumLines = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $captureDir -Recurse -File |
    Where-Object { $_.Name -ne "sha256sums.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (
            Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        ).Hash.ToLowerInvariant()
        $relative = [IO.Path]::GetRelativePath(
            $captureDir, $_.FullName
        ).Replace("\", "/")
        $sumLines.Add("$hash  $relative")
    }
$sumLines | Set-Content -LiteralPath (
    Join-Path $captureDir "sha256sums.txt"
) -Encoding ascii

Write-Host "Mesa vtest evidence bundle: $captureDir"
