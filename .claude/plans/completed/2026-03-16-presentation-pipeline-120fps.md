# Plan: Presentation Pipeline 120 FPS Optimization

**Date**: 2026-03-16
**Spec**: specs/2026-03-16-presentation-pipeline-120fps-spec.md
**Status**: APPROVED

## Blast Radius
- **Direct**: 11 files (2 new, 9 modified)
- **Dependent**: 3 files
- **Test/Evidence**: 3 files
- **Cleanup**: 3 files

## Agent Routing

| File Pattern | Agent |
|-------------|-------|
| `third_party/**/lorie/*.c`, `third_party/**/lorie/*.h` | Main session (native C — no agent matches) |
| `third_party/**/cpp/CMakeLists.txt` | Main session |
| `**/presentation/hybrid/**/*.kt` | Main session |
| `**/termux/x11/**/*.kt`, `**/termux/x11/**/*.java` | Main session |
| `assets/scripts/**/*.sh` | `termux-shell-agent` |
| `scripts/**/*.ps1` | Main session |
| Review (each phase) | `code-review-agent` |
| Performance validation | `performance-agent` |

**Note**: All Phase 1-3 work is native C in vendored Xlorie code. No existing agent specializes in native C/NDK, so the main session handles these directly. Phase 4 is Kotlin but touches the hybrid host (not Termux scripts), so also main session.

---

## Phase 1: Fix Legacy Drawing Fallback (RGBA Flip Path)

**Goal**: Eliminate per-frame `glTexSubImage2D` upload on Mali by fixing the EGLImage format path.
**Expected FPS**: ~60-65 (from ~50 baseline)
**Depends on**: nothing

### Step 1.1: Instrument rendererTestCapabilities() diagnostic logging
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
- **Agent**: Main session
- **Changes**:
  - In `rendererTestCapabilities()` (lines 350-458), add `__android_log_print()` calls after each critical step:
    1. After `AHardwareBuffer_allocate()` — log success/failure and format used
    2. After `eglGetNativeClientBufferANDROID()` — log result
    3. After `eglCreateImageKHR()` — log success/failure and EGL error code
    4. After pixel readback verification — log expected vs actual pixel value
    5. At final decision point — log `flip` and `legacy_drawing` values
  - Use tag `"XlorieCaps"` for easy logcat filtering
- **Depends on**: none
- **Verification**: Build, install, launch. Check `adb logcat -s XlorieCaps` for the exact failure point.

