package com.runelitetablet.perf

import com.runelitetablet.domain.logging.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/**
 * Aggregates metrics from all Phase 0 components and emits periodic PERF_SUMMARY
 * log lines every 10 seconds. Collects CPU affinity, ptrace overhead, and renderer stats.
 */
class PerfDashboard(
    private val logger: Logger,
    private val affinityMonitor: CpuAffinityMonitor,
    private val ptraceEstimator: PtraceOverheadEstimator,
    private val intervalSeconds: Long = 10,
    private val producerRateTracker: ProducerRateTracker = ProducerRateTracker(),
    private val monotonicNowNs: () -> Long = System::nanoTime
) {

    data class Summary(
        val affinities: List<CpuAffinityMonitor.ProcessAffinity>,
        val overheads: List<PtraceOverheadEstimator.OverheadSample>,
        val producedFps: Double?,
        val producedFpsStatus: String,
        val producerLastSeq: Long?,
        val submittedFps: Double?,
        val presentedFps: Double?,
        val presentationStatus: String,
        val xlorieConsumerRedrawRateHz: Double?,
        val x11GlxSwapRequestRateHz: Double?,
        val x11DamageRequestRateHz: Double?,
        val x11ProducerMarkerRateHz: Double?,
        val producerMarkerErrorCount: Long?,
        val xlorieSubmittedRateHz: Double?,
        val displayPresentEventRateHz: Double?,
        val uniqueSourceSubmitRateHz: Double?,
        val uniqueSourcePresentRateHz: Double?,
        val duplicateSourceSubmitCount: Long?,
        val duplicateSourcePresentCount: Long?,
        val presentationCapability: String,
        val x11UploadCallRateHz: Double?,
        val x11UploadBytes: Long?,
        val x11UploadCopyMs: Double?,
        val waitChoreoPct: Double,
        val waitContentPct: Double,
        val waitStatePct: Double
    )

    @Volatile private var lastConsumerRedrawRateHz: Double? = null
    @Volatile private var lastX11GlxSwapRequestRateHz: Double? = null
    @Volatile private var lastX11DamageRequestRateHz: Double? = null
    @Volatile private var lastX11ProducerMarkerRateHz: Double? = null
    @Volatile private var lastProducerMarkerErrorCount: Long? = null
    @Volatile private var lastXlorieSubmittedRateHz: Double? = null
    @Volatile private var lastDisplayPresentEventRateHz: Double? = null
    @Volatile private var lastUniqueSourceSubmitRateHz: Double? = null
    @Volatile private var lastUniqueSourcePresentRateHz: Double? = null
    @Volatile private var lastDuplicateSourceSubmitCount: Long? = null
    @Volatile private var lastDuplicateSourcePresentCount: Long? = null
    @Volatile private var lastPresentationCapability: String = "unavailable"
    @Volatile private var lastPresentationSourceIdentity: String = "unavailable"
    @Volatile private var lastPresentationRunId: String = "unknown"
    @Volatile private var lastX11UploadCallRateHz: Double? = null
    @Volatile private var lastX11UploadBytes: Long? = null
    @Volatile private var lastX11UploadCopyMs: Double? = null
    @Volatile private var lastWaitChoreoPct: Double = 0.0
    @Volatile private var lastWaitContentPct: Double = 0.0
    @Volatile private var lastWaitStatePct: Double = 0.0
    @Volatile private var trackedPids: List<Int> = emptyList()

    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "perf-dashboard").apply { isDaemon = true }
    }
    private var scheduledTask: ScheduledFuture<*>? = null

    fun updateConsumerStats(consumerRedrawRateHz: Double, choreoPct: Double, contentPct: Double, statePct: Double) {
        lastConsumerRedrawRateHz = consumerRedrawRateHz
        lastWaitChoreoPct = choreoPct
        lastWaitContentPct = contentPct
        lastWaitStatePct = statePct
    }

    fun updateX11StageRates(stats: X11StageRates) {
        lastX11GlxSwapRequestRateHz = stats.glxSwapRequestRateHz
        lastX11DamageRequestRateHz = stats.damageRequestRateHz
        lastX11ProducerMarkerRateHz = stats.producerMarkerRateHz
        lastProducerMarkerErrorCount = stats.producerMarkerErrorCount
    }

    fun updatePresentationStats(stats: PresentationStats) {
        lastXlorieSubmittedRateHz = stats.submittedRateHz
        lastDisplayPresentEventRateHz = stats.displayPresentEventRateHz
        lastUniqueSourceSubmitRateHz = stats.uniqueSourceSubmitRateHz
        lastUniqueSourcePresentRateHz = stats.uniqueSourcePresentRateHz
        lastDuplicateSourceSubmitCount = stats.duplicateSourceSubmitCount
        lastDuplicateSourcePresentCount = stats.duplicateSourcePresentCount
        lastPresentationCapability = stats.capability
        lastPresentationSourceIdentity = stats.sourceIdentity
        lastPresentationRunId = stats.runId
    }

    fun updateCopyStats(stats: CopyStats) {
        lastX11UploadCallRateHz = stats.uploadCallRateHz
        lastX11UploadBytes = stats.uploadBytes
        lastX11UploadCopyMs = stats.uploadCopyNs / 1_000_000.0
    }

    fun updateProducerStats(sample: ProducerRateTracker.Sample): ProducerRateTracker.UpdateOutcome =
        producerRateTracker.update(sample)

    fun resetFrameMetrics() {
        producerRateTracker.reset()
        lastConsumerRedrawRateHz = null
        lastX11GlxSwapRequestRateHz = null
        lastX11DamageRequestRateHz = null
        lastX11ProducerMarkerRateHz = null
        lastProducerMarkerErrorCount = null
        lastXlorieSubmittedRateHz = null
        lastDisplayPresentEventRateHz = null
        lastUniqueSourceSubmitRateHz = null
        lastUniqueSourcePresentRateHz = null
        lastDuplicateSourceSubmitCount = null
        lastDuplicateSourcePresentCount = null
        lastPresentationCapability = "unavailable"
        lastPresentationSourceIdentity = "unavailable"
        lastPresentationRunId = "unknown"
        lastX11UploadCallRateHz = null
        lastX11UploadBytes = null
        lastX11UploadCopyMs = null
        lastWaitChoreoPct = 0.0
        lastWaitContentPct = 0.0
        lastWaitStatePct = 0.0
    }

    fun updatePids(pids: List<Int>) {
        trackedPids = pids
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scheduledTask = scheduler.scheduleAtFixedRate(
            { try { collect(trackedPids) } catch (e: Exception) { logger.e("PERF", "PerfDashboard error", e) } },
            intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        )
        logger.perf("PERF_DASHBOARD: started interval=${intervalSeconds}s")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduledTask?.cancel(false)
        scheduledTask = null
        logger.perf("PERF_DASHBOARD: stopped")
    }

    fun collect(pids: List<Int>): Summary {
        val affinities = affinityMonitor.sample(pids)
        val overheads = ptraceEstimator.sample(pids)
        val producer = producerRateTracker.snapshot(monotonicNowNs())
        val presentationCorrelated =
            producer.runId != null &&
                producer.runId == lastPresentationRunId &&
                lastPresentationCapability == "supported" &&
                lastPresentationSourceIdentity == "producer_seq"
        val submittedFps =
            if (presentationCorrelated) lastUniqueSourceSubmitRateHz else null
        val presentedFps =
            if (presentationCorrelated) lastUniqueSourcePresentRateHz else null
        val presentationStatus =
            if (presentationCorrelated) {
                "valid_producer_sequence_correlated"
            } else {
                "unavailable_no_correlated_presentation_events"
            }

        val summary = Summary(
            affinities,
            overheads,
            producer.producedFps,
            producer.status,
            producer.lastSeq,
            submittedFps,
            presentedFps,
            presentationStatus,
            lastConsumerRedrawRateHz,
            lastX11GlxSwapRequestRateHz,
            lastX11DamageRequestRateHz,
            lastX11ProducerMarkerRateHz,
            lastProducerMarkerErrorCount,
            lastXlorieSubmittedRateHz,
            lastDisplayPresentEventRateHz,
            lastUniqueSourceSubmitRateHz,
            lastUniqueSourcePresentRateHz,
            lastDuplicateSourceSubmitCount,
            lastDuplicateSourcePresentCount,
            lastPresentationCapability,
            lastX11UploadCallRateHz,
            lastX11UploadBytes,
            lastX11UploadCopyMs,
            lastWaitChoreoPct, lastWaitContentPct, lastWaitStatePct
        )

        emitSummary(summary)
        return summary
    }

    private fun emitSummary(summary: Summary) {
        // Use fixed prefixes matching spec: rl_, virgl_, proot_
        val affinityParts = summary.affinities.joinToString(" ") { a ->
            val prefix = fixedPrefix(a.name)
            "${prefix}cpu=${a.core.cpu}(${a.core.type})"
        }
        val overheadParts = summary.overheads.joinToString(" ") { o ->
            val prefix = fixedPrefix(o.name)
            "${prefix}kernel_ratio=%.2f".format(Locale.ROOT, o.kernelRatio)
        }
        val producedValue = summary.producedFps?.let { "%.3f".format(Locale.ROOT, it) } ?: "unavailable"
        val producerLastSeq = summary.producerLastSeq?.toString() ?: "unavailable"
        val consumerRate = summary.xlorieConsumerRedrawRateHz
            ?.let { "%.1f".format(Locale.ROOT, it) }
            ?: "unavailable"
        fun rate(value: Double?): String =
            value?.let { "%.3f".format(Locale.ROOT, it) } ?: "unavailable"
        val uploadBytes = summary.x11UploadBytes?.toString() ?: "unavailable"
        val uploadCopyMs =
            summary.x11UploadCopyMs?.let { "%.3f".format(Locale.ROOT, it) } ?: "unavailable"
        val duplicateSubmitCount = summary.duplicateSourceSubmitCount?.toString() ?: "unavailable"
        val duplicatePresentCount = summary.duplicateSourcePresentCount?.toString() ?: "unavailable"
        val markerErrorCount = summary.producerMarkerErrorCount?.toString() ?: "unavailable"
        logger.perf(
            "PERF_SUMMARY: $affinityParts $overheadParts" +
                " produced_fps=$producedValue" +
                " produced_fps_status=${summary.producedFpsStatus}" +
                " producer_last_seq=$producerLastSeq" +
                " accepted_fps=unavailable" +
                " submitted_fps=${rate(summary.submittedFps)}" +
                " presented_fps=${rate(summary.presentedFps)}" +
                " presentation_status=${summary.presentationStatus}" +
                " xlorie_consumer_redraw_rate_hz=$consumerRate" +
                " x11_glx_swap_request_rate_hz=${rate(summary.x11GlxSwapRequestRateHz)}" +
                " x11_damage_request_rate_hz=${rate(summary.x11DamageRequestRateHz)}" +
                " x11_producer_marker_rate_hz=${rate(summary.x11ProducerMarkerRateHz)}" +
                " producer_marker_error_count=$markerErrorCount" +
                " xlorie_submitted_rate_hz=${rate(summary.xlorieSubmittedRateHz)}" +
                " display_present_event_rate_hz=${rate(summary.displayPresentEventRateHz)}" +
                " unique_source_submit_rate_hz=${rate(summary.uniqueSourceSubmitRateHz)}" +
                " unique_source_present_rate_hz=${rate(summary.uniqueSourcePresentRateHz)}" +
                " duplicate_source_submit_count=$duplicateSubmitCount" +
                " duplicate_source_present_count=$duplicatePresentCount" +
                " presentation_capability=${summary.presentationCapability}" +
                " x11_upload_call_rate_hz=${rate(summary.x11UploadCallRateHz)}" +
                " x11_upload_bytes=$uploadBytes" +
                " x11_upload_copy_ms=$uploadCopyMs" +
                " wait_choreo_pct=%.0f".format(Locale.ROOT, summary.waitChoreoPct) +
                " wait_content_pct=%.0f".format(Locale.ROOT, summary.waitContentPct) +
                " wait_state_pct=%.0f".format(Locale.ROOT, summary.waitStatePct)
        )
    }

    companion object {
        fun parseXloriePerfLine(line: String): ConsumerStats? {
            val consumerRedrawRateHz =
                extractField(line, "xlorie_consumer_redraw_rate_hz")?.toDoubleOrNull() ?: return null
            val choreoMs = extractField(line, "wait_choreo")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val contentMs = extractField(line, "wait_content")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val stateMs = extractField(line, "wait_state")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val totalWaitMs = choreoMs + contentMs + stateMs
            return if (totalWaitMs > 0) {
                ConsumerStats(consumerRedrawRateHz, 100.0 * choreoMs / totalWaitMs, 100.0 * contentMs / totalWaitMs, 100.0 * stateMs / totalWaitMs)
            } else {
                ConsumerStats(consumerRedrawRateHz, 0.0, 0.0, 0.0)
            }
        }

        fun parseXlorieStageRatesLine(line: String): X11StageRates? {
            if (
                !line.contains("XlorieStageRatesV1:") &&
                !line.contains("XlorieServerSnapshotV1:")
            ) return null
            val glxRate =
                extractField(line, "x11_glx_swap_request_rate_hz")?.toDoubleOrNull() ?: return null
            val damageRate =
                extractField(line, "x11_damage_request_rate_hz")?.toDoubleOrNull() ?: return null
            val markerRate =
                extractField(line, "x11_producer_marker_rate_hz")?.toDoubleOrNull()
            val markerErrors =
                extractField(line, "producer_marker_error_count")?.toLongOrNull()
            return X11StageRates(glxRate, damageRate, markerRate, markerErrors)
        }

        fun parseXloriePresentationLine(line: String): PresentationStats? {
            if (!line.contains("XloriePresentationV1:")) return null
            val capability = extractField(line, "capability") ?: return null
            val submittedRate =
                extractField(line, "xlorie_submitted_rate_hz")?.toDoubleOrNull() ?: return null
            val presentRate =
                extractField(line, "display_present_event_rate_hz")?.toDoubleOrNull() ?: return null
            val uniqueSubmitRate =
                extractField(line, "unique_source_submit_rate_hz")?.toDoubleOrNull() ?: return null
            val uniquePresentRate =
                extractField(line, "unique_source_present_rate_hz")?.toDoubleOrNull() ?: return null
            val duplicateSubmitCount =
                extractField(line, "duplicate_source_submit_count")?.toLongOrNull() ?: return null
            val duplicatePresentCount =
                extractField(line, "duplicate_source_present_count")?.toLongOrNull() ?: return null
            val sourceIdentity =
                extractField(line, "source_identity") ?: "x11_source_ordinal"
            val runId = extractField(line, "run_id") ?: "unknown"
            return PresentationStats(
                capability,
                submittedRate,
                presentRate,
                uniqueSubmitRate,
                uniquePresentRate,
                duplicateSubmitCount,
                duplicatePresentCount,
                sourceIdentity,
                runId
            )
        }

        fun parseXlorieCopyStatsLine(line: String): CopyStats? {
            if (
                !line.contains("XlorieCopyStatsV1:") &&
                !line.contains("XlorieServerSnapshotV1:")
            ) return null
            val callRate =
                extractField(line, "upload_call_rate_hz")?.toDoubleOrNull() ?: return null
            val bytes = extractField(line, "upload_bytes")?.toLongOrNull() ?: return null
            val copyNs = extractField(line, "upload_copy_ns")?.toLongOrNull() ?: return null
            return CopyStats(callRate, bytes, copyNs)
        }

        private fun extractField(line: String, key: String): String? {
            val prefix = "$key="
            val idx = line.indexOf(prefix)
            if (idx < 0) return null
            val start = idx + prefix.length
            val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
            return line.substring(start, end)
        }

        /** Map process names to spec's fixed prefixes: rl_, virgl_, proot_ */
        fun fixedPrefix(name: String): String = when {
            name.contains("RuneLite", ignoreCase = true) || name.contains("java", ignoreCase = true) -> "rl_"
            name.contains("virgl", ignoreCase = true) -> "virgl_"
            name.contains("proot", ignoreCase = true) -> "proot_"
            else -> "${name}_"
        }
    }

    data class ConsumerStats(
        val consumerRedrawRateHz: Double,
        val choreoPct: Double,
        val contentPct: Double,
        val statePct: Double
    )

    data class X11StageRates(
        val glxSwapRequestRateHz: Double,
        val damageRequestRateHz: Double,
        val producerMarkerRateHz: Double? = null,
        val producerMarkerErrorCount: Long? = null
    )

    data class PresentationStats(
        val capability: String,
        val submittedRateHz: Double,
        val displayPresentEventRateHz: Double,
        val uniqueSourceSubmitRateHz: Double,
        val uniqueSourcePresentRateHz: Double,
        val duplicateSourceSubmitCount: Long,
        val duplicateSourcePresentCount: Long,
        val sourceIdentity: String = "x11_source_ordinal",
        val runId: String = "unknown"
    )

    data class CopyStats(
        val uploadCallRateHz: Double,
        val uploadBytes: Long,
        val uploadCopyNs: Long
    )
}
