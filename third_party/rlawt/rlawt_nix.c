/*
 * Copyright (c) 2022 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#if defined(__unix__) && !defined(RLAWT_DIRECT_SURFACE)

#include "rlawt.h"
#include "rlt_frame_telemetry.h"
#include <jawt_md.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <X11/Xatom.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#define RLAWT_LOG(...) do { fprintf(stderr, "[rlawt] " __VA_ARGS__); fflush(stderr); } while (0)

/* GL timer-query constants (avoid <GL/glext.h> dep). */
#ifndef RLAWT_GL_TIME_ELAPSED
#define RLAWT_GL_TIME_ELAPSED           0x88BF
#endif
#ifndef RLAWT_GL_QUERY_RESULT
#define RLAWT_GL_QUERY_RESULT           0x8866
#endif
#ifndef RLAWT_GL_QUERY_RESULT_AVAILABLE
#define RLAWT_GL_QUERY_RESULT_AVAILABLE 0x8867
#endif

/* --- perf helpers --------------------------------------------------------- */

static inline uint64_t rlawt_ts_delta_us(const struct timespec *a, const struct timespec *b) {
	/* returns a - b in microseconds; assumes a >= b */
	int64_t sec = (int64_t)a->tv_sec - (int64_t)b->tv_sec;
	int64_t nsec = (int64_t)a->tv_nsec - (int64_t)b->tv_nsec;
	return (uint64_t)(sec * 1000000LL + nsec / 1000);
}

static inline uint64_t rlawt_ts_delta_ns(const struct timespec *a, const struct timespec *b) {
	/* returns a - b in nanoseconds; assumes a >= b */
	int64_t sec = (int64_t)a->tv_sec - (int64_t)b->tv_sec;
	int64_t nsec = (int64_t)a->tv_nsec - (int64_t)b->tv_nsec;
	return (uint64_t)(sec * 1000000000LL + nsec);
}

static int rlawt_cmp_u64(const void *pa, const void *pb) {
	uint64_t a = *(const uint64_t *)pa;
	uint64_t b = *(const uint64_t *)pb;
	if (a < b) return -1;
	if (a > b) return 1;
	return 0;
}

typedef struct {
	uint64_t mean;
	uint64_t p50;
	uint64_t p95;
	uint64_t max;
} RlawtStats;

/* Compute stats over samples[0..n). Uses `scratch` (n slots, caller-provided) for sorting.
 * If valid_mask is non-NULL, only samples where mask entry != 0 contribute; valid_n receives
 * the count of valid samples. If valid_mask is NULL, all n samples are used. */
static void rlawt_compute_stats(const uint64_t *samples, size_t n, uint64_t *scratch,
                                const uint64_t *valid_mask, size_t *valid_n_out,
                                RlawtStats *out) {
	size_t m = 0;
	uint64_t sum = 0;
	for (size_t i = 0; i < n; i++) {
		if (valid_mask && !valid_mask[i]) continue;
		scratch[m++] = samples[i];
		sum += samples[i];
	}
	if (valid_n_out) *valid_n_out = m;
	if (m == 0) {
		out->mean = out->p50 = out->p95 = out->max = 0;
		return;
	}
	qsort(scratch, m, sizeof(uint64_t), rlawt_cmp_u64);
	out->mean = sum / m;
	out->p50 = scratch[m / 2];
	size_t idx95 = (m * 95) / 100;
	if (idx95 >= m) idx95 = m - 1;
	out->p95 = scratch[idx95];
	out->max = scratch[m - 1];
}

static bool rlawt_env_flag(const char *name) {
	const char *v = getenv(name);
	if (!v || !*v) return false;
	return !(v[0] == '0' && v[1] == '\0');
}

/*
 * The completed-display-target route keeps the X11 host visible for login,
 * then foregrounds the app-owned AHB consumer immediately before the first
 * GLX swap. This control message is outside the measured swap interval and
 * runs at most once per process.
 */
static void rlawt_frame_stream_request_surface(void) {
	static bool requested;
	if (requested || !rlawt_env_flag("RLAWT_FRAME_STREAM_ACTIVITY_CONTROL"))
		return;

	const char *host = getenv("RLAWT_ACTIVITY_CONTROL_HOST");
	if (!host || !*host) host = "127.0.0.1";
	int port = 49382;
	const char *port_env = getenv("RLAWT_ACTIVITY_CONTROL_PORT");
	if (port_env && *port_env) {
		char *end = NULL;
		long parsed = strtol(port_env, &end, 10);
		if (end && *end == '\0' && parsed > 0 && parsed <= 65535)
			port = (int)parsed;
	}

	int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) {
		RLAWT_LOG("[rlawt-info] frame-stream surface control socket failed errno=%d\n", errno);
		return;
	}

	struct timeval timeout = { .tv_sec = 2, .tv_usec = 0 };
	setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
	struct sockaddr_in address = {
		.sin_family = AF_INET,
		.sin_port = htons((uint16_t)port),
	};
	if (inet_pton(AF_INET, host, &address.sin_addr) != 1 ||
	    connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
		RLAWT_LOG("[rlawt-info] frame-stream surface control connect failed host=%s port=%d errno=%d\n",
			host, port, errno);
		close(fd);
		return;
	}

	static const char request[] = "START rlt-frame-stream\n";
	char response[32] = {0};
	ssize_t sent = send(fd, request, sizeof(request) - 1, MSG_NOSIGNAL);
	ssize_t received = sent == (ssize_t)(sizeof(request) - 1)
		? recv(fd, response, sizeof(response) - 1, 0)
		: -1;
	close(fd);
	if (received > 0 && strncmp(response, "OK ", 3) == 0) {
		requested = true;
		RLAWT_LOG("[rlawt-info] frame-stream surface control ready host=%s port=%d\n",
			host, port);
	} else {
		RLAWT_LOG("[rlawt-info] frame-stream surface control response failed sent=%zd received=%zd errno=%d\n",
			sent, received, errno);
	}
}

