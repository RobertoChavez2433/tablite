# Fix Mali GPU Blocker: lfdevs Mesa with virpipe Driver

## Date: 2026-02-24 | Session: 40

## Problem

RuneLite runs inside proot Ubuntu ARM64 on a Samsung Tab S10 Ultra (MediaTek Dimensity 9300+ / Mali-G720 Immortalis MC12). GPU acceleration is BLOCKED because:

1. Ubuntu ARM64's `libgl1-mesa-dri` does **not** include `virtio_gpu_dri.so` (the virpipe Gallium driver)
2. The VirGL server (`virgl_test_server_android --angle-gl`) runs fine in Termux — server side works
3. But `GALLIUM_DRIVER=virpipe glxinfo` returns empty inside proot — client side has no virpipe driver
4. Mesa falls back to llvmpipe (CPU software rendering)

## Root Cause

Ubuntu's ARM64 Mesa build omits the virgl Gallium driver from `libgl1-mesa-dri`. This is likely intentional — Ubuntu doesn't expect ARM64 machines to be VirGL guests. The driver binary `virtio_gpu_dri.so` is simply not present in `/usr/lib/aarch64-linux-gnu/dri/`.

## Solution: lfdevs Mesa Package

Replace Ubuntu's Mesa with the [lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container) pre-built Mesa, which is compiled with `-Dgallium-drivers=freedreno,zink,virgl,llvmpipe` — includes virgl.

**This is the same project we already use for the Adreno GPU path** in `setup-gpu.sh` (lines 37-57). We simply apply the same pattern to the Mali path.

## Changes

### Modified File: `assets/scripts/setup-gpu-mali.sh`

Replace the `apt-get install libgl1-mesa-dri` approach inside the proot block with lfdevs Mesa tarball download + extraction.

#### Before (lines 47-52):
```bash
apt-get update -qq
apt-get install -y -qq mesa-utils libgl1-mesa-dri libglx-mesa0 \
    libx11-6 libxext6 libxfixes3 libxshmfence1 \
    libxxf86vm1 libdrm2 libexpat1 libwayland-client0 libelf1 \
    zlib1g libzstd1 >/dev/null
ldconfig
```

#### After:
```bash
# Install X11/GL dependencies (NOT Mesa itself — we install lfdevs Mesa below)
apt-get update -qq
apt-get install -y -qq mesa-utils libx11-6 libxext6 libxfixes3 \
    libxshmfence1 libxxf86vm1 libdrm2 libexpat1 libwayland-client0 \
    libelf1 zlib1g libzstd1 wget >/dev/null

# Download lfdevs Mesa (includes virgl Gallium driver for virpipe)
MESA_VERSION="26.1.0"
MESA_BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download"
MESA_TAG="mesa-${MESA_VERSION}"
cd /tmp
echo "Downloading Mesa ${MESA_VERSION} (with virgl driver)..."
wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"
echo "Installing Mesa..."
tar -zxf mesa.tar.gz -C /
rm -f /tmp/mesa.tar.gz
ldconfig
```

### No Other Files Change

- `launch-runelite.sh` — VirGL server startup, tiered fallback, GL validation all stay as-is
- `setup-gpu.sh` — Already branches to Mali path correctly
- `detect-gpu.sh` — Already detects Mali via `/dev/mali0`
- `shutdown-session.sh` — Already kills virgl server
- Kotlin code — No changes needed

## Verification

The existing verification logic in `setup-gpu-mali.sh` checks:
```bash
[ -f "/usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so" ]
```

This will now pass because the lfdevs tarball drops `virtio_gpu_dri.so` (symlink to megadriver) into the correct DRI directory.

The existing GL validation in `launch-runelite.sh` (lines 322-338) checks actual GL version at runtime and falls back to software if GL is too low.

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| lfdevs tarball missing virtio_gpu_dri.so | Verify post-extraction; fall through to Tier 3 (software) |
| ABI mismatch with Ubuntu Noble | lfdevs builds target Ubuntu Noble ARM64 specifically |
| VirGL+ANGLE crashes on Mali-G720 | Tier 2 (native GLES) and Tier 3 (software) fallbacks already implemented |
| GL version too low for RuneLite GPU plugin | GL validation already falls back to software rendering |
| lfdevs stops publishing releases | Pin to known-working version; build from source as backup |

## Testing Plan

1. Deploy updated `setup-gpu-mali.sh` to device
2. Run GPU setup step — verify `virtio_gpu_dri.so` present in proot
3. Launch RuneLite — check logs for `GPU acceleration: ENABLED (VirGL+ANGLE)`
4. If ENABLED: check glxinfo output, try RuneLite GPU plugin
5. If FAILED: verify Tier 2/3 fallback works correctly

## Research Summary

Three approaches were evaluated:

| Approach | Effort | Risk | Chosen? |
|----------|--------|------|---------|
| **lfdevs Mesa tarball** | Low (~10 lines) | Low (proven pattern) | **YES** |
| Switch to Arch Linux ARM | High (6+ scripts rewrite) | Medium (new distro) | No |
| Build Mesa from source in proot | Medium (15-30 min build) | Low (native ABI) | No — backup if lfdevs fails |

## Key Research Findings

- **Mali has NO direct GPU path from proot** — no `/dev/kgsl-3d0` equivalent, Panfrost needs DRM kernel, Android Vulkan blob can't load in proot
- **VirGL is the ONLY viable GPU path for Mali inside proot**
- **Zink on Mali is unreliable** — missing `fillModeNonSolid`, `shaderClipDistance`, `logicOp` Vulkan features
- **lfdevs Mesa build config** includes `virgl` in gallium-drivers — confirmed from their meson config
- **Ubuntu intentionally omits virgl on ARM64** — their Mesa build doesn't include it

## Decisions

1. Use lfdevs Mesa instead of Ubuntu's Mesa for Mali GPU path
2. Same tarball approach as Adreno path for consistency
3. No distro change — keep Ubuntu, replace only Mesa
4. No source builds — lfdevs pre-built is sufficient
5. Pin to Mesa 26.1.0 (same version as Adreno path)
