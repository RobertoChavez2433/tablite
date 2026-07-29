#include "rlt_host_telemetry.h"

#include <fcntl.h>
#include <inttypes.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "virglrenderer.h"
#include "rlt_frame_telemetry.h"

#define RLT_HOST_WINDOW 30U

struct rlt_host_accumulator {
   uint64_t producer_seq;
   uint64_t elapsed_ns[RLT_HOST_STAGE_COUNT];
   uint32_t calls[RLT_HOST_STAGE_COUNT];
   uint64_t submit_bytes;
   uint64_t marker_bytes;
   uint32_t queue_depth;
   bool saw_completion;
};

struct rlt_host_sample {
   uint64_t producer_seq;
   uint32_t elapsed_us[RLT_HOST_STAGE_COUNT];
   uint32_t calls[RLT_HOST_STAGE_COUNT];
   uint64_t submit_bytes;
   uint64_t marker_bytes;
   uint32_t queue_depth;
};

struct rlt_host_state {
   int telemetry_fd;
   const struct rlt_frame_telemetry_v1 *page;
   const char *telemetry_path;
   bool configuration_checked;
   bool self_profile_enabled;
   bool remote_marker_announced;
   bool remote_frame_active;
   uint64_t remote_producer_seq;
   uint64_t remote_producer_begin_ns;
   uint64_t self_calls;
   uint64_t self_wall_ns;
   uint64_t self_thread_cpu_ns;
   struct rlt_host_accumulator accumulator;
   struct rlt_host_sample samples[RLT_HOST_WINDOW];
   uint32_t sample_count;
   uint32_t incomplete_frames;
   uint32_t unattributed_events;
};

static struct rlt_host_state rlt_host = {
   .telemetry_fd = -1,
};

struct rlt_host_transfer_detail {
   struct rlt_host_scope scope;
   uint64_t started_ns[VIRGL_RENDERER_TRANSFER_PHASE_COUNT];
   bool active;
};

static _Thread_local struct rlt_host_transfer_detail rlt_transfer_detail;

struct rlt_host_submit_detail {
   struct rlt_host_scope scope;
   uint64_t started_ns[VIRGL_RENDERER_SUBMIT_PHASE_COUNT];
   bool active;
};

static _Thread_local struct rlt_host_submit_detail rlt_submit_detail;

static void rlt_host_finish_accumulator(void);

static uint64_t
rlt_host_clock_ns(clockid_t clock_id)
{
   struct timespec ts;
   if (clock_gettime(clock_id, &ts) != 0)
      return 0;
   return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void
rlt_host_self_end(uint64_t wall_started_ns, uint64_t cpu_started_ns)
{
   uint64_t wall_ended_ns;
   uint64_t cpu_ended_ns;

   if (!wall_started_ns)
      return;
   wall_ended_ns = rlt_host_clock_ns(CLOCK_MONOTONIC);
   cpu_ended_ns = rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID);
   rlt_host.self_calls++;
   if (wall_ended_ns >= wall_started_ns)
      rlt_host.self_wall_ns += wall_ended_ns - wall_started_ns;
   if (cpu_started_ns && cpu_ended_ns >= cpu_started_ns)
      rlt_host.self_thread_cpu_ns += cpu_ended_ns - cpu_started_ns;
}

