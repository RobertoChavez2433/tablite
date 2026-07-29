#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include "rlt_ahb_frame_stream.h"

#ifdef __ANDROID__

#if !defined(__ANDROID_MIN_SDK_VERSION__) || __ANDROID_MIN_SDK_VERSION__ < 26
#undef __ANDROID_MIN_SDK_VERSION__
#define __ANDROID_MIN_SDK_VERSION__ 26
#undef __ANDROID_API__
#define __ANDROID_API__ 26
#endif
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <epoxy/egl.h>
#include <epoxy/gl.h>
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define RLAWT_SURFACE_MAGIC 0x57414c52u
#define RLAWT_SURFACE_VERSION 1u
#define RLAWT_SURFACE_POOL_MAX 6u
#define RLAWT_SURFACE_FORMAT_RGBA8 1u
#define RLAWT_SURFACE_FORMAT_BGRA8 2u

enum rlawt_surface_msg_type {
   RLAWT_MSG_PRODUCER_HELLO = 1,
   RLAWT_MSG_POOL_HELLO = 2,
   RLAWT_MSG_POOL_BUFFER = 3,
   RLAWT_MSG_POOL_READY = 4,
   RLAWT_MSG_FRAME_READY = 5,
   RLAWT_MSG_RELEASE = 6,
   RLAWT_MSG_RESIZE_REQUEST = 7,
   RLAWT_MSG_BYE = 8,
};

struct rlawt_surface_hdr {
   uint32_t magic;
   uint16_t version;
   uint16_t type;
   uint32_t length;
   uint32_t reserved;
};

struct rlawt_surface_producer_hello {
   uint32_t requested_width;
   uint32_t requested_height;
   uint32_t requested_format;
   uint32_t flags;
};

struct rlawt_surface_pool_hello {
   uint16_t pool_size;
   uint16_t format;
   uint32_t width;
   uint32_t height;
   uint32_t stride_pixels;
   uint32_t reserved;
   uint64_t usage;
};

struct rlawt_surface_pool_buffer {
   uint16_t index;
   uint16_t reserved;
};

struct rlawt_surface_frame_ready {
   uint16_t index;
   uint16_t reserved;
   uint32_t frame_seq;
};

struct rlawt_surface_release {
   uint16_t index;
   uint16_t reserved;
   uint32_t frame_seq;
};

_Static_assert(sizeof(struct rlawt_surface_hdr) == 16,
               "rlawt_surface_hdr ABI changed");
_Static_assert(sizeof(struct rlawt_surface_pool_hello) == 32,
               "rlawt_surface_pool_hello ABI changed");

struct rlt_ahb_state {
   bool initialized;
   bool enabled;
   int fd;
   char socket_name[64];
   char run_id[RLT_FRAME_STREAM_RUN_ID_MAX];
   uint64_t next_connect_ns;
   uint64_t fallback_seq;
   uint64_t connect_failures;
   uint64_t frame_failures;
   uint64_t frames_sent;
   uint64_t bytes_copied;
   uint64_t acquire_wait_ns;
   uint64_t lock_ns;
   uint64_t copy_ns;
   uint64_t unlock_ns;
   uint64_t fence_wait_ns;
   uint64_t ready_send_ns;
   uint64_t gpu_frames_sent;
   uint64_t gpu_import_ns;
   uint64_t gpu_acquire_wait_ns;
   uint64_t gpu_blit_ns;
   uint64_t gpu_finish_ns;
   uint64_t gpu_ready_send_ns;
   uint64_t gpu_failures;
   uint64_t pool_frames_sent;
   uint64_t pool_first_present_ns;
   uint32_t width;
   uint32_t height;
   uint32_t format;
   uint16_t pool_size;
   uint16_t next_slot;
   uint32_t busy_mask;
   AHardwareBuffer *buffers[RLAWT_SURFACE_POOL_MAX];
   EGLDisplay gpu_display;
   EGLContext gpu_context;
   EGLImageKHR gpu_images[RLAWT_SURFACE_POOL_MAX];
   GLuint gpu_textures[RLAWT_SURFACE_POOL_MAX];
   GLuint gpu_fbos[RLAWT_SURFACE_POOL_MAX];
   GLuint gpu_source_fbo;
};

static struct rlt_ahb_state ahb = {
   .fd = -1,
};

static struct {
   void *library;
   void (*release)(AHardwareBuffer *);
   int (*recv_handle)(int, AHardwareBuffer **);
   void (*describe)(const AHardwareBuffer *, AHardwareBuffer_Desc *);
   int (*lock)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, void **);
   int (*unlock)(AHardwareBuffer *, int32_t *);
} ahb_api;

