[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [ValidateRange(3, 10)]
    [int]$PairCount = 3,
    [ValidateRange(30, 300)]
    [int]$DurationSeconds = 60,
    [ValidateRange(10, 120)]
    [int]$WarmupSeconds = 30,
    [ValidateRange(30, 240)]
    [int]$TargetHz = 120,
    [string]$OutputRoot,
    [string]$TraceProcessor
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $projectRoot "perf-captures"
}
$campaignId = [guid]::NewGuid().ToString()
$campaignDirectory = Join-Path $OutputRoot (
    "telemetry-overhead-{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $campaignId
)
$captureRoot = Join-Path $campaignDirectory "captures"
New-Item -ItemType Directory -Force -Path $captureRoot | Out-Null

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $all = @("-s", $Serial) + $Arguments
    $output = & adb @all 2>&1
    $code = $LASTEXITCODE
    if (-not $AllowFailure -and $code -ne 0) {
        throw "adb $($all -join ' ') failed ($code):`n$($output | Out-String)"
    }
    return [pscustomobject]@{
        ExitCode = $code
        Output = ($output | Out-String).Trim()
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
    foreach ($argument in @("-s", $Serial) + $Arguments) {
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

function Get-AgentState {
    $raw = & python (Join-Path $projectRoot "tools/agent_diagnostics.py") state `
        --serial $Serial --project-root $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Canonical agent state query failed"
    }
    return ($raw | ConvertFrom-Json)
}

function Wait-AgentPosture {
    param(
        [Parameter(Mandatory = $true)][string]$Posture,
        [string]$Mode,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        $state = Get-AgentState
        if ($state.stateMachine.posture -eq $Posture) {
            if (-not $Mode -or $state.render.telemetryMode -eq $Mode) {
                return $state
            }
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for posture=$Posture mode=$Mode"
}

function Set-TelemetryMode {
    param([ValidateSet("OFF", "SUMMARY")][string]$Mode)
    $prefs = Invoke-Adb @(
        "shell", "run-as", "com.runelitetablet", "cat",
        "shared_prefs/launch_prefs.xml"
    )
    if ($prefs.Output -notmatch '<string name="telemetry_mode">[^<]+</string>') {
        throw "launch_prefs.xml lacks an explicit telemetry_mode; refusing an ambiguous edit"
    }
    $script = @"
set -eu
prefs=shared_prefs/launch_prefs.xml
sed -i 's#<string name="telemetry_mode">[^<]*</string>#<string name="telemetry_mode">$Mode</string>#' "`$prefs"
"@
    $editResult = Invoke-AdbWithInput @(
        "shell", "run-as", "com.runelitetablet", "sh", "-s"
    ) $script
    if ($editResult.ExitCode -ne 0) {
        throw "Failed to edit telemetry preference: $($editResult.Output)"
    }
    $verified = Invoke-Adb @(
        "shell", "run-as", "com.runelitetablet", "cat",
        "shared_prefs/launch_prefs.xml"
    )
    if ($verified.Output -notmatch "<string name=`"telemetry_mode`">$Mode</string>") {
        throw "Failed to set telemetry_mode=$Mode"
    }
}

function Get-UiXml {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/rlt-overhead-window.xml") |
        Out-Null
    return (Invoke-Adb @("shell", "cat", "/sdcard/rlt-overhead-window.xml")).Output
}

function Invoke-LaunchControl {
    param([ValidateSet("Launch RuneLite", "Stop Game")][string]$Label)
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $xml = Get-UiXml
        $escaped = [regex]::Escape($Label)
        $match = [regex]::Match(
            $xml,
            "text=`"$escaped`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
        )
        if ($match.Success) {
            $x = [int]((
                [int]$match.Groups[1].Value + [int]$match.Groups[3].Value
            ) / 2)
            $y = [int]((
                [int]$match.Groups[2].Value + [int]$match.Groups[4].Value
            ) / 2)
            # This lifecycle runner is intentionally limited to the app's
            # Launch and Stop Game controls. It never switches to the game,
            # logs in, or synthesizes gameplay input.
            Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Could not find the authorized '$Label' lifecycle control"
}

function Start-ObservedSession {
    param([ValidateSet("OFF", "SUMMARY")][string]$Mode)
    Set-TelemetryMode $Mode
    Invoke-Adb @("shell", "am", "force-stop", "com.runelitetablet") | Out-Null
    Invoke-Adb @(
        "shell", "am", "start", "-n", "com.runelitetablet/.MainActivity"
    ) | Out-Null
    [void](Wait-AgentPosture -Posture "ready_to_launch" -TimeoutSeconds 90)
    Invoke-LaunchControl "Launch RuneLite"
    $state = Wait-AgentPosture -Posture "running_x11" -Mode $Mode
    if ($state.render.backend -ne "native_x11_virgl_gpu") {
        throw "Unexpected renderer backend: $($state.render.backend)"
    }
    if ($state.render.telemetryLostEvents -ne 0) {
        throw "Telemetry loss was already nonzero at launch"
    }
    return $state
}

function Stop-ObservedSession {
    Invoke-Adb @(
        "shell", "am", "start", "-n", "com.runelitetablet/.MainActivity"
    ) | Out-Null
    Invoke-LaunchControl "Stop Game"
    [void](Wait-AgentPosture -Posture "stopped" -TimeoutSeconds 90)
}

function Write-CampaignManifest {
    param([System.Collections.Generic.List[object]]$Captures)
    $manifest = [ordered]@{
        schema_version = 1
        campaign_id = $campaignId
        created_utc = (Get-Date).ToUniversalTime().ToString("o")
        device_serial = $Serial
        target_hz = $TargetHz
        duration_seconds = $DurationSeconds
        warmup_seconds = $WarmupSeconds
        pair_count = $PairCount
        design = "counterbalanced_pairs_off_summary_then_summary_off"
        capture_observer = "android.surfaceflinger.frametimeline"
        authorization = [ordered]@{
            launch_and_stop_only = $true
            login_performed = $false
            gameplay_performed = $false
            screenshots_collected = $false
        }
        captures = @($Captures)
    }
    $manifest | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $campaignDirectory "campaign-manifest.json") `
            -Encoding utf8
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not on PATH"
}
if ((Invoke-Adb @("get-state")).Output -ne "device") {
    throw "Device $Serial is not ready"
}

$initialState = Get-AgentState
if (
    $initialState.session.state -notin @("idle", "stopped") -or
    $initialState.render.x11BinderAlive
) {
    throw "Campaign requires a clean stopped session"
}

$captures = [System.Collections.Generic.List[object]]::new()
$orderIndex = 0
Write-CampaignManifest $captures
try {
    for ($pairIndex = 1; $pairIndex -le $PairCount; $pairIndex++) {
        $pairOrder = if (($pairIndex % 2) -eq 1) {
            @("OFF", "SUMMARY")
        } else {
            @("SUMMARY", "OFF")
        }
        foreach ($mode in $pairOrder) {
            $orderIndex++
            Write-Host (
                "Campaign $campaignId pair $pairIndex/$PairCount " +
                "capture $orderIndex mode=$mode"
            )
            $state = Start-ObservedSession $mode
            Start-Sleep -Seconds $WarmupSeconds

            $captureArgs = @{
                Serial = $Serial
                DurationSeconds = $DurationSeconds
                TargetHz = $TargetHz
                OutputRoot = $captureRoot
                SkipJfr = $true
            }
            if ($TraceProcessor) {
                $captureArgs.TraceProcessor = $TraceProcessor
            }
            & (Join-Path $PSScriptRoot "capture-frame-pipeline.ps1") @captureArgs
            if ($LASTEXITCODE -ne 0) {
                throw "Frame pipeline capture failed for mode=$mode"
            }

            $runId = $state.render.captureRunId
            $captureDirectory = Get-ChildItem -LiteralPath $captureRoot -Directory |
                Where-Object { $_.Name.EndsWith("-$runId") } |
                Sort-Object LastWriteTimeUtc -Descending |
                Select-Object -First 1
            if ($null -eq $captureDirectory) {
                throw "Capture bundle for run_id=$runId was not found"
            }
            $captureManifest = Get-Content -Raw -LiteralPath (
                Join-Path $captureDirectory.FullName "manifest.json"
            ) | ConvertFrom-Json
            if ($captureManifest.telemetry_mode -ne $mode) {
                throw "Captured mode $($captureManifest.telemetry_mode), expected $mode"
            }
            $captures.Add([pscustomobject][ordered]@{
                pair_index = $pairIndex
                order_index = $orderIndex
                mode = $mode
                run_id = $runId
                capture_dir = [IO.Path]::GetRelativePath(
                    $campaignDirectory,
                    $captureDirectory.FullName
                ).Replace("\", "/")
            })
            Write-CampaignManifest $captures
            Stop-ObservedSession
        }
    }
} finally {
    $state = Get-AgentState
    if ($state.session.state -eq "running" -or $state.render.x11BinderAlive) {
        try { Stop-ObservedSession } catch { Write-Warning $_ }
    }
    Set-TelemetryMode "SUMMARY"
    Invoke-Adb @("shell", "am", "force-stop", "com.runelitetablet") |
        Out-Null
}

$comparisonPath = Join-Path $campaignDirectory "overhead-summary.json"
$reportPath = Join-Path $campaignDirectory "report.md"
& python (Join-Path $projectRoot "tools/telemetry_overhead.py") `
    (Join-Path $campaignDirectory "campaign-manifest.json") `
    --output $comparisonPath --report $reportPath
$comparisonExitCode = $LASTEXITCODE
if ($comparisonExitCode -notin @(0, 2)) {
    throw "Overhead campaign analyzer failed with exit code $comparisonExitCode"
}

$sumLines = [System.Collections.Generic.List[string]]::new()
Get-ChildItem -LiteralPath $campaignDirectory -Recurse -File |
    Where-Object { $_.Name -ne "sha256sums.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $relative = [IO.Path]::GetRelativePath(
            $campaignDirectory,
            $_.FullName
        ).Replace("\", "/")
        $sumLines.Add("$hash  $relative")
    }
$sumLines | Set-Content -LiteralPath (
    Join-Path $campaignDirectory "sha256sums.txt"
) -Encoding ascii

Write-Host "Telemetry overhead campaign: $campaignDirectory"
exit $comparisonExitCode
