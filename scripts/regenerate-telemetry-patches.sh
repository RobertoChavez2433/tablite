#!/usr/bin/env bash
# Regenerate LF-only Mesa/VirGL telemetry package patches from pristine sources.
set -euo pipefail
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

PROJECT_ROOT="${1:-}"
if [[ -z "$PROJECT_ROOT" || ! -d "$PROJECT_ROOT/third_party" ]]; then
    echo "usage: $0 /absolute/path/to/Tablite" >&2
    exit 2
fi

WORK_DIR="$(mktemp -d /tmp/tablite-telemetry-patchregen.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

append_diff() {
    local output="$1" relative="$2" before="$3" after="$4"
    local raw_diff="$WORK_DIR/append-diff.raw" status=0
    printf 'diff --git a/%s b/%s\n' "$relative" "$relative" >> "$output"
    diff -u \
        --label "a/$relative" \
        --label "b/$relative" \
        "$before" "$after" > "$raw_diff" || status=$?
    if [[ "$status" != "0" && "$status" != "1" ]]; then
        return "$status"
    fi
    tr -d '\r' < "$raw_diff" >> "$output"
}

MESA_PACKAGE="$PROJECT_ROOT/third_party/termux-packages/packages/mesa"
MESA_PATCH="$MESA_PACKAGE/0020-rlt-vtest-frame-telemetry.patch"
MESA_LOOPBACK_PATCH="$MESA_PACKAGE/0021-rlt-vtest-loopback-tcp.patch"
MESA_MARKER_PATCH="$MESA_PACKAGE/0023-rlt-vtest-frame-marker.patch"
MESA_DIRECT_PATCH="$MESA_PACKAGE/0024-rlt-direct-host-transfer.patch"
MESA_PRECOPY_FENCE_PATCH="$MESA_PACKAGE/0025-rlt-frame-stream-delegate-precopy-fence.patch"
tr -d '\r' < "$MESA_PATCH" > "$WORK_DIR/mesa-input-lf.patch"
tr -d '\r' < "$MESA_LOOPBACK_PATCH" \
    > "$WORK_DIR/mesa-loopback-lf.patch"
tr -d '\r' < "$MESA_PACKAGE/0022-rlt-frame-stream-bypass-x11-display.patch" \
    > "$WORK_DIR/mesa-bypass-lf.patch"
tr -d '\r' < "$MESA_MARKER_PATCH" > "$WORK_DIR/mesa-marker-input-lf.patch"
tr -d '\r' < "$MESA_DIRECT_PATCH" > "$WORK_DIR/mesa-direct-input-lf.patch"
tr -d '\r' < "$MESA_PRECOPY_FENCE_PATCH" \
    > "$WORK_DIR/mesa-precopy-fence-input-lf.patch"
curl -fsSL https://archive.mesa3d.org/mesa-25.2.8.tar.xz \
    -o "$WORK_DIR/mesa.tar.xz"
