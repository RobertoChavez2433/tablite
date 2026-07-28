package com.runelitetablet.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Little-endian encoding matching native struct rlt_frame_event_v1.
 */
object FrameEventBinaryCodec {
    const val RECORD_SIZE = 144

    fun encode(event: FrameEventV1): ByteArray {
        val bytes = ByteArray(RECORD_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(event.schemaVersion.toShort())
        buffer.putShort(RECORD_SIZE.toShort())
        putRunId(buffer, event.runId)
        buffer.putShort(event.backend.toShort())
        buffer.putShort(event.stage.toShort())
        buffer.putShort(event.eventType.toShort())
        buffer.putShort(event.outcome.toShort())
        buffer.putInt(event.processId)
        buffer.putInt(event.threadId)
        buffer.putInt(0) // reserved_alignment keeps the native ABI stable on 32/64-bit
        buffer.putLong(event.frameSeq)
        buffer.putInt(event.bufferIndex)
        buffer.putInt(event.poolGeneration)
        buffer.putLong(event.platformFrameId)
        buffer.putLong(event.beginMonotonicNs)
        buffer.putLong(event.endMonotonicNs)
        buffer.putLong(event.threadCpuNs)
        buffer.putInt(event.width)
        buffer.putInt(event.height)
        buffer.putInt(event.strideBytes)
        buffer.putInt(event.commandCount)
        buffer.putInt(event.queueDepth)
        buffer.putInt(0)
        buffer.putLong(event.damagedPixels)
        buffer.putLong(event.bytesTransferred)
        buffer.putLong(event.configurationHash)
        buffer.putLong(event.flags)
        check(buffer.position() == RECORD_SIZE)
        return bytes
    }

    private fun putRunId(buffer: ByteBuffer, value: String) {
        val uuid = runCatching { UUID.fromString(value) }.getOrNull()
        if (uuid != null) {
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
        } else {
            val source = value.encodeToByteArray()
            repeat(16) { index -> buffer.put(source.getOrElse(index) { 0 }) }
        }
    }
}
