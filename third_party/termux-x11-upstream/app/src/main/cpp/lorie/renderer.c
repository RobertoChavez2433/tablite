#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma ide diagnostic ignored "UnusedParameter"
#pragma ide diagnostic ignored "DanglingPointer"
#pragma ide diagnostic ignored "ConstantConditionsOC"
#pragma ide diagnostic ignored "OCUnusedGlobalDeclarationInspection"
#pragma ide diagnostic ignored "UnreachableCode"
#pragma ide diagnostic ignored "OCUnusedMacroInspection"
#pragma ide diagnostic ignored "misc-no-recursion"
#pragma clang diagnostic ignored "-Wincompatible-pointer-types-discards-qualifiers"
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <media/NdkImageReader.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <time.h>
#include "list.h"
#include "lorie.h"

#define log(...) __android_log_print(ANDROID_LOG_DEBUG, "gles-renderer", __VA_ARGS__)
#define loge(...) __android_log_print(ANDROID_LOG_ERROR, "gles-renderer", __VA_ARGS__)
#define capslog(...) __android_log_print(ANDROID_LOG_INFO, "XlorieCaps", __VA_ARGS__)

static GLuint createProgram(const char* p_vertex_source, const char* p_fragment_source);

static int printEglError(char* msg, int line) {
    char descBuf[32] = {0};
    char* desc;
    int err = eglGetError();
    switch(err) {
#define E(code, text) case code: desc = (char*) text; break
        case EGL_SUCCESS: desc = NULL; // "No error"
        E(EGL_NOT_INITIALIZED, "EGL not initialized or failed to initialize");
        E(EGL_BAD_ACCESS, "Resource inaccessible");
        E(EGL_BAD_ALLOC, "Cannot allocate resources");
        E(EGL_BAD_ATTRIBUTE, "Unrecognized attribute or attribute value");
        E(EGL_BAD_CONTEXT, "Invalid EGL context");
        E(EGL_BAD_CONFIG, "Invalid EGL frame buffer configuration");
        E(EGL_BAD_CURRENT_SURFACE, "Current surface is no longer valid");
        E(EGL_BAD_DISPLAY, "Invalid EGL display");
        E(EGL_BAD_SURFACE, "Invalid surface");
        E(EGL_BAD_MATCH, "Inconsistent arguments");
        E(EGL_BAD_PARAMETER, "Invalid argument");
        E(EGL_BAD_NATIVE_PIXMAP, "Invalid native pixmap");
        E(EGL_BAD_NATIVE_WINDOW, "Invalid native window");
        E(EGL_CONTEXT_LOST, "Context lost");
#undef E
        default:
            snprintf(descBuf, sizeof(descBuf) - 1, "Unknown error (%d)", err);
            desc = descBuf;
    }

    if (desc)
        log("renderer: %s: %s (%s:%d)\n", msg, desc, __FILE__, line);

    return 0;
}

static inline __always_inline void vprintEglError(char* msg, int line) {
    printEglError(msg, line);
}

static const char* capsFormatName(uint32_t format) {
    switch (format) {
        case AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM:
            return "AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM";
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            return "AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM";
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
            return "AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM";
        default:
            return "UNKNOWN";
    }
}

static void checkGlError(int line) {
    GLenum error;
    char *desc = NULL;
    int errorCount = 0;
    while ((error = glGetError()) != GL_NO_ERROR) {
        switch (error) {
#define E(code) case code: desc = (char*)#code; break
            E(GL_INVALID_ENUM);
            E(GL_INVALID_VALUE);
            E(GL_INVALID_OPERATION);
            E(GL_STACK_OVERFLOW_KHR);
            E(GL_STACK_UNDERFLOW_KHR);
            E(GL_OUT_OF_MEMORY);
            E(GL_INVALID_FRAMEBUFFER_OPERATION);
            E(GL_CONTEXT_LOST_KHR);
            default:
                desc = "UNKNOWN";
                break;
#undef E
        }
        loge("Xlorie: GLES %d ERROR[%d]: %s (0x%04X).\n", line, errorCount, desc, error);
        errorCount++;
    }
}

#define checkGlError() checkGlError(__LINE__)

static const char vertexShaderSrc[] =
    "attribute vec4 position;\n"
    "attribute vec2 texCoords;"
    "varying vec2 outTexCoords;\n"
    "void main(void) {\n"
    "   outTexCoords = texCoords;\n"
    "   gl_Position = position;\n"
    "}\n";

#define FRAGMENT_SHADER(texture) \
    "precision mediump float;\n" \
    "varying vec2 outTexCoords;\n" \
    "uniform sampler2D texture;\n" \
    "void main(void) {\n" \
    "   gl_FragColor = texture2D(texture, outTexCoords)" texture ";\n" \
    "}\n"

static const char fragmentShaderSrc[] = FRAGMENT_SHADER();
static const char fragmentShaderBgraSrc[] = FRAGMENT_SHADER(".bgra");

static EGLDisplay egl_display = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface defaultSfc = EGL_NO_SURFACE, sfc = EGL_NO_SURFACE;
static EGLConfig cfg = 0;
static ANativeWindow *defaultWin = NULL, *win = NULL;
static volatile struct xorg_list addedBuffers, buffers, removedBuffers;

static JNIEnv* renderEnv = NULL;
static volatile bool stateChanged = false, windowChanged = false;
static volatile struct lorie_shared_server_state* pendingState = NULL;
static volatile ANativeWindow* pendingWin = NULL;

static pthread_mutex_t stateLock;
static pthread_cond_t stateCond;
static pthread_cond_t stateChangeFinishCond;
static pthread_spinlock_t bufferLock;
static volatile struct lorie_shared_server_state* state = NULL;
static struct {
    GLuint id;
    bool cursorChanged;
} cursor;

