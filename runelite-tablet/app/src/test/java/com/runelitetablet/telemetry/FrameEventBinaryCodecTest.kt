package com.runelitetablet.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameEventBinaryCodecTest {
    @Test
    fun `binary record matches native ABI size and key offsets`() {
        val event = FrameEventV1(
            runId = "11223344-5566-7788-99aa-bbccddeeff00",
            backend = RendererBackend.NATIVE_X11_VIRGL.id,
            processId = 123,
            threadId = 456,
            frameSeq = 0x102030405060708L,
            bufferIndex = 2,
            poolGeneration = 3,
            platformFrameId = 99,
            stage = FrameStage.GLX_SWAP.id,
            eventType = FrameEventType.SPAN.id,
            outcome = FrameOutcome.SUBMITTED.id,
            beginMonotonicNs = 100,
            endMonotonicNs = 200
        )

        val bytes = FrameEventBinaryCodec.encode(event)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(FrameEventBinaryCodec.RECORD_SIZE, bytes.size)
        assertEquals(FRAME_EVENT_SCHEMA_VERSION, buffer.getShort(0).toInt())
        assertEquals(FrameEventBinaryCodec.RECORD_SIZE, buffer.getShort(2).toInt())
        assertEquals(event.processId, buffer.getInt(28))
        assertEquals(event.threadId, buffer.getInt(32))
        assertEquals(event.frameSeq, buffer.getLong(40))
        assertEquals(event.beginMonotonicNs, buffer.getLong(64))
        assertEquals(event.endMonotonicNs, buffer.getLong(72))
    }
}
