#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include "rlt_frame_stream.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

struct rlt_frame_stream_slot {
   struct rlt_frame_stream_header header;
   uint8_t *pixels;
   size_t capacity;
   size_t payload_bytes;
   uint64_t copy_ns;
   uint64_t queued_ns;
};

struct rlt_frame_stream_state {
   bool initialized;
   bool enabled;
   bool worker_started;
   bool stop_worker;
   uint16_t port;
   int fd;
   pthread_t worker;
   pthread_mutex_t mutex;
   pthread_cond_t ready;
   int active_slot;
   int pending_slot;
   struct rlt_frame_stream_slot slots[2];
   uint64_t fallback_seq;
   uint64_t frames_queued;
   uint64_t frames_superseded;
   uint64_t copy_wall_ns;
   uint64_t queue_high_water_mark;
   uint64_t frames_sent;
   uint64_t bytes_sent;
   uint64_t send_wall_ns;
   uint64_t connect_failures;
   uint64_t send_failures;
   uint64_t next_connect_ns;
   char run_id[RLT_FRAME_STREAM_RUN_ID_MAX];
};

static struct rlt_frame_stream_state stream = {
   .fd = -1,
   .mutex = PTHREAD_MUTEX_INITIALIZER,
   .ready = PTHREAD_COND_INITIALIZER,
   .active_slot = -1,
   .pending_slot = -1,
};

static uint64_t
rlt_stream_now_ns(void)
{
   struct timespec ts;
   if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
      return 0;
   return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void
rlt_stream_init(void)
{
   const char *port_value;
   const char *run_id;
   char *end = NULL;
   long parsed;

   if (stream.initialized)
      return;
   stream.initialized = true;

   port_value = getenv("RLT_FRAME_TCP_PORT");
   if (!port_value || !port_value[0])
      return;

   errno = 0;
   parsed = strtol(port_value, &end, 10);
   if (errno || end == port_value || *end || parsed < 1 || parsed > 65535) {
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 stage=host_send outcome=disabled reason=invalid_port value=%s\n",
              port_value);
      return;
   }

   stream.port = (uint16_t)parsed;
   run_id = getenv("RLT_CAPTURE_ID");
   if (run_id && run_id[0])
      snprintf(stream.run_id, sizeof(stream.run_id), "%s", run_id);
   else
      snprintf(stream.run_id, sizeof(stream.run_id), "unknown");
   stream.enabled = true;

   fprintf(stderr,
           "RLT_FRAME_STREAM_V1 run_id=%s stage=host_send outcome=enabled transport=tcp_loopback port=%u\n",
           stream.run_id, stream.port);
}

static int
rlt_stream_connect(uint64_t now_ns)
{
   struct sockaddr_in addr;
   int fd;
   int one = 1;

   if (stream.fd >= 0)
      return stream.fd;
   if (now_ns < stream.next_connect_ns)
      return -1;

   fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
   if (fd < 0)
      goto fail;

   setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
   memset(&addr, 0, sizeof(addr));
   addr.sin_family = AF_INET;
   addr.sin_port = htons(stream.port);
   addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
   if (connect(fd, (const struct sockaddr *)&addr, sizeof(addr)) != 0) {
      close(fd);
      goto fail;
   }

   stream.fd = fd;
   fprintf(stderr,
           "RLT_FRAME_STREAM_V1 run_id=%s stage=host_send outcome=connected transport=tcp_loopback port=%u\n",
           stream.run_id, stream.port);
   return fd;

fail:
   stream.connect_failures++;
   stream.next_connect_ns = now_ns + 250000000ull;
   if (stream.connect_failures <= 5 || (stream.connect_failures % 60) == 0) {
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 run_id=%s stage=host_send outcome=connect_failed port=%u failures=%llu errno=%d\n",
              stream.run_id, stream.port,
              (unsigned long long)stream.connect_failures, errno);
   }
   return -1;
}

static bool
rlt_stream_write_all(int fd, const void *data, size_t size)
{
   const uint8_t *cursor = data;
   while (size) {
      ssize_t written = send(fd, cursor, size, MSG_NOSIGNAL);
      if (written < 0) {
         if (errno == EINTR)
            continue;
         return false;
      }
      if (written == 0) {
         errno = EPIPE;
         return false;
      }
      cursor += written;
      size -= (size_t)written;
   }
   return true;
}

