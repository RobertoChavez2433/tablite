param(
    [ValidateSet("hybrid", "stock", "internal-hybrid")]
    [string]$Variant = "hybrid",
    [ValidateSet("default", "native", "half-res")]
    [string]$GpuDisplayResolution = "default",
    [ValidateSet("1", "2")]
    [string]$RuneLiteScale = "2",
    [ValidateSet("default", "xrender", "opengl")]
    [string]$Java2dProfile = "default",
    [ValidateSet("default", "glthread", "dri3-off")]
    [string]$MesaProfile = "default",
    [ValidateSet("default", "no-loop-or-fork", "angle-gl", "angle-vulkan")]
    [string]$VirglServerProfile = "default",
    [ValidateSet("launcher", "direct-jvm")]
    [string]$RuneLiteLaunchMode = "launcher",
    [int]$LaunchTimeoutSeconds = 120,
    [int]$CaptureDurationSeconds = 20,
    [int]$PostShutdownWaitSeconds = 3,
    [string]$Serial,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$captureRoot = Join-Path $projectRoot "perf-captures"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resolutionSlug = switch ($GpuDisplayResolution) {
    "default" { "defaultres" }
    "native" { "nativeres" }
    "half-res" { "halfres" }
}
$java2dSlug = switch ($Java2dProfile) {
    "default" { "j2d-default" }
    "xrender" { "j2d-xrender" }
    "opengl" { "j2d-opengl" }
}
$mesaSlug = switch ($MesaProfile) {
    "default" { "mesa-default" }
    "glthread" { "mesa-glthread" }
    "dri3-off" { "mesa-dri3off" }
}
$virglServerSlug = switch ($VirglServerProfile) {
    "default" { "virglsrv-default" }
    "no-loop-or-fork" { "virglsrv-noloop" }
    "angle-gl" { "virglsrv-anglegl" }
    "angle-vulkan" { "virglsrv-anglevk" }
}
$launchModeSlug = switch ($RuneLiteLaunchMode) {
    "launcher" { "launch-launcher" }
    "direct-jvm" { "launch-directjvm" }
}
$captureBaseName = "$Variant-runelite-$resolutionSlug-$java2dSlug-$mesaSlug-$virglServerSlug-$launchModeSlug-clean-$timestamp"
$logPath = Join-Path $captureRoot "$captureBaseName.logcat.txt"
$summaryPath = Join-Path $captureRoot "$captureBaseName.summary.txt"

New-Item -ItemType Directory -Path $captureRoot -Force | Out-Null

$surfacePatternByVariant = @{
    hybrid = "SurfaceView[com.runelitetablet/com.runelitetablet.presentation.hybrid.HybridX11HostActivity]@0(BLAST)"
    "internal-hybrid" = "SurfaceView[com.runelitetablet/com.runelitetablet.presentation.hybrid.HybridX11HostActivity]@0(BLAST)"
    stock = "SurfaceView[com.termux.x11/com.termux.x11.MainActivity]@0(BLAST)"
}
$visibleSurfacePattern = $surfacePatternByVariant[$Variant]
$gpuDisplayResolutionOverride = switch ($GpuDisplayResolution) {
    "default" { $null }
    "native" { "native" }
    "half-res" { "custom:1480x924" }
}
$filteredLogcatArgs = @(
    "logcat", "-d", "-v", "threadtime",
    "RLT:V",
    "LorieNative:D",
    "gles-renderer:D",
    "BufferQueueProducer:I",
    "LayerHistory:I",
    "ActivityManager:I",
    "*:S"
)

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    $fullArguments = @()
    if ($Serial) {
        $fullArguments += @("-s", $Serial)
    }
    $fullArguments += $Arguments

    $output = & adb @fullArguments 2>&1
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb $($fullArguments -join ' ') failed with exit code $exitCode`n$output"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output | Out-String).TrimEnd()
    }
}

function Get-FilteredLogcatText {
    return (Invoke-Adb -Arguments $filteredLogcatArgs -AllowFailure).Output
}

function Invoke-LaunchStateDump {
    Invoke-Adb -Arguments @(
        "shell", "am", "broadcast",
        "-a", "com.runelitetablet.action.DUMP_REAL_HYBRID_LAUNCH_STATE",
        "-n", "com.runelitetablet/.presentation.hybrid.HybridX11TestReceiver"
    ) -AllowFailure | Out-Null
}

function Get-LastRegexCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $matches = @()
    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) {
            $matches += $match
        }
    }
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[-1]
}

function Get-LastMatchingLine {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $matches = @($Lines | Where-Object { $_ -match $Pattern })
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[-1]
}

function Get-MaxRegexCaptureValue {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $values = @()
    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) {
            $values += [double]::Parse($match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
        }
    }
    if ($values.Count -eq 0) {
        return $null
    }
    return ($values | Measure-Object -Maximum).Maximum
}

function Get-RegexCaptureValues {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $values = New-Object System.Collections.Generic.List[double]
    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) {
            $values.Add([double]::Parse($match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture))
        }
    }
    return @($values.ToArray())
}

function Get-MedianValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [double[]]$Values
    )

    if ($Values.Count -eq 0) {
        return $null
    }

    $sortedValues = @($Values | Sort-Object)
    $middleIndex = [int]($sortedValues.Count / 2)
    if ($sortedValues.Count % 2 -eq 1) {
        return $sortedValues[$middleIndex]
    }
    return (($sortedValues[$middleIndex - 1] + $sortedValues[$middleIndex]) / 2.0)
}

function Get-LatestLaunchScopedLines {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    for ($index = $Lines.Count - 1; $index -ge 0; $index--) {
        if ($Lines[$index] -match "=== RuneLite launch ") {
            return @($Lines[$index..($Lines.Count - 1)])
        }
    }

    return $Lines
}

function Get-RuneliteEvidenceSummary {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    $launchScopedLines = @(Get-LatestLaunchScopedLines -Lines $Lines)

    $gpuDeviceLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "GpuPlugin - Using device:"
    $gpuDriverLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "GpuPlugin - Using driver:"
    $clientInitLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Client initialization took"
    $configuredGpuSettingsLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured RuneLite GPU settings:"
    $configuredRuneLiteScaleLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured RuneLite scale:"
    $configuredJava2dProfileLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured Java2D profile:"
    $configuredMesaProfileLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured Mesa profile:"
    $configuredVirglServerProfileLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured VirGL server profile:"
    $configuredRuneLiteLaunchModeLine = Get-LastMatchingLine -Lines $launchScopedLines -Pattern "Configured RuneLite launch mode:"
    $stateRunningSeen = @($launchScopedLines | Where-Object { $_ -match "\brunning\s*$" }).Count -gt 0

    $surfaceVotePattern = [regex]::Escape($visibleSurfacePattern) + ".*voted ExplicitDefault \(120\.00 Hz\)"
    $surfaceHighCategoryPattern = [regex]::Escape($visibleSurfacePattern) + ".*voted ExplicitCategory with category: High"
    $layer120Votes = @($Lines | Where-Object { $_ -match $surfaceVotePattern })
    $layerHighCategoryVotes = @($Lines | Where-Object { $_ -match $surfaceHighCategoryPattern })

    $bufferPattern = [regex]::Escape($visibleSurfacePattern) + ".*queueBuffer: fps=([0-9]+(?:\.[0-9]+)?)"
    $loriePattern = "LorieNative: ([0-9]+) frames in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $lorieFpsPattern = "LorieNative: [0-9]+ frames in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $choreographerPattern = "LorieNative: choreographer callbacks in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $redrawWakeupPattern = "LorieNative: redraw wakeups in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $damageRequestPattern = "LorieNative: damage-triggered redraws in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $presentAfterFlipPattern = "LorieNative: present after-flips in 5\.0 seconds = ([0-9]+(?:\.[0-9]+)?) FPS"
    $consumerRedrawPattern = "gles-renderer: XloriePerf: .*consumer_redraw_fps=([0-9]+(?:\.[0-9]+)?)"

    $lastBufferMatch = Get-LastRegexCapture -Lines $Lines -Pattern $bufferPattern
    $bufferValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $bufferPattern)
    $lastLorieMatch = Get-LastRegexCapture -Lines $Lines -Pattern $loriePattern
    $lorieFpsValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $lorieFpsPattern)
    $choreographerValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $choreographerPattern)
    $redrawWakeupValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $redrawWakeupPattern)
    $damageRequestValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $damageRequestPattern)
    $presentAfterFlipValues = @(Get-RegexCaptureValues -Lines $Lines -Pattern $presentAfterFlipPattern)
    $lastRendererPerfLine = Get-LastMatchingLine -Lines $Lines -Pattern "gles-renderer: XloriePerf:"
    $lastConsumerRedrawMatch = Get-LastRegexCapture -Lines $Lines -Pattern $consumerRedrawPattern

    return [pscustomobject]@{
        StateRunningSeen = $stateRunningSeen
        GpuDeviceLine = $gpuDeviceLine
        GpuDriverLine = $gpuDriverLine
        ClientInitLine = $clientInitLine
        ConfiguredGpuSettingsLine = $configuredGpuSettingsLine
        ConfiguredRuneLiteScaleLine = $configuredRuneLiteScaleLine
        ConfiguredJava2dProfileLine = $configuredJava2dProfileLine
        ConfiguredMesaProfileLine = $configuredMesaProfileLine
        ConfiguredVirglServerProfileLine = $configuredVirglServerProfileLine
        ConfiguredRuneLiteLaunchModeLine = $configuredRuneLiteLaunchModeLine
        VisibleSurfacePattern = $visibleSurfacePattern
        Layer120VoteCount = $layer120Votes.Count
        LastLayer120VoteLine = if ($layer120Votes.Count -gt 0) { $layer120Votes[-1] } else { $null }
        LayerHighCategoryVoteCount = $layerHighCategoryVotes.Count
        LastLayerHighCategoryVoteLine = if ($layerHighCategoryVotes.Count -gt 0) { $layerHighCategoryVotes[-1] } else { $null }
        BufferQueueSampleCount = $bufferValues.Count
        LastBufferQueueFps = if ($lastBufferMatch) { $lastBufferMatch.Groups[1].Value } else { $null }
        AverageBufferQueueFps = if ($bufferValues.Count -gt 0) { ($bufferValues | Measure-Object -Average).Average } else { $null }
        MedianBufferQueueFps = Get-MedianValue -Values $bufferValues
        MaxBufferQueueFps = if ($bufferValues.Count -gt 0) { ($bufferValues | Measure-Object -Maximum).Maximum } else { $null }
        LorieSampleCount = $lorieFpsValues.Count
        LastLorieFrames = if ($lastLorieMatch) { $lastLorieMatch.Groups[1].Value } else { $null }
        LastLorieFps = if ($lastLorieMatch) { $lastLorieMatch.Groups[2].Value } else { $null }
        AverageLorieFps = if ($lorieFpsValues.Count -gt 0) { ($lorieFpsValues | Measure-Object -Average).Average } else { $null }
        MedianLorieFps = Get-MedianValue -Values $lorieFpsValues
        MaxLorieFps = if ($lorieFpsValues.Count -gt 0) { ($lorieFpsValues | Measure-Object -Maximum).Maximum } else { $null }
        ChoreographerSampleCount = $choreographerValues.Count
        LastChoreographerFps = if ($choreographerValues.Count -gt 0) { $choreographerValues[-1] } else { $null }
        AverageChoreographerFps = if ($choreographerValues.Count -gt 0) { ($choreographerValues | Measure-Object -Average).Average } else { $null }
        RedrawWakeupSampleCount = $redrawWakeupValues.Count
        LastRedrawWakeupFps = if ($redrawWakeupValues.Count -gt 0) { $redrawWakeupValues[-1] } else { $null }
        AverageRedrawWakeupFps = if ($redrawWakeupValues.Count -gt 0) { ($redrawWakeupValues | Measure-Object -Average).Average } else { $null }
        DamageRequestSampleCount = $damageRequestValues.Count
        LastDamageRequestFps = if ($damageRequestValues.Count -gt 0) { $damageRequestValues[-1] } else { $null }
        AverageDamageRequestFps = if ($damageRequestValues.Count -gt 0) { ($damageRequestValues | Measure-Object -Average).Average } else { $null }
        PresentAfterFlipSampleCount = $presentAfterFlipValues.Count
        LastPresentAfterFlipFps = if ($presentAfterFlipValues.Count -gt 0) { $presentAfterFlipValues[-1] } else { $null }
        AveragePresentAfterFlipFps = if ($presentAfterFlipValues.Count -gt 0) { ($presentAfterFlipValues | Measure-Object -Average).Average } else { $null }
        LastConsumerRedrawFps = if ($lastConsumerRedrawMatch) { $lastConsumerRedrawMatch.Groups[1].Value } else { $null }
        LastRendererPerfLine = $lastRendererPerfLine
    }
}

Write-Host "capture_base=$captureBaseName"
Write-Host "log_path=$logPath"
Write-Host "summary_path=$summaryPath"
Write-Host "variant=$Variant"
Write-Host "visible_surface_pattern=$visibleSurfacePattern"
Write-Host "gpu_display_resolution=$GpuDisplayResolution"
Write-Host "runelite_scale=$RuneLiteScale"
Write-Host "java2d_profile=$Java2dProfile"
Write-Host "mesa_profile=$MesaProfile"
Write-Host "virgl_server_profile=$VirglServerProfile"
Write-Host "runelite_launch_mode=$RuneLiteLaunchMode"

Invoke-Adb -Arguments @("get-state") | Out-Null

Write-Host "best-effort shutdown of previous RuneLite session"
Invoke-Adb -Arguments @(
    "shell", "am", "broadcast",
    "-a", "com.runelitetablet.action.SHUTDOWN_REAL_HYBRID_LAUNCHER",
    "-n", "com.runelitetablet/.presentation.hybrid.HybridX11TestReceiver"
) -AllowFailure | Out-Null
Start-Sleep -Seconds 2

Write-Host "forcing clean start for com.termux, com.termux.x11, and com.runelitetablet"
Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.termux") -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.termux.x11") -AllowFailure | Out-Null
Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.runelitetablet") -AllowFailure | Out-Null
Start-Sleep -Seconds 1

Write-Host "clearing logcat and gfxinfo"
Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", "com.runelitetablet", "reset") -AllowFailure | Out-Null

Write-Host "dispatching real RuneLite launcher variant=$Variant"
$launchBroadcastArgs = @(
    "shell", "am", "broadcast",
    "-a", "com.runelitetablet.action.RUN_REAL_HYBRID_LAUNCHER",
    "-n", "com.runelitetablet/.presentation.hybrid.HybridX11TestReceiver",
    "--es", "presentation_variant", $Variant
)
if ($gpuDisplayResolutionOverride) {
    $launchBroadcastArgs += @("--es", "gpu_display_resolution", $gpuDisplayResolutionOverride)
}
$launchBroadcastArgs += @("--es", "runelite_scale", $RuneLiteScale)
$launchBroadcastArgs += @("--es", "java2d_profile", $Java2dProfile)
$launchBroadcastArgs += @("--es", "mesa_profile", $MesaProfile)
$launchBroadcastArgs += @("--es", "virgl_server_profile", $VirglServerProfile)
$launchBroadcastArgs += @("--es", "runelite_launch_mode", $RuneLiteLaunchMode)
$launchResult = Invoke-Adb -Arguments $launchBroadcastArgs
Write-Host $launchResult.Output

$deadline = (Get-Date).AddSeconds($LaunchTimeoutSeconds)
$launchReady = $false
$pollCount = 0
do {
    Start-Sleep -Seconds 3
    $pollCount += 1
    if ($pollCount % 3 -eq 0) {
        Invoke-LaunchStateDump
        Start-Sleep -Seconds 2
    }
    $logText = Get-FilteredLogcatText
    $scopedLogLines = @(Get-LatestLaunchScopedLines -Lines ($logText -split "`r?`n"))
    $scopedLogText = $scopedLogLines -join "`n"
    if (
        $scopedLogText -match "GpuPlugin - Using device:" -or
        $scopedLogText -match "Client initialization took" -or
        $scopedLogText -match "(?m)^\s*running\s*$"
    ) {
        $launchReady = $true
        break
    }
} while ((Get-Date) -lt $deadline)