static struct {
    uint64_t windowStartNs;
    uint64_t lastFrameStartNs;
    uint64_t frameCount;
    uint64_t totalLockNs;
    uint64_t totalFenceNs;
    uint64_t totalSwapNs;
    uint64_t totalFrameNs;
    uint64_t totalInterFrameNs;
    uint64_t maxLockNs;
    uint64_t maxFenceNs;
    uint64_t maxSwapNs;
    uint64_t maxFrameNs;
    uint64_t maxInterFrameNs;
    uint64_t contentStarved;    // vsync wakeups with no new content (drawRequested=false)
    uint64_t vsyncWakeups;      // total vsync wakeups (content + starved)
    uint64_t waitChoreographerNs; // time blocked by waitForNextFrame
    uint64_t waitContentNs;       // time blocked waiting for drawRequested
    uint64_t waitStateNs;         // time blocked on state/window/buffer changes
} rendererPerfStats;

GLuint g_texture_program = 0, gv_pos = 0, gv_coords = 0;
GLuint g_texture_program_bgra = 0, gv_pos_bgra = 0, gv_coords_bgra = 0;

static void* rendererThread(void);

static uint64_t rendererNowNs(void) {
    struct timespec ts = {0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((uint64_t) ts.tv_sec * 1000000000ULL) + (uint64_t) ts.tv_nsec;
}

typedef enum {
    WAIT_NONE = 0,
    WAIT_CHOREOGRAPHER,   // waitForNextFrame is true
    WAIT_CONTENT,         // no drawRequested / cursor changes
    WAIT_STATE,           // no state, no surface, waiting for buffers
} WaitReason;

WaitReason classifyWaitReason(bool hasState, bool surfaceAvailable, bool waitForNextFrame,
                              bool waitingForBuffers, bool drawRequested, bool cursorMoved,
                              bool cursorUpdated, bool stateChanged, bool windowChanged,
                              bool buffersChanged) {
    if (stateChanged || windowChanged || buffersChanged)
        return WAIT_NONE;
    if (!hasState || !surfaceAvailable || waitingForBuffers)
        return WAIT_STATE;
    if (waitForNextFrame)
        return WAIT_CHOREOGRAPHER;
    if (!drawRequested && !cursorMoved && !cursorUpdated)
        return WAIT_CONTENT;
    return WAIT_NONE;
}

static void rendererLogPerfSample(uint64_t frameStartNs, uint64_t lockNs, uint64_t fenceNs, uint64_t swapNs, uint64_t frameNs) {
    uint64_t interFrameNs = 0;
    if (!rendererPerfStats.windowStartNs)
        rendererPerfStats.windowStartNs = frameStartNs;

    if (rendererPerfStats.lastFrameStartNs)
        interFrameNs = frameStartNs - rendererPerfStats.lastFrameStartNs;
    rendererPerfStats.lastFrameStartNs = frameStartNs;

    rendererPerfStats.frameCount++;
    rendererPerfStats.totalLockNs += lockNs;
    rendererPerfStats.totalFenceNs += fenceNs;
    rendererPerfStats.totalSwapNs += swapNs;
    rendererPerfStats.totalFrameNs += frameNs;
    rendererPerfStats.maxLockNs = max(rendererPerfStats.maxLockNs, lockNs);
    rendererPerfStats.maxFenceNs = max(rendererPerfStats.maxFenceNs, fenceNs);
    rendererPerfStats.maxSwapNs = max(rendererPerfStats.maxSwapNs, swapNs);
    rendererPerfStats.maxFrameNs = max(rendererPerfStats.maxFrameNs, frameNs);

    if (interFrameNs > 0) {
        rendererPerfStats.totalInterFrameNs += interFrameNs;
        rendererPerfStats.maxInterFrameNs = max(rendererPerfStats.maxInterFrameNs, interFrameNs);
    }

    if ((frameStartNs - rendererPerfStats.windowStartNs) < 1000000000ULL)
        return;

    double frameCount = (double) rendererPerfStats.frameCount;
    double intervalCount = rendererPerfStats.frameCount > 1 ? (double) (rendererPerfStats.frameCount - 1) : 0.0;
    double avgLockMs = rendererPerfStats.totalLockNs / 1000000.0 / frameCount;
    double avgFenceMs = rendererPerfStats.totalFenceNs / 1000000.0 / frameCount;
    double avgSwapMs = rendererPerfStats.totalSwapNs / 1000000.0 / frameCount;
    double avgFrameMs = rendererPerfStats.totalFrameNs / 1000000.0 / frameCount;
    double avgInterFrameMs = intervalCount > 0.0 ? (rendererPerfStats.totalInterFrameNs / 1000000.0 / intervalCount) : 0.0;
    double estimatedFps = avgInterFrameMs > 0.0 ? (1000.0 / avgInterFrameMs) : 0.0;

    double contentUtil = rendererPerfStats.vsyncWakeups > 0
        ? (100.0 * rendererPerfStats.frameCount / rendererPerfStats.vsyncWakeups)
        : 0.0;
    double waitChoreoMs = rendererPerfStats.waitChoreographerNs / 1000000.0;
    double waitContentMs = rendererPerfStats.waitContentNs / 1000000.0;
    double waitStateMs = rendererPerfStats.waitStateNs / 1000000.0;

    log(
        "XloriePerf: frames=%llu avg_lock_ms=%.3f avg_fence_ms=%.3f avg_swap_ms=%.3f avg_frame_ms=%.3f avg_inter_frame_ms=%.3f estimated_fps=%.1f"
        " content_util=%.0f%% starved=%llu vsyncs=%llu"
        " max_lock_ms=%.3f max_fence_ms=%.3f max_swap_ms=%.3f max_frame_ms=%.3f max_inter_frame_ms=%.3f"
        " wait_choreo=%.1fms wait_content=%.1fms wait_state=%.1fms",
        (unsigned long long) rendererPerfStats.frameCount,
        avgLockMs, avgFenceMs, avgSwapMs, avgFrameMs, avgInterFrameMs, estimatedFps,
        contentUtil,
        (unsigned long long) rendererPerfStats.contentStarved,
        (unsigned long long) rendererPerfStats.vsyncWakeups,
        rendererPerfStats.maxLockNs / 1000000.0,
        rendererPerfStats.maxFenceNs / 1000000.0,
        rendererPerfStats.maxSwapNs / 1000000.0,
        rendererPerfStats.maxFrameNs / 1000000.0,
        rendererPerfStats.maxInterFrameNs / 1000000.0,
        waitChoreoMs, waitContentMs, waitStateMs
    );

    rendererPerfStats.windowStartNs = frameStartNs;
    rendererPerfStats.frameCount = 0;
    rendererPerfStats.totalLockNs = 0;
    rendererPerfStats.totalFenceNs = 0;
    rendererPerfStats.totalSwapNs = 0;
    rendererPerfStats.totalFrameNs = 0;
    rendererPerfStats.totalInterFrameNs = 0;
    rendererPerfStats.maxLockNs = 0;
    rendererPerfStats.maxFenceNs = 0;
    rendererPerfStats.maxSwapNs = 0;
    rendererPerfStats.maxFrameNs = 0;
    rendererPerfStats.contentStarved = 0;
    rendererPerfStats.vsyncWakeups = 0;
    rendererPerfStats.maxInterFrameNs = 0;
    rendererPerfStats.waitChoreographerNs = 0;
    rendererPerfStats.waitContentNs = 0;
    rendererPerfStats.waitStateNs = 0;
}

static void pthreadCondVarProxyInit(void);
static void* pthreadCondVarProxyThread(void* cookie);
static void pthreadCondVarProxyListenOtherCondVar(pthread_cond_t* var);

static inline __always_inline void bindLinearTexture(GLuint id) {
    glBindTexture(GL_TEXTURE_2D, id);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

static EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,
        EGL_NONE
};

const EGLint ctxattribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE
};

