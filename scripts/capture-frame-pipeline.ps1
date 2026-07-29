param(
    [string]$Serial,
    [ValidateRange(5, 3600)]
    [int]$DurationSeconds = 120,
    [ValidateRange(30, 240)]
    [int]$TargetHz = 120,
    [string]$OutputRoot,
    [string]$TraceProcessor,
    [switch]$SkipFrameTimelineAnalysis,
    [switch]$SkipJfr
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
    $code = $LASTEXITCODE
    if (-not $AllowFailure -and $code -ne 0) {
        throw "adb $($all -join ' ') failed ($code):`n$($output | Out-String)"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = ($output | Out-String).TrimEnd() }
}

function Export-AdbBinary {
    param([string[]]$Arguments, [string]$Destination, [switch]$AllowFailure)
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
    foreach ($argument in $Arguments) { [void]$processInfo.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($processInfo)
    $file = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create)
    try {
        $process.StandardOutput.BaseStream.CopyTo($file)
    } finally {
        $file.Dispose()
    }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if (-not $AllowFailure -and $process.ExitCode -ne 0) {
        throw "adb binary export failed ($($process.ExitCode)): $errorText"
    }
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        return $false
    }
    return $true
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
    foreach ($argument in $Arguments) { [void]$processInfo.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($processInfo)
    $process.StandardInput.Write($InputText)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Output = (($stdout + [Environment]::NewLine + $stderr).Trim())
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not on PATH"
}
if (-not $Serial) {
    $devices = @((& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+device$" })
    if ($devices.Count -ne 1) {
        throw "Specify -Serial; expected exactly one connected device, found $($devices.Count)"
    }
    $Serial = ($devices[0] -split "\s+")[0]
}

$deviceState = (Invoke-Adb @("get-state")).Output
if ($deviceState -ne "device") { throw "Device $Serial is not ready: $deviceState" }
$captureStartedUtc = (Get-Date).ToUniversalTime().ToString("o")

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
$runIdentitySource = if ($runMatches.Count -gt 0) { "app_logcat" } else { "host_generated_unmatched" }
$rendererBackend = if ($runMatches.Count -gt 0) {
    $runMatches[$runMatches.Count - 1].Groups[2].Value
} else {
    "unavailable"
}
$telemetryMode = if ($runMatches.Count -gt 0) {
    $runMatches[$runMatches.Count - 1].Groups[3].Value
} else {
    "unavailable"
}
$appTargetHz = if ($runMatches.Count -gt 0) {
    [int]$runMatches[$runMatches.Count - 1].Groups[4].Value
} else {
    $null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureDir = Join-Path $OutputRoot "$timestamp-$runId"
New-Item -ItemType Directory -Path $captureDir -Force | Out-Null

$nativeLogRemotePath = "/data/data/com.termux/files/home/runelite-native.log"
$virglLogRemotePath = "/data/data/com.termux/files/home/virgl-server.log"
$appVirglLogRemotePath = "files/app-virgl-$runId.log"
$nativeLogSizeResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "stat", "-c", "%s", $nativeLogRemotePath
) -AllowFailure
$nativeLogStartBytes = if (
    $nativeLogSizeResult.ExitCode -eq 0 -and
    $nativeLogSizeResult.Output.Trim() -match "^[0-9]+$"
) {
    [long]$nativeLogSizeResult.Output.Trim()
} else {
    $null
}
$virglLogSizeResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "stat", "-c", "%s", $virglLogRemotePath
) -AllowFailure
$virglLogStartBytes = if (
    $virglLogSizeResult.ExitCode -eq 0 -and
    $virglLogSizeResult.Output.Trim() -match "^[0-9]+$"
) {
    [long]$virglLogSizeResult.Output.Trim()
} else {
    $null
}

$deviceTrace = "/data/misc/perfetto-traces/rlt-$runId.perfetto-trace"
$sourceConfig = Join-Path $PSScriptRoot "perfetto/frame-pipeline.pbtxt"
$resolvedConfig = Join-Path $captureDir "perfetto-config.pbtxt"
$perfettoConfigText = (Get-Content -LiteralPath $sourceConfig -Raw).Replace(
    "%DURATION_MS%",
    ($DurationSeconds * 1000).ToString([System.Globalization.CultureInfo]::InvariantCulture)
)
$perfettoConfigText | Set-Content -LiteralPath $resolvedConfig -Encoding utf8

$logcatOut = Join-Path $captureDir "device.logcat.txt"
$logcatErr = Join-Path $captureDir "device.logcat.stderr.txt"
$logcatArgs = @()
if ($Serial) { $logcatArgs += @("-s", $Serial) }
$logcatArgs += @(
    "logcat", "-v", "threadtime", "-T", "1",
    "RLT:V", "LorieNative:V", "gles-renderer:V", "RlawtSurface:V",
    "DirectSurface:V", "RLClient:V", "*:S"
)
$logcatProcess = Start-Process -FilePath "adb" -ArgumentList $logcatArgs `
    -RedirectStandardOutput $logcatOut -RedirectStandardError $logcatErr `
    -WindowStyle Hidden -PassThru

$jfrStatus = "skipped"
if (-not $SkipJfr) {
    $jfrCommand = "session=`$(cat `$PREFIX/tmp/rlt-session/current 2>/dev/null); " +
        "pid=`$(cat `"`$session/java.pid`" 2>/dev/null); " +
        "if [ -n `"`$pid`" ]; then `$PREFIX/bin/jcmd `$pid JFR.start " +
        "name=rlt settings=profile filename=`"`$HOME/.rlt/captures/$runId/runelite.jfr`" " +
        "duration=${DurationSeconds}s; fi"
    $jfrResult = Invoke-Adb @("shell", "run-as", "com.termux", "sh", "-c", $jfrCommand) -AllowFailure
    $jfrStatus = if ($jfrResult.ExitCode -eq 0 -and $jfrResult.Output -match "Started recording") {
        "started"
    } else {
        "unavailable: $($jfrResult.Output -replace '\s+', ' ')"
    }
}

try {
    Write-Host "Capturing $DurationSeconds seconds on $Serial (run_id=$runId)..."
    $perfettoResult = Invoke-AdbWithInput @(
        "shell", "perfetto", "--txt", "-c", "-", "-o", $deviceTrace
    ) $perfettoConfigText
    $perfettoResult.Output | Set-Content -LiteralPath (Join-Path $captureDir "perfetto-command.txt")
    if ($perfettoResult.ExitCode -ne 0) {
        throw "Perfetto capture failed: $($perfettoResult.Output)"
    }
} finally {
    if (-not $logcatProcess.HasExited) {
        $logcatProcess.Kill()
        $logcatProcess.WaitForExit()
    }
}

$nativeLogEndSizeResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "stat", "-c", "%s", $nativeLogRemotePath
) -AllowFailure
$nativeLogEndBytes = if (
    $nativeLogEndSizeResult.ExitCode -eq 0 -and
    $nativeLogEndSizeResult.Output.Trim() -match "^[0-9]+$"
) {
    [long]$nativeLogEndSizeResult.Output.Trim()
} else {
    $null
}
$virglLogEndSizeResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "stat", "-c", "%s", $virglLogRemotePath
) -AllowFailure
$virglLogEndBytes = if (
    $virglLogEndSizeResult.ExitCode -eq 0 -and
    $virglLogEndSizeResult.Output.Trim() -match "^[0-9]+$"
) {
    [long]$virglLogEndSizeResult.Output.Trim()
} else {
    $null
}

