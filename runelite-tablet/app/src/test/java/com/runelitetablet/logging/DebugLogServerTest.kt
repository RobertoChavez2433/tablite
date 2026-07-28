package com.runelitetablet.logging

import org.junit.Assert.*
import org.junit.Test

class DebugLogServerTest {

    // --- parseLogcatTag ---

    @Test
    fun `parseLogcatTag extracts LorieNative`() {
        val line = "04-14 17:45:39.497 23282 23282 I LorieNative: nativeInit: initializing JNI"
        assertEquals("LorieNative", DebugLogServer.parseLogcatTag(line))
    }

    @Test
    fun `parseLogcatTag extracts gles-renderer`() {
        val line = "04-14 17:45:38.264 28700 28727 D gles-renderer: Xlorie: Initialized EGL version 1.5"
        assertEquals("gles-renderer", DebugLogServer.parseLogcatTag(line))
    }

    @Test
    fun `parseLogcatTag extracts XlorieCaps`() {
        val line = "04-14 17:45:38.274 28700 28729 I XlorieCaps: eglGetNativeClientBufferANDROID format=5"
        assertEquals("XlorieCaps", DebugLogServer.parseLogcatTag(line))
    }

    @Test
    fun `parseLogcatTag returns native for malformed line`() {
        assertEquals("native", DebugLogServer.parseLogcatTag("short"))
        assertEquals("native", DebugLogServer.parseLogcatTag(""))
    }

    @Test
    fun `parseLogcatTag handles extra whitespace`() {
        val line = "  04-14 17:45:39.500  23282  28764 D gles-renderer: loadShader: VERTEX"
        assertEquals("gles-renderer", DebugLogServer.parseLogcatTag(line))
    }

    // --- parseLogcatMessage ---

    @Test
    fun `parseLogcatMessage extracts message after colon-space`() {
        val line = "04-14 17:45:39.497 23282 23282 I LorieNative: nativeInit: initializing JNI"
        assertEquals("nativeInit: initializing JNI", DebugLogServer.parseLogcatMessage(line))
    }

    @Test
    fun `parseLogcatMessage preserves colons in message body`() {
        val line = "04-14 17:45:39.501 23282 28764 D gles-renderer: rendererInit: display=0xb4 context=0xb4 shaders=1/1"
        assertEquals("rendererInit: display=0xb4 context=0xb4 shaders=1/1", DebugLogServer.parseLogcatMessage(line))
    }

    @Test
    fun `parseLogcatMessage returns full line when no colon-space`() {
        val line = "some garbage with no colon-space separator"
        assertEquals(line, DebugLogServer.parseLogcatMessage(line))
    }

    @Test
    fun `parseLogcatMessage handles empty message after tag`() {
        val line = "04-14 17:45:39.500 23282 28764 D gles-renderer: "
        assertEquals("", DebugLogServer.parseLogcatMessage(line))
    }

    @Test
    fun `parseLogcatMessage extracts XloriePerf data`() {
        val line = "04-14 17:46:01.234 23282 28764 D gles-renderer: XloriePerf avg_lock_ms=0.12 avg_fence_ms=0.05 avg_swap_ms=2.34 xlorie_consumer_redraw_rate_hz=54"
        assertEquals(
            "XloriePerf avg_lock_ms=0.12 avg_fence_ms=0.05 avg_swap_ms=2.34 xlorie_consumer_redraw_rate_hz=54",
            DebugLogServer.parseLogcatMessage(line)
        )
    }
}