if (-not $launchReady) {
    Invoke-LaunchStateDump
    Start-Sleep -Seconds 2
    $logText = Get-FilteredLogcatText
    Set-Content -Path $logPath -Value $logText
    throw "RuneLite did not reach GPU/client initialization within $LaunchTimeoutSeconds seconds. Log captured at $logPath"
}

Write-Host "RuneLite reached client initialization; capturing surface evidence for $CaptureDurationSeconds seconds"
Start-Sleep -Seconds $CaptureDurationSeconds

Write-Host "dumping launch state into logcat"
Invoke-LaunchStateDump
Start-Sleep -Seconds 3

$logText = Get-FilteredLogcatText
Set-Content -Path $logPath -Value $logText
$lines = Get-Content $logPath
$summary = Get-RuneliteEvidenceSummary -Lines $lines

$summaryLines = @(
    "capture_base=$captureBaseName"
    "variant=$Variant"
    "gpu_display_resolution=$GpuDisplayResolution"
    "runelite_scale=$RuneLiteScale"
    "java2d_profile=$Java2dProfile"
    "mesa_profile=$MesaProfile"
    "virgl_server_profile=$VirglServerProfile"
    "runelite_launch_mode=$RuneLiteLaunchMode"
    "keep_running=$([bool]$KeepRunning)"
    "launch_timeout_seconds=$LaunchTimeoutSeconds"
    "capture_duration_seconds=$CaptureDurationSeconds"
    "state_running_seen=$($summary.StateRunningSeen)"
    "gpu_device_line=$($summary.GpuDeviceLine)"
    "gpu_driver_line=$($summary.GpuDriverLine)"
    "client_init_line=$($summary.ClientInitLine)"
    "configured_gpu_settings_line=$($summary.ConfiguredGpuSettingsLine)"
    "configured_runelite_scale_line=$($summary.ConfiguredRuneLiteScaleLine)"
    "configured_java2d_profile_line=$($summary.ConfiguredJava2dProfileLine)"
    "configured_mesa_profile_line=$($summary.ConfiguredMesaProfileLine)"
    "configured_virgl_server_profile_line=$($summary.ConfiguredVirglServerProfileLine)"
    "configured_runelite_launch_mode_line=$($summary.ConfiguredRuneLiteLaunchModeLine)"
    "visible_surface_pattern=$($summary.VisibleSurfacePattern)"
    "layer_120_vote_count=$($summary.Layer120VoteCount)"
    "last_layer_120_vote_line=$($summary.LastLayer120VoteLine)"
    "layer_high_category_vote_count=$($summary.LayerHighCategoryVoteCount)"
    "last_layer_high_category_vote_line=$($summary.LastLayerHighCategoryVoteLine)"
    "bufferqueue_sample_count=$($summary.BufferQueueSampleCount)"
    "last_bufferqueue_fps=$($summary.LastBufferQueueFps)"
    "average_bufferqueue_fps=$($summary.AverageBufferQueueFps)"
    "median_bufferqueue_fps=$($summary.MedianBufferQueueFps)"
    "max_bufferqueue_fps=$($summary.MaxBufferQueueFps)"
    "lorie_sample_count=$($summary.LorieSampleCount)"
    "last_lorie_frames=$($summary.LastLorieFrames)"
    "last_lorie_fps=$($summary.LastLorieFps)"
    "average_lorie_fps=$($summary.AverageLorieFps)"
    "median_lorie_fps=$($summary.MedianLorieFps)"
    "max_lorie_fps=$($summary.MaxLorieFps)"
    "choreographer_sample_count=$($summary.ChoreographerSampleCount)"
    "last_choreographer_fps=$($summary.LastChoreographerFps)"
    "average_choreographer_fps=$($summary.AverageChoreographerFps)"
    "redraw_wakeup_sample_count=$($summary.RedrawWakeupSampleCount)"
    "last_redraw_wakeup_fps=$($summary.LastRedrawWakeupFps)"
    "average_redraw_wakeup_fps=$($summary.AverageRedrawWakeupFps)"
    "damage_request_sample_count=$($summary.DamageRequestSampleCount)"
    "last_damage_request_fps=$($summary.LastDamageRequestFps)"
    "average_damage_request_fps=$($summary.AverageDamageRequestFps)"
    "present_after_flip_sample_count=$($summary.PresentAfterFlipSampleCount)"
    "last_present_after_flip_fps=$($summary.LastPresentAfterFlipFps)"
    "average_present_after_flip_fps=$($summary.AveragePresentAfterFlipFps)"
    "last_xlorie_consumer_redraw_fps=$($summary.LastConsumerRedrawFps)"
    "last_renderer_perf_line=$($summary.LastRendererPerfLine)"
    "log_path=$logPath"
)

Set-Content -Path $summaryPath -Value $summaryLines
$summaryLines | ForEach-Object { Write-Host $_ }

if (-not $KeepRunning) {
    Write-Host "shutting down RuneLite session"
    Invoke-Adb -Arguments @(
        "shell", "am", "broadcast",
        "-a", "com.runelitetablet.action.SHUTDOWN_REAL_HYBRID_LAUNCHER",
        "-n", "com.runelitetablet/.presentation.hybrid.HybridX11TestReceiver"
    ) -AllowFailure | Out-Null
    Start-Sleep -Seconds $PostShutdownWaitSeconds
}
