package com.runelitetablet.logging

import android.util.Log
import com.runelitetablet.domain.process.LineProcessLauncher
import com.runelitetablet.telemetry.TelemetryCostTracker
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight WebSocket debug server on configurable port (default 8099).
 * Streams structured JSON log events to all connected clients.
 * Also serves an HTML/JS debug viewer at GET /.
 *
 * Lifecycle: start() in DEBUG builds, stop() on app destroy. No global state.
 */
class DebugLogServer(
    private val lineProcessLauncher: LineProcessLauncher,
    private val port: Int = DEFAULT_PORT,
    private val diagnosticsSession: AgentDiagnosticsSession? = null,
    stateSnapshotProvider: () -> AgentStateSnapshotV1 = AgentStateProjection::snapshot,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var logcatThread: Thread? = null
    private var sendThread: Thread? = null
    private val clients = CopyOnWriteArrayList<WsClient>()
    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()
    private val queuedFrames = AtomicInteger(0)
    private val droppedFrames = AtomicLong(0)
    private val startTimeMs = System.currentTimeMillis()
    private val logStore = DiagnosticLogStore(
        runId = diagnosticsSession?.runId ?: "legacy-debug-session"
    )
    private val statePublisher = AgentStatePublisher(stateSnapshotProvider)
    private var lastStateObservationMs = 0L
    private var lastStateHeartbeatMs = 0L

    /** Callback for bounded native telemetry summaries. Raw per-frame events never traverse logcat. */
    @Volatile var nativeLogListener: ((String) -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({ acceptLoop() }, "DebugLogServer-Accept").also { it.isDaemon = true; it.start() }
        logcatThread = Thread({ logcatBridge() }, "DebugLogServer-Logcat").also { it.isDaemon = true; it.start() }
        sendThread = Thread({ sendLoop() }, "DebugLogServer-Send").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        logcatThread?.interrupt()
        sendThread?.interrupt()
        clients.forEach { it.close() }
        clients.clear()
        sendQueue.clear()
        queuedFrames.set(0)
    }

    /**
     * Enqueue a log for broadcast. Safe to call from any thread (including main).
     * Actual socket writes happen on the send thread to avoid NetworkOnMainThreadException.
     */
    fun broadcast(json: String) {
        val retainToken = TelemetryCostTracker.begin(
            TelemetryCostTracker.Stage.RETAINED_STORE,
            byteCount = json.length.toLong()
        )
        val retained = try {
            runCatching { logStore.append(json) }.getOrNull()
        } finally {
            TelemetryCostTracker.end(TelemetryCostTracker.Stage.RETAINED_STORE, retainToken)
        }
        if (retained == null) {
            TelemetryCostTracker.recordDrop(TelemetryCostTracker.Stage.RETAINED_STORE)
            return
        }
        if (clients.isEmpty()) return
        val queueToken = TelemetryCostTracker.begin(
            TelemetryCostTracker.Stage.WEBSOCKET_QUEUE,
            byteCount = retained.encodedBytes.toLong(),
            queueDepth = queuedFrames.get().toLong()
        )
        if (queuedFrames.get() >= MAX_QUEUED_FRAMES) {
            droppedFrames.incrementAndGet()
            TelemetryCostTracker.recordDrop(TelemetryCostTracker.Stage.WEBSOCKET_QUEUE)
            TelemetryCostTracker.end(TelemetryCostTracker.Stage.WEBSOCKET_QUEUE, queueToken)
            return
        }
        sendQueue.offer(encodeWsFrame(retained.encodedJson))
        val depth = queuedFrames.incrementAndGet()
        if (depth > MAX_QUEUED_FRAMES) {
            if (sendQueue.poll() != null) queuedFrames.decrementAndGet()
            droppedFrames.incrementAndGet()
            TelemetryCostTracker.recordDrop(TelemetryCostTracker.Stage.WEBSOCKET_QUEUE)
        }
        TelemetryCostTracker.end(TelemetryCostTracker.Stage.WEBSOCKET_QUEUE, queueToken)
    }

    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.size
    val droppedEventCount: Long get() = droppedFrames.get()

    private fun sendLoop() {
        while (running.get()) {
            publishStateIfObserved()
            val frame = sendQueue.poll()
            if (frame == null) {
                Thread.sleep(10) // Avoid busy spin
                continue
            }
            queuedFrames.decrementAndGet()
            val dead = mutableListOf<WsClient>()
            for (client in clients) {
                try { client.send(frame) } catch (_: Exception) { dead.add(client) }
            }
            dead.forEach { it.close(); clients.remove(it) }
        }
    }

    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket(
                port,
                SERVER_BACKLOG,
                InetAddress.getByName("127.0.0.1")
            ).also { it.reuseAddress = true }
            Log.i(TAG, "DebugLogServer listening on loopback port $port")
            while (running.get()) {
                val socket = serverSocket!!.accept()
                Thread({ handleConnection(socket) }, "DebugLogServer-Client").also { it.isDaemon = true; it.start() }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "DebugLogServer accept error: ${e.message}")
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            // Read HTTP headers as raw bytes — do NOT wrap in BufferedReader/InputStreamReader
            // which do read-ahead buffering and corrupt the stream for WebSocket upgrade.
            val input = socket.getInputStream()
            val headerBytes = ByteArray(4096)
            var pos = 0
            while (pos < headerBytes.size) {
                val b = input.read()
                if (b == -1) return
                headerBytes[pos++] = b.toByte()
                // Detect end of headers: \r\n\r\n
                if (pos >= 4 &&
                    headerBytes[pos - 4] == '\r'.code.toByte() && headerBytes[pos - 3] == '\n'.code.toByte() &&
                    headerBytes[pos - 2] == '\r'.code.toByte() && headerBytes[pos - 1] == '\n'.code.toByte()
                ) break
            }
            val headerText = String(headerBytes, 0, pos, Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val colon = lines[i].indexOf(':')
                if (colon > 0) headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
            }

            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val wsKey = headers["sec-websocket-key"]

            if (wsKey != null && path == "/ws") {
                upgradeToWebSocket(socket, wsKey)
            } else if (path.substringBefore('?').startsWith("/v1/")) {
                handleCanonicalRequest(socket, requestLine, headers)
            } else {
                serveHtml(socket)
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "DebugLogServer connection error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun upgradeToWebSocket(socket: Socket, key: String) {
        val acceptKey = computeWsAcceptKey(key)
        val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acceptKey\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray())
        socket.getOutputStream().flush()
        val client = WsClient(socket)
        clients.add(client)
        Log.i(TAG, "WebSocket client connected, total clients: ${clients.size}")
        // Keep connection alive — read loop (handles pings/close frames)
        try {
            val input = socket.getInputStream()
            while (running.get() && !socket.isClosed) {
                val b1 = input.read(); if (b1 == -1) break
                val opcode = b1 and 0x0F
                if (opcode == 0x8) break // close frame
                val b2 = input.read(); if (b2 == -1) break
                val len = b2 and 0x7F
                val payloadLen = when {
                    len <= 125 -> len
                    len == 126 -> (input.read() shl 8) or input.read()
                    else -> break // 64-bit lengths not supported
                }
                val mask = if (b2 and 0x80 != 0) ByteArray(4).also { input.read(it) } else null
                val payload = ByteArray(payloadLen)
                var read = 0; while (read < payloadLen) { val n = input.read(payload, read, payloadLen - read); if (n == -1) break; read += n }
                if (mask != null) for (j in payload.indices) payload[j] = (payload[j].toInt() xor mask[j % 4].toInt()).toByte()
                if (opcode == 0x9) { // ping -> pong
                    client.send(encodeWsFrame(String(payload), opcode = 0xA))
                }
            }
        } catch (_: Exception) {} finally {
            client.close(); clients.remove(client)
        }
    }

    private fun serveHtml(socket: Socket) {
        val html = buildViewerHtml()
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
        socket.getOutputStream().write(response.toByteArray())
        socket.getOutputStream().flush()
        socket.close()
    }

    private fun logcatBridge() {
        try {
            val process = lineProcessLauncher.launch(listOf(
                "logcat", "-v", "threadtime", "-s",
                "LorieNative:V", "gles-renderer:V", "XlorieCaps:V", "Xlorie:V",
                "RlawtSurface:V", "DirectSurface:V", "RLClient:V", "RLFrame:V"
            ))
            process.use {
                while (running.get()) {
                    val line = process.readLine() ?: break
                    val msg = parseLogcatMessage(line)
                    val tag = parseLogcatTag(line)
                    // RuneLite client.log lines arrive via launch-runelite.sh tailing into
                    // `log -t RLClient`. Route them under source="runelite" so the HTML
                    // viewer shows them as a distinct filter group.
                    val source = if (tag.startsWith("RL")) "runelite" else "native"
                    val json = buildLogJson(
                        level = parseLogcatLevel(line), tag = tag,
                        msg = msg, thread = parseLogcatThread(line),
                        source = source, file = "", lineNum = 0, correlationId = null
                    )
                    broadcast(json)
                    if (
                        msg.contains("XloriePerf:") ||
                        msg.contains("XlorieStageRatesV1:") ||
                        msg.contains("XlorieCopyStatsV1:") ||
                        msg.contains("XlorieHandoffV1:") ||
                        msg.contains("XloriePresentationV1:") ||
                        msg.contains("XlorieServerSnapshotV1:") ||
                        msg.contains("RLT_FRAME_RATE_V1")
                    ) {
                        nativeLogListener?.invoke(msg)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Logcat bridge error: ${e.message}")
        }
    }

    fun buildLogJson(
        level: String, tag: String, msg: String, thread: String,
        source: String, file: String, lineNum: Int, correlationId: String?
    ): String {
        val encodeToken = TelemetryCostTracker.begin(
            TelemetryCostTracker.Stage.JSON_ENCODE,
            byteCount = msg.length.toLong()
        )
        val ts = System.currentTimeMillis()
        val elapsed = ts - startTimeMs
        val sanitizeToken = TelemetryCostTracker.begin(
            TelemetryCostTracker.Stage.PAYLOAD_SANITIZE,
            byteCount = msg.length.toLong()
        )
        val safeMessage = try {
            ObservabilityPayloadSanitizer.scrubString(msg)
        } finally {
            TelemetryCostTracker.end(
                TelemetryCostTracker.Stage.PAYLOAD_SANITIZE,
                sanitizeToken
            )
        }
        val eventClass = ObservabilityEventCatalog.eventClass(tag)
        val corrField = correlationId?.let(::jsonString) ?: "null"
        val captureRunId = com.runelitetablet.telemetry.CaptureSessionRegistry
            .active?.manifest?.runId
        return try {
            buildString {
                append("""{"schemaVersion":${ObservabilityEventCatalog.SCHEMA_VERSION}""")
                append(""","runId":${jsonString(diagnosticsSession?.runId ?: "legacy-debug-session")}""")
                append(""","captureRunId":${captureRunId?.let(::jsonString) ?: "null"}""")
                append(""","ts":$ts,"elapsed":$elapsed""")
                append(""","level":${jsonString(level)},"severity":${jsonString(severity(level))}""")
                append(""","eventClass":${jsonString(eventClass)}""")
                append(""","fingerprint":${jsonString("$eventClass.${level.lowercase()}")}""")
                append(""","tag":${jsonString(tag)},"message":${jsonString(safeMessage)}""")
                append(""","msg":${jsonString(safeMessage)},"thread":${jsonString(thread)}""")
                append(""","correlationId":$corrField,"source":${jsonString(source)}""")
                append(""","file":${jsonString(file)},"line":$lineNum}""")
            }
        } finally {
            TelemetryCostTracker.end(TelemetryCostTracker.Stage.JSON_ENCODE, encodeToken)
        }
    }

    private fun publishStateIfObserved() {
        if (statePublisher.subscriberCount == 0) return
        val now = System.currentTimeMillis()
        if (now - lastStateObservationMs >= STATE_OBSERVATION_INTERVAL_MS) {
            runCatching { statePublisher.observeNow() }
            lastStateObservationMs = now
        }
        if (now - lastStateHeartbeatMs >= STATE_HEARTBEAT_INTERVAL_MS) {
            statePublisher.heartbeat()
            lastStateHeartbeatMs = now
        }
    }

    private fun handleCanonicalRequest(
        socket: Socket,
        requestLine: String,
        headers: Map<String, String>
    ) {
        val session = diagnosticsSession
        if (session == null || !session.authorize(headers["authorization"])) {
            sendJson(socket, 403, """{"error":"run_auth_failed"}""")
            return
        }
        val requestParts = requestLine.split(" ")
        val method = requestParts.getOrNull(0) ?: ""
        val rawTarget = requestParts.getOrNull(1) ?: ""
        val path = rawTarget.substringBefore('?')
        if (method != "GET") {
            sendJson(socket, 405, """{"error":"method_not_allowed"}""")
            return
        }
        when (path) {
            "/v1/capabilities" -> sendJson(socket, 200, capabilitiesJson(session))
            "/v1/health" -> sendJson(socket, 200, healthJson(session))
            "/v1/state" -> {
                val snapshot = statePublisher.observeNow()
                sendJson(socket, 200, STATE_JSON.encodeToString(
                    AgentStateSnapshotV1.serializer(),
                    snapshot
                ))
            }
            "/v1/state/events" -> serveStateEvents(
                socket,
                headers["last-event-id"]
            )
            "/v1/logs" -> {
                val query = parseQuery(rawTarget)
                val after = query["after"]?.toLongOrNull() ?: 0L
                val limit = query["limit"]?.toIntOrNull() ?: 100
                if (after < 0 || limit !in 1..500) {
                    sendJson(socket, 400, """{"error":"invalid_log_query"}""")
                } else {
                    sendJson(socket, 200, encodeLogPage(logStore.query(after, limit)))
                }
            }
            else -> sendJson(socket, 404, """{"error":"not_found"}""")
        }
    }

    private fun serveStateEvents(socket: Socket, lastEventId: String?) {
        val subscription = try {
            statePublisher.subscribe(lastEventId)
        } catch (_: IllegalStateException) {
            sendJson(socket, 429, """{"error":"state_subscriber_limit_reached"}""")
            return
        }
        try {
            val headers =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream; charset=utf-8\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "X-Content-Type-Options: nosniff\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            while (running.get() && !socket.isClosed && !subscription.isClosed()) {
                val event = subscription.next(1_000) ?: continue
                socket.getOutputStream().write(event.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        } finally {
            subscription.close()
            runCatching { socket.close() }
        }
    }

    private fun sendJson(socket: Socket, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val response =
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
        socket.close()
    }

    private fun capabilitiesJson(session: AgentDiagnosticsSession): String =
        """{"schemaVersion":1,"runId":${jsonString(session.runId)},"protocol":"tablite-agent-diagnostics","routes":["/v1/capabilities","/v1/health","/v1/logs","/v1/state","/v1/state/events"],"stateStream":"sse","logStream":"websocket_legacy_plus_cursor","eventCatalog":${encodeStrings(ObservabilityEventCatalog.allEventClasses())},"limits":{"logPage":500,"logEntryBytes":65536,"stateSubscribers":8,"stateHistory":256}}"""

    private fun healthJson(session: AgentDiagnosticsSession): String {
        val stats = logStore.stats()
        return """{"ok":true,"schemaVersion":1,"runId":${jsonString(session.runId)},"server":"running","stateGeneration":${jsonString(statePublisher.generation())},"clients":${clients.size},"logRetained":${stats["retainedEntries"]},"logEvicted":${stats["evictedCount"]},"logDropped":${stats["droppedCount"]},"websocketDropped":${droppedFrames.get()}}"""
    }

    private fun encodeLogPage(page: DiagnosticLogPage): String =
        buildString {
            append("""{"runId":${jsonString(page.runId)},"entries":[""")
            append(page.entries.joinToString(","))
            append("""],"nextCursor":${page.nextCursor}""")
            append(""","retainedRange":{"oldest":${page.oldestCursor ?: "null"},"newest":${page.newestCursor ?: "null"}}""")
            append(""","gap":{"detected":${page.gapDetected},"requestedAfter":${page.requestedAfter}}""")
            append(""","evictedCount":${page.evictedCount},"droppedCount":${page.droppedCount},"truncated":${page.truncated}}""")
        }

    private fun parseQuery(target: String): Map<String, String> =
        target.substringAfter('?', "")
            .split('&')
            .mapNotNull {
                val separator = it.indexOf('=')
                if (separator <= 0) null
                else it.substring(0, separator) to it.substring(separator + 1)
            }
            .toMap()

    private class WsClient(private val socket: Socket) {
        private val output: OutputStream = socket.getOutputStream()
        private val lock = Any()
        fun send(frame: ByteArray) = synchronized(lock) { output.write(frame); output.flush() }
        fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    companion object {
        const val DEFAULT_PORT = 8099
        const val MAX_QUEUED_FRAMES = 500
        private const val SERVER_BACKLOG = 8
        private const val STATE_OBSERVATION_INTERVAL_MS = 500L
        private const val STATE_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val TAG = "RLT"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-5AB5DC11D65E"
        private val STATE_JSON = kotlinx.serialization.json.Json {
            encodeDefaults = true
        }

        private fun severity(level: String): String = when (level) {
            "E" -> "error"
            "W" -> "warning"
            else -> "info"
        }

        private fun jsonString(value: String): String =
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<String>(),
                value
            )

        private fun encodeStrings(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

        fun encodeWsFrame(text: String, opcode: Int = 0x1): ByteArray {
            val payload = text.toByteArray(Charsets.UTF_8)
            val header = when {
                payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
                payload.size <= 65535 -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126.toByte(),
                    (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte()
                )
                else -> throw IllegalArgumentException("Payload too large for simple frame")
            }
            return header + payload
        }

        fun computeWsAcceptKey(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        }

        private fun parseLogcatLevel(line: String): String {
            val parts = line.trim().split(Regex("\\s+"))
            return if (parts.size >= 5) when (parts[4].firstOrNull()) {
                'V' -> "V"; 'D' -> "D"; 'I' -> "I"; 'W' -> "W"; 'E' -> "E"; else -> "D"
            } else "D"
        }

        private fun parseLogcatThread(line: String): String {
            val parts = line.trim().split(Regex("\\s+"))
            return if (parts.size >= 4) parts[3] else "unknown"
        }

        fun parseLogcatTag(line: String): String {
            // threadtime format: "04-14 17:36:43.823 27320 27400 D gles-renderer: message"
            // parts: [date, time, pid, tid, level, tag:, ...]
            val parts = line.trim().split(Regex("\\s+"))
            return if (parts.size >= 6) parts[5].trimEnd(':') else "native"
        }

        fun parseLogcatMessage(line: String): String {
            // Everything after first ": " is the message body
            val colonSpace = line.indexOf(": ")
            return if (colonSpace >= 0) line.substring(colonSpace + 2) else line
        }

        fun buildViewerHtml(): String = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>RLT Debug</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#1a1a2e;color:#e0e0e0;font:12px/1.4 'Consolas','Courier New',monospace}
#toolbar{position:sticky;top:0;z-index:10;background:#16213e;padding:8px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}
#toolbar input,#toolbar select{background:#0f3460;color:#e0e0e0;border:1px solid #333;padding:4px 8px;border-radius:3px;font:inherit}
#toolbar button{background:#e94560;color:#fff;border:none;padding:4px 12px;border-radius:3px;cursor:pointer;font:inherit}
#toolbar button.active{background:#0f3460}
#log{padding:8px;overflow-y:auto;height:calc(100vh - 50px)}
.entry{padding:2px 4px;border-bottom:1px solid #1a1a2e;white-space:pre-wrap;word-break:break-all}
.E{color:#ff6b6b;background:#2d0f0f}.W{color:#ffd93d;background:#2d2a0f}
.JANK{color:#ff6b6b;font-weight:bold}.HANDOFF{color:#4ecdc4}
.FRAME{color:#95e1d3}.SURFACE{color:#a8d8ea}.IPC{color:#f38181}
.FD{color:#aa96da}.GL{color:#fcbad3}.NATIVE{color:#b8b5ff}
.elapsed{color:#666;min-width:80px;display:inline-block}
.tag{color:#4ecdc4;min-width:80px;display:inline-block;font-weight:bold}
.corr{color:#666;font-style:italic}
.src-native{border-left:3px solid #f38181}.src-shell{border-left:3px solid #ffd93d}
.src-kotlin{border-left:3px solid #4ecdc4}.src-runelite{border-left:3px solid #ffb86c}
#status{color:#666;font-size:11px}
</style></head><body>
<div id="toolbar">
<input id="filter" placeholder="Filter text..." oninput="applyFilters()">
<select id="levelFilter" onchange="applyFilters()"><option value="">All levels</option><option>D</option><option>I</option><option>W</option><option>E</option></select>
<select id="sourceFilter" onchange="applyFilters()"><option value="">All sources</option><option>kotlin</option><option>native</option><option>shell</option><option>runelite</option></select>
<input id="tagFilter" placeholder="Tag..." oninput="applyFilters()">
<input id="corrFilter" placeholder="CorrelationId..." oninput="applyFilters()">
<button id="pauseBtn" onclick="togglePause()">Pause</button>
<button onclick="document.getElementById('log').innerHTML='';entries=[]">Clear</button>
<span id="status">Connecting...</span>
</div><div id="log"></div>
<script>
let entries=[],paused=false,buffer=[];
const log=document.getElementById('log'),status=document.getElementById('status');
function connect(){
const ws=new WebSocket('ws://'+location.host+'/ws');
ws.onopen=()=>{status.textContent='Connected (0 entries)'};
ws.onmessage=e=>{const d=JSON.parse(e.data);entries.push(d);if(paused){buffer.push(d)}else{appendEntry(d)}status.textContent='Connected ('+entries.length+' entries)'};
ws.onclose=()=>{status.textContent='Disconnected — reconnecting...';setTimeout(connect,2000)};
ws.onerror=()=>ws.close();
}
function appendEntry(d){
if(!matchesFilter(d))return;
const div=document.createElement('div');
div.className='entry '+d.level+(d.tag?' '+d.tag:'')+' src-'+d.source;
const corr=d.correlationId?' <span class="corr">['+d.correlationId+']</span>':'';
div.innerHTML='<span class="elapsed">+'+d.elapsed+'ms</span> <span class="tag">'+d.level+'/'+d.tag+'</span> '+escHtml(d.msg)+corr+' <span style="color:#555">'+d.thread+' '+d.source+'</span>';
log.appendChild(div);
if(entries.length%5===0)log.scrollTop=log.scrollHeight;
}
function escHtml(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
function matchesFilter(d){
const f=document.getElementById('filter').value.toLowerCase();
const lf=document.getElementById('levelFilter').value;
const sf=document.getElementById('sourceFilter').value;
const tf=document.getElementById('tagFilter').value.toLowerCase();
const cf=document.getElementById('corrFilter').value.toLowerCase();
if(f&&!d.msg.toLowerCase().includes(f)&&!d.tag.toLowerCase().includes(f))return false;
if(lf&&d.level!==lf)return false;
if(sf&&d.source!==sf)return false;
if(tf&&!d.tag.toLowerCase().includes(tf))return false;
if(cf&&(!d.correlationId||!d.correlationId.toLowerCase().includes(cf)))return false;
return true;
}
function applyFilters(){log.innerHTML='';entries.forEach(d=>{appendEntry(d)})}
function togglePause(){paused=!paused;document.getElementById('pauseBtn').textContent=paused?'Resume':'Pause';
document.getElementById('pauseBtn').className=paused?'active':'';
if(!paused){buffer.forEach(d=>appendEntry(d));buffer=[];log.scrollTop=log.scrollHeight}}
connect();
</script></body></html>"""
    }
}
