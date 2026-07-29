package com.runelitetablet.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogStoreTest {
    @Test
    fun `retains cursor ordered pages`() {
        val store = DiagnosticLogStore("run-a", maxEntries = 4)
        assertNotNull(store.append("""{"message":"one"}"""))
        assertNotNull(store.append("""{"message":"two"}"""))

        val page = store.query(after = 0, limit = 10)

        assertEquals(2, page.entries.size)
        assertTrue(page.entries[0].contains(""""cursor":1"""))
        assertTrue(page.entries[1].contains(""""cursor":2"""))
        assertEquals(2L, page.nextCursor)
        assertFalse(page.gapDetected)
    }

    @Test
    fun `reports retention gaps and eviction`() {
        val store = DiagnosticLogStore("run-a", maxEntries = 2)
        repeat(3) { store.append("""{"message":"$it"}""") }

        val page = store.query(after = 0, limit = 10)

        assertTrue(page.gapDetected)
        assertEquals(1L, page.evictedCount)
        assertEquals(2L, page.oldestCursor)
    }

    @Test
    fun `drops oversized entries explicitly`() {
        val store = DiagnosticLogStore(
            "run-a",
            maxEntries = 2,
            maxBytes = 64,
            maxEntryBytes = 32
        )

        val retained = store.append("""{"message":"${"x".repeat(64)}"}""")

        assertEquals(null, retained)
        assertEquals(1L, store.query(0, 1).droppedCount)
    }
}