Invoke-Adb @("pull", $deviceTrace, (Join-Path $captureDir "system.perfetto-trace")) | Out-Null
Invoke-Adb @("shell", "rm", "-f", $deviceTrace) -AllowFailure | Out-Null

$frameTimelineStatus = "skipped"
if (-not $SkipFrameTimelineAnalysis) {
    $frameTimelineArgs = @(
        (Join-Path $projectRoot "tools/perfetto_frame_timeline.py"),
        (Join-Path $captureDir "system.perfetto-trace"),
        "--output", (Join-Path $captureDir "frame-timeline-summary.json"),
        "--intervals-output", (Join-Path $captureDir "frame-timeline-intervals.csv"),
        "--bootstrap"
    )
    if ($TraceProcessor) {
        $frameTimelineArgs += @("--trace-processor", $TraceProcessor)
    }
    & python @frameTimelineArgs
    if ($LASTEXITCODE -ne 0) {
        throw "SurfaceFlinger FrameTimeline analysis failed"
    }
    $frameTimelineStatus = "collected_external_common_per_frame_observer"
}

$deviceInfo = [ordered]@{
    serial = $Serial
    model = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Output
    device = (Invoke-Adb @("shell", "getprop", "ro.product.device")).Output
    fingerprint = (Invoke-Adb @("shell", "getprop", "ro.build.fingerprint")).Output
    sdk = (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")).Output
    boot_id = (Invoke-Adb @("shell", "cat", "/proc/sys/kernel/random/boot_id") -AllowFailure).Output
    root_identity = (Invoke-Adb @("shell", "id")).Output
    display = (Invoke-Adb @("shell", "dumpsys", "display") -AllowFailure).Output
    thermal = (Invoke-Adb @("shell", "dumpsys", "thermalservice") -AllowFailure).Output
}
$deviceInfo.display | Set-Content -LiteralPath (Join-Path $captureDir "display.txt")
$deviceInfo.thermal | Set-Content -LiteralPath (Join-Path $captureDir "thermal.txt")

function Export-RunAsTree {
    param([string]$Package, [string]$Root, [string]$Destination)
    $findResult = Invoke-Adb @("shell", "run-as", $Package, "find", $Root, "-type", "f") -AllowFailure
    if ($findResult.ExitCode -ne 0) { return "unavailable: $($findResult.Output)" }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    foreach ($remoteFile in @($findResult.Output -split "`r?`n" | Where-Object { $_ })) {
        $relative = $remoteFile.Substring($Root.Length).TrimStart("/")
        $localFile = Join-Path $Destination ($relative -replace "/", [IO.Path]::DirectorySeparatorChar)
        New-Item -ItemType Directory -Path (Split-Path -Parent $localFile) -Force | Out-Null
        [void](Export-AdbBinary @("exec-out", "run-as", $Package, "cat", $remoteFile) $localFile -AllowFailure)
    }
    return "collected"
}

$appCaptureStatus = Export-RunAsTree "com.runelitetablet" "files/captures/$runId" (Join-Path $captureDir "app")
$termuxCaptureStatus = Export-RunAsTree "com.termux" "files/home/.rlt/captures/$runId" (Join-Path $captureDir "termux")

foreach ($remoteLog in @(
    "files/home/runelite-native.log",
    "files/home/runelite-launch.log",
    "files/home/virgl-server.log"
)) {
    $name = Split-Path -Leaf $remoteLog
    [void](Export-AdbBinary @("exec-out", "run-as", "com.termux", "cat", $remoteLog) (Join-Path $captureDir $name) -AllowFailure)
}
$appVirglLog = Join-Path $captureDir "app-virgl-host.log"
[void](Export-AdbBinary @(
    "exec-out", "run-as", "com.runelitetablet", "cat", $appVirglLogRemotePath
) $appVirglLog -AllowFailure)
if (
    $null -ne $nativeLogStartBytes -and
    $null -ne $nativeLogEndBytes -and
    $nativeLogEndBytes -ge $nativeLogStartBytes
) {
    $captureWindowBytes = $nativeLogEndBytes - $nativeLogStartBytes
    [void](Export-AdbBinary @(
        "exec-out", "run-as", "com.termux",
        "/data/data/com.termux/files/usr/bin/dd",
        "if=$nativeLogRemotePath", "bs=1",
        "skip=$nativeLogStartBytes", "count=$captureWindowBytes", "status=none"
    ) (Join-Path $captureDir "runelite-native-capture-window.log") -AllowFailure)
}
if (
    $null -ne $virglLogStartBytes -and
    $null -ne $virglLogEndBytes -and
    $virglLogEndBytes -ge $virglLogStartBytes
) {
    $virglCaptureWindowBytes = $virglLogEndBytes - $virglLogStartBytes
    [void](Export-AdbBinary @(
        "exec-out", "run-as", "com.termux",
        "/data/data/com.termux/files/usr/bin/dd",
        "if=$virglLogRemotePath", "bs=1",
        "skip=$virglLogStartBytes", "count=$virglCaptureWindowBytes", "status=none"
    ) (Join-Path $captureDir "virgl-server-capture-window.log") -AllowFailure)
}

$sensitivePattern = "(?i)(JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|access_token|refresh_token)\s*[=:]\s*([^\s,`"']+)"
$javaPropertyPattern = "(?i)(-D(?:JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|[^=\s]*(?:token|secret|password)[^=\s]*)=)([^\s]+)"
$bearerPattern = "(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"
$gameplayTextPattern = "(?im)^.*(?:Chat message type|chatMessage=|typed key|key(?:code|event)=).*$"
$legacyAmbiguousRatePattern = "(?im)^.*(?:choreographer callbacks|redraw wakeups|damage-triggered redraws|present flip attempts|present after-flips|[0-9]+ frames) in 5\.0 seconds\s*=.*FPS.*$"
Get-ChildItem -LiteralPath $captureDir -Recurse -File |
    Where-Object { $_.Extension -in @(".txt", ".log", ".csv", ".json", ".md") } |
    ForEach-Object {
        $text = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -ne $text) {
            $text = [regex]::Replace($text, $sensitivePattern, '$1=***')
            $text = [regex]::Replace($text, $javaPropertyPattern, '$1***')
            $text = [regex]::Replace($text, $bearerPattern, 'Bearer ***')
            $text = [regex]::Replace($text, $gameplayTextPattern, '[REDACTED private gameplay content]')
            $text = [regex]::Replace(
                $text,
                $legacyAmbiguousRatePattern,
                '[IGNORED legacy ambiguous cadence; no FPS attribution]'
            )
            $text | Set-Content -LiteralPath $_.FullName -Encoding utf8
        }
    }