static uint64_t rlawt_clock_ns(clockid_t clock_id) {
	struct timespec now;
	if (clock_gettime(clock_id, &now) != 0) return 0;
	return (uint64_t)now.tv_sec * 1000000000ULL + (uint64_t)now.tv_nsec;
}

static void rlawt_self_profile_end(
	AWTContext *ctx,
	uint64_t wall_started_ns,
	uint64_t cpu_started_ns
) {
	if (!wall_started_ns) return;
	uint64_t wall_ended_ns = rlawt_clock_ns(CLOCK_MONOTONIC);
	uint64_t cpu_ended_ns = rlawt_clock_ns(CLOCK_THREAD_CPUTIME_ID);
	ctx->perf.telemetry_self_calls_window++;
	if (wall_ended_ns >= wall_started_ns)
		ctx->perf.telemetry_self_wall_ns_window += wall_ended_ns - wall_started_ns;
	if (cpu_started_ns && cpu_ended_ns >= cpu_started_ns)
		ctx->perf.telemetry_self_thread_cpu_ns_window += cpu_ended_ns - cpu_started_ns;
}

static void rlawt_frame_telemetry_open(AWTContext *ctx) {
	const char *path = getenv("RLT_VTEST_TELEMETRY_PATH");
	if (!path || !*path) return;

	int fd = open(path, O_RDWR | O_CLOEXEC);
	if (fd < 0) {
		RLAWT_LOG("[rlawt-perf] WARN frame telemetry open failed errno=%d\n", errno);
		return;
	}

	struct stat st;
	int stat_result = fstat(fd, &st);
	if (stat_result != 0 || st.st_size < (off_t)RLT_FRAME_TELEMETRY_SIZE) {
		RLAWT_LOG("[rlawt-perf] WARN frame telemetry size invalid errno=%d size=%lld\n",
			errno, (long long)(stat_result == 0 ? st.st_size : -1));
		close(fd);
		return;
	}

	struct rlt_frame_telemetry_v1 *page = mmap(
		NULL,
		RLT_FRAME_TELEMETRY_SIZE,
		PROT_READ | PROT_WRITE,
		MAP_SHARED,
		fd,
		0);
	if (page == MAP_FAILED) {
		RLAWT_LOG("[rlawt-perf] WARN frame telemetry mmap failed errno=%d\n", errno);
		close(fd);
		return;
	}

	page->magic = RLT_FRAME_TELEMETRY_MAGIC;
	page->abi_version = RLT_FRAME_TELEMETRY_ABI_VERSION;
	page->struct_size = RLT_FRAME_TELEMETRY_SIZE;
	atomic_store_explicit(&page->active_begin_monotonic_ns, 0, memory_order_relaxed);
	atomic_store_explicit(&page->active_producer_seq, 0, memory_order_release);
	atomic_fetch_add_explicit(&page->generation, 1, memory_order_release);
	ctx->perf.frame_telemetry_fd = fd;
	ctx->perf.frame_telemetry_page = page;
	RLAWT_LOG("[rlawt-info] frame telemetry ABI v1 enabled\n");
}

static void rlawt_frame_telemetry_begin(AWTContext *ctx, uint64_t seq, uint64_t begin_ns) {
	struct rlt_frame_telemetry_v1 *page = ctx->perf.frame_telemetry_page;
	if (!page) return;
	atomic_store_explicit(&page->active_begin_monotonic_ns, begin_ns, memory_order_relaxed);
	atomic_store_explicit(&page->active_producer_seq, seq, memory_order_release);
}

static void rlawt_frame_telemetry_end(AWTContext *ctx) {
	struct rlt_frame_telemetry_v1 *page = ctx->perf.frame_telemetry_page;
	if (!page) return;
	atomic_store_explicit(&page->active_producer_seq, 0, memory_order_release);
}

static bool rlawt_gl_has_ext(const char *exts, const char *needle) {
	if (!exts || !needle) return false;
	size_t nlen = strlen(needle);
	const char *p = exts;
	while ((p = strstr(p, needle)) != NULL) {
		char pre = (p == exts) ? ' ' : p[-1];
		char post = p[nlen];
		if ((pre == ' ' || pre == '\0') && (post == ' ' || post == '\0')) return true;
		p += nlen;
	}
	return false;
}

static XErrorEvent lastError = {0};
static int rlawtXErrorHandler(Display *display, XErrorEvent *event) {
	if (lastError.display == 0) {
		lastError = *event;
	}
	return 0;
}

void rlawtThrow(JNIEnv *env, const char *msg) {
	if ((*env)->ExceptionCheck(env)) {
		return;
	}

	jclass clazz = (*env)->FindClass(env, "java/lang/RuntimeException");
	if (lastError.display) {
		char buf[256] = {0};
		snprintf(buf, sizeof(buf), "%s (glx: %u.%u: %u)", msg, (unsigned) lastError.minor_code, (unsigned) lastError.request_code, (unsigned) lastError.error_code);
		lastError.display = 0;
		(*env)->ThrowNew(env, clazz, buf);
	} else {
		(*env)->ThrowNew(env, clazz, msg);
	}
}