tar -xf "$WORK_DIR/mesa.tar.xz" -C "$WORK_DIR"
(
    cd "$WORK_DIR/mesa-25.2.8"
    git apply --no-index "$MESA_PACKAGE/0009-disable-resource_create_front-for-vtest.patch"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c \
        "$WORK_DIR/mesa-virgl_vtest_winsys.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h \
        "$WORK_DIR/mesa-virgl_vtest_winsys.h.before"
    git apply --no-index --recount "$WORK_DIR/mesa-input-lf.patch"
    : > "$WORK_DIR/mesa-regenerated.patch"
    append_diff \
        "$WORK_DIR/mesa-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c" \
        "$WORK_DIR/mesa-virgl_vtest_winsys.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c"
    append_diff \
        "$WORK_DIR/mesa-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h" \
        "$WORK_DIR/mesa-virgl_vtest_winsys.h.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h"
)
cp "$WORK_DIR/mesa-regenerated.patch" "$MESA_PATCH"
(
    cd "$WORK_DIR/mesa-25.2.8"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c \
        "$WORK_DIR/mesa-loopback-socket.c.before"
    git apply --no-index --recount "$WORK_DIR/mesa-loopback-lf.patch"
    : > "$WORK_DIR/mesa-loopback-regenerated.patch"
    append_diff \
        "$WORK_DIR/mesa-loopback-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c" \
        "$WORK_DIR/mesa-loopback-socket.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c"
    git apply --no-index --recount "$WORK_DIR/mesa-bypass-lf.patch"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c \
        "$WORK_DIR/mesa-marker-socket.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c \
        "$WORK_DIR/mesa-marker-winsys.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h \
        "$WORK_DIR/mesa-marker-winsys.h.before"
    cp src/virtio/vtest/vtest_protocol.h \
        "$WORK_DIR/mesa-marker-protocol.h.before"
    git apply --no-index --recount "$WORK_DIR/mesa-marker-input-lf.patch"
    : > "$WORK_DIR/mesa-marker-regenerated.patch"
    append_diff \
        "$WORK_DIR/mesa-marker-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c" \
        "$WORK_DIR/mesa-marker-socket.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c"
    append_diff \
        "$WORK_DIR/mesa-marker-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c" \
        "$WORK_DIR/mesa-marker-winsys.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c"
    append_diff \
        "$WORK_DIR/mesa-marker-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h" \
        "$WORK_DIR/mesa-marker-winsys.h.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h"
    append_diff \
        "$WORK_DIR/mesa-marker-regenerated.patch" \
        "src/virtio/vtest/vtest_protocol.h" \
        "$WORK_DIR/mesa-marker-protocol.h.before" \
        "src/virtio/vtest/vtest_protocol.h"
)
cp "$WORK_DIR/mesa-loopback-regenerated.patch" "$MESA_LOOPBACK_PATCH"
cp "$WORK_DIR/mesa-marker-regenerated.patch" "$MESA_MARKER_PATCH"
(
    cd "$WORK_DIR/mesa-25.2.8"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c \
        "$WORK_DIR/mesa-direct-socket.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c \
        "$WORK_DIR/mesa-direct-winsys.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h \
        "$WORK_DIR/mesa-direct-winsys.h.before"
    cp src/virtio/vtest/vtest_protocol.h \
        "$WORK_DIR/mesa-direct-protocol.h.before"
    git apply --no-index --recount "$WORK_DIR/mesa-direct-input-lf.patch"
    : > "$WORK_DIR/mesa-direct-regenerated.patch"
    append_diff \
        "$WORK_DIR/mesa-direct-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c" \
        "$WORK_DIR/mesa-direct-socket.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_socket.c"
    append_diff \
        "$WORK_DIR/mesa-direct-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c" \
        "$WORK_DIR/mesa-direct-winsys.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c"
    append_diff \
        "$WORK_DIR/mesa-direct-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h" \
        "$WORK_DIR/mesa-direct-winsys.h.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.h"
    append_diff \
        "$WORK_DIR/mesa-direct-regenerated.patch" \
        "src/virtio/vtest/vtest_protocol.h" \
        "$WORK_DIR/mesa-direct-protocol.h.before" \
        "src/virtio/vtest/vtest_protocol.h"
)
cp "$WORK_DIR/mesa-direct-regenerated.patch" "$MESA_DIRECT_PATCH"
(
    cd "$WORK_DIR/mesa-25.2.8"
    cp src/gallium/frontends/dri/drisw.c \
        "$WORK_DIR/mesa-precopy-fence-drisw.c.before"
    cp src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c \
        "$WORK_DIR/mesa-precopy-fence-winsys.c.before"
    git apply --no-index --recount \
        "$WORK_DIR/mesa-precopy-fence-input-lf.patch"
    : > "$WORK_DIR/mesa-precopy-fence-regenerated.patch"
    append_diff \
        "$WORK_DIR/mesa-precopy-fence-regenerated.patch" \
        "src/gallium/frontends/dri/drisw.c" \
        "$WORK_DIR/mesa-precopy-fence-drisw.c.before" \
        "src/gallium/frontends/dri/drisw.c"
    append_diff \
        "$WORK_DIR/mesa-precopy-fence-regenerated.patch" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c" \
        "$WORK_DIR/mesa-precopy-fence-winsys.c.before" \
        "src/gallium/winsys/virgl/vtest/virgl_vtest_winsys.c"
)
cp "$WORK_DIR/mesa-precopy-fence-regenerated.patch" \
    "$MESA_PRECOPY_FENCE_PATCH"