uint64_t
rlt_host_now_ns(void)
{
   struct timespec ts;
   if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
      return 0;
   return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void
rlt_host_init(void)
{
   const char *path;
   struct stat stat_buf;
   void *mapping;

   if (rlt_host.page)
      return;

   if (!rlt_host.configuration_checked) {
      path = getenv("RLT_VTEST_TELEMETRY_PATH");
      rlt_host.telemetry_path = path && *path ? path : NULL;
      path = getenv("RLT_TELEMETRY_SELF_PROFILE");
      rlt_host.self_profile_enabled =
         path && *path && !(path[0] == '0' && path[1] == '\0');
      rlt_host.configuration_checked = true;
   }
   path = rlt_host.telemetry_path;
   if (!path)
      return;

   rlt_host.telemetry_fd = open(path, O_RDONLY | O_CLOEXEC);
   if (rlt_host.telemetry_fd < 0)
      return;
   if (fstat(rlt_host.telemetry_fd, &stat_buf) != 0 ||
       stat_buf.st_size != RLT_FRAME_TELEMETRY_SIZE)
      goto fail;

   mapping = mmap(NULL, RLT_FRAME_TELEMETRY_SIZE, PROT_READ, MAP_SHARED,
                  rlt_host.telemetry_fd, 0);
   if (mapping == MAP_FAILED)
      goto fail;

   rlt_host.page = mapping;
   if (rlt_host.page->magic != RLT_FRAME_TELEMETRY_MAGIC ||
       rlt_host.page->abi_version != RLT_FRAME_TELEMETRY_ABI_VERSION ||
       rlt_host.page->struct_size != RLT_FRAME_TELEMETRY_SIZE) {
      munmap((void *)rlt_host.page, RLT_FRAME_TELEMETRY_SIZE);
      rlt_host.page = NULL;
      goto fail;
   }

   fprintf(stderr, "RLT-VIRGL-HOST: frame telemetry ABI v1 enabled\n");
   return;

fail:
   close(rlt_host.telemetry_fd);
   rlt_host.telemetry_fd = -1;
}

void
rlt_host_set_remote_frame(uint64_t producer_seq,
                          uint64_t producer_begin_ns)
{
   uint64_t received_ns;
   uint64_t self_wall_started_ns;
   uint64_t self_cpu_started_ns;

   rlt_host_init();
   received_ns = rlt_host_now_ns();
   self_wall_started_ns = rlt_host.self_profile_enabled ? received_ns : 0;
   self_cpu_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID) : 0;
   if (!producer_seq || !producer_begin_ns ||
       producer_begin_ns > received_ns) {
      rlt_host.remote_frame_active = false;
      rlt_host.unattributed_events++;
      rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
      return;
   }

   if (rlt_host.accumulator.producer_seq &&
       rlt_host.accumulator.producer_seq != producer_seq)
      rlt_host_finish_accumulator();

   rlt_host.remote_producer_seq = producer_seq;
   rlt_host.remote_producer_begin_ns = producer_begin_ns;
   rlt_host.remote_frame_active = true;
   if (!rlt_host.remote_marker_announced) {
      fprintf(stderr,
              "RLT-VIRGL-HOST: remote frame marker enabled\n");
      rlt_host.remote_marker_announced = true;
   }
   rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
}

struct rlt_host_scope
rlt_host_scope_begin(void)
{
   struct rlt_host_scope scope = {0};
   uint64_t self_wall_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_MONOTONIC) : 0;
   uint64_t self_cpu_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID) : 0;

   rlt_host_init();
   scope.start_ns = rlt_host_now_ns();
   if (rlt_host.page) {
      scope.producer_seq = atomic_load_explicit(
         &rlt_host.page->active_producer_seq, memory_order_acquire);
      scope.producer_begin_ns = atomic_load_explicit(
         &rlt_host.page->active_begin_monotonic_ns, memory_order_acquire);
   } else if (rlt_host.remote_frame_active) {
      scope.producer_seq = rlt_host.remote_producer_seq;
      scope.producer_begin_ns = rlt_host.remote_producer_begin_ns;
   }
   if (!scope.producer_seq || !scope.producer_begin_ns) {
      rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
      return scope;
   }

   if (scope.producer_seq && rlt_host.accumulator.producer_seq &&
       rlt_host.accumulator.producer_seq != scope.producer_seq)
      rlt_host_finish_accumulator();
   rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
   return scope;
}

uint64_t
rlt_host_stage_begin(const struct rlt_host_scope *scope)
{
   uint64_t self_wall_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_MONOTONIC) : 0;
   uint64_t self_cpu_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID) : 0;
   uint64_t started_ns = scope->producer_seq ? rlt_host_now_ns() : 0;
   rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
   return started_ns;
}

