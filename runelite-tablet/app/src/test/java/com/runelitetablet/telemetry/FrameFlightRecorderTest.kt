package com.runelitetablet.telemetry

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFlightRecorderTest {
    @Test
    fun `burst records and drains complete events in order`() {
        val recorder = recorder(TelemetryMode.BURST, capacity = 4)

        repeat(3) { frame ->
            assertTrue(
                recorder.record(
                    threadId = 7,
                    frameSeq = frame.toLong(),
                    stage = FrameStage.GLX_SWAP,
                    eventType = FrameEventType.SPAN,
                    outcome = FrameOutcome.SUBMITTED,
                    beginMonotonicNs = frame * 10L,
                    endMonotonicNs = frame * 10L + 8,
                    bufferIndex = frame,
                    poolGeneration = 2
                )
            )
        }

        val events = recorder.drain()
        assertEquals(listOf(0L, 1L, 2L), events.map { it.frameSeq })
        assertEquals(listOf(0, 1, 2), events.map { it.bufferIndex })
        assertTrue(events.all { it.durationNs == 8L })
        assertEquals(0, recorder.pendingEventCount())
    }

    @Test
    fun `full ring drops without overwriting unread events`() {
        val recorder = recorder(TelemetryMode.BURST, capacity = 2)
        assertTrue(record(recorder, 1))
        assertTrue(record(recorder, 2))
        assertFalse(record(recorder, 3))

        val events = recorder.drain()
        assertEquals(listOf(1L, 2L), events.map { it.frameSeq })
        assertEquals(1, recorder.snapshotSummary().lostEvents)
        assertEquals(1L, recorder.snapshotSummary().outcomeCounts[FrameOutcome.TELEMETRY_LOST.id])
    }

    @Test
    fun `drained slots are reusable after wrap`() {
        val recorder = recorder(TelemetryMode.BURST, capacity = 2)
        assertTrue(record(recorder, 1))
        assertTrue(record(recorder, 2))
        assertEquals(1, recorder.drain(maxEvents = 1).size)
        assertTrue(record(recorder, 3))
        assertEquals(listOf(2L, 3L), recorder.drain().map { it.frameSeq })
    }

    @Test
    fun `summary mode keeps counters but no raw events`() {
        val recorder = recorder(TelemetryMode.SUMMARY, capacity = 2)
        repeat(10) { record(recorder, it.toLong()) }

        assertTrue(recorder.drain().isEmpty())
        val summary = recorder.snapshotSummary()
        assertEquals(10, summary.recordedEvents)
        assertEquals(0, summary.lostEvents)
        assertEquals(10L, summary.stageCounts[FrameStage.PRODUCER_FRAME.id])
    }

    @Test
    fun `off mode performs no accounting`() {
        val recorder = recorder(TelemetryMode.OFF, capacity = 2)
        repeat(3) { assertTrue(record(recorder, it.toLong())) }
        assertEquals(0, recorder.snapshotSummary().recordedEvents)
    }

    @Test
    fun `background export failures are surfaced as telemetry loss`() {
        val recorder = recorder(TelemetryMode.SUMMARY, capacity = 2)
        recorder.recordTelemetryLoss(2)

        val summary = recorder.snapshotSummary()
        assertEquals(2, summary.lostEvents)
        assertEquals(2L, summary.stageCounts[FrameStage.TELEMETRY.id])
        assertEquals(2L, summary.outcomeCounts[FrameOutcome.TELEMETRY_LOST.id])
    }

    @Test
    fun `event schema round trips without losing 64 bit identity`() {
        val event = FrameEventV1(
            runId = "run-1",
            backend = RendererBackend.DIRECT_VIRGL_AHB.id,
            processId = 10,
            threadId = 11,
            frameSeq = Long.MAX_VALUE - 1,
            bufferIndex = 2,
            poolGeneration = 9,
            platformFrameId = 8_000_000_000L,
            stage = FrameStage.DISPLAY_PRESENT.id,
            eventType = FrameEventType.INSTANT.id,
            outcome = FrameOutcome.PRESENTED.id,
            beginMonotonicNs = 123,
            endMonotonicNs = 456
        )

        val encoded = Json.encodeToString(FrameEventV1.serializer(), event)
        assertEquals(event, Json.decodeFromString(FrameEventV1.serializer(), encoded))
    }

    private fun recorder(mode: TelemetryMode, capacity: Int) = FrameFlightRecorder(
        runId = "test-run",
        backend = RendererBackend.NATIVE_X11_VIRGL,
        mode = mode,
        processId = 42,
        capacity = capacity
    )

    private fun record(recorder: FrameFlightRecorder, frameSeq: Long) = recorder.record(
        threadId = 7,
        frameSeq = frameSeq,
        stage = FrameStage.PRODUCER_FRAME,
        eventType = FrameEventType.INSTANT,
        outcome = FrameOutcome.PRODUCED,
        beginMonotonicNs = frameSeq * 10
    )
}
