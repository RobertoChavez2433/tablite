/*
 * rlawt direct-Android-surface backend.
 *
 * Drop-in replacement for rlawt_nix.c's GLX/X11 path. Compiles only when
 * RLAWT_DIRECT_SURFACE is defined (rlawt-bionic CMake target). RuneLite's
 * Java/AWT-facing surface (AWTContext.java / rlawt.c) is unchanged; we only
 * swap the platform implementation.
 *
 * Architecture:
 *   - rlawt JVM (this code, running in Termux/Bionic JVM under proot) is the
 *     producer. AHardwareBuffers are allocated by the consumer (the app-side
 *     RlawtSurfaceService running in com.runelitetablet:surfacerenderer) and
 *     handed to us as native FDs over AF_UNIX SCM_RIGHTS.
 *   - We import each AHB as an EGLImage via eglGetNativeClientBufferANDROID +
 *     eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID), bind to a GL_TEXTURE_2D
 *     via glEGLImageTargetTexture2DOES, attach to an FBO as COLOR_ATTACHMENT0.
 *   - getFramebuffer(true) returns the FBO of the currently-active AHB, so
 *     RuneLite's GPU plugin draws into our AHB through GL_COLOR_ATTACHMENT0.
 *   - swapBuffers signals FRAME_READY{index, frame_seq} to the consumer,
 *     advances current_index to a free slot (blocking on RELEASE if the pool
 *     is exhausted).
 *
 * Cross-process primitive validated by S82 task #25 (cross-process AHB import
 * on Mali r44p1: 100/100 PASS, zero FD leak across resize-varied iterations).
 */

#if defined(__unix__) && defined(RLAWT_DIRECT_SURFACE)

#include "rlawt.h"
#include "rlawt_surface_protocol.h"
#include "rlt_frame_trace.h"

#include <jawt_md.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Logcat goes to the launcher's stderr, which the launcher captures into the
 * S82 logfile under $LOGDIR/rlawt-jvm.log (added by the launcher integration).
 * Tag every line with [rlawt-android] + a relative timestamp so we can
 * correlate against XloriePerf and the consumer-side logcat. */
static struct timespec rlawt_log_origin = {0};
static inline uint64_t rlawt_log_ms(void) {
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	if (rlawt_log_origin.tv_sec == 0 && rlawt_log_origin.tv_nsec == 0) rlawt_log_origin = now;
	int64_t s = (int64_t)now.tv_sec - (int64_t)rlawt_log_origin.tv_sec;
	int64_t n = (int64_t)now.tv_nsec - (int64_t)rlawt_log_origin.tv_nsec;
	return (uint64_t)(s * 1000LL + n / 1000000LL);
}

#define RLAWT_LOG(...) do { \
	fprintf(stderr, "[rlawt-android +%llums] ", (unsigned long long)rlawt_log_ms()); \
	fprintf(stderr, __VA_ARGS__); \
	fflush(stderr); \
} while (0)

/* Count entries in /proc/self/fd. Used at lifecycle transitions to verify
 * SCM_RIGHTS imports aren't leaking. Returns -1 on error. */
static int rlawt_count_fds(void) {
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

static const char *rlawt_msg_name(uint16_t type) {
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

#ifndef RLAWT_DEFAULT_POOL_SIZE
#define RLAWT_DEFAULT_POOL_SIZE 3
#endif

/* How often (in frames) to emit a periodic swap-timing summary. Independent
 * of the CSV side-file. Always-on so we can spot stalls at a glance. */
#ifndef RLAWT_LOG_FRAME_PERIOD
#define RLAWT_LOG_FRAME_PERIOD 60
#endif

/* ---------------------------------------------------------------- helpers */

static inline uint64_t ts_delta_us(const struct timespec *a, const struct timespec *b) {
	int64_t sec = (int64_t)a->tv_sec - (int64_t)b->tv_sec;
	int64_t nsec = (int64_t)a->tv_nsec - (int64_t)b->tv_nsec;
	return (uint64_t)(sec * 1000000LL + nsec / 1000);
}

void rlawtThrow(JNIEnv *env, const char *msg) {
	if ((*env)->ExceptionCheck(env)) return;
	jclass clazz = (*env)->FindClass(env, "java/lang/RuntimeException");
	(*env)->ThrowNew(env, clazz, msg);
}

/* Connect to the consumer's abstract-namespace UDS. Returns FD or -1.
 * Caller throws on failure. The override is an abstract NAME (no leading NUL),
 * not a filesystem path. */
static int rlawt_connect_consumer(const char *override_name) {
	int fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
	if (fd < 0) {
		RLAWT_LOG("socket() failed errno=%d (%s)\n", errno, strerror(errno));
		return -1;
	}
	struct sockaddr_un addr;
	socklen_t addr_len = rlawt_surface_fill_abstract_addr(&addr, override_name);
	if (connect(fd, (struct sockaddr *)&addr, addr_len) < 0) {
		RLAWT_LOG("connect(@%s) failed errno=%d (%s)\n",
			override_name && *override_name ? override_name : RLAWT_SURFACE_ABSTRACT_NAME,
			errno, strerror(errno));
		close(fd);
		return -1;
	}
	RLAWT_LOG("connected to consumer @%s (fd=%d)\n",
		override_name && *override_name ? override_name : RLAWT_SURFACE_ABSTRACT_NAME, fd);
	return fd;
}

#define RLAWT_ACTIVITY_CONTROL_ABSTRACT_NAME "rlt-direct-control"
#define RLAWT_ACTIVITY_CONTROL_HOST "127.0.0.1"
#define RLAWT_ACTIVITY_CONTROL_PORT 49382

static bool rlawt_write_all(int fd, const char *buf, size_t len);

static bool rlawt_request_surface_activity_tcp(const char *surface_name) {
	const char *host = getenv("RLAWT_ACTIVITY_CONTROL_HOST");
	if (!host || !*host) host = RLAWT_ACTIVITY_CONTROL_HOST;
	int port = RLAWT_ACTIVITY_CONTROL_PORT;
	const char *port_env = getenv("RLAWT_ACTIVITY_CONTROL_PORT");
	if (port_env && *port_env) {
		int parsed = atoi(port_env);
		if (parsed > 0 && parsed < 65536) port = parsed;
	}
	RLAWT_LOG("requesting DirectSurfaceHostActivity via tcp %s:%d surface=@%s\n", host, port, surface_name);

	int fd = socket(AF_INET, SOCK_STREAM, 0);
	if (fd < 0) {
		RLAWT_LOG("tcp control socket() failed errno=%d (%s)\n", errno, strerror(errno));
		return false;
	}

	struct sockaddr_in addr;
	memset(&addr, 0, sizeof(addr));
	addr.sin_family = AF_INET;
	addr.sin_port = htons((uint16_t)port);
	if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
		RLAWT_LOG("tcp control invalid host '%s'\n", host);
		close(fd);
		return false;
	}
	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		RLAWT_LOG("tcp control connect(%s:%d) failed errno=%d (%s)\n", host, port, errno, strerror(errno));
		close(fd);
		return false;
	}

	char req[160];
	int req_len = snprintf(req, sizeof(req), "START %s\n", surface_name);
	if (req_len <= 0 || req_len >= (int)sizeof(req)) {
		RLAWT_LOG("tcp control request format failed len=%d surface=@%s\n", req_len, surface_name);
		close(fd);
		return false;
	}
	if (!rlawt_write_all(fd, req, (size_t)req_len)) {
		RLAWT_LOG("tcp control write failed errno=%d (%s)\n", errno, strerror(errno));
		close(fd);
		return false;
	}

	char resp[128];
	ssize_t rn = read(fd, resp, sizeof(resp) - 1);
	if (rn < 0) {
		RLAWT_LOG("tcp control read failed errno=%d (%s)\n", errno, strerror(errno));
		close(fd);
		return false;
	}
	resp[rn > 0 ? rn : 0] = '\0';
	close(fd);
	RLAWT_LOG("tcp control response bytes=%zd text=%s", rn, rn > 0 ? resp : "(empty)\n");
	return rn >= 2 && strncmp(resp, "OK", 2) == 0;
}