$gitCommit = (& git -C $projectRoot rev-parse HEAD 2>$null | Out-String).Trim()
$gitDirty = -not [string]::IsNullOrWhiteSpace(
    (& git -C $projectRoot status --porcelain 2>$null | Out-String).Trim()
)
$manifest = [ordered]@{
    schema_version = 1
    run_id = $runId
    run_identity_source = $runIdentitySource
    capture_started_utc = $captureStartedUtc
    capture_completed_utc = (Get-Date).ToUniversalTime().ToString("o")
    duration_seconds = $DurationSeconds
    native_log_start_bytes = $nativeLogStartBytes
    native_log_end_bytes = $nativeLogEndBytes
    virgl_log_start_bytes = $virglLogStartBytes
    virgl_log_end_bytes = $virglLogEndBytes
    target_hz = $TargetHz
    app_target_hz = $appTargetHz
    renderer_backend = $rendererBackend
    telemetry_mode = $telemetryMode
    git_commit = $gitCommit
    git_dirty = $gitDirty
    jfr = $jfrStatus
    frame_timeline_analysis = $frameTimelineStatus
    app_capture = $appCaptureStatus
    termux_capture = $termuxCaptureStatus
    device = $deviceInfo
    privacy = [ordered]@{
        local_only = $true
        screenshots_collected = $false
        input_text_collected = $false
        gameplay_packets_collected = $false
        text_redaction_applied = $true
    }
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $captureDir "manifest.json") -Encoding utf8

