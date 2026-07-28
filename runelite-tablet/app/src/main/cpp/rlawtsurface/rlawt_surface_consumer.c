/*
 * App-side consumer for the rlawt direct-Android-surface path.
 *
 * Runs in com.runelitetablet (framework JVM), bound to a SurfaceView's
 * Surface. Listens on an abstract-namespace AF_UNIX socket, accepts a
 * connection from the rlawt JVM (Termux UID, inside proot), allocates a pool
 * of AHardwareBuffers, hands their FDs to the producer via SCM_RIGHTS, then
 * loops:
 *   - recv FRAME_READY{index, frame_seq} from producer
 *   - GPU-blit the AHB at index onto the SurfaceView via EGL textured quad
 *   - eglSwapBuffers (presents to SurfaceFlinger)
 *   - send RELEASE{index, frame_seq} back to producer
 *
 * Architecture is single-threaded inside the worker. The Kotlin service starts
 * one worker thread per Surface; on disconnect or surface-destroyed, the worker
 * exits and is joined.
 */

#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "rlawt_surface_protocol.h"
#include "rlt_frame_trace.h"

#define LOG_TAG "RltDirectSurface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define POOL_SIZE_DEFAULT 3
#define BLIT_LOG_PERIOD 60   /* periodic blit summary every N frames */

JNIEXPORT jboolean JNICALL
Java_com_runelitetablet_directsurface_HardwareBufferPatternWriter_nativeFill(
        JNIEnv *env, jclass clazz, jobject hardware_buffer, jint slot, jint generation) {
    (void) clazz;
    if (!hardware_buffer) {
        return JNI_FALSE;
    }
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer);
    if (!buffer) {
        return JNI_FALSE;
    }
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.layers != 1) {
        LOGE("patternFill unsupported format=%u layers=%u", desc.format, desc.layers);
        return JNI_FALSE;
    }

    void *pixels = NULL;
    int result = AHardwareBuffer_lock(
        buffer,
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
        -1,
        NULL,
        &pixels);
    if (result != 0 || !pixels) {
        LOGE("patternFill lock failed result=%d", result);
        return JNI_FALSE;
    }

    static const uint8_t colors[3][3] = {
        {214, 176, 57},
        {78, 163, 223},
        {191, 80, 120},
    };
    const uint8_t *base = colors[((unsigned int) slot) % 3u];
    for (uint32_t y = 0; y < desc.height; y++) {
        uint8_t *row = (uint8_t *) pixels + ((size_t) y * desc.stride * 4u);
        for (uint32_t x = 0; x < desc.width; x++) {
            const bool checker = (((x / 96u) + (y / 96u) + (uint32_t) generation) & 1u) != 0u;
            const uint8_t delta = checker ? 32u : 0u;
            row[x * 4u + 0u] = (uint8_t) (base[0] > delta ? base[0] - delta : 0u);
            row[x * 4u + 1u] = (uint8_t) (base[1] > delta ? base[1] - delta : 0u);
            row[x * 4u + 2u] = (uint8_t) (base[2] > delta ? base[2] - delta : 0u);
            row[x * 4u + 3u] = 255u;
        }
    }

    int fence_fd = -1;
    result = AHardwareBuffer_unlock(buffer, &fence_fd);
    if (result != 0) {
        LOGE("patternFill unlock failed result=%d", result);
        if (fence_fd >= 0) close(fence_fd);
        return JNI_FALSE;
    }
    if (fence_fd >= 0) {
        struct pollfd poll_fd = {
            .fd = fence_fd,
            .events = POLLIN,
            .revents = 0,
        };
        result = poll(&poll_fd, 1, 1000);
        close(fence_fd);
        if (result <= 0) {
            LOGE("patternFill unlock fence wait failed result=%d errno=%d", result, errno);
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

static int count_open_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return -1;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] != '.') n++;
    }
    closedir(d);
    return n;
}