static uint64_t
rlt_ahb_now_ns(void)
{
   struct timespec ts;
   if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
      return 0;
   return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static unsigned
rlt_ahb_probe_value(const char *name, unsigned fallback,
                    unsigned minimum, unsigned maximum)
{
   const char *value = getenv(name);
   char *end = NULL;
   unsigned long parsed;
   if (!value || !value[0])
      return fallback;
   errno = 0;
   parsed = strtoul(value, &end, 10);
   if (errno || end == value || *end || parsed < minimum || parsed > maximum)
      return fallback;
   return (unsigned)parsed;
}

static void
rlt_ahb_init(void)
{
   const char *socket_name;
   const char *run_id;

   if (ahb.initialized)
      return;
   ahb.initialized = true;
   socket_name = getenv("RLT_FRAME_AHB_SOCKET");
   if (!socket_name || !socket_name[0])
      return;

   snprintf(ahb.socket_name, sizeof(ahb.socket_name), "%s", socket_name);
   run_id = getenv("RLT_CAPTURE_ID");
   snprintf(ahb.run_id, sizeof(ahb.run_id), "%s",
            run_id && run_id[0] ? run_id : "unknown");
   ahb.enabled = true;
   ahb_api.library = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
   *(void **)(&ahb_api.release) =
      dlsym(ahb_api.library, "AHardwareBuffer_release");
   *(void **)(&ahb_api.recv_handle) =
      dlsym(ahb_api.library, "AHardwareBuffer_recvHandleFromUnixSocket");
   *(void **)(&ahb_api.describe) =
      dlsym(ahb_api.library, "AHardwareBuffer_describe");
   *(void **)(&ahb_api.lock) =
      dlsym(ahb_api.library, "AHardwareBuffer_lock");
   *(void **)(&ahb_api.unlock) =
      dlsym(ahb_api.library, "AHardwareBuffer_unlock");
   if (!ahb_api.library || !ahb_api.release || !ahb_api.recv_handle || !ahb_api.describe ||
       !ahb_api.lock || !ahb_api.unlock) {
      fprintf(stderr,
              "RLT_AHB_STREAM_V1 run_id=%s stage=host_handoff "
              "outcome=disabled reason=missing_ahardwarebuffer_api\n",
              ahb.run_id);
      ahb.enabled = false;
      return;
   }
   fprintf(stderr,
           "RLT_AHB_STREAM_V1 run_id=%s stage=host_handoff outcome=enabled "
           "transport=unix_seqpacket_ahb socket=@%s\n",
           ahb.run_id, ahb.socket_name);
}

static bool
rlt_ahb_send_msg(uint16_t type, const void *body, uint32_t body_len)
{
   struct rlawt_surface_hdr hdr = {
      .magic = RLAWT_SURFACE_MAGIC,
      .version = RLAWT_SURFACE_VERSION,
      .type = type,
      .length = body_len,
      .reserved = 0,
   };
   struct iovec iov[2] = {
      { .iov_base = &hdr, .iov_len = sizeof(hdr) },
      { .iov_base = (void *)body, .iov_len = body_len },
   };
   struct msghdr msg = {
      .msg_iov = iov,
      .msg_iovlen = body_len ? 2 : 1,
   };
   ssize_t sent;
   do {
      sent = sendmsg(ahb.fd, &msg, MSG_NOSIGNAL);
   } while (sent < 0 && errno == EINTR);
   return sent == (ssize_t)(sizeof(hdr) + body_len);
}

static void
rlt_ahb_gpu_pool_clear(void)
{
   unsigned i;
   EGLContext current_context = eglGetCurrentContext();
   bool context_matches =
      ahb.gpu_context != EGL_NO_CONTEXT &&
      current_context == ahb.gpu_context;

   if (context_matches && ahb.gpu_source_fbo)
      glDeleteFramebuffers(1, &ahb.gpu_source_fbo);
   ahb.gpu_source_fbo = 0;
   for (i = 0; i < RLAWT_SURFACE_POOL_MAX; i++) {
      if (context_matches && ahb.gpu_fbos[i])
         glDeleteFramebuffers(1, &ahb.gpu_fbos[i]);
      if (context_matches && ahb.gpu_textures[i])
         glDeleteTextures(1, &ahb.gpu_textures[i]);
      if (ahb.gpu_images[i] != EGL_NO_IMAGE_KHR &&
          ahb.gpu_display != EGL_NO_DISPLAY)
         eglDestroyImageKHR(ahb.gpu_display, ahb.gpu_images[i]);
      ahb.gpu_fbos[i] = 0;
      ahb.gpu_textures[i] = 0;
      ahb.gpu_images[i] = EGL_NO_IMAGE_KHR;
   }
   ahb.gpu_display = EGL_NO_DISPLAY;
   ahb.gpu_context = EGL_NO_CONTEXT;
}

static int
rlt_ahb_recv_msg(struct rlawt_surface_hdr *hdr,
                 void *body,
                 size_t body_capacity,
                 int flags)
{
   uint8_t packet[128];
   ssize_t received;

   do {
      received = recv(ahb.fd, packet, sizeof(packet), flags);
   } while (received < 0 && errno == EINTR);
   if (received < (ssize_t)sizeof(*hdr))
      return -1;
   memcpy(hdr, packet, sizeof(*hdr));
   if (hdr->magic != RLAWT_SURFACE_MAGIC ||
       hdr->version != RLAWT_SURFACE_VERSION ||
       hdr->length > body_capacity ||
       received != (ssize_t)(sizeof(*hdr) + hdr->length)) {
      errno = EPROTO;
      return -1;
   }
   if (hdr->length)
      memcpy(body, packet + sizeof(*hdr), hdr->length);
   return (int)hdr->length;
}

static void
rlt_ahb_disconnect(void)
{
   unsigned i;
   rlt_ahb_gpu_pool_clear();
   if (ahb.fd >= 0) {
      close(ahb.fd);
      ahb.fd = -1;
   }
   for (i = 0; i < RLAWT_SURFACE_POOL_MAX; i++) {
      if (ahb.buffers[i]) {
         ahb_api.release(ahb.buffers[i]);
         ahb.buffers[i] = NULL;
      }
   }
   ahb.pool_size = 0;
   ahb.busy_mask = 0;
   ahb.width = 0;
   ahb.height = 0;
}

static bool
rlt_ahb_gpu_pool_prepare(uint64_t *import_ns)
{
   const EGLint image_attribs[] = {
      EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
      EGL_NONE,
   };
   EGLDisplay display = eglGetCurrentDisplay();
   EGLContext context = eglGetCurrentContext();
   GLint old_active_texture = GL_TEXTURE0;
   GLint old_texture = 0;
   GLint old_draw_fbo = 0;
   uint64_t start_ns;
   unsigned i;

   if (import_ns)
      *import_ns = 0;
   if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT ||
       epoxy_gl_version() < 30 ||
       !epoxy_has_egl_extension(
          display, "EGL_ANDROID_image_native_buffer") ||
       !epoxy_has_gl_extension("GL_OES_EGL_image"))
      return false;
   if (ahb.gpu_context == context && ahb.gpu_display == display &&
       ahb.gpu_source_fbo)
      return true;

   rlt_ahb_gpu_pool_clear();
   start_ns = rlt_ahb_now_ns();
   ahb.gpu_display = display;
   ahb.gpu_context = context;
   glGetIntegerv(GL_ACTIVE_TEXTURE, &old_active_texture);
   glActiveTexture(GL_TEXTURE0);
   glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
   glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw_fbo);
   glGenFramebuffers(1, &ahb.gpu_source_fbo);

   for (i = 0; i < ahb.pool_size; i++) {
      EGLClientBuffer client_buffer =
         eglGetNativeClientBufferANDROID(ahb.buffers[i]);
      if (!client_buffer)
         goto fail;
      ahb.gpu_images[i] = eglCreateImageKHR(
         display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
         client_buffer, image_attribs);
      if (ahb.gpu_images[i] == EGL_NO_IMAGE_KHR)
         goto fail;

      glGenTextures(1, &ahb.gpu_textures[i]);
      glBindTexture(GL_TEXTURE_2D, ahb.gpu_textures[i]);
      glEGLImageTargetTexture2DOES(
         GL_TEXTURE_2D, (GLeglImageOES)ahb.gpu_images[i]);
      if (glGetError() != GL_NO_ERROR)
         goto fail;

      glGenFramebuffers(1, &ahb.gpu_fbos[i]);
      glBindFramebuffer(GL_DRAW_FRAMEBUFFER, ahb.gpu_fbos[i]);
      glFramebufferTexture2D(
         GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
         GL_TEXTURE_2D, ahb.gpu_textures[i], 0);
      if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) !=
          GL_FRAMEBUFFER_COMPLETE)
         goto fail;
   }

   glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw_fbo);
   glBindTexture(GL_TEXTURE_2D, (GLuint)old_texture);
   glActiveTexture((GLenum)old_active_texture);
   if (import_ns)
      *import_ns = rlt_ahb_now_ns() - start_ns;
   return true;