& python (Join-Path $projectRoot "tools/telemetry_cost_summary.py") `
    $captureDir --output (Join-Path $captureDir "telemetry-cost-summary.json")
$telemetryCostExit = $LASTEXITCODE
if ($telemetryCostExit -notin @(0, 2)) {
    throw "Telemetry self-cost analysis failed with exit code $telemetryCostExit"
}

if ($telemetryMode -in @("BURST", "DEEP")) {
    $nativeWindowLog = Join-Path $captureDir "runelite-native-capture-window.log"
    if (-not (Test-Path -LiteralPath $nativeWindowLog)) {
        $nativeWindowLog = Join-Path $captureDir "runelite-native.log"
    }
    $virglWindowLog = Join-Path $captureDir "virgl-server-capture-window.log"
    if (-not (Test-Path -LiteralPath $virglWindowLog)) {
        $virglWindowLog = Join-Path $captureDir "virgl-server.log"
    }
    $joinedInputs = @($nativeWindowLog, $virglWindowLog)
    if (Test-Path -LiteralPath $appVirglLog) {
        $joinedInputs += $appVirglLog
    }
    & python (Join-Path $projectRoot "tools/mesa_vtest_summary.py") `
        --agent-logs @joinedInputs --run-id $runId `
        --output (Join-Path $captureDir "mesa-vtest-summary.json") `
        --report (Join-Path $captureDir "mesa-vtest-report.md")
    if ($LASTEXITCODE -ne 0) {
        throw "Mesa vtest summary join input failed"
    }
    & python (Join-Path $projectRoot "tools/virgl_host_summary.py") `
        --agent-logs @joinedInputs --run-id $runId `
        --output (Join-Path $captureDir "virgl-host-summary.json") `
        --report (Join-Path $captureDir "virgl-host-report.md")
    if ($LASTEXITCODE -ne 0) {
        throw "Mesa/VirGL host join failed"
    }
}

& (Join-Path $PSScriptRoot "analyze-frame-capture.ps1") `
    -CaptureDirectory $captureDir -TargetHz $TargetHz
if (-not $?) { throw "Capture analyzer failed" }

$sumLines = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $captureDir -Recurse -File |
    Where-Object { $_.Name -ne "sha256sums.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $relative = [IO.Path]::GetRelativePath($captureDir, $_.FullName).Replace("\", "/")
        $sumLines.Add("$hash  $relative")
    }
$sumLines | Set-Content -LiteralPath (Join-Path $captureDir "sha256sums.txt") -Encoding ascii

Write-Host "Capture bundle: $captureDir"