static const char *msg_name(uint16_t type) {
    switch (type) {
    case RLAWT_MSG_PRODUCER_HELLO: return "PRODUCER_HELLO";
    case RLAWT_MSG_POOL_HELLO:     return "POOL_HELLO";
    case RLAWT_MSG_POOL_BUFFER:    return "POOL_BUFFER";
    case RLAWT_MSG_POOL_READY:     return "POOL_READY";
    case RLAWT_MSG_FRAME_READY:    return "FRAME_READY";
    case RLAWT_MSG_RELEASE:        return "RELEASE";
    case RLAWT_MSG_RESIZE_REQUEST: return "RESIZE_REQUEST";
    case RLAWT_MSG_BYE:            return "BYE";
    default: return "?";
    }
}

static inline uint64_t ts_delta_us(const struct timespec *a, const struct timespec *b) {
    int64_t s = (int64_t)a->tv_sec - (int64_t)b->tv_sec;
    int64_t n = (int64_t)a->tv_nsec - (int64_t)b->tv_nsec;
    return (uint64_t)(s * 1000000LL + n / 1000);
}

/* Per-blit telemetry: total blits, total swap_us, periodic summary. Reset on
 * pool re-allocation. */
typedef struct blit_stats {
    uint64_t total_blits;
    uint64_t window_blits;
    uint64_t window_blit_us;
    uint64_t window_eglswap_us;
    struct timespec last_blit_ts;
} blit_stats_t;