static void
rlt_stream_send_slot(struct rlt_frame_stream_slot *slot)
{
   uint64_t send_start_ns;
   uint64_t send_done_ns;
   uint64_t frames_queued;
   uint64_t frames_superseded;
   uint64_t queue_high_water_mark;
   int fd;

   send_start_ns = rlt_stream_now_ns();
   fd = rlt_stream_connect(send_start_ns);
   if (fd < 0)
      return;

   slot->header.send_start_ns = send_start_ns;

   if (!rlt_stream_write_all(fd, &slot->header, sizeof(slot->header)) ||
       !rlt_stream_write_all(fd, slot->pixels, slot->payload_bytes)) {
      int saved_errno = errno;
      stream.send_failures++;
      close(stream.fd);
      stream.fd = -1;
      stream.next_connect_ns = rlt_stream_now_ns() + 250000000ull;
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 run_id=%s frame_seq=%llu stage=host_send outcome=send_failed bytes=%zu failures=%llu errno=%d\n",
              stream.run_id,
              (unsigned long long)slot->header.frame_seq,
              slot->payload_bytes,
              (unsigned long long)stream.send_failures, saved_errno);
      return;
   }

   send_done_ns = rlt_stream_now_ns();
   stream.frames_sent++;
   stream.bytes_sent += slot->payload_bytes;
   stream.send_wall_ns += send_done_ns - send_start_ns;
   pthread_mutex_lock(&stream.mutex);
   frames_queued = stream.frames_queued;
   frames_superseded = stream.frames_superseded;
   queue_high_water_mark = stream.queue_high_water_mark;
   pthread_mutex_unlock(&stream.mutex);
   if (stream.frames_sent <= 5 || (stream.frames_sent % 60) == 0) {
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 run_id=%s frame_seq=%llu stage=host_send outcome=sent width=%u height=%u stride_bytes=%u format=%u bytes=%zu copy_ns=%llu enqueue_to_send_ns=%llu transfer_to_send_ns=%llu send_ns=%llu frames_queued=%llu frames_sent=%llu frames_superseded=%llu queue_high_water_mark=%llu total_bytes=%llu total_send_ns=%llu send_failures=%llu\n",
              stream.run_id,
              (unsigned long long)slot->header.frame_seq,
              slot->header.width, slot->header.height,
              slot->header.stride_bytes, slot->header.format,
              slot->payload_bytes,
              (unsigned long long)slot->copy_ns,
              (unsigned long long)(send_start_ns - slot->queued_ns),
              (unsigned long long)(send_start_ns -
                                   slot->header.transfer_done_ns),
              (unsigned long long)(send_done_ns - send_start_ns),
              (unsigned long long)frames_queued,
              (unsigned long long)stream.frames_sent,
              (unsigned long long)frames_superseded,
              (unsigned long long)queue_high_water_mark,
              (unsigned long long)stream.bytes_sent,
              (unsigned long long)stream.send_wall_ns,
              (unsigned long long)stream.send_failures);
   }
}

static void *
rlt_stream_worker(void *unused)
{
   (void)unused;

   while (true) {
      int slot_index;

      pthread_mutex_lock(&stream.mutex);
      while (stream.pending_slot < 0 && !stream.stop_worker)
         pthread_cond_wait(&stream.ready, &stream.mutex);
      if (stream.stop_worker) {
         pthread_mutex_unlock(&stream.mutex);
         break;
      }
      slot_index = stream.pending_slot;
      stream.pending_slot = -1;
      stream.active_slot = slot_index;
      pthread_mutex_unlock(&stream.mutex);

      rlt_stream_send_slot(&stream.slots[slot_index]);

      pthread_mutex_lock(&stream.mutex);
      stream.active_slot = -1;
      pthread_mutex_unlock(&stream.mutex);
   }

   return NULL;
}

static bool
rlt_stream_start_worker(void)
{
   int ret;

   if (stream.worker_started)
      return true;

   ret = pthread_create(&stream.worker, NULL, rlt_stream_worker, NULL);
   if (ret != 0) {
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 run_id=%s stage=host_enqueue outcome=disabled reason=worker_create_failed error=%d\n",
              stream.run_id, ret);
      stream.enabled = false;
      return false;
   }
   stream.worker_started = true;
   fprintf(stderr,
           "RLT_FRAME_STREAM_V1 run_id=%s stage=host_enqueue outcome=enabled queue=latest_frame slots=2\n",
           stream.run_id);
   return true;
}