### Step 1.2: Fix RGBA format selection for Mali
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
- **Agent**: Main session
- **Changes**:
  - In the `EGL_BAD_PARAMETER` retry path (around line 410):
    - Change `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM` (value 2, maps to `GL_RGB8`) to `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` (value 1, maps to `GL_RGBA8`)
    - Keep `flip = 1` (shader swizzle still needed for BGRA→RGBA)
  - In the pixel readback verification (lines 443-450):
    - Add an additional expected value for the RGBA path with `flip=1`
    - The test writes `0xAABBCCDD` as BGRA. With `R8G8B8A8_UNORM` + `flip=1`, the GPU may read it as RGBA directly (no swizzle at texture sample time, swizzle happens in shader). Log the actual readback value and accept both known-good orderings.
  - Add a final fallback: if `R8G8B8A8_UNORM` also fails EGLImage creation, log the error and fall to legacy (don't crash).
- **Depends on**: Step 1.1 (need diagnostic to confirm the exact failure)
- **Verification**: `adb logcat -s XlorieCaps` should show `flip=1, legacy_drawing=0`. No `"Forcing legacy drawing"` message.

### Step 1.3: Verify buffer.c handles RGBA format correctly
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/buffer.c`
- **Agent**: Main session
- **Changes**:
  - In `LorieBuffer_attachToGL()` (line 399-419): Verify the `eglCreateImageKHR` call works with `R8G8B8A8_UNORM` buffers. The function is format-agnostic (it uses whatever the AHB was allocated with), so this is likely a no-op change — just verify.
  - In `LorieBuffer_bindTexture()` (line 421-428): Verify the FD fallback path uses the correct GL format enum for RGBA (`GL_RGBA` not `GL_BGRA_EXT`).
  - In `LorieBuffer_convert()` (lines 200-260): If `LORIEBUFFER_AHARDWAREBUFFER` path allocates with hardcoded BGRA format, update to use `R8G8B8A8_UNORM` when `flip=1`.
- **Depends on**: Step 1.2
- **Verification**: No `glTexSubImage2D` calls in logcat during steady-state rendering. `XloriePerf` shows reduced `avg_frame_ms`.

### Step 1.4: Measure Phase 1 result
- **Files**: none (uses existing harness)
- **Agent**: Main session
- **Changes**: none — run evidence harness
- **Commands**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts/hybrid-x11-runelite-evidence.ps1 -Variant internal-hybrid
  ```
- **Depends on**: Steps 1.1-1.3
- **Verification**:
  - `LorieNative` average should improve from ~50 to ~60-65 FPS
  - No `"Forcing legacy drawing"` in logcat
  - `XloriePerf` shows `avg_frame_ms` reduction (no more 5-15ms `glTexSubImage2D`)
  - Log to iteration log as new checkpoint

### Step 1.5: Code review
- **Files**: All files modified in Steps 1.1-1.3
- **Agent**: `code-review-agent`
- **Changes**: none — review only
- **Depends on**: Step 1.4 (review after measurement confirms correctness)

---

## Phase 2: Remove waitForNextFrame 2-Vsync Cap

**Goal**: Remove artificial frame pacing stall that wastes ~10ms per frame.
**Expected FPS**: ~70-80 (from ~60-65 after Phase 1)
**Depends on**: Phase 1 (sequential — same file `renderer.c`)

### Step 2.1: Remove post-swap fence wait (Option A — conservative)
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
- **Agent**: Main session
- **Changes**:
  - In `rendererRedrawLocked()`, remove or comment out lines 670-678:
    ```c
    // REMOVED: Post-swap fence wait (was adding ~4ms dead time)
    // glEnable(GL_SCISSOR_TEST);
    // glScissor(0, 0, 1, 1);
    // glClear(GL_COLOR_BUFFER_BIT);
    // glDisable(GL_SCISSOR_TEST);
    // EGLSyncKHR postSwapFence = eglCreateSyncKHR(...);
    // eglClientWaitSyncKHR(egl_display, postSwapFence, 0, EGL_FOREVER);
    // eglDestroySyncKHR(egl_display, postSwapFence);
    ```
  - Keep `waitForNextFrame` logic intact for now (Option A only)
- **Depends on**: Phase 1 complete
- **Verification**: `XloriePerf` shows `avg_inter_frame_ms` drop by ~4ms. Measure with evidence harness.

### Step 2.2: Measure Option A result (decision gate)
- **Files**: none
- **Agent**: Main session
- **Commands**: Run evidence harness, compare against Phase 1 baseline
- **Depends on**: Step 2.1
- **Decision**: If FPS improves meaningfully (>5 FPS gain) AND no jank visible, proceed to Step 2.3 (Option B). If jank appears, stop here and keep Option A only.

### Step 2.3: Remove waitForNextFrame entirely (Option B — aggressive)
- **Files**:
  - `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
  - `third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c`
  - `third_party/termux-x11-upstream/app/src/main/cpp/lorie/lorie.h`
- **Agent**: Main session
- **Changes**:
  - In `rendererRedrawLocked()` (renderer.c:662): Remove `state->waitForNextFrame = true;`
  - In `rendererShouldWait()` (renderer.c:701): Remove `state->waitForNextFrame` from the OR condition
  - In `lorieRedraw()` (InitOutput.c:413): Remove `pvfb->state->waitForNextFrame = false;` (now dead code)
  - In `lorie_shared_server_state` (lorie.h): Leave the field in struct (binary compat with running X server), just stop using it
  - Add `eglPresentationTimeANDROID()` call before `eglSwapBuffers()` in `rendererRedrawLocked()`:
    ```c
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    EGLnsecsANDROID presentTime = (EGLnsecsANDROID)ts.tv_sec * 1000000000LL + ts.tv_nsec;
    eglPresentationTimeANDROID(egl_display, sfc, presentTime);
    eglSwapBuffers(egl_display, sfc);
    ```
- **Depends on**: Step 2.2 (decision gate must approve)
- **Verification**: `avg_inter_frame_ms` should drop from ~16ms (Option A) to ~8-10ms. Frame rate should approach damage cadence.

### Step 2.4: Measure Phase 2 result
- **Files**: none
- **Agent**: Main session
- **Commands**: Run evidence harness for both clean-start synthetic and real RuneLite
- **Depends on**: Step 2.3 (or Step 2.1 if Option B rejected)
- **Verification**:
  - `LorieNative` average should reach ~70-80 FPS
  - No visible jank or frame tearing in real RuneLite session
  - `choreographer callbacks` still at 120 FPS (not broken)
  - Log to iteration log

### Step 2.5: Code review
- **Files**: All files modified in Steps 2.1-2.3
- **Agent**: `code-review-agent`
- **Depends on**: Step 2.4

---

## Phase 3 (Execution Order 3): Hybrid Host Lifecycle Parity

**Goal**: Close missing upstream lifecycle calls to ensure renderer is properly initialized.
**Expected FPS**: ~75-85 (or validates no regression)
**Depends on**: Phase 1 (ensures renderer format path is correct before adding preference reload)

**Note**: This is spec Phase 4 but executed third in the plan (before the heavy Phase 3 AHB work).

### Step 3.1: Add reloadPreferences after connect
- **Files**: `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
- **Agent**: Main session
- **Changes**:
  - In `tryAttach()`, after `lorieView.connect(detached)` + `lorieView.triggerCallback()`:
    ```kotlin
    // Upstream parity: reload preferences after X connection
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    lorieView.reloadPreferences(prefs)
    ```
  - Import `PreferenceManager` if not already imported
- **Depends on**: Phase 1 complete (so format path is correct before preference reload potentially re-triggers it)
- **Verification**: No `"Forcing legacy drawing"` regression. Renderer receives correct display configuration.

### Step 3.2: Add SharedPreferences change listener
- **Files**: `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
- **Agent**: Main session
- **Changes**:
  - In `onCreate()`, register `SharedPreferences.OnSharedPreferenceChangeListener`:
    ```kotlin
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        lorieView?.reloadPreferences(prefs)
    }
    // In onCreate:
    PreferenceManager.getDefaultSharedPreferences(this)
        .registerOnSharedPreferenceChangeListener(prefListener)
    ```
  - In `onDestroy()`, unregister the listener
- **Depends on**: Step 3.1

### Step 3.3: Add clientConnectedStateChanged callback
- **Files**:
  - `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
  - `runelite-tablet/app/src/main/java/com/termux/x11/MainActivity.kt`
