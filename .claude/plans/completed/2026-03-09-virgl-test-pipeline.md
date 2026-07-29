# Plan: VirGL Rendering Test Pipeline

**Date**: 2026-03-09
**Spec**: specs/2026-03-09-virgl-test-pipeline-spec.md
**Review**: adversarial_reviews/2026-03-09-virgl-test-pipeline/review.md
**Status**: APPROVED

## Blast Radius
- **Direct**: 14 new files (all in `runelite-tablet/gl-tests/`)
- **Modified**: 1 file (`.gitattributes` — 2 lines added)
- **Dependent**: 0 files
- **Test**: 0 files (the pipeline IS the test infra)
- **Cleanup**: 0 files

## Adversarial Review Fixes Incorporated

All 6 MUST-FIX and 13 SHOULD-CONSIDER items from the review are addressed in this plan:

| Review Finding | Addressed In |
|---|---|
| MF1: adb push cross-UID | Phase 1, Step 1.1 |
| MF2: run-as no Termux PATH | Phase 1, Step 1.1 (all scripts self-bootstrap) |
| MF3: dlopen vs LD_PRELOAD | Phase 3, Step 3.2 (sub-process with LD_PRELOAD) |
| MF4: .gitattributes C/H | Phase 1, Step 1.2 |
| MF5: DEBIAN_FRONTEND | Phase 2, Step 2.1 |
| MF6: results path mismatch | Phase 1, Step 1.1 (copy results to shared-tmp) |
| SC1: unset MESA_NO_ERROR | Phase 4, Step 4.1 |
| SC2: set -euo strategy | Phase 1, Step 1.1 (deploy.sh: -euo; proot scripts: -uo) |
| SC3: apt-get update || true | Phase 2, Step 2.1 |
| SC4: VirGL server conflict | Phase 4, Step 4.1 (pre-flight check) |
| SC5: symlink → LATEST file | Phase 4, Step 4.1 |
| SC6: env var allowlist | Phase 2, Step 2.2 (gl_test_log.h) |
| SC7: setprop cleanup | Phase 4, Step 4.1 (trap handler) |
| SC8: disk space check | Phase 5, Step 5.1 |
| SC9: stb version/license | Phase 2, Step 2.3 |
| SC10: idempotent install | Phase 2, Step 2.1 + Phase 5, Step 5.1 |
| SC11: absolute dlopen paths | Phase 3, Step 3.1 |
| SC12: shader capture path | Phase 4, Step 4.1 |
| SC13: df -k in Termux | Phase 4, Step 4.1 |

## Agent Routing

| File Pattern | Agent |
|---|---|
| `gl-tests/scripts/*.sh` | `termux-shell-agent` |
| `gl-tests/src/*.c`, `gl-tests/src/*.h` | Main session |
| `.gitattributes` | Main session |
| `README.md` | Main session |
| Review (any phase) | `code-review-agent` |

## Phase 1: Project Scaffolding & Deployment
*Goal: Create directory structure, deploy.sh that works around cross-UID constraints, .gitattributes update.*

### Step 1.1: Create deploy.sh with cross-UID fix
- **Files**: `runelite-tablet/gl-tests/scripts/deploy.sh`
- **Agent**: `termux-shell-agent`
- **Depends on**: none
- **Changes**: Write deploy script that:
  - Uses `set -euo pipefail` (no proot involved)
  - Detects device via `adb devices` (support `-s SERIAL` for multi-device)
  - Pushes files to `/data/local/tmp/gl-tests/` (world-writable staging)
  - Copies to Termux home via `adb shell run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc 'cp -r /data/local/tmp/gl-tests ~/gl-tests/'`
  - Sets `chmod +x` on all scripts via `run-as`
  - Strips `\r` from all files defensively (`sed -i 's/\r$//'`)
  - Cleans up staging dir after copy
  - All scripts self-bootstrap Termux env at top: `export PREFIX=/data/data/com.termux/files/usr; export HOME=/data/data/com.termux/files/home; export PATH=$PREFIX/bin:$PATH; export LD_LIBRARY_PATH=$PREFIX/lib`