static void onImageAvailable(void* context, AImageReader* reader) {
    (void) context;
    AImage* image = NULL;
    if (AImageReader_acquireLatestImage(reader, &image) == AMEDIA_OK && image)
        AImage_delete(image);
}

int rendererInitThread(void) {
    EGLint major, minor;
    EGLint numConfigs;
    EGLint *const alphaAttrib = &configAttribs[11];
    AImageReader* reader = NULL; // We will use this ImageReader each time surface is destroyed, zero reasons to clean it up

    pthread_setname_np(pthread_self(), "LorieRendererThread");

    xorg_list_init(&addedBuffers);
    xorg_list_init(&buffers);
    xorg_list_init(&removedBuffers);

    egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl_display == EGL_NO_DISPLAY)
        return printEglError("Got no EGL display", __LINE__);

    if (eglInitialize(egl_display, &major, &minor) != EGL_TRUE)
        return printEglError("Unable to initialize EGL", __LINE__);

    log("Xlorie: Initialized EGL version %d.%d\n", major, minor);
    eglBindAPI(EGL_OPENGL_ES_API);

    if (eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE &&
        (*alphaAttrib = 8) &&
        eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE)
        return printEglError("eglChooseConfig failed", __LINE__);

    ctx = eglCreateContext(egl_display, cfg, NULL, ctxattribs);
    if (ctx == EGL_NO_CONTEXT)
        return printEglError("eglCreateContext failed", __LINE__);

    // Weird devices without proper EGL_KHR_surfaceless_context support
    // We can not use pbuffer-based surfaces because it will require searching for configs supporting it
    // and I am not sure all devices have configs supporting both pbuffers and regular surfaces simultaneously
    //
    // Upstream eed4085a (2026-04-26): replaced the SurfaceTexture+Surface JNI dance
    // (which required AttachCurrentThread on the renderer thread purely to construct
    // a Java SurfaceTexture) with a pure-NDK AImageReader. The native consumer
    // listener is a no-op: we only need a valid ANativeWindow to bind a default EGL
    // surface against; the listener immediately deletes any image to keep the queue
    // drained.
    if (AImageReader_new(1, 1, AIMAGE_FORMAT_RGBA_8888, 2, &reader) != AMEDIA_OK) {
        log("Failed to initialise ImageReader");
        return 1;
    }

    if (AImageReader_setImageListener(reader, &(AImageReader_ImageListener) { .context = NULL, .onImageAvailable = onImageAvailable }) != AMEDIA_OK) {
        log("Failed to set ImageReader listener");
        AImageReader_delete(reader);
        return 1;
    }

    if (AImageReader_getWindow(reader, &defaultWin) != AMEDIA_OK) {
        log("Failed to obtain ImageReader native window");
        AImageReader_delete(reader);
        return 1;
    }

    win = defaultWin;
    ANativeWindow_acquire(defaultWin);

    sfc = defaultSfc = eglCreateWindowSurface(egl_display, cfg, win, NULL);

    eglMakeCurrent(egl_display, sfc, sfc, ctx);
    eglSwapInterval(egl_display, 0);

    g_texture_program = createProgram(vertexShaderSrc, fragmentShaderSrc);
    if (!g_texture_program)
        loge("Xlorie: GLESv2: Unable to create shader program.\n");
    else
        log("rendererInit: shader program created id=%u", g_texture_program);

    g_texture_program_bgra = createProgram(vertexShaderSrc, fragmentShaderBgraSrc);
    if (!g_texture_program_bgra)
        loge("Xlorie: GLESv2: Unable to create bgra shader program.\n");
    else
        log("rendererInit: bgra shader program created id=%u", g_texture_program_bgra);

    gv_pos = (GLuint) glGetAttribLocation(g_texture_program, "position");
    gv_coords = (GLuint) glGetAttribLocation(g_texture_program, "texCoords");

    gv_pos_bgra = (GLuint) glGetAttribLocation(g_texture_program_bgra, "position");
    gv_coords_bgra = (GLuint) glGetAttribLocation(g_texture_program_bgra, "texCoords");

    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, &cursor.id);

    log("rendererInit: display=%p context=%p shaders=%d/%d programs=%u/%u cursor=%u",
        egl_display, ctx,
        g_texture_program ? 1 : 0, g_texture_program_bgra ? 1 : 0,
        g_texture_program, g_texture_program_bgra, cursor.id);

    rendererThread();
    return 1;
}

