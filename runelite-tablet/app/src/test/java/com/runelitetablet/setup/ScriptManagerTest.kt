package com.runelitetablet.setup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptManagerTest {
    @Test
    fun `script batch is streamed over stdin instead of process arguments`() {
        val transport = buildScriptBatchTransport(
            scriptsDir = "/termux/home/scripts",
            scripts = listOf(
                "launch-runelite.sh" to "x".repeat(56_000),
                "launch-runelite-native.sh" to "y".repeat(60_000)
            )
        )

        assertArrayEquals(arrayOf("-s"), transport.arguments)
        assertTrue(transport.stdin.length > 100_000)
        assertTrue(transport.stdin.startsWith("mkdir -p "))
        assertTrue(transport.stdin.contains("launch-runelite-native.sh"))
    }
}
