package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfDashboardTest {

    private lateinit var logger: PrintLogger
    private lateinit var affinityReader: CpuAffinityMonitorTest.FakeProcReader
    private lateinit var ptraceReader: PtraceOverheadEstimatorTest.FakeProcReader
    private lateinit var dashboard: PerfDashboard

    @Before
    fun setUp() {
        logger = PrintLogger()
        affinityReader = CpuAffinityMonitorTest.FakeProcReader()
        ptraceReader = PtraceOverheadEstimatorTest.FakeProcReader()

        val affinityMonitor = CpuAffinityMonitor(logger, affinityReader)
        val ptraceEstimator = PtraceOverheadEstimator(logger, ptraceReader)
        dashboard = PerfDashboard(logger, affinityMonitor, ptraceEstimator)
    }

    private fun buildStatLine(pid: Int, comm: String, cpu: Int, utime: Long = 100, stime: Long = 50): String {
        val fields = MutableList(50) { "0" }
        fields[0] = "S"; fields[1] = "1"; fields[11] = utime.toString(); fields[12] = stime.toString(); fields[36] = cpu.toString()
        return "$pid ($comm) ${fields.joinToString(" ")}"
    }

    @Test
    fun `collect emits PERF_SUMMARY with all components`() {
        affinityReader.stats[100] = buildStatLine(100, "RuneLite", 5)
        affinityReader.comms[100] = "RuneLite"
        affinityReader.maxFreqs[5] = 2800000L

        ptraceReader.stats[100] = buildStatLine(100, "RuneLite", 5, 46, 54)
        ptraceReader.comms[100] = "RuneLite"

        dashboard.updateConsumerStats(42.0, 25.0, 60.0, 15.0)
        val summary = dashboard.collect(listOf(100))

        assertEquals(1, summary.affinities.size)
        assertEquals(1, summary.overheads.size)
        assertEquals(42.0, summary.xlorieConsumerRedrawRateHz!!, 0.01)

        val perfLogs = logger.entriesWithTag("PERF")
        assertTrue(perfLogs.any { it.message.contains("PERF_SUMMARY:") })
        assertTrue(perfLogs.any { it.message.contains("xlorie_consumer_redraw_rate_hz=42.0") })
        assertTrue(perfLogs.any { it.message.contains("produced_fps=unavailable") })
        assertTrue(perfLogs.any { it.message.contains("presented_fps=unavailable") })
    }

    @Test
    fun `collect handles empty PID list`() {
        val summary = dashboard.collect(emptyList())
        assertTrue(summary.affinities.isEmpty())
        assertTrue(summary.overheads.isEmpty())
    }

    @Test
    fun `parseXloriePerfLine extracts renderer stats`() {
        val line = "XloriePerf: consumer_redraws=42 xlorie_consumer_redraw_rate_hz=42.0 wait_choreo=250.0ms wait_content=600.0ms wait_state=150.0ms"
        val stats = PerfDashboard.parseXloriePerfLine(line)
        assertNotNull(stats)
        assertEquals(42.0, stats!!.consumerRedrawRateHz, 0.01)
        assertEquals(25.0, stats.choreoPct, 0.1) // 250/(250+600+150) = 25%
        assertEquals(60.0, stats.contentPct, 0.1) // 600/1000 = 60%
        assertEquals(15.0, stats.statePct, 0.1)   // 150/1000 = 15%
    }

    @Test
    fun `parseXloriePerfLine returns null for non-matching line`() {
        assertNull(PerfDashboard.parseXloriePerfLine("some random log"))
    }

    @Test
    fun `parses structured X11 handoff presentation and copy summaries`() {
        val stage = PerfDashboard.parseXlorieStageRatesLine(
            "XlorieStageRatesV1: x11_damage_request_rate_hz=21.0 " +
                "x11_glx_swap_request_rate_hz=22.0"
        )
        val presentation = PerfDashboard.parseXloriePresentationLine(
            "XloriePresentationV1: capability=supported " +
                "xlorie_submitted_rate_hz=110.0 display_present_event_rate_hz=109.5 " +
                "unique_source_submit_rate_hz=20.5 unique_source_present_rate_hz=19.8 " +
                "duplicate_source_submit_count=89 duplicate_source_present_count=88"
        )
        val copy = PerfDashboard.parseXlorieCopyStatsLine(
            "XlorieCopyStatsV1: upload_call_rate_hz=42.0 upload_bytes=128000 " +
                "upload_copy_ns=2500000"
        )

        assertNotNull(stage)
        assertEquals(22.0, stage!!.glxSwapRequestRateHz, 0.01)
        assertEquals(21.0, stage.damageRequestRateHz, 0.01)
        assertNotNull(presentation)
        assertEquals("supported", presentation!!.capability)
        assertEquals(110.0, presentation.submittedRateHz, 0.01)
        assertEquals(109.5, presentation.displayPresentEventRateHz, 0.01)
        assertEquals(20.5, presentation.uniqueSourceSubmitRateHz, 0.01)
        assertEquals(19.8, presentation.uniqueSourcePresentRateHz, 0.01)
        assertEquals(89L, presentation.duplicateSourceSubmitCount)
        assertEquals(88L, presentation.duplicateSourcePresentCount)
        assertNotNull(copy)
        assertEquals(42.0, copy!!.uploadCallRateHz, 0.01)
        assertEquals(128000L, copy.uploadBytes)
        assertEquals(2500000L, copy.uploadCopyNs)
    }

    @Test
    fun `diagnostic handoff rates remain separate from canonical FPS`() {
        dashboard.updateX11StageRates(PerfDashboard.X11StageRates(22.0, 21.0))
        dashboard.updatePresentationStats(
            PerfDashboard.PresentationStats(
                "supported",
                110.0,
                109.5,
                20.5,
                19.8,
                89,
                88
            )
        )
        dashboard.updateCopyStats(PerfDashboard.CopyStats(42.0, 128000L, 2500000L))

        val summary = dashboard.collect(emptyList())

        assertEquals(22.0, summary.x11GlxSwapRequestRateHz!!, 0.01)
        assertEquals(21.0, summary.x11DamageRequestRateHz!!, 0.01)
        assertEquals(110.0, summary.xlorieSubmittedRateHz!!, 0.01)
        assertEquals(109.5, summary.displayPresentEventRateHz!!, 0.01)
        assertEquals(20.5, summary.uniqueSourceSubmitRateHz!!, 0.01)
        assertEquals(19.8, summary.uniqueSourcePresentRateHz!!, 0.01)
        assertEquals(89L, summary.duplicateSourceSubmitCount)
        assertEquals(88L, summary.duplicateSourcePresentCount)
        assertEquals("supported", summary.presentationCapability)
        assertNull(summary.producedFps)
        val log = logger.entriesWithTag("PERF").last().message
        assertTrue(log.contains("submitted_fps=unavailable"))
        assertTrue(log.contains("presented_fps=unavailable"))
        assertTrue(log.contains("display_present_event_rate_hz=109.500"))
        assertTrue(log.contains("unique_source_present_rate_hz=19.800"))
        assertTrue(log.contains("duplicate_source_present_count=88"))
    }

    @Test
    fun `producer sequence identity enables canonical submitted and presented FPS`() {
        val now = System.nanoTime()
        dashboard.updateProducerStats(
            ProducerRateTracker.Sample(
                runId = "11111111-1111-1111-1111-111111111111",
                firstSeq = 90,
                lastSeq = 120,
                intervalCount = 30,
                elapsedNs = 1_500_000_000,
                endMonotonicNs = now,
                reportedProducedFps = 20.0
            )
        )
        dashboard.updatePresentationStats(
            PerfDashboard.PresentationStats(
                capability = "supported",
                submittedRateHz = 20.0,
                displayPresentEventRateHz = 19.9,
                uniqueSourceSubmitRateHz = 19.8,
                uniqueSourcePresentRateHz = 19.7,
                duplicateSourceSubmitCount = 0,
                duplicateSourcePresentCount = 0,
                sourceIdentity = "producer_seq",
                runId = "11111111-1111-1111-1111-111111111111"
            )
        )

        val summary = dashboard.collect(emptyList())

        assertEquals(19.8, summary.submittedFps!!, 0.01)
        assertEquals(19.7, summary.presentedFps!!, 0.01)
        assertEquals("valid_producer_sequence_correlated", summary.presentationStatus)
        val log = logger.entriesWithTag("PERF").last().message
        assertTrue(log.contains("submitted_fps=19.800"))
        assertTrue(log.contains("presented_fps=19.700"))
    }

    @Test
    fun `parses cross process X server snapshot mirrored by renderer`() {
        val line = "XlorieServerSnapshotV1: generation=2 window_ms=5000 " +
            "x11_damage_request_rate_hz=22.0 x11_glx_swap_request_rate_hz=0.0 " +
            "upload_call_rate_hz=22.0 upload_bytes=2332472688 upload_copy_ns=230338231"

        val stage = PerfDashboard.parseXlorieStageRatesLine(line)
        val copy = PerfDashboard.parseXlorieCopyStatsLine(line)

        assertNotNull(stage)
        assertEquals(0.0, stage!!.glxSwapRequestRateHz, 0.01)
        assertEquals(22.0, stage.damageRequestRateHz, 0.01)
        assertNotNull(copy)
        assertEquals(2332472688L, copy!!.uploadBytes)
        assertEquals(230338231L, copy.uploadCopyNs)
    }

    @Test
    fun `parseXloriePerfLine handles zero wait times`() {
        val line = "XloriePerf: consumer_redraws=120 xlorie_consumer_redraw_rate_hz=120.0 wait_choreo=0.0ms wait_content=0.0ms wait_state=0.0ms"
        val stats = PerfDashboard.parseXloriePerfLine(line)
        assertNotNull(stats)
        assertEquals(120.0, stats!!.consumerRedrawRateHz, 0.01)
        assertEquals(0.0, stats.choreoPct, 0.01)
    }

    @Test
    fun `updateConsumerStats reflected in next collect`() {
        dashboard.updateConsumerStats(90.0, 10.0, 80.0, 10.0)
        val summary = dashboard.collect(emptyList())
        assertEquals(90.0, summary.xlorieConsumerRedrawRateHz!!, 0.01)
        assertEquals(10.0, summary.waitChoreoPct, 0.01)
        assertEquals(80.0, summary.waitContentPct, 0.01)
        assertEquals(10.0, summary.waitStatePct, 0.01)
    }

    @Test
    fun `fixedPrefix maps process names to spec prefixes`() {
        assertEquals("rl_", PerfDashboard.fixedPrefix("RuneLite"))
        assertEquals("rl_", PerfDashboard.fixedPrefix("java"))
        assertEquals("virgl_", PerfDashboard.fixedPrefix("virgl_test_server_android"))
        assertEquals("proot_", PerfDashboard.fixedPrefix("proot"))
        assertEquals("unknown_", PerfDashboard.fixedPrefix("unknown"))
    }

    @Test
    fun `PERF_SUMMARY uses fixed rl_ prefix for RuneLite process`() {
        affinityReader.stats[100] = buildStatLine(100, "RuneLite", 5)
        affinityReader.comms[100] = "RuneLite"
        affinityReader.maxFreqs[5] = 2800000L
        ptraceReader.stats[100] = buildStatLine(100, "RuneLite", 5, 46, 54)
        ptraceReader.comms[100] = "RuneLite"

        dashboard.collect(listOf(100))
        val summaryLog = logger.entriesWithTag("PERF")
            .first { it.message.contains("PERF_SUMMARY:") }
        assertTrue(summaryLog.message.contains("rl_cpu="))
        assertTrue(summaryLog.message.contains("rl_kernel_ratio="))
    }

    @Test
    fun `start and stop control periodic scheduling`() {
        dashboard.start()
        // Verify start log
        assertTrue(logger.entriesWithTag("PERF").any { it.message.contains("PERF_DASHBOARD: started") })
        dashboard.stop()
        assertTrue(logger.entriesWithTag("PERF").any { it.message.contains("PERF_DASHBOARD: stopped") })
    }

    @Test
    fun `start is idempotent`() {
        dashboard.start()
        dashboard.start() // Second call should be no-op
        val startLogs = logger.entriesWithTag("PERF").filter { it.message.contains("PERF_DASHBOARD: started") }
        assertEquals(1, startLogs.size)
        dashboard.stop()
    }
}