void
rlt_host_stage_end(const struct rlt_host_scope *scope,
                   enum rlt_host_stage stage,
                   uint64_t started_ns,
                   uint64_t bytes)
{
   uint64_t self_wall_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_MONOTONIC) : 0;
   uint64_t self_cpu_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID) : 0;
   if (!started_ns) {
      rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
      return;
   }
   rlt_host_accumulate(scope, stage, rlt_host_now_ns() - started_ns, bytes);
   rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
}

static bool
rlt_host_scope_still_active(const struct rlt_host_scope *scope)
{
   if (!scope->producer_seq || !scope->producer_begin_ns)
      return false;
   if (rlt_host.page)
      return atomic_load_explicit(&rlt_host.page->active_producer_seq,
                                  memory_order_acquire) ==
             scope->producer_seq;
   return rlt_host.remote_frame_active &&
          rlt_host.remote_producer_seq == scope->producer_seq;
}

static struct rlt_host_accumulator *
rlt_host_accumulator_for(const struct rlt_host_scope *scope)
{
   if (!scope->producer_seq)
      return NULL;
   if (rlt_host.accumulator.producer_seq != scope->producer_seq) {
      memset(&rlt_host.accumulator, 0, sizeof(rlt_host.accumulator));
      rlt_host.accumulator.producer_seq = scope->producer_seq;
   }
   return &rlt_host.accumulator;
}

void
rlt_host_accumulate(const struct rlt_host_scope *scope,
                    enum rlt_host_stage stage,
                    uint64_t elapsed_ns,
                    uint64_t bytes)
{
   struct rlt_host_accumulator *accumulator;

   if (stage >= RLT_HOST_STAGE_COUNT)
      return;
   accumulator = rlt_host_accumulator_for(scope);
   if (!accumulator)
      return;
   if (!accumulator->calls[RLT_HOST_BEGIN_TO_RECEIVE] &&
       scope->start_ns >= scope->producer_begin_ns) {
      accumulator->elapsed_ns[RLT_HOST_BEGIN_TO_RECEIVE] =
         scope->start_ns - scope->producer_begin_ns;
      accumulator->calls[RLT_HOST_BEGIN_TO_RECEIVE] = 1;
   }
   accumulator->elapsed_ns[stage] += elapsed_ns;
   accumulator->calls[stage]++;
   if (stage == RLT_HOST_SUBMIT_RECEIVE)
      accumulator->submit_bytes += bytes;
   else if (stage == RLT_HOST_MARKER_RECEIVE)
      accumulator->marker_bytes += bytes;
}

void
rlt_host_transfer_detail_begin(const struct rlt_host_scope *scope)
{
   memset(&rlt_transfer_detail, 0, sizeof(rlt_transfer_detail));
   if (!scope->producer_seq)
      return;
   rlt_transfer_detail.scope = *scope;
   rlt_transfer_detail.active = true;
}

void
rlt_host_transfer_detail_end(void)
{
   memset(&rlt_transfer_detail, 0, sizeof(rlt_transfer_detail));
}

void
rlt_host_transfer_observer(void *cookie, unsigned phase, bool begin)
{
   static const enum rlt_host_stage stages[] = {
      RLT_HOST_TRANSFER_PREPARE,
      RLT_HOST_TRANSFER_FRAMEBUFFER_SETUP,
      RLT_HOST_TRANSFER_READ_PIXELS,
      RLT_HOST_TRANSFER_FRAMEBUFFER_CLEANUP,
      RLT_HOST_TRANSFER_POSTPROCESS,
   };
   uint64_t started_ns;

   (void)cookie;
   if (!rlt_transfer_detail.active ||
       phase >= VIRGL_RENDERER_TRANSFER_PHASE_COUNT)
      return;
   if (begin) {
      rlt_transfer_detail.started_ns[phase] =
         rlt_host_stage_begin(&rlt_transfer_detail.scope);
      return;
   }

   started_ns = rlt_transfer_detail.started_ns[phase];
   rlt_transfer_detail.started_ns[phase] = 0;
   rlt_host_stage_end(
      &rlt_transfer_detail.scope, stages[phase], started_ns, 0);
}