void rendererInit(JNIEnv* env) {
    (void) env;
    pthread_t t;

    if (ctx)
        return;

    pthreadCondVarProxyInit();

    pthread_mutex_init(&stateLock, NULL);
    pthread_cond_init(&stateCond, NULL);
    pthread_cond_init(&stateChangeFinishCond, NULL);
    pthread_spin_init(&bufferLock, false);

    pthread_create(&t, NULL, (void*(*)(void*)) rendererInitThread, NULL);
}

void rendererTestCapabilities(int* legacy_drawing, uint8_t* flip) {
    // Some devices do not support sampling from HAL_PIXEL_FORMAT_BGRA_8888, here we are checking it.
    const EGLint imageAttributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLint major = 0, minor = 0;
    EGLint numConfigs;
    EGLClientBuffer clientBuffer;
    EGLImageKHR img;
    AHardwareBuffer *new = NULL;
    int status;
    uint32_t probeFormat = AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
    bool usingRgbaFallback = false;
    AHardwareBuffer_Desc d0 = {
            .width = 64,
            .height = 64,
            .layers = 1,
            .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            .format = probeFormat
    };

    *legacy_drawing = 0;
    *flip = 0;

    if (egl_display == EGL_NO_DISPLAY) {
        egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (egl_display == EGL_NO_DISPLAY)
            return vprintEglError("Got no EGL display", __LINE__);
    }
    if (eglInitialize(egl_display, &major, &minor) != EGL_TRUE) {
        *legacy_drawing = 1;
        capslog("eglInitialize failed legacy_drawing=%d flip=%u", *legacy_drawing, *flip);
        return vprintEglError("Unable to initialize EGL for capability probe", __LINE__);
    }
    capslog("eglInitialize major=%d minor=%d", major, minor);

retry_probe:
    d0.format = probeFormat;
    status = AHardwareBuffer_allocate(&d0, &new);
    capslog(
            "AHardwareBuffer_allocate format=%s(%u) status=%d buffer=%p",
            capsFormatName(d0.format),
            d0.format,
            status,
            new
    );
    if (status != 0 || new == NULL) {
        loge("Failed to allocate native buffer (%p, error %d)", new, status);
        loge("Forcing legacy drawing");
        *legacy_drawing = 1;
        capslog("decision format=%s(%u) legacy_drawing=%d flip=%u", capsFormatName(d0.format), d0.format, *legacy_drawing, *flip);
        return;
    }

    uint32_t *pixels;
    if (AHardwareBuffer_lock(new, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void **) &pixels) == 0) {
        pixels[0] = 0xAABBCCDD;
        AHardwareBuffer_unlock(new, NULL);
    } else {
        loge("Failed to lock native buffer (%p, error %d)", new, status);
        loge("Forcing legacy drawing");
        *legacy_drawing = 1;
        AHardwareBuffer_release(new);
        capslog("decision format=%s(%u) legacy_drawing=%d flip=%u", capsFormatName(d0.format), d0.format, *legacy_drawing, *flip);
        return;
    }

    clientBuffer = eglGetNativeClientBufferANDROID(new);
    capslog(
            "eglGetNativeClientBufferANDROID format=%s(%u) clientBuffer=%p",
            capsFormatName(d0.format),
            d0.format,
            clientBuffer
    );
    if (!clientBuffer) {
        *legacy_drawing = 1;
        AHardwareBuffer_release(new);
        capslog("eglGetNativeClientBufferANDROID failed format=%s(%u)", capsFormatName(d0.format), d0.format);
        capslog("decision format=%s(%u) legacy_drawing=%d flip=%u", capsFormatName(d0.format), d0.format, *legacy_drawing, *flip);
        return vprintEglError("Failed to obtain EGLClientBuffer from AHardwareBuffer, forcing legacy drawing", __LINE__);
    }

    if (!(img = eglCreateImageKHR(egl_display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttributes))) {
        EGLint imageError = eglGetError();
        capslog(
                "eglCreateImageKHR format=%s(%u) image=%p eglError=0x%04X",
                capsFormatName(d0.format),
                d0.format,
                img,
                imageError
        );
        if (imageError == EGL_BAD_PARAMETER && !usingRgbaFallback) {
            loge("Sampling from HAL_PIXEL_FORMAT_BGRA_8888 is not supported, retrying with AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM");
            *flip = 1;
            usingRgbaFallback = true;
            probeFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            AHardwareBuffer_release(new);
            new = NULL;
            goto retry_probe;
        } else {
            loge("Failed to obtain EGLImageKHR from EGLClientBuffer (eglError=0x%04X)", imageError);
            loge("Forcing legacy drawing");
            *legacy_drawing = 1;
        }
        AHardwareBuffer_release(new);
        capslog("decision format=%s(%u) legacy_drawing=%d flip=%u", capsFormatName(d0.format), d0.format, *legacy_drawing, *flip);
    } else {
        capslog(
                "eglCreateImageKHR format=%s(%u) image=%p eglError=0x%04X",
                capsFormatName(d0.format),
                d0.format,
                img,
                EGL_SUCCESS
        );
        // For some reason all devices I checked had no GL_EXT_texture_format_BGRA8888 support, but some of them still provided BGRA extension.
        // EGL does not provide functions to query texture format in runtime.
        // Workarounds are less performant but at least they let us use Termux:X11 on devices with missing BGRA support.
        // We handle two cases.
        // If resulting texture has BGRA format but still drawing RGBA we should flip format to RGBA and flip pixels manually in shader.
        // In the case if for some reason we can not use HAL_PIXEL_FORMAT_BGRA_8888 we should fallback to legacy drawing method (uploading pixels via glTexImage2D).
        configAttribs[1] = EGL_PBUFFER_BIT;
        EGLConfig checkcfg = 0;
        GLuint fbo = 0, texture = 0;
        if (eglChooseConfig(egl_display, configAttribs, &checkcfg, 1, &numConfigs) != EGL_TRUE)
            return vprintEglError("check eglChooseConfig failed", __LINE__);

        EGLContext testctx = eglCreateContext(egl_display, checkcfg, NULL, ctxattribs);
        if (testctx == EGL_NO_CONTEXT)
            return vprintEglError("check eglCreateContext failed", __LINE__);

        const EGLint pbufferAttributes[] = {
                EGL_WIDTH, 64,
                EGL_HEIGHT, 64,
                EGL_NONE,
        };
        EGLSurface checksfc = eglCreatePbufferSurface(egl_display, checkcfg, pbufferAttributes);

        if (eglMakeCurrent(egl_display, checksfc, checksfc, testctx) != EGL_TRUE)
            return vprintEglError("check eglMakeCurrent failed", __LINE__);

        glActiveTexture(GL_TEXTURE0); checkGlError();
        glGenTextures(1, &texture); checkGlError();
        bindLinearTexture(texture);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img); checkGlError();
        glGenFramebuffers(1, &fbo); checkGlError();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo); checkGlError();
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0); checkGlError();
        uint32_t pixel[64*64];
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, &pixel); checkGlError();
        capslog(
                "glReadPixels format=%s(%u) usingRgbaFallback=%d actual=0x%08X expectedDirect=0xAADDCCBB expectedFlip=0xAABBCCDD",
                capsFormatName(d0.format),
                d0.format,
                usingRgbaFallback ? 1 : 0,
                pixel[0]
        );
        if (pixel[0] == 0xAABBCCDD) {
            log("Xlorie: GLES draws pixels unchanged, probably system does not support AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM. Forcing bgra.\n");
            *flip = 1;
        } else if (pixel[0] != 0xAADDCCBB) {
            // On RGBA fallback path (Mali/VirGL), accept pixel values where RGB channels
            // are present even if alpha differs (VirGL/ANGLE may modify alpha).
            uint8_t r = (pixel[0] >> 0) & 0xFF;
            uint8_t g = (pixel[0] >> 8) & 0xFF;
            uint8_t b = (pixel[0] >> 16) & 0xFF;
            if (usingRgbaFallback && r == 0xDD && g == 0xCC && b == 0xBB) {
                log("Xlorie: RGBA fallback pixel alpha differs (0x%X) but RGB correct, accepting.\n", pixel[0]);
            } else {
                log("Xlorie: GLES receives broken pixels. Forcing legacy drawing. 0x%X (rgba_fallback=%d)\n", pixel[0], usingRgbaFallback ? 1 : 0);
                *legacy_drawing = 1;
            }
        }
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(egl_display, testctx);
        eglDestroyImageKHR(egl_display, img);
        eglDestroySurface(egl_display, checksfc);
        AHardwareBuffer_release(new);
        capslog("decision format=%s(%u) legacy_drawing=%d flip=%u", capsFormatName(d0.format), d0.format, *legacy_drawing, *flip);
    }
}