static socklen_t rlawt_fill_abstract_addr_name(struct sockaddr_un *addr, const char *name) {
	size_t name_len = strlen(name);
	if (name_len >= sizeof(addr->sun_path) - 1) name_len = sizeof(addr->sun_path) - 1;
	memset(addr, 0, sizeof(*addr));
	addr->sun_family = AF_UNIX;
	addr->sun_path[0] = '\0';
	memcpy(&addr->sun_path[1], name, name_len);
	return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);
}

static bool rlawt_write_all(int fd, const char *buf, size_t len) {
	size_t off = 0;
	while (off < len) {
		ssize_t n = send(fd, buf + off, len - off, MSG_NOSIGNAL);
		if (n < 0) {
			if (errno == EINTR) continue;
			return false;
		}
		if (n == 0) return false;
		off += (size_t)n;
	}
	return true;
}

static bool rlawt_request_surface_activity(const char *surface_name) {
	if (!getenv("RLAWT_DISABLE_TCP_ACTIVITY_CONTROL") &&
			rlawt_request_surface_activity_tcp(surface_name)) {
		return true;
	}

	const char *control_name = getenv("RLAWT_ACTIVITY_CONTROL_NAME");
	if (!control_name || !*control_name) control_name = RLAWT_ACTIVITY_CONTROL_ABSTRACT_NAME;
	RLAWT_LOG("requesting DirectSurfaceHostActivity via control @%s surface=@%s\n", control_name, surface_name);

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) {
		RLAWT_LOG("control socket() failed errno=%d (%s)\n", errno, strerror(errno));
		return false;
	}

	struct sockaddr_un addr;
	socklen_t addr_len = rlawt_fill_abstract_addr_name(&addr, control_name);
	if (connect(fd, (struct sockaddr *)&addr, addr_len) < 0) {
		RLAWT_LOG("control connect(@%s) failed errno=%d (%s)\n", control_name, errno, strerror(errno));
		close(fd);
		return false;
	}

	char req[160];
	int req_len = snprintf(req, sizeof(req), "START %s\n", surface_name);
	if (req_len <= 0 || req_len >= (int)sizeof(req)) {
		RLAWT_LOG("control request format failed len=%d surface=@%s\n", req_len, surface_name);
		close(fd);
		return false;
	}
	if (!rlawt_write_all(fd, req, (size_t)req_len)) {
		RLAWT_LOG("control write failed errno=%d (%s)\n", errno, strerror(errno));
		close(fd);
		return false;
	}

	char resp[128];
	ssize_t rn = read(fd, resp, sizeof(resp) - 1);
	if (rn < 0) {
		RLAWT_LOG("control read failed errno=%d (%s)\n", errno, strerror(errno));
		close(fd);
		return false;
	}
	resp[rn > 0 ? rn : 0] = '\0';
	close(fd);
	RLAWT_LOG("control response bytes=%zd text=%s", rn, rn > 0 ? resp : "(empty)\n");
	return rn >= 2 && strncmp(resp, "OK", 2) == 0;
}

static void rlawt_start_surface_activity_via_am(const char *name) {
	RLAWT_LOG("falling back to /system/bin/am start DirectSurfaceHostActivity abstract_name=%s\n", name);
	int pipefd[2] = {-1, -1};
	bool capture = pipe(pipefd) == 0;
	if (!capture) {
		RLAWT_LOG("pipe for am output failed errno=%d (%s)\n", errno, strerror(errno));
	}
	pid_t pid = fork();
	if (pid < 0) {
		RLAWT_LOG("fork for am start failed errno=%d (%s)\n", errno, strerror(errno));
		if (capture) {
			close(pipefd[0]);
			close(pipefd[1]);
		}
		return;
	}
	if (pid == 0) {
		if (capture) {
			dup2(pipefd[1], STDOUT_FILENO);
			dup2(pipefd[1], STDERR_FILENO);
			close(pipefd[0]);
			close(pipefd[1]);
		}
		execl("/system/bin/am", "am",
			"start",
			"-n", "com.runelitetablet/.directsurface.DirectSurfaceHostActivity",
			"--es", "rlt.abstract_name", name,
			(char *)NULL);
		_exit(127);
	}

	if (capture) {
		close(pipefd[1]);
		char buf[512];
		ssize_t n;
		while ((n = read(pipefd[0], buf, sizeof(buf) - 1)) > 0) {
			buf[n] = '\0';
			RLAWT_LOG("am output: %s", buf);
		}
		close(pipefd[0]);
	}

	int status = 0;
	if (waitpid(pid, &status, 0) < 0) {
		RLAWT_LOG("waitpid(am start) failed errno=%d (%s)\n", errno, strerror(errno));
		return;
	}
	if (WIFEXITED(status)) {
		RLAWT_LOG("am start exited code=%d raw=0x%x\n", WEXITSTATUS(status), status);
	} else if (WIFSIGNALED(status)) {
		RLAWT_LOG("am start signaled signal=%d raw=0x%x\n", WTERMSIG(status), status);
	} else {
		RLAWT_LOG("am start ended raw=0x%x\n", status);
	}
}