typedef struct rlawt_consumer {
    pthread_t thread;
    atomic_int stopping;
    int srv_fd;
    int client_fd;
    ANativeWindow *window;
    char abstract_name[64];

    EGLDisplay egl_display;
    EGLConfig  egl_config;
    EGLContext egl_context;
    EGLSurface egl_surface;

    /* Pool state. */
    uint32_t pool_size;
    uint32_t pool_width;
    uint32_t pool_height;
    AHardwareBuffer *ahbs[RLAWT_SURFACE_POOL_MAX];
    void *egl_images[RLAWT_SURFACE_POOL_MAX]; /* EGLImageKHR */
    GLuint textures[RLAWT_SURFACE_POOL_MAX];

    /* Blit pipeline. */
    GLuint program;
    GLint  uniform_tex;
    GLuint vbo;

    /* Loaded extension entrypoints. */
    void (*fpEGLImageTargetTex2D)(GLenum, void *);
    void *(*fpEglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
    EGLBoolean (*fpEglDestroyImageKHR)(EGLDisplay, void *);
    EGLClientBuffer (*fpEglGetNativeClientBufferANDROID)(const AHardwareBuffer *);

    blit_stats_t stats;
} rlawt_consumer_t;

/* ----------------------------------------------------------- proto helpers */

static bool send_msg(int fd, uint16_t type, const void *body, uint32_t body_len) {
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
    struct msghdr msg = {0};
    msg.msg_iov = iov;
    msg.msg_iovlen = body_len > 0 ? 2 : 1;
    ssize_t n = sendmsg(fd, &msg, 0);
    if (n < 0) {
        LOGE("send FAIL %s body=%u errno=%d (%s)", msg_name(type), body_len, errno, strerror(errno));
        return false;
    }
    /* Per-frame chatter (RELEASE) is silenced; covered by periodic blit summary. */
    if (type != RLAWT_MSG_RELEASE) {
        LOGI("send OK   %s body=%u total=%zd", msg_name(type), body_len, n);
    }
    return true;
}

/* Recv a non-FD message. Returns body bytes, -1 on error. */
static int recv_msg(int fd, struct rlawt_surface_hdr *hdr, void *body, uint32_t body_cap) {
    struct iovec iov[2] = {
        { .iov_base = hdr, .iov_len = sizeof(*hdr) },
        { .iov_base = body, .iov_len = body_cap },
    };
    struct msghdr msg = {0};
    msg.msg_iov = iov;
    msg.msg_iovlen = 2;
    ssize_t n = recvmsg(fd, &msg, 0);
    if (n < 0) {
        if (errno != ECONNRESET && errno != EBADF) {
            LOGE("recv FAIL errno=%d (%s)", errno, strerror(errno));
        }
        return -1;
    }
    if ((size_t)n < sizeof(*hdr)) {
        LOGE("recv FAIL short header n=%zd want>=%zu", n, sizeof(*hdr));
        return -1;
    }
    if (hdr->magic != RLAWT_SURFACE_MAGIC || hdr->version != RLAWT_SURFACE_VERSION) {
        LOGE("recv FAIL bad header magic=0x%08x ver=%u type=%u", hdr->magic, hdr->version, hdr->type);
        return -1;
    }
    int body_n = (int)(n - sizeof(*hdr));
    if (hdr->type != RLAWT_MSG_FRAME_READY) {
        LOGI("recv OK   %s body=%d total=%zd", msg_name(hdr->type), body_n, n);
    }
    return body_n;
}

/* -------------------------------------------------------------- EGL setup */

static GLuint compile_shader(GLenum stage, const char *src) {
    GLuint s = glCreateShader(stage);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[1024]; buf[0] = '\0';
        glGetShaderInfoLog(s, sizeof(buf), NULL, buf);
        LOGE("shader stage=%d compile failed: %s", stage, buf);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint link_program(GLuint vs, GLuint fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glBindAttribLocation(p, 0, "a_pos");
    glBindAttribLocation(p, 1, "a_uv");
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[1024]; buf[0] = '\0';
        glGetProgramInfoLog(p, sizeof(buf), NULL, buf);
        LOGE("program link failed: %s", buf);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

static const char *VS_SRC =
    "#version 100\n"
    "attribute vec2 a_pos;\n"
    "attribute vec2 a_uv;\n"
    "varying vec2 v_uv;\n"
    "void main() { v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";

static const char *FS_SRC =
    "#version 100\n"
    "precision mediump float;\n"
    "varying vec2 v_uv;\n"
    "uniform sampler2D u_tex;\n"
    "void main() { gl_FragColor = texture2D(u_tex, v_uv); }\n";

/* Full-screen triangle strip quad: pos.xy + uv.xy interleaved.
 * NDC top-left = (-1, +1), Surface texture origin = bottom-left, so flip V. */
static const float QUAD_VBO_DATA[] = {
    -1.0f, -1.0f, 0.0f, 1.0f,
     1.0f, -1.0f, 1.0f, 1.0f,
    -1.0f,  1.0f, 0.0f, 0.0f,
     1.0f,  1.0f, 1.0f, 0.0f,
};

static bool egl_init(rlawt_consumer_t *c) {
    LOGI("egl_init: window=%p w=%d h=%d format=%d",
        (void *)c->window,
        c->window ? ANativeWindow_getWidth(c->window) : -1,
        c->window ? ANativeWindow_getHeight(c->window) : -1,
        c->window ? ANativeWindow_getFormat(c->window) : -1);
    c->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (c->egl_display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay no display"); return false; }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(c->egl_display, &major, &minor)) {
        LOGE("eglInitialize FAIL egl_err=0x%x", eglGetError());
        return false;
    }
    LOGI("EGL %d.%d vendor=%s version=%s", major, minor,
        eglQueryString(c->egl_display, EGL_VENDOR),
        eglQueryString(c->egl_display, EGL_VERSION));
    LOGI("EGL_CLIENT_APIS=%s", eglQueryString(c->egl_display, EGL_CLIENT_APIS));
    LOGI("EGL_EXTENSIONS=%s", eglQueryString(c->egl_display, EGL_EXTENSIONS));

    const EGLint cfg_attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint num = 0;
    if (!eglChooseConfig(c->egl_display, cfg_attribs, &c->egl_config, 1, &num) || num < 1) {
        LOGE("eglChooseConfig FAIL num=%d egl_err=0x%x", num, eglGetError());
        return false;
    }
    {
        EGLint r=0,g=0,b=0,a=0,vid=0;
        eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_RED_SIZE, &r);
        eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_BLUE_SIZE, &b);
        eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_ALPHA_SIZE, &a);
        eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_NATIVE_VISUAL_ID, &vid);
        LOGI("eglChooseConfig OK num=%d picked R%d G%d B%d A%d native_vid=%d", num, r, g, b, a, vid);
    }

    EGLint vid = 0;
    eglGetConfigAttrib(c->egl_display, c->egl_config, EGL_NATIVE_VISUAL_ID, &vid);
    int rc = ANativeWindow_setBuffersGeometry(c->window, 0, 0, vid);
    LOGI("ANativeWindow_setBuffersGeometry vid=%d -> %d", vid, rc);

    const EGLint surf_attribs[] = { EGL_NONE };
    c->egl_surface = eglCreateWindowSurface(c->egl_display, c->egl_config, c->window, surf_attribs);
    if (c->egl_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface FAIL egl_err=0x%x", eglGetError());
        return false;
    }
    LOGI("eglCreateWindowSurface OK surf=%p", (void *)c->egl_surface);

    const EGLint ctx_attribs_3[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    const EGLint ctx_attribs_2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    const char *picked = "GLES3";
    c->egl_context = eglCreateContext(c->egl_display, c->egl_config, EGL_NO_CONTEXT, ctx_attribs_3);
    if (c->egl_context == EGL_NO_CONTEXT) {
        EGLint err3 = eglGetError();
        c->egl_context = eglCreateContext(c->egl_display, c->egl_config, EGL_NO_CONTEXT, ctx_attribs_2);
        picked = "GLES2";
        LOGW("eglCreateContext GLES3 FAIL err=0x%x; fell back to GLES2", err3);
    }
    if (c->egl_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext FAIL both GLES3 and GLES2 egl_err=0x%x", eglGetError());
        return false;
    }
    LOGI("eglCreateContext OK ctx=%p picked=%s", (void *)c->egl_context, picked);
    if (!eglMakeCurrent(c->egl_display, c->egl_surface, c->egl_surface, c->egl_context)) {
        LOGE("eglMakeCurrent FAIL egl_err=0x%x", eglGetError());
        return false;
    }
    LOGI("consumer GL_VENDOR=%s GL_RENDERER=%s GL_VERSION=%s GL_SHADING_LANGUAGE_VERSION=%s",
         glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION),
         glGetString(GL_SHADING_LANGUAGE_VERSION));
    {
        const GLubyte *exts = glGetString(GL_EXTENSIONS);
        LOGI("consumer GL_EXTENSIONS=%s", exts ? (const char *)exts : "(null)");
    }

    c->fpEGLImageTargetTex2D = (void(*)(GLenum,void*))eglGetProcAddress("glEGLImageTargetTexture2DOES");
    c->fpEglCreateImageKHR =
        (void *(*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *))
        eglGetProcAddress("eglCreateImageKHR");
    c->fpEglDestroyImageKHR =
        (EGLBoolean (*)(EGLDisplay, void *)) eglGetProcAddress("eglDestroyImageKHR");
    c->fpEglGetNativeClientBufferANDROID =
        (EGLClientBuffer (*)(const AHardwareBuffer *))
        eglGetProcAddress("eglGetNativeClientBufferANDROID");
    LOGI("ext entrypoints: ImageTargetTex2D=%p eglCreateImageKHR=%p eglDestroyImageKHR=%p eglGetNativeClientBufferANDROID=%p",
        (void *)c->fpEGLImageTargetTex2D, (void *)c->fpEglCreateImageKHR,
        (void *)c->fpEglDestroyImageKHR, (void *)c->fpEglGetNativeClientBufferANDROID);
    if (!c->fpEGLImageTargetTex2D || !c->fpEglCreateImageKHR ||
        !c->fpEglDestroyImageKHR || !c->fpEglGetNativeClientBufferANDROID) {
        LOGE("required ext entrypoints missing");
        return false;
    }

    GLuint vs = compile_shader(GL_VERTEX_SHADER, VS_SRC);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, FS_SRC);
    if (!vs || !fs) return false;
    c->program = link_program(vs, fs);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!c->program) return false;
    c->uniform_tex = glGetUniformLocation(c->program, "u_tex");

    glGenBuffers(1, &c->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, c->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VBO_DATA), QUAD_VBO_DATA, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGI("blit pipeline ready program=%u u_tex=%d vbo=%u",
        c->program, c->uniform_tex, c->vbo);
    return true;
}

