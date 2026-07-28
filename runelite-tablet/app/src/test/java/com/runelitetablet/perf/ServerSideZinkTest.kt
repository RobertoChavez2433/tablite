package com.runelitetablet.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates test-server-zink.sh output parsing.
 * Tests Vulkan feature detection and Zink FPS extraction.
 */
class ServerSideZinkTest {

    @Test
    fun `parse zink eval detects viable when all features present`() {
        val output = listOf(
            "ZINK_EVAL: start",
            "ZINK_EVAL: fillModeNonSolid=TRUE shaderClipDistance=TRUE logicOp=TRUE",
            "ZINK_EVAL: vulkan features OK, testing Zink backend...",
            "ZINK_EVAL: zink virgl server started PID=12345",
            "300 frames in 5.0 seconds = 60.0 FPS",
            "400 frames in 5.0 seconds = 80.0 FPS",
            "ZINK_EVAL: glxgears_render_rate_hz=70.0",
            "ZINK_EVAL: verdict=viable glxgears_render_rate_hz=70.0",
            "ZINK_EVAL: done"
        )

        val result = parseZinkEvalOutput(output)
        assertTrue(result.viable)
        assertEquals(70.0, result.fps, 0.1)
    }

    @Test
    fun `parse zink eval detects blocked when features missing`() {
        val output = listOf(
            "ZINK_EVAL: start",
            "ZINK_EVAL: fillModeNonSolid=TRUE shaderClipDistance=FALSE logicOp=TRUE",
            "ZINK_EVAL: verdict=blocked reason=missing_vulkan_features"
        )

        val result = parseZinkEvalOutput(output)
        assertFalse(result.viable)
        assertEquals(0.0, result.fps, 0.1)
        assertEquals("missing_vulkan_features", result.blockedReason)
    }

    @Test
    fun `parse zink eval handles vulkaninfo missing`() {
        val output = listOf(
            "ZINK_EVAL: start",
            "ZINK_EVAL: error=vulkaninfo_not_found",
            "ZINK_EVAL: verdict=blocked reason=no_vulkaninfo"
        )

        val result = parseZinkEvalOutput(output)
        assertFalse(result.viable)
        assertEquals("no_vulkaninfo", result.blockedReason)
    }

    @Test
    fun `parse zink eval handles server failure`() {
        val output = listOf(
            "ZINK_EVAL: start",
            "ZINK_EVAL: fillModeNonSolid=TRUE shaderClipDistance=TRUE logicOp=TRUE",
            "ZINK_EVAL: error=socket_not_ready",
            "ZINK_EVAL: verdict=blocked reason=zink_server_failed"
        )

        val result = parseZinkEvalOutput(output)
        assertFalse(result.viable)
        assertEquals("zink_server_failed", result.blockedReason)
    }

    data class ZinkEvalResult(val viable: Boolean, val fps: Double, val blockedReason: String?)

    private fun parseZinkEvalOutput(lines: List<String>): ZinkEvalResult {
        var viable = false
        var fps = 0.0
        var blockedReason: String? = null

        for (line in lines) {
            if (!line.startsWith("ZINK_EVAL:")) continue
            val payload = line.substringAfter("ZINK_EVAL:").trim()
            when {
                payload.startsWith("verdict=viable") -> {
                    viable = true
                    Regex("""glxgears_render_rate_hz=([\d.]+)""").find(payload)?.let {
                        fps = it.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
                }
                payload.startsWith("verdict=blocked") -> {
                    viable = false
                    Regex("""reason=(\S+)""").find(payload)?.let {
                        blockedReason = it.groupValues[1]
                    }
                }
                payload.startsWith("glxgears_render_rate_hz=") -> {
                    fps = payload.substringAfter("glxgears_render_rate_hz=").toDoubleOrNull() ?: 0.0
                }
            }
        }

        return ZinkEvalResult(viable, fps, blockedReason)
    }
}
