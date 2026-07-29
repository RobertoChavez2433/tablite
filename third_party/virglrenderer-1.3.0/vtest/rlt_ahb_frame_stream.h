#ifndef RLT_AHB_FRAME_STREAM_H
#define RLT_AHB_FRAME_STREAM_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "rlt_frame_stream.h"

/*
 * Same-UID AHardwareBuffer completed-frame handoff. When
 * RLT_FRAME_AHB_SOCKET is set, this function owns delivery and returns true,
 * even while the consumer is temporarily unavailable. When the environment
 * gate is absent it returns false so the TCP bridge may handle the frame.
 */
bool rlt_ahb_frame_stream_send(uint64_t frame_seq,
                               uint32_t width,
                               uint32_t height,
                               uint32_t stride_bytes,
                               enum rlt_frame_stream_format format,
                               const void *pixels,
                               size_t payload_bytes,
                               uint64_t transfer_done_ns);

/*
 * Direct GPU path for a completed VirGL display-target texture. Returns true
 * only after the texture has been copied into a shared AHardwareBuffer and the
 * consumer has been notified. A false result requires the caller to retain the
 * existing transfer/readback fallback.
 */
bool rlt_ahb_frame_stream_send_gpu(uint64_t frame_seq,
                                   uint32_t width,
                                   uint32_t height,
                                   uint32_t source_texture,
                                   bool source_y_0_top,
                                   uint64_t transfer_start_ns);

void rlt_ahb_frame_stream_close(void);

/* Startup-only visual/transport sentinel, gated by RLT_FRAME_AHB_PROBE=1. */
void rlt_ahb_frame_stream_probe(void);

#endif
