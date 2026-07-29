package com.runelitetablet.telemetry

import android.os.Debug
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Sampled telemetry-on-telemetry accounting.
 *
 * Every stage counts calls, bytes, drops, and queue depth. One call per fixed
 * power-of-two stride also measures wall and current-thread CPU time. The
 * sampled means are expanded to an estimated total in the exported snapshot.
 * Sampling keeps steady SUMMARY cost bounded while BURST/DEEP can still
 * explain which observability stage consumes time.
 */
object TelemetryCostTracker {
    enum class Stage(val wireName: String) {
        FRAME_RECORDER("frame_recorder"),
        NATIVE_INGRESS_READ("native_ingress_read"),
        NATIVE_INGRESS_NORMALIZE("native_ingress_normalize"),
        NATIVE_INGRESS_DISPATCH("native_ingress_dispatch"),
        PAYLOAD_SANITIZE("payload_sanitize"),
        LOG_ROUTE("log_route"),
        JSON_ENCODE("json_encode"),
        RETAINED_STORE("retained_store"),
        WEBSOCKET_QUEUE("websocket_queue"),
        EXPORT_MANIFEST("export_manifest"),
        EXPORT_EVENTS("export_events"),
        EXPORT_SUMMARY("export_summary");
    }

    class Token internal constructor(
        val wallStartNs: Long,
        val threadCpuStartNs: Long
    )

    private val calls = AtomicLongArray(Stage.entries.size)
    private val samples = AtomicLongArray(Stage.entries.size)
    private val wallNs = AtomicLongArray(Stage.entries.size)
    private val threadCpuNs = AtomicLongArray(Stage.entries.size)
    private val bytes = AtomicLongArray(Stage.entries.size)
    private val dropped = AtomicLongArray(Stage.entries.size)
    private val queueHighWater = AtomicLongArray(Stage.entries.size)

    fun begin(stage: Stage, byteCount: Long = 0, queueDepth: Long = 0): Token? {
        val index = stage.ordinal
        val call = calls.incrementAndGet(index)
        if (byteCount > 0) bytes.addAndGet(index, byteCount)
        updateHighWater(index, queueDepth)
        if ((call - 1L).and(MEASUREMENT_STRIDE - 1L) != 0L) return null
        samples.incrementAndGet(index)
        return Token(System.nanoTime(), currentThreadCpuNs())
    }

    fun end(stage: Stage, token: Token?) {
        if (token == null) return
        wallNs.addAndGet(stage.ordinal, (System.nanoTime() - token.wallStartNs).coerceAtLeast(0))
        val cpuEnd = currentThreadCpuNs()
        if (token.threadCpuStartNs > 0 && cpuEnd >= token.threadCpuStartNs) {
            threadCpuNs.addAndGet(stage.ordinal, cpuEnd - token.threadCpuStartNs)
        }
    }

    fun recordDrop(stage: Stage, count: Long = 1) {
        if (count > 0) dropped.addAndGet(stage.ordinal, count)
    }

    fun addBytes(stage: Stage, count: Long) {
        if (count > 0) bytes.addAndGet(stage.ordinal, count)
    }

    fun reset() {
        repeat(Stage.entries.size) { index ->
            calls.set(index, 0)
            samples.set(index, 0)
            wallNs.set(index, 0)
            threadCpuNs.set(index, 0)
            bytes.set(index, 0)
            dropped.set(index, 0)
            queueHighWater.set(index, 0)
        }
    }

    fun snapshot(runId: String, mode: String): TelemetryPipelineCostV1 =
        TelemetryPipelineCostV1(
            runId = runId,
            mode = mode,
            measurementStride = MEASUREMENT_STRIDE.toInt(),
            stages = Stage.entries.mapNotNull { stage ->
                val index = stage.ordinal
                val callCount = calls.get(index)
                if (callCount == 0L) return@mapNotNull null
                val sampleCount = samples.get(index)
                val sampledWall = wallNs.get(index)
                val sampledCpu = threadCpuNs.get(index)
                TelemetryStageCostV1(
                    stage = stage.wireName,
                    calls = callCount,
                    measuredSamples = sampleCount,
                    measuredWallNs = sampledWall,
                    measuredThreadCpuNs = sampledCpu,
                    estimatedWallNs = estimate(sampledWall, sampleCount, callCount),
                    estimatedThreadCpuNs = estimate(sampledCpu, sampleCount, callCount),
                    bytes = bytes.get(index),
                    dropped = dropped.get(index),
                    queueHighWaterMark = queueHighWater.get(index)
                )
            }
        )

    private fun estimate(measured: Long, sampleCount: Long, callCount: Long): Long {
        if (measured <= 0 || sampleCount <= 0 || callCount <= 0) return 0
        return ((measured.toDouble() / sampleCount.toDouble()) * callCount.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
    }

    private fun updateHighWater(index: Int, depth: Long) {
        if (depth <= 0) return
        var previous = queueHighWater.get(index)
        while (depth > previous && !queueHighWater.compareAndSet(index, previous, depth)) {
            previous = queueHighWater.get(index)
        }
    }

    private fun currentThreadCpuNs(): Long =
        runCatching { Debug.threadCpuTimeNanos() }.getOrDefault(0)

    private const val MEASUREMENT_STRIDE = 64L
}
