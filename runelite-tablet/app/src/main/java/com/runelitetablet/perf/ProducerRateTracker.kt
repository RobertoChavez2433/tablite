package com.runelitetablet.perf

import kotlin.math.abs

/**
 * Validates rlawt's externally observable GLX swap sequence windows.
 *
 * This is produced FPS only. It intentionally knows nothing about Xlorie
 * redraws, BufferQueue submissions, or physical presentation.
 */
class ProducerRateTracker(
    private val staleAfterNs: Long = DEFAULT_STALE_AFTER_NS
) {
    data class Sample(
        val runId: String,
        val firstSeq: Long,
        val lastSeq: Long,
        val intervalCount: Long,
        val elapsedNs: Long,
        val endMonotonicNs: Long,
        val reportedProducedFps: Double
    ) {
        val calculatedProducedFps: Double
            get() = intervalCount * NANOS_PER_SECOND / elapsedNs
    }

    enum class UpdateOutcome {
        ACCEPTED,
        NEW_RUN_ACCEPTED,
        DUPLICATE,
        REORDERED,
        OVERLAPPING,
        INVALID
    }

    data class Snapshot(
        val producedFps: Double?,
        val status: String,
        val runId: String?,
        val lastSeq: Long?,
        val endMonotonicNs: Long?
    )

    private var latest: Sample? = null

    @Synchronized
    fun reset() {
        latest = null
    }

    @Synchronized
    fun update(sample: Sample): UpdateOutcome {
        if (!isValid(sample)) return UpdateOutcome.INVALID

        val previous = latest
        if (previous == null || previous.runId != sample.runId) {
            latest = sample
            return if (previous == null) UpdateOutcome.ACCEPTED else UpdateOutcome.NEW_RUN_ACCEPTED
        }
        if (sample.lastSeq == previous.lastSeq && sample.endMonotonicNs == previous.endMonotonicNs) {
            return UpdateOutcome.DUPLICATE
        }
        if (sample.lastSeq <= previous.lastSeq || sample.endMonotonicNs <= previous.endMonotonicNs) {
            return UpdateOutcome.REORDERED
        }
        if (sample.firstSeq < previous.lastSeq) {
            return UpdateOutcome.OVERLAPPING
        }

        latest = sample
        return UpdateOutcome.ACCEPTED
    }

    @Synchronized
    fun snapshot(nowMonotonicNs: Long): Snapshot {
        val sample = latest ?: return Snapshot(
            producedFps = null,
            status = "unavailable_no_producer_signal",
            runId = null,
            lastSeq = null,
            endMonotonicNs = null
        )
        val ageNs = nowMonotonicNs - sample.endMonotonicNs
        if (ageNs < 0 || ageNs > staleAfterNs) {
            return Snapshot(
                producedFps = null,
                status = "unavailable_stale_producer_signal",
                runId = sample.runId,
                lastSeq = sample.lastSeq,
                endMonotonicNs = sample.endMonotonicNs
            )
        }
        return Snapshot(
            producedFps = sample.calculatedProducedFps,
            status = "valid_sequence_derived",
            runId = sample.runId,
            lastSeq = sample.lastSeq,
            endMonotonicNs = sample.endMonotonicNs
        )
    }

    private fun isValid(sample: Sample): Boolean {
        if (sample.runId.isBlank() || sample.runId == "unknown") return false
        if (sample.firstSeq < 1 || sample.lastSeq <= sample.firstSeq) return false
        if (sample.intervalCount <= 0 || sample.elapsedNs <= 0 || sample.endMonotonicNs <= 0) return false
        if (sample.lastSeq - sample.firstSeq != sample.intervalCount) return false
        if (!sample.reportedProducedFps.isFinite() || sample.reportedProducedFps <= 0.0) return false
        val calculated = sample.calculatedProducedFps
        return abs(calculated - sample.reportedProducedFps) <=
            maxOf(REPORTED_RATE_ABSOLUTE_TOLERANCE, calculated * REPORTED_RATE_RELATIVE_TOLERANCE)
    }

    companion object {
        const val DEFAULT_STALE_AFTER_NS = 15_000_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val REPORTED_RATE_ABSOLUTE_TOLERANCE = 0.01
        private const val REPORTED_RATE_RELATIVE_TOLERANCE = 0.001

        fun parse(line: String): Sample? {
            if (!line.contains("RLT_FRAME_RATE_V1")) return null
            if (field(line, "stage") != "producer" || field(line, "outcome") != "produced") return null
            return Sample(
                runId = field(line, "run_id") ?: return null,
                firstSeq = field(line, "first_seq")?.toLongOrNull() ?: return null,
                lastSeq = field(line, "last_seq")?.toLongOrNull() ?: return null,
                intervalCount = field(line, "interval_count")?.toLongOrNull() ?: return null,
                elapsedNs = field(line, "elapsed_ns")?.toLongOrNull() ?: return null,
                endMonotonicNs = field(line, "end_monotonic_ns")?.toLongOrNull() ?: return null,
                reportedProducedFps = field(line, "produced_fps")?.toDoubleOrNull() ?: return null
            )
        }

        private fun field(line: String, key: String): String? {
            val match = Regex("""(?:^|\s)${Regex.escape(key)}=([^\s]+)""").find(line)
            return match?.groupValues?.get(1)
        }
    }
}