- **Review fixes**: MF1 (cross-UID), MF2 (Termux PATH), MF6 (results use `$PREFIX/tmp`), SC2 (set -euo for non-proot)

### Step 1.2: Update root .gitattributes
- **Files**: `.gitattributes` (repo root)
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Add two lines:
  ```
  gl-tests/src/*.c text eol=lf
  gl-tests/src/*.h text eol=lf
  ```
- **Review fix**: MF4

### Step 1.3: Create README.md
- **Files**: `runelite-tablet/gl-tests/README.md`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Document deployment, execution, results pulling. Include:
  - Prerequisites (Termux, proot-distro, Termux:X11 installed)
  - Deploy: `./scripts/deploy.sh`
  - Run: `adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc '~/gl-tests/scripts/run-tests.sh --quick'"`
  - Pull results: `adb shell "run-as com.termux /data/data/com.termux/files/usr/bin/bash -lc 'cat \$PREFIX/tmp/gl-results-latest.tar.gz'" > gl-results.tar.gz`
  - Warning: results may contain system-specific info, do not share without review
  - Note: cannot run concurrently with RuneLite

## Phase 2: C Source Files (Logging, Shaders, Dependencies)
*Goal: Write header files and utility code that other C files depend on.*

### Step 2.1: Create install-deps.sh
- **Files**: `runelite-tablet/gl-tests/scripts/install-deps.sh`
- **Agent**: `termux-shell-agent`
- **Depends on**: Step 1.1
- **Changes**: Write dependency installer that:
  - Self-bootstraps Termux PATH
  - Uses `set -uo pipefail` (no `-e` — proot exit codes unreliable)
  - Checks if deps already installed (`which gcc && dpkg -l libglfw3-dev` inside proot)
  - If missing: `proot-distro login ubuntu --shared-tmp -- env DEBIAN_FRONTEND=noninteractive bash -c '...'`
  - Uses `apt-get update -qq || true` (gpgv issue)
  - Installs: `gcc`, `g++`, `libglfw3-dev`, `libgl-dev`, `libglu1-mesa-dev`, `pkg-config`
  - Writes marker file on success: `~/gl-tests/.deps-installed`
  - Idempotent: skip if marker exists and deps verify
- **Review fixes**: MF5 (DEBIAN_FRONTEND), SC3 (apt-get || true), SC10 (idempotent)

### Step 2.2: Create gl_test_log.h
- **Files**: `runelite-tablet/gl-tests/src/gl_test_log.h`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Write logging header with:
  - `LOG_INFO(fmt, ...)`, `LOG_ERROR(fmt, ...)`, `LOG_GL(func_name)` macros
  - `CHECK_GL(func_name)` macro: calls `glGetError()` after every GL call, logs non-zero with hex code
  - `log_init(results_dir)`: opens log files, writes header with timestamp
  - `log_environment()`: dumps env vars using **allowlist** (only GL/Mesa/VirGL/GALLIUM/DISPLAY/PROOT vars)
  - `log_gl_caps()`: dumps all `glGetString`, `glGetIntegerv`, extensions to JSON
  - `log_function_pointer(name, ptr)`: logs each probed function pointer
  - JSON output helpers for `summary.json`, `gl-caps.json`, `function-pointers.json`
  - Signal handler registration for SIGSEGV/SIGBUS crash dumps
  - `clock_gettime(CLOCK_MONOTONIC)` timing helpers with nanosecond precision
  - CRC32 checksum function for framebuffer validation
- **Review fix**: SC6 (env var allowlist)

### Step 2.3: Vendor stb_image_write.h
- **Files**: `runelite-tablet/gl-tests/src/stb_image_write.h`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Download from `https://github.com/nothings/stb/blob/master/stb_image_write.h`. Add header comment with:
  - Source URL and exact commit hash
  - License: public domain / MIT dual-licensed
  - Date vendored
- **Review fix**: SC9 (version/license)