- **Agent**: Main session
- **Changes**:
  - In `HybridX11HostActivity`, set a connection changed listener on the `MainActivity` singleton:
    ```kotlin
    MainActivity.getInstance().setConnectionChangedListener { connected ->
        runOnUiThread { handleConnectionStateChange(connected) }
    }
    ```
  - Verify `handleConnectionStateChange()` already exists (it does per codebase map)
  - Ensure `LorieView.kt` exposes `reloadPreferences()` — check if it's already public, expose if not
- **Depends on**: Step 3.1

### Step 3.4: Measure Phase 3 result
- **Files**: none
- **Agent**: Main session
- **Commands**: Run evidence harness, compare against Phase 2 baseline
- **Depends on**: Steps 3.1-3.3
- **Verification**:
  - No FPS regression from Phase 2
  - Renderer receives correct resolution and refresh rate after connect
  - Connection state changes handled cleanly (no reconnect storm)

### Step 3.5: Code review
- **Files**: All files modified in Steps 3.1-3.3
- **Agent**: `code-review-agent`
- **Depends on**: Step 3.4

---

## Phase 4: AHardwareBuffer Zero-Copy VirGL Bypass

**Goal**: Eliminate the VirGL readback + socket transfer + re-upload pipeline by sharing GPU buffers directly.
**Expected FPS**: ~90-120 (from ~75-85 after Phase 3)
**Depends on**: Phase 1 (RGBA EGLImage validated), Phase 3 (lifecycle parity ensures clean renderer state)