VIRGL_VENDOR="$PROJECT_ROOT/third_party/virglrenderer-1.3.0"
VIRGL_PACKAGE="$PROJECT_ROOT/third_party/termux-packages/packages/virglrenderer-android"
VIRGL_PATCH="$VIRGL_PACKAGE/0011-rlt-host-completion-telemetry.patch.beforehostbuild"
curl -fsSL \
    https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.3.0/virglrenderer-virglrenderer-1.3.0.tar.gz \
    -o "$WORK_DIR/virgl.tar.gz"
tar -xf "$WORK_DIR/virgl.tar.gz" -C "$WORK_DIR"
(
    cd "$WORK_DIR/virglrenderer-virglrenderer-1.3.0"
    # The package's ANGLE/socket-path patch changes vtest_server.c before our
    # project patch is applied. Diff against that effective baseline so the
    # regenerated TCP transport hunks do not overlap or reject.
    git apply --no-index \
        "$VIRGL_PACKAGE/0007-Ensure-EGL-GLES-ANGLE-help-and-socket-path.patch.beforehostbuild"
    cp src/virglrenderer.c "$WORK_DIR/virgl-virglrenderer.c.before"
    cp src/virglrenderer.h "$WORK_DIR/virgl-virglrenderer.h.before"
    cp src/vrend/vrend_decode.c "$WORK_DIR/virgl-vrend_decode.c.before"
    cp src/vrend/vrend_renderer.c "$WORK_DIR/virgl-vrend_renderer.c.before"
    cp src/vrend/vrend_renderer.h "$WORK_DIR/virgl-vrend_renderer.h.before"
    cp vtest/meson.build "$WORK_DIR/virgl-meson.build.before"
    cp vtest/vtest.h "$WORK_DIR/virgl-vtest.h.before"
    cp vtest/vtest_protocol.h "$WORK_DIR/virgl-vtest_protocol.h.before"
    cp vtest/vtest_renderer.c "$WORK_DIR/virgl-vtest_renderer.c.before"
    cp vtest/vtest_server.c "$WORK_DIR/virgl-vtest_server.c.before"
    cp "$VIRGL_VENDOR/src/virglrenderer.c" src/virglrenderer.c
    cp "$VIRGL_VENDOR/src/virglrenderer.h" src/virglrenderer.h
    cp "$VIRGL_VENDOR/src/vrend/vrend_decode.c" src/vrend/vrend_decode.c
    cp "$VIRGL_VENDOR/src/vrend/vrend_renderer.c" src/vrend/vrend_renderer.c
    cp "$VIRGL_VENDOR/src/vrend/vrend_renderer.h" src/vrend/vrend_renderer.h
    cp "$VIRGL_VENDOR/vtest/meson.build" vtest/meson.build
    cp "$VIRGL_VENDOR/vtest/rlt_ahb_frame_stream.c" vtest/rlt_ahb_frame_stream.c
    cp "$VIRGL_VENDOR/vtest/rlt_ahb_frame_stream.h" vtest/rlt_ahb_frame_stream.h
    cp "$VIRGL_VENDOR/vtest/rlt_frame_stream.c" vtest/rlt_frame_stream.c
    cp "$VIRGL_VENDOR/vtest/rlt_frame_stream.h" vtest/rlt_frame_stream.h
    cp "$VIRGL_VENDOR/vtest/vtest.h" vtest/vtest.h
    cp "$VIRGL_VENDOR/vtest/vtest_protocol.h" vtest/vtest_protocol.h
    cp "$VIRGL_VENDOR/vtest/vtest_renderer.c" vtest/vtest_renderer.c
    cp "$VIRGL_VENDOR/vtest/vtest_server.c" vtest/vtest_server.c
    cp "$VIRGL_VENDOR/vtest/rlt_host_telemetry.c" vtest/rlt_host_telemetry.c
    cp "$VIRGL_VENDOR/vtest/rlt_host_telemetry.h" vtest/rlt_host_telemetry.h
    : > "$WORK_DIR/virgl-regenerated.patch"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "src/vrend/vrend_decode.c" \
        "$WORK_DIR/virgl-vrend_decode.c.before" \
        "src/vrend/vrend_decode.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "src/virglrenderer.c" \
        "$WORK_DIR/virgl-virglrenderer.c.before" \
        "src/virglrenderer.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "src/virglrenderer.h" \
        "$WORK_DIR/virgl-virglrenderer.h.before" \
        "src/virglrenderer.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "src/vrend/vrend_renderer.c" \
        "$WORK_DIR/virgl-vrend_renderer.c.before" \
        "src/vrend/vrend_renderer.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "src/vrend/vrend_renderer.h" \
        "$WORK_DIR/virgl-vrend_renderer.h.before" \
        "src/vrend/vrend_renderer.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/meson.build" \
        "$WORK_DIR/virgl-meson.build.before" \
        "vtest/meson.build"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_ahb_frame_stream.c" \
        "/dev/null" \
        "vtest/rlt_ahb_frame_stream.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_ahb_frame_stream.h" \
        "/dev/null" \
        "vtest/rlt_ahb_frame_stream.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_frame_stream.c" \
        "/dev/null" \
        "vtest/rlt_frame_stream.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_frame_stream.h" \
        "/dev/null" \
        "vtest/rlt_frame_stream.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_host_telemetry.c" \
        "/dev/null" \
        "vtest/rlt_host_telemetry.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/rlt_host_telemetry.h" \
        "/dev/null" \
        "vtest/rlt_host_telemetry.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/vtest.h" \
        "$WORK_DIR/virgl-vtest.h.before" \
        "vtest/vtest.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/vtest_protocol.h" \
        "$WORK_DIR/virgl-vtest_protocol.h.before" \
        "vtest/vtest_protocol.h"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/vtest_renderer.c" \
        "$WORK_DIR/virgl-vtest_renderer.c.before" \
        "vtest/vtest_renderer.c"
    append_diff \
        "$WORK_DIR/virgl-regenerated.patch" \
        "vtest/vtest_server.c" \
        "$WORK_DIR/virgl-vtest_server.c.before" \
        "vtest/vtest_server.c"
)
cp "$WORK_DIR/virgl-regenerated.patch" "$VIRGL_PATCH"