static bool makeCurrent(JNIEnv *env, Display *dpy, GLXDrawable drawable, GLXContext ctx) {
	if (!glXMakeCurrent(dpy, drawable, ctx)) {
		rlawtThrow(env, "unable to make current");
		return false;
	}
	return true;
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_createGLContext(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, false)) {
		return;
	}

	ctx->awt.Lock(env);
	XErrorHandler oldErrorHandler = XSetErrorHandler(rlawtXErrorHandler);

	jint dsLock = ctx->ds->Lock(ctx->ds);
	if (dsLock & JAWT_LOCK_ERROR) {
		rlawtThrow(env, "unable to lock ds");
		goto unlock;
	}

	JAWT_DrawingSurfaceInfo *dsi = ctx->ds->GetDrawingSurfaceInfo(ctx->ds);
	if (!dsi) {
		rlawtThrow(env, "unable to get dsi");
		goto unlockDS;
	}

	JAWT_X11DrawingSurfaceInfo *dspi = (JAWT_X11DrawingSurfaceInfo*) dsi->platformInfo;
	if (!dspi || !dspi->display || !dspi->drawable) {
		rlawtThrow(env, "unable to get platform dsi");
		goto freeDSI;
	}

	ctx->drawable = dspi->drawable;

	const char *displayName = XDisplayString(dspi->display);
	ctx->dpy = XOpenDisplay(displayName);
	if (!ctx->dpy) {
		rlawtThrow(env, "unable to open display copy");
		goto freeDSI;
	}

	if (!glXQueryExtension(ctx->dpy, NULL, NULL)) {
		rlawtThrow(env, "glx is not supported");
		goto freeDisplay;
	}

	int screen = DefaultScreen(ctx->dpy);

	RLAWT_LOG("createGLContext: displayName=%s screen=%d\n", displayName ? displayName : "(null)", screen);
	RLAWT_LOG("  dspi->visualID=%lu (0x%lx)  dspi->drawable=0x%lx\n",
		(unsigned long)dspi->visualID, (unsigned long)dspi->visualID, (unsigned long)dspi->drawable);
	RLAWT_LOG("  ctx: alphaDepth=%d depthDepth=%d stencilDepth=%d multisamples=%d\n",
		ctx->alphaDepth, ctx->depthDepth, ctx->stencilDepth, ctx->multisamples);
	{
		Visual *defVis = DefaultVisual(ctx->dpy, screen);
		VisualID defVid = XVisualIDFromVisual(defVis);
		RLAWT_LOG("  DefaultVisualID=%lu (0x%lx)\n", (unsigned long)defVid, (unsigned long)defVid);
	}

	GLXFBConfig fbConfig = NULL;
	for (int db = 0; db < 2; db++) {
		ctx->doubleBuffered = db == 0;

		int attribs[] = {
			GLX_RENDER_TYPE, GLX_RGBA_BIT,
			GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, // JAWT never hands out a pixmap
			GLX_X_VISUAL_TYPE, GLX_TRUE_COLOR,
			GLX_X_RENDERABLE, true,
			GLX_RED_SIZE, 8,
			GLX_GREEN_SIZE, 8,
			GLX_BLUE_SIZE, 8,
			GLX_ALPHA_SIZE, ctx->alphaDepth,
			GLX_DEPTH_SIZE, ctx->depthDepth,
			GLX_STENCIL_SIZE, ctx->stencilDepth,
			GLX_SAMPLE_BUFFERS, ctx->multisamples > 0,
			GLX_SAMPLES, ctx->multisamples,
			GLX_DOUBLEBUFFER, ctx->doubleBuffered,
			None
		};

		int nConfigs;
		GLXFBConfig *fbConfigs = glXChooseFBConfig(ctx->dpy, screen, attribs, &nConfigs);
		RLAWT_LOG("  glXChooseFBConfig DB=%d nConfigs=%d fbConfigs=%p\n",
			ctx->doubleBuffered, nConfigs, (void*)fbConfigs);
		if (!fbConfigs) {
			continue;
		}

		// X11 doesn't seem to care if you use a matching visual, but we try to anyway
		for (int i = 0; i < nConfigs; i++) {
			int	fbVid = -1;
			glXGetFBConfigAttrib(ctx->dpy, fbConfigs[i], GLX_VISUAL_ID, &fbVid);
			RLAWT_LOG("    DB=%d fbconfig[%d] GLX_VISUAL_ID=%d (0x%x) %s\n",
				ctx->doubleBuffered, i, fbVid, fbVid,
				(unsigned long)fbVid == (unsigned long)dspi->visualID ? "<-- MATCH" : "");
			if (fbVid == dspi->visualID) {
				fbConfig = fbConfigs[i];
				break;
			}
		}
		RLAWT_LOG("  DB=%d selection after loop: fbConfig=%p\n", ctx->doubleBuffered, (void*)fbConfig);

		if (fbConfig) {
			XFree(fbConfigs);
			break;
		} else {
			fbConfig = fbConfigs[0];
			XFree(fbConfigs);
		}
	}
	if (!fbConfig) {
		rlawtThrow(env, "unable to find a fb config");
		goto freeDisplay;
	}

	const char *extensions = glXQueryExtensionsString(ctx->dpy, screen);

	PFNGLXCREATECONTEXTATTRIBSARBPROC glXCreateContextAttribsARB = NULL;
	if (strstr(extensions, "GLX_ARB_create_context")) {
		glXCreateContextAttribsARB = (PFNGLXCREATECONTEXTATTRIBSARBPROC) glXGetProcAddressARB("glXCreateContextAttribsARB");
	}

	if (glXCreateContextAttribsARB) {
		ctx->context = glXCreateContextAttribsARB(ctx->dpy, fbConfig, NULL, true, NULL);
	} else {
		ctx->context = glXCreateNewContext(ctx->dpy, fbConfig, GLX_RGBA_TYPE, NULL, true);
	}

	if (!ctx->context) {
		rlawtThrow(env, "unable to create glx context");
		goto freeDisplay;
	}

	if (!makeCurrent(env, ctx->dpy, ctx->drawable, ctx->context)) {
		goto freeContext;
	}

	if (strstr(extensions, "GLX_EXT_swap_control")) {
		ctx->glXSwapIntervalEXT = (PFNGLXSWAPINTERVALEXTPROC) glXGetProcAddress("glXSwapIntervalEXT");
		ctx->glxSwapControlTear = !!strstr(extensions, "GLX_EXT_swap_control_tear");
	} else if (strstr(extensions, "GLX_SGI_swap_control")) {
		ctx->glXSwapIntervalSGI = (PFNGLXSWAPINTERVALSGIPROC) glXGetProcAddress("glXSwapIntervalSGI");
	}

	/* ---- perf telemetry init (A1/A2/A3/A4/A5/A6/A7/A8) ----------------- */
	/* AWTContext is calloc'd (rlawt.c Java_..._create0), so ctx->perf is zero. */
	ctx->perf.frame_telemetry_fd = -1;
	ctx->perf.enabled = rlawt_env_flag("RLAWT_PERF");
	if (ctx->perf.enabled) {
		ctx->perf.finish_enabled = rlawt_env_flag("RLAWT_PERF_FINISH");
		ctx->perf.xsync_enabled = rlawt_env_flag("RLAWT_PERF_XSYNC");
		ctx->perf.gpu_timer_enabled = rlawt_env_flag("RLAWT_PERF_GPU_TIMER");
		ctx->perf.self_profile_enabled = rlawt_env_flag("RLT_TELEMETRY_SELF_PROFILE");

		/* Window size */
		size_t window = 60;
		const char *wstr = getenv("RLAWT_PERF_WINDOW");
		if (wstr && *wstr) {
			long w = strtol(wstr, NULL, 10);
			if (w >= 1 && w <= 10000) {
				window = (size_t)w;
			} else if (w > 10000) {
				window = 10000;
				RLAWT_LOG("[rlawt-perf] WARN RLAWT_PERF_WINDOW=%ld clamped to %d\n", w, 10000);
			}
		}
		ctx->perf.window = window;
		ctx->perf.frame_marker_atom =
			XInternAtom(ctx->dpy, "_RLT_FRAME_SEQ_V1", False);
		rlawt_frame_telemetry_open(ctx);

		/* Allocate rolling buffers (once, outside the hot path). */
		ctx->perf.swap_samples   = (uint64_t *)calloc(window, sizeof(uint64_t));
		ctx->perf.gap_samples    = (uint64_t *)calloc(window, sizeof(uint64_t));
		ctx->perf.xsync_samples  = (uint64_t *)calloc(window, sizeof(uint64_t));
		ctx->perf.finish_samples = (uint64_t *)calloc(window, sizeof(uint64_t));
		ctx->perf.gpu_samples    = (uint64_t *)calloc(window, sizeof(uint64_t));
		ctx->perf.gpu_sample_frame_seq = (uint64_t *)calloc(window, sizeof(uint64_t));
		if (!ctx->perf.swap_samples || !ctx->perf.gap_samples ||
			!ctx->perf.xsync_samples || !ctx->perf.finish_samples ||
			!ctx->perf.gpu_samples || !ctx->perf.gpu_sample_frame_seq) {
			RLAWT_LOG("[rlawt-perf] WARN sample buffer alloc failed; disabling perf\n");
			free(ctx->perf.swap_samples);   ctx->perf.swap_samples = NULL;
			free(ctx->perf.gap_samples);    ctx->perf.gap_samples = NULL;
			free(ctx->perf.xsync_samples);  ctx->perf.xsync_samples = NULL;
			free(ctx->perf.finish_samples); ctx->perf.finish_samples = NULL;
			free(ctx->perf.gpu_samples);    ctx->perf.gpu_samples = NULL;
			free(ctx->perf.gpu_sample_frame_seq); ctx->perf.gpu_sample_frame_seq = NULL;
			ctx->perf.enabled = false;
		}

		/* S81 audit: optional per-frame CSV dump. Env gate: RLAWT_PERF_CSV=<path>.
		 * Window summaries (default) stay in logcat; this adds raw per-frame rows
		 * for end-to-end correlation with XloriePerf / SurfaceFlinger --latency.
		 * Line format (units, with CLOCK_MONOTONIC timestamps from the swap call):
		 *   frame,t_post_swap_ns,swap_us,gap_us,finish_us,xsync_us,gpu_frame,gpu_us,mkcur_cumulative
		 * `gpu_us` is 0 when GL_ARB_timer_query isn't available (virgl Mesa 25.2.8).
		 * `mkcur_cumulative` is the running per-window makeCurrent counter — diff
		 * across rows to reconstruct per-frame makeCurrent activity. */
		const char *csv_path = getenv("RLAWT_PERF_CSV");
		if (ctx->perf.enabled && csv_path && *csv_path) {
			ctx->perf.csv_fp = fopen(csv_path, "a");
			if (ctx->perf.csv_fp) {
				setvbuf(ctx->perf.csv_fp, NULL, _IOLBF, 0); /* line-buffered */
				fprintf(ctx->perf.csv_fp,
					"# rlawt per-frame CSV, CLOCK_MONOTONIC ns / microseconds\n"
					"# mkcur_cumulative is the per-window makeCurrent counter — diff rows for per-frame\n"
					"frame,t_post_swap_ns,swap_us,gap_us,finish_us,xsync_us,gpu_frame,gpu_us,mkcur_cumulative\n");
				fflush(ctx->perf.csv_fp);
				RLAWT_LOG("[rlawt-info] per-frame CSV enabled path=%s\n", csv_path);
			} else {
				RLAWT_LOG("[rlawt-perf] WARN RLAWT_PERF_CSV fopen failed path=%s errno=%d\n",
					csv_path, errno);
			}
		}
	}

	if (ctx->perf.enabled) {
		/* A4 — one-shot context info dump */
		const char *vendor   = (const char *) glGetString(GL_VENDOR);
		const char *renderer = (const char *) glGetString(GL_RENDERER);
		const char *version  = (const char *) glGetString(GL_VERSION);
		RLAWT_LOG("[rlawt-info] GL_VENDOR: %s\n", vendor ? vendor : "(null)");
		RLAWT_LOG("[rlawt-info] GL_RENDERER: %s\n", renderer ? renderer : "(null)");
		RLAWT_LOG("[rlawt-info] GL_VERSION: %s\n", version ? version : "(null)");
		RLAWT_LOG("[rlawt-info] glXIsDirect: %d\n", glXIsDirect(ctx->dpy, ctx->context) ? 1 : 0);
		const char *glx_ext_str = glXQueryExtensionsString(ctx->dpy, screen);
		RLAWT_LOG("[rlawt-info] GLX_EXTENSIONS: %s\n", glx_ext_str ? glx_ext_str : "(null)");

		/* A3 — GL timer-query probe */
		ctx->perf.gpu_timer_available = false;
		if (ctx->perf.gpu_timer_enabled) {
			const char *gl_ext = (const char *) glGetString(GL_EXTENSIONS); /* may be NULL in core profile */
			bool have_timer_ext = (gl_ext && (rlawt_gl_has_ext(gl_ext, "GL_ARB_timer_query") ||
			                                   rlawt_gl_has_ext(gl_ext, "GL_EXT_timer_query")));
			/* Parse GL version major.minor for >= 3.3 gate */
			int gl_major = 0, gl_minor = 0;
			if (version) sscanf(version, "%d.%d", &gl_major, &gl_minor);
			bool ver_ok = (gl_major > 3) || (gl_major == 3 && gl_minor >= 3);

			if (!have_timer_ext || !ver_ok) {
				RLAWT_LOG("[rlawt-info] GL timer queries unavailable (ext=%d ver=%d.%d)\n",
					have_timer_ext ? 1 : 0, gl_major, gl_minor);
			} else {
				ctx->perf.fpGenQueries = (rlawt_PFNGLGENQUERIESPROC)
					glXGetProcAddressARB((const GLubyte *)"glGenQueries");
				ctx->perf.fpDeleteQueries = (rlawt_PFNGLDELETEQUERIESPROC)
					glXGetProcAddressARB((const GLubyte *)"glDeleteQueries");
				ctx->perf.fpBeginQuery = (rlawt_PFNGLBEGINQUERYPROC)
					glXGetProcAddressARB((const GLubyte *)"glBeginQuery");
				ctx->perf.fpEndQuery = (rlawt_PFNGLENDQUERYPROC)
					glXGetProcAddressARB((const GLubyte *)"glEndQuery");
				ctx->perf.fpGetQueryObjectui64v = (rlawt_PFNGLGETQUERYOBJECTUI64VPROC)
					glXGetProcAddressARB((const GLubyte *)"glGetQueryObjectui64v");

				if (ctx->perf.fpGenQueries && ctx->perf.fpDeleteQueries &&
					ctx->perf.fpBeginQuery && ctx->perf.fpEndQuery &&
					ctx->perf.fpGetQueryObjectui64v) {
					ctx->perf.gpu_queries = (unsigned int *)calloc(ctx->perf.window, sizeof(unsigned int));
					if (ctx->perf.gpu_queries) {
						ctx->perf.fpGenQueries((int)ctx->perf.window, ctx->perf.gpu_queries);
						ctx->perf.gpu_queries_allocated = true;
						ctx->perf.gpu_timer_available = true;
						RLAWT_LOG("[rlawt-info] GL timer queries enabled (window=%zu)\n", ctx->perf.window);
					} else {
						RLAWT_LOG("[rlawt-info] GL timer queries disabled (query id alloc failed)\n");
					}
				} else {
					RLAWT_LOG("[rlawt-info] GL timer queries disabled (entry-point lookup failed)\n");
				}
			}
		}
	}
	/* ------------------------------------------------------------------- */

	ctx->ds->FreeDrawingSurfaceInfo(dsi);

	XSync(ctx->dpy, false);
	XSetErrorHandler(oldErrorHandler);
	ctx->ds->Unlock(ctx->ds);
	rlawtUnlockAWT(env, ctx);

	ctx->contextCreated = true;
	return;

