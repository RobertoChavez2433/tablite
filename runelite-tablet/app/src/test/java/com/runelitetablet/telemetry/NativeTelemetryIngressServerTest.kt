package com.runelitetablet.telemetry

import com.runelitetablet.testutil.PrintLogger
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetryIngressServerTest {
    @Test
    fun `loopback ingress accepts only bounded producer summaries`() {
        val logger = PrintLogger()
        val accepted = mutableListOf<String>()
        val received = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val server = NativeTelemetryIngressServer(logger, port) { line ->
            synchronized(accepted) { accepted.add(line) }
            received.countDown()
        }

        server.start()
        waitForServer(port)
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().bufferedWriter().use { writer ->
                writer.appendLine("not-telemetry")
                writer.appendLine(
                    "RLT_FRAME_RATE_V1 run_id=test stage=producer outcome=produced " +
                        "first_seq=1 last_seq=31 interval_count=30 elapsed_ns=250000000 " +
                        "end_monotonic_ns=1000000000 produced_fps=120.000"
                )
                writer.flush()
            }
        }

        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals(1, synchronized(accepted) { accepted.size })
        assertEquals(1L, server.rejectedLineCount)
        server.stop()
    }

    private fun waitForServer(port: Int) {
        repeat(100) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(10)
            }
        }
        throw AssertionError("native telemetry ingress did not start")
    }
}
