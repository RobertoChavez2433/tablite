# Plan: VirGL Fix Pipeline — FBO Rendering, SIGSEGV, and Fallback Resilience

**Date**: 2026-03-11
**Spec**: N/A — derived from on-device test results
**Previous Plan**: `.claude/plans/2026-03-09-virgl-test-pipeline.md`
**Status**: APPROVED (adversarial review completed, 7 MUST-FIX incorporated)
**Adversarial Review**: `.claude/adversarial_reviews/2026-03-11-virgl-fix-pipeline/review.md`

---

## Background

The VirGL test pipeline (14 files in `runelite-tablet/gl-tests/`) was fully implemented and
deployed. On-device testing revealed three interconnected bugs:

1. **P0: FBO rendering completely broken** — `glClear(0,0,0,1)` produces A=0. The FBO color
   attachment is never written to. This also means the RuneLite GPU plugin (which relies on FBO
   blitting) cannot work in its current configuration.

2. **P0: `--all` SIGSEGV** — crashes before any `[INFO]` output, almost certainly in
   `log_gl_caps()` during integer query enumeration or extension string enumeration.

3. **P1: Default framebuffer fallback absent** — modules currently hard-fail when FBO creation
   fails, producing no results instead of degraded results. We need the pipeline to produce
   useful signal even when FBO is broken.

These bugs are interconnected: the SIGSEGV must be fixed first (it happens before FBO tests
run), then the FBO problem must be diagnosed using the working pipeline.

---

## Blast Radius

| Category | Files | Notes |
|----------|-------|-------|
| Modified: C source | `gl-tests/src/gl_test_log.h` | Remove dangerous int_queries, add fflush fence |
| Modified: C source | `gl-tests/src/gl_test_harness.c` | glGetStringi gating, GLFW_VISIBLE, FBO fixes, fallback logic, probe_fbo |
| Modified: Shell | `gl-tests/scripts/run-tests.sh` | MESA_EXTENSION_OVERRIDE, GL/GLSL version, test order |
| Modified: Shell | `app/src/main/assets/scripts/launch-runelite.sh` | Winning env config (Phase 5 only) |
| No change | `gl-tests/src/fix_flip_depth.c` | |
| No change | `gl-tests/src/fix_inject_clipcontrol.c` | |
| No change | `gl-tests/scripts/deploy.sh` | |
| No change | `gl-tests/scripts/build.sh` | |
| No change | `gl-tests/scripts/install-deps.sh` | |

Modules 4, 5, 6, 7, 8, 9 in `gl_test_harness.c` gain fallback logic. Module 7 gains a new
diagnostic code path. No external API changes; all changes are self-contained within the test
pipeline and launch script.

---

## Agent Routing

| File Pattern | Agent |
|---|---|
| `gl-tests/scripts/*.sh` | `termux-shell-agent` |
| `gl-tests/src/*.c`, `gl-tests/src/*.h` | Main session |
| `app/src/main/assets/scripts/launch-runelite.sh` | `termux-shell-agent` |
| Review (any phase) | `code-review-agent` |

---

## Phase Dependencies

```
Phase 0 (SIGSEGV fix) ──────────────────────────────────────────┐
                                                                  ▼
Phase 1 (env fixes: EXTENSION_OVERRIDE + GL version) ──────> Phase 2 (FBO code fixes)
                                                                  │
                                                         [FBO probe: WORKS?]
                                                         YES ──► Phase 5 (apply to RuneLite)
                                                         NO  ──► Phase 3 (fallback)
                                                                  │
                                                                  ▼
                                                         Phase 4 (alt GPU backends)
                                                                  │
                                                                  ▼
                                                         Phase 5 (apply winning config)
                                                                  │
                                                                  ▼
                                                         Phase 6 (deploy + full verify)
```

---

## Rollback Strategy [AR: SC-3]

Before starting implementation, create a branch: `git checkout -b virgl-fix-pipeline`.
Each phase's changes can be reverted independently:
- **Phase 0**: `git checkout -- runelite-tablet/gl-tests/src/gl_test_log.h runelite-tablet/gl-tests/src/gl_test_harness.c`
- **Phase 1**: `git checkout -- runelite-tablet/gl-tests/scripts/run-tests.sh`
- **Phase 2**: `git checkout -- runelite-tablet/gl-tests/src/gl_test_harness.c`
- **Phase 3**: `git checkout -- runelite-tablet/gl-tests/src/gl_test_harness.c`
- **Phase 5**: `git checkout -- runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`

---

## Shell Script Exception [AR: MF-5]

`run-tests.sh` deliberately omits `set -e` and uses `set -uo pipefail` because proot exit codes
are unreliable (documented in `.claude/rules/shell-scripts.md` proot exception). This is a
known exception, NOT a rule violation. Error checking for env var setup relies on proot command
parsing failures, not shell `-e`. `launch-runelite.sh` uses standard `set -euo pipefail`.

---

## CRLF Protection [AR: CC-1]

All modified shell scripts must preserve LF line endings. The existing `.gitattributes` covers
`gl-tests/scripts/*.sh` with `eol=lf`. Verify `launch-runelite.sh` is also covered.

---

## Phase 0: SIGSEGV Crash Bisection

**Goal**: Make `--all` and `--module 1` run to completion without segfault. This is a
prerequisite for all other phases — nothing can be diagnosed if the harness crashes before
printing anything.

**Root cause ranking** (from research):

| # | Cause | Probability |
|---|-------|------------|
| 1 | `GL_MAX_VARYING_FLOATS` (0x8B4B) not defined in GLES 3.x → hard crash in virpipe | HIGHEST |
| 2 | `glXGetProcAddressARB` returns non-NULL stub for `glGetStringi` → stub crashes on call | HIGH |
| 3 | `GL_DEPTH_BITS`, `GL_STENCIL_BITS` deprecated in core profile → stale-error cascade | MEDIUM |
| 4 | Output buffered → crash appears "before any [INFO]" even if some queries ran | LOW |

### Step 0.1: Remove dangerous integer queries from `gl_test_log.h`

**File**: `runelite-tablet/gl-tests/src/gl_test_log.h`

**Change**: In the `int_queries[]` table (lines 272–299), remove the following six entries:

```c
// REMOVE these entries from int_queries[]:
{GL_MAX_VARYING_FLOATS, "max_varying_floats"},  // Desktop GL 2.0 only (0x8B4B) — GLES crash
{GL_MAX_CLIP_DISTANCES, "max_clip_distances"},   // GL_EXT_clip_cull_distance on GLES — not safe on virpipe [AR: SC-5]
{GL_DEPTH_BITS, "depth_bits"},                  // Removed from core in GL 3.1+
{GL_STENCIL_BITS, "stencil_bits"},              // Removed from core in GL 3.1+
{GL_SAMPLE_BUFFERS, "sample_buffers"},           // Deprecated; may cascade stale errors
{GL_SAMPLES, "samples"},                         // Deprecated; may cascade stale errors
{GL_SUBPIXEL_BITS, "subpixel_bits"},             // Not available in all GLES/virpipe contexts
```

The remaining queries (`GL_MAX_TEXTURE_SIZE`, `GL_MAX_3D_TEXTURE_SIZE`,
`GL_MAX_ARRAY_TEXTURE_LAYERS`, `GL_MAX_TEXTURE_IMAGE_UNITS`, `GL_MAX_VERTEX_ATTRIBS`,
`GL_MAX_VERTEX_UNIFORM_COMPONENTS`, `GL_MAX_FRAGMENT_UNIFORM_COMPONENTS`,
`GL_MAX_UNIFORM_BLOCK_SIZE`, `GL_MAX_UNIFORM_BUFFER_BINDINGS`, `GL_MAX_DRAW_BUFFERS`,
`GL_MAX_COLOR_ATTACHMENTS`, `GL_MAX_RENDERBUFFER_SIZE`, `GL_MAX_VIEWPORT_DIMS`,
`GL_MAX_SAMPLES`, `GL_MAX_VERTEX_OUTPUT_COMPONENTS`, `GL_MAX_FRAGMENT_INPUT_COMPONENTS`,
`GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS`, `GL_MAX_ELEMENTS_VERTICES`,
`GL_MAX_ELEMENTS_INDICES`) are all GLES 3.x-safe and should stay.

### Step 0.2: Add fflush fence before each `glGetIntegerv` call

**File**: `runelite-tablet/gl-tests/src/gl_test_log.h`

**Change**: In the `log_gl_caps()` function, the loop over `int_queries` currently does:

```c
for (int i = 0; i < num_queries; i++) {
    GLint val[2] = {0, 0};
    glGetIntegerv(int_queries[i].e, val);
```

Change to:

```c
for (int i = 0; i < num_queries; i++) {
    GLint val[2] = {0, 0};
    fflush(stdout);  /* Ensure output visible before any potential crash */
    if (g_log_file) fflush(g_log_file);  /* [AR: MF-4] NULL guard — fflush(NULL) flushes ALL streams */
    glGetIntegerv(int_queries[i].e, val);
```

This ensures that if any query crashes, the last output line tells us exactly which query was
in flight. Without this, buffered stdout means "no output" even if 20 queries ran fine.

Also add `fflush(stdout)` immediately before the extension enumeration block (before
`glGetIntegerv(GL_NUM_EXTENSIONS, &num_ext)`).

### Step 0.3: Version-gate `glGetStringi` lookup

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Context**: Current code at line 331:

```c
PFNGLGETSTRINGIPROC pglGetStringi = (PFNGLGETSTRINGIPROC)glXGetProcAddressARB((const GLubyte *)"glGetStringi");

if (pglGetStringi && num_ext > 0) {
    for (int i = 0; i < num_ext; i++) {
        const char *ext = (const char *)pglGetStringi(GL_EXTENSIONS, i);
```

**Problem**: `glXGetProcAddressARB` is documented to NEVER return NULL (Mesa DRI wiki). It
returns a non-NULL stub even for functions that don't exist. Calling that stub crashes.

**Change**: Version-gate the call and validate index 0 before iterating:

```c
/* glXGetProcAddressARB never returns NULL (Mesa design) — gate on GL version >= 3.0.
 * Also validate index 0 before iterating to detect non-NULL crash stubs. */
PFNGLGETSTRINGIPROC pglGetStringi = NULL;
{
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    while (glGetError() != GL_NO_ERROR) {}
    if (major >= 3) {
        pglGetStringi = (PFNGLGETSTRINGIPROC)glXGetProcAddressARB((const GLubyte *)"glGetStringi");
        /* Validate: call index 0 and check result before trusting for full loop */
        if (pglGetStringi && num_ext > 0) {
            const char *probe = (const char *)pglGetStringi(GL_EXTENSIONS, 0);
            if (!probe) {
                LOG_ERROR("glGetStringi returned NULL for index 0 — disabling (crash stub)");
                pglGetStringi = NULL;
            }
        }
    } else {
        LOG_INFO("GL version < 3.0 — skipping glGetStringi (GL_EXTENSIONS string only)");
    }
}

if (pglGetStringi && num_ext > 0) {
    for (int i = 0; i < num_ext; i++) {
        const char *ext = (const char *)pglGetStringi(GL_EXTENSIONS, i);
```

### Quality Gate G0

Deploy, rebuild, then test in two steps [AR: SC-6]:

**Step 1**: Test Module 1 in isolation (this is where the crash was):
```bash
# Run module 1 alone — if this crashes, the SIGSEGV fix is incomplete
adb shell "run-as com.termux bash -lc 'cd ~/gl-tests && proot-distro login ubuntu --shared-tmp -- env GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=/tmp/.virgl_test MESA_GLX_ALPHA_BITS=0 MESA_GL_VERSION_OVERRIDE=4.3COMPAT MESA_GLSL_VERSION_OVERRIDE=430 DISPLAY=:0 ./build/gl_test_harness --results-dir /tmp/g0-test --module 1'"
```

**Step 2**: Run `--all` to verify no SIGSEGV across all modules:
```bash
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/run-tests.sh --quick'" 2>&1 | head -50
```

Expected: `[INFO] === VirGL Test Harness ===` appears; no SIGSEGV. Module 1 prints vendor/
renderer/version strings. `--all` completes all 9 modules (even if many report FAIL/SKIP —
FBO failures are expected at this phase).

---

## Phase 1: FBO Environment Fixes

**Goal**: Fix FBO rendering by correcting the Mesa environment configuration. These are
changes to environment variables only — no C source changes. They have the highest
probability of fixing FBO without requiring code changes.

**Root cause ranking**:

