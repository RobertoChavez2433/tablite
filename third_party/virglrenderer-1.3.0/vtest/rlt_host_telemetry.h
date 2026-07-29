#ifndef RLT_HOST_TELEMETRY_H
#define RLT_HOST_TELEMETRY_H

#include <stdbool.h>
#include <stdint.h>

enum rlt_host_stage {
   RLT_HOST_SUBMIT_RECEIVE,
   RLT_HOST_SUBMIT_DECODE,
   RLT_HOST_SUBMIT_DRAW,
   RLT_HOST_SUBMIT_TRANSFER,
   RLT_HOST_SUBMIT_OBJECT,
   RLT_HOST_SUBMIT_STATE,
   RLT_HOST_SUBMIT_QUERY_SYNC,
   RLT_HOST_SUBMIT_OTHER,
   RLT_HOST_FENCE_CREATE,
   RLT_HOST_TRANSFER_RECEIVE,
   RLT_HOST_TRANSFER_EXEC,
   RLT_HOST_TRANSFER_PREPARE,
   RLT_HOST_TRANSFER_FRAMEBUFFER_SETUP,
   RLT_HOST_TRANSFER_READ_PIXELS,
   RLT_HOST_TRANSFER_FRAMEBUFFER_CLEANUP,
   RLT_HOST_TRANSFER_POSTPROCESS,
   RLT_HOST_BUSY_RECEIVE,
   RLT_HOST_FENCE_WAIT,
   RLT_HOST_POLL_FD_WAIT,
   RLT_HOST_RENDERER_POLL,
   RLT_HOST_RESPONSE_WRITE,
   RLT_HOST_BEGIN_TO_RECEIVE,
   RLT_HOST_MARKER_RECEIVE,
   RLT_HOST_STAGE_COUNT,
};

struct rlt_host_scope {
   uint64_t producer_seq;
   uint64_t producer_begin_ns;
   uint64_t start_ns;
};

uint64_t rlt_host_now_ns(void);
void rlt_host_set_remote_frame(uint64_t producer_seq,
                               uint64_t producer_begin_ns);
struct rlt_host_scope rlt_host_scope_begin(void);
uint64_t rlt_host_stage_begin(const struct rlt_host_scope *scope);
void rlt_host_stage_end(const struct rlt_host_scope *scope,
                        enum rlt_host_stage stage,
                        uint64_t started_ns,
                        uint64_t bytes);
void rlt_host_accumulate(const struct rlt_host_scope *scope,
                         enum rlt_host_stage stage,
                         uint64_t elapsed_ns,
                         uint64_t bytes);
void rlt_host_transfer_detail_begin(const struct rlt_host_scope *scope);
void rlt_host_transfer_detail_end(void);
void rlt_host_transfer_observer(void *cookie, unsigned phase, bool begin);
void rlt_host_submit_detail_begin(const struct rlt_host_scope *scope);
void rlt_host_submit_detail_end(void);
void rlt_host_submit_observer(void *cookie, unsigned phase, bool begin);
void rlt_host_complete_busy_wait(const struct rlt_host_scope *scope,
                                 uint32_t queue_depth);

#endif
