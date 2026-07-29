/*
 * rlawt-on-Surface wire protocol — shared between:
 *   - rlawt JVM-side native (third_party/rlawt-bionic/, runs in proot/Bionic JVM)
 *   - app-side RlawtSurfaceService native helper (runelite-tablet/, framework JVM)
 *
 * Topology:
 *   producer (rlawt JNI)  <---- AF_UNIX (SOCK_SEQPACKET) ---->  consumer (app service)
 *
 * Buffer transport:
 *   AHardwareBuffers are allocated by the consumer (it owns the pool lifetime).
 *   The native FD for each AHB is sent via SCM_RIGHTS as ancillary data on the
 *   POOL_BUFFER message that describes that buffer's index/format. Producer
 *   reconstructs the AHardwareBuffer with AHardwareBuffer_recvHandleFromUnixSocket
 *   and imports it as an EGLImage via eglGetNativeClientBufferANDROID +
 *   eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID).
 *
 * Frame transport:
 *   Producer renders into the FBO-bound EGLImage of one AHB, calls swapBuffers,
 *   sends FRAME_READY{index, frame_seq}. Consumer queues the AHB to its
 *   SurfaceView producer via ANativeWindow_lock/unlockAndPost (for now we only
 *   need the AHB to land on the panel, not to be GPU-blitted by the consumer).
 *   Consumer sends RELEASE{index, frame_seq} when the buffer is free for re-use.
 *
 * Protocol is single-version-pinned; mismatched magic or version closes the
 * connection. All multi-byte fields are host-endian (both sides are aarch64-LE).
 */

#ifndef RLAWT_SURFACE_PROTOCOL_H
#define RLAWT_SURFACE_PROTOCOL_H

#include <stdint.h>

#define RLAWT_SURFACE_MAGIC   0x57414C52u   /* 'RLAW' little-endian */
#define RLAWT_SURFACE_VERSION 1u

/* Linux abstract socket name. Producer connects, consumer listens.
 * The Termux JVM (rlawt producer) and the com.runelitetablet app (consumer)
 * run under different Linux UIDs, so a filesystem-backed UDS would require
 * coordinating directory permissions; abstract sockets bypass the filesystem
 * entirely and any process can connect by name.
 *
 * In sockaddr_un, the abstract namespace is signalled by sun_path[0] = '\0',
 * followed by the name (no trailing NUL), with addr_len = offsetof(sun_path)
 * + 1 + strlen(name). Use rlawt_surface_fill_abstract_addr() below. */
#define RLAWT_SURFACE_ABSTRACT_NAME "rlt-rlawt-surface"

#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>

static inline socklen_t rlawt_surface_fill_abstract_addr(struct sockaddr_un *addr, const char *override_name) {
    const char *name = override_name && *override_name ? override_name : RLAWT_SURFACE_ABSTRACT_NAME;
    size_t name_len = strlen(name);
    if (name_len >= sizeof(addr->sun_path) - 1) name_len = sizeof(addr->sun_path) - 1;
    memset(addr, 0, sizeof(*addr));
    addr->sun_family = AF_UNIX;
    addr->sun_path[0] = '\0';
    memcpy(&addr->sun_path[1], name, name_len);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
}

/* Pool sizing limits. Three buffers is the established pattern (1 in flight to
 * the panel, 1 just-rendered, 1 next render target). Hard cap protects both
 * sides from ridiculous values arriving over the wire. */
#define RLAWT_SURFACE_POOL_MIN  2u
#define RLAWT_SURFACE_POOL_MAX  6u

/* Pixel format: AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM (= 1).
 * #25 de-risker proved this format imports cleanly into Mali r44p1 EGLImage.
 * BGRA mismatch with LorieView's pinned BGRA_8888 is irrelevant here because
 * our consumer is NOT LorieView — it's a fresh SurfaceView in the host
 * activity that we configure to accept RGBA. */
#define RLAWT_SURFACE_FORMAT_RGBA8 1u
/* Bytes are BGRA, while the allocated AHardwareBuffer remains RGBA8. The
 * consumer's presentation shader swaps R/B. This lets CPU-upload producers
 * copy a VirGL display target row-for-row without a second conversion pass. */
#define RLAWT_SURFACE_FORMAT_BGRA8 2u

enum rlawt_surface_msg_type {
    /* Producer → consumer: initial connect. Producer announces requested canvas
     * size (from JAWT). Consumer answers with POOL_HELLO + POOL_BUFFER*N +
     * POOL_READY. */
    RLAWT_MSG_PRODUCER_HELLO = 1,

    /* Consumer → producer: pool metadata (size, format, dims, stride, usage).
     * Sent before any POOL_BUFFER. */
    RLAWT_MSG_POOL_HELLO     = 2,

    /* Consumer → producer: one entry per pool buffer, with the AHB FD as
     * SCM_RIGHTS ancillary on the same datagram. Body says which slot index
     * the FD maps to. */
    RLAWT_MSG_POOL_BUFFER    = 3,

    /* Consumer → producer: pool fully described, render away. */
    RLAWT_MSG_POOL_READY     = 4,

    /* Producer → consumer: AHB[index] has a complete frame. Consumer must
     * eventually reply with RELEASE for the same {index, frame_seq}. */
    RLAWT_MSG_FRAME_READY    = 5,

    /* Consumer → producer: AHB[index] is free for the producer to re-use.
     * Pairs 1:1 with FRAME_READY. */
    RLAWT_MSG_RELEASE        = 6,

    /* Producer → consumer: AWT canvas resized; please re-allocate at new dims.
     * Consumer answers with a fresh POOL_HELLO + POOL_BUFFERs + POOL_READY,
     * implicitly invalidating the old pool. */
    RLAWT_MSG_RESIZE_REQUEST = 7,

    /* Either side: clean teardown. Receiver must close its FDs and exit. */
    RLAWT_MSG_BYE            = 8,
};

/* Every message starts with this header. Body length excludes the header. */
struct rlawt_surface_hdr {
    uint32_t magic;     /* RLAWT_SURFACE_MAGIC */
    uint16_t version;   /* RLAWT_SURFACE_VERSION */
    uint16_t type;      /* enum rlawt_surface_msg_type */
    uint32_t length;    /* body bytes after this header */
    uint32_t reserved;  /* must be 0 */
};

struct rlawt_surface_producer_hello {
    uint32_t requested_width;
    uint32_t requested_height;
    uint32_t requested_format; /* RLAWT_SURFACE_FORMAT_RGBA8 */
    uint32_t flags;            /* reserved, must be 0 */
};

struct rlawt_surface_pool_hello {
    uint16_t pool_size;        /* 2..6 */
    uint16_t format;           /* RLAWT_SURFACE_FORMAT_RGBA8 */
    uint32_t width;
    uint32_t height;
    uint32_t stride_pixels;    /* AHB row stride in pixels */
    uint32_t reserved;
    uint64_t usage;            /* AHB usage bits the consumer allocated with */
};

struct rlawt_surface_pool_buffer {
    uint16_t index;            /* 0..pool_size-1 */
    uint16_t reserved;
    /* AHB FD travels in SCM_RIGHTS ancillary on this datagram. */
};

struct rlawt_surface_frame_ready {
    uint16_t index;
    uint16_t reserved;
    uint32_t frame_seq;        /* monotonically increasing per producer */
};

struct rlawt_surface_release {
    uint16_t index;
    uint16_t reserved;
    uint32_t frame_seq;
};

struct rlawt_surface_resize_request {
    uint32_t width;
    uint32_t height;
};

#endif /* RLAWT_SURFACE_PROTOCOL_H */
