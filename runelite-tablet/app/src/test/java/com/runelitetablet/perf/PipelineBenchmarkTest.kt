package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineBenchmarkTest {

    private val logger = PrintLogger()
    private val benchmark = PipelineBenchmark(logger)

    @Test
    fun `parseRenderRateFromGlxgears extracts render rate from standard output`() {
        assertEquals(60.0, PipelineBenchmark.parseRenderRateFromGlxgears("300 frames in 5.0 seconds = 60.0 FPS")!!, 0.01)
        assertEquals(120.5, PipelineBenchmark.parseRenderRateFromGlxgears("602 frames in 5.0 seconds = 120.5 FPS")!!, 0.01)
    }

    @Test
    fun `parseRenderRateFromGlxgears returns null for non-matching lines`() {
        assertNull(PipelineBenchmark.parseRenderRateFromGlxgears("Running synchronized to the vertical refresh."))
        assertNull(PipelineBenchmark.parseRenderRateFromGlxgears(""))
    }

    @Test
    fun `parseOutput extracts gl info and calculates average FPS`() {
        val output = listOf(
            "BENCHMARK: start duration=30s",
            "BENCHMARK: gl_renderer=virgl",
            "BENCHMARK: gl_version=3.1 Mesa 23.1.0",
            "Running synchronized to the vertical refresh.  The framerate should be",
            "300 frames in 5.0 seconds = 60.0 FPS",
            "350 frames in 5.0 seconds = 70.0 FPS",
            "400 frames in 5.0 seconds = 80.0 FPS",
            "BENCHMARK: glxgears_render_rate_hz=70.0 samples=3",
            "BENCHMARK: done"
        )

        val result = benchmark.parseOutput(output)
        assertEquals(70.0, result.glxgearsRenderRateHz, 0.01)
        assertEquals("virgl", result.glRenderer)
        assertEquals("3.1 Mesa 23.1.0", result.glVersion)
        assertNull(result.error)
    }

    @Test
    fun `parseOutput handles missing glxgears`() {
        val output = listOf(
            "BENCHMARK: start duration=30s",
            "BENCHMARK: error=glxgears_not_found"
        )

        val result = benchmark.parseOutput(output)
        assertEquals(0.0, result.glxgearsRenderRateHz, 0.01)
        assertEquals("glxgears_not_found", result.error)
    }

    @Test
    fun `parseOutput handles no FPS output`() {
        val output = listOf(
            "BENCHMARK: start duration=30s",
            "BENCHMARK: gl_renderer=virgl",
            "BENCHMARK: gl_version=3.1 Mesa 23.1.0",
            "BENCHMARK: error=no_fps_output"
        )

        val result = benchmark.parseOutput(output)
        assertEquals(0.0, result.glxgearsRenderRateHz, 0.01)
        assertEquals("no_fps_output", result.error)
    }

    @Test
    fun `parseOutput logs result via logger`() {
        val output = listOf(
            "BENCHMARK: gl_renderer=virgl",
            "300 frames in 5.0 seconds = 60.0 FPS",
            "BENCHMARK: done"
        )

        benchmark.parseOutput(output)
        val perfLogs = logger.entriesWithTag("PERF")
        assertTrue(
            perfLogs.any {
                it.message.contains("BENCHMARK:") &&
                    it.message.contains("glxgears_render_rate_hz=60.0")
            }
        )
    }
}
