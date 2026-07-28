param(
    [Parameter(Mandatory = $true)]
    [string]$CaptureDirectory,
    [int]$TargetHz = 120
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))
    return [double]$sorted[$index]
}

function Get-Stats {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return $null }
    return [ordered]@{
        count = $Values.Count
        p50 = Get-Percentile $Values 50
        p90 = Get-Percentile $Values 90
        p95 = Get-Percentile $Values 95
        p99 = Get-Percentile $Values 99
        max = ($Values | Measure-Object -Maximum).Maximum
    }
}

$capturePath = (Resolve-Path -LiteralPath $CaptureDirectory).Path
$deadlineNs = 1000000000.0 / $TargetHz
$rlawtCsv = Get-ChildItem -LiteralPath $capturePath -Recurse -File |
    Where-Object { $_.Name -match "^rlawt.*frames?\.csv$|^rlawt-perframe\.csv$" } |
    Select-Object -First 1

$rows = @()
if ($null -ne $rlawtCsv) {
    $csvLines = @(Get-Content -LiteralPath $rlawtCsv.FullName | Where-Object {
        $_ -and -not $_.StartsWith("#")
    })
    if ($csvLines.Count -ge 2) {
        $rows = @($csvLines | ConvertFrom-Csv)
    }
}

$normalized = New-Object System.Collections.Generic.List[object]
$presentIntervalsNs = @()
$producerIntervalsNs = New-Object System.Collections.Generic.List[double]
$swapUs = New-Object System.Collections.Generic.List[double]
$generationUs = New-Object System.Collections.Generic.List[double]
$previousPostNs = $null

foreach ($row in $rows) {
    $frameSeq = [long]$row.frame
    $postNs = [long]$row.t_post_swap_ns
    $swap = [double]$row.swap_us
    $gap = [double]$row.gap_us
    if ($null -ne $previousPostNs -and $postNs -gt $previousPostNs) {
        $producerIntervalsNs.Add([double]($postNs - $previousPostNs))
    }
    $previousPostNs = $postNs
    $swapUs.Add($swap)
    $generationUs.Add($gap)
    $normalized.Add([pscustomobject][ordered]@{
        frame_seq = $frameSeq
        producer_post_monotonic_ns = $postNs
        generation_us = $gap
        swap_or_submit_us = $swap
        submitted_buffer_index = if ($row.PSObject.Properties.Name -contains "submitted_index") { $row.submitted_index } else { "" }
        gpu_query_frame_seq = if ($row.PSObject.Properties.Name -contains "gpu_frame") { $row.gpu_frame } else { "" }
        gpu_query_us = if ($row.PSObject.Properties.Name -contains "gpu_us") { $row.gpu_us } else { "" }
        accepted_monotonic_ns = ""
        submitted_monotonic_ns = ""
        latch_monotonic_ns = ""
        presented_monotonic_ns = ""
        release_monotonic_ns = ""
        outcome = "presentation_unmatched"
    })
}

$framesPath = Join-Path $capturePath "frames.csv"
if ($normalized.Count -gt 0) {
    $normalized | Export-Csv -LiteralPath $framesPath -NoTypeInformation
} else {
    @(
        "frame_seq,producer_post_monotonic_ns,generation_us,swap_or_submit_us,"
        "submitted_buffer_index,gpu_query_frame_seq,gpu_query_us,"
        "accepted_monotonic_ns,submitted_monotonic_ns,latch_monotonic_ns,"
        "presented_monotonic_ns,release_monotonic_ns,outcome"
    ) -join "" | Set-Content -LiteralPath $framesPath -Encoding ascii
}

$producedFps = $null
if ($rows.Count -ge 2) {
    $firstNs = [long]$rows[0].t_post_swap_ns
    $lastNs = [long]$rows[-1].t_post_swap_ns
    if ($lastNs -gt $firstNs) {
        $producedFps = (($rows.Count - 1) * 1000000000.0) / ($lastNs - $firstNs)
    }
}

