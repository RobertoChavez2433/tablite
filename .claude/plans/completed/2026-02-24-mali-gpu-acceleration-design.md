# Mali GPU Acceleration Design

## Date: 2026-02-24 | Session: 37

## Problem

RuneLite runs on a Samsung Tab S10 Ultra with **MediaTek Dimensity 9300+ / Mali-G720 Immortalis MC12**. The GPU plugin requires OpenGL 3.1+ (reduced from 4.0 in RuneLite v1.8.27). Currently stuck on llvmpipe (CPU software rendering) because:

1. Our GPU setup script installs Turnip — an **Adreno-only** driver. Useless on Mali.
2. Zink over Mali Vulkan crashes — Mali is missing `fillModeNonSolid`, `shaderClipDistance`, `logicOp`.
3. Panfrost needs Panthor kernel driver — Android ships kbase (incompatible).
4. VirGL alone caps at GL 2.1 — not enough.

**Device details**: `/dev/mali0`, `/dev/dri/card0`, Vulkan 1.3, GLES 3.2 via blob.

## Solution: Tiered GPU Acceleration

Three tiers, tried in order. Each tier is self-contained — if it fails, the next is tried automatically.

### Tier 1: virgl-angle (ANGLE Vulkan backend)

**Pipeline**: `RuneLite → virpipe → virgl_test_server → ANGLE → Mali Vulkan → GPU`

ANGLE translates GLES→Vulkan without requiring `fillModeNonSolid`/`shaderClipDistance`. This bypasses all known Mali Vulkan feature gaps.

**Termux packages**: `virglrenderer-android`, `angle-android`

**Server launch**:
```bash
virgl_test_server_android --angle-gl &
```

**Proot env vars**:
```bash
export GALLIUM_DRIVER=virpipe
export MESA_GL_VERSION_OVERRIDE=4.1COMPAT
export MESA_NO_ERROR=1
```

**Expected GL**: 3.1–4.1 (with COMPAT override)
**Risk**: Untested on G720. ANGLE Vulkan may have Mali-specific bugs.

### Tier 2: virglrenderer-android (Native GLES)

**Pipeline**: `RuneLite → virpipe → virgl_test_server → Android GLES 3.2 → Mali GPU`

Uses Mali's native GLES driver directly. No Vulkan involved.

**Termux packages**: `virglrenderer-android`

**Server launch**:
```bash
virgl_test_server_android &
```

**Proot env vars**:
```bash
export GALLIUM_DRIVER=virpipe
export MESA_GL_VERSION_OVERRIDE=3.1COMPAT
export MESA_NO_ERROR=1
```

**Expected GL**: 2.1 native, 3.1 with override (features may be missing)
**Risk**: Stack smashing crashes reported on Mali-G925 (similar arch).

### Tier 3: Optimized Software Rendering (Guaranteed)

**Pipeline**: `RuneLite → Java2D software renderer → llvmpipe → CPU`

No GPU plugin. Optimized JVM and display settings.

**Key optimizations**:
- GPU plugin OFF (avoid llvmpipe compute shader overhead)
- `-Dsun.java2d.opengl=false` (force software path)
- RuneLite Stretched Mode: render at 1480x924, scale to 2960x1848
- `LP_NUM_THREADS=4` (llvmpipe threading)
- `-Xmx2g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=30`

**Expected**: 30-50fps (OSRS software renderer caps at 50fps)

---

## Architecture Changes

### 1. GPU Detection (New: `detect-gpu.sh`)

Detect GPU vendor at runtime — not at install time.

```bash
#!/data/data/com.termux/files/usr/bin/bash
# Detect GPU vendor from Android system properties
GPU_VENDOR="unknown"

if [ -e /dev/kgsl-3d0 ]; then
    GPU_VENDOR="adreno"
elif [ -e /dev/mali0 ]; then
    GPU_VENDOR="mali"
elif [ -e /dev/pvr_sync ]; then
    GPU_VENDOR="powervr"
fi

echo "$GPU_VENDOR"
```

### 2. GPU Setup (Modified: `setup-gpu.sh`)

Branch based on detected GPU vendor:

- **Adreno**: Install Mesa + Turnip (existing path)
- **Mali**: Install `virglrenderer-android` + `angle-android` in Termux, Mesa in proot
- **Unknown**: Skip GPU setup, use software rendering

### 3. Launch Script (Modified: `launch-runelite.sh`)

Before entering proot, start the virgl server if Mali is detected:

```bash
GPU_VENDOR=$(detect-gpu.sh)

if [ "$GPU_VENDOR" = "mali" ]; then
    # Kill any existing virgl server
    pkill -f virgl_test_server 2>/dev/null || true

    # Try Tier 1: ANGLE backend
    if command -v virgl_test_server_android >/dev/null 2>&1; then
        virgl_test_server_android --angle-gl &
        VIRGL_PID=$!
        sleep 1

        # Verify server started
        if kill -0 $VIRGL_PID 2>/dev/null; then
            echo "VirGL+ANGLE server started (PID $VIRGL_PID)"
            PROOT_GPU_ENV="GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.1COMPAT MESA_NO_ERROR=1"
        else
            echo "VirGL+ANGLE failed, trying native GLES..."
            # Tier 2: Native GLES
            virgl_test_server_android &
            VIRGL_PID=$!
            sleep 1
            if kill -0 $VIRGL_PID 2>/dev/null; then
                echo "VirGL native GLES server started"
                PROOT_GPU_ENV="GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=3.1COMPAT MESA_NO_ERROR=1"
            else
                echo "VirGL unavailable, software rendering"
                PROOT_GPU_ENV=""
            fi
        fi
    fi
elif [ "$GPU_VENDOR" = "adreno" ]; then
    # Existing Zink+Turnip path (bind /dev/kgsl-3d0)
    PROOT_GPU_ENV="MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink TU_DEBUG=noconform MESA_NO_ERROR=1 ZINK_DESCRIPTORS=lazy"
fi
```