static void rlawt_start_surface_activity(const char *override_name) {
	if (getenv("RLAWT_NO_ACTIVITY_START")) {
		RLAWT_LOG("activity start skipped by RLAWT_NO_ACTIVITY_START\n");
		return;
	}
	const char *name = override_name && *override_name ? override_name : RLAWT_SURFACE_ABSTRACT_NAME;
	if (rlawt_request_surface_activity(name)) {
		RLAWT_LOG("DirectSurfaceHostActivity request accepted by app control socket\n");
		return;
	}
	rlawt_start_surface_activity_via_am(name);
}

static int rlawt_connect_consumer_with_retry(const char *override_name) {
	int fd = rlawt_connect_consumer(override_name);
	if (fd >= 0) return fd;

	rlawt_start_surface_activity(override_name);

	for (int attempt = 1; attempt <= 40; attempt++) {
		usleep(125000);
		fd = rlawt_connect_consumer(override_name);
		if (fd >= 0) {
			RLAWT_LOG("consumer connected after activity start attempt=%d\n", attempt);
			return fd;
		}
	}
	RLAWT_LOG("consumer connect failed after activity start retries\n");
	return -1;
}

/* Send a header-only or header+body message with no FDs. */
static bool rlawt_send_msg(int fd, uint16_t type, const void *body, uint32_t body_len) {
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
		RLAWT_LOG("send FAIL %s body=%u errno=%d (%s)\n",
			rlawt_msg_name(type), body_len, errno, strerror(errno));
		return false;
	}
	/* Per-frame chatter (FRAME_READY) is silenced; covered by the periodic
	 * frame summary in swapBuffers. Control messages always log. */
	if (type != RLAWT_MSG_FRAME_READY) {
		RLAWT_LOG("send OK   %s body=%u total=%zd\n", rlawt_msg_name(type), body_len, n);
	}
	return true;
}

/* Receive one header + body (no FDs). Returns body bytes (0 ok) or -1 on err. */
static int rlawt_recv_msg(int fd, struct rlawt_surface_hdr *hdr, void *body, uint32_t body_cap) {
	char ctrl_unused[CMSG_SPACE(sizeof(int))];
	struct iovec iov[2] = {
		{ .iov_base = hdr, .iov_len = sizeof(*hdr) },
		{ .iov_base = body, .iov_len = body_cap },
	};
	struct msghdr msg = {0};
	msg.msg_iov = iov;
	msg.msg_iovlen = 2;
	msg.msg_control = ctrl_unused;
	msg.msg_controllen = sizeof(ctrl_unused);
	ssize_t n = recvmsg(fd, &msg, 0);
	if (n < 0) {
		RLAWT_LOG("recv FAIL errno=%d (%s)\n", errno, strerror(errno));
		return -1;
	}
	if ((size_t)n < sizeof(*hdr)) {
		RLAWT_LOG("recv FAIL short header n=%zd want>=%zu\n", n, sizeof(*hdr));
		return -1;
	}
	if (hdr->magic != RLAWT_SURFACE_MAGIC || hdr->version != RLAWT_SURFACE_VERSION) {
		RLAWT_LOG("recv FAIL bad header magic=0x%08x ver=%u type=%u\n",
			hdr->magic, hdr->version, hdr->type);
		return -1;
	}
	int body_n = (int)(n - sizeof(*hdr));
	if (hdr->type != RLAWT_MSG_RELEASE) {
		RLAWT_LOG("recv OK   %s body=%d total=%zd\n", rlawt_msg_name(hdr->type), body_n, n);
	}
	return body_n;
}

/* Receive POOL_BUFFER carrying an AHB FD via SCM_RIGHTS. Reconstructs the
 * AHardwareBuffer and stores it at *out_ahb. Returns slot index or -1. */
static int rlawt_recv_pool_buffer(int fd, AHardwareBuffer **out_ahb) {
	struct rlawt_surface_hdr hdr;
	struct rlawt_surface_pool_buffer body;

	struct iovec iov[2] = {
		{ .iov_base = &hdr, .iov_len = sizeof(hdr) },
		{ .iov_base = &body, .iov_len = sizeof(body) },
	};
	char cbuf[CMSG_SPACE(sizeof(int))];
	struct msghdr msg = {0};
	msg.msg_iov = iov;
	msg.msg_iovlen = 2;
	msg.msg_control = cbuf;
	msg.msg_controllen = sizeof(cbuf);

	/* AHardwareBuffer_recvHandleFromUnixSocket reads its own datagram; it
	 * peeks no header. Our protocol sends POOL_BUFFER as a SEPARATE message
	 * before the AHB handle. Two-message-per-buffer pattern: first the
	 * header+slot, then the AHB handle on its own. The de-risker (#25)
	 * proved AHB_recvHandleFromUnixSocket works on this kernel. */
	int fds_pre = rlawt_count_fds();
	ssize_t n = recvmsg(fd, &msg, 0);
	if (n < 0) {
		RLAWT_LOG("recv POOL_BUFFER hdr FAIL errno=%d (%s)\n", errno, strerror(errno));
		return -1;
	}
	if (hdr.magic != RLAWT_SURFACE_MAGIC || hdr.type != RLAWT_MSG_POOL_BUFFER) {
		RLAWT_LOG("recv POOL_BUFFER WRONG TYPE magic=0x%08x type=%u (%s)\n",
			hdr.magic, hdr.type, rlawt_msg_name(hdr.type));
		return -1;
	}
	RLAWT_LOG("recv POOL_BUFFER hdr index=%u  fds_pre=%d\n", body.index, fds_pre);

	/* Now read the AHB handle (uses its own SCM_RIGHTS message). */
	if (AHardwareBuffer_recvHandleFromUnixSocket(fd, out_ahb) != 0) {
		RLAWT_LOG("AHardwareBuffer_recvHandleFromUnixSocket index=%u FAIL errno=%d\n", body.index, errno);
		return -1;
	}
	int fds_post = rlawt_count_fds();
	RLAWT_LOG("AHB import OK  index=%u ahb=%p fds_post=%d delta=%d\n",
		body.index, (void *)*out_ahb, fds_post, fds_post - fds_pre);
	return (int)body.index;
}

