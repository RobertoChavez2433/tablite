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
$previousFrameSeq = $null
$seenFrameSeqs = [System.Collections.Generic.HashSet[long]]::new()
$csvIntegrityIssues = New-Object System.Collections.Generic.List[string]
$missingCsvEvents = 0

foreach ($row in $rows) {
    $frameSeq = [long]$row.frame
    $postNs = [long]$row.t_post_swap_ns
    $swap = [double]$row.swap_us
    $gap = [double]$row.gap_us
    if (-not $seenFrameSeqs.Add($frameSeq)) {
        $csvIntegrityIssues.Add("duplicate_frame_seq:$frameSeq")
    }
    if ($null -ne $previousFrameSeq) {
        if ($frameSeq -le $previousFrameSeq) {
            $csvIntegrityIssues.Add("non_increasing_frame_seq:$previousFrameSeq->$frameSeq")
        } elseif ($frameSeq -gt ($previousFrameSeq + 1)) {
            $missingCsvEvents += [int]($frameSeq - $previousFrameSeq - 1)
        }
    }
    if ($null -ne $previousPostNs) {
        if ($postNs -gt $previousPostNs) {
            $producerIntervalsNs.Add([double]($postNs - $previousPostNs))
        } else {
            $csvIntegrityIssues.Add("non_increasing_producer_timestamp:$previousPostNs->$postNs")
        }
    }
    $previousPostNs = $postNs
    $previousFrameSeq = $frameSeq
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
$producedFpsStatus = "unavailable_no_producer_signal"
$producedFpsSource = $null
if ($rows.Count -ge 2 -and $csvIntegrityIssues.Count -eq 0) {
    $firstNs = [long]$rows[0].t_post_swap_ns
    $lastNs = [long]$rows[-1].t_post_swap_ns
    $firstSeq = [long]$rows[0].frame
    $lastSeq = [long]$rows[-1].frame
    if ($lastNs -gt $firstNs) {
        $producedFps = (($lastSeq - $firstSeq) * 1000000000.0) / ($lastNs - $firstNs)
        $producedFpsStatus = if ($missingCsvEvents -eq 0) {
            "valid_sequence_derived_csv"
        } else {
            "valid_sequence_derived_csv_with_missing_detail_events"
        }
        $producedFpsSource = $rlawtCsv.FullName
    }
} elseif ($csvIntegrityIssues.Count -gt 0) {
    $producedFpsStatus = "invalid_csv_sequence_or_clock_order"
}

$missed = @($producerIntervalsNs | Where-Object { $_ -gt ($deadlineNs + 100000.0) }).Count
$stalls = @($producerIntervalsNs | Where-Object { $_ -gt 50000000.0 }).Count
$missRate = if ($producerIntervalsNs.Count -gt 0) { 100.0 * $missed / $producerIntervalsNs.Count } else { $null }

$captureId = $null
$launcherContext = Get-ChildItem -LiteralPath $capturePath -Recurse -File -Filter "launcher-context.txt" |
    Select-Object -First 1
if ($null -ne $launcherContext) {
    $captureIdLine = Get-Content -LiteralPath $launcherContext.FullName |
        Where-Object { $_ -match "^capture_id=" } |
        Select-Object -First 1
    if ($captureIdLine) { $captureId = $captureIdLine.Substring("capture_id=".Length).Trim() }
}

$xlorieConsumerRedrawRateHzValues = New-Object System.Collections.Generic.List[double]
$x11GlxSwapRequestRateHzValues = New-Object System.Collections.Generic.List[double]
$x11DamageRequestRateHzValues = New-Object System.Collections.Generic.List[double]
$x11UploadCallRateHzValues = New-Object System.Collections.Generic.List[double]
$x11UploadBytesValues = New-Object System.Collections.Generic.List[long]
$x11UploadCopyNsValues = New-Object System.Collections.Generic.List[long]
$x11UploadCopyMaxNsValues = New-Object System.Collections.Generic.List[long]
$xlorieSubmittedRateHzValues = New-Object System.Collections.Generic.List[double]
$displayPresentEventRateHzValues = New-Object System.Collections.Generic.List[double]
$uniqueSourceSubmitRateHzValues = New-Object System.Collections.Generic.List[double]
$uniqueSourcePresentRateHzValues = New-Object System.Collections.Generic.List[double]
$presentationCapabilities = New-Object System.Collections.Generic.List[string]
$presentationSubmittedCounts = New-Object System.Collections.Generic.List[long]
$presentationPresentedCounts = New-Object System.Collections.Generic.List[long]
$presentationUniqueSubmitCounts = New-Object System.Collections.Generic.List[long]
$presentationDuplicateSubmitCounts = New-Object System.Collections.Generic.List[long]
$presentationMissingSubmitCounts = New-Object System.Collections.Generic.List[long]
$presentationUniquePresentCounts = New-Object System.Collections.Generic.List[long]
$presentationDuplicatePresentCounts = New-Object System.Collections.Generic.List[long]
$presentationMissingPresentCounts = New-Object System.Collections.Generic.List[long]
$presentationInvalidCounts = New-Object System.Collections.Generic.List[long]
$presentationQueryErrorCounts = New-Object System.Collections.Generic.List[long]
$presentationLostCounts = New-Object System.Collections.Generic.List[long]
$sourceOrdinalGapCounts = New-Object System.Collections.Generic.List[long]
$sourceOrdinalDuplicateCounts = New-Object System.Collections.Generic.List[long]
$sourceOrdinalMissingCounts = New-Object System.Collections.Generic.List[long]
$contentRequestCounts = New-Object System.Collections.Generic.List[long]
$cursorMoveRequestCounts = New-Object System.Collections.Generic.List[long]
$cursorUpdateRequestCounts = New-Object System.Collections.Generic.List[long]
$contentOnlyRedrawCounts = New-Object System.Collections.Generic.List[long]
$cursorOnlyRedrawCounts = New-Object System.Collections.Generic.List[long]
$mixedReasonRedrawCounts = New-Object System.Collections.Generic.List[long]
$unclassifiedRedrawCounts = New-Object System.Collections.Generic.List[long]
$acceptedToDamageMsValues = New-Object System.Collections.Generic.List[double]
$damageToRedrawMsValues = New-Object System.Collections.Generic.List[double]
$acceptedToDamageStatuses = New-Object System.Collections.Generic.List[string]
$producerWindowsByKey = @{}
$producerWindowInvalidCount = 0
$producerWindowDuplicateCount = 0
$boundedProducerLog = Get-ChildItem -LiteralPath $capturePath -Recurse -File -Filter "runelite-native-capture-window.log" |
    Select-Object -First 1
$telemetryLogFiles = @(
    Get-ChildItem -LiteralPath $capturePath -Recurse -File -Include "*.log","*.txt"
)
$hasMirroredServerSnapshots = @(
    $telemetryLogFiles |
        Select-String -Pattern "XlorieServerSnapshotV1:" -List -ErrorAction SilentlyContinue
).Count -gt 0
$telemetryLogFiles | ForEach-Object {
    $currentLog = $_
    $isProducerWindowSource = ($null -eq $boundedProducerLog) -or
        ($currentLog.FullName -eq $boundedProducerLog.FullName)
    Get-Content -LiteralPath $currentLog.FullName -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_ -match "XloriePerf:.*xlorie_consumer_redraw_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
            $xlorieConsumerRedrawRateHzValues.Add([double]::Parse(
                $Matches[1],
                [System.Globalization.CultureInfo]::InvariantCulture
            ))
        }
        $isSelectedServerSnapshot =
            ($hasMirroredServerSnapshots -and $_ -match "XlorieServerSnapshotV1:") -or
            (-not $hasMirroredServerSnapshots -and $_ -match "XlorieStageRatesV1:")
        if ($isSelectedServerSnapshot) {
            if ($_ -match "x11_glx_swap_request_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $x11GlxSwapRequestRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            if ($_ -match "x11_damage_request_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $x11DamageRequestRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
        }
        if (
            ($hasMirroredServerSnapshots -and $_ -match "XlorieServerSnapshotV1:") -or
            (-not $hasMirroredServerSnapshots -and $_ -match "XlorieCopyStatsV1:")
        ) {
            if ($_ -match "upload_call_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $x11UploadCallRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            if ($_ -match "upload_bytes=([0-9]+)") {
                $x11UploadBytesValues.Add([long]$Matches[1])
            }
            if ($_ -match "upload_copy_ns=([0-9]+)") {
                $x11UploadCopyNsValues.Add([long]$Matches[1])
            }
            if ($_ -match "upload_copy_max_ns=([0-9]+)") {
                $x11UploadCopyMaxNsValues.Add([long]$Matches[1])
            }
        }
        if ($_ -match "XloriePresentationV1:") {
            if ($_ -match "capability=([a-z_]+)") {
                $presentationCapabilities.Add($Matches[1])
            }
            if ($_ -match "xlorie_submitted_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $xlorieSubmittedRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            if ($_ -match "display_present_event_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $displayPresentEventRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            if ($_ -match "unique_source_submit_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $uniqueSourceSubmitRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            if ($_ -match "unique_source_present_rate_hz=([0-9]+(?:\.[0-9]+)?)") {
                $uniqueSourcePresentRateHzValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            foreach ($field in @(
                @{ Name = "submitted_count"; Target = $presentationSubmittedCounts },
                @{ Name = "presented_count"; Target = $presentationPresentedCounts },
                @{ Name = "unique_source_submit_count"; Target = $presentationUniqueSubmitCounts },
                @{ Name = "duplicate_source_submit_count"; Target = $presentationDuplicateSubmitCounts },
                @{ Name = "missing_source_submit_count"; Target = $presentationMissingSubmitCounts },
                @{ Name = "unique_source_present_count"; Target = $presentationUniquePresentCounts },
                @{ Name = "duplicate_source_present_count"; Target = $presentationDuplicatePresentCounts },
                @{ Name = "missing_source_present_count"; Target = $presentationMissingPresentCounts },
                @{ Name = "invalid_timestamp_count"; Target = $presentationInvalidCounts },
                @{ Name = "query_error_count"; Target = $presentationQueryErrorCounts },
                @{ Name = "telemetry_lost_count"; Target = $presentationLostCounts }
            )) {
                if ($_ -match "$($field.Name)=([0-9]+)") {
                    $field.Target.Add([long]$Matches[1])
                }
            }
        }
        if ($_ -match "XlorieHandoffV1:") {
            foreach ($field in @(
                @{ Name = "source_ordinal_gap_count"; Target = $sourceOrdinalGapCounts },
                @{ Name = "source_ordinal_duplicate_count"; Target = $sourceOrdinalDuplicateCounts },
                @{ Name = "source_ordinal_missing_count"; Target = $sourceOrdinalMissingCounts },
                @{ Name = "content_request_count"; Target = $contentRequestCounts },
                @{ Name = "cursor_move_request_count"; Target = $cursorMoveRequestCounts },
                @{ Name = "cursor_update_request_count"; Target = $cursorUpdateRequestCounts },
                @{ Name = "content_only_redraw_count"; Target = $contentOnlyRedrawCounts },
                @{ Name = "cursor_only_redraw_count"; Target = $cursorOnlyRedrawCounts },
                @{ Name = "mixed_reason_redraw_count"; Target = $mixedReasonRedrawCounts },
                @{ Name = "unclassified_redraw_count"; Target = $unclassifiedRedrawCounts }
            )) {
                if ($_ -match "$($field.Name)=([0-9]+)") {
                    $field.Target.Add([long]$Matches[1])
                }
            }
            if ($_ -match "accepted_to_damage_status=([a-z_]+)") {
                $acceptedToDamageStatuses.Add($Matches[1])
            }
            $acceptedSampleCount = 0
            if ($_ -match "accepted_to_damage_sample_count=([0-9]+)") {
                $acceptedSampleCount = [long]$Matches[1]
            }
            if (
                $acceptedSampleCount -gt 0 -and
                $_ -match "avg_accepted_to_damage_ms=([0-9]+(?:\.[0-9]+)?)"
            ) {
                $acceptedToDamageMsValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
            $damageSampleCount = 0
            if ($_ -match "damage_to_redraw_sample_count=([0-9]+)") {
                $damageSampleCount = [long]$Matches[1]
            }
            if (
                $damageSampleCount -gt 0 -and
                $_ -match "avg_damage_to_redraw_ms=([0-9]+(?:\.[0-9]+)?)"
            ) {
                $damageToRedrawMsValues.Add(
                    [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture))
            }
        }
        if ($isProducerWindowSource -and $_ -match "RLT_FRAME_RATE_V1\s+run_id=([^\s]+)\s+stage=producer\s+outcome=produced\s+first_seq=([0-9]+)\s+last_seq=([0-9]+)\s+interval_count=([0-9]+)\s+elapsed_ns=([0-9]+)\s+end_monotonic_ns=([0-9]+)\s+produced_fps=([0-9]+(?:\.[0-9]+)?)") {
            $windowRunId = $Matches[1]
            $windowFirstSeq = [long]$Matches[2]
            $windowLastSeq = [long]$Matches[3]
            $windowIntervalCount = [long]$Matches[4]
            $windowElapsedNs = [long]$Matches[5]
            $windowEndNs = [long]$Matches[6]
            $windowReportedRate = [double]::Parse($Matches[7], [System.Globalization.CultureInfo]::InvariantCulture)
            $windowCalculatedRate = if ($windowElapsedNs -gt 0) {
                $windowIntervalCount * 1000000000.0 / $windowElapsedNs
            } else { 0.0 }
            $windowValid = ($windowRunId -ne "unknown") -and
                (($null -eq $captureId) -or ($windowRunId -eq $captureId)) -and
                ($windowFirstSeq -ge 1) -and
                ($windowLastSeq -gt $windowFirstSeq) -and
                (($windowLastSeq - $windowFirstSeq) -eq $windowIntervalCount) -and
                ($windowElapsedNs -gt 0) -and
                ($windowEndNs -gt 0) -and
                ([Math]::Abs($windowCalculatedRate - $windowReportedRate) -le
                    [Math]::Max(0.01, $windowCalculatedRate * 0.001))
            if (-not $windowValid) {
                $producerWindowInvalidCount++
            } else {
                $windowKey = "$windowRunId/$windowFirstSeq/$windowLastSeq/$windowEndNs"
                if ($producerWindowsByKey.ContainsKey($windowKey)) {
                    $producerWindowDuplicateCount++
                } else {
                    $producerWindowsByKey[$windowKey] = [pscustomobject]@{
                        RunId = $windowRunId
                        FirstSeq = $windowFirstSeq
                        LastSeq = $windowLastSeq
                        IntervalCount = $windowIntervalCount
                        ElapsedNs = $windowElapsedNs
                        EndNs = $windowEndNs
                    }
                }
            }
        }
    }
}

$producerWindows = @($producerWindowsByKey.Values | Sort-Object EndNs)
if ($null -eq $producedFps -and $rows.Count -eq 0 -and $producerWindows.Count -gt 0 -and $producerWindowInvalidCount -eq 0) {
    $windowIntervals = ($producerWindows | Measure-Object -Property IntervalCount -Sum).Sum
    $windowElapsed = ($producerWindows | Measure-Object -Property ElapsedNs -Sum).Sum
    if ($windowElapsed -gt 0) {
        $producedFps = $windowIntervals * 1000000000.0 / $windowElapsed
        $producedFpsStatus = "valid_sequence_derived_summary_windows"
        $producedFpsSource = "RLT_FRAME_RATE_V1"
    }
} elseif ($null -eq $producedFps -and $producerWindowInvalidCount -gt 0) {
    $producedFpsStatus = "invalid_structured_producer_windows"
}

$summary = [ordered]@{
    schema_version = 1
    target_hz = $TargetHz
    target_deadline_ms = $deadlineNs / 1000000.0
    source_rows = $rows.Count
    producer_summary_windows = $producerWindows.Count
    producer_window_scope = if ($null -ne $boundedProducerLog) { "capture_interval" } else { "whole_session_fallback" }
    rates = [ordered]@{
        produced_fps = $producedFps
        accepted_fps = $null
        submitted_fps = $null
        presented_fps = $null
        display_vsync_hz = $null
        xlorie_consumer_redraw_rate_hz = if ($xlorieConsumerRedrawRateHzValues.Count -gt 0) {
            ($xlorieConsumerRedrawRateHzValues | Measure-Object -Average).Average
        } else { $null }
        x11_glx_swap_request_rate_hz = if ($x11GlxSwapRequestRateHzValues.Count -gt 0) {
            ($x11GlxSwapRequestRateHzValues | Measure-Object -Average).Average
        } else { $null }
        x11_damage_request_rate_hz = if ($x11DamageRequestRateHzValues.Count -gt 0) {
            ($x11DamageRequestRateHzValues | Measure-Object -Average).Average
        } else { $null }
        xlorie_submitted_rate_hz = if ($xlorieSubmittedRateHzValues.Count -gt 0) {
            ($xlorieSubmittedRateHzValues | Measure-Object -Average).Average
        } else { $null }
        display_present_event_rate_hz = if ($displayPresentEventRateHzValues.Count -gt 0) {
            ($displayPresentEventRateHzValues | Measure-Object -Average).Average
        } else { $null }
        unique_source_submit_rate_hz = if ($uniqueSourceSubmitRateHzValues.Count -gt 0) {
            ($uniqueSourceSubmitRateHzValues | Measure-Object -Average).Average
        } else { $null }
        unique_source_present_rate_hz = if ($uniqueSourcePresentRateHzValues.Count -gt 0) {
            ($uniqueSourcePresentRateHzValues | Measure-Object -Average).Average
        } else { $null }
        x11_upload_call_rate_hz = if ($x11UploadCallRateHzValues.Count -gt 0) {
            ($x11UploadCallRateHzValues | Measure-Object -Average).Average
        } else { $null }
    }
    rate_validity = [ordered]@{
        produced_fps = [ordered]@{
            status = $producedFpsStatus
            source = $producedFpsSource
            missing_detail_events = $missingCsvEvents
            invalid_csv_events = $csvIntegrityIssues.Count
            invalid_summary_windows = $producerWindowInvalidCount
            duplicate_summary_windows_ignored = $producerWindowDuplicateCount
        }
        accepted_fps = [ordered]@{ status = "unavailable_no_correlated_accept_events" }
        submitted_fps = [ordered]@{ status = "unavailable_no_correlated_submit_events" }
        presented_fps = [ordered]@{ status = "unavailable_no_correlated_presentation_events" }
        display_vsync_hz = [ordered]@{ status = "unavailable_not_in_frame_event_source" }
        xlorie_consumer_redraw_rate_hz = [ordered]@{
            status = if ($xlorieConsumerRedrawRateHzValues.Count -gt 0) { "valid_diagnostic_rate_only" } else { "unavailable" }
        }
        x11_glx_swap_request_rate_hz = [ordered]@{
            status = if ($x11GlxSwapRequestRateHzValues.Count -gt 0) { "valid_diagnostic_rate_only" } else { "unavailable" }
        }
        x11_damage_request_rate_hz = [ordered]@{
            status = if ($x11DamageRequestRateHzValues.Count -gt 0) { "valid_diagnostic_rate_only" } else { "unavailable" }
        }
        xlorie_submitted_rate_hz = [ordered]@{
            status = if ($xlorieSubmittedRateHzValues.Count -gt 0) { "valid_diagnostic_rate_only" } else { "unavailable" }
        }
        display_present_event_rate_hz = [ordered]@{
            status = if ($displayPresentEventRateHzValues.Count -gt 0) {
                "valid_display_event_rate_unmatched_to_producer"
            } else { "unavailable" }
        }
        unique_source_submit_rate_hz = [ordered]@{
            status = if ($uniqueSourceSubmitRateHzValues.Count -gt 0) {
                "valid_x11_source_ordinal_rate_not_producer_correlated"
            } else { "unavailable" }
        }
        unique_source_present_rate_hz = [ordered]@{
            status = if ($uniqueSourcePresentRateHzValues.Count -gt 0) {
                "valid_x11_source_ordinal_rate_not_producer_correlated"
            } else { "unavailable" }
        }
    }
    x11_copy = [ordered]@{
        sample_windows = $x11UploadBytesValues.Count
        upload_bytes = if ($x11UploadBytesValues.Count -gt 0) {
            ($x11UploadBytesValues | Measure-Object -Sum).Sum
        } else { 0 }
        upload_copy_ms = if ($x11UploadCopyNsValues.Count -gt 0) {
            (($x11UploadCopyNsValues | Measure-Object -Sum).Sum) / 1000000.0
        } else { 0.0 }
        upload_copy_max_ms = if ($x11UploadCopyMaxNsValues.Count -gt 0) {
            (($x11UploadCopyMaxNsValues | Measure-Object -Maximum).Maximum) / 1000000.0
        } else { 0.0 }
    }
    x11_handoff = [ordered]@{
        source_ordinal_gaps = if ($sourceOrdinalGapCounts.Count -gt 0) {
            ($sourceOrdinalGapCounts | Measure-Object -Sum).Sum
        } else { 0 }
        source_ordinal_duplicates = if ($sourceOrdinalDuplicateCounts.Count -gt 0) {
            ($sourceOrdinalDuplicateCounts | Measure-Object -Sum).Sum
        } else { 0 }
        source_ordinal_missing = if ($sourceOrdinalMissingCounts.Count -gt 0) {
            ($sourceOrdinalMissingCounts | Measure-Object -Sum).Sum
        } else { 0 }
        content_requests = if ($contentRequestCounts.Count -gt 0) {
            ($contentRequestCounts | Measure-Object -Sum).Sum
        } else { 0 }
        cursor_move_requests = if ($cursorMoveRequestCounts.Count -gt 0) {
            ($cursorMoveRequestCounts | Measure-Object -Sum).Sum
        } else { 0 }
        cursor_update_requests = if ($cursorUpdateRequestCounts.Count -gt 0) {
            ($cursorUpdateRequestCounts | Measure-Object -Sum).Sum
        } else { 0 }
        content_only_redraws = if ($contentOnlyRedrawCounts.Count -gt 0) {
            ($contentOnlyRedrawCounts | Measure-Object -Sum).Sum
        } else { 0 }
        cursor_only_redraws = if ($cursorOnlyRedrawCounts.Count -gt 0) {
            ($cursorOnlyRedrawCounts | Measure-Object -Sum).Sum
        } else { 0 }
        mixed_reason_redraws = if ($mixedReasonRedrawCounts.Count -gt 0) {
            ($mixedReasonRedrawCounts | Measure-Object -Sum).Sum
        } else { 0 }
        unclassified_redraws = if ($unclassifiedRedrawCounts.Count -gt 0) {
            ($unclassifiedRedrawCounts | Measure-Object -Sum).Sum
        } else { 0 }
        accepted_to_damage_status = if ($acceptedToDamageStatuses.Count -gt 0) {
            $acceptedToDamageStatuses[-1]
        } else { "unavailable" }
        accepted_to_damage_ms = Get-Stats @($acceptedToDamageMsValues)
        damage_to_redraw_ms = Get-Stats @($damageToRedrawMsValues)
    }
    presentation = [ordered]@{
        capability = if ($presentationCapabilities.Count -gt 0) {
            $presentationCapabilities[-1]
        } else { "unavailable" }
        submitted_events = if ($presentationSubmittedCounts.Count -gt 0) {
            ($presentationSubmittedCounts | Measure-Object -Sum).Sum
        } else { 0 }
        display_present_events = if ($presentationPresentedCounts.Count -gt 0) {
            ($presentationPresentedCounts | Measure-Object -Sum).Sum
        } else { 0 }
        unique_source_submissions = if ($presentationUniqueSubmitCounts.Count -gt 0) {
            ($presentationUniqueSubmitCounts | Measure-Object -Sum).Sum
        } else { 0 }
        duplicate_source_submissions = if ($presentationDuplicateSubmitCounts.Count -gt 0) {
            ($presentationDuplicateSubmitCounts | Measure-Object -Sum).Sum
        } else { 0 }
        missing_source_submissions = if ($presentationMissingSubmitCounts.Count -gt 0) {
            ($presentationMissingSubmitCounts | Measure-Object -Sum).Sum
        } else { 0 }
        unique_source_presentations = if ($presentationUniquePresentCounts.Count -gt 0) {
            ($presentationUniquePresentCounts | Measure-Object -Sum).Sum
        } else { 0 }
        duplicate_source_presentations = if ($presentationDuplicatePresentCounts.Count -gt 0) {
            ($presentationDuplicatePresentCounts | Measure-Object -Sum).Sum
        } else { 0 }
        missing_source_presentations = if ($presentationMissingPresentCounts.Count -gt 0) {
            ($presentationMissingPresentCounts | Measure-Object -Sum).Sum
        } else { 0 }
        invalid_timestamps = if ($presentationInvalidCounts.Count -gt 0) {
            ($presentationInvalidCounts | Measure-Object -Sum).Sum
        } else { 0 }
        query_errors = if ($presentationQueryErrorCounts.Count -gt 0) {
            ($presentationQueryErrorCounts | Measure-Object -Sum).Sum
        } else { 0 }
        telemetry_lost = if ($presentationLostCounts.Count -gt 0) {
            ($presentationLostCounts | Measure-Object -Sum).Sum
        } else { 0 }
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
        reason = "Display-present timestamps are source-ordinal classified but not joined to producer frame sequences."
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
        "producer-correlated presentation timestamps",
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
- rlawt source rows: $($rows.Count); structured producer windows: $($producerWindows.Count)
- Produced FPS: $(Format-Value $producedFps) ($producedFpsStatus)
- Accepted FPS: unavailable
- Submitted FPS: unavailable
- Uniquely presented FPS: unavailable
- Xlorie consumer redraw rate: $(Format-Value $(if ($xlorieConsumerRedrawRateHzValues.Count -gt 0) { ($xlorieConsumerRedrawRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — diagnostic only
- X11 GLX swap request rate: $(Format-Value $(if ($x11GlxSwapRequestRateHzValues.Count -gt 0) { ($x11GlxSwapRequestRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — diagnostic until ordinal validation
- X11 damage request rate: $(Format-Value $(if ($x11DamageRequestRateHzValues.Count -gt 0) { ($x11DamageRequestRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz")
- Xlorie EGL submission rate: $(Format-Value $(if ($xlorieSubmittedRateHzValues.Count -gt 0) { ($xlorieSubmittedRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — diagnostic until producer join
- Display-present event rate: $(Format-Value $(if ($displayPresentEventRateHzValues.Count -gt 0) { ($displayPresentEventRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — raw actual EGL timestamps, including repeated source content
- Unique X11-source submission rate: $(Format-Value $(if ($uniqueSourceSubmitRateHzValues.Count -gt 0) { ($uniqueSourceSubmitRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — source-ordinal diagnostic, not producer FPS
- Unique X11-source presentation rate: $(Format-Value $(if ($uniqueSourcePresentRateHzValues.Count -gt 0) { ($uniqueSourcePresentRateHzValues | Measure-Object -Average).Average } else { $null }) " Hz") — source-ordinal diagnostic, not producer FPS
- Duplicate source submissions: $(if ($presentationDuplicateSubmitCounts.Count -gt 0) { ($presentationDuplicateSubmitCounts | Measure-Object -Sum).Sum } else { 0 }); duplicate source presentations: $(if ($presentationDuplicatePresentCounts.Count -gt 0) { ($presentationDuplicatePresentCounts | Measure-Object -Sum).Sum } else { 0 })
- Presentation timestamp capability: $(if ($presentationCapabilities.Count -gt 0) { $presentationCapabilities[-1] } else { "unavailable" })
- Producer deadline misses: $missed ($(Format-Value $missRate "%"))
- Producer stalls over 50 ms: $stalls
- Correlation confidence: 0% — exact attribution is invalid until actual presentation events are joined.
- Bottleneck: unknown — this report does not infer CPU or IPC causality from swap/gap ratios.

The detailed system trace is system.perfetto-trace. Open it in Perfetto and
correlate scheduler, frequency, SurfaceFlinger, and frame-lifecycle tracks with
frames.csv. Any unsupported source remains unavailable rather than being
estimated as presentation ground truth.
"@
$report | Set-Content -LiteralPath (Join-Path $capturePath "report.md") -Encoding utf8

Write-Host "Analyzed $($rows.Count) frame rows -> $summaryPath"