fail:
   glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw_fbo);
   glBindTexture(GL_TEXTURE_2D, (GLuint)old_texture);
   glActiveTexture((GLenum)old_active_texture);
   rlt_ahb_gpu_pool_clear();
   return false;
}

static bool
rlt_ahb_receive_pool(uint32_t width, uint32_t height, uint32_t format)
{
   struct rlawt_surface_hdr hdr;
   struct rlawt_surface_pool_hello pool;
   unsigned i;

   if (rlt_ahb_recv_msg(&hdr, &pool, sizeof(pool), 0) < (int)sizeof(pool) ||
       hdr.type != RLAWT_MSG_POOL_HELLO ||
       pool.pool_size < 2 || pool.pool_size > RLAWT_SURFACE_POOL_MAX ||
       pool.width != width || pool.height != height)
      return false;

   for (i = 0; i < pool.pool_size; i++) {
      struct rlawt_surface_pool_buffer entry;
      if (rlt_ahb_recv_msg(&hdr, &entry, sizeof(entry), 0) <
             (int)sizeof(entry) ||
          hdr.type != RLAWT_MSG_POOL_BUFFER || entry.index != i ||
          ahb_api.recv_handle(ahb.fd, &ahb.buffers[i]) != 0)
         return false;
   }
   if (rlt_ahb_recv_msg(&hdr, NULL, 0, 0) != 0 ||
       hdr.type != RLAWT_MSG_POOL_READY)
      return false;

   ahb.pool_size = pool.pool_size;
   ahb.width = width;
   ahb.height = height;
   ahb.format = format;
   ahb.next_slot = 0;
   ahb.busy_mask = 0;
   ahb.pool_frames_sent = 0;
   ahb.pool_first_present_ns = 0;
   fprintf(stderr,
           "RLT_AHB_STREAM_V1 run_id=%s stage=pool outcome=ready "
           "size=%u width=%u height=%u stride_pixels=%u format=%u\n",
           ahb.run_id, pool.pool_size, width, height,
           pool.stride_pixels, format);
   return true;
}

