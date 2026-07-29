package com.runelitetablet.telemetry

import kotlinx.serialization.Serializable

const val FRAME_EVENT_SCHEMA_VERSION = 1
const val CAPTURE_MANIFEST_SCHEMA_VERSION = 1

enum class RendererBackend(val id: Int, val envValue: String) {
    NATIVE_X11_VIRGL(1, "native_x11_virgl_gpu"),
    DIRECT_VIRGL_AHB(2, "direct_virgl_ahb"),
    DIRECT_ZINK_AHB(3, "direct_zink_ahb"),
    SOFTWARE_X11(4, "software_x11"),
    PROOT_X11_VIRGL(5, "proot_x11_virgl_gpu"),
    COMPOSITE_VIRGL_AHB(6, "composite_virgl_ahb");

    companion object {
        fun fromEnv(value: String?): RendererBackend =
            entries.firstOrNull { it.envValue.equals(value, ignoreCase = true) }
                ?: NATIVE_X11_VIRGL
    }
}

enum class TelemetryMode(val id: Int, val envValue: String) {
    OFF(0, "OFF"),
    SUMMARY(1, "SUMMARY"),
    BURST(2, "BURST"),
    DEEP(3, "DEEP");

    companion object {
        fun fromEnv(value: String?): TelemetryMode =
            entries.firstOrNull { it.envValue.equals(value, ignoreCase = true) }
                ?: SUMMARY
    }
}

/**
 * Numeric values are an append-only cross-language ABI. Never renumber an
 * existing value; native producers use the matching rlt_frame_trace.h values.
 */
enum class FrameStage(val id: Int) {
    UNKNOWN(0),
    PRODUCER_FRAME(10),
    AWT_JNI_LOCK(11),
    GL_FLUSH(12),
    GPU_FENCE(13),
    GLX_SWAP(14),
    VIRPIPE_WRITE(20),
    VIRPIPE_ACK(21),
    VIRGL_RECEIVE(30),
    VIRGL_DECODE(31),
    VIRGL_SUBMIT(32),
    VIRGL_RESOURCE_COPY(33),
    VIRGL_FENCE(34),
    X11_PUT_IMAGE(40),
    X11_DAMAGE(41),
    XLORIE_UPLOAD(42),
    XLORIE_DRAW(43),
    EGL_SWAP(44),
    EGL_PRESENT(45),
    DIRECT_FRAME_READY(50),
    SURFACE_CONTROL_SUBMIT(51),
    SURFACE_CONTROL_LATCH(52),
    DISPLAY_PRESENT(53),
    BUFFER_RELEASE(54),
    SCHEDULER(60),
    THERMAL(61),
    HOST_VSYNC_CALLBACK(62),
    ANDROID_JANK_CLASSIFICATION(63),
    RENDERER_HEARTBEAT(70),
    TELEMETRY(71);

    companion object {
        fun fromId(id: Int): FrameStage = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

enum class FrameEventType(val id: Int) {
    INSTANT(0),
    SPAN(1),
    COUNTER(2),
    HEARTBEAT(3);

    companion object {
        fun fromId(id: Int): FrameEventType = entries.firstOrNull { it.id == id } ?: INSTANT
    }
}

enum class FrameOutcome(val id: Int) {
    UNKNOWN(0),
    PRODUCED(1),
    ACCEPTED(2),
    SUBMITTED(3),
    PRESENTED(4),
    DUPLICATE(5),
    DROP_SUPERSEDED(6),
    DROP_DOWNSTREAM(7),
    STARVED(8),
    TIMED_OUT(9),
    UNSUPPORTED(10),
    TELEMETRY_LOST(11),
    ERROR(12);

    companion object {
        fun fromId(id: Int): FrameOutcome = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

enum class BottleneckClass(val id: Int) {
    UNKNOWN(0),
    PRODUCER_CPU(1),
    JVM_PAUSE(2),
    SCHEDULER(3),
    VIRGL_TRANSPORT(4),
    GPU_EXECUTION(5),
    X11_COPY(6),
    CONSUMER_FENCE(7),
    BUFFER_QUEUE_COMPOSITOR(8),
    THERMAL(9);
}

@Serializable
data class FrameEventV1(
    val schemaVersion: Int = FRAME_EVENT_SCHEMA_VERSION,
    val runId: String,
    val backend: Int,
    val processId: Int,
    val threadId: Int,
    val frameSeq: Long,
    val bufferIndex: Int = -1,
    val poolGeneration: Int = 0,
    val platformFrameId: Long = -1,
    val stage: Int,
    val eventType: Int,
    val outcome: Int,
    val beginMonotonicNs: Long,
    val endMonotonicNs: Long,
    val threadCpuNs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val strideBytes: Int = 0,
    val damagedPixels: Long = 0,
    val bytesTransferred: Long = 0,
    val commandCount: Int = 0,
    val queueDepth: Int = 0,
    val configurationHash: Long = 0,
    val flags: Long = 0
) {
    val durationNs: Long
        get() = (endMonotonicNs - beginMonotonicNs).coerceAtLeast(0)
}

@Serializable
data class TelemetrySummaryV1(
    val schemaVersion: Int = FRAME_EVENT_SCHEMA_VERSION,
    val runId: String,
    val backend: Int,
    val mode: Int,
    val recordedEvents: Long,
    val lostEvents: Long,
    val queueHighWaterMark: Long,
    val telemetryCpuNs: Long,
    val stageCounts: Map<Int, Long>,
    val outcomeCounts: Map<Int, Long>,
    val durationHistogram: List<Long>
)

@Serializable
data class TelemetryStageCostV1(
    val stage: String,
    val calls: Long,
    val measuredSamples: Long,
    val measuredWallNs: Long,
    val measuredThreadCpuNs: Long,
    val estimatedWallNs: Long,
    val estimatedThreadCpuNs: Long,
    val bytes: Long,
    val dropped: Long,
    val queueHighWaterMark: Long
)

@Serializable
data class TelemetryPipelineCostV1(
    val schemaVersion: Int = 1,
    val runId: String,
    val mode: String,
    val measurementStride: Int,
    val stages: List<TelemetryStageCostV1>
)
