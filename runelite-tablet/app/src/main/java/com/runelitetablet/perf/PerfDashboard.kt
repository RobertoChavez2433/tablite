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
        val xlorieConsumerRedrawRateHz: Double?,
        val waitChoreoPct: Double,
        val waitContentPct: Double,
        val waitStatePct: Double
    )

    @Volatile private var lastConsumerRedrawRateHz: Double? = null
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

    fun updateProducerStats(sample: ProducerRateTracker.Sample): ProducerRateTracker.UpdateOutcome =
        producerRateTracker.update(sample)

    fun resetFrameMetrics() {
        producerRateTracker.reset()
        lastConsumerRedrawRateHz = null
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

        val summary = Summary(
            affinities,
            overheads,
            producer.producedFps,
            producer.status,
            producer.lastSeq,
            lastConsumerRedrawRateHz,
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
        logger.perf(
            "PERF_SUMMARY: $affinityParts $overheadParts" +
                " produced_fps=$producedValue" +
                " produced_fps_status=${summary.producedFpsStatus}" +
                " producer_last_seq=$producerLastSeq" +
                " accepted_fps=unavailable submitted_fps=unavailable presented_fps=unavailable" +
                " presentation_status=unavailable_no_correlated_presentation_events" +
                " xlorie_consumer_redraw_rate_hz=$consumerRate" +
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
}
