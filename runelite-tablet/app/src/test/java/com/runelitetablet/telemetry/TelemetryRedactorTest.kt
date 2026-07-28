package com.runelitetablet.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRedactorTest {
    @Test
    fun `redacts launcher credentials and bearer tokens`() {
        val source = """
            JX_SESSION_ID=raw-session
            -DJX_CHARACTER_ID=12345 -Daccess_token=abc.def
            Authorization: Bearer secret-token
        """.trimIndent()

        val redacted = TelemetryRedactor.redact(source)
        assertFalse(redacted.contains("raw-session"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("secret-token"))
        assertTrue(redacted.contains("JX_SESSION_ID=***"))
        assertTrue(redacted.contains("Bearer ***"))
    }

    @Test
    fun `removes chat and input content while preserving adjacent frame telemetry`() {
        val source = """
            RLT_FRAME_RATE_V1 run_id=test produced_fps=21.5
            [Client] Chat message type CLAN: private words
            keyEvent=typed-secret
            [rlawt-perf] frame=30 swap_us mean=23000
        """.trimIndent()

        val redacted = TelemetryRedactor.redact(source)
        assertFalse(redacted.contains("private words"))
        assertFalse(redacted.contains("typed-secret"))
        assertTrue(redacted.contains("RLT_FRAME_RATE_V1"))
        assertTrue(redacted.contains("[rlawt-perf]"))
    }
}