__unused void rendererSetSharedState(struct lorie_shared_server_state* newState) {
    pthread_mutex_lock(&stateLock);
    pendingState = newState;
    stateChanged = true;
    pthread_cond_signal(&stateCond);

    while(stateChanged)
        pthread_cond_wait(&stateChangeFinishCond, &stateLock);

    pthread_mutex_unlock(&stateLock);
}

void rendererAddBuffer(LorieBuffer* buf) {
    pthread_spin_lock(&bufferLock);
    LorieBuffer_addToList(buf, &addedBuffers);
    pthread_cond_signal(&stateCond);
    pthread_spin_unlock(&bufferLock);
}

void rendererRemoveBuffer(uint64_t id) {
    pthread_spin_lock(&bufferLock);
    LorieBuffer* buf = LorieBufferList_findById(&addedBuffers, id);
    if (buf)
        // Buffer was not attached to GL yet, it is safe to release it now.
        LorieBuffer_release(buf);
    else {
        buf = LorieBufferList_findById(&buffers, id);
        if (buf) {
            // The buffer is attached to GL so we should release it from renderer thread.
            LorieBuffer_removeFromList(buf);
            LorieBuffer_addToList(buf, &removedBuffers);
        }
    }
    pthread_spin_unlock(&bufferLock);
}