/* ----------------------------------------------- EGL setup and teardown */

static bool rlawt_egl_init(JNIEnv *env, AWTContext *ctx) {
	RLAWT_LOG("egl_init: alpha=%d depth=%d stencil=%d multisamples=%d\n",
		ctx->alphaDepth, ctx->depthDepth, ctx->stencilDepth, ctx->multisamples);
	ctx->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (ctx->egl_display == EGL_NO_DISPLAY) {
		RLAWT_LOG("eglGetDisplay FAIL egl_err=0x%x\n", eglGetError());
		rlawtThrow(env, "eglGetDisplay returned EGL_NO_DISPLAY");
		return false;
	}
	EGLint major = 0, minor = 0;
	if (!eglInitialize(ctx->egl_display, &major, &minor)) {
		RLAWT_LOG("eglInitialize FAIL egl_err=0x%x\n", eglGetError());
		rlawtThrow(env, "eglInitialize failed");
		return false;
	}
	RLAWT_LOG("EGL %d.%d vendor=%s version=%s\n",
		major, minor,
		eglQueryString(ctx->egl_display, EGL_VENDOR),
		eglQueryString(ctx->egl_display, EGL_VERSION));
	RLAWT_LOG("EGL_CLIENT_APIS=%s\n", eglQueryString(ctx->egl_display, EGL_CLIENT_APIS));
	RLAWT_LOG("EGL_EXTENSIONS=%s\n", eglQueryString(ctx->egl_display, EGL_EXTENSIONS));

	/* Pixmap-class config — we only use EGLImages, never an EGLSurface bound
	 * to the panel. A minimal pbuffer is required by eglMakeCurrent on some
	 * drivers; configure for it. */
	const EGLint cfg_attribs[] = {
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
		EGL_RED_SIZE, 8,
		EGL_GREEN_SIZE, 8,
		EGL_BLUE_SIZE, 8,
		EGL_ALPHA_SIZE, ctx->alphaDepth ? ctx->alphaDepth : 8,
		EGL_DEPTH_SIZE, ctx->depthDepth,
		EGL_STENCIL_SIZE, ctx->stencilDepth,
		EGL_NONE
	};
	EGLint num = 0;
	if (!eglChooseConfig(ctx->egl_display, cfg_attribs, &ctx->egl_config, 1, &num) || num < 1) {
		RLAWT_LOG("eglChooseConfig FAIL num=%d egl_err=0x%x\n", num, eglGetError());
		rlawtThrow(env, "eglChooseConfig produced no matches");
		return false;
	}
	{
		EGLint r=0,g=0,b=0,a=0,d=0,s=0,vid=0;
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_RED_SIZE, &r);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_GREEN_SIZE, &g);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_BLUE_SIZE, &b);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_ALPHA_SIZE, &a);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_DEPTH_SIZE, &d);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_STENCIL_SIZE, &s);
		eglGetConfigAttrib(ctx->egl_display, ctx->egl_config, EGL_NATIVE_VISUAL_ID, &vid);
		RLAWT_LOG("eglChooseConfig OK num=%d picked R%d G%d B%d A%d D%d S%d native_vid=%d\n",
			num, r, g, b, a, d, s, vid);
	}

	const EGLint pb_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
	ctx->egl_pbuffer = eglCreatePbufferSurface(ctx->egl_display, ctx->egl_config, pb_attribs);
	if (ctx->egl_pbuffer == EGL_NO_SURFACE) {
		RLAWT_LOG("eglCreatePbufferSurface FAIL egl_err=0x%x\n", eglGetError());
		rlawtThrow(env, "eglCreatePbufferSurface failed");
		return false;
	}
	RLAWT_LOG("eglCreatePbufferSurface OK pbuffer=%p\n", (void *)ctx->egl_pbuffer);

	/* Match RuneLite's expected GL profile. RL plugin queries GL_VERSION and
	 * downgrades shaders if needed; GLES 3.2 (Mali r44p1 v1.r44p1) covers all
	 * we need. Request a context attribs version 3 first, fall back to 2. */
	const EGLint ctx_attribs_3[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	const EGLint ctx_attribs_2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
	const char *picked = "GLES3";
	ctx->egl_context = eglCreateContext(ctx->egl_display, ctx->egl_config, EGL_NO_CONTEXT, ctx_attribs_3);
	if (ctx->egl_context == EGL_NO_CONTEXT) {
		EGLint err3 = eglGetError();
		ctx->egl_context = eglCreateContext(ctx->egl_display, ctx->egl_config, EGL_NO_CONTEXT, ctx_attribs_2);
		picked = "GLES2";
		RLAWT_LOG("eglCreateContext GLES3 failed err=0x%x; fell back to GLES2\n", err3);
	}
	if (ctx->egl_context == EGL_NO_CONTEXT) {
		RLAWT_LOG("eglCreateContext FAIL both GLES3 and GLES2 egl_err=0x%x\n", eglGetError());
		rlawtThrow(env, "eglCreateContext failed for both GLES3 and GLES2");
		return false;
	}
	RLAWT_LOG("eglCreateContext OK ctx=%p picked=%s\n", (void *)ctx->egl_context, picked);

	if (!eglMakeCurrent(ctx->egl_display, ctx->egl_pbuffer, ctx->egl_pbuffer, ctx->egl_context)) {
		RLAWT_LOG("eglMakeCurrent FAIL egl_err=0x%x\n", eglGetError());
		rlawtThrow(env, "eglMakeCurrent on pbuffer failed");
		return false;
	}
	RLAWT_LOG("eglMakeCurrent OK on pbuffer\n");

	RLAWT_LOG("GL_VENDOR=%s\n",     glGetString(GL_VENDOR));
	RLAWT_LOG("GL_RENDERER=%s\n",   glGetString(GL_RENDERER));
	RLAWT_LOG("GL_VERSION=%s\n",    glGetString(GL_VERSION));
	RLAWT_LOG("GL_SHADING_LANGUAGE_VERSION=%s\n", glGetString(GL_SHADING_LANGUAGE_VERSION));
	{
		const GLubyte *exts = glGetString(GL_EXTENSIONS);
		RLAWT_LOG("GL_EXTENSIONS=%s\n", exts ? (const char *)exts : "(null)");
	}

	ctx->fpEGLImageTargetTex2D =
		(void (*)(unsigned int, void *)) eglGetProcAddress("glEGLImageTargetTexture2DOES");
	ctx->fpEglCreateImageKHR =
		(void *(*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *))
		eglGetProcAddress("eglCreateImageKHR");
	ctx->fpEglDestroyImageKHR =
		(EGLBoolean (*)(EGLDisplay, void *)) eglGetProcAddress("eglDestroyImageKHR");
	ctx->fpEglGetNativeClientBufferANDROID =
		(EGLClientBuffer (*)(const struct AHardwareBuffer *))
		eglGetProcAddress("eglGetNativeClientBufferANDROID");

	RLAWT_LOG("ext entrypoints: ImageTargetTex2D=%p eglCreateImageKHR=%p eglDestroyImageKHR=%p eglGetNativeClientBufferANDROID=%p\n",
		(void *)ctx->fpEGLImageTargetTex2D,
		(void *)ctx->fpEglCreateImageKHR,
		(void *)ctx->fpEglDestroyImageKHR,
		(void *)ctx->fpEglGetNativeClientBufferANDROID);
	if (!ctx->fpEGLImageTargetTex2D || !ctx->fpEglCreateImageKHR ||
	    !ctx->fpEglDestroyImageKHR || !ctx->fpEglGetNativeClientBufferANDROID) {
		rlawtThrow(env, "required EGL/GLES extension entrypoints missing");
		return false;
	}
	return true;
}