python3 - "$MESA_PATCH" "$MESA_LOOPBACK_PATCH" "$MESA_MARKER_PATCH" \
    "$MESA_DIRECT_PATCH" "$MESA_PRECOPY_FENCE_PATCH" "$VIRGL_PATCH" <<'PY'
from pathlib import Path
import sys

for value in sys.argv[1:]:
    path = Path(value)
    data = path.read_bytes()
    if b"\r" in data:
        raise SystemExit(f"CR byte found in regenerated patch: {path}")
PY

printf 'mesa_sha256=%s\n' "$(sha256sum "$MESA_PATCH" | awk '{print $1}')"
printf 'mesa_loopback_sha256=%s\n' \
    "$(sha256sum "$MESA_LOOPBACK_PATCH" | awk '{print $1}')"
printf 'mesa_marker_sha256=%s\n' \
    "$(sha256sum "$MESA_MARKER_PATCH" | awk '{print $1}')"
printf 'mesa_direct_sha256=%s\n' \
    "$(sha256sum "$MESA_DIRECT_PATCH" | awk '{print $1}')"
printf 'mesa_precopy_fence_sha256=%s\n' \
    "$(sha256sum "$MESA_PRECOPY_FENCE_PATCH" | awk '{print $1}')"
printf 'virgl_sha256=%s\n' "$(sha256sum "$VIRGL_PATCH" | awk '{print $1}')"