freeContext:
	glXDestroyContext(ctx->dpy, ctx->context);
freeDisplay:
	XSync(ctx->dpy, false);
	XCloseDisplay(ctx->dpy);
	jthrowable exception;
freeDSI:
	exception = (*env)->ExceptionOccurred(env);
	ctx->ds->FreeDrawingSurfaceInfo(dsi);
	if (exception) {
		(*env)->Throw(env, exception);
	}
unlockDS:
	exception = (*env)->ExceptionOccurred(env);
	ctx->ds->Unlock(ctx->ds);
	if (exception) {
		(*env)->Throw(env, exception);
	}
unlock:
	XSetErrorHandler(oldErrorHandler);
	rlawtUnlockAWT(env, ctx);
}

void rlawtContextFreePlatform(JNIEnv *env, AWTContext *ctx) {
	if (ctx->contextCreated) {
		/* Free perf GL resources while the context is still current-capable. */
		if (ctx->perf.gpu_queries_allocated && ctx->perf.gpu_queries && ctx->perf.fpDeleteQueries) {
			/* Best-effort: context might not be current here. Try make-current first. */
			if (glXMakeCurrent(ctx->dpy, ctx->drawable, ctx->context)) {
				ctx->perf.fpDeleteQueries((int)ctx->perf.window, ctx->perf.gpu_queries);
			}
		}
		glXMakeCurrent(ctx->dpy, None, None);
		glXDestroyContext(ctx->dpy, ctx->context);
		XCloseDisplay(ctx->dpy);
	}
	/* Free heap-allocated perf buffers (safe on NULL). */
	free(ctx->perf.swap_samples);   ctx->perf.swap_samples = NULL;
	free(ctx->perf.gap_samples);    ctx->perf.gap_samples = NULL;
	free(ctx->perf.xsync_samples);  ctx->perf.xsync_samples = NULL;
	free(ctx->perf.finish_samples); ctx->perf.finish_samples = NULL;
	free(ctx->perf.gpu_samples);    ctx->perf.gpu_samples = NULL;
	free(ctx->perf.gpu_sample_frame_seq); ctx->perf.gpu_sample_frame_seq = NULL;
	free(ctx->perf.gpu_queries);    ctx->perf.gpu_queries = NULL;
	if (ctx->perf.frame_telemetry_page) {
		struct rlt_frame_telemetry_v1 *page = ctx->perf.frame_telemetry_page;
		atomic_store_explicit(&page->active_producer_seq, 0, memory_order_release);
		munmap(page, RLT_FRAME_TELEMETRY_SIZE);
		ctx->perf.frame_telemetry_page = NULL;
	}
	if (ctx->perf.frame_telemetry_fd >= 0) {
		close(ctx->perf.frame_telemetry_fd);
		ctx->perf.frame_telemetry_fd = -1;
	}
	if (ctx->perf.csv_fp) {
		fclose(ctx->perf.csv_fp);
		ctx->perf.csv_fp = NULL;
	}
}

