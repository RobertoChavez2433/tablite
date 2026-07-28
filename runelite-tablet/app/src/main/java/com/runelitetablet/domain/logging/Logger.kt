package com.runelitetablet.domain.logging

/**
 * Logging abstraction. Implementations: AppLog (production), PrintLogger (tests).
 * No Android imports — pure Kotlin interface.
 */
interface Logger {
    fun d(tag: String, message: String, correlationId: String? = null)
    fun i(tag: String, message: String, correlationId: String? = null)
    fun w(tag: String, message: String, correlationId: String? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null, correlationId: String? = null)

    // Convenience methods — delegate to core methods
    fun lifecycle(message: String, correlationId: String? = null) = i("LIFECYCLE", message, correlationId)
    fun step(stepId: String, message: String, correlationId: String? = null) = i("STEP", "[$stepId] $message", correlationId)
    fun cmd(execId: Int, message: String, correlationId: String? = null) = i("CMD", "[id=$execId] $message", correlationId)
    fun http(message: String, correlationId: String? = null) = i("HTTP", message, correlationId)
    fun install(message: String, correlationId: String? = null) = i("INSTALL", message, correlationId)
    fun cleanup(message: String, correlationId: String? = null) = i("CLEANUP", message, correlationId)
    fun script(message: String, correlationId: String? = null) = i("SCRIPT", message, correlationId)
    fun verify(message: String, correlationId: String? = null) = i("VERIFY", message, correlationId)
    fun state(message: String, correlationId: String? = null) = i("STATE", message, correlationId)
    fun ui(message: String, correlationId: String? = null) = i("UI", message, correlationId)
    fun perf(message: String, correlationId: String? = null) = i("PERF", message, correlationId)

    // 9.2 — Graphics/Pipeline logging methods
    fun hostVsync(callbackRate: Double, missedDeadlines: Int, p99Ms: Double, targetHz: Int, message: String, correlationId: String? = null) =
        i(
            "HOST_VSYNC",
            "host_vsync_callback_rate_hz=%.1f missed_deadlines=%d p99=%.1fms target_hz=%d %s"
                .format(callbackRate, missedDeadlines, p99Ms, targetHz, message),
            correlationId
        )

    fun jank(frameTimeMs: Double, expectedMs: Double, frameNumber: Long, message: String, correlationId: String? = null) =
        w("JANK", "frame=%d actual=%.1fms expected=%.1fms %s".format(frameNumber, frameTimeMs, expectedMs, message), correlationId)

    fun handoff(from: String, to: String, dataType: String, dataSizeBytes: Int, message: String, correlationId: String? = null) =
        i("HANDOFF", "$from->$to type=$dataType size=$dataSizeBytes $message", correlationId)

    fun surface(event: String, width: Int, height: Int, format: String, message: String, correlationId: String? = null) =
        i("SURFACE", "$event ${width}x$height fmt=$format $message", correlationId)

    fun ipc(direction: String, endpoint: String, latencyMs: Long, message: String, correlationId: String? = null) =
        i("IPC", "$direction $endpoint latency=${latencyMs}ms $message", correlationId)

    fun fd(operation: String, fd: Int, path: String, message: String, correlationId: String? = null) =
        i("FD", "$operation fd=$fd path=$path $message", correlationId)

    fun gl(operation: String, errorCode: Int?, message: String, correlationId: String? = null) =
        i("GL", "$operation err=$errorCode $message", correlationId)

    fun native(component: String, message: String, correlationId: String? = null) =
        i("NATIVE", "[$component] $message", correlationId)

    fun thread(from: String, to: String, operation: String, message: String, correlationId: String? = null) =
        i("THREAD", "$from->$to op=$operation $message", correlationId)

    fun buffer(operation: String, sizeBytes: Int, format: String, message: String, correlationId: String? = null) =
        i("BUFFER", "$operation size=$sizeBytes fmt=$format $message", correlationId)
}
