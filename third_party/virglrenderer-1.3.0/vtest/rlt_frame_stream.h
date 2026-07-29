#ifndef RLT_FRAME_STREAM_H
#define RLT_FRAME_STREAM_H

#include <stddef.h>
#include <stdint.h>

#define RLT_FRAME_STREAM_MAGIC 0x52544653u
#define RLT_FRAME_STREAM_VERSION 1u
#define RLT_FRAME_STREAM_RUN_ID_MAX 64u

enum rlt_frame_stream_format {
   RLT_FRAME_STREAM_FORMAT_BGRA8 = 1,
   RLT_FRAME_STREAM_FORMAT_RGBA8 = 2,
};

struct rlt_frame_stream_header {
   uint32_t magic;
   uint32_t version;
   uint32_t header_bytes;
   uint32_t format;
   uint64_t frame_seq;
   uint32_t width;
   uint32_t height;
   uint32_t stride_bytes;
   uint32_t payload_bytes;
   uint64_t transfer_done_ns;
   uint64_t send_start_ns;
   char run_id[RLT_FRAME_STREAM_RUN_ID_MAX];
};

_Static_assert(sizeof(struct rlt_frame_stream_header) == 120,
               "rlt_frame_stream_header ABI changed");

/*
 * Experimental completed-frame bridge. Disabled unless RLT_FRAME_TCP_PORT is
 * set. Failures are non-fatal to vtest and reconnects are retried so an app
 * receiver may start after the renderer.
 */
void rlt_frame_stream_send(uint64_t frame_seq,
                           uint32_t width,
                           uint32_t height,
                           uint32_t stride_bytes,
                           enum rlt_frame_stream_format format,
                           const void *pixels,
                           size_t payload_bytes,
                           uint64_t transfer_done_ns);

void rlt_frame_stream_close(void);

#endif