### Step 2.4: Create test_shaders.h
- **Files**: `runelite-tablet/gl-tests/src/test_shaders.h`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: GLSL shader source strings as C string constants:
  - `SHADER_VERT_BASIC` — `#version 330`, `layout(location=0) in vec3`, `layout(std140) uniform`
  - `SHADER_FRAG_BASIC` — basic color output
  - `SHADER_FRAG_TEXARRAY` — `sampler2DArray` + `textureLod()`
  - `SHADER_VERT_NOPERSP` — `noperspective centroid out`
  - `SHADER_FRAG_NOPERSP` — `noperspective centroid in`
  - `SHADER_VERT_SMOOTH` — `smooth out` (comparison reference)
  - `SHADER_FRAG_COLORBLIND` — `inverse(mat3)` test
  - `SHADER_FRAG_TEXTURESIZE` — `textureSize()` test
  - `SHADER_VERT_SCENE` / `SHADER_FRAG_SCENE` — full scene emulation (Module 8)

## Phase 3: LD_PRELOAD Shims
*Goal: Write both depth fix shims with proper logging and absolute paths.*

### Step 3.1: Create fix_inject_clipcontrol.c (Shim A)
- **Files**: `runelite-tablet/gl-tests/src/fix_inject_clipcontrol.c`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Write Shim A:
  - Intercepts `glClearDepth(GLdouble depth)`
  - On `depth == 0.0` (first time only, atomic flag):
    - Gets `glClipControl` via `glXGetProcAddressARB("glClipControl")`
    - If non-NULL: calls `glClipControl(0x8CA1, 0x935F)` (GL_LOWER_LEFT, GL_ZERO_TO_ONE)
    - Logs result to stderr: `[SHIM-A] glClipControl injected, ptr=%p, glGetError=0x%04x`
    - If NULL: `[SHIM-A] FATAL: glClipControl function pointer is NULL`
  - Always passes through to real `glClearDepth` via `dlsym(RTLD_NEXT)`
  - Thread-safe: `_Atomic int initialized = 0`
  - Compiled as: `gcc -shared -fPIC -o fix_inject_clipcontrol.so fix_inject_clipcontrol.c -ldl`
  - `chmod 755` after compilation
- **Review fix**: SC11 (absolute paths in build.sh, chmod 755)

### Step 3.2: Create fix_flip_depth.c (Shim B)
- **Files**: `runelite-tablet/gl-tests/src/fix_flip_depth.c`
- **Agent**: Main session
- **Depends on**: none
- **Changes**: Write Shim B:
  - Intercepts `glDepthFunc(GLenum func)`: GL_GREATER→GL_LESS, GL_GEQUAL→GL_LEQUAL
  - Intercepts `glClearDepth(GLdouble depth)`: 0.0→1.0
  - Logs every interception: `[SHIM-B] glDepthFunc(GL_GREATER → GL_LESS)`
  - `dlsym(RTLD_NEXT)` for originals
  - Thread-safe, same pattern as Shim A
  - Same compilation and chmod
- **Note**: Module 4 tests shims via **sub-processes** with `LD_PRELOAD=<absolute_path>/shim.so`, NOT dlopen(). This is the actual deployment mechanism matching RuneLite use.
- **Review fix**: MF3 (sub-process LD_PRELOAD instead of dlopen)

## Phase 4: Master Orchestrator & Instrumentation
*Goal: Write run-tests.sh with full pipeline instrumentation, env var setup, result collection.*