JNIEXPORT jint JNICALL Java_net_runelite_rlawt_AWTContext_setSwapInterval(JNIEnv *env, jobject self, jint interval) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) {
		return 0;
	}

	jint incoming_raw = interval;
	if (ctx->perf.enabled) {
		RLAWT_LOG("[rlawt-info] setSwapInterval: interval=%d tearSupported=%s\n",
			(int)incoming_raw, ctx->glxSwapControlTear ? "true" : "false");
	}

	ctx->awt.Lock(env);

	if (interval < 0 && !ctx->glxSwapControlTear) {
		interval = -interval;
	}
	const char *path = "NONE";
	if (ctx->glXSwapIntervalEXT) {
		ctx->glXSwapIntervalEXT(ctx->dpy, ctx->drawable, interval);
		path = "EXT";
	} else if (ctx->glXSwapIntervalSGI) {
		ctx->glXSwapIntervalSGI(interval);
		path = "SGI";
	} else {
		interval = 0;
	}

	rlawtUnlockAWT(env, ctx);

	if (ctx->perf.enabled) {
		RLAWT_LOG("[rlawt-info] setSwapInterval: applied=%d path=%s\n", (int)interval, path);
	}

	return interval;
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_makeCurrent(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) {
		return;
	}

	if (ctx->perf.enabled) {
		ctx->perf.mkcur_count_window++;
	}

	ctx->awt.Lock(env);

	makeCurrent(env, ctx->dpy, ctx->drawable, ctx->context);

	rlawtUnlockAWT(env, ctx);
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_detachCurrent(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) {
		return;
	}

	ctx->awt.Lock(env);

	makeCurrent(env, ctx->dpy, None, None);

	rlawtUnlockAWT(env, ctx);
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_swapBuffers(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) {
		return;
	}

	ctx->awt.Lock(env);
	XErrorHandler oldErrorHandler = XSetErrorHandler(rlawtXErrorHandler);
	rlawt_frame_stream_request_surface();

	/* Fast path: perf disabled — original behavior, one compare+branch overhead. */
	if (!ctx->perf.enabled) {
		if (ctx->doubleBuffered) {
			// TODO: handle -1
			glXSwapBuffers(ctx->dpy, ctx->drawable);
		} else {
			glFinish();
		}
		rlawtUnlockAWT(env, ctx);
		return;
	}

	/* --- perf-instrumented path --------------------------------------- */
	uint64_t telemetry_self_wall_started_ns = ctx->perf.self_profile_enabled
		? rlawt_clock_ns(CLOCK_MONOTONIC)
		: 0;
	uint64_t telemetry_self_cpu_started_ns = ctx->perf.self_profile_enabled
		? rlawt_clock_ns(CLOCK_THREAD_CPUTIME_ID)
		: 0;
	const size_t window = ctx->perf.window;
	const size_t slot = ctx->perf.frame_idx % window;

	/* A6: XSync probe (before pre-swap timing) */
	if (ctx->perf.xsync_enabled) {
		struct timespec t_xs_a, t_xs_b;
		clock_gettime(CLOCK_MONOTONIC, &t_xs_a);
		XSync(ctx->dpy, false);
		clock_gettime(CLOCK_MONOTONIC, &t_xs_b);
		ctx->perf.xsync_samples[slot] = rlawt_ts_delta_us(&t_xs_b, &t_xs_a);
	}

	/* A3: read back the lagged GPU timer query for this slot, if we're past the first cycle */
	if (ctx->perf.gpu_timer_available && ctx->perf.frame_idx >= window) {
		rlawt_GLuint64 avail = 0;
		ctx->perf.fpGetQueryObjectui64v(ctx->perf.gpu_queries[slot],
			RLAWT_GL_QUERY_RESULT_AVAILABLE, &avail);
		if (avail) {
			rlawt_GLuint64 ns = 0;
			ctx->perf.fpGetQueryObjectui64v(ctx->perf.gpu_queries[slot],
				RLAWT_GL_QUERY_RESULT, &ns);
			ctx->perf.gpu_samples[slot] = (uint64_t)(ns / 1000ULL);
			ctx->perf.gpu_sample_frame_seq[slot] = ctx->perf.frame_idx - window + 1;
			ctx->perf.gpu_valid_count++;
		} else {
			ctx->perf.gpu_samples[slot] = 0; /* sentinel: invalid */
			ctx->perf.gpu_sample_frame_seq[slot] = 0;
		}
	} else if (ctx->perf.gpu_timer_available) {
		/* First pass through the ring: no prior query to read; ensure sample is zeroed. */
		ctx->perf.gpu_samples[slot] = 0;
		ctx->perf.gpu_sample_frame_seq[slot] = 0;
	}

	/* Begin GPU query bracket (around finish+swap). */
	if (ctx->perf.gpu_timer_available) {
		ctx->perf.fpBeginQuery(RLAWT_GL_TIME_ELAPSED, ctx->perf.gpu_queries[slot]);
	}

	struct timespec t_pre_swap, t_post_swap;
	uint64_t finish_us = 0;

	/* A2: glFinish probe */
	if (ctx->perf.finish_enabled) {
		struct timespec t_before_finish, t_after_finish;
		clock_gettime(CLOCK_MONOTONIC, &t_before_finish);
		glFinish();
		clock_gettime(CLOCK_MONOTONIC, &t_after_finish);
		finish_us = rlawt_ts_delta_us(&t_after_finish, &t_before_finish);
		t_pre_swap = t_after_finish;
	} else {
		clock_gettime(CLOCK_MONOTONIC, &t_pre_swap);
	}

	/*
	 * Ordered producer identity marker. XChangeProperty is asynchronous: it
	 * appends a tiny request to this Display connection without a round trip.
	 * The following swap flushes the connection, so Xlorie observes this
	 * marker before damage caused by the corresponding swap. This does not
	 * alter RuneLite or its GPU plugin.
	 */
	if (ctx->perf.frame_marker_atom != None) {
		uint64_t frame_seq = (uint64_t)ctx->perf.frame_idx + 1ULL;
		uint64_t marker_ns =
			(uint64_t)t_pre_swap.tv_sec * 1000000000ULL +
			(uint64_t)t_pre_swap.tv_nsec;
		unsigned long marker[4] = {
			(unsigned long)(frame_seq & 0xffffffffULL),
			(unsigned long)(frame_seq >> 32),
			(unsigned long)(marker_ns & 0xffffffffULL),
			(unsigned long)(marker_ns >> 32),
		};
		int marker_status = XChangeProperty(
			ctx->dpy,
			(Window)ctx->drawable,
			ctx->perf.frame_marker_atom,
			XA_CARDINAL,
			32,
			PropModeReplace,
			(const unsigned char *)marker,
			4);
		if (marker_status)
			ctx->perf.marker_count_window++;
		else
			ctx->perf.marker_error_count_window++;
	}

	uint64_t frame_seq = (uint64_t)ctx->perf.frame_idx + 1ULL;
	uint64_t frame_begin_ns =
		(uint64_t)t_pre_swap.tv_sec * 1000000000ULL +
		(uint64_t)t_pre_swap.tv_nsec;
	rlawt_frame_telemetry_begin(ctx, frame_seq, frame_begin_ns);
	rlawt_self_profile_end(
		ctx,
		telemetry_self_wall_started_ns,
		telemetry_self_cpu_started_ns);
	if (ctx->doubleBuffered) {
		// TODO: handle -1
		glXSwapBuffers(ctx->dpy, ctx->drawable);
	} else {
		glFinish();
	}
	rlawt_frame_telemetry_end(ctx);
	telemetry_self_wall_started_ns = ctx->perf.self_profile_enabled
		? rlawt_clock_ns(CLOCK_MONOTONIC)
		: 0;
	telemetry_self_cpu_started_ns = ctx->perf.self_profile_enabled
		? rlawt_clock_ns(CLOCK_THREAD_CPUTIME_ID)
		: 0;

	clock_gettime(CLOCK_MONOTONIC, &t_post_swap);

	/* End GPU query bracket. */
	if (ctx->perf.gpu_timer_available) {
		ctx->perf.fpEndQuery(RLAWT_GL_TIME_ELAPSED);
	}

	uint64_t swap_us = rlawt_ts_delta_us(&t_post_swap, &t_pre_swap);
	uint64_t gap_us = 0;
	if (ctx->perf.last_post_swap_ts.tv_sec != 0 || ctx->perf.last_post_swap_ts.tv_nsec != 0) {
		gap_us = rlawt_ts_delta_us(&t_pre_swap, &ctx->perf.last_post_swap_ts);
		uint64_t producer_interval_ns =
			rlawt_ts_delta_ns(&t_post_swap, &ctx->perf.last_post_swap_ts);
		ctx->perf.producer_interval_ns_sum += producer_interval_ns;
		ctx->perf.producer_interval_count++;
	}
	ctx->perf.swap_samples[slot] = swap_us;
	ctx->perf.gap_samples[slot] = gap_us;
	if (ctx->perf.finish_enabled) {
		ctx->perf.finish_samples[slot] = finish_us;
	}
	ctx->perf.last_post_swap_ts = t_post_swap;
	ctx->perf.frame_idx++;

	/* S81 audit: per-frame CSV row. Writes the raw timestamps + stage costs for
	 * this single frame so we can align individual frames across rlawt, Xlorie,
	 * and Android presentation. gpu_frame explicitly identifies the older frame
	 * owning a lagged timer-query result; it is 0 when unavailable. xsync_us/finish_us read
	 * the slot that was just written above — 0 when the respective probe is
	 * disabled. mkcur column is the running per-window counter; diff across
	 * rows to get per-frame makeCurrent activity. */
	if (ctx->perf.csv_fp) {
		uint64_t t_post_ns = (uint64_t)t_post_swap.tv_sec * 1000000000ULL +
		                     (uint64_t)t_post_swap.tv_nsec;
		uint64_t xsync_us = ctx->perf.xsync_enabled ? ctx->perf.xsync_samples[slot] : 0;
		uint64_t gpu_us   = ctx->perf.gpu_timer_available ? ctx->perf.gpu_samples[slot] : 0;
		uint64_t gpu_frame = ctx->perf.gpu_timer_available ? ctx->perf.gpu_sample_frame_seq[slot] : 0;
		fprintf(ctx->perf.csv_fp,
			"%zu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%u\n",
			ctx->perf.frame_idx,
			(unsigned long long)t_post_ns,
			(unsigned long long)swap_us,
			(unsigned long long)gap_us,
			(unsigned long long)(ctx->perf.finish_enabled ? finish_us : 0),
			(unsigned long long)xsync_us,
			(unsigned long long)gpu_frame,
			(unsigned long long)gpu_us,
			ctx->perf.mkcur_count_window);
	}

	/* Window-boundary emit. */
	if (ctx->perf.frame_idx >= window && (ctx->perf.frame_idx % window) == 0) {
		/* A7: glGetError sweep (just before emit) */
		GLenum glerr = glGetError();

		/* Stack scratch sized to window (capped at compile time). */
		uint64_t scratch[RLAWT_PERF_MAX_WINDOW];

		RlawtStats swap_stats, gap_stats;
		/* For gap_samples, skip index 0 which may be 0 on the very first full window. */
		rlawt_compute_stats(ctx->perf.swap_samples, window, scratch, NULL, NULL, &swap_stats);
		rlawt_compute_stats(ctx->perf.gap_samples, window, scratch, NULL, NULL, &gap_stats);

		char xsync_seg[96]; xsync_seg[0] = '\0';
		if (ctx->perf.xsync_enabled) {
			RlawtStats s;
			rlawt_compute_stats(ctx->perf.xsync_samples, window, scratch, NULL, NULL, &s);
			snprintf(xsync_seg, sizeof(xsync_seg),
				"  xsync_us mean=%llu p50=%llu p95=%llu",
				(unsigned long long)s.mean, (unsigned long long)s.p50, (unsigned long long)s.p95);
		}

		char gpu_seg[128]; gpu_seg[0] = '\0';
		if (ctx->perf.gpu_timer_available) {
			RlawtStats s;
			size_t valid_n = 0;
			rlawt_compute_stats(ctx->perf.gpu_samples, window, scratch,
				ctx->perf.gpu_samples /* non-zero => valid */, &valid_n, &s);
			snprintf(gpu_seg, sizeof(gpu_seg),
				"  gpu_us mean=%llu p50=%llu p95=%llu valid=%zu/%zu",
				(unsigned long long)s.mean, (unsigned long long)s.p50, (unsigned long long)s.p95,
				valid_n, window);
		}

		char finish_seg[64]; finish_seg[0] = '\0';
		if (ctx->perf.finish_enabled) {
			RlawtStats s;
			rlawt_compute_stats(ctx->perf.finish_samples, window, scratch, NULL, NULL, &s);
			snprintf(finish_seg, sizeof(finish_seg),
				"  finish_us mean=%llu p50=%llu",
				(unsigned long long)s.mean, (unsigned long long)s.p50);
		}

		const size_t producer_first_seq =
			ctx->perf.frame_idx - (size_t)ctx->perf.producer_interval_count;
		const double produced_fps =
			ctx->perf.producer_interval_ns_sum > 0
				? ((double)ctx->perf.producer_interval_count * 1000000000.0) /
					(double)ctx->perf.producer_interval_ns_sum
				: 0.0;
		const uint64_t producer_end_ns =
			(uint64_t)t_post_swap.tv_sec * 1000000000ULL +
			(uint64_t)t_post_swap.tv_nsec;
		const char *capture_id = getenv("RLT_CAPTURE_ID");

		RLAWT_LOG("[rlawt-perf] frame=%zu win=%zu  swap_us mean=%llu p50=%llu p95=%llu max=%llu  gap_us mean=%llu p50=%llu p95=%llu max=%llu  marker=%llu marker_err=%llu mkcur=%u glerr=%s%x%s%s%s\n",
			ctx->perf.frame_idx, window,
			(unsigned long long)swap_stats.mean, (unsigned long long)swap_stats.p50,
			(unsigned long long)swap_stats.p95, (unsigned long long)swap_stats.max,
			(unsigned long long)gap_stats.mean, (unsigned long long)gap_stats.p50,
			(unsigned long long)gap_stats.p95, (unsigned long long)gap_stats.max,
			(unsigned long long)ctx->perf.marker_count_window,
			(unsigned long long)ctx->perf.marker_error_count_window,
			ctx->perf.mkcur_count_window,
			glerr ? "0x" : "", (unsigned)glerr, xsync_seg, gpu_seg, finish_seg);
		RLAWT_LOG("RLT_FRAME_RATE_V1 run_id=%s stage=producer outcome=produced first_seq=%zu last_seq=%zu interval_count=%llu elapsed_ns=%llu end_monotonic_ns=%llu produced_fps=%.3f\n",
			capture_id && *capture_id ? capture_id : "unknown",
			producer_first_seq,
			ctx->perf.frame_idx,
			(unsigned long long)ctx->perf.producer_interval_count,
			(unsigned long long)ctx->perf.producer_interval_ns_sum,
			(unsigned long long)producer_end_ns,
			produced_fps);

		/* Reset per-window counters after the self-cost record below. */
		ctx->perf.mkcur_count_window = 0;
		ctx->perf.gpu_valid_count = 0;
		ctx->perf.producer_interval_ns_sum = 0;
		ctx->perf.producer_interval_count = 0;
	}

	rlawt_self_profile_end(
		ctx,
		telemetry_self_wall_started_ns,
		telemetry_self_cpu_started_ns);
	if (ctx->perf.self_profile_enabled &&
		ctx->perf.frame_idx >= window &&
		(ctx->perf.frame_idx % window) == 0) {
		const char *capture_id = getenv("RLT_CAPTURE_ID");
		const char *telemetry_mode = getenv("RLT_TELEMETRY_MODE");
		RLAWT_LOG(
			"RLT_TELEMETRY_COST_V1 run_id=%s mode=%s stage=rlawt_producer "
			"outcome=measured calls=%llu measured_samples=%llu "
			"measured_wall_ns=%llu measured_thread_cpu_ns=%llu "
			"bytes=%llu dropped=%llu queue_high_water_mark=0 "
			"record_emit_excluded=1\n",
			capture_id && *capture_id ? capture_id : "unknown",
			telemetry_mode && *telemetry_mode ? telemetry_mode : "unknown",
			(unsigned long long)ctx->perf.telemetry_self_calls_window,
			(unsigned long long)ctx->perf.telemetry_self_calls_window,
			(unsigned long long)ctx->perf.telemetry_self_wall_ns_window,
			(unsigned long long)ctx->perf.telemetry_self_thread_cpu_ns_window,
			(unsigned long long)(ctx->perf.marker_count_window * 16ULL),
			(unsigned long long)ctx->perf.marker_error_count_window);
		ctx->perf.telemetry_self_calls_window = 0;
		ctx->perf.telemetry_self_wall_ns_window = 0;
		ctx->perf.telemetry_self_thread_cpu_ns_window = 0;
	}
	if (ctx->perf.frame_idx >= window && (ctx->perf.frame_idx % window) == 0) {
		ctx->perf.marker_count_window = 0;
		ctx->perf.marker_error_count_window = 0;
	}

	rlawtUnlockAWT(env, ctx);
}

#endif
