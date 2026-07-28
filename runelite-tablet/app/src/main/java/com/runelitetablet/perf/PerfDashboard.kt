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
    private val intervalSeconds: Long = 10
) {

    data class Summary(
        val affinities: List<CpuAffinityMonitor.ProcessAffinity>,
        val overheads: List<PtraceOverheadEstimator.OverheadSample>,
        val consumerRedrawFps: Double,
        val waitChoreoPct: Double,
        val waitContentPct: Double,
        val waitStatePct: Double
    )

    @Volatile private var lastConsumerRedrawFps: Double = 0.0
    @Volatile private var lastWaitChoreoPct: Double = 0.0
    @Volatile private var lastWaitContentPct: Double = 0.0
    @Volatile private var lastWaitStatePct: Double = 0.0
    @Volatile private var trackedPids: List<Int> = emptyList()

    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "perf-dashboard").apply { isDaemon = true }
    }
    private var scheduledTask: ScheduledFuture<*>? = null

    fun updateConsumerStats(consumerRedrawFps: Double, choreoPct: Double, contentPct: Double, statePct: Double) {
        lastConsumerRedrawFps = consumerRedrawFps
        lastWaitChoreoPct = choreoPct
        lastWaitContentPct = contentPct
        lastWaitStatePct = statePct
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

        val summary = Summary(
            affinities, overheads, lastConsumerRedrawFps,
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
        logger.perf(
            "PERF_SUMMARY: $affinityParts $overheadParts" +
                " consumer_redraw_fps=%.1f".format(Locale.ROOT, summary.consumerRedrawFps) +
                " wait_choreo_pct=%.0f".format(Locale.ROOT, summary.waitChoreoPct) +
                " wait_content_pct=%.0f".format(Locale.ROOT, summary.waitContentPct) +
                " wait_state_pct=%.0f".format(Locale.ROOT, summary.waitStatePct)
        )
    }

    companion object {
        fun parseXloriePerfLine(line: String): ConsumerStats? {
            val consumerRedrawFps = extractField(line, "consumer_redraw_fps")?.toDoubleOrNull() ?: return null
            val choreoMs = extractField(line, "wait_choreo")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val contentMs = extractField(line, "wait_content")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val stateMs = extractField(line, "wait_state")?.removeSuffix("ms")?.toDoubleOrNull() ?: 0.0
            val totalWaitMs = choreoMs + contentMs + stateMs
            return if (totalWaitMs > 0) {
                ConsumerStats(consumerRedrawFps, 100.0 * choreoMs / totalWaitMs, 100.0 * contentMs / totalWaitMs, 100.0 * stateMs / totalWaitMs)
            } else {
                ConsumerStats(consumerRedrawFps, 0.0, 0.0, 0.0)
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
        val consumerRedrawFps: Double,
        val choreoPct: Double,
        val contentPct: Double,
        val statePct: Double
    )
}