### Step 4.1: Create run-tests.sh
- **Files**: `runelite-tablet/gl-tests/scripts/run-tests.sh`
- **Agent**: `termux-shell-agent`
- **Depends on**: Steps 1.1, 2.1
- **Changes**: Master orchestrator that:
  - Self-bootstraps Termux PATH
  - Uses `set -uo pipefail` (no `-e`)
  - Accepts `--quick` (Tier 1+2) or `--full` (Tier 1+2+3)
  - **Pre-flight checks**:
    - VirGL server conflict: `pgrep -f virgl_test_server` → error "Kill RuneLite session first"
    - X11 available: check Termux:X11 running, start if not
    - Disk space: `df -k` in proot rootfs, warn if < 500MB (< 2GB for --full)
  - **Start services**:
    - Kill stale processes (same cleanup as launch-runelite.sh: both `termux-x11` AND `com.termux.x11.Loader`)
    - Start Termux:X11 if needed
    - Start `virgl_test_server_android --angle-gl`
    - Wait for socket (`[ -S "$PREFIX/tmp/.virgl_test" ]`, 30 retries)
  - **Set debug properties** (with cleanup trap):
    - Save originals: `ORIG_ANGLE_MARKERS=$(getprop debug.angle.markers)`
    - Set: `setprop debug.angle.markers 1`, `setprop debug.vulkan.layers VK_LAYER_KHRONOS_validation`
    - Register cleanup trap: `trap cleanup EXIT INT TERM`
    - Cleanup restores all original values
  - **Create results directory**: `$RESULTS_DIR=~/gl-tests/results/run-$(date +%Y%m%d-%H%M%S)`
  - **Enter proot** with `--shared-tmp`:
    - `unset MESA_NO_ERROR` (critical: opposite of production)
    - Set ALL instrumentation env vars:
      - `GALLIUM_TRACE=$RESULTS_DIR/gallium.xml`
      - `MESA_LOG_FILE=$RESULTS_DIR/mesa.log`, `MESA_LOG_LEVEL=debug`
      - `LIBGL_DEBUG=verbose` → `$RESULTS_DIR/libgl-debug.log`
      - `ST_DEBUG=tgsi` → `$RESULTS_DIR/tgsi-shaders.log`
      - `TGSI_PRINT_SANITY=1`
      - `MESA_SHADER_CAPTURE_PATH=$RESULTS_DIR/shaders/` (inside results dir, mkdir -m 700)
      - `GALLIUM_PRINT_OPTIONS=1`, `GALLIUM_DUMP_CPU=1`
      - `GALLIUM_DRIVER=virpipe`, `VTEST_SOCKET_NAME=/tmp/.virgl_test`
      - `MESA_GLX_ALPHA_BITS=0`
      - `MESA_GL_VERSION_OVERRIDE=4.5COMPAT`, `MESA_GLSL_VERSION_OVERRIDE=330`
      - `DISPLAY=:0`
    - VirGL server debug: `VIRGL_DEBUG=tgsi,shader,shader_compile,stream,resource,query` (set before server start, stderr → `$RESULTS_DIR/virgl-server.log`)
    - Capture proot diagnostics: `uname -a`, `cat /etc/os-release`, `id`, `free -m`, `nproc`, `df -h /`, `df -h /tmp`, socket checks
    - Capture X11 info: `xdpyinfo > $RESULTS_DIR/xdpyinfo.txt`
  - **Start background monitors**:
    - `adb logcat -s ANGLE` → `$RESULTS_DIR/angle.log` (background)
    - `adb logcat -s mali` → `$RESULTS_DIR/mali.log` (background)
    - Vulkan validation → `$RESULTS_DIR/vulkan-validation.log` (background)
    - Perf monitor: CPU/memory/thermal every 100ms → `$RESULTS_DIR/perf-monitor.csv`
    - VirGL watchdog: check server PID every second
  - **Run Tier 1**: `./gl_test_harness --results-dir $RESULTS_DIR --all`
  - **Run Tier 2** (Module 4 shim sub-tests):
    - `./gl_test_harness --results-dir $RESULTS_DIR --module 4a` (no shim)
    - `LD_PRELOAD=$BUILD_DIR/fix_inject_clipcontrol.so ./gl_test_harness --results-dir $RESULTS_DIR --module 4b`
    - `LD_PRELOAD=$BUILD_DIR/fix_flip_depth.so ./gl_test_harness --results-dir $RESULTS_DIR --module 4c`
    - (uses absolute paths for LD_PRELOAD)
  - If `--full`: **Run Tier 3** (Piglit, see Phase 5)
  - **Copy results** from proot to Termux shared-tmp:
    - Inside proot: `tar czf /tmp/gl-results-latest.tar.gz -C $RESULTS_DIR .`
    - Write `LATEST` file (not symlink): `echo "$RESULTS_DIR" > ~/gl-tests/results/LATEST`
  - **Generate SUMMARY.md** from summary.json
  - Stop background monitors, cleanup
  - Use `df -k` (not `df -m` or `df -h`) for any Termux-layer disk checks
