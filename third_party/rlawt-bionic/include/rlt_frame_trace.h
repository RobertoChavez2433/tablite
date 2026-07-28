/*
 * Tablite frame telemetry ABI shared by native producers and the Kotlin
 * FrameEventV1 model. Numeric enum values are append-only.
 */
#ifndef RLT_FRAME_TRACE_H
#define RLT_FRAME_TRACE_H

#include <stdint.h>

#define RLT_FRAME_EVENT_SCHEMA_VERSION 1u
#define RLT_RUN_ID_BYTES 16u

enum rlt_renderer_backend {
    RLT_BACKEND_NATIVE_X11_VIRGL = 1,
    RLT_BACKEND_DIRECT_VIRGL_AHB = 2,
    RLT_BACKEND_DIRECT_ZINK_AHB = 3,
    RLT_BACKEND_SOFTWARE_X11 = 4,
};

enum rlt_telemetry_mode {
    RLT_TELEMETRY_OFF = 0,
    RLT_TELEMETRY_SUMMARY = 1,
    RLT_TELEMETRY_BURST = 2,
    RLT_TELEMETRY_DEEP = 3,
};

enum rlt_frame_stage {
    RLT_STAGE_UNKNOWN = 0,
    RLT_STAGE_PRODUCER_FRAME = 10,
    RLT_STAGE_AWT_JNI_LOCK = 11,
    RLT_STAGE_GL_FLUSH = 12,
    RLT_STAGE_GPU_FENCE = 13,
    RLT_STAGE_GLX_SWAP = 14,
    RLT_STAGE_VIRPIPE_WRITE = 20,
    RLT_STAGE_VIRPIPE_ACK = 21,
    RLT_STAGE_VIRGL_RECEIVE = 30,
    RLT_STAGE_VIRGL_DECODE = 31,
    RLT_STAGE_VIRGL_SUBMIT = 32,
    RLT_STAGE_VIRGL_RESOURCE_COPY = 33,
    RLT_STAGE_VIRGL_FENCE = 34,
    RLT_STAGE_X11_PUT_IMAGE = 40,
    RLT_STAGE_X11_DAMAGE = 41,
    RLT_STAGE_XLORIE_UPLOAD = 42,
    RLT_STAGE_XLORIE_DRAW = 43,
    RLT_STAGE_EGL_SWAP = 44,
    RLT_STAGE_EGL_PRESENT = 45,
    RLT_STAGE_DIRECT_FRAME_READY = 50,
    RLT_STAGE_SURFACE_CONTROL_SUBMIT = 51,
    RLT_STAGE_SURFACE_CONTROL_LATCH = 52,
    RLT_STAGE_DISPLAY_PRESENT = 53,
    RLT_STAGE_BUFFER_RELEASE = 54,
    RLT_STAGE_SCHEDULER = 60,
    RLT_STAGE_THERMAL = 61,
    RLT_STAGE_HOST_VSYNC_CALLBACK = 62,
    RLT_STAGE_ANDROID_JANK_CLASSIFICATION = 63,
    RLT_STAGE_RENDERER_HEARTBEAT = 70,
    RLT_STAGE_TELEMETRY = 71,
};

enum rlt_frame_event_type {
    RLT_EVENT_INSTANT = 0,
    RLT_EVENT_SPAN = 1,
    RLT_EVENT_COUNTER = 2,
    RLT_EVENT_HEARTBEAT = 3,
};

enum rlt_frame_outcome {
    RLT_OUTCOME_UNKNOWN = 0,
    RLT_OUTCOME_PRODUCED = 1,
    RLT_OUTCOME_ACCEPTED = 2,
    RLT_OUTCOME_SUBMITTED = 3,
    RLT_OUTCOME_PRESENTED = 4,
    RLT_OUTCOME_DUPLICATE = 5,
    RLT_OUTCOME_DROP_SUPERSEDED = 6,
    RLT_OUTCOME_DROP_DOWNSTREAM = 7,
    RLT_OUTCOME_STARVED = 8,
    RLT_OUTCOME_TIMED_OUT = 9,
    RLT_OUTCOME_UNSUPPORTED = 10,
    RLT_OUTCOME_TELEMETRY_LOST = 11,
    RLT_OUTCOME_ERROR = 12,
};

/*
 * Fixed-size binary record. Writers use CLOCK_MONOTONIC and must not write
 * this struct directly to disk from a render/presentation thread.
 */
struct rlt_frame_event_v1 {
    uint16_t schema_version;
    uint16_t record_size;
    uint8_t run_id[RLT_RUN_ID_BYTES];
    uint16_t backend;
    uint16_t stage;
    uint16_t event_type;
    uint16_t outcome;
    int32_t process_id;
    int32_t thread_id;
    uint32_t reserved_alignment;
    uint64_t frame_seq;
    int32_t buffer_index;
    uint32_t pool_generation;
    int64_t platform_frame_id;
    uint64_t begin_monotonic_ns;
    uint64_t end_monotonic_ns;
    uint64_t thread_cpu_ns;
    uint32_t width;
    uint32_t height;
    uint32_t stride_bytes;
    uint32_t command_count;
    uint32_t queue_depth;
    uint32_t reserved;
    uint64_t damaged_pixels;
    uint64_t bytes_transferred;
    uint64_t configuration_hash;
    uint64_t flags;
};

#if defined(__cplusplus)
static_assert(sizeof(struct rlt_frame_event_v1) == 144, "rlt_frame_event_v1 ABI changed");
#else
_Static_assert(sizeof(struct rlt_frame_event_v1) == 144, "rlt_frame_event_v1 ABI changed");
#endif

#endif /* RLT_FRAME_TRACE_H */