static void egl_teardown(rlawt_consumer_t *c) {
    if (c->egl_display == EGL_NO_DISPLAY) return;
    eglMakeCurrent(c->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c->vbo) glDeleteBuffers(1, &c->vbo);
    if (c->program) glDeleteProgram(c->program);
    if (c->egl_surface != EGL_NO_SURFACE) eglDestroySurface(c->egl_display, c->egl_surface);
    if (c->egl_context != EGL_NO_CONTEXT) eglDestroyContext(c->egl_display, c->egl_context);
    eglTerminate(c->egl_display);
    c->egl_display = EGL_NO_DISPLAY;
}

/* ------------------------------------------------------------- pool mgmt */

static bool alloc_pool(rlawt_consumer_t *c, uint32_t width, uint32_t height) {
    int fds_pre = count_open_fds();
    c->pool_size = POOL_SIZE_DEFAULT;
    c->pool_width = width;
    c->pool_height = height;
    memset(&c->stats, 0, sizeof(c->stats));
    LOGI("alloc_pool size=%u %ux%u fds_pre=%d", c->pool_size, width, height, fds_pre);

    AHardwareBuffer_Desc desc = {
        .width = width,
        .height = height,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
               | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
               | AHARDWAREBUFFER_USAGE_CPU_READ_NEVER
               | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER,
        .stride = 0,
        .rfu0 = 0,
        .rfu1 = 0,
    };

    for (uint32_t i = 0; i < c->pool_size; i++) {
        if (AHardwareBuffer_allocate(&desc, &c->ahbs[i]) != 0) {
            LOGE("AHardwareBuffer_allocate slot=%u FAIL errno=%d", i, errno);
            return false;
        }
        AHardwareBuffer_Desc actual = {0};
        AHardwareBuffer_describe(c->ahbs[i], &actual);
        LOGI("alloc slot=%u ahb=%p actual=%ux%u stride=%u format=%u usage=0x%llx",
            i, (void *)c->ahbs[i], actual.width, actual.height, actual.stride,
            actual.format, (unsigned long long)actual.usage);

        EGLClientBuffer cbuf = c->fpEglGetNativeClientBufferANDROID(c->ahbs[i]);
        if (!cbuf) {
            LOGE("getNativeClientBuffer slot=%u NULL egl_err=0x%x", i, eglGetError());
            return false;
        }
        const EGLint img_attribs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        c->egl_images[i] = c->fpEglCreateImageKHR(c->egl_display, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, cbuf, img_attribs);
        if (!c->egl_images[i]) {
            LOGE("eglCreateImage slot=%u FAIL egl_err=0x%x", i, eglGetError());
            return false;
        }

        glGenTextures(1, &c->textures[i]);
        glBindTexture(GL_TEXTURE_2D, c->textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        c->fpEGLImageTargetTex2D(GL_TEXTURE_2D, c->egl_images[i]);
        GLenum gl_err = glGetError();
        LOGI("attach slot=%u tex=%u image=%p gl_err_after_bind=0x%x",
            i, c->textures[i], c->egl_images[i], gl_err);
    }
    int fds_post = count_open_fds();
    LOGI("alloc_pool DONE fds %d->%d (delta=%d)", fds_pre, fds_post, fds_post - fds_pre);
    return true;
}

static void drain_pool(rlawt_consumer_t *c) {
    int fds_pre = count_open_fds();
    uint32_t old_size = c->pool_size;
    for (uint32_t i = 0; i < c->pool_size; i++) {
        if (c->textures[i]) { glDeleteTextures(1, &c->textures[i]); c->textures[i] = 0; }
        if (c->egl_images[i]) { c->fpEglDestroyImageKHR(c->egl_display, c->egl_images[i]); c->egl_images[i] = NULL; }
        if (c->ahbs[i]) { AHardwareBuffer_release(c->ahbs[i]); c->ahbs[i] = NULL; }
    }
    c->pool_size = 0;
    int fds_post = count_open_fds();
    LOGI("drain_pool old_size=%u fds %d->%d (delta=%d) total_blits_so_far=%llu",
        old_size, fds_pre, fds_post, fds_post - fds_pre,
        (unsigned long long)c->stats.total_blits);
}

/* Send pool: POOL_HELLO, then N×{POOL_BUFFER hdr, AHB handle}, then POOL_READY. */
static bool send_pool(rlawt_consumer_t *c) {
    struct rlawt_surface_pool_hello ph = {
        .pool_size = (uint16_t)c->pool_size,
        .format = RLAWT_SURFACE_FORMAT_RGBA8,
        .width = c->pool_width,
        .height = c->pool_height,
        .stride_pixels = c->pool_width,
        .reserved = 0,
        .usage = (uint64_t)(AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER),
    };
    LOGI("send_pool: pool_size=%u %ux%u usage=0x%llx",
        ph.pool_size, ph.width, ph.height, (unsigned long long)ph.usage);
    if (!send_msg(c->client_fd, RLAWT_MSG_POOL_HELLO, &ph, sizeof(ph))) return false;

    for (uint32_t i = 0; i < c->pool_size; i++) {
        struct rlawt_surface_pool_buffer pb = { .index = (uint16_t)i, .reserved = 0 };
        if (!send_msg(c->client_fd, RLAWT_MSG_POOL_BUFFER, &pb, sizeof(pb))) return false;
        int fds_pre = count_open_fds();
        if (AHardwareBuffer_sendHandleToUnixSocket(c->ahbs[i], c->client_fd) != 0) {
            LOGE("AHardwareBuffer_sendHandleToUnixSocket slot=%u FAIL errno=%d", i, errno);
            return false;
        }
        int fds_post = count_open_fds();
        LOGI("AHB sent slot=%u ahb=%p fds %d->%d (delta=%d)",
            i, (void *)c->ahbs[i], fds_pre, fds_post, fds_post - fds_pre);
    }
    bool ok = send_msg(c->client_fd, RLAWT_MSG_POOL_READY, NULL, 0);
    if (ok) LOGI("send_pool DONE fds=%d", count_open_fds());
    return ok;
}

/* ------------------------------------------------------ blit + frame loop */

static void blit_index(rlawt_consumer_t *c, uint32_t index) {
    struct timespec t_pre, t_after_draw, t_after_swap;
    clock_gettime(CLOCK_MONOTONIC, &t_pre);

    glViewport(0, 0, ANativeWindow_getWidth(c->window), ANativeWindow_getHeight(c->window));
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(c->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, c->textures[index]);
    glUniform1i(c->uniform_tex, 0);
    glBindBuffer(GL_ARRAY_BUFFER, c->vbo);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float)*4, (const void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(float)*4, (const void *)(sizeof(float)*2));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    clock_gettime(CLOCK_MONOTONIC, &t_after_draw);

    EGLBoolean swapped = eglSwapBuffers(c->egl_display, c->egl_surface);
    clock_gettime(CLOCK_MONOTONIC, &t_after_swap);

    if (!swapped) {
        LOGE("eglSwapBuffers FAIL idx=%u egl_err=0x%x", index, eglGetError());
    }
    GLenum gl_err = glGetError();

    /* Per-frame metrics. */
    uint64_t draw_us = ts_delta_us(&t_after_draw, &t_pre);
    uint64_t swap_us = ts_delta_us(&t_after_swap, &t_after_draw);
    c->stats.total_blits++;
    c->stats.window_blits++;
    c->stats.window_blit_us += draw_us;
    c->stats.window_eglswap_us += swap_us;

    /* First 5 blits always log; afterwards every BLIT_LOG_PERIOD frames. */
    if (c->stats.total_blits <= 5 || (c->stats.total_blits % BLIT_LOG_PERIOD) == 0) {
        uint64_t avg_blit = c->stats.window_blits ? (c->stats.window_blit_us / c->stats.window_blits) : 0;
        uint64_t avg_swap = c->stats.window_blits ? (c->stats.window_eglswap_us / c->stats.window_blits) : 0;
        LOGI("blit total=%llu idx=%u draw_us=%llu swap_us=%llu (window avg draw=%llu swap=%llu over %llu frames) gl_err=0x%x",
            (unsigned long long)c->stats.total_blits, index,
            (unsigned long long)draw_us, (unsigned long long)swap_us,
            (unsigned long long)avg_blit, (unsigned long long)avg_swap,
            (unsigned long long)c->stats.window_blits, gl_err);
        c->stats.window_blits = 0;
        c->stats.window_blit_us = 0;
        c->stats.window_eglswap_us = 0;
    }
    c->stats.last_blit_ts = t_after_swap;
}