void
rlt_frame_stream_send(uint64_t frame_seq,
                      uint32_t width,
                      uint32_t height,
                      uint32_t stride_bytes,
                      enum rlt_frame_stream_format format,
                      const void *pixels,
                      size_t payload_bytes,
                      uint64_t transfer_done_ns)
{
   struct rlt_frame_stream_slot *slot;
   uint64_t copy_start_ns;
   uint64_t copy_done_ns;
   int slot_index;

   rlt_stream_init();
   if (!stream.enabled || !pixels || !payload_bytes ||
       payload_bytes > UINT32_MAX || !rlt_stream_start_worker())
      return;

   if (!frame_seq)
      frame_seq = ++stream.fallback_seq;

   copy_start_ns = rlt_stream_now_ns();
   pthread_mutex_lock(&stream.mutex);
   if (stream.pending_slot >= 0) {
      slot_index = stream.pending_slot;
      stream.frames_superseded++;
   } else {
      slot_index = stream.active_slot == 0 ? 1 : 0;
   }
   slot = &stream.slots[slot_index];
   if (slot->capacity < payload_bytes) {
      uint8_t *resized = realloc(slot->pixels, payload_bytes);
      if (!resized) {
         pthread_mutex_unlock(&stream.mutex);
         fprintf(stderr,
                 "RLT_FRAME_STREAM_V1 run_id=%s frame_seq=%llu stage=host_enqueue outcome=dropped reason=allocation_failed bytes=%zu\n",
                 stream.run_id, (unsigned long long)frame_seq,
                 payload_bytes);
         return;
      }
      slot->pixels = resized;
      slot->capacity = payload_bytes;
   }

   memcpy(slot->pixels, pixels, payload_bytes);
   copy_done_ns = rlt_stream_now_ns();
   memset(&slot->header, 0, sizeof(slot->header));
   slot->header.magic = RLT_FRAME_STREAM_MAGIC;
   slot->header.version = RLT_FRAME_STREAM_VERSION;
   slot->header.header_bytes = sizeof(slot->header);
   slot->header.format = format;
   slot->header.frame_seq = frame_seq;
   slot->header.width = width;
   slot->header.height = height;
   slot->header.stride_bytes = stride_bytes;
   slot->header.payload_bytes = (uint32_t)payload_bytes;
   slot->header.transfer_done_ns = transfer_done_ns;
   memcpy(slot->header.run_id, stream.run_id,
          sizeof(slot->header.run_id));
   slot->payload_bytes = payload_bytes;
   slot->copy_ns = copy_done_ns - copy_start_ns;
   slot->queued_ns = copy_done_ns;
   stream.pending_slot = slot_index;
   stream.frames_queued++;
   stream.copy_wall_ns += slot->copy_ns;
   stream.queue_high_water_mark = 1;
   if (stream.frames_queued <= 5 || (stream.frames_queued % 60) == 0) {
      fprintf(stderr,
              "RLT_FRAME_STREAM_V1 run_id=%s frame_seq=%llu stage=host_enqueue outcome=queued bytes=%zu copy_ns=%llu frames_queued=%llu frames_superseded=%llu queue_high_water_mark=%llu total_copy_ns=%llu\n",
              stream.run_id, (unsigned long long)frame_seq, payload_bytes,
              (unsigned long long)slot->copy_ns,
              (unsigned long long)stream.frames_queued,
              (unsigned long long)stream.frames_superseded,
              (unsigned long long)stream.queue_high_water_mark,
              (unsigned long long)stream.copy_wall_ns);
   }
   pthread_cond_signal(&stream.ready);
   pthread_mutex_unlock(&stream.mutex);
}

void
rlt_frame_stream_close(void)
{
   if (stream.worker_started) {
      pthread_mutex_lock(&stream.mutex);
      stream.stop_worker = true;
      pthread_cond_signal(&stream.ready);
      pthread_mutex_unlock(&stream.mutex);
      pthread_join(stream.worker, NULL);
      stream.worker_started = false;
   }
   if (stream.fd >= 0) {
      close(stream.fd);
      stream.fd = -1;
   }
   for (size_t i = 0; i < 2; i++) {
      free(stream.slots[i].pixels);
      stream.slots[i].pixels = NULL;
      stream.slots[i].capacity = 0;
   }
}