void
rlt_host_submit_detail_begin(const struct rlt_host_scope *scope)
{
   memset(&rlt_submit_detail, 0, sizeof(rlt_submit_detail));
   if (!scope->producer_seq)
      return;
   rlt_submit_detail.scope = *scope;
   rlt_submit_detail.active = true;
}

void
rlt_host_submit_detail_end(void)
{
   memset(&rlt_submit_detail, 0, sizeof(rlt_submit_detail));
}

void
rlt_host_submit_observer(void *cookie, unsigned phase, bool begin)
{
   static const enum rlt_host_stage stages[] = {
      RLT_HOST_SUBMIT_DRAW,
      RLT_HOST_SUBMIT_TRANSFER,
      RLT_HOST_SUBMIT_OBJECT,
      RLT_HOST_SUBMIT_STATE,
      RLT_HOST_SUBMIT_QUERY_SYNC,
      RLT_HOST_SUBMIT_OTHER,
   };
   uint64_t started_ns;

   (void)cookie;
   if (!rlt_submit_detail.active ||
       phase >= VIRGL_RENDERER_SUBMIT_PHASE_COUNT)
      return;
   if (begin) {
      rlt_submit_detail.started_ns[phase] =
         rlt_host_stage_begin(&rlt_submit_detail.scope);
      return;
   }

   started_ns = rlt_submit_detail.started_ns[phase];
   rlt_submit_detail.started_ns[phase] = 0;
   rlt_host_stage_end(
      &rlt_submit_detail.scope, stages[phase], started_ns, 0);
}

static int
rlt_host_compare_u32(const void *left, const void *right)
{
   const uint32_t a = *(const uint32_t *)left;
   const uint32_t b = *(const uint32_t *)right;
   return (a > b) - (a < b);
}

static uint32_t
rlt_host_metric(enum rlt_host_stage stage, unsigned percentile)
{
   uint32_t values[RLT_HOST_WINDOW];
   uint32_t index;

   for (uint32_t i = 0; i < RLT_HOST_WINDOW; i++)
      values[i] = rlt_host.samples[i].elapsed_us[stage];
   qsort(values, RLT_HOST_WINDOW, sizeof(values[0]), rlt_host_compare_u32);
   index = (RLT_HOST_WINDOW * percentile + 99U) / 100U;
   return values[index ? index - 1U : 0U];
}

static uint32_t
rlt_host_max(enum rlt_host_stage stage)
{
   return rlt_host_metric(stage, 100U);
}

static uint64_t
rlt_host_mean(enum rlt_host_stage stage)
{
   uint64_t sum = 0;
   for (uint32_t i = 0; i < RLT_HOST_WINDOW; i++)
      sum += rlt_host.samples[i].elapsed_us[stage];
   return sum / RLT_HOST_WINDOW;
}

static const char *
rlt_host_run_id(void)
{
   const char *run_id = getenv("RLT_CAPTURE_ID");
   if (!run_id || !*run_id)
      return "unknown";
   for (const char *cursor = run_id; *cursor; cursor++) {
      if ((size_t)(cursor - run_id) >= 64 ||
          !(('a' <= *cursor && *cursor <= 'z') ||
            ('A' <= *cursor && *cursor <= 'Z') ||
            ('0' <= *cursor && *cursor <= '9') ||
            *cursor == '-' || *cursor == '_'))
         return "invalid";
   }
   return run_id;
}