void rendererRemoveAllBuffers(void) {
    LorieBuffer *buf = NULL;

    pthread_spin_lock(&bufferLock);
    while ((buf = LorieBufferList_first(&addedBuffers))) {
        // These buffers are not yet attached to GL, it is safe to release them
        LorieBuffer_release(buf);
    }
    while ((buf = LorieBufferList_first(&buffers))) {
        // These buffers are attached to GL, we must release them from renderer thread.
        LorieBuffer_removeFromList(buf);
        LorieBuffer_addToList(buf, &removedBuffers);
    }
    pthread_spin_unlock(&bufferLock);
}

void rendererSetWindow(ANativeWindow* newWin) {
    pthread_mutex_lock(&stateLock);
    if (newWin && pendingWin == newWin) {
        ANativeWindow_release(newWin);
        pthread_mutex_unlock(&stateLock);
        return;
    }

    if (pendingWin)
        ANativeWindow_release(pendingWin);

    pendingWin = newWin;
    windowChanged = TRUE;

    pthread_cond_signal(&stateCond);

    // We should wait until renderer destroys EGLSurface before SurfaceCallback::surfaceDestroyed finishes
    // Otherwise we will have weird errors like
    // `freeAllBuffers: 1 buffers were freed while being dequeued!`
    // or
    // `query: BufferQueue has been abandoned`
    while(windowChanged)
        pthread_cond_wait(&stateChangeFinishCond, &stateLock);

    pthread_mutex_unlock(&stateLock);
}

static inline __always_inline void releaseWinAndSurface(ANativeWindow** anw, EGLSurface *esfc) {
    if (esfc && *esfc && *esfc != defaultSfc) {
        // Requeue the dequeued buffer, causes flickering during window reconfiguring
        eglSwapBuffers(egl_display, *esfc);
        if (eglMakeCurrent(egl_display, defaultSfc, defaultSfc, ctx) != EGL_TRUE)
            return vprintEglError("eglMakeCurrent failed (EGL_NO_SURFACE)", __LINE__);
        if (eglDestroySurface(egl_display, *esfc) != EGL_TRUE)
            return vprintEglError("eglDestoySurface failed", __LINE__);
        *esfc = defaultSfc;
    }

    if (anw && *anw && *anw != defaultWin) {
        ANativeWindow_release(*anw);
        *anw = defaultWin;
    }
}

void rendererRefreshContext(void) {
    int width = pendingWin ? ANativeWindow_getWidth(pendingWin) : 0;
    int height = pendingWin ? ANativeWindow_getHeight(pendingWin) : 0;
    log("rendererSetWindow %p %d %d", pendingWin, width, height);

    releaseWinAndSurface(&win, &sfc);

    if (pendingWin && (width <= 0 || height <= 0)) {
        log("Xlorie: We've got invalid surface. Probably it became invalid before we started working with it.\n");
        releaseWinAndSurface(&pendingWin, NULL);
    }

    win = pendingWin;
    pendingWin = NULL;
    windowChanged = FALSE;

    if (!win) {
        win = defaultWin;
        eglMakeCurrent(egl_display, defaultSfc, defaultSfc, ctx);
        if (state)
            state->surfaceAvailable = false;
        return;
    }

    sfc = eglCreateWindowSurface(egl_display, cfg, win, NULL);
    if (sfc == EGL_NO_SURFACE)
        return vprintEglError("eglCreateWindowSurface failed", __LINE__);

    if (eglMakeCurrent(egl_display, sfc, sfc, ctx) != EGL_TRUE) {
        if (state)
            state->surfaceAvailable = false;
        return vprintEglError("eglMakeCurrent failed", __LINE__);
    }

    eglSwapInterval(egl_display, 0);

    // We should redraw image at least once right after surface change
    if (state)
        state->surfaceAvailable = state->drawRequested = state->cursor.updated = win != defaultWin;

    glViewport(0, 0, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win));
    log("Xlorie: new surface applied: %p\n", sfc);
}

static void draw(GLuint id, float x0, float y0, float x1, float y1, float xfactor, uint8_t flip);
static void drawCursor(float displayWidth, float displayHeight);

