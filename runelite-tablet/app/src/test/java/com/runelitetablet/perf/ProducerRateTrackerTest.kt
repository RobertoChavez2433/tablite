package com.runelitetablet.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducerRateTrackerTest {
    private val runId = "787febae-2e7e-4fd2-9ddd-9bdc219b8229"

    @Test
    fun `structured producer line is parsed and rate is sequence derived`() {
        val sample = ProducerRateTracker.parse(
            "[rlawt] RLT_FRAME_RATE_V1 run_id=$runId stage=producer outcome=produced " +
                "first_seq=1 last_seq=31 interval_count=30 elapsed_ns=250000000 end_monotonic_ns=9000000000 produced_fps=120.000"
        )
        val tracker = ProducerRateTracker()

        assertEquals(ProducerRateTracker.UpdateOutcome.ACCEPTED, tracker.update(sample!!))
        val snapshot = tracker.snapshot(9_100_000_000)
        assertEquals(120.0, snapshot.producedFps!!, 0.001)
        assertEquals("valid_sequence_derived", snapshot.status)
        assertEquals(31L, snapshot.lastSeq)
    }

    @Test
    fun `consumer or presentation labels cannot parse as produced rate`() {
        assertNull(
            ProducerRateTracker.parse(
                "RLT_FRAME_RATE_V1 run_id=$runId stage=xlorie_consumer outcome=produced " +
                    "first_seq=1 last_seq=31 interval_count=30 elapsed_ns=250000000 " +
                    "end_monotonic_ns=9000000000 produced_fps=120.000"
            )
        )
    }

    @Test
    fun `duplicate reordered overlapping and malformed windows are rejected`() {
        val tracker = ProducerRateTracker()
        val first = sample(firstSeq = 1, lastSeq = 31, endNs = 9_000_000_000)
        assertEquals(ProducerRateTracker.UpdateOutcome.ACCEPTED, tracker.update(first))
        assertEquals(ProducerRateTracker.UpdateOutcome.DUPLICATE, tracker.update(first))
        assertEquals(
            ProducerRateTracker.UpdateOutcome.REORDERED,
            tracker.update(sample(firstSeq = 1, lastSeq = 30, endNs = 9_100_000_000))
        )
        assertEquals(
            ProducerRateTracker.UpdateOutcome.OVERLAPPING,
            tracker.update(sample(firstSeq = 30, lastSeq = 60, endNs = 9_200_000_000))
        )
        assertEquals(
            ProducerRateTracker.UpdateOutcome.INVALID,
            tracker.update(
                sample(firstSeq = 31, lastSeq = 61, endNs = 9_300_000_000)
                    .copy(intervalCount = 29)
            )
        )
    }

    @Test
    fun `new run resets sequence history and stale samples become unavailable`() {
        val tracker = ProducerRateTracker(staleAfterNs = 1_000_000_000)
        assertEquals(
            ProducerRateTracker.UpdateOutcome.ACCEPTED,
            tracker.update(sample(firstSeq = 1, lastSeq = 31, endNs = 9_000_000_000))
        )
        assertEquals(
            ProducerRateTracker.UpdateOutcome.NEW_RUN_ACCEPTED,
            tracker.update(
                sample(firstSeq = 1, lastSeq = 31, endNs = 10_000_000_000)
                    .copy(runId = "new-run")
            )
        )

        val stale = tracker.snapshot(11_000_000_001)
        assertNull(stale.producedFps)
        assertEquals("unavailable_stale_producer_signal", stale.status)
    }

    @Test
    fun `explicit session reset removes the prior producer identity`() {
        val tracker = ProducerRateTracker()
        tracker.update(sample(firstSeq = 1, lastSeq = 31, endNs = 9_000_000_000))
        tracker.reset()

        val reset = tracker.snapshot(9_100_000_000)
        assertNull(reset.producedFps)
        assertNull(reset.runId)
        assertNull(reset.lastSeq)
        assertEquals("unavailable_no_producer_signal", reset.status)
    }

    @Test
    fun `reported rate must agree with sequence count and monotonic elapsed time`() {
        val tracker = ProducerRateTracker()
        val outcome = tracker.update(
            sample(firstSeq = 1, lastSeq = 31, endNs = 9_000_000_000)
                .copy(reportedProducedFps = 43.0)
        )
        assertEquals(ProducerRateTracker.UpdateOutcome.INVALID, outcome)
        assertTrue(tracker.snapshot(9_100_000_000).status.startsWith("unavailable"))
    }

    private fun sample(firstSeq: Long, lastSeq: Long, endNs: Long) =
        ProducerRateTracker.Sample(
            runId = runId,
            firstSeq = firstSeq,
            lastSeq = lastSeq,
            intervalCount = lastSeq - firstSeq,
            elapsedNs = 250_000_000,
            endMonotonicNs = endNs,
            reportedProducedFps = (lastSeq - firstSeq) * 4.0
        )
}
