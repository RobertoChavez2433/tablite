package com.runelitetablet.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilityPayloadSanitizerTest {
    @Test
    fun `scrubs credentials identity and prohibited input content`() {
        val source =
            "email=player@example.com Authorization=secret " +
                "Bearer abc.def.ghi token=top-secret chatMessage=hello"

        val result = ObservabilityPayloadSanitizer.scrubString(source)

        assertFalse(result.contains("player@example.com"))
        assertFalse(result.contains("top-secret"))
        assertFalse(result.contains("chatMessage=hello"))
        assertTrue(result.contains("[redacted-email]"))
        assertTrue(result.contains("token=[redacted]"))
        assertTrue(result.contains("chatMessage=[redacted]"))
    }

    @Test
    fun `truncates oversized messages`() {
        val result = ObservabilityPayloadSanitizer.scrubString("x".repeat(5_000))
        assertTrue(result.length < 5_000)
        assertTrue(result.endsWith("...[truncated]"))
    }
}