static bool
rlt_ahb_connect(uint32_t width, uint32_t height, uint32_t format)
{
   struct sockaddr_un addr;
   struct rlawt_surface_producer_hello hello;
   uint64_t now_ns = rlt_ahb_now_ns();
   size_t name_len;

   if (ahb.fd >= 0 && ahb.width == width && ahb.height == height &&
       ahb.format == format)
      return true;
   if (ahb.fd >= 0)
      rlt_ahb_disconnect();
   if (now_ns < ahb.next_connect_ns)
      return false;

   ahb.fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
   if (ahb.fd < 0)
      goto fail;
   memset(&addr, 0, sizeof(addr));
   addr.sun_family = AF_UNIX;
   addr.sun_path[0] = '\0';
   name_len = strlen(ahb.socket_name);
   if (name_len > sizeof(addr.sun_path) - 2)
      name_len = sizeof(addr.sun_path) - 2;
   memcpy(addr.sun_path + 1, ahb.socket_name, name_len);
   if (connect(ahb.fd, (struct sockaddr *)&addr,
               (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                           1 + name_len)) != 0)
      goto fail;

   hello.requested_width = width;
   hello.requested_height = height;
   hello.requested_format =
      format == RLT_FRAME_STREAM_FORMAT_BGRA8
         ? RLAWT_SURFACE_FORMAT_BGRA8
         : RLAWT_SURFACE_FORMAT_RGBA8;
   hello.flags = 0;
   if (!rlt_ahb_send_msg(RLAWT_MSG_PRODUCER_HELLO,
                         &hello, sizeof(hello)) ||
       !rlt_ahb_receive_pool(width, height, format))
      goto fail;

   fprintf(stderr,
           "RLT_AHB_STREAM_V1 run_id=%s stage=host_handoff "
           "outcome=connected transport=unix_seqpacket_ahb socket=@%s\n",
           ahb.run_id, ahb.socket_name);
   return true;

fail:
   ahb.connect_failures++;
   if (ahb.connect_failures <= 5 || (ahb.connect_failures % 60) == 0) {
      fprintf(stderr,
              "RLT_AHB_STREAM_V1 run_id=%s stage=host_handoff "
              "outcome=connect_failed failures=%llu errno=%d\n",
              ahb.run_id,
              (unsigned long long)ahb.connect_failures, errno);
   }
   rlt_ahb_disconnect();
   ahb.next_connect_ns = now_ns + 250000000ull;
   return false;
}

