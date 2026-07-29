package com.runelitetablet.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryCostTrackerTest {
    @Before
    fun setUp() {
        TelemetryCostTracker.reset()
    }

    @After
    fun tearDown() {
        TelemetryCostTracker.reset()
    }

    @Test
    fun sampledStageExportsCallsBytesQueueAndDrops() {
        repeat(65) {
            val token = TelemetryCostTracker.begin(
                TelemetryCostTracker.Stage.NATIVE_INGRESS_NORMALIZE,
                byteCount = 10,
                queueDepth = it.toLong()
            )
            TelemetryCostTracker.end(
                TelemetryCostTracker.Stage.NATIVE_INGRESS_NORMALIZE,
                token
            )
        }
        TelemetryCostTracker.recordDrop(
            TelemetryCostTracker.Stage.NATIVE_INGRESS_NORMALIZE,
            2
        )

        val snapshot = TelemetryCostTracker.snapshot("run", "BURST")
        val stage = snapshot.stages.single()

        assertEquals("run", snapshot.runId)
        assertEquals(64, snapshot.measurementStride)
        assertEquals(65, stage.calls)
        assertEquals(2, stage.measuredSamples)
        assertEquals(650, stage.bytes)
        assertEquals(2, stage.dropped)
        assertEquals(64, stage.queueHighWaterMark)
        assertTrue(stage.measuredWallNs >= 0)
    }
}