/* Allocate the FBO/texture/depthbuffer state for one slot, importing the AHB
 * as an EGLImage. Returns true on success. */
static bool rlawt_attach_slot(AWTContext *ctx, uint32_t i) {
	EGLClientBuffer cbuf = ctx->fpEglGetNativeClientBufferANDROID(ctx->ahbs[i]);
	if (!cbuf) {
		RLAWT_LOG("attach slot=%u eglGetNativeClientBufferANDROID NULL egl_err=0x%x\n", i, eglGetError());
		return false;
	}
	const EGLint img_attribs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
	ctx->egl_images[i] = ctx->fpEglCreateImageKHR(
		ctx->egl_display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, img_attribs);
	if (!ctx->egl_images[i]) {
		RLAWT_LOG("attach slot=%u eglCreateImageKHR FAIL egl_err=0x%x\n", i, eglGetError());
		return false;
	}

	glGenTextures(1, &ctx->color_textures[i]);
	glBindTexture(GL_TEXTURE_2D, ctx->color_textures[i]);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	ctx->fpEGLImageTargetTex2D(GL_TEXTURE_2D, ctx->egl_images[i]);
	GLenum gl_err_after_bind = glGetError();

	glGenFramebuffers(1, &ctx->fbos[i]);
	glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbos[i]);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
		GL_TEXTURE_2D, ctx->color_textures[i], 0);

	if (ctx->depth_renderbuffer && (ctx->depthDepth || ctx->stencilDepth)) {
		glBindRenderbuffer(GL_RENDERBUFFER, ctx->depth_renderbuffer);
		GLenum att = ctx->stencilDepth ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, att, GL_RENDERBUFFER, ctx->depth_renderbuffer);
	}

	GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if (status != GL_FRAMEBUFFER_COMPLETE) {
		RLAWT_LOG("attach slot=%u FBO incomplete status=0x%x bind_gl_err=0x%x\n", i, status, gl_err_after_bind);
		return false;
	}
	RLAWT_LOG("attach slot=%u OK image=%p tex=%u fbo=%u\n",
		i, ctx->egl_images[i], ctx->color_textures[i], ctx->fbos[i]);
	return true;
}

static void rlawt_detach_slot(AWTContext *ctx, uint32_t i) {
	if (ctx->fbos[i]) {
		glDeleteFramebuffers(1, &ctx->fbos[i]);
		ctx->fbos[i] = 0;
	}
	if (ctx->color_textures[i]) {
		glDeleteTextures(1, &ctx->color_textures[i]);
		ctx->color_textures[i] = 0;
	}
	if (ctx->egl_images[i]) {
		ctx->fpEglDestroyImageKHR(ctx->egl_display, ctx->egl_images[i]);
		ctx->egl_images[i] = NULL;
	}
	if (ctx->ahbs[i]) {
		AHardwareBuffer_release(ctx->ahbs[i]);
		ctx->ahbs[i] = NULL;
	}
	ctx->pool_busy[i] = 0;
}

static void rlawt_drain_pool(AWTContext *ctx) {
	int fds_pre = rlawt_count_fds();
	uint32_t old_size = ctx->pool_size;
	for (uint32_t i = 0; i < ctx->pool_size; i++) rlawt_detach_slot(ctx, i);
	if (ctx->depth_renderbuffer) {
		glDeleteRenderbuffers(1, &ctx->depth_renderbuffer);
		ctx->depth_renderbuffer = 0;
	}
	ctx->pool_size = 0;
	int fds_post = rlawt_count_fds();
	RLAWT_LOG("drain_pool old_size=%u fds %d->%d (delta=%d)\n",
		old_size, fds_pre, fds_post, fds_post - fds_pre);
}