static bool
rlt_ahb_apply_release(bool block)
{
   struct rlawt_surface_hdr hdr;
   struct rlawt_surface_release release;
   int result = rlt_ahb_recv_msg(
      &hdr, &release, sizeof(release), block ? 0 : MSG_DONTWAIT);
   if (result < 0)
      return !block && (errno == EAGAIN || errno == EWOULDBLOCK);
   if (hdr.type != RLAWT_MSG_RELEASE ||
       result < (int)sizeof(release) ||
       release.index >= ahb.pool_size) {
      errno = EPROTO;
      return false;
   }
   ahb.busy_mask &= ~(1u << release.index);
   return true;
}

static int
rlt_ahb_acquire_slot(void)
{
   unsigned attempts;

   while (rlt_ahb_apply_release(false) &&
          errno != EAGAIN && errno != EWOULDBLOCK) {
   }
   for (;;) {
      for (attempts = 0; attempts < ahb.pool_size; attempts++) {
         uint16_t slot = (uint16_t)((ahb.next_slot + attempts) %
                                    ahb.pool_size);
         if (!(ahb.busy_mask & (1u << slot))) {
            ahb.next_slot = (uint16_t)((slot + 1) % ahb.pool_size);
            return slot;
         }
      }
      if (!rlt_ahb_apply_release(true))
         return -1;
   }
}

bool
rlt_ahb_frame_stream_send_gpu(uint64_t frame_seq,
                              uint32_t width,
                              uint32_t height,
                              uint32_t source_texture,
                              bool source_y_0_top,
                              uint64_t transfer_start_ns)
{
   struct rlawt_surface_frame_ready ready;
   GLint old_read_fbo = 0;
   GLint old_draw_fbo = 0;
   GLboolean scissor_enabled = GL_FALSE;
   uint64_t start_ns;
   uint64_t slot_acquired_ns;
   uint64_t import_done_ns;
   uint64_t blit_done_ns;
   uint64_t finish_done_ns;
   uint64_t send_done_ns;
   uint64_t pool_elapsed_ns;
   uint64_t pool_rate_millihz;
   uint64_t import_ns = 0;
   GLenum error;
   int slot;

   rlt_ahb_init();
   if (!ahb.enabled || !width || !height || !source_texture)
      return false;
   if (!frame_seq)
      frame_seq = ++ahb.fallback_seq;

   /*
    * VirGL's GLES BGRA display targets are internally RGBA textures. The
    * legacy readback path CPU-swizzles them back to guest byte order; a direct
    * texture copy must advertise the actual RGBA AHardwareBuffer order.
    */
   if (!rlt_ahb_connect(
          width, height, RLT_FRAME_STREAM_FORMAT_RGBA8))
      return false;

   start_ns = rlt_ahb_now_ns();
   slot = rlt_ahb_acquire_slot();
   if (slot < 0)
      goto fail;
   slot_acquired_ns = rlt_ahb_now_ns();
   if (!rlt_ahb_gpu_pool_prepare(&import_ns))
      goto fail;
   import_done_ns = rlt_ahb_now_ns();

   while (glGetError() != GL_NO_ERROR) {
   }
   glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_read_fbo);
   glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw_fbo);
   scissor_enabled = glIsEnabled(GL_SCISSOR_TEST);
   if (scissor_enabled)
      glDisable(GL_SCISSOR_TEST);

   glBindFramebuffer(GL_READ_FRAMEBUFFER, ahb.gpu_source_fbo);
   glFramebufferTexture2D(
      GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
      GL_TEXTURE_2D, source_texture, 0);
   if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) !=
       GL_FRAMEBUFFER_COMPLETE)
      goto restore_fail;
   glBindFramebuffer(GL_DRAW_FRAMEBUFFER, ahb.gpu_fbos[slot]);
   glBlitFramebuffer(
      0, source_y_0_top ? (GLint)height : 0,
      (GLint)width, source_y_0_top ? 0 : (GLint)height,
      0, 0, (GLint)width, (GLint)height,
      GL_COLOR_BUFFER_BIT, GL_NEAREST);
   error = glGetError();
   if (error != GL_NO_ERROR)
      goto restore_fail;
   blit_done_ns = rlt_ahb_now_ns();

   /*
    * The current ABI does not carry a native fence fd. Keep correctness
    * explicit with a timed finish; a fence-bearing ABI is the next refinement
    * only if this wait remains material after removing readback.
    */
   glFinish();
   error = glGetError();
   finish_done_ns = rlt_ahb_now_ns();
   if (error != GL_NO_ERROR)
      goto restore_fail;

   glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_read_fbo);
   glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw_fbo);
   if (scissor_enabled)
      glEnable(GL_SCISSOR_TEST);

   ready.index = (uint16_t)slot;
   ready.reserved = 0;
   ready.frame_seq = (uint32_t)frame_seq;
   if (!rlt_ahb_send_msg(RLAWT_MSG_FRAME_READY, &ready, sizeof(ready)))
      goto fail;
   send_done_ns = rlt_ahb_now_ns();
   ahb.busy_mask |= 1u << slot;
   ahb.frames_sent++;
   ahb.gpu_frames_sent++;
   ahb.gpu_import_ns += import_ns;
   ahb.gpu_acquire_wait_ns += slot_acquired_ns - start_ns;
   ahb.gpu_blit_ns += blit_done_ns - import_done_ns;
   ahb.gpu_finish_ns += finish_done_ns - blit_done_ns;
   ahb.gpu_ready_send_ns += send_done_ns - finish_done_ns;
   if (!ahb.pool_first_present_ns)
      ahb.pool_first_present_ns = send_done_ns;
   ahb.pool_frames_sent++;
   pool_elapsed_ns = send_done_ns - ahb.pool_first_present_ns;
   pool_rate_millihz =
      ahb.pool_frames_sent > 1 && pool_elapsed_ns
         ? (uint64_t)(((__uint128_t)ahb.pool_frames_sent - 1u) *
                      1000000000000ull / pool_elapsed_ns)
         : 0;

   if (ahb.gpu_frames_sent <= 5 ||
       (ahb.gpu_frames_sent % 60) == 0) {
      fprintf(stderr,
              "RLT_AHB_GPU_V1 run_id=%s frame_seq=%llu "
              "stage=host_handoff outcome=presented slot=%d "
              "width=%u height=%u source_texture=%u source_y_0_top=%u "
              "acquire_wait_ns=%llu import_ns=%llu blit_ns=%llu "
              "finish_ns=%llu ready_send_ns=%llu transfer_to_ready_ns=%llu "
              "frames=%llu pool_frames=%llu pool_elapsed_ns=%llu "
              "pool_rate_millihz=%llu total_acquire_wait_ns=%llu "
              "total_import_ns=%llu total_blit_ns=%llu "
              "total_finish_ns=%llu total_ready_send_ns=%llu failures=%llu\n",
              ahb.run_id, (unsigned long long)frame_seq, slot,
              width, height, source_texture, source_y_0_top ? 1u : 0u,
              (unsigned long long)(slot_acquired_ns - start_ns),
              (unsigned long long)import_ns,
              (unsigned long long)(blit_done_ns - import_done_ns),
              (unsigned long long)(finish_done_ns - blit_done_ns),
              (unsigned long long)(send_done_ns - finish_done_ns),
              (unsigned long long)(send_done_ns - transfer_start_ns),
              (unsigned long long)ahb.gpu_frames_sent,
              (unsigned long long)ahb.pool_frames_sent,
              (unsigned long long)pool_elapsed_ns,
              (unsigned long long)pool_rate_millihz,
              (unsigned long long)ahb.gpu_acquire_wait_ns,
              (unsigned long long)ahb.gpu_import_ns,
              (unsigned long long)ahb.gpu_blit_ns,
              (unsigned long long)ahb.gpu_finish_ns,
              (unsigned long long)ahb.gpu_ready_send_ns,
              (unsigned long long)ahb.gpu_failures);
   }
   return true;