static void
rlt_host_emit_window(void)
{
   uint64_t queue_sum = 0;
   uint32_t queue_max = 0;
   uint64_t submit_bytes = 0;
   uint64_t marker_bytes = 0;
   uint32_t submit_calls = 0;

   for (uint32_t i = 0; i < RLT_HOST_WINDOW; i++) {
      queue_sum += rlt_host.samples[i].queue_depth;
      if (rlt_host.samples[i].queue_depth > queue_max)
         queue_max = rlt_host.samples[i].queue_depth;
      submit_bytes += rlt_host.samples[i].submit_bytes;
      marker_bytes += rlt_host.samples[i].marker_bytes;
      submit_calls += rlt_host.samples[i].calls[RLT_HOST_SUBMIT_RECEIVE];
   }

   fprintf(stderr,
      "RLT_VIRGL_HOST_V1 run_id=%s stage=host_completion outcome=measured "
      "first_seq=%" PRIu64 " last_seq=%" PRIu64
      " frame_count=%u attribution_pct=100.000 incomplete_frames=%u "
      "unattributed_events=%u submit_calls=%u submit_bytes=%" PRIu64
      " marker_bytes=%" PRIu64
      ,
      rlt_host_run_id(),
      rlt_host.samples[0].producer_seq,
      rlt_host.samples[RLT_HOST_WINDOW - 1U].producer_seq,
      RLT_HOST_WINDOW,
      rlt_host.incomplete_frames,
      rlt_host.unattributed_events,
      submit_calls,
      submit_bytes,
      marker_bytes);
#define RLT_PRINT_METRICS(name, stage) \
   fprintf(stderr, \
      " " name "_us_mean=%" PRIu64 " " name "_us_p50=%u " \
      name "_us_p95=%u " name "_us_max=%u", \
      rlt_host_mean(stage), rlt_host_metric(stage, 50U), \
      rlt_host_metric(stage, 95U), rlt_host_max(stage))
   RLT_PRINT_METRICS("submit_receive", RLT_HOST_SUBMIT_RECEIVE);
   RLT_PRINT_METRICS("submit_decode", RLT_HOST_SUBMIT_DECODE);
   RLT_PRINT_METRICS("submit_draw", RLT_HOST_SUBMIT_DRAW);
   RLT_PRINT_METRICS("submit_transfer", RLT_HOST_SUBMIT_TRANSFER);
   RLT_PRINT_METRICS("submit_object", RLT_HOST_SUBMIT_OBJECT);
   RLT_PRINT_METRICS("submit_state", RLT_HOST_SUBMIT_STATE);
   RLT_PRINT_METRICS("submit_query_sync", RLT_HOST_SUBMIT_QUERY_SYNC);
   RLT_PRINT_METRICS("submit_other", RLT_HOST_SUBMIT_OTHER);
   RLT_PRINT_METRICS("fence_create", RLT_HOST_FENCE_CREATE);
   RLT_PRINT_METRICS("transfer_receive", RLT_HOST_TRANSFER_RECEIVE);
   RLT_PRINT_METRICS("transfer_exec", RLT_HOST_TRANSFER_EXEC);
   RLT_PRINT_METRICS("transfer_prepare", RLT_HOST_TRANSFER_PREPARE);
   RLT_PRINT_METRICS("transfer_framebuffer_setup",
                     RLT_HOST_TRANSFER_FRAMEBUFFER_SETUP);
   RLT_PRINT_METRICS("transfer_read_pixels",
                     RLT_HOST_TRANSFER_READ_PIXELS);
   RLT_PRINT_METRICS("transfer_framebuffer_cleanup",
                     RLT_HOST_TRANSFER_FRAMEBUFFER_CLEANUP);
   RLT_PRINT_METRICS("transfer_postprocess",
                     RLT_HOST_TRANSFER_POSTPROCESS);
   RLT_PRINT_METRICS("busy_receive", RLT_HOST_BUSY_RECEIVE);
   RLT_PRINT_METRICS("fence_wait", RLT_HOST_FENCE_WAIT);
   RLT_PRINT_METRICS("poll_fd_wait", RLT_HOST_POLL_FD_WAIT);
   RLT_PRINT_METRICS("renderer_poll", RLT_HOST_RENDERER_POLL);
   RLT_PRINT_METRICS("response_write", RLT_HOST_RESPONSE_WRITE);
   RLT_PRINT_METRICS("begin_to_receive", RLT_HOST_BEGIN_TO_RECEIVE);
   RLT_PRINT_METRICS("marker_receive", RLT_HOST_MARKER_RECEIVE);
#undef RLT_PRINT_METRICS
   fprintf(stderr, " queue_depth_mean=%" PRIu64 " queue_depth_max=%u\n",
           queue_sum / RLT_HOST_WINDOW, queue_max);
   if (rlt_host.self_profile_enabled) {
      const char *mode = getenv("RLT_TELEMETRY_MODE");
      fprintf(stderr,
         "RLT_TELEMETRY_COST_V1 run_id=%s mode=%s stage=virgl_host "
         "outcome=measured calls=%" PRIu64 " measured_samples=%" PRIu64
         " measured_wall_ns=%" PRIu64 " measured_thread_cpu_ns=%" PRIu64
         " bytes=%" PRIu64 " dropped=%u queue_high_water_mark=%u "
         "incomplete_frames=%u unattributed_events=%u "
         "record_emit_excluded=1\n",
         rlt_host_run_id(),
         mode && *mode ? mode : "unknown",
         rlt_host.self_calls,
         rlt_host.self_calls,
         rlt_host.self_wall_ns,
         rlt_host.self_thread_cpu_ns,
         submit_bytes + marker_bytes,
         0U,
         queue_max,
         rlt_host.incomplete_frames,
         rlt_host.unattributed_events);
   }
   fflush(stderr);
   rlt_host.self_calls = 0;
   rlt_host.self_wall_ns = 0;
   rlt_host.self_thread_cpu_ns = 0;
   rlt_host.sample_count = 0;
   rlt_host.incomplete_frames = 0;
   rlt_host.unattributed_events = 0;
}