/* Receive POOL_HELLO + N POOL_BUFFER + POOL_READY. Sets up FBOs. */
static bool rlawt_negotiate_pool(JNIEnv *env, AWTContext *ctx) {
	struct rlawt_surface_hdr hdr;
	struct rlawt_surface_pool_hello pool;
	int n = rlawt_recv_msg(ctx->sock_fd, &hdr, &pool, sizeof(pool));
	if (n < (int)sizeof(pool) || hdr.type != RLAWT_MSG_POOL_HELLO) {
		rlawtThrow(env, "expected POOL_HELLO from consumer");
		return false;
	}
	if (pool.pool_size < RLAWT_SURFACE_POOL_MIN || pool.pool_size > RLAWT_SURFACE_POOL_MAX) {
		rlawtThrow(env, "consumer announced out-of-range pool_size");
		return false;
	}
	ctx->pool_size = pool.pool_size;
	ctx->pool_width = pool.width;
	ctx->pool_height = pool.height;
	ctx->pool_format = pool.format;
	RLAWT_LOG("POOL_HELLO size=%u %ux%u format=%u stride=%u usage=0x%llx\n",
		ctx->pool_size, ctx->pool_width, ctx->pool_height, ctx->pool_format,
		pool.stride_pixels, (unsigned long long)pool.usage);

	/* Single shared depth/stencil renderbuffer sized to pool dims. Re-attached
	 * to each slot's FBO. We only render one frame at a time so sharing is
	 * safe. */
	if (ctx->depthDepth || ctx->stencilDepth) {
		glGenRenderbuffers(1, &ctx->depth_renderbuffer);
		glBindRenderbuffer(GL_RENDERBUFFER, ctx->depth_renderbuffer);
		GLenum fmt = ctx->stencilDepth ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT16;
		glRenderbufferStorage(GL_RENDERBUFFER, fmt, ctx->pool_width, ctx->pool_height);
	}

	for (uint32_t i = 0; i < ctx->pool_size; i++) {
		int slot = rlawt_recv_pool_buffer(ctx->sock_fd, &ctx->ahbs[i]);
		if (slot < 0 || slot != (int)i) {
			rlawtThrow(env, "POOL_BUFFER slot mismatch");
			return false;
		}
		if (!rlawt_attach_slot(ctx, i)) {
			rlawtThrow(env, "rlawt_attach_slot failed");
			return false;
		}
	}

	int rn = rlawt_recv_msg(ctx->sock_fd, &hdr, NULL, 0);
	if (rn < 0 || hdr.type != RLAWT_MSG_POOL_READY) {
		RLAWT_LOG("expected POOL_READY got type=%u (%s)\n", hdr.type, rlawt_msg_name(hdr.type));
		rlawtThrow(env, "expected POOL_READY from consumer");
		return false;
	}
	ctx->current_index = 0;
	memset(ctx->pool_busy, 0, sizeof(ctx->pool_busy));
	RLAWT_LOG("pool ready size=%u dims=%ux%u current_index=0 fds=%d\n",
		ctx->pool_size, ctx->pool_width, ctx->pool_height, rlawt_count_fds());
	return true;
}

/* ----------------------------------------------------------------- JNI */

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_createGLContext(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, false)) return;

	RLAWT_LOG("createGLContext START fds=%d\n", rlawt_count_fds());
	RLAWT_LOG("env: RLAWT_SURFACE_NAME=%s RLAWT_INITIAL_WIDTH=%s RLAWT_INITIAL_HEIGHT=%s RLAWT_PERF_CSV=%s\n",
		getenv("RLAWT_SURFACE_NAME") ? getenv("RLAWT_SURFACE_NAME") : "(default)",
		getenv("RLAWT_INITIAL_WIDTH") ? getenv("RLAWT_INITIAL_WIDTH") : "(unset)",
		getenv("RLAWT_INITIAL_HEIGHT") ? getenv("RLAWT_INITIAL_HEIGHT") : "(unset)",
		getenv("RLAWT_PERF_CSV") ? getenv("RLAWT_PERF_CSV") : "(unset)");

	/* Override is an abstract namespace NAME (no leading NUL). */
	const char *sock_override = getenv("RLAWT_SURFACE_NAME");
	ctx->sock_fd = rlawt_connect_consumer_with_retry(sock_override);
	if (ctx->sock_fd < 0) {
		rlawtThrow(env, "rlawt direct-surface: cannot connect to consumer");
		return;
	}

	/* Tell consumer the canvas size we intend to render at. JAWT doesn't
	 * give us a meaningful drawable on Android (no X11), so we send the
	 * AWT component size. RuneLite will resize via the ComponentListener
	 * path (5b-A) and we'll renegotiate. Use a sane default until then. */
	struct rlawt_surface_producer_hello hello = {
		.requested_width = 800,
		.requested_height = 600,
		.requested_format = RLAWT_SURFACE_FORMAT_RGBA8,
		.flags = 0,
	};
	const char *envw = getenv("RLAWT_INITIAL_WIDTH");
	const char *envh = getenv("RLAWT_INITIAL_HEIGHT");
	if (envw && *envw) hello.requested_width  = (uint32_t)strtoul(envw, NULL, 10);
	if (envh && *envh) hello.requested_height = (uint32_t)strtoul(envh, NULL, 10);

	if (!rlawt_send_msg(ctx->sock_fd, RLAWT_MSG_PRODUCER_HELLO, &hello, sizeof(hello))) {
		rlawtThrow(env, "send PRODUCER_HELLO failed");
		return;
	}

	if (!rlawt_egl_init(env, ctx)) return;

	if (!rlawt_negotiate_pool(env, ctx)) return;

	const char *csv = getenv("RLAWT_PERF_CSV");
	if (csv && *csv) {
		ctx->csv_fp = fopen(csv, "a");
		if (ctx->csv_fp) {
			setvbuf(ctx->csv_fp, NULL, _IOLBF, 0);
			fprintf(ctx->csv_fp,
				"# rlawt-android per-frame CSV CLOCK_MONOTONIC ns / us\n"
				"frame,t_post_swap_ns,swap_us,gap_us,finish_us,send_us,reuse_wait_us,submitted_index,next_index\n");
			ctx->csv_enabled = true;
			RLAWT_LOG("per-frame CSV enabled %s\n", csv);
		}
	}

	ctx->contextCreated = true;
	RLAWT_LOG("createGLContext OK pool=%u fbo[0]=%u\n", ctx->pool_size, ctx->fbos[0]);
}