$missed = @($producerIntervalsNs | Where-Object { $_ -gt ($deadlineNs + 100000.0) }).Count
$stalls = @($producerIntervalsNs | Where-Object { $_ -gt 50000000.0 }).Count
$missRate = if ($producerIntervalsNs.Count -gt 0) { 100.0 * $missed / $producerIntervalsNs.Count } else { $null }

$xlorieConsumerRedrawFpsValues = New-Object System.Collections.Generic.List[double]
Get-ChildItem -LiteralPath $capturePath -Recurse -File -Include "*.log","*.txt" | ForEach-Object {
    Get-Content -LiteralPath $_.FullName -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_ -match "XloriePerf:.*consumer_redraw_fps=([0-9]+(?:\.[0-9]+)?)") {
            $xlorieConsumerRedrawFpsValues.Add([double]::Parse(
                $Matches[1],
                [System.Globalization.CultureInfo]::InvariantCulture
            ))
        }
    }
}

$summary = [ordered]@{
    schema_version = 1
    target_hz = $TargetHz
    target_deadline_ms = $deadlineNs / 1000000.0
    source_rows = $rows.Count
    rates = [ordered]@{
        produced_fps = $producedFps
        accepted_fps = $null
        submitted_fps = $null
        presented_fps = $null
        display_vsync_hz = $null
        xlorie_consumer_redraw_fps = if ($xlorieConsumerRedrawFpsValues.Count -gt 0) {
            ($xlorieConsumerRedrawFpsValues | Measure-Object -Average).Average
        } else { $null }
    }
    producer_interval_ms = Get-Stats @($producerIntervalsNs | ForEach-Object { $_ / 1000000.0 })
    frame_generation_ms = Get-Stats @($generationUs | ForEach-Object { $_ / 1000.0 })
    swap_or_submit_ms = Get-Stats @($swapUs | ForEach-Object { $_ / 1000.0 })
    missed_deadlines = $missed
    missed_deadline_percent = $missRate
    stalls_over_50ms = $stalls
    correlation = [ordered]@{
        matched_frames = 0
        producer_frames = $rows.Count
        confidence_percent = 0.0
        exact_attribution_valid = $false
        reason = "No actual presentation timestamps were available in the pulled frame events."
    }
    bottleneck = [ordered]@{
        classification = "unknown"
        confidence = "insufficient"
        reason = "Swap/generation proportions alone are non-causal; inspect Perfetto and presentation events."
    }
    unsupported_or_missing = @(
        "accepted_fps",
        "submitted_fps",
        "presented_fps",
        "actual presentation timestamps",
        "Mali counters unless an AGI artifact is added"
    )
}

$summaryPath = Join-Path $capturePath "summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8

function Format-Value {
    param($Value, [string]$Suffix = "")
    if ($null -eq $Value) { return "unavailable" }
    if ($Value -is [double] -or $Value -is [float] -or $Value -is [decimal]) {
        return ("{0:N3}{1}" -f $Value, $Suffix)
    }
    return "$Value$Suffix"
}

$report = @"
# Frame Capture Report

- Target: $TargetHz Hz ($(Format-Value ($deadlineNs / 1000000.0) " ms"))
- rlawt source rows: $($rows.Count)
- Produced FPS: $(Format-Value $producedFps)
- Accepted FPS: unavailable
- Submitted FPS: unavailable
- Uniquely presented FPS: unavailable
- Producer deadline misses: $missed ($(Format-Value $missRate "%"))
- Producer stalls over 50 ms: $stalls
- Correlation confidence: 0% — exact attribution is invalid until actual presentation events are joined.
- Bottleneck: unknown — this report does not infer CPU or IPC causality from swap/gap ratios.

The detailed system trace is `system.perfetto-trace`. Open it in Perfetto and
correlate scheduler, frequency, SurfaceFlinger, and frame-lifecycle tracks with
`frames.csv`. Any unsupported source remains unavailable rather than being
estimated as presentation ground truth.
"@
$report | Set-Content -LiteralPath (Join-Path $capturePath "report.md") -Encoding utf8

Write-Host "Analyzed $($rows.Count) frame rows -> $summaryPath"
