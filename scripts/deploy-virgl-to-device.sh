#!/usr/bin/env bash
# Install one exact project-built virglrenderer-android package after proving
# that RuneLite, X11, and VirGL are stopped.
#
# Usage: scripts/deploy-virgl-to-device.sh [serial] <virgl-deb>

set -euo pipefail

SERIAL="${1:-R52X90378YB}"
VIRGL_DEB="${2:-}"

if [ -z "$VIRGL_DEB" ] || [ ! -f "$VIRGL_DEB" ]; then
    echo "ERROR: pass an existing virglrenderer-android .deb as the second argument" >&2
    exit 1
fi
case "$VIRGL_DEB" in
    *.deb) ;;
    *) echo "ERROR: requested package is not a .deb: $VIRGL_DEB" >&2; exit 1 ;;
esac

if adb -s "$SERIAL" shell \
    "ps -A -o ARGS | grep -E 'net.runelite.client.RuneLite|virgl_test_server|com.termux.x11.Loader' | grep -v grep" |
    grep -q .; then
    echo "ERROR: RuneLite/X11/VirGL is still active; stop the production session first" >&2
    exit 1
fi

UNAME=$(uname)
if [[ "$UNAME" == MINGW* || "$UNAME" == MSYS* || "$UNAME" == CYGWIN* ]]; then
    VIRGL_DEB_NATIVE=$(cygpath -w "$VIRGL_DEB")
else
    VIRGL_DEB_NATIVE="$VIRGL_DEB"
fi

MSYS2_ARG_CONV_EXCL='*' adb -s "$SERIAL" push \
    "$VIRGL_DEB_NATIVE" /sdcard/virglrenderer-rlt-host.deb
adb -s "$SERIAL" shell \
    "run-as com.termux cp /sdcard/virglrenderer-rlt-host.deb /data/data/com.termux/files/home/virglrenderer-rlt-host.deb"
adb -s "$SERIAL" shell "run-as com.termux sh -c '
export PREFIX=/data/data/com.termux/files/usr
export PATH=\$PREFIX/bin:\$PATH
dpkg -i --force-downgrade /data/data/com.termux/files/home/virglrenderer-rlt-host.deb
'"

adb -s "$SERIAL" shell \
    "run-as com.termux /data/data/com.termux/files/usr/bin/grep -a -m 1 -o RLT_VIRGL_HOST_V1 /data/data/com.termux/files/usr/bin/virgl_test_server_android"

adb -s "$SERIAL" shell rm -f /sdcard/virglrenderer-rlt-host.deb
adb -s "$SERIAL" shell \
    "run-as com.termux rm -f /data/data/com.termux/files/home/virglrenderer-rlt-host.deb"

echo "VirGL host telemetry package installed and marker verified."