void rlawtContextFreePlatform(JNIEnv *env, AWTContext *ctx) {
	(void)env;
	RLAWT_LOG("rlawtContextFreePlatform: contextCreated=%d sock_fd=%d frame_seq=%u fds=%d\n",
		ctx->contextCreated, ctx->sock_fd, ctx->frame_seq, rlawt_count_fds());
	if (!ctx->contextCreated) return;

	if (ctx->sock_fd >= 0) {
		rlawt_send_msg(ctx->sock_fd, RLAWT_MSG_BYE, NULL, 0);
	}

	if (ctx->egl_display != EGL_NO_DISPLAY) {
		eglMakeCurrent(ctx->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		rlawt_drain_pool(ctx);
		if (ctx->egl_context != EGL_NO_CONTEXT) eglDestroyContext(ctx->egl_display, ctx->egl_context);
		if (ctx->egl_pbuffer != EGL_NO_SURFACE) eglDestroySurface(ctx->egl_display, ctx->egl_pbuffer);
		eglTerminate(ctx->egl_display);
		RLAWT_LOG("EGL torn down\n");
	}

	if (ctx->sock_fd >= 0) {
		close(ctx->sock_fd);
		ctx->sock_fd = -1;
	}
	if (ctx->csv_fp) {
		fclose(ctx->csv_fp);
		ctx->csv_fp = NULL;
	}
	RLAWT_LOG("rlawtContextFreePlatform DONE fds=%d\n", rlawt_count_fds());
}

JNIEXPORT jint JNICALL Java_net_runelite_rlawt_AWTContext_setSwapInterval(JNIEnv *env, jobject self, jint interval) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return 0;
	if (interval < 0) interval = -interval; /* tear-control not meaningful on EGLImage path */
	EGLBoolean ok = eglSwapInterval(ctx->egl_display, interval);
	RLAWT_LOG("setSwapInterval %d -> %s\n", interval, ok ? "OK" : "FAIL");
	return interval;
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_makeCurrent(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return;
	if (!eglMakeCurrent(ctx->egl_display, ctx->egl_pbuffer, ctx->egl_pbuffer, ctx->egl_context)) {
		EGLint err = eglGetError();
		RLAWT_LOG("makeCurrent FAIL egl_err=0x%x\n", err);
		rlawtThrow(env, "eglMakeCurrent failed");
		return;
	}
	glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbos[ctx->current_index]);
	if (ctx->frame_seq < 3) {
		RLAWT_LOG("makeCurrent OK fbo=%u (idx=%u, frame_seq=%u)\n",
			ctx->fbos[ctx->current_index], ctx->current_index, ctx->frame_seq);
	}
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_detachCurrent(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return;
	if (!eglMakeCurrent(ctx->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
		RLAWT_LOG("detachCurrent egl_err=0x%x\n", eglGetError());
	}
}

/* Drain any pending RELEASE messages without blocking. Returns updated busy mask
 * implicitly via ctx->pool_busy[]. */
static void rlawt_drain_releases(AWTContext *ctx, bool block_for_one) {
	int flags = block_for_one ? 0 : MSG_DONTWAIT;
	uint32_t released = 0;
	for (;;) {
		struct rlawt_surface_hdr hdr;
		struct rlawt_surface_release rel;
		struct iovec iov[2] = {
			{ .iov_base = &hdr, .iov_len = sizeof(hdr) },
			{ .iov_base = &rel, .iov_len = sizeof(rel) },
		};
		struct msghdr msg = {0};
		msg.msg_iov = iov;
		msg.msg_iovlen = 2;
		ssize_t n = recvmsg(ctx->sock_fd, &msg, flags);
		if (n < 0) {
			if (errno == EAGAIN || errno == EWOULDBLOCK) {
				if (block_for_one) {
					RLAWT_LOG("drain_releases: blocked path with EAGAIN — unexpected\n");
				}
				if (released > 0 && ctx->frame_seq <= 5) {
					RLAWT_LOG("drain_releases drained=%u\n", released);
				}
				return;
			}
			RLAWT_LOG("drain_releases recvmsg FAIL errno=%d (%s)\n", errno, strerror(errno));
			return;
		}
		if (hdr.type == RLAWT_MSG_RELEASE && rel.index < ctx->pool_size) {
			ctx->pool_busy[rel.index] = 0;
			released++;
		} else if (hdr.type != RLAWT_MSG_RELEASE) {
			RLAWT_LOG("drain_releases got unexpected type=%u (%s)\n",
				hdr.type, rlawt_msg_name(hdr.type));
		}
		flags = MSG_DONTWAIT; /* after the first, never block */
		block_for_one = false;
	}
}

JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_swapBuffers(JNIEnv *env, jobject self) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return;

	struct timespec t_pre, t_post, t_after_finish, t_after_send;
	clock_gettime(CLOCK_MONOTONIC, &t_pre);

	/* Make sure GPU work is finished before we hand the AHB to the consumer.
	 * EGL_KHR_fence_sync would be cheaper than glFinish, but glFinish is the
	 * safest first cut. Mali r44p1 does not flush GLES commands across the
	 * AHB boundary without an explicit barrier. */
	glFinish();
	clock_gettime(CLOCK_MONOTONIC, &t_after_finish);

	/* Signal frame-ready for the buffer just rendered. */
	struct rlawt_surface_frame_ready fr = {
		.index = (uint16_t)ctx->current_index,
		.reserved = 0,
		.frame_seq = ++ctx->frame_seq,
	};
	ctx->pool_busy[ctx->current_index] = 1;
	if (!rlawt_send_msg(ctx->sock_fd, RLAWT_MSG_FRAME_READY, &fr, sizeof(fr))) {
		rlawtThrow(env, "send FRAME_READY failed");
		return;
	}
	clock_gettime(CLOCK_MONOTONIC, &t_after_send);

	/* Drain any pending releases. */
	rlawt_drain_releases(ctx, false);

	/* Pick next free slot. If everything is busy, block on RELEASE. */
	uint32_t next = (ctx->current_index + 1) % ctx->pool_size;
	for (uint32_t tries = 0; tries < ctx->pool_size; tries++) {
		if (!ctx->pool_busy[next]) break;
		next = (next + 1) % ctx->pool_size;
	}
	if (ctx->pool_busy[next]) {
		RLAWT_LOG("pool starved frame=%u — blocking on RELEASE (busy_mask=%d%d%d%d%d%d)\n",
			ctx->frame_seq,
			ctx->pool_size > 0 ? ctx->pool_busy[0] : -1,
			ctx->pool_size > 1 ? ctx->pool_busy[1] : -1,
			ctx->pool_size > 2 ? ctx->pool_busy[2] : -1,
			ctx->pool_size > 3 ? ctx->pool_busy[3] : -1,
			ctx->pool_size > 4 ? ctx->pool_busy[4] : -1,
			ctx->pool_size > 5 ? ctx->pool_busy[5] : -1);
	}
	while (ctx->pool_busy[next]) {
		rlawt_drain_releases(ctx, true);
		for (uint32_t i = 0; i < ctx->pool_size; i++) {
			if (!ctx->pool_busy[i]) { next = i; break; }
		}
	}
	ctx->current_index = next;

	/* Re-bind FBO so the next batch of draws lands on the new AHB. RL also
	 * calls makeCurrent again at the top of the next frame, which re-binds. */
	glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbos[ctx->current_index]);

	clock_gettime(CLOCK_MONOTONIC, &t_post);
	uint64_t finish_us = ts_delta_us(&t_after_finish, &t_pre);
	uint64_t send_us   = ts_delta_us(&t_after_send, &t_after_finish);
	uint64_t reuse_wait_us = ts_delta_us(&t_post, &t_after_send);
	uint64_t swap_us   = ts_delta_us(&t_post, &t_pre);
	uint64_t gap_us = 0;
	if (ctx->last_post_swap_ts.tv_sec || ctx->last_post_swap_ts.tv_nsec) {
		gap_us = ts_delta_us(&t_pre, &ctx->last_post_swap_ts);
	}
	ctx->last_post_swap_ts = t_post;

	/* Periodic stdout summary. The first 5 frames always log so we can spot
	 * a startup hang; after that, every Nth frame. The total frame counter
	 * lives in ctx->frame_seq (1-based, was just incremented). */
	if (ctx->frame_seq <= 5 || (ctx->frame_seq % RLAWT_LOG_FRAME_PERIOD) == 0) {
		uint32_t busy_count = 0;
		for (uint32_t i = 0; i < ctx->pool_size; i++) busy_count += ctx->pool_busy[i] ? 1 : 0;
		RLAWT_LOG("frame=%u idx=%u->%u finish_us=%llu send_us=%llu swap_us=%llu gap_us=%llu busy=%u/%u\n",
			ctx->frame_seq, fr.index, ctx->current_index,
			(unsigned long long)finish_us, (unsigned long long)send_us,
			(unsigned long long)swap_us, (unsigned long long)gap_us,
			busy_count, ctx->pool_size);
	}

	if (ctx->csv_enabled && ctx->csv_fp) {
		uint64_t t_post_ns = (uint64_t)t_post.tv_sec * 1000000000ULL + (uint64_t)t_post.tv_nsec;
		fprintf(ctx->csv_fp, "%u,%llu,%llu,%llu,%llu,%llu,%llu,%u,%u\n",
			ctx->frame_seq, (unsigned long long)t_post_ns,
			(unsigned long long)swap_us, (unsigned long long)gap_us,
			(unsigned long long)finish_us, (unsigned long long)send_us,
			(unsigned long long)reuse_wait_us,
			fr.index, ctx->current_index);
	}
}