- **Review fixes**: SC1 (unset MESA_NO_ERROR), SC4 (VirGL conflict), SC5 (LATEST file), SC7 (setprop cleanup), SC12 (shader capture path in results), SC13 (df -k)

### Step 4.2: Create build.sh
- **Files**: `runelite-tablet/gl-tests/scripts/build.sh`
- **Agent**: `termux-shell-agent`
- **Depends on**: Steps 2.1, 3.1, 3.2
- **Changes**: Compile harness + shims inside proot:
  - Self-bootstraps Termux PATH
  - Uses `set -uo pipefail`
  - Checks deps installed (marker file from install-deps.sh)
  - Enters proot:
    ```
    gcc -o gl_test_harness gl_test_harness.c -lGL -lGLU -lglfw -lX11 -lm -ldl -DSTB_IMAGE_WRITE_IMPLEMENTATION
    gcc -shared -fPIC -o fix_inject_clipcontrol.so fix_inject_clipcontrol.c -ldl
    gcc -shared -fPIC -o fix_flip_depth.so fix_flip_depth.c -ldl
    chmod 755 gl_test_harness fix_inject_clipcontrol.so fix_flip_depth.so
    ```
  - Writes marker file: `~/gl-tests/.build-complete`
  - Idempotent: skip if marker exists and binaries exist
  - Uses absolute paths for all source references

## Phase 5: Piglit Integration (Tier 3)
*Goal: install-piglit.sh for comprehensive GL 3.3 sweep.*

### Step 5.1: Create install-piglit.sh
- **Files**: `runelite-tablet/gl-tests/scripts/install-piglit.sh`
- **Agent**: `termux-shell-agent`
- **Depends on**: Step 2.1
- **Changes**: Build Piglit from source:
  - Self-bootstraps Termux PATH
  - Uses `set -uo pipefail`
  - Disk space check: `df -k` inside proot, require 2GB free
  - Idempotent: skip if `~/piglit/piglit` binary exists
  - Installs build deps: `cmake`, `python3-numpy`, `libwaffle-dev`, `freeglut3-dev`, `python3-mako`
  - All apt-get: `DEBIAN_FRONTEND=noninteractive`, `apt-get update -qq || true`
  - Clone + build: `git clone --depth 1 https://gitlab.freedesktop.org/mesa/piglit.git ~/piglit-src && cd ~/piglit-src && cmake -B build -DPIGLIT_BUILD_DMA_BUF_TESTS=OFF -DPIGLIT_BUILD_CL_TESTS=OFF && cmake --build build -j$(nproc)`
  - Writes marker file on success
- **Review fixes**: SC8 (disk space), SC10 (idempotent), MF5 (DEBIAN_FRONTEND), SC3 (apt-get || true)

## Phase 6: Test Harness (C Source)
*Goal: gl_test_harness.c with all 9 modules.*

### Step 6.1: Create gl_test_harness.c — Modules 1-3 (Capability + Shader + ClipControl)
- **Files**: `runelite-tablet/gl-tests/src/gl_test_harness.c`
- **Agent**: Main session
- **Depends on**: Steps 2.2, 2.3, 2.4
- **Changes**: Write main harness with:
  - `main()`: parse args (`--results-dir`, `--module N`, `--all`)
  - GLFW window + GL context creation (request GL 3.3 compat, set `MESA_GLX_ALPHA_BITS=0` hint)
  - Register signal handlers (from gl_test_log.h)
  - Module 1: GL Capability Dump — `glGetString` x4, `glGetIntegerv` x30, full extension list, function pointer probes (glClipControl, glBlitFramebuffer, glTexImage3D, etc. — ~30 functions), write gl-caps.json + function-pointers.json
  - Module 2: GLSL 330 Shader Compilation — compile each shader from test_shaders.h separately, log compile status + info log + link status
  - Module 3: glClipControl Probe — 3a (pointer check), 3b (call + glGetError), 3c (state query GL_CLIP_DEPTH_MODE + GL_CLIP_ORIGIN)
  - Every GL call wrapped with `CHECK_GL()` macro
  - JSON results appended to summary.json