### Step 4.1: Create ahb_bridge protocol header
- **Files**:
  - NEW: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/ahb_bridge.h`
- **Agent**: Main session
- **Changes**:
  - Define control message types:
    ```c
    enum AhbBridgeCmd {
        AHB_CMD_OFFER_BUFFER = 1,    // Xlorie→Server: here's an AHB handle
        AHB_CMD_BUFFER_ACK = 2,      // Server→Xlorie: buffer imported successfully
        AHB_CMD_FRAME_READY = 3,     // Server→Xlorie: fence fd for completed frame
        AHB_CMD_RESIZE = 4,          // Xlorie→Server: new dimensions, reallocating
        AHB_CMD_SHUTDOWN = 5,        // Either direction: clean disconnect
    };
    ```
  - Define message struct (fixed 16 bytes + optional ancillary fd):
    ```c
    typedef struct {
        uint32_t cmd;
        uint32_t buffer_index;   // 0 or 1 (double buffer)
        uint32_t width;
        uint32_t height;
    } AhbBridgeMsg;
    ```
  - Declare public functions:
    ```c
    int ahb_bridge_create_socketpair(int fds[2]);
    int ahb_bridge_send_buffer(int sockfd, AHardwareBuffer *ahb, uint32_t index);
    int ahb_bridge_recv_buffer(int sockfd, AHardwareBuffer **out_ahb, uint32_t *out_index);
    int ahb_bridge_send_fence(int sockfd, int fence_fd, uint32_t buffer_index);
    int ahb_bridge_recv_fence(int sockfd, int *out_fence_fd, uint32_t *out_buffer_index);
    ```
- **Depends on**: none (header only)

### Step 4.2: Implement ahb_bridge protocol
- **Files**:
  - NEW: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/ahb_bridge.c`
  - `third_party/termux-x11-upstream/app/src/main/cpp/CMakeLists.txt`
- **Agent**: Main session
- **Changes**:
  - Implement all functions from header using:
    - `socketpair(AF_UNIX, SOCK_STREAM, 0, fds)` for control channel
    - `AHardwareBuffer_sendHandleToUnixSocket()` / `AHardwareBuffer_recvHandleFromUnixSocket()` for buffer sharing
    - `sendmsg()` with `SCM_RIGHTS` for fence fd passing
    - Non-blocking `recv()` for frame-ready polling
  - Add `ahb_bridge.c` to CMakeLists.txt source list
  - All functions must be thread-safe (called from renderer thread and server thread)
- **Depends on**: Step 4.1
- **Verification**: Unit test with a simple socketpair — allocate AHB, send, receive, verify dimensions match.

### Step 4.3: Xlorie allocator — create and share AHBs
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
- **Agent**: Main session
- **Changes**:
  - Add new function `rendererInitAhbBridge()`:
    1. Create socketpair via `ahb_bridge_create_socketpair()`
    2. Allocate 2 AHardwareBuffers with `R8G8B8A8_UNORM` + `GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE`
    3. Import each as EGLImage for texture sampling (compositor side)
    4. Send both AHB handles to server via `ahb_bridge_send_buffer()`
    5. Store the server-side socket fd for later fence receiving
  - Call `rendererInitAhbBridge()` from `rendererRefreshContext()` when surface dimensions change
  - Add cleanup in `rendererThread()` exit path
- **Depends on**: Step 4.2
- **Verification**: AHB allocation succeeds, EGLImage creation succeeds, server receives handles.

