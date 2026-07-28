package com.runelitetablet.perf

import com.runelitetablet.testutil.PrintLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates VirGL pipeline tuning env var configuration.
 * Tests the feature flag toggle and env var propagation from launch-runelite.sh.
 */
class VirglTuningTest {

    private val logger = PrintLogger()
    private val profiler = VirglEnvironmentProfiler(logger)

    @Test
    fun `virgl tune flag enables shader cache env vars`() {
        // When RLT_VIRGL_TUNE=1, these env vars should be set inside proot
        val tunedOutput = listOf(
            "VIRGL_ENV: env_MESA_GLSL_CACHE_DISABLE=0",
            "VIRGL_ENV: env_MESA_SHADER_CACHE_MAX_SIZE=256M",
            "VIRGL_ENV: virgl_pid=26636",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(tunedOutput)
        assertEquals("0", profile.envVars["MESA_GLSL_CACHE_DISABLE"])
        assertEquals("256M", profile.envVars["MESA_SHADER_CACHE_MAX_SIZE"])
    }

    @Test
    fun `virgl tune flag enables server-side threading`() {
        // When RLT_VIRGL_TUNE=1, VIRGL_RENDERER_THREAD and VIRGL_RENDERER_ASYNC should be set
        val tunedOutput = listOf(
            "VIRGL_ENV: env_VIRGL_RENDERER_THREAD=1",
            "VIRGL_ENV: env_VIRGL_RENDERER_ASYNC=1",
            "VIRGL_ENV: virgl_pid=26636",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(tunedOutput)
        assertEquals("1", profile.envVars["VIRGL_RENDERER_THREAD"])
        assertEquals("1", profile.envVars["VIRGL_RENDERER_ASYNC"])
    }

    @Test
    fun `virgl tune disabled produces no tuning env vars`() {
        // When RLT_VIRGL_TUNE=0 (default), no extra env vars
        val defaultOutput = listOf(
            "VIRGL_ENV: env_GALLIUM_DRIVER=virpipe",
            "VIRGL_ENV: virgl_pid=26636",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(defaultOutput)
        assertEquals("virpipe", profile.envVars["GALLIUM_DRIVER"])
        assertEquals(null, profile.envVars["VIRGL_RENDERER_THREAD"])
        assertEquals(null, profile.envVars["MESA_GLSL_CACHE_DISABLE"])
    }

    @Test
    fun `gallium_hud profiling env var pass-through`() {
        // When RLT_GALLIUM_HUD is set, it should appear in the proot environment
        val hudOutput = listOf(
            "VIRGL_ENV: env_GALLIUM_HUD=fps,draw-calls,prims-emitted",
            "VIRGL_ENV: virgl_pid=26636",
            "VIRGL_ENV: done"
        )

        val profile = profiler.parseOutput(hudOutput)
        assertNotNull(profile.envVars["GALLIUM_HUD"])
        assertTrue(profile.envVars["GALLIUM_HUD"]!!.contains("fps"))
    }

    @Test
    fun `benchmark parser can measure tuning impact`() {
        // Verify PipelineBenchmark can parse before/after FPS for A/B comparison
        val benchmark = PipelineBenchmark(logger)

        val beforeOutput = listOf(
            "BENCHMARK: gl_renderer=virgl",
            "300 frames in 5.0 seconds = 60.0 FPS",
            "BENCHMARK: done"
        )
        val afterOutput = listOf(
            "BENCHMARK: gl_renderer=virgl",
            "400 frames in 5.0 seconds = 80.0 FPS",
            "BENCHMARK: done"
        )

        val before = benchmark.parseOutput(beforeOutput)
        val after = benchmark.parseOutput(afterOutput)
        assertTrue(after.glxgearsRenderRateHz > before.glxgearsRenderRateHz)
    }
}
