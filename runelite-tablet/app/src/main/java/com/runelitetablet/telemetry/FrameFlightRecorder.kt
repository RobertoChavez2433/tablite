package com.runelitetablet.telemetry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max

/**
 * Fixed-capacity multi-producer/single-consumer flight recorder.
 *
 * Render threads only write primitive arrays and atomics. They never allocate a
 * FrameEventV1, perform file I/O, format strings, or wait for an exporter. A
 * full ring drops the new event and increments lostEvents.
 */
class FrameFlightRecorder(
    val runId: String,
    val backend: RendererBackend,
    val mode: TelemetryMode,
    private val processId: Int,
    val capacity: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacity >= 2) { "capacity must be at least 2" }
    }

    private val enqueuePosition = AtomicLong(0)
    private val dequeuePosition = AtomicLong(0)
    private val slotSequence = AtomicLongArray(capacity).also { sequence ->
        repeat(capacity) { sequence.set(it, it.toLong()) }
    }

    private val threadIds = IntArray(capacity)
    private val frameSeqs = LongArray(capacity)
    private val bufferIndices = IntArray(capacity)
    private val poolGenerations = IntArray(capacity)
    private val platformFrameIds = LongArray(capacity)
    private val stages = IntArray(capacity)
    private val eventTypes = IntArray(capacity)
    private val outcomes = IntArray(capacity)
    private val beginNs = LongArray(capacity)
    private val endNs = LongArray(capacity)
    private val threadCpuNs = LongArray(capacity)
    private val widths = IntArray(capacity)
    private val heights = IntArray(capacity)
    private val strides = IntArray(capacity)
    private val damagedPixels = LongArray(capacity)
    private val bytesTransferred = LongArray(capacity)
    private val commandCounts = IntArray(capacity)
    private val queueDepths = IntArray(capacity)
    private val configurationHashes = LongArray(capacity)
    private val flags = LongArray(capacity)

    private val recordedEvents = AtomicLong(0)
    private val lostEvents = AtomicLong(0)
    private val queueHighWaterMark = AtomicLong(0)
    private val telemetryCpuNs = AtomicLong(0)
    private val stageCounts = AtomicLongArray(MAX_STAGE_ID + 1)
    private val outcomeCounts = AtomicLongArray(MAX_OUTCOME_ID + 1)
    private val durationHistogram = AtomicLongArray(DURATION_BUCKETS)

    fun record(
        threadId: Int,
        frameSeq: Long,
        stage: FrameStage,
        eventType: FrameEventType,
        outcome: FrameOutcome,
        beginMonotonicNs: Long,
        endMonotonicNs: Long = beginMonotonicNs,
        threadCpuTimeNs: Long = 0,
        bufferIndex: Int = -1,
        poolGeneration: Int = 0,
        platformFrameId: Long = -1,
        width: Int = 0,
        height: Int = 0,
        strideBytes: Int = 0,
        damagedPixelCount: Long = 0,
        transferredBytes: Long = 0,
        commands: Int = 0,
        queueDepth: Int = 0,
        configurationHash: Long = 0,
        flags: Long = 0
    ): Boolean {
        if (mode == TelemetryMode.OFF) return true

        val overheadStart = System.nanoTime()
        recordedEvents.incrementAndGet()
        incrementIfInRange(stageCounts, stage.id)
        incrementIfInRange(outcomeCounts, outcome.id)
        durationHistogram.incrementAndGet(durationBucket(endMonotonicNs - beginMonotonicNs))

        if (mode == TelemetryMode.SUMMARY) {
            telemetryCpuNs.addAndGet(System.nanoTime() - overheadStart)
            return true
        }

        val position = claimSlot()
        if (position < 0) {
            lostEvents.incrementAndGet()
            incrementIfInRange(outcomeCounts, FrameOutcome.TELEMETRY_LOST.id)
            telemetryCpuNs.addAndGet(System.nanoTime() - overheadStart)
            return false
        }

        val slot = (position % capacity).toInt()
        threadIds[slot] = threadId
        frameSeqs[slot] = frameSeq
        bufferIndices[slot] = bufferIndex
        poolGenerations[slot] = poolGeneration
        platformFrameIds[slot] = platformFrameId
        stages[slot] = stage.id
        eventTypes[slot] = eventType.id
        outcomes[slot] = outcome.id
        beginNs[slot] = beginMonotonicNs
        endNs[slot] = endMonotonicNs
        threadCpuNs[slot] = threadCpuTimeNs
        widths[slot] = width
        heights[slot] = height
        strides[slot] = strideBytes
        damagedPixels[slot] = damagedPixelCount
        bytesTransferred[slot] = transferredBytes
        commandCounts[slot] = commands
        queueDepths[slot] = queueDepth
        configurationHashes[slot] = configurationHash
        this.flags[slot] = flags

        slotSequence.set(slot, position + 1)
        updateHighWater(position + 1 - dequeuePosition.get())
        telemetryCpuNs.addAndGet(System.nanoTime() - overheadStart)
        return true
    }

    fun recordHeartbeat(threadId: Int, monotonicNs: Long, frameSeq: Long = 0): Boolean =
        record(
            threadId = threadId,
            frameSeq = frameSeq,
            stage = FrameStage.RENDERER_HEARTBEAT,
            eventType = FrameEventType.HEARTBEAT,
            outcome = FrameOutcome.ACCEPTED,
            beginMonotonicNs = monotonicNs
        )

    /**
     * Called by a background exporter. Snapshot allocation happens here, never
     * on a render or presentation thread.
     */
    fun drain(maxEvents: Int = capacity): List<FrameEventV1> {
        if (mode == TelemetryMode.OFF || mode == TelemetryMode.SUMMARY || maxEvents <= 0) {
            return emptyList()
        }

        val result = ArrayList<FrameEventV1>(minOf(maxEvents, capacity))
        while (result.size < maxEvents) {
            val position = dequeuePosition.get()
            val slot = (position % capacity).toInt()
            if (slotSequence.get(slot) != position + 1) break

            result += FrameEventV1(
                runId = runId,
                backend = backend.id,
                processId = processId,
                threadId = threadIds[slot],
                frameSeq = frameSeqs[slot],
                bufferIndex = bufferIndices[slot],
                poolGeneration = poolGenerations[slot],
                platformFrameId = platformFrameIds[slot],
                stage = stages[slot],
                eventType = eventTypes[slot],
                outcome = outcomes[slot],
                beginMonotonicNs = beginNs[slot],
                endMonotonicNs = endNs[slot],
                threadCpuNs = threadCpuNs[slot],
                width = widths[slot],
                height = heights[slot],
                strideBytes = strides[slot],
                damagedPixels = damagedPixels[slot],
                bytesTransferred = bytesTransferred[slot],
                commandCount = commandCounts[slot],
                queueDepth = queueDepths[slot],
                configurationHash = configurationHashes[slot],
                flags = flags[slot]
            )

            slotSequence.set(slot, position + capacity)
            dequeuePosition.set(position + 1)
        }
        return result
    }

    fun snapshotSummary(): TelemetrySummaryV1 = TelemetrySummaryV1(
        runId = runId,
        backend = backend.id,
        mode = mode.id,
        recordedEvents = recordedEvents.get(),
        lostEvents = lostEvents.get(),
        queueHighWaterMark = queueHighWaterMark.get(),
        telemetryCpuNs = telemetryCpuNs.get(),
        stageCounts = snapshotNonZero(stageCounts),
        outcomeCounts = snapshotNonZero(outcomeCounts),
        durationHistogram = List(DURATION_BUCKETS) { durationHistogram.get(it) }
    )

    fun pendingEventCount(): Long = (enqueuePosition.get() - dequeuePosition.get()).coerceAtLeast(0)

    /**
     * Background exporters use this when a batch cannot be persisted. The
     * failure is visible in the same lost-event/outcome counters as ring
     * overflow; logging the exception is the exporter's responsibility.
     */
    fun recordTelemetryLoss(count: Long = 1) {
        if (mode == TelemetryMode.OFF || count <= 0) return
        lostEvents.addAndGet(count)
        stageCounts.addAndGet(FrameStage.TELEMETRY.id, count)
        outcomeCounts.addAndGet(FrameOutcome.TELEMETRY_LOST.id, count)
    }

    private fun claimSlot(): Long {
        repeat(MAX_CLAIM_ATTEMPTS) {
            val position = enqueuePosition.get()
            val slot = (position % capacity).toInt()
            val difference = slotSequence.get(slot) - position
            when {
                difference == 0L -> if (enqueuePosition.compareAndSet(position, position + 1)) return position
                difference < 0L -> return -1
            }
        }
        return -1
    }

    private fun updateHighWater(depth: Long) {
        var previous = queueHighWaterMark.get()
        while (depth > previous && !queueHighWaterMark.compareAndSet(previous, depth)) {
            previous = queueHighWaterMark.get()
        }
    }

    private fun incrementIfInRange(counters: AtomicLongArray, index: Int) {
        if (index in 0 until counters.length()) counters.incrementAndGet(index)
    }

    private fun durationBucket(durationNs: Long): Int {
        val micros = max(0L, durationNs) / 1_000L
        if (micros == 0L) return 0
        return (64 - java.lang.Long.numberOfLeadingZeros(micros)).coerceAtMost(DURATION_BUCKETS - 1)
    }

    private fun snapshotNonZero(counters: AtomicLongArray): Map<Int, Long> = buildMap {
        repeat(counters.length()) { index ->
            val value = counters.get(index)
            if (value != 0L) put(index, value)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 16_384
        private const val MAX_STAGE_ID = 127
        private const val MAX_OUTCOME_ID = 31
        private const val DURATION_BUCKETS = 32
        private const val MAX_CLAIM_ATTEMPTS = 4
    }
}