### Step 6.2: Add Modules 4-7 (Depth, Texture, Interpolation, FBO)
- **Files**: `runelite-tablet/gl-tests/src/gl_test_harness.c` (extend)
- **Agent**: Main session
- **Depends on**: Step 6.1
- **Changes**: Add rendering test modules:
  - Module 4: Reversed-Z Depth — render to FBO (not default FB), two overlapping triangles (red at z=0.3, blue at z=0.7), reversed-Z setup (glDepthFunc(GL_GREATER), glClearDepth(0), GL_DEPTH_COMPONENT32F). Three sub-modes (4a/4b/4c) selected by `--module` arg. Capture color FB (PNG) + depth FB (min/max/mean). Validate blue-over-red pixel check.
  - Module 5: sampler2DArray — create 3-layer texture (R/G/B), render 3 quads, validate pixel colors
  - Module 6: noperspective — render with `noperspective`, render with `smooth`, compare FBOs (should differ)
  - Module 7: FBO Blit — render to FBO A, blit to FBO B, compare CRC32

### Step 6.3: Add Modules 8-9 (Scene Emulation + Performance)
- **Files**: `runelite-tablet/gl-tests/src/gl_test_harness.c` (extend)
- **Agent**: Main session
- **Depends on**: Step 6.2
- **Changes**: Add integration + perf modules:
  - Module 8: RuneLite Scene Emulation — reversed-Z + texarray + noperspective + FBO blit + UBO. Render textured cubes at varying depths with fog. Uses whichever shim was identified as winner.
  - Module 9: Performance Baseline — render Module 8 scene 300 times, collect frame time stats (min/max/mean/p95/p99), glReadPixels latency, GL calls per frame. Re-run at half resolution. Write timing.json.

## Phase 7: Verification & First Run
*Goal: Deploy, build, run --quick, validate results.*

### Step 7.1: Deploy and build on device
- **Agent**: Main session (adb commands)
- **Depends on**: All prior phases
- **Changes**: Run the pipeline:
  1. `./scripts/deploy.sh` — push to device
  2. Via adb: `install-deps.sh` — install gcc/GLFW
  3. Via adb: `build.sh` — compile harness + shims
  4. Verify binaries exist

### Step 7.2: Run --quick and validate
- **Agent**: Main session
- **Depends on**: Step 7.1
- **Changes**: Execute:
  1. `run-tests.sh --quick`
  2. Pull results tarball
  3. Verify SUMMARY.md exists and all 9 modules report results
  4. Verify all log files present (no silent gaps)
  5. Check shim comparison — which fix works?

### Step 7.3: Code review
- **Agent**: `code-review-agent`
- **Depends on**: Step 7.2
- **Changes**: Review all C source and shell scripts for:
  - Shell constraint compliance
  - C code quality (memory leaks, error handling, signal safety)
  - Logging completeness
  - Security (env var allowlist, file permissions)

## Quality Gates

| Gate | Phase | Criteria |
|------|-------|----------|
| G1 | After Phase 1 | deploy.sh successfully pushes files to Termux home via staging |
| G2 | After Phase 2 | install-deps.sh is idempotent, gcc available in proot |
| G3 | After Phase 4 | build.sh compiles harness + shims without errors |
| G4 | After Phase 6 | `--quick` produces SUMMARY.md with all modules reporting |
| G5 | After Phase 7 | Code review passes, all adversarial review items verified |