| # | Cause | Probability |
|---|-------|------------|
| 1 | `GL_DEPTH_CLAMP` breaks virglrenderer: Mesa 4.5COMPAT auto-enables it; GLES host returns GL_INVALID_ENUM; stale error causes all subsequent draws/clears to be silently discarded | MOST LIKELY |
| 2 | `MESA_GL_VERSION_OVERRIDE=4.5COMPAT` + `MESA_GLSL_VERSION_OVERRIDE=330` mismatch: 4.5 implies GLSL 450; using 330 creates inconsistent state in virglrenderer | HIGH |

### Step 1.1: Add `MESA_EXTENSION_OVERRIDE` to `run-tests.sh`

**File**: `runelite-tablet/gl-tests/scripts/run-tests.sh`
**Agent**: `termux-shell-agent`

**Context**: Current env block at lines 244–248:

```bash
PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=virpipe"
PROOT_CMD="$PROOT_CMD VTEST_SOCKET_NAME=/tmp/.virgl_test"
PROOT_CMD="$PROOT_CMD MESA_GLX_ALPHA_BITS=0"
PROOT_CMD="$PROOT_CMD MESA_GL_VERSION_OVERRIDE=4.5COMPAT"
PROOT_CMD="$PROOT_CMD MESA_GLSL_VERSION_OVERRIDE=330"
```

**Change**: Replace those five lines with:

```bash
PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=virpipe"
PROOT_CMD="$PROOT_CMD VTEST_SOCKET_NAME=/tmp/.virgl_test"
PROOT_CMD="$PROOT_CMD MESA_GLX_ALPHA_BITS=0"
# GL_DEPTH_CLAMP fix: Mesa 4.5COMPAT auto-enables GL_DEPTH_CLAMP; GLES host
# returns GL_INVALID_ENUM which causes virglrenderer to discard all subsequent
# draws. Disable both ARB and EXT variants. (Termux issue #15832)
PROOT_CMD="$PROOT_CMD MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp"
# Use 4.3COMPAT to avoid 4.5-specific state that virglrenderer doesn't handle.
# Match GLSL to the GL version: 4.3 = GLSL 430.
PROOT_CMD="$PROOT_CMD MESA_GL_VERSION_OVERRIDE=4.3COMPAT"
PROOT_CMD="$PROOT_CMD MESA_GLSL_VERSION_OVERRIDE=430"
```

**Rationale for 4.3COMPAT + 430**: GLSL 430 is consistent with GL 4.3. The previous 4.5COMPAT +
330 mismatch (4.5 implies GLSL 450; 330 is GL 3.3) could confuse virglrenderer's shader
translator. Termux issue #15832 specifically reports Mali + virpipe FBO failures caused by the
depth clamp stale-error chain.

**NOTE [AR: MF-3]**: RuneLite requires GL 4.3+ (confirmed in research). 4.3COMPAT satisfies this.
Both `run-tests.sh` and `launch-runelite.sh` MUST use identical env vars so that tests accurately
reflect what RuneLite will encounter. If RuneLite's version-string check requires `4.5` specifically
(unlikely — research says 4.3+), use `4.5COMPAT` in both scripts and rely on
`MESA_EXTENSION_OVERRIDE` alone for the depth-clamp fix.

**NOTE [AR: MF-6]**: `MESA_EXTENSION_OVERRIDE` works at the Mesa guest layer. It prevents Mesa from
generating depth-clamp commands in the virpipe protocol, so virglrenderer never receives them.
This is the correct layer to fix it. However, add a verification step:

### Step 1.1a: Verify MESA_EXTENSION_OVERRIDE takes effect [AR: MF-6]

After deploying the env var change, run Module 1 and check `gl-caps.json`:
```bash
# After run, pull gl-caps.json and grep for depth_clamp
adb shell "run-as com.termux bash -lc 'cat ~/gl-tests/results/*/gl-caps.json'" | grep -i clamp
```
If `GL_ARB_depth_clamp` appears in the extension list, `MESA_EXTENSION_OVERRIDE` is NOT taking
effect. Check for quoting issues in the `PROOT_CMD` string — commas in the value may need escaping.

**NOTE [AR: SC-4]**: `run-tests.sh` passes env vars via `PROOT_CMD` (command-line to proot),
while `launch-runelite.sh` uses `export`. The comma-separated `MESA_EXTENSION_OVERRIDE` value
may need different quoting. Verify both paths produce the same result by checking
`env | grep MESA` inside the proot session.

### Step 1.2: Update GLFW context request in `gl_test_harness.c`

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Context**: Lines 1585–1589 request a 3.3 compat context from GLFW. Mesa ignores this hint
when `MESA_GL_VERSION_OVERRIDE` is set, so the actual context is 4.3COMPAT. However, the
harness GLSL shaders use `#version 330` — which is valid in a 4.3COMPAT context (it's
backwards compatible). No shader changes needed.

**No change required** to GLFW hints — Mesa's override takes precedence.

### Quality Gate G1

Deploy updated `run-tests.sh`, rebuild (no C changes so no recompile needed), run:

```bash
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/run-tests.sh --quick'" 2>&1 | tail -30
```

Then pull the FBO result image and inspect:

```bash
adb shell "run-as com.termux bash -lc 'cat \$PREFIX/tmp/gl-results-latest.tar.gz'" > /tmp/gl-results.tar.gz
tar xzf /tmp/gl-results.tar.gz -C /tmp/gl-results/
# Look at module 4 PNG
file /tmp/gl-results/module4*.png
```

**Pass criterion**: Module 4 PNG has non-zero alpha channel (A > 0); module result is not
"all black/zero". If the center pixel of the 32x32 probe FBO has B >= 200, the FBO is working.

---

## Phase 2: FBO Code Fixes

**Goal**: If Phase 1 environment fixes were insufficient, fix the FBO at the code level.
Apply all three code changes in a single deploy — they are non-conflicting and each targets
a distinct failure mode.

**Root causes being addressed**:

| # | Cause | Probability |
|---|-------|------------|
| 2 | `GL_RGBA8` sized internal format: virglrenderer GLES backend may translate it incorrectly (virglrenderer issue #221, BGRA emulation) | HIGH |
| 3 | `GL_DEPTH_COMPONENT24` depth texture fails silently on GLES hosts; guest Mesa reports FRAMEBUFFER_COMPLETE but host FBO is broken | HIGH |

### Step 2.1: Add FBO probe function to `gl_test_harness.c`

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Change**: Add global flag and probe function before `create_fbo()`:

```c
/* ===== FBO Capability Probe ===== */

static int g_fbo_works = -1; /* -1=untested, 0=broken, 1=works */

/* probe_fbo_capability() — Create a minimal 32x32 FBO, clear to blue, read center pixel.
 * If blue >= 200 and alpha >= 200, FBO is working. If all-zero, FBO is broken.
 * Sets g_fbo_works. Logs result. Must be called after GL context is current. */
static void probe_fbo_capability(void) {
    if (!pglGenFramebuffers || !pglBindFramebuffer || !pglFramebufferTexture2D ||
        !pglCheckFramebufferStatus) {
        LOG_INFO("FBO probe: SKIP (FBO extension functions not available)");
        g_fbo_works = 0;
        return;
    }

    GLuint fbo, colorTex;
    glGenTextures(1, &colorTex);
    glBindTexture(GL_TEXTURE_2D, colorTex);
    /* Use unsized GL_RGBA (Cause 2 fix) */
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    pglGenFramebuffers(1, &fbo);
    pglBindFramebuffer(GL_FRAMEBUFFER, fbo);
    pglFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);

    GLenum status = pglCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOG_ERROR("FBO probe: INCOMPLETE (status=0x%04x) — FBO broken", status);
        g_fbo_works = 0;
        pglDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &colorTex);
        return;
    }

    glViewport(0, 0, 32, 32);
    glClearColor(0.0f, 0.0f, 1.0f, 1.0f); /* Blue */
    glClear(GL_COLOR_BUFFER_BIT);
    CHECK_GL("FBO probe glClear");

    GLubyte pixel[4] = {0, 0, 0, 0};
    glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    CHECK_GL("FBO probe glReadPixels");

    LOG_INFO("FBO probe: center pixel R=%d G=%d B=%d A=%d", pixel[0], pixel[1], pixel[2], pixel[3]);

    if (pixel[2] >= 200 && pixel[3] >= 200) {
        LOG_INFO("FBO probe: WORKS (blue clear successful)");
        g_fbo_works = 1;
    } else if (pixel[0] == 0 && pixel[1] == 0 && pixel[2] == 0 && pixel[3] == 0) {
        LOG_ERROR("FBO probe: BROKEN (all-zero — color attachment not written)");
        g_fbo_works = 0;
    } else {
        LOG_ERROR("FBO probe: BROKEN (unexpected pixel — expected B>=200,A>=200 got R=%d G=%d B=%d A=%d)",
                  pixel[0], pixel[1], pixel[2], pixel[3]);
        g_fbo_works = 0;
    }

    pglDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &colorTex);

    /* Restore default framebuffer */
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
}
```

### Step 2.2: Change `GL_RGBA8` to unsized `GL_RGBA` in `create_fbo()`

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Context**: Line 295:
```c
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
```

**Change**:
```c
/* Use unsized GL_RGBA instead of GL_RGBA8: virglrenderer's GLES backend has
 * known BGRA emulation issues with sized internal formats (issue #221). */
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
```

### Step 2.3: Add renderbuffer function pointers [AR: MF-1]

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**CRITICAL [AR: MF-1]**: The harness resolves ALL GL extension functions via
`glXGetProcAddressARB` with `pgl*` prefixed pointers. Using bare GL calls will SIGSEGV.
Add these 5 typedefs, statics, and resolution lines:

```c
/* In the typedef section (near other PFNGL* types): */
typedef void (*PFNGLGENRENDERBUFFERSPROC)(GLsizei, GLuint*);
typedef void (*PFNGLBINDRENDERBUFFERPROC)(GLenum, GLuint);
typedef void (*PFNGLRENDERBUFFERSTORAGEPROC)(GLenum, GLenum, GLsizei, GLsizei);
typedef void (*PFNGLFRAMEBUFFERRENDERBUFFERPROC)(GLenum, GLenum, GLenum, GLuint);
typedef void (*PFNGLDELETERENDERBUFFERSPROC)(GLsizei, const GLuint*);

/* In the static pointer section: */
static PFNGLGENRENDERBUFFERSPROC pglGenRenderbuffers = NULL;
static PFNGLBINDRENDERBUFFERPROC pglBindRenderbuffer = NULL;
static PFNGLRENDERBUFFERSTORAGEPROC pglRenderbufferStorage = NULL;
static PFNGLFRAMEBUFFERRENDERBUFFERPROC pglFramebufferRenderbuffer = NULL;
static PFNGLDELETERENDERBUFFERSPROC pglDeleteRenderbuffers = NULL;

/* In resolve_all_functions(): */
RESOLVE(pglGenRenderbuffers, PFNGLGENRENDERBUFFERSPROC, "glGenRenderbuffers");
RESOLVE(pglBindRenderbuffer, PFNGLBINDRENDERBUFFERPROC, "glBindRenderbuffer");
RESOLVE(pglRenderbufferStorage, PFNGLRENDERBUFFERSTORAGEPROC, "glRenderbufferStorage");
RESOLVE(pglFramebufferRenderbuffer, PFNGLFRAMEBUFFERRENDERBUFFERPROC, "glFramebufferRenderbuffer");
RESOLVE(pglDeleteRenderbuffers, PFNGLDELETERENDERBUFFERSPROC, "glDeleteRenderbuffers");
```

### Step 2.4: Replace depth texture with renderbuffer in `create_fbo()` [AR: MF-2]

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Change `create_fbo()` signature** to track depth type:

```c
/* New signature — depth_is_rbo output tells destroy_fbo how to clean up */
static int create_fbo(GLuint *fbo, GLuint *colorTex, GLuint *depthRes,
                      int *depth_is_rbo, int w, int h)
```

**Replace depth texture creation with renderbuffer** (using `pgl*` prefixed pointers):

```c
/* Old depth texture creation — REMOVE */

/* New depth renderbuffer — depth textures can fail silently on GLES hosts;
 * renderbuffers are more reliably implemented in virglrenderer. */
if (!pglGenRenderbuffers || !pglBindRenderbuffer || !pglRenderbufferStorage ||
    !pglFramebufferRenderbuffer) {
    LOG_ERROR("Renderbuffer functions not available — cannot create depth attachment");
    /* Fall through — color-only FBO may still work */
    *depthRes = 0;
    *depth_is_rbo = 0;
} else {
    GLuint depthRbo;
    pglGenRenderbuffers(1, &depthRbo);
    pglBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
    pglRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
    CHECK_GL("depth renderbuffer storage");
    pglFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);
    CHECK_GL("depth renderbuffer attach");
    *depthRes = depthRbo;
    *depth_is_rbo = 1;
}
```

**Update `destroy_fbo()` [AR: MF-2]** — must distinguish texture vs renderbuffer:

```c
static void destroy_fbo(GLuint fbo, GLuint colorTex, GLuint depthRes, int depth_is_rbo) {
    if (pglDeleteFramebuffers) pglDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &colorTex);
    if (depthRes) {
        if (depth_is_rbo && pglDeleteRenderbuffers)
            pglDeleteRenderbuffers(1, &depthRes);
        else
            glDeleteTextures(1, &depthRes);
    }
}
```

**Update ALL call sites** of `create_fbo()` and `destroy_fbo()` to pass the new parameters.
Remove the now-unused `depth32f` parameter [AR: NP-2].

**NOTE [AR: SC-9]**: All Phase 2 C changes (Steps 2.1-2.6) MUST be compiled and deployed as a
single unit. The FBO probe (Step 2.1) uses `GL_RGBA` unsized format; if deployed separately from
Step 2.2 (`GL_RGBA8` → `GL_RGBA` in `create_fbo()`), the probe would report WORKS while actual
FBOs remain broken — a false positive.

Also add `glGetError()` after each FBO operation for diagnosis:

```c
/* After pglBindFramebuffer: */
CHECK_GL("pglBindFramebuffer");

/* After each pglFramebufferTexture2D / glFramebufferRenderbuffer: */
CHECK_GL("color attachment");
CHECK_GL("depth attachment");

/* After pglCheckFramebufferStatus: */
GLenum status = pglCheckFramebufferStatus(GL_FRAMEBUFFER);
if (status != GL_FRAMEBUFFER_COMPLETE) {
    LOG_ERROR("FBO incomplete: status=0x%04x", status);
    return 0;
}
LOG_INFO("FBO %dx%d complete (status=0x%04x)", w, h, status);
```

**NOTE [AR: SC-7]**: Modules 5 and 8 call `pglTexImage3D` with `GL_RGBA8` for texture arrays.
If `GL_RGBA8` is fundamentally broken in virglrenderer (not just for FBO color attachments),
consider changing these to `GL_RGBA` as well. This should only be done if Modules 5/8 still
fail after the `create_fbo()` fix.

### Step 2.5: Add `MESA_DEBUG=1` to `run-tests.sh` for this phase

**File**: `runelite-tablet/gl-tests/scripts/run-tests.sh`
**Agent**: `termux-shell-agent`

**Change**: Add temporarily to the proot env block:

```bash
# PHASE 2 DIAGNOSTIC: Enable Mesa GL error tracing.
# Remove after FBO diagnosis is complete (before Phase 3, not Phase 6) [AR: SC-8]
PROOT_CMD="$PROOT_CMD MESA_DEBUG=1"
```

This causes Mesa to print GL errors to stderr, which will show if `GL_DEPTH_CLAMP` or any
other query is generating `GL_INVALID_ENUM` that stalls the pipeline.

**[AR: SC-8]**: Remove `MESA_DEBUG=1` immediately after Phase 2 diagnosis is complete (before
Phase 3), NOT in Phase 6. Combined with the existing `MESA_LOG_LEVEL=debug` and
`LIBGL_DEBUG=verbose`, it produces very large mesa.log files that could fill tmp.

### Step 2.6: Insert `probe_fbo_capability()` call in `main()` run order

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Context**: Lines 1607–1617, the `if (run_all)` block currently runs modules 1–9 in order.

**Change**: Call the probe immediately after logging and function pointer resolution, then
reorder `--all` to run the probe first:

```c
/* After resolve_all_functions(): */
probe_fbo_capability();
LOG_INFO("FBO capability: %s", g_fbo_works == 1 ? "WORKS" : "BROKEN");

/* In if (run_all) block: */
run_module_1();
run_module_2();
run_module_3();
run_module_7();  /* FBO diagnostic first — before modules that depend on FBO */
run_module_4("a");
run_module_5();
run_module_6();
run_module_8();
run_module_9();
```

Rationale for moving Module 7 before Module 4: Module 7 is the FBO blit diagnostic. Running
it right after the FBO probe gives us maximum diagnostic signal before other modules consume
FBO state.

### Quality Gate G2

After deploying Phase 2 changes:

1. Pull and inspect `harness.log`:
   - Look for `FBO probe: WORKS` or `FBO probe: BROKEN`
   - Look for `GL error after color attachment` or similar
   - If `MESA_DEBUG=1` is active, look for `GL_INVALID_ENUM` in mesa.log

2. If `FBO probe: WORKS`: proceed to Phase 3 (add fallback for robustness, even though FBO works).

3. If `FBO probe: BROKEN` still: check `MESA_DEBUG=1` output for which GL call produced
   `GL_INVALID_ENUM`. Proceed to Phase 4 (alternative backends).

---

## Phase 3: Default Framebuffer Fallback

**Goal**: Make all 9 modules produce results regardless of FBO health. This is the robustness
layer — modules should degrade gracefully, not hard-fail with no output.

This phase can proceed in parallel with Phase 2 if desired, but logically depends on Phase 0
(harness must not crash first).

### Step 3.1: Fix `GLFW_VISIBLE` — critical for glReadPixels on X11

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Context**: Line 1589:
```c
glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); /* Offscreen */
```

**Change**:
```c
/* GLFW issue #2620: GLFW_VISIBLE=FALSE causes glReadPixels to return garbage on X11/Linux.
 * The window must be visible for correct framebuffer readback even when using FBOs,
 * because the default framebuffer backing is not allocated on invisible windows. */
glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
```

Also add depth bits and focus hints:
```c
glfwWindowHint(GLFW_DEPTH_BITS, 24);
glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);   /* [AR: SC-2] Don't steal focus from other windows */
glfwWindowHint(GLFW_DECORATED, GLFW_FALSE); /* [AR: SC-2] Minimize visual disruption */
```

### Step 3.2: Add fallback rendering paths to Modules 4, 5, 6, 8, 9

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

Each module currently fails hard when `create_fbo()` fails. Add a fallback path that renders
to the default framebuffer instead. The general pattern for each module:

```c
/* Existing code: */
GLuint fbo, colorTex, depthTex;
if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
    log_module_result(N, NULL, "Module Name", "FAIL", ..., "FBO creation failed");
    return;
}
pglBindFramebuffer(GL_FRAMEBUFFER, fbo);

/* Replace with: */
GLuint fbo = 0, colorTex = 0, depthTex = 0;
int use_fbo = (g_fbo_works == 1);
if (use_fbo) {
    if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
        LOG_ERROR("Module N: FBO creation failed despite probe success — falling back");
        use_fbo = 0;
    }
}
if (use_fbo) {
    pglBindFramebuffer(GL_FRAMEBUFFER, fbo);
} else {
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
    LOG_INFO("Module N: using DEFAULT FRAMEBUFFER (FBO unavailable)");
}
```

Apply this pattern to: Module 4, Module 5, Module 6, Module 8, Module 9.

For Module 9 (performance, half-resolution FBO), the half-res FBO attempt already has a
conditional. Change it to also use `g_fbo_works` and skip the half-res test entirely if
FBO is broken, rather than crashing.

FBO cleanup at end of each module: guard with `if (use_fbo)`:
```c
if (use_fbo) {
    destroy_fbo(fbo, colorTex, depthTex);
}
```

### Step 3.3: Redesign Module 7 as FBO diagnostic when FBO broken

**File**: `runelite-tablet/gl-tests/src/gl_test_harness.c`

**Current behavior**: Module 7 (FBO Blit) creates two FBOs, blits between them, compares CRC32.
When FBO is broken, it fails immediately with no useful diagnostic.

**Change**: Gate the existing Module 7 on `g_fbo_works`, and add a diagnostic branch:

```c
void run_module_7(void) {
    double t0 = get_time_ms();
    char detail[256] = "";  /* [AR: MF-7] Function-scoped to avoid stack corruption */

    if (g_fbo_works != 1) {
        /* FBO diagnostic mode: try each failure point in isolation and report */
        LOG_INFO("Module 7: FBO BROKEN — running diagnostic probe sequence");

        /* Diagnostic 1: Can we create an FBO at all? */
        if (!pglGenFramebuffers) {
            log_module_result(7, "diag", "FBO Blit Diagnostic",
                "SKIP", get_time_ms() - t0, "FBO extension not available");
            return;
        }

        /* Diagnostic 2: Does color-only FBO (no depth) complete? */
        GLuint fbo1, colorTex1;
        glGenTextures(1, &colorTex1);
        glBindTexture(GL_TEXTURE_2D, colorTex1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        pglGenFramebuffers(1, &fbo1);
        pglBindFramebuffer(GL_FRAMEBUFFER, fbo1);
        pglFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex1, 0);
        GLenum s1 = pglCheckFramebufferStatus(GL_FRAMEBUFFER);
        snprintf(detail, sizeof(detail), "color-only FBO status=0x%04x", s1);
        LOG_INFO("Module 7 diag: %s", detail);

        /* Diagnostic 3: Can we clear and read back from the color-only FBO? */
        if (s1 == GL_FRAMEBUFFER_COMPLETE) {
            glViewport(0, 0, 32, 32);
            glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glFinish();
            GLubyte p[4] = {0};
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
            snprintf(detail, sizeof(detail),
                "color-only FBO clear+read: R=%d G=%d B=%d A=%d (expect R=255,A=255)",
                p[0], p[1], p[2], p[3]);
            LOG_INFO("Module 7 diag: %s", detail);
        }

        pglDeleteFramebuffers(1, &fbo1);
        glDeleteTextures(1, &colorTex1);
        pglBindFramebuffer(GL_FRAMEBUFFER, 0);

        log_module_result(7, "diag", "FBO Blit Diagnostic",
            "FAIL", get_time_ms() - t0, detail);
        return;
    }

    /* --- Normal FBO Blit path (g_fbo_works == 1) --- */
    /* ... existing Module 7 code unchanged ... */
}
```

### Quality Gate G3

After deploying Phase 3:

```bash
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/run-tests.sh --quick'"
```

Expected: All 9 modules produce a result entry in `summary.json` — even if many are FAIL or
SKIP. No module should produce no output at all. The summary should show which modules worked
on the default framebuffer and which required FBO.

---

## Phase 4: Alternative GPU Backend Testing

**Goal**: If virpipe FBO remains broken after Phases 1 and 2, identify which backend + config
combination produces a working FBO. This determines whether RuneLite GPU plugin is viable and
documents the winning configuration.

This phase is run manually, one backend at a time, using targeted single-module runs.

### Step 4.1: Test without `--angle-gl` (native GLES)

**Root cause being tested**: Cause 5 (ANGLE threading race — EGL context on wrong thread
causes all GL calls to be discarded).

```bash
# In run-tests.sh, temporarily change line 157:
# Old: env -u LD_LIBRARY_PATH virgl_test_server_android --angle-gl ...
# New: env -u LD_LIBRARY_PATH virgl_test_server_android ...
```

Run `--module 4` and inspect FBO probe output. If FBO works without `--angle-gl`, the ANGLE
threading race is the root cause.

### Step 4.2: Test `GALLIUM_DRIVER=zink`

**Rationale**: Zink translates OpenGL to Vulkan. Dimensity 9300+ supports Vulkan 1.3. If the
device Vulkan driver handles FBO correctly, this provides a working path.

```bash
# Temporary env change in run-tests.sh:
# Replace: PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=virpipe"
# With:    PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=zink"
# And remove VTEST_SOCKET_NAME (not needed for Zink)
# Also remove MESA_GL_VERSION_OVERRIDE / MESA_GLSL_VERSION_OVERRIDE (Zink handles natively)
```

Note: Zink requires Vulkan ICD loader inside proot. May need:
```bash
apt-get install -y mesa-vulkan-drivers vulkan-tools
```

Run `--module 4` and check FBO probe. Also check `--module 9` for performance (Zink may be
slower than virpipe).

### Step 4.3: Test `llvmpipe` as software baseline

**Rationale**: llvmpipe is pure software Mesa. If it works, we confirm the harness and FBO
code are correct and the bug is in virpipe/ANGLE specifically.

```bash
# Temporary env change in run-tests.sh:
# Replace: PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=virpipe"
# With:    PROOT_CMD="$PROOT_CMD GALLIUM_DRIVER=llvmpipe"
# And remove VTEST_SOCKET_NAME
```

If llvmpipe FBO works: bug is definitely in virpipe/ANGLE, not the harness. This is
expected — document it as the baseline proof.

### Step 4.4: Document winning backend configuration

After testing, record the result in `harness.log` or a separate `backend-comparison.txt`:

```
Backend          | FBO Works | Notes
virpipe+angle-gl | ?         | Current default
virpipe (no ANGLE)| ?        | Test 4.1
zink             | ?         | Test 4.2
llvmpipe         | ?         | Baseline (always works, too slow for RuneLite)
```

### Quality Gate G4

At least one configuration produces:
- FBO probe: WORKS
- Module 4 PNG: non-black, correct blue-over-red pixel
- Module 7: PASS (CRC32 blit comparison succeeds)

If only llvmpipe works, document this as "virpipe FBO fundamentally broken on this device/Mesa
version" and escalate to upstream virglrenderer.

---

## Phase 5: Apply Winning Configuration to `launch-runelite.sh`

**Goal**: Update the RuneLite launch script with the environment variables that produced a
working FBO. This is the gate for RuneLite GPU plugin functionality.

**Depends on**: Phase 4 (or Phase 1/2 if virpipe FBO was fixed earlier).

### Step 5.1: Update virpipe env vars in `launch-runelite.sh`

**File**: `runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh`
**Agent**: `termux-shell-agent`

**Context**: Current virpipe block (lines ~345–367):

```bash
export GALLIUM_DRIVER=virpipe
...
export MESA_GLX_ALPHA_BITS=0
export MESA_NO_ERROR=1
...
export MESA_GL_VERSION_OVERRIDE="$OVERRIDE"   # $OVERRIDE = "4.5COMPAT"
export MESA_GLSL_VERSION_OVERRIDE=330
```

**Change if virpipe+EXTENSION_OVERRIDE fix worked (Phase 1)**:

```bash
export GALLIUM_DRIVER=virpipe
export VTEST_SOCKET_NAME=/tmp/.virgl_test
export MESA_GLX_ALPHA_BITS=0
export MESA_NO_ERROR=1
# GL_DEPTH_CLAMP fix: Mesa 4.5COMPAT auto-enables on 4.5COMPAT but GLES host
# returns GL_INVALID_ENUM, causing virglrenderer to discard all subsequent draws.
export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
export MESA_GLSL_VERSION_OVERRIDE=430
```

**Change if Zink worked better (Phase 4)**:

```bash
export GALLIUM_DRIVER=zink
export MESA_NO_ERROR=1
export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
export MESA_GLSL_VERSION_OVERRIDE=430
```

**Change if no-ANGLE virpipe worked (Phase 4.1)**:
- Remove `--angle-gl` from the `virgl_test_server_android` launch line in `launch-runelite.sh`.

### Step 5.2: Verify RuneLite GPU plugin renders

After deploying `launch-runelite.sh`:

1. Kill any running RuneLite session
2. Start RuneLite via the normal flow
3. Enable the GPU plugin in RuneLite settings
4. Navigate to a scene with geometry (e.g. Lumbridge)
5. Take a screenshot via `adb exec-out screencap -p > runelite-gpu-test.png`
6. Inspect: scene should show rendered geometry, not a black screen

**Gate**: RuneLite GPU plugin produces a visible scene with correct geometry and textures.

---

## Phase 6: Deploy and Full Verify

**Goal**: Run the complete test suite and document the final state.

### Step 6.1: Redeploy all changes

```bash
# From Windows host:
cd /c/Users/rseba/Projects/Tablite/runelite-tablet/gl-tests
./scripts/deploy.sh

# Rebuild harness (C changes in Phases 0, 2, 3):
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/build.sh'"
```

### Step 6.2: Run full `--quick` suite

```bash
adb shell "run-as com.termux bash -lc '~/gl-tests/scripts/run-tests.sh --quick'"
```

### Step 6.3: Pull and inspect all results

```bash
adb shell "run-as com.termux bash -lc 'cat \$PREFIX/tmp/gl-results-latest.tar.gz'" > /tmp/gl-results-$(date +%Y%m%d).tar.gz
tar xzf /tmp/gl-results-$(date +%Y%m%d).tar.gz -C /tmp/gl-results-final/
```

Check:
- `summary.json` — all 9 modules have entries, no missing modules
- `harness.log` — FBO probe result, no SIGSEGV, all modules logged
- `gl-caps.json` — GL version, renderer, extension list populated
- Module 4 PNG — non-black, blue-over-red depth test visible
- Module 7 — PASS (CRC32 blit comparison)
- `mesa.log` — no `GL_INVALID_ENUM` chains (if MESA_DEBUG=1 still set, remove it for prod)

### Quality Gate G5 (Final)

| Criterion | Expected |
|-----------|----------|
| `--module 1` runs to completion | PASS |
| `--all` runs to completion (no SIGSEGV) | PASS |
| FBO probe result | WORKS |
| Module 4 (Reversed-Z Depth) | PASS |
| Module 7 (FBO Blit) | PASS |
| All modules have result entries in summary.json | PASS |
| RuneLite GPU plugin produces visible scene | PASS |

---

## Adversarial Review Fixes Incorporated

All 7 MUST-FIX and relevant SHOULD-CONSIDER items from the adversarial review are addressed:

| Review Finding | Addressed In |
|---|---|
| MF-1: Renderbuffer function pointers not resolved | Phase 2, Step 2.3 (5 typedefs + statics + resolution) |
| MF-2: `*depthTex = depthRbo` semantic bomb | Phase 2, Step 2.4 (new `destroy_fbo` with `depth_is_rbo` flag) |
| MF-3: 4.3COMPAT may break RuneLite | Phase 1, Step 1.1 notes (validation step + env var consistency) |
| MF-4: `fflush(g_log_file)` NULL guard | Phase 0, Step 0.2 (`if (g_log_file)` guard) |
| MF-5: `set -e` exception | Rollback/exception section (documented proot exception) |
| MF-6: MESA_EXTENSION_OVERRIDE propagation | Phase 1, Step 1.1a (verification step) |
| MF-7: `diag1` variable scope | Phase 3, Step 3.3 (function-scoped `detail[256]`) |
| SC-1: FBO probe may crash virgl server | Phase 2, Step 2.1 note (fallback to `g_fbo_works = 0`) |
| SC-2: GLFW_VISIBLE focus issues | Phase 3, Step 3.1 (GLFW_FOCUSED FALSE + DECORATED FALSE) |
| SC-3: No rollback strategy | Rollback Strategy section |
| SC-4: Env var propagation divergence | Phase 1, Step 1.1a + SC-4 note |
| SC-5: GL_MAX_CLIP_DISTANCES unsafe | Phase 0, Step 0.1 (added to removal list) |
| SC-6: G0 uses --quick not --module 1 | Phase 0, G0 (two-step: --module 1 first, then --all) |
| SC-7: GL_RGBA8 in Modules 5/8 texture arrays | Phase 2 note |
| SC-8: MESA_DEBUG=1 removed too late | Phase 2, Step 2.5 (remove before Phase 3, not Phase 6) |
| SC-9: Phase 2 must deploy as single unit | Phase 2, Step 2.4 note |
| NP-2: Dead `depth32f` parameter | Phase 2, Step 2.4 (removed from signature) |
| NP-5: Checklist M4 vs M4a | Updated checklist below |
| CC-1: CRLF protection | CRLF Protection section |

---

## Implementation Checklist

### Phase 0 (SIGSEGV fix) — C source
- [ ] `gl_test_log.h`: Remove `GL_MAX_VARYING_FLOATS`, `GL_MAX_CLIP_DISTANCES`, `GL_DEPTH_BITS`, `GL_STENCIL_BITS`, `GL_SAMPLE_BUFFERS`, `GL_SAMPLES`, `GL_SUBPIXEL_BITS` from `int_queries[]`
- [ ] `gl_test_log.h`: Add `fflush(stdout)` + `if (g_log_file) fflush(g_log_file)` before each `glGetIntegerv` in loop
- [ ] `gl_test_log.h`: Add `fflush(stdout)` before `glGetIntegerv(GL_NUM_EXTENSIONS, &num_ext)`
- [ ] `gl_test_harness.c`: Version-gate `glGetStringi` with GL version check + index 0 probe
- [ ] Rebuild + deploy + gate G0 (--module 1 first, then --all)

### Phase 1 (env fixes) — shell only
- [ ] `run-tests.sh`: Add `MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp`
- [ ] `run-tests.sh`: Change `MESA_GL_VERSION_OVERRIDE` to `4.3COMPAT`
- [ ] `run-tests.sh`: Change `MESA_GLSL_VERSION_OVERRIDE` to `430`
- [ ] Deploy run-tests.sh (no recompile needed)
- [ ] Verify: check gl-caps.json for absence of `GL_ARB_depth_clamp` [MF-6]
- [ ] Gate G1: check FBO probe result

### Phase 2 (FBO code fixes) — C source + shell — DEPLOY AS SINGLE UNIT [SC-9]
- [ ] `gl_test_harness.c`: Add 5 renderbuffer typedefs, statics, and resolution lines [MF-1]
- [ ] `gl_test_harness.c`: Add `g_fbo_works` global and `probe_fbo_capability()` function
- [ ] `gl_test_harness.c`: Change `GL_RGBA8` to `GL_RGBA` in `create_fbo()`
- [ ] `gl_test_harness.c`: Replace depth texture with renderbuffer in `create_fbo()` (new signature) [MF-2]
- [ ] `gl_test_harness.c`: Update `destroy_fbo()` to handle renderbuffer vs texture [MF-2]
- [ ] `gl_test_harness.c`: Update ALL `create_fbo`/`destroy_fbo` call sites
- [ ] `gl_test_harness.c`: Add `CHECK_GL()` after each FBO setup step
- [ ] `gl_test_harness.c`: Add `probe_fbo_capability()` call in `main()` after `resolve_all_functions()`
- [ ] `gl_test_harness.c`: Reorder `--all` module sequence (M1, M2, M3, M7, M4a, M5, M6, M8, M9)
- [ ] `run-tests.sh`: Add `MESA_DEBUG=1` (temporary — remove before Phase 3)
- [ ] Rebuild + deploy + gate G2

### Phase 3 (fallback) — C source
- [ ] `run-tests.sh`: Remove `MESA_DEBUG=1` [SC-8]
- [ ] `gl_test_harness.c`: Change `GLFW_VISIBLE` to `GLFW_TRUE`
- [ ] `gl_test_harness.c`: Add `GLFW_DEPTH_BITS 24`, `GLFW_FOCUSED FALSE`, `GLFW_DECORATED FALSE` hints
- [ ] `gl_test_harness.c`: Add `use_fbo` fallback to Module 4
- [ ] `gl_test_harness.c`: Add `use_fbo` fallback to Module 5
- [ ] `gl_test_harness.c`: Add `use_fbo` fallback to Module 6 (sequential render + CRC compare)
- [ ] `gl_test_harness.c`: Add `use_fbo` fallback to Module 8
- [ ] `gl_test_harness.c`: Add `use_fbo` fallback to Module 9 (skip half-res when FBO broken)
- [ ] `gl_test_harness.c`: Redesign Module 7 with diagnostic branch for broken FBO [MF-7]
- [ ] Rebuild + deploy + gate G3

### Phase 4 (alt backends) — manual testing
- [ ] Test virpipe without `--angle-gl` — document result
- [ ] Test `GALLIUM_DRIVER=zink` — document result
- [ ] Test `GALLIUM_DRIVER=llvmpipe` — document as baseline
- [ ] Record winning config in backend-comparison.txt
- [ ] Gate G4

### Phase 5 (apply to RuneLite) — shell
- [ ] Verify env vars match between `run-tests.sh` and planned `launch-runelite.sh` [SC-4]
- [ ] `launch-runelite.sh`: Apply winning env config (same as run-tests.sh) [MF-3]
- [ ] Deploy `launch-runelite.sh`
- [ ] Start RuneLite, check GPU plugin init log for version-gated failures [MF-3]
- [ ] Enable GPU plugin, take screenshot
- [ ] Gate: visible scene

### Phase 6 (final verify)
- [ ] Full redeploy and rebuild
- [ ] Run `--quick`, pull results, inspect all log files
- [ ] Verify gl-caps.json has no `GL_ARB_depth_clamp` in extension list
- [ ] Gate G5

---

## Key References

- Termux issue #15832: `GL_DEPTH_CLAMP` + virpipe on Mali devices
- virglrenderer GitLab issue #221: BGRA emulation issues on GLES hosts
- GLFW issue #2620: `GLFW_VISIBLE=FALSE` causes `glReadPixels` garbage on X11
- Mesa DRI wiki: `glXGetProcAddressARB` always returns non-NULL
- MEMORY.md: "Must set BOTH MESA_GL_VERSION_OVERRIDE AND MESA_GLSL_VERSION_OVERRIDE"
- MEMORY.md: "Confirmed renderer: virgl (ANGLE (ARM, Mali-G720-Immortalis MC12, OpenGL ES 3.2))"
