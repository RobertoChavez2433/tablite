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
}