### Step 4.4: VirGL server — receive AHBs and redirect FBO
- **Files**: `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
- **Agent**: Main session
- **Changes**:
  - Add native JNI method `nativeSetAhbBridgeFd(int fd)` that passes the control socket fd to the native server
  - In native cmdentrypoint.c, add a new looper callback for the AHB bridge socket:
    1. On `AHB_CMD_OFFER_BUFFER`: receive AHB handle, import as EGLImage, create FBO, attach as color
    2. Send `AHB_CMD_BUFFER_ACK` back to Xlorie
    3. Hook into virglrenderer's scanout path: when the scanout resource is about to be read back via `glReadPixels`, instead redirect rendering to the shared AHB FBO
  - After each frame's `eglSwapBuffers` or `glFlush` on the server:
    1. Create native fence: `eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, -1, EGL_NONE})`
    2. Extract fd: `eglDupNativeFenceFDANDROID(display, sync)` — caller owns returned fd
    3. Send via `ahb_bridge_send_fence(sockfd, fence_fd, buffer_index)`
    4. Destroy sync: `eglDestroySyncKHR(display, sync)` — does NOT close the dup'd fd
    5. Swap double-buffer index
  - **Critical**: The fence fd must be created AFTER the GL commands that render into the AHB, not before. The `EGL_NO_NATIVE_FENCE_FD_ANDROID` (-1) attribute tells EGL to create a new fence from pending GL commands.
- **Depends on**: Steps 4.2, 4.3
- **Verification**: Server receives AHB handles. `glCheckFramebufferStatus` returns `GL_FRAMEBUFFER_COMPLETE`. Fence fds are non-negative.

### Step 4.5: Xlorie compositor — receive fences and sample AHB
- **Files**: `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c`
- **Agent**: Main session
- **Changes**:
  - In `rendererRedrawLocked()`, add alternative path when AHB bridge is active:
    1. Poll for `AHB_CMD_FRAME_READY` from bridge socket (non-blocking)
    2. If received: import fence fd as EGL sync:
       ```c
       EGLint attribs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fence_fd, EGL_NONE};
       EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
       // EGL takes ownership of fence_fd — do NOT close it
       eglWaitSyncKHR(display, sync, 0);  // GPU-side wait, no CPU stall
       eglDestroySyncKHR(display, sync);
       ```
    3. Bind the AHB texture for the received buffer index
    4. Draw full-screen quad (same as existing path)
    5. Draw cursor overlay (unchanged)
    6. `eglSwapBuffers()` to present
  - Skip the old `LorieBuffer_bindTexture()` path entirely when bridge is active
  - Keep the old path as fallback when bridge is not connected
- **Depends on**: Steps 4.3, 4.4
- **Verification**: Frames render without `glTexSubImage2D` calls. `XloriePerf` shows dramatically reduced `avg_frame_ms`.

### Step 4.6: Wire bridge socket between Xlorie and CmdEntryPoint
- **Files**:
  - `third_party/termux-x11-upstream/app/src/main/cpp/lorie/activity.c`
  - `runelite-tablet/app/src/main/java/com/termux/x11/CmdEntryPoint.java`
  - `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/hybrid/HybridX11HostActivity.kt`
- **Agent**: Main session
- **Changes**:
  - In `activity.c`: When X connection is established (in `xcallback`), send the Xlorie-side bridge socket fd to Java via JNI callback
  - In `HybridX11HostActivity.kt`: Receive the bridge fd from native, pass to CmdEntryPoint via binder interface
  - In `CmdEntryPoint.java`: Accept the bridge fd, pass to native via `nativeSetAhbBridgeFd()`
  - The socketpair is created in the Xlorie renderer (Step 4.3). One end stays in Xlorie, the other end is passed to the server.
- **Depends on**: Steps 4.3, 4.4, 4.5
- **Verification**: End-to-end: RuneLite renders, frames appear on screen via AHB path, no readback in logs.

### Step 4.7: Measure Phase 4 result
- **Files**: none
- **Agent**: Main session
- **Commands**:
  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts/hybrid-x11-runelite-evidence.ps1 -Variant internal-hybrid
  ```
- **Depends on**: Steps 4.1-4.6
- **Verification**:
  - `LorieNative` average should reach ~90-120 FPS
  - `avg_inter_frame_ms` should approach `avg_frame_ms` (~5-8ms)
  - No `glReadPixels` calls in server logs
  - No `glTexSubImage2D` calls in renderer logs
  - Real RuneLite session stable (no black screen, no corruption)
  - Log to iteration log as milestone checkpoint

### Step 4.8: Code review
- **Files**: All files from Steps 4.1-4.6
- **Agent**: `code-review-agent`
- **Depends on**: Step 4.7

### Step 4.9: Performance review
- **Files**: All evidence captures from Step 4.7
- **Agent**: `performance-agent`
- **Depends on**: Step 4.7
- **Focus**:
  - Compare full pipeline: Phase 1 → Phase 2 → Phase 3 → Phase 4
  - Identify any remaining bottlenecks
  - Validate AFBC is active (if GPU profiler available)
  - Check for GPU memory pressure from double-buffered AHBs

---

## Execution Summary

