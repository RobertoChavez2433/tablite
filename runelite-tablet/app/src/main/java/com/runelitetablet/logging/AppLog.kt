package com.runelitetablet.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.runelitetablet.BuildConfig
import com.runelitetablet.domain.logging.Logger
import java.io.File

object AppLog : Logger {
    private const val TAG = "RLT"
    private val PERF_TAGS = setOf("FRAME", "HOST_VSYNC", "JANK", "SURFACE", "GL", "BUFFER")
    private var fileWriter: LogFileWriter = LogFileWriter(null)
    private var perfWriter: LogFileWriter = LogFileWriter(null)
    private var debugServer: DebugLogServer? = null
    private val startTime = SystemClock.elapsedRealtime()

    fun init(context: Context) {
        fileWriter.shutdown(); perfWriter.shutdown()
        val dir = File(context.filesDir, "logs")
        fileWriter = runCatching { LogFileWriter.initForSession(dir) }.getOrElse { LogFileWriter(null) }
        perfWriter = runCatching { PerfLogWriter.initForSession(dir) }.getOrElse { LogFileWriter(null) }
    }

    fun startDebugServer(server: DebugLogServer) { debugServer = server; server.start() }
    fun stopDebugServer() { debugServer?.stop(); debugServer = null }

    override fun d(tag: String, msg: String, correlationId: String?) = route("D", tag, msg, correlationId) { Log.d(TAG, it) }
    override fun i(tag: String, msg: String, correlationId: String?) = route("I", tag, msg, correlationId) { Log.i(TAG, it) }
    override fun w(tag: String, msg: String, correlationId: String?) = route("W", tag, msg, correlationId) { Log.w(TAG, it) }
    override fun e(tag: String, msg: String, throwable: Throwable?, correlationId: String?) {
        val line = fmt(tag, msg, correlationId)
        if (throwable != null) Log.e(TAG, line, throwable) else Log.e(TAG, line)
        if (BuildConfig.DEBUG) writer(tag).write(if (throwable != null) "$line\n${trunc(throwable)}" else line)
        emit("E", tag, msg, correlationId)
    }

    fun shell(tag: String, msg: String, cid: String? = null) {
        val line = fmt(tag, msg, cid); Log.i(TAG, line)
        if (BuildConfig.DEBUG) writer(tag).write(line); emit("I", tag, msg, cid, source = "shell")
    }

    private inline fun route(lvl: String, tag: String, msg: String, cid: String?, logcat: (String) -> Unit) {
        logcat(fmt(tag, msg, cid)); if (BuildConfig.DEBUG) writer(tag).write(fmt(tag, msg, cid)); emit(lvl, tag, msg, cid)
    }

    private fun writer(tag: String) = if (tag in PERF_TAGS) perfWriter else fileWriter

    private fun emit(lvl: String, tag: String, msg: String, cid: String?, source: String = "kotlin") {
        val s = debugServer ?: return; val c = Throwable().stackTrace.getOrNull(3)
        s.broadcast(s.buildLogJson(lvl, tag, msg, Thread.currentThread().name, source, c?.fileName ?: "", c?.lineNumber ?: 0, cid))
    }

    private fun fmt(prefix: String, msg: String, cid: String?): String {
        val corr = if (cid != null) " corr=$cid" else ""
        return "+${SystemClock.elapsedRealtime() - startTime}ms [$prefix] $msg$corr | thread=${Thread.currentThread().name}"
    }

    private fun trunc(t: Throwable): String {
        val frames = t.stackTrace.take(10).joinToString("\n") { "  at $it" }
        val more = (t.stackTrace.size - 10).let { if (it > 0) "\n  ... $it more" else "" }
        return "$frames$more"
    }
}