void rendererRedrawLocked(bool* waitingForBuffers) {
    float xfactor = 1.f;
    LorieBuffer_Desc *desc = NULL;
    EGLSync fence;
    uint64_t frameStartNs = rendererNowNs();
    uint64_t lockStartNs;
    uint64_t fenceStartNs;
    uint64_t swapStartNs;
    uint64_t lockNs;
    uint64_t fenceNs;
    uint64_t swapNs;
    uint64_t frameEndNs;
    // The buffer will not be released until this function ends, but main thread can modify buffer list
    pthread_spin_lock(&bufferLock);
    LorieBuffer *buffer = LorieBufferList_findById(&buffers, state->rootWindowTextureID);
    // Probably X server requested us to draw removed buffer and immediately requested to remove it. Let's display it one last time.
    if (!buffer)
        buffer = LorieBufferList_findById(&removedBuffers, state->rootWindowTextureID);
    if (!buffer)
        *waitingForBuffers = true;
    pthread_spin_unlock(&bufferLock);
    if (!buffer) {
        log("Buffer %llu not found", state->rootWindowTextureID);
        return;
    }

    desc = LorieBuffer_description(buffer);

    // We should signal X server to not use root window while we actively copy it
    lockStartNs = rendererNowNs();
    lorie_mutex_lock(&state->lock, &state->lockingPid);
    lockNs = rendererNowNs() - lockStartNs;
    state->drawRequested = FALSE;

    LorieBuffer_bindTexture(buffer);
    if (desc->type == LORIEBUFFER_FD)
        xfactor = (float) desc->width/(float) desc->stride;
    draw(0, -1.f, -1.f, 1.f, 1.f, xfactor, LorieBuffer_isRgba(buffer));
    fence = eglCreateSyncKHR(egl_display, EGL_SYNC_FENCE_KHR, NULL);
    glFlush();

    if (state->cursor.updated) {
        log("Xlorie: updating cursor\n");
        lorie_mutex_lock(&state->cursor.lock, &state->cursor.lockingPid);
        state->cursor.updated = false;
        bindLinearTexture(cursor.id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei) state->cursor.width, (GLsizei) state->cursor.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, state->cursor.bits);
        lorie_mutex_unlock(&state->cursor.lock, &state->cursor.lockingPid);
    }

    state->cursor.moved = FALSE;
    drawCursor((float) (LorieBuffer_getWidth(buffer)), (float) (LorieBuffer_getHeight(buffer)));
    glFlush();

    // Wait until root window drawing is finished before giving control back to X server.
    // Upstream fix d69385b9 (2026-04-20): pass EGL_SYNC_FLUSH_COMMANDS_BIT_KHR so any
    // pending GL commands (including the fence itself) are flushed before waiting —
    // without this, eglClientWaitSyncKHR can hang indefinitely when the fence was
    // created after glClear() with no explicit flush in between.
    fenceStartNs = rendererNowNs();
    eglClientWaitSyncKHR(egl_display, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER);
    fenceNs = rendererNowNs() - fenceStartNs;
    eglDestroySyncKHR(egl_display, fence);
    // Phase 2B: Removed waitForNextFrame=true — was capping FPS by forcing 2-vsync wait.
    // Choreographer still clears the flag in lorieChoreographerFrameCallback().
    lorie_mutex_unlock(&state->lock, &state->lockingPid);

    swapStartNs = rendererNowNs();
    if (eglSwapBuffers(egl_display, sfc) != EGL_TRUE)
        printEglError("Failed to swap buffers", __LINE__);
    swapNs = rendererNowNs() - swapStartNs;

    state->renderedFrames++;
    frameEndNs = rendererNowNs();
    rendererLogPerfSample(frameStartNs, lockNs, fenceNs, swapNs, frameEndNs - frameStartNs);
}

static inline __always_inline bool rendererShouldWait(bool *waitingForBuffers) {
    static uint64_t lastRequestedBufferId = 0;
    bool buffersChanged;
    pthread_spin_lock(&bufferLock);
    buffersChanged = !xorg_list_is_empty(&addedBuffers) || !xorg_list_is_empty(&removedBuffers);
    pthread_spin_unlock(&bufferLock);
    if (stateChanged || windowChanged || buffersChanged)
        // If there are pending changes we should process them immediately.
        return false;

    if (state) {
        if (lastRequestedBufferId != state->rootWindowTextureID)
            *waitingForBuffers = false;
        lastRequestedBufferId = state->rootWindowTextureID;
    }

    if (!state || !state->surfaceAvailable || state->waitForNextFrame || *waitingForBuffers)
        // Even in the case if there are pending changes, we can not draw it without rendering surface
        return true;

    if (state->drawRequested || state->cursor.moved || state->cursor.updated)
        // X server reported drawing or cursor changes, no need to wait.
        return false;

    // Probably spurious wake, no changes we can work with.
    return true;
}

__noreturn static void* rendererThread(void) {
    LorieBuffer* buf;
    bool waitingForBuffers = false;
    while (true) {
        while (rendererShouldWait(&waitingForBuffers)) {
            /*
             * Attribute the wait from the state that caused us to sleep.
             * Inspecting after wake races with the signaler and mislabeled
             * content waits as state/choreographer waits.
             */
            WaitReason reason = classifyWaitReason(
                state != NULL,
                state ? state->surfaceAvailable : false,
                state ? state->waitForNextFrame : false,
                waitingForBuffers,
                state ? state->drawRequested : false,
                state ? state->cursor.moved : false,
                state ? state->cursor.updated : false,
                stateChanged, windowChanged, false);
            uint64_t waitStartNs = rendererNowNs();
            pthread_cond_wait(&stateCond, &stateLock);
            uint64_t waitElapsedNs = rendererNowNs() - waitStartNs;
            switch (reason) {
                case WAIT_CHOREOGRAPHER: rendererPerfStats.waitChoreographerNs += waitElapsedNs; break;
                case WAIT_CONTENT:       rendererPerfStats.waitContentNs += waitElapsedNs; break;
                case WAIT_STATE:         rendererPerfStats.waitStateNs += waitElapsedNs; break;
                default: break;
            }
        }

        if (stateChanged) {
            struct lorie_shared_server_state* oldState = NULL;
            if (state && pendingState != state)
                oldState = state;

            state = pendingState;
            pendingState = NULL;
            stateChanged = false;
            waitingForBuffers = false;

            if (state)
                state->surfaceAvailable = win != defaultWin;
            else if (win != defaultWin) {
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
                eglSwapBuffers(egl_display, sfc);
            }

            pthreadCondVarProxyListenOtherCondVar(state ? &state->cond : NULL);

            if (oldState)
                munmap(oldState, sizeof(*oldState));
        }

        if (windowChanged)
            rendererRefreshContext();

        // Attach all pending buffers to GL.
        pthread_spin_lock(&bufferLock);
        while((buf = LorieBufferList_first(&addedBuffers))) {
            LorieBuffer_attachToGL(buf);
            LorieBuffer_addToList(buf, &buffers);
            waitingForBuffers = false;
        }
        pthread_spin_unlock(&bufferLock);

        pthread_cond_signal(&stateChangeFinishCond);
        pthread_mutex_unlock(&stateLock);

        if (state && state->surfaceAvailable && !state->waitForNextFrame) {
            rendererPerfStats.vsyncWakeups++;
            if (state->drawRequested || state->cursor.moved || state->cursor.updated)
                rendererRedrawLocked(&waitingForBuffers);
            else
                rendererPerfStats.contentStarved++;
        }

        pthread_spin_lock(&bufferLock);
        // Remove all buffers which were attached to GL.
        while((buf = LorieBufferList_first(&removedBuffers)))
            LorieBuffer_release(buf);
        pthread_spin_unlock(&bufferLock);
        pthread_mutex_lock(&stateLock);
    }
}

