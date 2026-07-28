package com.runelitetablet.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.runelitetablet.domain.process.LineProcess
import com.runelitetablet.domain.process.LineProcessLauncher

/**
 * Verifies the logcat bridge captures all X server diagnostic tags,
 * including the expanded set (XloriePerf, Xlorie) added for Phase 0 observability.
 */
class XServerLogBridgeTest {

    @Test
    fun `parseLogcatTag extracts XloriePerf tag`() {
        val line = "04-14 17:46:01.234 23282 28764 D XloriePerf: frames=42 avg_inter_frame_ms=23.8"
        assertEquals("XloriePerf", DebugLogServer.parseLogcatTag(line))
    }

    @Test
    fun `parseLogcatTag extracts Xlorie tag`() {
        val line = "04-14 17:45:38.270 28700 28727 I Xlorie: new surface applied: 0xb4"
        assertEquals("Xlorie", DebugLogServer.parseLogcatTag(line))
    }

    @Test
    fun `parseLogcatMessage extracts wait-reason fields from XloriePerf`() {
        val line = "04-14 17:46:01.234 23282 28764 D gles-renderer: XloriePerf: frames=42 wait_choreo=120.5ms wait_content=350.2ms wait_state=10.0ms"
        val msg = DebugLogServer.parseLogcatMessage(line)
        assertTrue(msg.contains("wait_choreo="))
        assertTrue(msg.contains("wait_content="))
        assertTrue(msg.contains("wait_state="))
    }

    @Test
    fun `buildLogJson includes native source for X server logs`() {
        val server = DebugLogServer(
            LineProcessLauncher {
                object : LineProcess {
                    override fun readLine(): String? = null
                    override fun close() = Unit
                }
            }
        )
        val json = server.buildLogJson(
            level = "D", tag = "XloriePerf",
            msg = "consumer_redraws=42 xlorie_consumer_redraw_rate_hz=42.0 wait_choreo=120.5ms",
            thread = "28764", source = "native",
            file = "", lineNum = 0, correlationId = null
        )
        assertTrue(json.contains("\"source\":\"native\""))
        assertTrue(json.contains("\"tag\":\"XloriePerf\""))
        assertTrue(json.contains("wait_choreo=120.5ms"))
    }

    @Test
    fun `all expected X server tags are recognized by parser`() {
        val tags = listOf("LorieNative", "gles-renderer", "XlorieCaps", "XloriePerf", "Xlorie")
        for (tag in tags) {
            val line = "04-14 17:45:39.497 23282 23282 I $tag: test message"
            assertEquals("Tag $tag should be extracted", tag, DebugLogServer.parseLogcatTag(line))
        }
    }
}