restore_fail:
   glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_read_fbo);
   glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw_fbo);
   if (scissor_enabled)
      glEnable(GL_SCISSOR_TEST);
fail:
   ahb.gpu_failures++;
   if (ahb.gpu_failures <= 5 || (ahb.gpu_failures % 60) == 0) {
      fprintf(stderr,
              "RLT_AHB_GPU_V1 run_id=%s frame_seq=%llu "
              "stage=host_handoff outcome=fallback failures=%llu "
              "egl_error=0x%x gl_error=0x%x errno=%d\n",
              ahb.run_id, (unsigned long long)frame_seq,
              (unsigned long long)ahb.gpu_failures,
              eglGetError(), glGetError(), errno);
   }
   return false;
}

bool
rlt_ahb_frame_stream_send(uint64_t frame_seq,
                          uint32_t width,
                          uint32_t height,
                          uint32_t stride_bytes,
                          enum rlt_frame_stream_format format,
                          const void *pixels,
                          size_t payload_bytes,
                          uint64_t transfer_done_ns)
{
   AHardwareBuffer_Desc desc = {0};
   struct rlawt_surface_frame_ready ready;
   uint64_t start_ns;
   uint64_t slot_acquired_ns;
   uint64_t lock_done_ns;
   uint64_t copy_done_ns;
   uint64_t unlock_done_ns;
   uint64_t fence_done_ns;
   uint64_t send_done_ns;
   uint64_t pool_elapsed_ns;
   uint64_t pool_rate_millihz;
   uint64_t row_bytes;
   uint8_t *dst = NULL;
   int fence_fd = -1;
   int slot;
   unsigned y;

   rlt_ahb_init();
   if (!ahb.enabled)
      return false;
   if (!pixels || !width || !height ||
       (format != RLT_FRAME_STREAM_FORMAT_BGRA8 &&
        format != RLT_FRAME_STREAM_FORMAT_RGBA8))
      return true;
   row_bytes = (uint64_t)width * 4u;
   if (stride_bytes < row_bytes ||
       (uint64_t)stride_bytes * height > payload_bytes)
      return true;
   if (!frame_seq)
      frame_seq = ++ahb.fallback_seq;
   if (!rlt_ahb_connect(width, height, format))
      return true;

   start_ns = rlt_ahb_now_ns();
   slot = rlt_ahb_acquire_slot();
   if (slot < 0)
      goto fail;
   slot_acquired_ns = rlt_ahb_now_ns();
   ahb_api.describe(ahb.buffers[slot], &desc);
   if (desc.width != width || desc.height != height ||
       desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
       ahb_api.lock(ahb.buffers[slot],
                    AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                    -1, NULL, (void **)&dst) != 0 || !dst)
      goto fail;
   lock_done_ns = rlt_ahb_now_ns();

   for (y = 0; y < height; y++)
      memcpy(dst + (size_t)y * desc.stride * 4u,
             (const uint8_t *)pixels + (size_t)y * stride_bytes,
             (size_t)row_bytes);
   copy_done_ns = rlt_ahb_now_ns();
   if (ahb_api.unlock(ahb.buffers[slot], &fence_fd) != 0)
      goto fail;
   unlock_done_ns = rlt_ahb_now_ns();
   if (fence_fd >= 0) {
      struct pollfd poll_fd = { .fd = fence_fd, .events = POLLIN };
      while (poll(&poll_fd, 1, -1) < 0 && errno == EINTR) {
      }
      close(fence_fd);
      fence_fd = -1;
   }
   fence_done_ns = rlt_ahb_now_ns();

   ready.index = (uint16_t)slot;
   ready.reserved = 0;
   ready.frame_seq = (uint32_t)frame_seq;
   if (!rlt_ahb_send_msg(RLAWT_MSG_FRAME_READY, &ready, sizeof(ready)))
      goto fail;
   send_done_ns = rlt_ahb_now_ns();
   ahb.busy_mask |= 1u << slot;
   ahb.frames_sent++;
   ahb.bytes_copied += row_bytes * height;
   ahb.acquire_wait_ns += slot_acquired_ns - start_ns;
   ahb.lock_ns += lock_done_ns - slot_acquired_ns;
   ahb.copy_ns += copy_done_ns - lock_done_ns;
   ahb.unlock_ns += unlock_done_ns - copy_done_ns;
   ahb.fence_wait_ns += fence_done_ns - unlock_done_ns;
   ahb.ready_send_ns += send_done_ns - fence_done_ns;
   if (!ahb.pool_first_present_ns)
      ahb.pool_first_present_ns = send_done_ns;
   ahb.pool_frames_sent++;
   pool_elapsed_ns = send_done_ns - ahb.pool_first_present_ns;
   pool_rate_millihz =
      ahb.pool_frames_sent > 1 && pool_elapsed_ns
         ? (uint64_t)(((__uint128_t)ahb.pool_frames_sent - 1u) *
                      1000000000000ull / pool_elapsed_ns)
         : 0;
   if (ahb.frames_sent <= 5 || (ahb.frames_sent % 60) == 0) {
      fprintf(stderr,
              "RLT_AHB_STREAM_V1 run_id=%s frame_seq=%llu "
              "stage=host_handoff outcome=presented slot=%d "
              "width=%u height=%u bytes=%llu acquire_wait_ns=%llu "
              "lock_ns=%llu copy_ns=%llu "
              "unlock_ns=%llu fence_wait_ns=%llu ready_send_ns=%llu "
              "transfer_to_ready_ns=%llu frames=%llu "
              "pool_frames=%llu pool_elapsed_ns=%llu "
              "pool_rate_millihz=%llu total_bytes=%llu "
              "total_acquire_wait_ns=%llu total_lock_ns=%llu "
              "total_copy_ns=%llu total_unlock_ns=%llu "
              "total_fence_wait_ns=%llu total_ready_send_ns=%llu failures=%llu\n",
              ahb.run_id, (unsigned long long)frame_seq, slot,
              width, height, (unsigned long long)(row_bytes * height),
              (unsigned long long)(slot_acquired_ns - start_ns),
              (unsigned long long)(lock_done_ns - slot_acquired_ns),
              (unsigned long long)(copy_done_ns - lock_done_ns),
              (unsigned long long)(unlock_done_ns - copy_done_ns),
              (unsigned long long)(fence_done_ns - unlock_done_ns),
              (unsigned long long)(send_done_ns - fence_done_ns),
              (unsigned long long)(send_done_ns - transfer_done_ns),
              (unsigned long long)ahb.frames_sent,
              (unsigned long long)ahb.pool_frames_sent,
              (unsigned long long)pool_elapsed_ns,
              (unsigned long long)pool_rate_millihz,
              (unsigned long long)ahb.bytes_copied,
              (unsigned long long)ahb.acquire_wait_ns,
              (unsigned long long)ahb.lock_ns,
              (unsigned long long)ahb.copy_ns,
              (unsigned long long)ahb.unlock_ns,
              (unsigned long long)ahb.fence_wait_ns,
              (unsigned long long)ahb.ready_send_ns,
              (unsigned long long)ahb.frame_failures);
   }
   return true;

fail:
   if (fence_fd >= 0)
      close(fence_fd);
   ahb.frame_failures++;
   fprintf(stderr,
           "RLT_AHB_STREAM_V1 run_id=%s frame_seq=%llu "
           "stage=host_handoff outcome=frame_failed failures=%llu errno=%d\n",
           ahb.run_id, (unsigned long long)frame_seq,
           (unsigned long long)ahb.frame_failures, errno);
   rlt_ahb_disconnect();
   ahb.next_connect_ns = rlt_ahb_now_ns() + 250000000ull;
   return true;
}

