param(
    [string]$Serial,
    [ValidateRange(1, 30)]
    [int]$DurationSeconds = 5,
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
    $code = $LASTEXITCODE
    if (-not $AllowFailure -and $code -ne 0) {
        throw "adb $($all -join ' ') failed ($code):`n$($output | Out-String)"
    }
    return [pscustomobject]@{
        ExitCode = $code
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
    return [pscustomobject]@{
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
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "adb binary export failed ($($process.ExitCode)): $errorText"
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

$deviceState = (Invoke-Adb @("get-state")).Output
if ($deviceState -ne "device") {
    throw "Device $Serial is not ready: $deviceState"
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
$rendererBackend = if ($runMatches.Count -gt 0) {
    $runMatches[$runMatches.Count - 1].Groups[2].Value
} else {
    "unavailable"
}
$appTelemetryMode = if ($runMatches.Count -gt 0) {
    $runMatches[$runMatches.Count - 1].Groups[3].Value
} else {
    "unavailable"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureDir = Join-Path $OutputRoot "$timestamp-$runId-virgl-seams"
New-Item -ItemType Directory -Path $captureDir -Force | Out-Null
$remoteRoot = "/data/data/com.termux/files/home/.rlt/captures/$runId/virgl-seams-$timestamp"

$remoteScript = @'
set -u
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:$PREFIX/bin/applets"
remote_root="__REMOTE_ROOT__"
duration_seconds="__DURATION_SECONDS__"
mkdir -p "$remote_root"

target_pid=""
for candidate in $(pgrep -x virgl_test_server_android 2>/dev/null); do
    if ls -l "/proc/$candidate/fd" 2>/dev/null | grep -q "/dev/mali0"; then
        target_pid="$candidate"
        break
    fi
done
if [ -z "$target_pid" ]; then
    printf '%s\n' "error=no_active_virgl_worker" > "$remote_root/status.txt"
    exit 2
fi
if ! command -v strace >/dev/null 2>&1; then
    printf '%s\n' "error=strace_unavailable" > "$remote_root/status.txt"
    exit 3
fi

native_log="$HOME/runelite-native.log"
native_log_start=0
if [ -f "$native_log" ]; then
    native_log_start=$(stat -c '%s' "$native_log" 2>/dev/null || printf '0')
fi

{
    printf 'target_pid=%s\n' "$target_pid"
    python -c 'import time; a=time.time_ns(); m=time.monotonic_ns(); b=time.time_ns(); print(f"clock_start_realtime_before_ns={a}\nclock_start_monotonic_ns={m}\nclock_start_realtime_after_ns={b}")'
    printf 'duration_seconds=%s\n' "$duration_seconds"
    printf 'package=%s\n' "$(dpkg-query -W -f='${Package}' virglrenderer-android 2>/dev/null || true)"
    printf 'package_version=%s\n' "$(dpkg-query -W -f='${Version}' virglrenderer-android 2>/dev/null || true)"
    printf 'package_architecture=%s\n' "$(dpkg-query -W -f='${Architecture}' virglrenderer-android 2>/dev/null || true)"
    printf 'binary_path=%s\n' "$(command -v virgl_test_server_android)"
    printf 'binary_sha256=%s\n' "$(sha256sum "$(command -v virgl_test_server_android)" | cut -d' ' -f1)"
    printf 'libvirglrenderer_sha256=%s\n' "$(sha256sum "$PREFIX/opt/virglrenderer-android/lib/libvirglrenderer.so" | cut -d' ' -f1)"
    printf 'libepoxy_sha256=%s\n' "$(sha256sum "$PREFIX/opt/virglrenderer-android/lib/libepoxy.so" | cut -d' ' -f1)"
    printf 'native_log_start_bytes=%s\n' "$native_log_start"
} > "$remote_root/metadata.txt"

grep -E '^(Name|State|Pid|PPid|TracerPid|Threads):' \
    "/proc/$target_pid/status" > "$remote_root/process-before.txt" 2>&1 || true
ls -l "/proc/$target_pid/fd" > "$remote_root/fd-before.txt" 2>&1 || true
ps -T -p "$target_pid" -o PID,TID,STAT,PSR,PCPU,COMM \
    > "$remote_root/threads-before.txt" 2>&1 || true

set +e
timeout "$duration_seconds" strace \
    -f -ttt -T -yy -qq -s 1 \
    -e trace=read,readv,recvfrom,recvmsg,write,writev,sendto,sendmsg,ioctl,poll,ppoll,epoll_wait,epoll_pwait,futex \
    -o "$remote_root/virgl.strace" \
    -p "$target_pid" \
    2> "$remote_root/strace.stderr.txt"
strace_status=$?
set -e

native_log_end="$native_log_start"
if [ -f "$native_log" ]; then
    native_log_end=$(stat -c '%s' "$native_log" 2>/dev/null || printf '%s' "$native_log_start")
fi
if [ "$native_log_end" -ge "$native_log_start" ] && [ "$native_log_end" -gt "$native_log_start" ]; then
    dd if="$native_log" bs=1 skip="$native_log_start" \
        count="$((native_log_end - native_log_start))" status=none \
        > "$remote_root/runelite-native-window.log" 2>/dev/null || true
fi

{
    printf 'strace_exit_status=%s\n' "$strace_status"
    python -c 'import time; a=time.time_ns(); m=time.monotonic_ns(); b=time.time_ns(); print(f"clock_end_realtime_before_ns={a}\nclock_end_monotonic_ns={m}\nclock_end_realtime_after_ns={b}")'
    printf 'native_log_end_bytes=%s\n' "$native_log_end"
    printf 'tracer_pid_after=%s\n' "$(awk '/^TracerPid:/ {print $2}' "/proc/$target_pid/status" 2>/dev/null || true)"
} >> "$remote_root/metadata.txt"
grep -E '^(Name|State|Pid|PPid|TracerPid|Threads):' \
    "/proc/$target_pid/status" > "$remote_root/process-after.txt" 2>&1 || true
ps -T -p "$target_pid" -o PID,TID,STAT,PSR,PCPU,COMM \
    > "$remote_root/threads-after.txt" 2>&1 || true
tracer_pid_after=$(awk '/^TracerPid:/ {print $2}' "/proc/$target_pid/status" 2>/dev/null || true)
if [ -n "$tracer_pid_after" ] && [ "$tracer_pid_after" != "0" ]; then
    printf '%s\n' "error=tracer_still_attached tracer_pid=$tracer_pid_after" \
        > "$remote_root/status.txt"
    exit 4
fi
printf '%s\n' "ok target_pid=$target_pid strace_status=$strace_status root=$remote_root"
'@
$remoteScript = $remoteScript.Replace("__REMOTE_ROOT__", $remoteRoot)
$remoteScript = $remoteScript.Replace(
    "__DURATION_SECONDS__",
    $DurationSeconds.ToString([Globalization.CultureInfo]::InvariantCulture)
)

$logcatOut = Join-Path $captureDir "device-window.logcat.txt"
$logcatErr = Join-Path $captureDir "device-window.logcat.stderr.txt"
$logcatArgs = @()
if ($Serial) { $logcatArgs += @("-s", $Serial) }
$logcatArgs += @(
    "logcat", "-v", "threadtime", "-T", "1",
    "RLT:V", "LorieNative:V", "gles-renderer:V", "*:S"
)
$logcatProcess = Start-Process -FilePath "adb" -ArgumentList $logcatArgs `
    -RedirectStandardOutput $logcatOut -RedirectStandardError $logcatErr `
    -WindowStyle Hidden -PassThru

try {
    Write-Host "Capturing invasive VirGL seams for $DurationSeconds seconds on $Serial..."
    $captureResult = Invoke-AdbWithInput @(
        "shell", "run-as", "com.termux",
        "/data/data/com.termux/files/usr/bin/bash", "-s"
    ) $remoteScript
    if ($captureResult.ExitCode -ne 0) {
        throw "VirGL seam capture failed ($($captureResult.ExitCode)): $($captureResult.Output)"
    }
    $captureResult.Output | Set-Content -LiteralPath (
        Join-Path $captureDir "capture-command.txt"
    ) -Encoding utf8
} finally {
    if (-not $logcatProcess.HasExited) {
        $logcatProcess.Kill()
        $logcatProcess.WaitForExit()
    }
}

$remoteFilesResult = Invoke-Adb @(
    "shell", "run-as", "com.termux", "find", $remoteRoot, "-type", "f"
)
foreach ($remoteFile in @($remoteFilesResult.Output -split "`r?`n" | Where-Object { $_ })) {
    $relative = $remoteFile.Substring($remoteRoot.Length).TrimStart("/")
    $destination = Join-Path $captureDir ($relative -replace "/", [IO.Path]::DirectorySeparatorChar)
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Export-AdbBinary @("exec-out", "run-as", "com.termux", "cat", $remoteFile) $destination
}

$sensitivePattern = "(?i)(JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|access_token|refresh_token)\s*[=:]\s*([^\s,`"']+)"
$javaPropertyPattern = "(?i)(-D(?:JX_(?:SESSION_ID|CHARACTER_ID|DISPLAY_NAME)|[^=\s]*(?:token|secret|password)[^=\s]*)=)([^\s]+)"
$bearerPattern = "(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"
$gameplayTextPattern = "(?im)^.*(?:Chat message type|chatMessage=|typed key|key(?:code|event)=).*$"
Get-ChildItem -LiteralPath $captureDir -Recurse -File |
    Where-Object { $_.Extension -in @(".txt", ".log", ".csv", ".json", ".md") } |
    ForEach-Object {
        $text = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue
        if ($null -ne $text) {
            $text = [regex]::Replace($text, $sensitivePattern, '$1=***')
            $text = [regex]::Replace($text, $javaPropertyPattern, '$1***')
            $text = [regex]::Replace($text, $bearerPattern, 'Bearer ***')
            $text = [regex]::Replace(
                $text,
                $gameplayTextPattern,
                '[REDACTED private gameplay content]'
            )
            $text | Set-Content -LiteralPath $_.FullName -Encoding utf8
        }
    }

$metadata = @{}
Get-Content -LiteralPath (Join-Path $captureDir "metadata.txt") | ForEach-Object {
    $parts = $_ -split "=", 2
    if ($parts.Count -eq 2) { $metadata[$parts[0]] = $parts[1] }
}
if (-not $metadata.ContainsKey("target_pid")) {
    throw "Capture metadata has no target_pid"
}
$traceFiles = @(
    Get-ChildItem -LiteralPath $captureDir -File |
        Where-Object { $_.Name -like "virgl.strace*" } |
        Sort-Object Name
)
if ($traceFiles.Count -eq 0) {
    throw "Capture produced no virgl.strace file"
}

$summaryPath = Join-Path $captureDir "virgl-seams-summary.json"
$reportPath = Join-Path $captureDir "virgl-seams-report.md"
$analysisArguments = @(
    (Join-Path $projectRoot "tools/virgl_seams.py"),
    "--input"
)
$analysisArguments += @($traceFiles.FullName)
$analysisArguments += @(
    "--main-pid", $metadata["target_pid"],
    "--metadata", (Join-Path $captureDir "metadata.txt"),
    "--output", $summaryPath,
    "--report", $reportPath
)
& python @analysisArguments
if ($LASTEXITCODE -ne 0) {
    throw "VirGL seam analyzer failed ($LASTEXITCODE)"
}

$gitCommit = (& git -C $projectRoot rev-parse HEAD 2>$null | Out-String).Trim()
$gitDirty = -not [string]::IsNullOrWhiteSpace(
    (& git -C $projectRoot status --porcelain 2>$null | Out-String).Trim()
)
$manifest = [ordered]@{
    schema_version = 1
    run_id = $runId
    run_identity_source = $runIdentitySource
    capture_mode = "invasive_deep_syscall_trace"
    duration_seconds = $DurationSeconds
    renderer_backend = $rendererBackend
    app_telemetry_mode = $appTelemetryMode
    target_pid = [int]$metadata["target_pid"]
    git_commit = $gitCommit
    git_dirty = $gitDirty
    device = [ordered]@{
        serial = $Serial
        model = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Output
        fingerprint = (Invoke-Adb @("shell", "getprop", "ro.build.fingerprint")).Output
        boot_id = (Invoke-Adb @(
            "shell", "cat", "/proc/sys/kernel/random/boot_id"
        ) -AllowFailure).Output
    }
    virgl = [ordered]@{
        package = $metadata["package"]
        package_version = $metadata["package_version"]
        package_architecture = $metadata["package_architecture"]
        binary_path = $metadata["binary_path"]
        binary_sha256 = $metadata["binary_sha256"]
        libvirglrenderer_sha256 = $metadata["libvirglrenderer_sha256"]
        libepoxy_sha256 = $metadata["libepoxy_sha256"]
        upstream_tag = "virglrenderer-1.3.0"
        upstream_commit = "ca50e008863837e094747a69974dde3ae148aeaa"
        termux_recipe = "https://github.com/termux/termux-packages/tree/master/packages/virglrenderer-android"
        source_vendored = $false
        binary_modified = $false
    }
    privacy = [ordered]@{
        local_only = $true
        screenshots_collected = $false
        input_text_collected = $false
        gameplay_packets_collected = $false
        syscall_string_limit_bytes = 1
        text_redaction_applied = $true
    }
    interpretation_limits = @(
        "ptrace is invasive; captured throughput is not a benchmark result",
        "syscall wall times on different threads overlap",
        "driver ioctl duration is not GPU execution time",
        "VirGL remains unmodified; source will be vendored only if evidence requires it"
    )
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $captureDir "manifest.json") -Encoding utf8

$sumLines = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $captureDir -Recurse -File |
    Where-Object { $_.Name -ne "sha256sums.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $relative = [IO.Path]::GetRelativePath($captureDir, $_.FullName).Replace("\", "/")
        $sumLines.Add("$hash  $relative")
    }
$sumLines | Set-Content -LiteralPath (
    Join-Path $captureDir "sha256sums.txt"
) -Encoding ascii

Write-Host "VirGL seam capture bundle: $captureDir"