Inside proot, apply the GPU env vars and add a runtime GL check:

```bash
# Quick GL validation before launching RuneLite
if [ -n "$PROOT_GPU_ENV" ]; then
    GL_VERSION=$(glxinfo 2>/dev/null | grep "OpenGL version" | head -1)
    echo "GL check: $GL_VERSION"

    # Extract major.minor version
    GL_MAJOR=$(echo "$GL_VERSION" | grep -oP '\d+\.\d+' | head -1 | cut -d. -f1)
    if [ "${GL_MAJOR:-0}" -ge 3 ]; then
        echo "GPU acceleration: ENABLED ($GL_VERSION)"
    else
        echo "GPU acceleration: GL version too low ($GL_VERSION), falling back to software"
        unset GALLIUM_DRIVER MESA_GL_VERSION_OVERRIDE
    fi
fi
```

### 4. SetupStep Changes (Kotlin)

Replace `SetupStep.InstallGpuDrivers` logic:

```kotlin
// In SetupOrchestrator, GPU step:
// 1. Run detect-gpu.sh to get vendor
// 2. If "mali" → install virglrenderer-android + angle-android in Termux
// 3. If "adreno" → existing Turnip path
// 4. If "unknown" → skip, software rendering
```

### 5. Shutdown Changes

Add virgl server cleanup to `shutdown-session.sh` and `cleanup_on_exit()`:

```bash
# Kill virgl server
pkill -f 'virgl_test_server' 2>/dev/null || true
```

### 6. Health Check

No changes needed — the sentinel file approach works regardless of GPU backend.

---

## New Files

| File | Purpose |
|------|---------|
| `assets/scripts/detect-gpu.sh` | Detect GPU vendor (adreno/mali/powervr/unknown) |
| `assets/scripts/setup-gpu-mali.sh` | Install virglrenderer + angle packages in Termux |

## Modified Files

| File | Changes |
|------|---------|
| `assets/scripts/setup-gpu.sh` | Branch on GPU vendor, call mali-specific setup |
| `assets/scripts/launch-runelite.sh` | Start virgl server for Mali, tiered fallback, GL validation |
| `assets/scripts/shutdown-session.sh` | Kill virgl server on shutdown |
| `session/RuneLiteSessionService.kt` | Add virgl cleanup to aggressive shutdown |
| `setup/SetupOrchestrator.kt` | GPU step branches on vendor |
| `setup/ScriptManager.kt` | Deploy new scripts |

---

## Testing Plan

### Phase 1: Spike Test (on device)
1. Manually run `pkg install virglrenderer-android angle-android` in Termux
2. Start `virgl_test_server_android --angle-gl &`
3. In proot: `GALLIUM_DRIVER=virpipe MESA_GL_VERSION_OVERRIDE=4.1COMPAT glxinfo | grep OpenGL`
4. If GL 3.1+ → proceed. If crash → try without `--angle-gl` (Tier 2). If both fail → Tier 3 only.

### Phase 2: RuneLite GPU Plugin Test
1. Launch RuneLite with virgl env vars active
2. Enable GPU plugin
3. Check RuneLite logs for `Using device:` — should NOT say "llvmpipe"
4. Check FPS (F3 key or FPS Counter plugin)

### Phase 3: Stability Test
1. Play for 10+ minutes with GPU plugin ON
2. World hop (tests GL context recreation)
3. Open bank (heavy UI overlay rendering)
4. Teleport to busy area (geometry load spike)

### Phase 4: Integration Test
1. Full launch flow via app → setup → launch → verify GPU is used
2. Shutdown via notification → verify virgl server killed
3. Re-launch → verify virgl server starts fresh

---

## Decisions Made

1. **Tiered fallback**: ANGLE → native GLES → software. Auto-detected, no user configuration.
2. **GPU detection at runtime**: `/dev/mali0` vs `/dev/kgsl-3d0` check, not build-time.
3. **Server lifecycle**: virgl server started by launch script, killed by shutdown script. Tied to session, not persistent.
4. **GL version override**: 4.1COMPAT for ANGLE path, 3.1COMPAT for native GLES. Conservative — RuneLite minimum is 3.1.
5. **No proot Vulkan**: Vulkan doesn't work in proot (confirmed by virgl-angle maintainer). All GPU acceleration goes through VirGL protocol.

## Open Questions

1. Does `virgl_test_server_android --angle-gl` actually start without crashing on Mali-G720?
2. What real GL features are available through the ANGLE path (not just version string)?
3. Does RuneLite GPU plugin work with VirGL's serialization latency, or does the frame timing break?
4. Performance: is VirGL+ANGLE faster than llvmpipe for RuneLite's rendering workload at 2960x1848?

## Key Insight

RuneLite v1.8.27 reduced the GPU plugin minimum to **OpenGL 3.1** (macOS compatibility). This means even partial GL support from VirGL may be enough — we don't need full GL 4.3. The GPU plugin will work without compute shaders (losing extended draw distance only).