static bool run_session(rlawt_consumer_t *c) {
    LOGI("run_session START client_fd=%d fds=%d", c->client_fd, count_open_fds());

    /* 1. recv PRODUCER_HELLO. */
    struct rlawt_surface_hdr hdr;
    struct rlawt_surface_producer_hello hello;
    int n = recv_msg(c->client_fd, &hdr, &hello, sizeof(hello));
    if (n < (int)sizeof(hello) || hdr.type != RLAWT_MSG_PRODUCER_HELLO) {
        LOGE("expected PRODUCER_HELLO got type=%u (%s) body=%d", hdr.type, msg_name(hdr.type), n);
        return false;
    }
    LOGI("PRODUCER_HELLO requested=%ux%u format=%u flags=0x%x",
         hello.requested_width, hello.requested_height, hello.requested_format, hello.flags);

    if (!alloc_pool(c, hello.requested_width, hello.requested_height)) return false;
    if (!send_pool(c)) return false;

    /* 2. main loop */
    while (!atomic_load(&c->stopping)) {
        struct rlawt_surface_hdr h;
        char body[64];
        int rn = recv_msg(c->client_fd, &h, body, sizeof(body));
        if (rn < 0) {
            LOGW("main loop ended (recv<0) total_blits=%llu errno=%d",
                (unsigned long long)c->stats.total_blits, errno);
            return false;
        }
        if (h.type == RLAWT_MSG_FRAME_READY && rn >= (int)sizeof(struct rlawt_surface_frame_ready)) {
            struct rlawt_surface_frame_ready *fr = (struct rlawt_surface_frame_ready *)body;
            if (fr->index < c->pool_size) {
                if (c->stats.total_blits < 3) {
                    LOGI("FRAME_READY idx=%u seq=%u (early frame)", fr->index, fr->frame_seq);
                }
                blit_index(c, fr->index);
                struct rlawt_surface_release rel = { .index = fr->index, .reserved = 0, .frame_seq = fr->frame_seq };
                if (!send_msg(c->client_fd, RLAWT_MSG_RELEASE, &rel, sizeof(rel))) {
                    LOGE("send RELEASE failed at frame_seq=%u", fr->frame_seq);
                    return false;
                }
            } else {
                LOGE("FRAME_READY out-of-range idx=%u pool_size=%u", fr->index, c->pool_size);
            }
        } else if (h.type == RLAWT_MSG_RESIZE_REQUEST && rn >= (int)sizeof(struct rlawt_surface_resize_request)) {
            struct rlawt_surface_resize_request *req = (struct rlawt_surface_resize_request *)body;
            LOGI("RESIZE_REQUEST current=%ux%u -> requested=%ux%u",
                c->pool_width, c->pool_height, req->width, req->height);
            drain_pool(c);
            if (!alloc_pool(c, req->width, req->height)) return false;
            if (!send_pool(c)) return false;
        } else if (h.type == RLAWT_MSG_BYE) {
            LOGI("producer sent BYE total_blits=%llu", (unsigned long long)c->stats.total_blits);
            return true;
        } else {
            LOGW("unexpected msg type=%u (%s) body=%d in main loop", h.type, msg_name(h.type), rn);
        }
    }
    LOGI("main loop ended (stopping flag set) total_blits=%llu", (unsigned long long)c->stats.total_blits);
    return true;
}

