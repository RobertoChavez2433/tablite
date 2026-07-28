package com.runelitetablet.telemetry

import com.runelitetablet.domain.logging.Logger
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-UID, device-local ingress for bounded native telemetry summaries.
 *
 * Android logcat visibility is UID-scoped on production devices, so Termux
 * cannot reliably use it to feed the Tablite dashboard. This server binds
 * loopback only, accepts a strict allowlist of summary records, and never
 * handles raw per-frame events, credentials, client logs, or gameplay data.
 */
class NativeTelemetryIngressServer(
    private val logger: Logger,
    private val port: Int = DEFAULT_PORT,
    private val listener: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val rejectedLines = AtomicLong(0)
    private val clients = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val rejectedLineCount: Long get() = rejectedLines.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({ acceptLoop() }, "native-telemetry-ingress").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clients.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
            logger.perf("NATIVE-TELEMETRY-INGRESS: listening address=loopback port=$port")
            while (running.get()) {
                val socket = serverSocket!!.accept()
                clients.add(socket)
                Thread(
                    { handleClient(socket) },
                    "native-telemetry-client"
                ).apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                logger.e("PERF", "Native telemetry ingress failed", error)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use {
                val input = it.getInputStream()
                while (running.get()) {
                    val line = readBoundedLine(input) ?: break
                    if (line.contains("RLT_FRAME_RATE_V1") && line.length <= MAX_LINE_CHARS) {
                        listener(line)
                    } else {
                        rejectedLines.incrementAndGet()
                    }
                }
            }
        } catch (_: Exception) {
            // A native launcher or app lifecycle transition may close either
            // end. The next session establishes a new bounded connection.
        } finally {
            clients.remove(socket)
        }
    }

    private fun readBoundedLine(input: InputStream): String? {
        val result = StringBuilder()
        var overflow = false
        while (true) {
            val next = input.read()
            if (next == -1) return if (result.isEmpty() && !overflow) null else result.toString()
            if (next == '\n'.code) {
                if (overflow) {
                    return ""
                }
                return result.toString().trimEnd('\r')
            }
            if (result.length < MAX_LINE_CHARS) {
                result.append(next.toChar())
            } else {
                overflow = true
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8100
        private const val BACKLOG = 2
        private const val MAX_LINE_CHARS = 2_048
    }
}