static void
rlt_host_finish_accumulator(void)
{
   struct rlt_host_accumulator *accumulator = &rlt_host.accumulator;
   struct rlt_host_sample *sample;

   if (!accumulator->producer_seq)
      return;
   if (!accumulator->saw_completion) {
      rlt_host.incomplete_frames++;
      memset(accumulator, 0, sizeof(*accumulator));
      return;
   }

   sample = &rlt_host.samples[rlt_host.sample_count++];
   memset(sample, 0, sizeof(*sample));
   sample->producer_seq = accumulator->producer_seq;
   sample->submit_bytes = accumulator->submit_bytes;
   sample->marker_bytes = accumulator->marker_bytes;
   sample->queue_depth = accumulator->queue_depth;
   for (uint32_t i = 0; i < RLT_HOST_STAGE_COUNT; i++) {
      uint64_t elapsed_us = accumulator->elapsed_ns[i] / 1000ULL;
      sample->elapsed_us[i] =
         elapsed_us > UINT32_MAX ? UINT32_MAX : (uint32_t)elapsed_us;
      sample->calls[i] = accumulator->calls[i];
   }

   memset(accumulator, 0, sizeof(*accumulator));
   if (rlt_host.sample_count == RLT_HOST_WINDOW)
      rlt_host_emit_window();
}

void
rlt_host_complete_busy_wait(const struct rlt_host_scope *scope,
                            uint32_t queue_depth)
{
   struct rlt_host_accumulator *accumulator;
   uint64_t self_wall_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_MONOTONIC) : 0;
   uint64_t self_cpu_started_ns = rlt_host.self_profile_enabled
      ? rlt_host_clock_ns(CLOCK_THREAD_CPUTIME_ID) : 0;

   if (!rlt_host_scope_still_active(scope)) {
      if (rlt_host.page)
         rlt_host.unattributed_events++;
      rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
      return;
   }
   accumulator = rlt_host_accumulator_for(scope);
   if (!accumulator) {
      rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
      return;
   }
   accumulator->saw_completion = true;
   if (queue_depth > accumulator->queue_depth)
      accumulator->queue_depth = queue_depth;
   if (!rlt_host.page &&
       rlt_host.remote_producer_seq == scope->producer_seq)
      rlt_host.remote_frame_active = false;
   rlt_host_self_end(self_wall_started_ns, self_cpu_started_ns);
}