/* --------------------------------------------------------- worker thread */

static void *worker_main(void *arg) {
    rlawt_consumer_t *c = (rlawt_consumer_t *)arg;
    LOGI("worker_main START tid=%d fds=%d", (int)gettid(), count_open_fds());

    /* Bind server socket, listen. */
    c->srv_fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (c->srv_fd < 0) { LOGE("server socket() FAIL errno=%d (%s)", errno, strerror(errno)); return NULL; }
    struct sockaddr_un addr;
    socklen_t addr_len = rlawt_surface_fill_abstract_addr(&addr, c->abstract_name[0] ? c->abstract_name : NULL);
    const char *bind_name = c->abstract_name[0] ? c->abstract_name : RLAWT_SURFACE_ABSTRACT_NAME;
    if (bind(c->srv_fd, (struct sockaddr *)&addr, addr_len) < 0) {
        LOGE("bind @%s FAIL errno=%d (%s)", bind_name, errno, strerror(errno));
        return NULL;
    }
    if (listen(c->srv_fd, 1) < 0) {
        LOGE("listen FAIL errno=%d (%s)", errno, strerror(errno));
        return NULL;
    }
    LOGI("server listening @%s srv_fd=%d", bind_name, c->srv_fd);

    /* EGL must be initialised on this thread. */
    if (!egl_init(c)) {
        LOGE("egl_init FAIL — worker exiting without accepting");
        goto done;
    }

    while (!atomic_load(&c->stopping)) {
        LOGI("waiting for producer connect on @%s ...", bind_name);
        c->client_fd = accept(c->srv_fd, NULL, NULL);
        if (c->client_fd < 0) {
            if (errno == EINTR) continue;
            LOGW("accept FAIL errno=%d (%s) — exiting accept loop", errno, strerror(errno));
            break;
        }
        LOGI("accepted producer client_fd=%d fds=%d", c->client_fd, count_open_fds());
        bool ok = run_session(c);
        LOGI("session ended ok=%d total_blits=%llu — draining pool",
            ok, (unsigned long long)c->stats.total_blits);
        drain_pool(c);
        close(c->client_fd);
        c->client_fd = -1;
    }

done:
    LOGI("worker_main DONE — tearing down EGL fds=%d", count_open_fds());
    egl_teardown(c);
    if (c->srv_fd >= 0) { close(c->srv_fd); c->srv_fd = -1; }
    if (c->window) { ANativeWindow_release(c->window); c->window = NULL; }
    LOGI("worker exited fds=%d", count_open_fds());
    return NULL;
}

