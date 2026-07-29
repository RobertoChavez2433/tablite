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
    fun `loopback ingress accepts only allowlisted bounded summaries`() {
        val logger = PrintLogger()
        val accepted = mutableListOf<String>()
        val received = CountDownLatch(11)
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
                    "[rlawt] RLT_FRAME_RATE_V1 run_id=test stage=producer outcome=produced " +
                        "first_seq=1 last_seq=31 interval_count=30 elapsed_ns=250000000 " +
                        "end_monotonic_ns=1000000000 produced_fps=120.000"
                )
                writer.appendLine(
                    "RLT_VIRGL_WORKER_V1 run_id=test pid=42 sample_count=50 " +
                        "worker_run_ns=1000000 runqueue_wait_ns=2000"
                )
                writer.appendLine(
                    "RLT_MESA_VTEST_V1 run_id=test stage=mesa_vtest outcome=measured " +
                        "first_seq=1 last_seq=30 frame_count=30 attribution_pct=100.000"
                )
                writer.appendLine(
                    "RLT_VIRGL_HOST_V1 run_id=test stage=host_completion outcome=measured " +
                        "first_seq=1 last_seq=30 frame_count=30 attribution_pct=100.000"
                )
                writer.appendLine(
                    "RLT_VTEST_TRANSPORT_V1 run_id=test stage=vtest_transport " +
                        "outcome=connected kind=tcp host=127.0.0.1 port=49383 protocol_max=2"
                )
                writer.appendLine(
                    "RLT_VTEST_FENCE_V1 run_id=test stage=busy_wait " +
                        "outcome=recovered submitted=2 completed=2 queue_depth=1 " +
                        "wait_ms=100 timeout_polls=1"
                )
                writer.appendLine(
                    "RLT_FRAME_STREAM_V1 run_id=test frame_seq=30 stage=host_send " +
                        "outcome=sent width=2960 height=1848 bytes=21880320 send_ns=1000"
                )
                writer.appendLine(
                    "RLT_AHB_STREAM_V1 run_id=test frame_seq=30 stage=host_handoff " +
                        "outcome=presented width=1280 height=1024 frames=30 failures=0"
                )
                writer.appendLine(
                    "RLT_AHB_GPU_V1 run_id=test frame_seq=30 stage=host_handoff " +
                        "outcome=presented width=1280 height=1024 frames=30 " +
                        "total_blit_ns=1000 total_finish_ns=2000 failures=0"
                )
                writer.appendLine(
                    "RLT_TELEMETRY_COST_V1 run_id=test mode=BURST stage=rlawt_producer " +
                        "outcome=measured calls=60 measured_samples=60 measured_wall_ns=1000 " +
                        "measured_thread_cpu_ns=900 bytes=480 dropped=0 queue_high_water_mark=0"
                )
                writer.appendLine(
                    "[rlawt] RLT_TELEMETRY_COST_V1 run_id=test mode=BURST " +
                        "stage=rlawt_producer outcome=measured calls=60 measured_samples=60 " +
                        "measured_wall_ns=1000 measured_thread_cpu_ns=900 bytes=480 " +
                        "dropped=0 queue_high_water_mark=0"
                )
                val detailedHostWindowPrefix =
                    "RLT_VIRGL_HOST_V1 run_id=test stage=host_completion "
                writer.appendLine(
                    detailedHostWindowPrefix +
                        "x".repeat(2_106 - detailedHostWindowPrefix.length)
                )
                writer.appendLine(
                    detailedHostWindowPrefix +
                        "x".repeat(4_097 - detailedHostWindowPrefix.length)
                )
                writer.flush()
            }
        }

        try {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            repeat(100) {
                if (server.rejectedLineCount >= 2L) return@repeat
                Thread.sleep(10)
            }
            assertEquals(12, synchronized(accepted) { accepted.size })
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_FRAME_RATE_V1 ") }
                }
            )
            assertEquals(
                2,
                synchronized(accepted) {
                    accepted.count { it.startsWith("RLT_TELEMETRY_COST_V1 ") }
                }
            )
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_VTEST_TRANSPORT_V1 ") }
                }
            )
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_VTEST_FENCE_V1 ") }
                }
            )
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_FRAME_STREAM_V1 ") }
                }
            )
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_AHB_STREAM_V1 ") }
                }
            )
            assertTrue(
                synchronized(accepted) {
                    accepted.any { it.startsWith("RLT_AHB_GPU_V1 ") }
                }
            )
            assertTrue(synchronized(accepted) { accepted.any { it.length == 2_106 } })
            assertEquals(2L, server.rejectedLineCount)
        } finally {
            server.stop()
        }
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
