[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateRange(1, 30)][int]$DurationSeconds = 5,
    [ValidateRange(5, 1000)][int]$IntervalMilliseconds = 20,
    [string]$OutputRoot
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

function Invoke-AdbWithInput {
    param([string[]]$Arguments, [string]$InputText)
    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = "adb"
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardInput = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.CreateNoWindow = $true
    if ($Serial) {
        [void]$processInfo.ArgumentList.Add("-s")
        [void]$processInfo.ArgumentList.Add($Serial)
    }
    foreach ($argument in $Arguments) {
        [void]$processInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($processInfo)
    $process.StandardInput.Write($InputText)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    [pscustomobject]@{
        ExitCode = $process.ExitCode
        Output = (($stdout + [Environment]::NewLine + $stderr).Trim())
    }
}

function Export-AdbBinary {
    param([string[]]$Arguments, [string]$Destination)
    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = "adb"
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.CreateNoWindow = $true
    if ($Serial) {
        [void]$processInfo.ArgumentList.Add("-s")
        [void]$processInfo.ArgumentList.Add($Serial)
    }
    foreach ($argument in $Arguments) {
        [void]$processInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($processInfo)
    $file = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create)
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Dispose()
    }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "adb binary export failed ($($process.ExitCode)): $stderr"
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

$latestCaptureLog = (Invoke-Adb @(
    "logcat", "-d", "-v", "brief", "-s", "RLT:I", "*:S"
) -AllowFailure).Output
$runMatches = [regex]::Matches(
    $latestCaptureLog,
    "CAPTURE-START runId=([0-9a-fA-F-]{36}) backend=(\S+) mode=(\S+) targetHz=(\d+)"
)
$runId = if ($runMatches.Count -gt 0) {
    $runMatches[$runMatches.Count - 1].Groups[1].Value
} else {
    [guid]::NewGuid().ToString()
}
$runIdentitySource = if ($runMatches.Count -gt 0) {
    "app_logcat"
} else {
    "host_generated_unmatched"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureDir = Join-Path $OutputRoot "$timestamp-$runId-virgl-summary"
New-Item -ItemType Directory -Path $captureDir -Force | Out-Null
$remoteRoot = "/data/data/com.termux/files/home/.rlt/captures/$runId/virgl-summary-$timestamp"
$samplerSource = Get-Content -LiteralPath (
    Join-Path $projectRoot "tools/virgl_worker_summary.py"
) -Raw
$samplerBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($samplerSource)
)

$remoteScript = @'
set -u
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PATH"
remote_root="__REMOTE_ROOT__"
mkdir -p "$remote_root"
printf '%s' '__SAMPLER_BASE64__' | base64 -d > "$remote_root/virgl_worker_summary.py"
set +e
python "$remote_root/virgl_worker_summary.py" sample \
    --duration-seconds "__DURATION_SECONDS__" \
    --interval-ms "__INTERVAL_MILLISECONDS__" \
    --run-id "__RUN_ID__" \
    --producer-log "$HOME/runelite-native.log" \
    --output "$remote_root/virgl-worker-samples.json" \
    > "$remote_root/sampler.stdout.txt" \
    2> "$remote_root/sampler.stderr.txt"
sampler_status=$?
set -e
printf 'sampler_exit_status=%s\n' "$sampler_status" > "$remote_root/status.txt"
rm -f "$remote_root/virgl_worker_summary.py"
exit "$sampler_status"
'@
$remoteScript = $remoteScript.Replace("__REMOTE_ROOT__", $remoteRoot)
$remoteScript = $remoteScript.Replace("__SAMPLER_BASE64__", $samplerBase64)
$remoteScript = $remoteScript.Replace("__DURATION_SECONDS__", "$DurationSeconds")
$remoteScript = $remoteScript.Replace("__INTERVAL_MILLISECONDS__", "$IntervalMilliseconds")
$remoteScript = $remoteScript.Replace("__RUN_ID__", $runId)

Write-Host (
    "Capturing non-invasive VirGL SUMMARY/BURST observations for " +
    "$DurationSeconds seconds on $Serial..."
)
$captureResult = Invoke-AdbWithInput @(
    "shell", "run-as", "com.termux",
    "/data/data/com.termux/files/usr/bin/bash", "-s"
) $remoteScript
if ($captureResult.ExitCode -ne 0) {
    throw "VirGL summary capture failed ($($captureResult.ExitCode)): $($captureResult.Output)"
}

$remoteFilesResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "find", $remoteRoot, "-type", "f"
)
foreach ($remoteFile in @($remoteFilesResult.Output -split "`r?`n" | Where-Object { $_ })) {
    $relative = $remoteFile.Substring($remoteRoot.Length).TrimStart("/")
    $destination = Join-Path $captureDir (
        $relative -replace "/", [IO.Path]::DirectorySeparatorChar
    )
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force |
        Out-Null
    Export-AdbBinary @(
        "exec-out", "run-as", "com.termux", "cat", $remoteFile
    ) $destination
}