/* RuneLite's GPU plugin queries getFramebuffer(true) to decide whether to bind
 * GL_FRONT (default FB) or GL_COLOR_ATTACHMENT0 (FBO mode). On the direct-
 * surface path we MUST be FBO mode, so always return a non-zero handle. */
JNIEXPORT jint JNICALL Java_net_runelite_rlawt_AWTContext_getFramebuffer(JNIEnv *env, jobject self, jboolean front) {
	(void)front;
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return 0;
	return (jint)ctx->fbos[ctx->current_index];
}

/* Resize hook driven by AWTContext.notifyResized JNI (added by 5b-A). */
JNIEXPORT void JNICALL Java_net_runelite_rlawt_AWTContext_notifyResized(JNIEnv *env, jobject self, jint width, jint height) {
	AWTContext *ctx = rlawtGetContext(env, self);
	if (!ctx || !rlawtContextState(env, ctx, true)) return;
	if (width <= 0 || height <= 0) return;
	if ((uint32_t)width == ctx->pool_width && (uint32_t)height == ctx->pool_height) return;

	RLAWT_LOG("notifyResized %ux%u -> %dx%d\n", ctx->pool_width, ctx->pool_height, width, height);

	/* Drain any in-flight RELEASEs first so the consumer can teardown cleanly. */
	rlawt_drain_releases(ctx, false);

	struct rlawt_surface_resize_request req = {
		.width = (uint32_t)width,
		.height = (uint32_t)height,
	};
	if (!rlawt_send_msg(ctx->sock_fd, RLAWT_MSG_RESIZE_REQUEST, &req, sizeof(req))) {
		rlawtThrow(env, "send RESIZE_REQUEST failed");
		return;
	}

	/* Tear down current pool while EGL context is current. */
	rlawt_drain_pool(ctx);

	if (!rlawt_negotiate_pool(env, ctx)) return;
	glBindFramebuffer(GL_FRAMEBUFFER, ctx->fbos[ctx->current_index]);
}

#endif /* __unix__ && RLAWT_DIRECT_SURFACE */
