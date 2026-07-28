package com.runelitetablet.directsurface

import android.content.Context
import android.content.Intent
import com.runelitetablet.logging.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-owned control plane for the direct-Android-surface path.
 *
 * The Termux JVM cannot use `/system/bin/am start` from its app UID on current
 * Android builds: the shell command identifies as com.android.shell while the
 * Binder caller is com.termux, and ActivityTaskManager rejects that mismatch.
 * Android local sockets are also blocked cross-UID by SELinux on the target
 * tablet, so this listener binds loopback TCP inside com.runelitetablet before
 * launch. Native rlawt can then ask the app process to foreground
 * DirectSurfaceHostActivity at the exact moment RuneLite's GPU plugin creates
 * its context.
 */
object DirectSurfaceControlServer {
    const val CONTROL_PORT = 49382
    const val CONTROL_ENDPOINT = "127.0.0.1:$CONTROL_PORT"
    private const val DEFAULT_SURFACE_SOCKET_NAME = "rlt-rlawt-surface"
    private const val TAG = "DirectSurface"

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun ensureStarted(context: Context) {
        if (!running.compareAndSet(false, true)) {
            AppLog.d(TAG, "DirectSurfaceControlServer already running $CONTROL_ENDPOINT")
            return
        }

        val appContext = context.applicationContext
        Thread {
            runServer(appContext)
        }.apply {
            name = "rlt-direct-control"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        AppLog.i(TAG, "DirectSurfaceControlServer.stop $CONTROL_ENDPOINT")
    }

    private fun runServer(context: Context) {
        AppLog.i(TAG, "DirectSurfaceControlServer starting $CONTROL_ENDPOINT")
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), CONTROL_PORT))
            }
            serverSocket = server
            AppLog.i(TAG, "DirectSurfaceControlServer listening $CONTROL_ENDPOINT")
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (t: Throwable) {
                    if (running.get()) AppLog.e(TAG, "DirectSurfaceControlServer.accept failed", t)
                    break
                }
                handleClient(context, socket)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "DirectSurfaceControlServer failed to listen $CONTROL_ENDPOINT", t)
        } finally {
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            AppLog.i(TAG, "DirectSurfaceControlServer stopped $CONTROL_ENDPOINT")
        }
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { client ->
            val peerText = "remote=${client.remoteSocketAddress} local=${client.localSocketAddress}"
            val line = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.US_ASCII))
                .readLine()
                ?.trim()
                .orEmpty()

            AppLog.i(TAG, "DirectSurfaceControlServer request='$line' $peerText")
            val parts = line.split(' ', limit = 2)
            if (parts.firstOrNull() != "START") {
                writeResponse(client, "ERR unsupported-command\n")
                AppLog.w(TAG, "DirectSurfaceControlServer rejected request='$line'")
                return
            }

            val abstractName = parts.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_SURFACE_SOCKET_NAME

            try {
                val intent = Intent(context, DirectSurfaceHostActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(DirectSurfaceHostActivity.EXTRA_ABSTRACT_NAME, abstractName)
                }
                AppLog.i(TAG, "DirectSurfaceControlServer startActivity DirectSurfaceHostActivity abstractName=$abstractName")
                context.startActivity(intent)
                writeResponse(client, "OK started\n")
            } catch (t: Throwable) {
                AppLog.e(TAG, "DirectSurfaceControlServer startActivity failed abstractName=$abstractName", t)
                writeResponse(client, "ERR startActivity ${t.javaClass.simpleName}\n")
            }
        }
    }

    private fun writeResponse(client: Socket, response: String) {
        runCatching {
            client.outputStream.write(response.toByteArray(StandardCharsets.US_ASCII))
            client.outputStream.flush()
        }.onFailure {
            AppLog.w(TAG, "DirectSurfaceControlServer response failed: ${it.message}")
        }
    }
}
