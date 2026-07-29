#!/data/data/com.termux/files/usr/bin/bash
# Build the project-patched Mesa virpipe DRI driver natively on the target.
#
# Usage:
#   device-build-mesa-frame-stream.sh MESA_ARCHIVE PATCH_DIRECTORY BUILD_ROOT
#
# The result is an additive package under /opt. It never overwrites the
# system Mesa package; the launcher selects it only for completed-frame
# streaming captures.

set -euo pipefail

MESA_ARCHIVE="${1:?Mesa source archive is required}"
PATCH_DIRECTORY="${2:?Mesa patch directory is required}"
BUILD_ROOT="${3:?Build root is required}"
CONTROL_FILE="${4:?Debian control template is required}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SOURCE_ROOT="$BUILD_ROOT/mesa-25.2.8"
BUILD_DIRECTORY="$BUILD_ROOT/build"
PACKAGE_ROOT="$BUILD_ROOT/package"
OUTPUT_DEB="$BUILD_ROOT/mesa-rlt-frame-stream_25.2.8-5_aarch64.deb"
OPT_ROOT="$PACKAGE_ROOT$TERMUX_PREFIX/opt/mesa-rlt-frame-stream"

export PATH="/data/data/com.termux/files/home/.local/bin:$TERMUX_PREFIX/bin:$PATH"
export PYTHONPATH="/data/data/com.termux/files/home/.local/lib/python3.14/site-packages:/data/user/0/com.termux/.local/lib/python3.14/site-packages${PYTHONPATH:+:$PYTHONPATH}"
# Keep the installed Termux Mesa ABI: its API-24 target uses compiler-emulated
# TLS for GL dispatch. An API-30 target builds but cannot interoperate with the
# installed libGLX_mesa. Meson's on-device memfd probe sees the API-36 runtime
# and incorrectly enables API-gated libc declarations, so force Mesa's existing
# Android syscall and /dev/urandom fallbacks instead.
export CC="clang --target=aarch64-linux-android24"
export CXX="clang++ --target=aarch64-linux-android24"
export CPPFLAGS="${CPPFLAGS:-} -D__USE_GNU -UHAVE_MEMFD_CREATE -UHAVE_GETRANDOM"
export LDFLAGS="${LDFLAGS:-} -landroid-shmem"

if [[ "$BUILD_ROOT" != /data/data/com.termux/files/home/.rlt/build/mesa-frame-stream-* ]]; then
    echo "ERROR: unsafe build root: $BUILD_ROOT" >&2
    exit 2
fi
if [[ -e "$SOURCE_ROOT" || -e "$BUILD_DIRECTORY" || -e "$PACKAGE_ROOT" ]]; then
    echo "ERROR: build root is not empty: $BUILD_ROOT" >&2
    exit 2
fi
if [[ ! -f "$MESA_ARCHIVE" || ! -d "$PATCH_DIRECTORY" || ! -f "$CONTROL_FILE" ]]; then
    echo "ERROR: source archive, patch directory, or control template is missing" >&2
    exit 2
fi

mkdir -p "$BUILD_ROOT"
tar -xJf "$MESA_ARCHIVE" -C "$BUILD_ROOT"
rm -rf "$SOURCE_ROOT/subprojects"
cp "$PATCH_DIRECTORY/rlt_frame_telemetry.h" \
    "$SOURCE_ROOT/src/gallium/winsys/virgl/vtest/rlt_frame_telemetry.h"

while IFS= read -r patch_file; do
    echo "Applying $(basename "$patch_file")"
    tr -d '\r' < "$patch_file" |
        patch --batch --forward --silent -d "$SOURCE_ROOT" -p1
done < <(
    find "$PATCH_DIRECTORY" -maxdepth 1 -type f \
        \( -name '*.patch' -o -name '*.diff' \) |
        sort
)

sed -i "s#@TERMUX_PREFIX@#$TERMUX_PREFIX#g" \
    "$SOURCE_ROOT/src/virtio/vtest/vtest_protocol.h"

meson setup "$BUILD_DIRECTORY" "$SOURCE_ROOT" \
    --prefix="$TERMUX_PREFIX" \
    --buildtype=release \
    -Dplatforms=x11 \
    -Dgallium-drivers=virgl \
    -Dvulkan-drivers= \
    -Dllvm=disabled \
    -Dshared-llvm=disabled \
    -Dopengl=true \
    -Dglx=dri \
    -Degl=enabled \
    -Degl-native-platform=x11 \
    -Dgles1=disabled \
    -Dgles2=enabled \
    -Dgbm=disabled \
    -Dgallium-rusticl=false \
    -Dglvnd=enabled \
    -Dxmlconfig=disabled \
    -Dvalgrind=disabled \
    -Dlibunwind=disabled \
    -Dbuild-tests=false \
    -Dvideo-codecs=

ninja -C "$BUILD_DIRECTORY" src/gallium/targets/dri/libgallium-25.2.8.so

mkdir -p "$OPT_ROOT/lib/dri" "$PACKAGE_ROOT/DEBIAN"
cp "$BUILD_DIRECTORY/src/gallium/targets/dri/libgallium-25.2.8.so" \
    "$OPT_ROOT/lib/libgallium-25.2.8.so"
cp "$TERMUX_PREFIX/lib/dri/libdril_dri.so" "$OPT_ROOT/lib/dri/libdril_dri.so"
ln -s libdril_dri.so "$OPT_ROOT/lib/dri/swrast_dri.so"
ln -s libdril_dri.so "$OPT_ROOT/lib/dri/virtio_gpu_dri.so"
cp "$CONTROL_FILE" "$PACKAGE_ROOT/DEBIAN/control"
chmod 0755 "$PACKAGE_ROOT/DEBIAN"
chmod 0644 "$PACKAGE_ROOT/DEBIAN/control"

dpkg-deb --build --root-owner-group "$PACKAGE_ROOT" "$OUTPUT_DEB"
sha256sum "$OUTPUT_DEB" "$OPT_ROOT/lib/libgallium-25.2.8.so"
strings "$OPT_ROOT/lib/libgallium-25.2.8.so" |
    grep -E 'bypassed_frame_stream|host_present_no_guest_payload|delegated_to_transfer_completion|frame_marker_wall_ns|VCMD_RLT_FRAME_MARKER|VCMD_RLT_TRANSFER_GET_DIRECT'
