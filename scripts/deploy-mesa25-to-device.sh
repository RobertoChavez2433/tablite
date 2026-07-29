#!/usr/bin/env bash
# Deploy a locally-built Mesa .deb to the test device and verify the
# glxinfo regression fix.
#
# Usage: scripts/deploy-mesa25-to-device.sh [serial] [mesa-deb]
#
# Assumes:
#   - third_party/termux-packages/output/ contains mesa_<ver>_aarch64.deb
#   - adb is on PATH
#   - device has Termux installed with com.termux uid

set -euo pipefail

SERIAL="${1:-R52X90378YB}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEBS_DIR="$REPO_ROOT/third_party/termux-packages/output"
REQUESTED_MESA_DEB="${2:-${MESA_DEB:-}}"

if [ -n "$REQUESTED_MESA_DEB" ]; then
    MESA_DEB="$(cd "$(dirname "$REQUESTED_MESA_DEB")" && pwd)/$(basename "$REQUESTED_MESA_DEB")"
    if [ ! -f "$MESA_DEB" ]; then
        echo "ERROR: requested Mesa deb not found: $MESA_DEB" >&2
        exit 1
    fi
elif [ ! -d "$DEBS_DIR" ]; then
    echo "ERROR: $DEBS_DIR not found — run ./scripts/run-docker.sh ./build-package.sh -a aarch64 mesa inside third_party/termux-packages first" >&2
    exit 1
else
    mapfile -t MESA_DEBS < <(find "$DEBS_DIR" -maxdepth 1 -type f -name 'mesa_*_aarch64.deb' -print)
    if [ "${#MESA_DEBS[@]}" -ne 1 ]; then
        echo "ERROR: expected exactly one mesa_*_aarch64.deb in $DEBS_DIR, found ${#MESA_DEBS[@]}." >&2
        echo "Pass the exact package path as the second argument." >&2
        exit 1
    fi
    MESA_DEB="${MESA_DEBS[0]}"
fi

echo "Found deb: $MESA_DEB"

# Git-Bash: adb.exe is a Windows binary and can't stat /c/... paths from MSYS.
# Convert to a native Windows path when running on MINGW/MSYS/CYGWIN.
UNAME=$(uname)
if [[ "$UNAME" == MINGW* || "$UNAME" == MSYS* || "$UNAME" == CYGWIN* ]]; then
    MESA_DEB_WIN=$(cygpath -w "$MESA_DEB")
else
    MESA_DEB_WIN="$MESA_DEB"
fi

# Push to /sdcard and copy into Termux home via run-as (run-as can read /sdcard
# under its own uid but cannot directly accept adb push into its sandbox).
MSYS2_ARG_CONV_EXCL='*' adb -s "$SERIAL" push "$MESA_DEB_WIN" /sdcard/mesa-rebuild.deb

adb -s "$SERIAL" shell "run-as com.termux cp /sdcard/mesa-rebuild.deb /data/data/com.termux/files/home/mesa-rebuild.deb"

# Before install: kill any running RL + X11 so Mesa's files can be overwritten.
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true"
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'launch-runelite-native' 2>/dev/null || true"
adb -s "$SERIAL" shell "run-as com.termux pkill -f 'virgl_test_server' 2>/dev/null || true"
adb -s "$SERIAL" shell "am force-stop com.runelitetablet"
adb -s "$SERIAL" shell "am force-stop com.termux.x11"
sleep 2

# Install with dpkg, allowing downgrade.
adb -s "$SERIAL" shell "run-as com.termux sh -c '
export PREFIX=/data/data/com.termux/files/usr
export PATH=\$PREFIX/bin:\$PATH
dpkg -i --force-downgrade /data/data/com.termux/files/home/mesa-rebuild.deb
'"

# Verify new version.
adb -s "$SERIAL" shell "run-as com.termux sh -c '
export PREFIX=/data/data/com.termux/files/usr
export PATH=\$PREFIX/bin:\$PATH
pkg list-installed 2>/dev/null | grep ^mesa
'"

echo ""
echo "Mesa deb installed. Now relaunch RuneLiteTablet and check client.log for"
echo "\"Error starting GPU plugin\" -> if gone, GLX handshake is fixed."