static GLuint loadShader(GLenum shaderType, const char* pSource) {
    GLint compiled = 0, infoLen = 0;
    const char* typeName = (shaderType == GL_VERTEX_SHADER) ? "VERTEX" : "FRAGMENT";
    GLuint shader = glCreateShader(shaderType);
    if (!shader) {
        loge("loadShader: glCreateShader(%s) returned 0", typeName);
        return 0;
    }

    glShaderSource(shader, 1, &pSource, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "shader: type=%s status=%d", typeName, compiled);
    if (compiled) {
        log("loadShader: %s shader compiled id=%u status=OK", typeName, shader);
        return shader;
    }

    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
    if (infoLen) {
        char buf[infoLen];
        glGetShaderInfoLog(shader, infoLen, NULL, buf);
        loge("loadShader: %s shader compile FAILED id=%u log='%s'", typeName, shader, buf);
    } else {
        loge("loadShader: %s shader compile FAILED id=%u (no log)", typeName, shader);
    }
    glDeleteShader(shader);

    return 0;
}

static GLuint createProgram(const char* p_vertex_source, const char* p_fragment_source) {
    GLuint program, vertexShader, pixelShader;
    GLint linkStatus = GL_FALSE, bufLength = 0;
    vertexShader = loadShader(GL_VERTEX_SHADER, p_vertex_source);
    pixelShader = loadShader(GL_FRAGMENT_SHADER, p_fragment_source);
    if (!pixelShader || !vertexShader) {
        return 0;
    }

    program = glCreateProgram();
    if (!program)
        return 0;

    glAttachShader(program, vertexShader);
    glAttachShader(program, pixelShader);
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
    if (linkStatus == GL_TRUE)
        return program;

    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
    if (bufLength) {
        char buf[bufLength];
        glGetProgramInfoLog(program, bufLength, NULL, buf);
        log("renderer: Could not link program:\n%s\n", buf);
    }
    glDeleteProgram(program);

    return 0;
}

static void draw(GLuint id, float x0, float y0, float x1, float y1, float xfactor, uint8_t flip) {
    float coords[16] = {
        x0, -y0, 0.f, 0.f,
        x1, -y0, xfactor, 0.f,
        x0, -y1, 0.f, 1.f,
        x1, -y1, xfactor, 1.f,
    };

    GLuint p = flip ? gv_pos_bgra : gv_pos, c = flip ? gv_coords_bgra : gv_coords;

    glActiveTexture(GL_TEXTURE0);
    glUseProgram(flip ? g_texture_program_bgra : g_texture_program);
    if (id)
        glBindTexture(GL_TEXTURE_2D, id);

    glVertexAttribPointer(p, 2, GL_FLOAT, GL_FALSE, 16, coords);
    glVertexAttribPointer(c, 2, GL_FLOAT, GL_FALSE, 16, &coords[2]);
    glEnableVertexAttribArray(p);
    glEnableVertexAttribArray(c);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); checkGlError();
}

__unused static void drawCursor(float displayWidth, float displayHeight) {
    float x, y, w, h;

    if (!state->cursor.width || !state->cursor.height)
        return;

    x = 2.f * ((float) state->cursor.x - (float) state->cursor.xhot) / displayWidth - 1.f;
    y = 2.f * ((float) state->cursor.y - (float) state->cursor.yhot) / displayHeight - 1.f;
    w = 2.f * (float) state->cursor.width / displayWidth;
    h = 2.f * (float) state->cursor.height / displayHeight;
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    draw(cursor.id, x, y, x + w, y + h, 1.f, false);
    glDisable(GL_BLEND);
}

// auxillary pthread condition var proxy shenanigans
static volatile struct {
    pthread_mutex_t lock;
    pthread_cond_t def, *current, *pending;
    bool relocked;
} proxy = { .current = &proxy.def };

static void pthreadCondVarProxyInit(void) {
    pthread_t t;
    pthread_mutex_init(&proxy.lock, NULL);
    pthread_cond_init(&proxy.def, NULL);
    pthread_create(&t, NULL, pthreadCondVarProxyThread, NULL);
}

__noreturn static void* pthreadCondVarProxyThread(void* cookie) {
    // We can not wait for two conditional variables simultaneously.
    // But we are required to listen for both remote and local events.
    // This thread waits for remote signals and proxies them to the local cond var.
    pthread_setname_np(pthread_self(), "PthreadCondVarProxy");
    pthread_mutex_lock(&proxy.lock);
    log("pthreadCondVarProxyThread %ld started", pthread_self());
    while (true) {
        pthread_cond_wait(proxy.current, &proxy.lock);
        if (proxy.pending) {
            proxy.current = proxy.pending;
            proxy.pending = NULL;
        }
        proxy.relocked = true;
        pthread_cond_signal(&stateCond);
    }
}

static void pthreadCondVarProxyListenOtherCondVar(pthread_cond_t* var) {
    pthread_mutex_lock(&proxy.lock);
    pthread_cond_broadcast(proxy.current);
    proxy.pending = var ?: &proxy.def;
    proxy.relocked = false;
    while(!proxy.relocked)
        pthread_cond_wait(&stateCond, &proxy.lock);
    pthread_mutex_unlock(&proxy.lock);
}