```
Phase 1 (5 steps) — Fix Legacy Drawing
  ├─ 1.1: Instrument diagnostic logging
  ├─ 1.2: Fix RGBA format selection
  ├─ 1.3: Verify buffer.c format handling
  ├─ 1.4: Measure (~60-65 FPS expected)
  └─ 1.5: Code review
      ↓
Phase 2 (5 steps) — Remove waitForNextFrame
  ├─ 2.1: Remove post-swap fence (Option A)
  ├─ 2.2: Measure + decision gate
  ├─ 2.3: Remove waitForNextFrame (Option B, if approved)
  ├─ 2.4: Measure (~70-80 FPS expected)
  └─ 2.5: Code review
      ↓
Phase 3 (5 steps) — Hybrid Lifecycle Parity (spec Phase 4)
  ├─ 3.1: Add reloadPreferences
  ├─ 3.2: Add preference change listener
  ├─ 3.3: Add clientConnectedStateChanged
  ├─ 3.4: Measure (~75-85 FPS expected)
  └─ 3.5: Code review
      ↓
Phase 4 (9 steps) — AHB Zero-Copy Bypass (spec Phase 3)
  ├─ 4.1: Create bridge protocol header
  ├─ 4.2: Implement bridge protocol
  ├─ 4.3: Xlorie allocator (create + share AHBs)
  ├─ 4.4: VirGL server (receive AHBs + redirect FBO)
  ├─ 4.5: Xlorie compositor (receive fences + sample)
  ├─ 4.6: Wire bridge socket end-to-end
  ├─ 4.7: Measure (~90-120 FPS expected)
  ├─ 4.8: Code review
  └─ 4.9: Performance review
```

**Total**: 4 phases, 24 steps, 11 direct files (2 new), ~640 estimated lines changed

---

## 2026-03-16 Gate Update

Validated captures `20260316-113124` and `20260316-113505` changed the execution priority for this plan.

Observed in both runs:

- layer votes still reached `120 Hz`
- `LorieNative` stayed around `~29.5-32.66 FPS` average
- Present stayed inactive: `present after-flips = 0 FPS`
- DRI3 still only logged `QueryVersion`
- runtime logs still showed only plain `DamageTrace: PutImage` / `DamageTrace: Composite`

Important control result:

- the built arm64 `libXlorie.so` contains the new backend-tagged / `DamageTraceV2` strings
- the running process never emitted `DamageTraceV2`, `backend=`, or `fn=`

That means the current blocker is now execution-path identity, not missing AHB bridge code.

### Updated next step before Phase 4

Add a hard binary/path identity checkpoint first:

1. Emit an unmistakable startup sentinel from a native entry point known to run in every validated launch.
2. Correlate that sentinel with the process/package/library actually producing the `DamageTrace` lines.
3. Only resume Phase 4 AHB zero-copy implementation after the live runtime boundary is proven.

Phase 4 remains the long-term candidate path, but it is currently gated by missing proof that the patched native code being built is the same code executing in the hot path under test.

## 2026-03-16 Packaging Correction

The live hybrid path does not primarily depend on the separately installed donor APK build. It uses:

1. `com.runelitetablet` host process loading `base.apk!/lib/arm64-v8a/libXlorie.so`
2. `CmdEntryPoint` extracting that same app package library to:
   `/data/data/com.termux/files/usr/tmp/termux-x11-com.runelitetablet-libs/arm64-v8a-libXlorie.so`

Implication:

- rebuilding only `third_party/termux-x11-upstream :app` is insufficient for the validated internal-hybrid path
- the correct build/install target for performance iterations is `runelite-tablet :app`

## 2026-03-16 Gate Update 2

Validated captures `20260316-115145` and `20260316-115428` now prove the runtime identity and the live damage wrapper path.

Observed:

- `XlorieIdentity` logs appeared from both the UI host and the app-process X server
- `DamageTraceV2` appeared in the real workload after rebuilding `runelite-tablet`
- `PutImage` now reports `backend=other`
- `Composite` and `PutImage` function pointers both resolve to the extracted server `libXlorie.so`
- `dladdr` symbol names are still `unknown`
- Present remains inactive
- FPS remains around the same range (`~30-32 FPS` average `LorieNative`)

### Updated next step before any Phase 4 continuation

Do one more direct mapping iteration:

1. Log `dladdr` base addresses and function offsets for the hot `PutImage` and `Composite` pointers.
2. Map those offsets to the unstripped local `libXlorie.so` built by `runelite-tablet`.
3. Instrument or optimize that exact implementation.

This is now a much narrower and better-justified blocker than the earlier generic “prove the runtime path” gate.