$agentDir = Join-Path $captureDir "agent-diagnostics"
& python (Join-Path $projectRoot "tools/agent_diagnostics.py") capture `
    --serial $Serial `
    --project-root $projectRoot `
    --output $agentDir
if ($LASTEXITCODE -ne 0) {
    throw "Canonical agent diagnostics capture failed ($LASTEXITCODE)"
}

$agentLogsPath = Join-Path $agentDir "logs.json"
$agentLogs = Get-Content -LiteralPath $agentLogsPath -Raw
$escapedRunId = [regex]::Escape($runId)
if ($agentLogs -notmatch "RLT_VIRGL_WORKER_V1 run_id=$escapedRunId") {
    throw "VirGL worker summary did not arrive through the canonical retained endpoint"
}

$summaryPath = Join-Path $captureDir "virgl-worker-summary.json"
$reportPath = Join-Path $captureDir "report.md"
& python (Join-Path $projectRoot "tools/virgl_worker_summary.py") analyze `
    --sample-json (Join-Path $captureDir "virgl-worker-samples.json") `
    --agent-logs $agentLogsPath `
    --output $summaryPath `
    --report $reportPath
if ($LASTEXITCODE -ne 0) {
    throw "VirGL summary analyzer failed ($LASTEXITCODE)"
}

$sampleCapture = Get-Content -LiteralPath (
    Join-Path $captureDir "virgl-worker-samples.json"
) -Raw | ConvertFrom-Json
$gitCommit = (& git -C $projectRoot rev-parse HEAD 2>$null | Out-String).Trim()
$gitDirty = -not [string]::IsNullOrWhiteSpace(
    (& git -C $projectRoot status --porcelain 2>$null | Out-String).Trim()
)
$manifest = [ordered]@{
    schema_version = 1
    run_id = $runId
    run_identity_source = $runIdentitySource
    capture_mode = "noninvasive_procfs_burst"
    duration_seconds = $DurationSeconds
    interval_milliseconds = $IntervalMilliseconds
    target_pid = [int]$sampleCapture.target.pid
    git_commit = $gitCommit
    git_dirty = $gitDirty
    device = [ordered]@{
        serial = $Serial
        model = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Output
        fingerprint = (Invoke-Adb @(
            "shell", "getprop", "ro.build.fingerprint"
        )).Output
        boot_id = (Invoke-Adb @(
            "shell", "cat", "/proc/sys/kernel/random/boot_id"
        ) -AllowFailure).Output
    }
    virgl = [ordered]@{
        source_vendored = $false
        binary_modified = $false
    }
    observability = [ordered]@{
        canonical_ingress_record = "RLT_VIRGL_WORKER_V1"
        retained_endpoint_verified = $true
        agent_bundle = "agent-diagnostics"
    }
    privacy = [ordered]@{
        local_only = $true
        screenshots_collected = $false
        input_text_collected = $false
        gameplay_packets_collected = $false
    }
    interpretation_limits = @(
        "procfs sampling has measured but non-zero observer cost",
        "schedstat runqueue wait is not a GPU or fence wait",
        "blocked or idle time remains internally unresolved",
        "producer identity is summary-window correlated, not per-frame"
    )
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

Write-Host "VirGL summary capture bundle: $captureDir"