void
rlt_ahb_frame_stream_close(void)
{
   if (!ahb.initialized)
      return;
   if (ahb.fd >= 0)
      rlt_ahb_send_msg(RLAWT_MSG_BYE, NULL, 0);
   rlt_ahb_disconnect();
}

void
rlt_ahb_frame_stream_probe(void)
{
   const char *enabled = getenv("RLT_FRAME_AHB_PROBE");
   uint32_t *pixels;
   unsigned width;
   unsigned height;
   unsigned frames;
   unsigned frame;
   unsigned x;
   unsigned y;
   uint64_t start_ns;
   uint64_t done_ns;

   if (!enabled || strcmp(enabled, "1") != 0)
      return;
   width = rlt_ahb_probe_value("RLT_FRAME_AHB_PROBE_WIDTH", 64, 16, 4096);
   height = rlt_ahb_probe_value("RLT_FRAME_AHB_PROBE_HEIGHT", 64, 16, 4096);
   frames = rlt_ahb_probe_value("RLT_FRAME_AHB_PROBE_FRAMES", 1, 1, 1200);
   pixels = malloc((size_t)width * height * sizeof(*pixels));
   if (!pixels)
      return;
   for (y = 0; y < height; y++) {
      for (x = 0; x < width; x++) {
         bool light = ((x / 8u) + (y / 8u)) % 2u == 0u;
         pixels[(size_t)y * width + x] =
            light ? 0xff39b6d6u : 0xffba5078u;
      }
   }
   start_ns = rlt_ahb_now_ns();
   for (frame = 1; frame <= frames; frame++) {
      (void)rlt_ahb_frame_stream_send(
         frame, width, height, width * 4,
         RLT_FRAME_STREAM_FORMAT_RGBA8,
         pixels, (size_t)width * height * sizeof(*pixels),
         rlt_ahb_now_ns());
   }
   done_ns = rlt_ahb_now_ns();
   fprintf(stderr,
           "RLT_AHB_STREAM_V1 run_id=%s stage=startup_probe outcome=complete "
           "width=%u height=%u frames=%u duration_ns=%llu rate_millihz=%llu "
           "failures=%llu\n",
           ahb.run_id, width, height, frames,
           (unsigned long long)(done_ns - start_ns),
           (unsigned long long)(done_ns > start_ns
              ? (uint64_t)frames * 1000000000000ull / (done_ns - start_ns)
              : 0),
           (unsigned long long)ahb.frame_failures);
   free(pixels);
}

#else

bool
rlt_ahb_frame_stream_send(uint64_t frame_seq,
                          uint32_t width,
                          uint32_t height,
                          uint32_t stride_bytes,
                          enum rlt_frame_stream_format format,
                          const void *pixels,
                          size_t payload_bytes,
                          uint64_t transfer_done_ns)
{
   (void)frame_seq;
   (void)width;
   (void)height;
   (void)stride_bytes;
   (void)format;
   (void)pixels;
   (void)payload_bytes;
   (void)transfer_done_ns;
   return false;
}

void
rlt_ahb_frame_stream_close(void)
{
}

void
rlt_ahb_frame_stream_probe(void)
{
}

#endif
