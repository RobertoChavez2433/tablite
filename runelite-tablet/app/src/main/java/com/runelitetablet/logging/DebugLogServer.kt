package com.runelitetablet.logging

import android.util.Log
import com.runelitetablet.domain.process.LineProcessLauncher
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
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
        if (clients.isEmpty()) return
        if (queuedFrames.get() >= MAX_QUEUED_FRAMES) {
            droppedFrames.incrementAndGet()
            return
        }
        sendQueue.offer(encodeWsFrame(json))
        val depth = queuedFrames.incrementAndGet()
        if (depth > MAX_QUEUED_FRAMES) {
            if (sendQueue.poll() != null) queuedFrames.decrementAndGet()
            droppedFrames.incrementAndGet()
        }
    }

    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.size
    val droppedEventCount: Long get() = droppedFrames.get()

    private fun sendLoop() {
        while (running.get()) {
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
            serverSocket = ServerSocket(port).also { it.reuseAddress = true }
            Log.i(TAG, "DebugLogServer listening on port $port")
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
                    if (msg.contains("XloriePerf:") || msg.contains("RLT_FRAME_RATE_V1")) {
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
        val ts = System.currentTimeMillis()
        val elapsed = ts - startTimeMs
        val escapedMsg = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        val escapedFile = file.replace("\\", "\\\\").replace("\"", "\\\"")
        val corrField = if (correlationId != null) "\"$correlationId\"" else "null"
        return """{"ts":$ts,"elapsed":$elapsed,"level":"$level","tag":"$tag","msg":"$escapedMsg","thread":"$thread","correlationId":$corrField,"source":"$source","file":"$escapedFile","line":$lineNum}"""
    }

    private class WsClient(private val socket: Socket) {
        private val output: OutputStream = socket.getOutputStream()
        private val lock = Any()
        fun send(frame: ByteArray) = synchronized(lock) { output.write(frame); output.flush() }
        fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    companion object {
        const val DEFAULT_PORT = 8099
        const val MAX_QUEUED_FRAMES = 500
        private const val TAG = "RLT"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-5AB5DC11D65E"

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