/* ------------------------------------------------------------------- JNI */

JNIEXPORT jlong JNICALL
Java_com_runelitetablet_directsurface_RlawtSurfaceServer_nativeStart(
    JNIEnv *env, jclass clazz, jobject surface, jstring abstract_name) {
    (void)clazz;
    LOGI("nativeStart: surface=%p abstract_name=%p fds=%d",
        (void *)surface, (void *)abstract_name, count_open_fds());

    rlawt_consumer_t *c = (rlawt_consumer_t *)calloc(1, sizeof(*c));
    if (!c) { LOGE("calloc failed"); return 0; }
    c->srv_fd = -1;
    c->client_fd = -1;
    c->egl_display = EGL_NO_DISPLAY;
    c->egl_surface = EGL_NO_SURFACE;
    c->egl_context = EGL_NO_CONTEXT;

    c->window = ANativeWindow_fromSurface(env, surface);
    if (!c->window) { LOGE("ANativeWindow_fromSurface returned NULL"); free(c); return 0; }
    LOGI("ANativeWindow_fromSurface OK window=%p (%dx%d format=%d)",
        (void *)c->window,
        ANativeWindow_getWidth(c->window),
        ANativeWindow_getHeight(c->window),
        ANativeWindow_getFormat(c->window));

    if (abstract_name) {
        const char *cname = (*env)->GetStringUTFChars(env, abstract_name, NULL);
        if (cname) {
            strncpy(c->abstract_name, cname, sizeof(c->abstract_name) - 1);
            (*env)->ReleaseStringUTFChars(env, abstract_name, cname);
            LOGI("using override abstract name='%s'", c->abstract_name);
        }
    } else {
        LOGI("using default abstract name='%s'", RLAWT_SURFACE_ABSTRACT_NAME);
    }

    if (pthread_create(&c->thread, NULL, worker_main, c) != 0) {
        LOGE("pthread_create FAIL errno=%d (%s)", errno, strerror(errno));
        ANativeWindow_release(c->window);
        free(c);
        return 0;
    }
    LOGI("nativeStart OK consumer=%p", (void *)c);
    return (jlong)c;
}

JNIEXPORT void JNICALL
Java_com_runelitetablet_directsurface_RlawtSurfaceServer_nativeStop(
    JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    rlawt_consumer_t *c = (rlawt_consumer_t *)handle;
    if (!c) { LOGW("nativeStop: handle is NULL"); return; }
    LOGI("nativeStop consumer=%p — flagging stop and shutting sockets", (void *)c);
    atomic_store(&c->stopping, 1);
    /* shutdown sockets to break accept/recv. */
    if (c->client_fd >= 0) shutdown(c->client_fd, SHUT_RDWR);
    if (c->srv_fd >= 0)    shutdown(c->srv_fd, SHUT_RDWR);
    pthread_join(c->thread, NULL);
    LOGI("nativeStop joined; total_blits=%llu fds=%d",
        (unsigned long long)c->stats.total_blits, count_open_fds());
    free(c);
}
