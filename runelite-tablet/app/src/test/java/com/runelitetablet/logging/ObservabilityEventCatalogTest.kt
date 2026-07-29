package com.runelitetablet.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilityEventCatalogTest {
    @Test
    fun `native ingress components remain declared canonical event classes`() {
        val eventClass = ObservabilityEventCatalog.eventClass("NATIVE_VIRGL_WORKER")

        assertEquals("tablite.native_virgl_worker", eventClass)
        assertTrue(ObservabilityEventCatalog.isDeclared(eventClass))
    }
}
