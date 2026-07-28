package com.runelitetablet.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TelemetryTypesTest {
    @Test
    fun `stable enum ids are unique`() {
        assertUnique(RendererBackend.entries.map { it.id })
        assertUnique(TelemetryMode.entries.map { it.id })
        assertUnique(FrameStage.entries.map { it.id })
        assertUnique(FrameEventType.entries.map { it.id })
        assertUnique(FrameOutcome.entries.map { it.id })
        assertUnique(BottleneckClass.entries.map { it.id })
    }

    @Test
    fun `unknown backend and telemetry values use campaign defaults`() {
        assertEquals(RendererBackend.NATIVE_X11_VIRGL, RendererBackend.fromEnv("future"))
        assertEquals(TelemetryMode.SUMMARY, TelemetryMode.fromEnv("future"))
    }

    private fun assertUnique(values: List<Int>) {
        assertNotEquals("enum must not be empty", 0, values.size)
        assertEquals(values.size, values.toSet().size)
    }
}
