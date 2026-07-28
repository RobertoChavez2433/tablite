package com.runelitetablet.domain.presentation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FrameTimingTrackerTest {
    private lateinit var tracker: FrameTimingTracker

    @Before
    fun setup() {
        tracker = FrameTimingTracker()
    }

    @Test
    fun `120 frames at 8_33ms intervals equals 120fps`() {
        val intervalNs = 8_333_333L // 120fps
        for (i in 0 until 120) {
            tracker.recordFrame(i * intervalNs)
        }
        val fps = tracker.getAverageFps(120)
        assertEquals(120.0, fps, 1.0) // within 1 fps tolerance
    }

    @Test
    fun `60 frames at 16_67ms intervals equals 60fps`() {
        val intervalNs = 16_666_666L // 60fps
        for (i in 0 until 60) {
            tracker.recordFrame(i * intervalNs)
        }
        val fps = tracker.getAverageFps(60)
        assertEquals(60.0, fps, 1.0)
    }

    @Test
    fun `mixed intervals correctly averaged`() {
        // 10 frames with alternating 8ms and 10ms intervals
        var time = 0L
        tracker.recordFrame(time)
        for (i in 1 until 10) {
            val intervalNs = if (i % 2 == 0) 8_000_000L else 10_000_000L
            time += intervalNs
            tracker.recordFrame(time)
        }
        val fps = tracker.getAverageFps(10)
        // Average interval should be around 9ms -> ~111 fps
        assertTrue("fps was $fps", fps > 100 && fps < 130)
    }

    @Test
    fun `120Hz deadline detection flags a 60fps interval`() {
        val normalInterval = 8_333_333L // 120fps
        val jankInterval = 16_666_666L // 60fps -> jank
        // 10 normal frames
        for (i in 0 until 10) {
            tracker.recordFrame(i * normalInterval)
        }
        // 1 jank frame (16.67ms gap)
        tracker.recordFrame(10 * normalInterval + jankInterval)

        val jankCount = tracker.getMissedDeadlineCount()
        assertEquals(1, jankCount)
    }

    @Test
    fun `P99 frame time calculation`() {
        val intervalNs = 8_333_333L
        for (i in 0 until 100) {
            tracker.recordFrame(i * intervalNs)
        }
        val p99 = tracker.getFrameTimePercentile(99.0)
        assertEquals(8.33, p99, 0.5) // ~8.33ms
    }

    @Test
    fun `rolling window discards old samples`() {
        val tracker = FrameTimingTracker(capacity = 10)
        // Fill 20 frames (wraps around)
        for (i in 0 until 20) {
            tracker.recordFrame(i * 8_333_333L)
        }
        assertEquals(10, tracker.getFrameCount())
    }

    @Test
    fun `zero frames returns zero fps`() {
        assertEquals(0.0, tracker.getAverageFps(), 0.001)
    }

    @Test
    fun `one frame returns zero fps`() {
        tracker.recordFrame(0L)
        assertEquals(0.0, tracker.getAverageFps(), 0.001)
    }

    @Test
    fun `no jank with all fast frames`() {
        val intervalNs = 8_000_000L // 8ms -> under 12ms threshold
        for (i in 0 until 50) {
            tracker.recordFrame(i * intervalNs)
        }
        assertEquals(0, tracker.getMissedDeadlineCount())
    }

    @Test
    fun `reset clears all data`() {
        for (i in 0 until 10) {
            tracker.recordFrame(i * 8_333_333L)
        }
        tracker.reset()
        assertEquals(0, tracker.getFrameCount())
        assertEquals(0.0, tracker.getAverageFps(), 0.001)
    }

    @Test
    fun `120Hz vblank interval is 8333us`() {
        val intervalUs = 1_000_000.0 / 120.0
        assertEquals(8333.0, intervalUs, 1.0)
    }

    @Test
    fun `60Hz vblank interval is 16666us`() {
        val intervalUs = 1_000_000.0 / 60.0
        assertEquals(16666.0, intervalUs, 1.0)
    }

    @Test
    fun `deadline follows configured target rate`() {
        assertEquals(8.333, FrameTimingTracker(targetHz = 120).deadlineMs(), 0.01)
        assertEquals(11.111, FrameTimingTracker(targetHz = 90).deadlineMs(), 0.01)
        assertEquals(16.666, FrameTimingTracker(targetHz = 60).deadlineMs(), 0.01)
    }

    @Test
    fun `119Hz rounding error is less than 1 percent`() {
        val expected120 = 1_000_000.0 / 120.0
        val actual119 = 1_000_000.0 / 119.0
        val errorPercent = Math.abs(actual119 - expected120) / expected120 * 100
        assertTrue("Error was $errorPercent%", errorPercent < 1.0)
    }
}
