#!/data/data/com.termux/files/usr/bin/bash
# launch-runelite-native.sh — Phase 2.1 native-Termux RuneLite launcher.
#
# This is the proot-free path. It bypasses proot-distro entirely, runs the
# JVM directly under Termux-native openjdk-21, and uses our Bionic-rebuilt
# rlawt-1.8-bionic.jar (see Task 21 spec).
#
# Goal: eliminate proot's ptrace syscall-interception (S77 diagnosis) so the
# RuneLite Client thread is no longer capped at ~2000 syscalls/s.
#
# Gated by RLT_NATIVE_TERMUX=1. If the env flag is not set, this script
# refuses to run so it can coexist with launch-runelite.sh during Phase 2.2
# service-selector wiring.
#
# Phase 2.1 exit: JVM starts under Termux-native openjdk-21 without proot,
# loads net.runelite.client.RuneLite, and Bionic dlopen resolves librlawt.so
# NEEDED libs (libjawt, libGL.so.1, libGLX.so.0, libX11.so, libm, libdl, libc).
# See spec .claude/specs/2026-04-17-phase-2.1-native-launch-spec.md.

set -uo pipefail

# ===================================================================
# Gate + env bootstrap
# ===================================================================
if [ "${RLT_NATIVE_TERMUX:-0}" != "1" ]; then
    echo "ERROR: launch-runelite-native.sh requires RLT_NATIVE_TERMUX=1" >&2
    echo "       This is the proot-free path. Use launch-runelite.sh for the proot path." >&2
    exit 2
fi

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -f "$TERMUX_PREFIX/etc/profile" ]; then
    # shellcheck disable=SC1091
    . "$TERMUX_PREFIX/etc/profile"
fi
export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"

# Shared capture contract. The app normally supplies these values; direct
# shell launches get safe defaults so every artifact still has one identity.
RLT_CAPTURE_ID="${RLT_CAPTURE_ID:-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || printf 'manual-%s-%s' "$(date +%s)" "$$")}"
RLT_TELEMETRY_MODE="${RLT_TELEMETRY_MODE:-SUMMARY}"
RLT_TARGET_HZ="${RLT_TARGET_HZ:-120}"
RLT_RENDERER_BACKEND="${RLT_RENDERER_BACKEND:-native_x11_virgl_gpu}"
RLT_CAPTURE_DIR="${RLT_CAPTURE_DIR:-$HOME/.rlt/captures/$RLT_CAPTURE_ID}"
export RLT_CAPTURE_ID RLT_TELEMETRY_MODE RLT_RENDERER_BACKEND RLT_TARGET_HZ RLT_CAPTURE_DIR
if [[ -n "${VTEST_TCP_PORT:-}" ]]; then
    if [[ ! "$VTEST_TCP_PORT" =~ ^[0-9]+$ ]] ||
        (( VTEST_TCP_PORT < 1 || VTEST_TCP_PORT > 65535 )); then
        echo "ERROR: VTEST_TCP_PORT must be an integer in 1..65535" >&2
        exit 2
    fi
    RLT_VTEST_TRANSPORT="tcp_loopback_legacy_in_band"
    export VTEST_TCP_PORT
else
    RLT_VTEST_TRANSPORT="unix_v3"
    unset VTEST_TCP_PORT
fi
export RLT_VTEST_TRANSPORT
if [[ "${RLT_APP_VIRGL_HOST:-0}" == "1" && -z "${VTEST_TCP_PORT:-}" ]]; then
    echo "ERROR: RLT_APP_VIRGL_HOST=1 requires VTEST_TCP_PORT" >&2
    exit 2
fi
export RLT_APP_VIRGL_HOST="${RLT_APP_VIRGL_HOST:-0}"
export RLT_APP_VIRGL_AHB="${RLT_APP_VIRGL_AHB:-0}"
if [[ "$RLT_APP_VIRGL_AHB" == "1" && "$RLT_APP_VIRGL_HOST" != "1" ]]; then
    echo "ERROR: RLT_APP_VIRGL_AHB=1 requires RLT_APP_VIRGL_HOST=1" >&2
    exit 2
fi
if [[ -n "${RLT_FRAME_TCP_PORT:-}" || "$RLT_APP_VIRGL_AHB" == "1" ]]; then
    if [[ -n "${RLT_FRAME_TCP_PORT:-}" ]]; then
        if [[ ! "$RLT_FRAME_TCP_PORT" =~ ^[0-9]+$ ]] ||
            (( RLT_FRAME_TCP_PORT < 1 || RLT_FRAME_TCP_PORT > 65535 )); then
            echo "ERROR: RLT_FRAME_TCP_PORT must be an integer in 1..65535" >&2
            exit 2
        fi
    fi
    if [[ "$RLT_APP_VIRGL_AHB" == "1" ]]; then
        RLT_FRAME_STREAM_TRANSPORT="unix_seqpacket_ahb_completed_display_target"
    else
        RLT_FRAME_STREAM_TRANSPORT="tcp_loopback_completed_display_target"
    fi
    RLT_FRAME_STREAM_BYPASS_X11_DISPLAY="${RLT_FRAME_STREAM_BYPASS_X11_DISPLAY:-1}"
    if [[ "$RLT_FRAME_STREAM_BYPASS_X11_DISPLAY" == "1" ]]; then
        RLT_FRAME_STREAM_DISPLAY_POLICY="bypassed_x11"
    else
        RLT_FRAME_STREAM_DISPLAY_POLICY="mirrored_x11_login_gate"
    fi
    RLT_FRAME_STREAM_MESA_ROOT="$PREFIX/opt/mesa-rlt-frame-stream"
    if [[ -f "$RLT_FRAME_STREAM_MESA_ROOT/lib/dri/swrast_dri.so" &&
          -f "$RLT_FRAME_STREAM_MESA_ROOT/lib/libgallium-25.2.8.so" ]]; then
        export LIBGL_DRIVERS_PATH="$RLT_FRAME_STREAM_MESA_ROOT/lib/dri"
        RLT_FRAME_STREAM_MESA_DRIVER="project_frame_stream"
    else
        unset LIBGL_DRIVERS_PATH
        RLT_FRAME_STREAM_MESA_DRIVER="system_missing_project_driver"
    fi
    export RLT_FRAME_STREAM_BYPASS_X11_DISPLAY
    if [[ -n "${RLT_FRAME_TCP_PORT:-}" ]]; then
        export RLT_FRAME_TCP_PORT
    else
        unset RLT_FRAME_TCP_PORT
    fi
else
    RLT_FRAME_STREAM_TRANSPORT="disabled"
    RLT_FRAME_STREAM_DISPLAY_POLICY="x11"
    RLT_FRAME_STREAM_MESA_DRIVER="system"
    unset RLT_FRAME_TCP_PORT
    unset RLT_FRAME_STREAM_BYPASS_X11_DISPLAY
    unset LIBGL_DRIVERS_PATH
fi
export RLT_FRAME_STREAM_TRANSPORT RLT_FRAME_STREAM_DISPLAY_POLICY RLT_FRAME_STREAM_MESA_DRIVER
case "$RLT_RENDERER_BACKEND" in
    # direct_virgl_ahb is the completed-display-target frame stream:
    # standard rlawt/GLX produces into Mesa virpipe, while app-owned VirGL
    # presents through same-UID AHardwareBuffers. Only direct_zink_ahb retains
    # the older rlawt producer-to-consumer socket.
    direct_zink_ahb) export RLT_DIRECT_SURFACE=1 ;;
    *) unset RLT_DIRECT_SURFACE ;;
esac
mkdir -p "$RLT_CAPTURE_DIR"

# Versioned, run-scoped producer identity page shared by rlawt and Mesa's
# virpipe/vtest client. Full per-stage guest/host timing is intentionally
# BURST/DEEP-only: the external FrameTimeline overhead campaign proved that
# enabling it in steady SUMMARY adds more than one 120 Hz interval at p95.
# SUMMARY retains bounded producer/X11 observability; OFF remains dormant.
# The page contains sequence/timestamp metadata only—never credentials,
# client logs, input, or gameplay data.
if [[ "$RLT_TELEMETRY_MODE" == "BURST" || "$RLT_TELEMETRY_MODE" == "DEEP" ]]; then
    RLT_VTEST_TELEMETRY_PATH="$RLT_CAPTURE_DIR/rlt-frame-telemetry-v1.shm"
    umask 077
    truncate -s 64 "$RLT_VTEST_TELEMETRY_PATH"
    export RLT_VTEST_TELEMETRY_PATH
    RLT_VTEST_STAGE_TELEMETRY="enabled_burst_deep"
    RLT_TELEMETRY_SELF_PROFILE="1"
else
    unset RLT_VTEST_TELEMETRY_PATH
    RLT_VTEST_STAGE_TELEMETRY="disabled_off_summary"
    RLT_TELEMETRY_SELF_PROFILE="0"
fi
export RLT_VTEST_STAGE_TELEMETRY RLT_TELEMETRY_SELF_PROFILE

LOGFILE="$HOME/runelite-native.log"
CLASSLOAD_LOG="$HOME/native-launch-classload.log"
echo "=== Native RuneLite launch $(date) ===" | tee "$LOGFILE"
printf '%s\n' \
    "capture_id=$RLT_CAPTURE_ID" \
    "telemetry_mode=$RLT_TELEMETRY_MODE" \
    "renderer_backend=$RLT_RENDERER_BACKEND" \
    "target_hz=$RLT_TARGET_HZ" \
    "capture_dir=$RLT_CAPTURE_DIR" \
    "vtest_transport=$RLT_VTEST_TRANSPORT" \
    "vtest_tcp_port=${VTEST_TCP_PORT:-none}" \
    "app_virgl_host=$RLT_APP_VIRGL_HOST" \
    "app_virgl_ahb=$RLT_APP_VIRGL_AHB" \
    "frame_stream_transport=$RLT_FRAME_STREAM_TRANSPORT" \
    "frame_stream_tcp_port=${RLT_FRAME_TCP_PORT:-none}" \
    "frame_stream_display_policy=$RLT_FRAME_STREAM_DISPLAY_POLICY" \
    "frame_stream_mesa_driver=$RLT_FRAME_STREAM_MESA_DRIVER" \
    "vtest_stage_telemetry=$RLT_VTEST_STAGE_TELEMETRY" \
    "telemetry_self_profile=$RLT_TELEMETRY_SELF_PROFILE" \
    > "$RLT_CAPTURE_DIR/launcher-context.txt"
echo "CAPTURE: id=$RLT_CAPTURE_ID mode=$RLT_TELEMETRY_MODE backend=$RLT_RENDERER_BACKEND target_hz=$RLT_TARGET_HZ vtest_transport=$RLT_VTEST_TRANSPORT vtest_tcp_port=${VTEST_TCP_PORT:-none} app_virgl_host=$RLT_APP_VIRGL_HOST app_virgl_ahb=$RLT_APP_VIRGL_AHB frame_stream_transport=$RLT_FRAME_STREAM_TRANSPORT frame_stream_tcp_port=${RLT_FRAME_TCP_PORT:-none} frame_stream_display_policy=$RLT_FRAME_STREAM_DISPLAY_POLICY frame_stream_mesa_driver=$RLT_FRAME_STREAM_MESA_DRIVER vtest_stage_telemetry=$RLT_VTEST_STAGE_TELEMETRY telemetry_self_profile=$RLT_TELEMETRY_SELF_PROFILE dir=$RLT_CAPTURE_DIR" | tee -a "$LOGFILE"

# Optional env-file argument (parity with launch-runelite.sh). LaunchCoordinator
# writes Jagex auth env to $HOME/.rlt-launch-env.sh and passes its path as $1.
# We source it and then delete it to avoid leaving credentials on disk.
NATIVE_ENV_FILE="${1:-}"
if [ -n "$NATIVE_ENV_FILE" ] && [ -f "$NATIVE_ENV_FILE" ]; then
    echo "Sourcing credentials from env file..." | tee -a "$LOGFILE"
    # shellcheck disable=SC1090
    source "$NATIVE_ENV_FILE"
    rm -f "$NATIVE_ENV_FILE"
    [ -n "${JX_SESSION_ID:-}" ] && echo "  JX_SESSION_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_CHARACTER_ID:-}" ] && echo "  JX_CHARACTER_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_DISPLAY_NAME:-}" ] && echo "  JX_DISPLAY_NAME=***" | tee -a "$LOGFILE"
else
    echo "No credentials env file provided — RuneLite will show its own login" | tee -a "$LOGFILE"
fi

# Same cpuset-at-entry logging as the proot launcher, so we can diff.
rlt_log_cpuset() {
    local label="$1" pid="$2"
    local file="/proc/$pid/cpuset"
    local cset
    cset=$(cat "$file" 2>/dev/null || echo "<unreadable>")
    local line="CPUSET $label pid=$pid cpuset=$cset"
    echo "$line" | tee -a "$LOGFILE" 2>/dev/null || true
    if [ -x /system/bin/log ]; then
        /system/bin/log -t RLT -p i "$line" 2>/dev/null || true
    fi
}
rlt_log_cpuset "native-launch-entry" "$$"

# ===================================================================
# Locking (distinct from proot launcher so both paths can coexist)
# ===================================================================
LAUNCH_LOCK_DIR="$PREFIX/tmp/.rlt-launch-native.lock"
LAUNCH_LOCK_PID_FILE="$LAUNCH_LOCK_DIR/pid"

acquire_launch_lock() {
    if mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null; then
        echo "$$" > "$LAUNCH_LOCK_PID_FILE"
        return 0
    fi
    if [ -f "$LAUNCH_LOCK_PID_FILE" ]; then
        local existing_pid existing_cmdline
        existing_pid="$(cat "$LAUNCH_LOCK_PID_FILE" 2>/dev/null || true)"
        # Issue #55: `kill -0 $pid` alone false-positives under Android PID recycling
        # (S80: virgl_test_server_android reused a stale launcher PID and blocked
        # every re-launch). Verify /proc/$pid/cmdline actually names *this* script
        # before treating the lock as live.
        if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
            existing_cmdline="$(tr '\0' ' ' < "/proc/$existing_pid/cmdline" 2>/dev/null || true)"
            case "$existing_cmdline" in
                *launch-runelite-native*)
                    echo "Another native RuneLite launch is already in progress (PID $existing_pid cmdline=$existing_cmdline)" | tee -a "$LOGFILE"
                    exit 0
                    ;;
                *)
                    echo "Stale native-launch lock: PID $existing_pid was recycled (cmdline='$existing_cmdline') — reclaiming lock" | tee -a "$LOGFILE"
                    ;;
            esac
        fi
    fi
    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
    mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null && echo "$$" > "$LAUNCH_LOCK_PID_FILE"
}
release_launch_lock() {
    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
}

acquire_launch_lock

# ===================================================================
# Paths & constants
# ===================================================================
ROOTFS_PATH="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
RL_DOT_DIR="$ROOTFS_PATH/root/.runelite"
RL_REPO_DIR="$RL_DOT_DIR/repository2"
RL_PROFILE_DIR="$RL_DOT_DIR/profiles2"
# APK-deployed location for the Bionic rlawt jar.
# Set RLT_RLAWT_BIONIC_JAR to override (e.g. during ad-hoc adb push testing).
#
# RLT_DIRECT_SURFACE=1 swaps to the EGL-on-AHardwareBuffer drop-in JAR which
# replaces the X11/GLX path with a direct connection to RlawtSurfaceServer
# in com.runelitetablet (DirectSurfaceHostActivity must be running). Build
# with `bash scripts/build-rlawt-bionic.sh --direct-surface` and deploy the
# resulting jar to $HOME/.rlt/rlawt-1.8-direct-surface.jar.
if [[ "${RLT_DIRECT_SURFACE:-0}" == "1" ]]; then
    RLAWT_BIONIC_JAR="${RLT_RLAWT_BIONIC_JAR:-$HOME/.rlt/rlawt-1.8-direct-surface.jar}"
    echo "[direct-surface] using JAR=$RLAWT_BIONIC_JAR" >&2
else
    RLAWT_BIONIC_JAR="${RLT_RLAWT_BIONIC_JAR:-$HOME/.rlt/rlawt-1.8-bionic.jar}"
fi

JAVA_HOME_NATIVE="$PREFIX/lib/jvm/java-21-openjdk"
JAVA_BIN="$JAVA_HOME_NATIVE/bin/java"
# Bionic dlopen does not honor -Djava.library.path for resolving NEEDED libs.
# This is the runtime requirement from Task 21 Review Note #4.
NATIVE_LD_LIBRARY_PATH="$JAVA_HOME_NATIVE/lib:$PREFIX/lib"
if [[ "$RLT_FRAME_STREAM_MESA_DRIVER" == "project_frame_stream" ]]; then
    NATIVE_LD_LIBRARY_PATH="$PREFIX/opt/mesa-rlt-frame-stream/lib:$NATIVE_LD_LIBRARY_PATH"
fi

export TERMUX_X11_BIN="$PREFIX/bin/termux-x11"
export TERMUX_X11_PREF_BIN="$PREFIX/bin/termux-x11-preference"
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
X11_HOST_COMPONENT="com.runelitetablet/.presentation.hybrid.HybridX11HostActivity"
X11_OVERRIDE_PACKAGE="com.runelitetablet"
export TERMUX_X11_FORCE_FLIP=1

# PIDs we own (for EXIT trap)
VIRGL_PID=""
X11_PID=""
JAVA_PID=""
OPENBOX_PID=""
PERF_SAMPLER_PID=""
CPUSET_SETTLE_PID=""
AFFINITY_TIMELINE_PID=""
THREAD_CPU_SAMPLER_PID=""
X11_WINDOW_PROBE_PID=""
RLAWT_RATE_TAIL_PID=""
TELEMETRY_BRIDGE_MONITOR_PID=""

# ===================================================================
# Cleanup
# ===================================================================
find_pids_by_cmdline() {
    local pattern="$1" proc cmdline
    for proc in /proc/[0-9]*; do
        [ -r "$proc/cmdline" ] || continue
        cmdline="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
        case "$cmdline" in
            *"$pattern"*) basename "$proc" ;;
        esac
    done
}

stop_termux_x11() {
    local pid
    for pid in $(find_pids_by_cmdline 'com.termux.x11.Loader') \
               $(find_pids_by_cmdline 'com.termux.x11.CmdEntryPoint') \
               $(find_pids_by_cmdline 'termux-x11'); do
        kill "$pid" 2>/dev/null || true
    done
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true
    rm -f "$TMPDIR/.tX0-lock" 2>/dev/null || true
    rm -f "$TMPDIR/.X0-lock" 2>/dev/null || true
    rm -f "$X11_SOCKET_DIR/X0" 2>/dev/null || true
}

cleanup_previous() {
    echo "Cleaning up previous native-launch state..." | tee -a "$LOGFILE"
    pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null | xargs -r kill 2>/dev/null || true
    # Don't kill virgl if the external-virgl sentinel is active (we'd adopt it below).
    if [ ! -f "$HOME/.rlt-external-virgl" ]; then
        pkill -f 'virgl_test_server' 2>/dev/null || true
        rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true
    fi
    stop_termux_x11
    sleep 0.5
}

cleanup_on_exit() {
    echo "" | tee -a "$LOGFILE"
    echo "=== Native launch exit $(date) ===" | tee -a "$LOGFILE"
    # Emit the rlawt CSV summary BEFORE we kill samplers so the verdict line
    # lands in $LOGFILE next to the affinity-timeline / thread-cpu CSVs the
    # caller is about to pull.
    if declare -F summarize_rlawt_swap_gap >/dev/null 2>&1; then
        summarize_rlawt_swap_gap
    fi
    [ -n "${AFFINITY_TIMELINE_PID:-}" ] && kill "$AFFINITY_TIMELINE_PID" 2>/dev/null || true
    [ -n "${THREAD_CPU_SAMPLER_PID:-}" ] && kill "$THREAD_CPU_SAMPLER_PID" 2>/dev/null || true
    [ -n "${X11_WINDOW_PROBE_PID:-}" ] && kill "$X11_WINDOW_PROBE_PID" 2>/dev/null || true
    [ -n "${PERF_SAMPLER_PID:-}" ] && kill "$PERF_SAMPLER_PID" 2>/dev/null || true
    [ -n "${CPUSET_SETTLE_PID:-}" ] && kill "$CPUSET_SETTLE_PID" 2>/dev/null || true
    [ -n "${RLAWT_RATE_TAIL_PID:-}" ] && kill "$RLAWT_RATE_TAIL_PID" 2>/dev/null || true
    [ -n "${TELEMETRY_BRIDGE_MONITOR_PID:-}" ] && kill "$TELEMETRY_BRIDGE_MONITOR_PID" 2>/dev/null || true
    [ -n "${JAVA_PID:-}" ] && kill "$JAVA_PID" 2>/dev/null || true
    [ -n "${OPENBOX_PID:-}" ] && kill "$OPENBOX_PID" 2>/dev/null || true
    [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null || true
    [ -n "${X11_PID:-}" ] && kill "$X11_PID" 2>/dev/null || true
    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
    pkill -f '^openbox' 2>/dev/null || true
    pkill -f 'virgl_test_server' 2>/dev/null || true
    stop_termux_x11
    rm -f "$HOME/.rlt-native.pid" 2>/dev/null || true
    # Tear down the session-alive sentinel so the next health-check poll flips
    # SessionHealthMonitor to Stopped cleanly (the same contract launch-runelite.sh
    # honors at line 695/1160 / shutdown-session.sh:128).
    if [ -f "$PREFIX/tmp/.rlt-session-alive" ]; then
        rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true
        echo "SESSION-SENTINEL: removed $PREFIX/tmp/.rlt-session-alive on cleanup" | tee -a "$LOGFILE"
    fi
    release_launch_lock
    echo "Native launch cleanup complete" | tee -a "$LOGFILE"
}
trap cleanup_on_exit EXIT

cleanup_previous

# ===================================================================
# Preflight — fail fast with specific error messages
# ===================================================================
if [ ! -x "$JAVA_BIN" ]; then
    echo "ERROR: Termux openjdk-21 missing at $JAVA_BIN" | tee -a "$LOGFILE"
    echo "       Install with: pkg install openjdk-21" | tee -a "$LOGFILE"
    exit 1
fi
if [ ! -f "$RLAWT_BIONIC_JAR" ]; then
    echo "ERROR: Bionic rlawt jar missing at $RLAWT_BIONIC_JAR" | tee -a "$LOGFILE"
    echo "       Deploy runelite-tablet/app/libs/rlawt-1.8-bionic.jar to \$HOME/.rlt/ before running." | tee -a "$LOGFILE"
    exit 1
fi
if [ ! -d "$RL_REPO_DIR" ]; then
    echo "ERROR: RuneLite repository2 dir missing at $RL_REPO_DIR" | tee -a "$LOGFILE"
    echo "       Run the proot launcher at least once to populate it, or restore .runelite from backup." | tee -a "$LOGFILE"
    exit 1
fi

echo "Preflight OK:" | tee -a "$LOGFILE"
echo "  java=$JAVA_BIN" | tee -a "$LOGFILE"
echo "  rlawt-bionic-jar=$RLAWT_BIONIC_JAR ($(stat -c%s "$RLAWT_BIONIC_JAR" 2>/dev/null) bytes)" | tee -a "$LOGFILE"
echo "  rl-repo=$RL_REPO_DIR ($(ls "$RL_REPO_DIR"/*.jar 2>/dev/null | wc -l) jars)" | tee -a "$LOGFILE"

# ===================================================================
# Patch LWJGL liblwjgl.so for Bionic. The LWJGL native jar in repository2
# is glibc-linked; Bionic's loader rejects it with either libpthread.so.0
# not found or a DT_VERNEED/DT_NEEDED mismatch for ld-linux-aarch64.so.1.
# patch-lwjgl-bionic.sh is idempotent — skips if already patched. RuneLite's
# auto-updater re-installs the upstream jar on version bump, so we re-run
# on every launch to keep the GPU plugin working across RL updates.
# ===================================================================
PATCH_LWJGL_SCRIPT="$HOME/scripts/patch-lwjgl-bionic.sh"
if [ -x "$PATCH_LWJGL_SCRIPT" ]; then
    echo "Running LWJGL Bionic patch..." | tee -a "$LOGFILE"
    if ! bash "$PATCH_LWJGL_SCRIPT" 2>&1 | tee -a "$LOGFILE"; then
        echo "WARNING: LWJGL Bionic patch failed — GPU plugin will fail to start" | tee -a "$LOGFILE"
    fi
else
    echo "WARNING: $PATCH_LWJGL_SCRIPT missing or not executable — GPU plugin will fail to start" | tee -a "$LOGFILE"
fi

# ===================================================================
# Start PulseAudio (minimal — RL needs an audio endpoint or it logs noisily)
# ===================================================================
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
        --exit-idle-time=-1 2>&1 | tee -a "$LOGFILE" || true
    export PULSE_SERVER=tcp:127.0.0.1:4713
else
    echo "PulseAudio not installed — RL will use null audio backend" | tee -a "$LOGFILE"
    export PULSE_SERVER=""
fi

# ===================================================================
# Start Termux:X11 (identical to proot launcher)
# ===================================================================
if [ ! -x "$TERMUX_X11_BIN" ]; then
    echo "ERROR: Termux:X11 launcher missing at $TERMUX_X11_BIN" | tee -a "$LOGFILE"
    exit 1
fi
export TERMUX_X11_OVERRIDE_PACKAGE="$X11_OVERRIDE_PACKAGE"
mkdir -p "$X11_SOCKET_DIR"

# IMPORTANT: the Termux:X11 server reads its resolution pref ONCE at startup; changing
# `displayResolutionMode` after the server is up does nothing. A prior proot-software
# run (which sets 1480x924 half-res) leaves the custom-mode pref behind, so the native
# launcher MUST reset to `native` BEFORE forking the X server. Otherwise RuneLite's
# AWT screen bounds report 1480x924 and the whole frame renders small.
# `adjustResolution:true` asks Termux:X11 to resize the X canvas to the surface size
# whenever the activity's window changes — without it, `native` mode falls back to a
# DP-based half-res on HiDPI displays (2960×1848 physical ⇒ 1480×924 DP).
if [ -x "$TERMUX_X11_PREF_BIN" ]; then
    timeout 5 "$TERMUX_X11_PREF_BIN" displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" adjustResolution:true 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" fullscreen:true 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" showAdditionalKbd:false 2>&1 | tee -a "$LOGFILE" || true
fi

"$TERMUX_X11_BIN" :0 &
X11_PID=$!
echo "X11 launch pid=$X11_PID" | tee -a "$LOGFILE"

X11_READY=false
for _i in $(seq 1 60); do
    if [ -e "$X11_SOCKET_DIR/X0" ]; then X11_READY=true; break; fi
    if ! kill -0 "$X11_PID" 2>/dev/null; then
        echo "X11 process $X11_PID died before socket creation" | tee -a "$LOGFILE"
        break
    fi
    sleep 0.2
done
if [ "$X11_READY" != true ]; then
    echo "ERROR: X11 socket not ready after 12s" | tee -a "$LOGFILE"
    exit 1
fi
echo "X11 socket ready" | tee -a "$LOGFILE"
export DISPLAY=:0

# Launch hybrid X11 host activity (undecorated frame). Mirrors proot launcher.
# In direct-surface mode the Android app foregrounds this before dispatching
# us, because /system/bin/am from Termux's UID is rejected on current Android
# builds (com.android.shell package / com.termux UID mismatch).
if [[ "${RLT_DIRECT_SURFACE:-0}" != "1" && -z "${RLT_FRAME_TCP_PORT:-}" &&
      "$RLT_APP_VIRGL_AHB" != "1" ]]; then
    am start --activity-single-top --activity-clear-top -n "$X11_HOST_COMPONENT" >/dev/null 2>&1 || true
elif [[ -n "${RLT_FRAME_TCP_PORT:-}" || "$RLT_APP_VIRGL_AHB" == "1" ]]; then
    echo "FRAME-STREAM-DIAG DirectSurfaceHostActivity remains foreground; X11 host not foregrounded" | tee -a "$LOGFILE"
else
    echo "DIRECT-SURFACE-DIAG Hybrid host foreground handled by app LaunchCoordinator" | tee -a "$LOGFILE"
fi

# ===================================================================
# Start VirGL server (Termux-native already — no change from proot path)
# ===================================================================
VIRGL_LOG="$HOME/virgl-server.log"
if [[ "$RLT_APP_VIRGL_HOST" == "1" ]]; then
    # Android filters /proc/net/tcp by app UID, so Termux cannot observe the
    # Tablite-owned listener without consuming VirGL's single client socket.
    # The app starts the host before this launcher is dispatched; Mesa's vtest
    # connection is the authoritative fail-closed transport gate.
    sleep 1
    echo "APP-VIRGL: using app-owned host at 127.0.0.1:$VTEST_TCP_PORT" | tee -a "$LOGFILE"
    export GALLIUM_DRIVER=virpipe
    unset VTEST_SOCKET_NAME
    export MESA_NO_ERROR=1
    export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLSL_VERSION_OVERRIDE=430
    export MESA_GLSL_CACHE_DISABLE=0
    sleep 0.5
elif command -v virgl_test_server_android >/dev/null 2>&1; then
    EXTERNAL_VIRGL_SENTINEL="$HOME/.rlt-external-virgl"
    if [ -f "$EXTERNAL_VIRGL_SENTINEL" ] && [ -S "$PREFIX/tmp/.virgl_test" ]; then
        EXISTING_VIRGL_PID=$(pgrep -f virgl_test_server_android | head -1 || true)
        if [ -n "$EXISTING_VIRGL_PID" ]; then
            VIRGL_PID="$EXISTING_VIRGL_PID"
            echo "VIRGL-EXTERNAL: adopting pre-spawned virgl PID=$VIRGL_PID" | tee -a "$LOGFILE"
        fi
    fi
    if [ -z "$VIRGL_PID" ]; then
        env -u LD_LIBRARY_PATH VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1 \
            virgl_test_server_android > "$VIRGL_LOG" 2>&1 &
        VIRGL_PID=$!
        echo "VirGL server forked PID=$VIRGL_PID log=$VIRGL_LOG" | tee -a "$LOGFILE"
        for _i in $(seq 1 30); do
            [ -S "$PREFIX/tmp/.virgl_test" ] && kill -0 "$VIRGL_PID" 2>/dev/null && break
            sleep 0.2
        done
        if [ ! -S "$PREFIX/tmp/.virgl_test" ]; then
            echo "WARNING: VirGL socket not ready; rendering will fall back to software" | tee -a "$LOGFILE"
        else
            echo "VirGL socket ready" | tee -a "$LOGFILE"
        fi
    fi
    # Env for Mesa virpipe backend (mirrors proot launcher's post-validation block).
    # MESA_EXTENSION_OVERRIDE disables a depth-clamp pair that virgl's front-end
    # advertises but the Turnip/Mali driver behind it can't actually honor; leaving
    # them enabled causes RuneLite's GPU plugin to request features and then segfault.
    # The MESA_GLSL_CACHE path keeps the shader cache on-disk so cold-start re-compiles
    # are skipped between launches.
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME="$PREFIX/tmp/.virgl_test"
    export MESA_NO_ERROR=1
    export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp
    export MESA_GL_VERSION_OVERRIDE=4.3COMPAT
    export MESA_GLSL_VERSION_OVERRIDE=430
    export MESA_GLSL_CACHE_DISABLE=0
    # Brief settle: virgl_test_server can accept the socket connection before its
    # internal FBConfig enumeration completes, which makes glXChooseFBConfig return
    # zero configs when RuneLite queries them (producing the "unable to find a fb
    # config" RuntimeException from rlawt). 500ms after socket-ready is enough on
    # the Samsung Tab S10 Ultra to let virgl finish initializing.
    sleep 0.5
else
    echo "virgl_test_server_android not installed — software rendering" | tee -a "$LOGFILE"
fi

# Export LD_LIBRARY_PATH + JAVA_HOME NOW so the DIAG block below captures real values
# (previously they were only set right before the JVM invoke, and the DIAG dump logged
# `<unset>`). glxinfo also needs LD_LIBRARY_PATH set to pick up Termux's libGL rather
# than any system stub.
export LD_LIBRARY_PATH="$NATIVE_LD_LIBRARY_PATH"
export JAVA_HOME="$JAVA_HOME_NATIVE"

# ===================================================================
# DIAGNOSTIC PREFLIGHT — dump everything rlawt/Java/virgl see so we can
# actually debug size/GPU issues without guessing. Adds no overhead in
# the happy path (log writes only) and makes post-mortems tractable.
# ===================================================================
echo "=== DIAG preflight @$(date +%H:%M:%S.%3N) ===" | tee -a "$LOGFILE"
echo "[DIAG] device physical size (Android wm): unreadable from run-as com.termux" | tee -a "$LOGFILE"
echo "[DIAG] Termux:X11 resolution prefs:" | tee -a "$LOGFILE"
if [ -r /data/data/com.termux.x11/shared_prefs/com.termux.x11_preferences.xml ]; then
    grep -E 'displayResolution|fullscreen|adjustResolution|displayStretch' \
        /data/data/com.termux.x11/shared_prefs/com.termux.x11_preferences.xml \
        2>/dev/null | sed 's/^/  /' | tee -a "$LOGFILE"
else
    echo "  (prefs file not readable from com.termux uid)" | tee -a "$LOGFILE"
fi
echo "[DIAG] Termux:X11 server process(es):" | tee -a "$LOGFILE"
# Termux:X11's server runs under Android app_process with its cmdline set to
# "com.termux.x11.Loader" or "com.termux.x11.CmdEntryPoint" — never "termux-x11".
# Match via find_pids_by_cmdline to cover both.
_x11_pids="$(find_pids_by_cmdline 'com.termux.x11.Loader')$(echo)$(find_pids_by_cmdline 'com.termux.x11.CmdEntryPoint')"
if [ -n "$_x11_pids" ]; then
    for p in $_x11_pids; do
        echo "  pid=$p cmdline=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)" | tee -a "$LOGFILE"
    done
else
    echo "  (none)" | tee -a "$LOGFILE"
fi
echo "[DIAG] VirGL server:" | tee -a "$LOGFILE"
if [[ "$RLT_APP_VIRGL_HOST" == "1" ]]; then
    echo "  app-owned TCP host 127.0.0.1:$VTEST_TCP_PORT (cross-UID /proc visibility unavailable)" | tee -a "$LOGFILE"
elif [ -S "$PREFIX/tmp/.virgl_test" ]; then
    echo "  socket OK $(stat -c '%Y %n' "$PREFIX/tmp/.virgl_test")" | tee -a "$LOGFILE"
    pgrep -af virgl_test_server_android 2>/dev/null | sed 's/^/  pid: /' | tee -a "$LOGFILE"
    echo "  virgl-server.log (last 5):" | tee -a "$LOGFILE"
    tail -5 "$HOME/virgl-server.log" 2>/dev/null | sed 's/^/    /' | tee -a "$LOGFILE"
else
    echo "  socket missing — virgl not running, RL will see software Mesa only" | tee -a "$LOGFILE"
fi
echo "[DIAG] Mali/GPU busy counter probe:" | tee -a "$LOGFILE"
GPU_BUSY_PATH=""
for candidate in \
    /sys/class/misc/mali0/device/utilization \
    /sys/kernel/gpu/gpu_busy \
    /sys/class/kgsl/kgsl-3d0/gpubusy \
    /sys/class/devfreq/gpufreq/load \
    /sys/devices/platform/mali/utilization
do
    if [ -r "$candidate" ]; then
        val=$(head -1 "$candidate" 2>/dev/null || echo "?")
        echo "  readable: $candidate = $val" | tee -a "$LOGFILE"
        if [ -z "$GPU_BUSY_PATH" ]; then
            GPU_BUSY_PATH="$candidate"
        fi
    else
        echo "  not readable: $candidate" | tee -a "$LOGFILE"
    fi
done
if [ -n "$GPU_BUSY_PATH" ]; then
    echo "  selected GPU busy path: $GPU_BUSY_PATH" | tee -a "$LOGFILE"
else
    echo "  no GPU busy counter readable; sampler will skip GPU metric" | tee -a "$LOGFILE"
fi
export GPU_BUSY_PATH
echo "[DIAG] Mesa env that rlawt/GL will see:" | tee -a "$LOGFILE"
for v in GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE MESA_EXTENSION_OVERRIDE MESA_NO_ERROR LD_LIBRARY_PATH DISPLAY; do
    eval "val=\${$v:-<unset>}"
    echo "  $v=$val" | tee -a "$LOGFILE"
done
echo "[DIAG] glxinfo (OpenGL renderer + FBConfigs):" | tee -a "$LOGFILE"
if command -v glxinfo >/dev/null 2>&1; then
    # -B: short info (vendor / renderer / version); then dump up to 50 FBConfigs
    # so we can see what Mesa/virgl actually offers when rlawt's glXChooseFBConfig
    # returns 0 matches.
    timeout 10 glxinfo -B 2>&1 | head -30 | sed 's/^/  /' | tee -a "$LOGFILE"
    echo "[DIAG] glxinfo FBConfig summary (first 10 visuals):" | tee -a "$LOGFILE"
    timeout 10 glxinfo 2>&1 | grep -E 'visual id|color sizes|samples|stencil|depth|drawable|renderable' \
        | head -40 | sed 's/^/  /' | tee -a "$LOGFILE"
else
    echo "  glxinfo not installed — run 'pkg install mesa-demos' for fb-config diagnostics" | tee -a "$LOGFILE"
fi
echo "[DIAG] RL profile window/layout prefs:" | tee -a "$LOGFILE"
RL_PROFILE_DIR="$ROOTFS_PATH/root/.runelite/profiles2"
if [ -d "$RL_PROFILE_DIR" ]; then
    for prof in "$RL_PROFILE_DIR"/*.properties; do
        [ -f "$prof" ] || continue
        echo "  --- $(basename "$prof") ---" | tee -a "$LOGFILE"
        grep -aE 'runelite\.(gameSize|lockWindowSize|containInScreen|automaticResizeType|rememberScreenBounds)|stretchedmode\.' \
            "$prof" 2>/dev/null | sed 's/^/    /' | tee -a "$LOGFILE"
    done
else
    echo "  (profile dir missing)" | tee -a "$LOGFILE"
fi
echo "[DIAG] Bionic rlawt jar expected on classpath:" | tee -a "$LOGFILE"
echo "  $(ls -la "$RLAWT_BIONIC_JAR" 2>&1)" | tee -a "$LOGFILE"
echo "=== DIAG end ===" | tee -a "$LOGFILE"

rlt_log_cpuset "virgl-ready" "${VIRGL_PID:-$$}"

# RuneLite profiles and settings are user-owned. The native compatibility
# path observes them but never edits visual quality, window, plugin, pacing,
# login, or gameplay configuration.
echo "RuneLite configuration boundary: user-managed settings preserved without launcher writes" |
    tee -a "$LOGFILE"

# ===================================================================
# Build RuneLite classpath at runtime
# ===================================================================
# Scan repository2/ live — the stored direct-classpath.txt is stale (points
# at RL 1.12.20, rlawt-1.7; current disk has RL 1.12.24 + rlawt-1.8). See
# spec U2. Skip stock rlawt-*.jar so our Bionic variant resolves instead.
CLASSPATH_ENTRIES=""
SKIPPED_RLAWT=""
for jar in "$RL_REPO_DIR"/*.jar; do
    [ -f "$jar" ] || continue
    case "${jar##*/}" in
        rlawt-*.jar)
            SKIPPED_RLAWT="$jar"
            continue
            ;;
    esac
    if [ -z "$CLASSPATH_ENTRIES" ]; then
        CLASSPATH_ENTRIES="$jar"
    else
        CLASSPATH_ENTRIES="${CLASSPATH_ENTRIES}:${jar}"
    fi
done
if [ -z "$CLASSPATH_ENTRIES" ]; then
    echo "ERROR: repository2/ produced an empty classpath" | tee -a "$LOGFILE"
    exit 1
fi
# Prepend our Bionic rlawt jar so it shadows anything else.
FULL_CLASSPATH="${RLAWT_BIONIC_JAR}:${CLASSPATH_ENTRIES}"

echo "Classpath assembled:" | tee -a "$LOGFILE"
echo "  bionic-rlawt-jar=$RLAWT_BIONIC_JAR" | tee -a "$LOGFILE"
echo "  skipped-stock-rlawt=${SKIPPED_RLAWT:-<none>}" | tee -a "$LOGFILE"
echo "  repo-jar-count=$(ls "$RL_REPO_DIR"/*.jar 2>/dev/null | wc -l)" | tee -a "$LOGFILE"
echo "  classpath=$FULL_CLASSPATH" | tee -a "$LOGFILE"

# ===================================================================
# Invoke JVM directly — no proot
# ===================================================================
# LD_LIBRARY_PATH / JAVA_HOME already exported before the DIAG block above.
echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH" | tee -a "$LOGFILE"
echo "JAVA_HOME=$JAVA_HOME" | tee -a "$LOGFILE"

MAIN_CLASS="net.runelite.client.RuneLite"
# `--scale` is a flag on the RuneLite *launcher* (`java -jar RuneLite.jar`), NOT on the
# RuneLite client main class — joptsimple rejects it with UnrecognizedOptionException.
# Since we invoke the client main class directly (we're not going through the launcher),
# we use the equivalent AWT system property instead. -Dsun.java2d.uiScale controls AWT
# HiDPI scaling for the Frame + fonts without needing the launcher wrapper.
CLIENT_ARGS="--insecure-write-credentials --debug"

# UI scale is computed by LaunchCoordinator from context.resources.displayMetrics (the
# Android WindowManager Binder service isn't callable from run-as com.termux, so the
# Kotlin side is the only place with reliable access to the real display size). Falls
# back to 1.0 here if the env var is somehow missing (direct on-device shell invocation
# for testing) — never hardcode a specific scale.
UI_SCALE="${RLT_UI_SCALE:-1}"
if [ -n "${RLT_UI_SCALE:-}" ]; then
    echo "UI scale: $UI_SCALE (source=Kotlin-computed)" | tee -a "$LOGFILE"
else
    echo "UI scale: $UI_SCALE (source=fallback, set RLT_UI_SCALE to override)" | tee -a "$LOGFILE"
fi
# -Xss2m: Bionic default thread stack is ~1MB, JVM threads want more.
# -Xmx2g: matches device-memory budget used in proot path for small bench.
# -Duser.home points at the proot rootfs .runelite dir so native JVM sees
#   the same profiles2/, settings.properties, and credentials.properties.
# -Xlog:class+load=info: classload log proves main class was loaded, even
#   if RL's own loggers never initialize.
JVM_ARGS=(
    -Xss2m
    -Xmx2g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=50
    # S82 in-game telemetry caught a 1.74-second gap_us stall on rlawt frame=600
    # right as the world loaded; thread-cpu.csv showed C2 CompilerThread spiking
    # to 195-296 jiffies/s during that same window. The user perceived it as
    # "noticeably worse" perf. These four additions remove the most common
    # JIT/heap stall sources without forcing a slow eager full compile (-Xcomp
    # would add minutes to startup on Cortex-A520 small cores).
    #
    # +AlwaysPreTouch: writes a zero to every heap page at JVM init so the
    #   first allocation in a hot path doesn't take a soft-page-fault detour.
    #   Cost: ~100-300ms additional startup time for our 2GB heap on a512 cores.
    -XX:+AlwaysPreTouch
    # ReservedCodeCacheSize: explicit cap big enough that C2 doesn't evict
    #   compiled hot methods mid-game and have to recompile (which manifests
    #   as the 200-300 jiffies/s spikes we saw). JDK 21 default is ~240MB,
    #   we raise to 256MB and turn on segmented cache flushing so cold methods
    #   leave room for hot ones.
    -XX:ReservedCodeCacheSize=256m
    # CICompilerCount=4: default JIT thread count on this device may default to
    #   2 (depends on cgroup-visible CPU count). With 8 cores available and a
    #   plentiful JIT workload at startup, 4 lets C1+C2 run in parallel. No
    #   harm if the machine can't provision them — they idle.
    -XX:CICompilerCount=4
    # UseStringDeduplication: small free memory win, reduces young-gen pressure
    #   on the high-string-churn AWT/Swing code paths.
    -XX:+UseStringDeduplication
    "-Duser.home=$ROOTFS_PATH/root"
    # Java2D HiDPI scale — equivalent of the proot launcher's RuneLite-launcher `--scale`
    # flag but applied at the JVM level since we invoke the client main class directly.
    # Value comes from detect_ui_scale() above which reads the real display size; never
    # hardcode a specific scale here.
    "-Dsun.java2d.uiScale=$UI_SCALE"
    "-Xlog:class+load=info:file=$CLASSLOAD_LOG:time,uptime,level,tags:filecount=2,filesize=5M"
    # JIT-event log so we can correlate compile bursts with rlawt gap_us spikes
    # in post-mortem analysis. Compile events go to a separate file (jit-events.log)
    # because they're too chatty to interleave with class+load on the same channel.
    # Tag is `jit` in OpenJDK 21+ logging — `jit+compilation*` was tried first but
    # silently produced no events (run 3 confirmed empty file). The canonical tag
    # set is `jit+compilation` literal (no glob), per JEP 158 / -Xlog:help.
    "-Xlog:jit+compilation=info:file=$HOME/jit-events.log:time,uptime,level,tags:filecount=2,filesize=5M"
)
# Forward Jagex credentials from the env-file (if any) as system properties —
# RuneLite's auth layer reads these when sessionId / characterId are present,
# matching the proot path's env-based forwarding.
[ -n "${JX_SESSION_ID:-}" ]   && JVM_ARGS+=("-DJX_SESSION_ID=$JX_SESSION_ID")
[ -n "${JX_CHARACTER_ID:-}" ] && JVM_ARGS+=("-DJX_CHARACTER_ID=$JX_CHARACTER_ID")
[ -n "${JX_DISPLAY_NAME:-}" ] && JVM_ARGS+=("-DJX_DISPLAY_NAME=$JX_DISPLAY_NAME")

# ===================================================================
# Window manager — start openbox BEFORE the JVM
# ===================================================================
# S82 evidence (docs/s82-capture/run2-x11-window-state.log) — when the native
# path runs without a WM, RL's setExtendedState(MAXIMIZED_BOTH) call
# (driven by runelite.clientMaximized=true in the profile) is silently
# dropped because there's no EWMH window manager listening for
# _NET_WM_STATE_MAXIMIZED_HORZ / VERT atoms. Result: ContainableFrame is
# shown at default 1238x932 instead of filling the X root, leaving black
# bands on either side of the game.
#
# The proot path solves this by starting openbox inside the Ubuntu rootfs
# with rc.xml `<maximized>yes</maximized>` (launch-runelite.sh:888-905).
# Termux ships openbox 3.6.1 natively (`pkg install openbox`), so we mirror
# that contract here for the native path.
start_openbox_native() {
    local ob_bin
    ob_bin="$(command -v openbox)"
    if [ -z "$ob_bin" ]; then
        echo "OPENBOX-WARN: openbox binary not found on PATH — Frame will not maximize" | tee -a "$LOGFILE"
        return 0  # non-fatal — RL still runs, just not maximized
    fi
    # Same `<decor>no</decor>` + `<maximized>yes</maximized>` rules the proot
    # launcher uses. Termux openbox honors $HOME/.config/openbox/rc.xml.
    local ob_cfg_dir="$HOME/.config/openbox"
    local ob_cfg="$ob_cfg_dir/rc.xml"
    mkdir -p "$ob_cfg_dir"
    cat > "$ob_cfg" <<'OBCFG'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc"
    xmlns:xi="http://www.w3.org/2001/XInclude">
  <applications>
    <application class="*" groupclass="*">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
  </applications>
</openbox_config>
OBCFG
    echo "OPENBOX: rc.xml written -> $ob_cfg ($(wc -c < "$ob_cfg") bytes)" | tee -a "$LOGFILE"
    DISPLAY=:0 "$ob_bin" --sm-disable >>"$LOGFILE" 2>&1 &
    OPENBOX_PID=$!
    # Probe once after a short delay so cleanup_on_exit has a real PID to kill
    # if openbox died on startup (e.g. couldn't connect to :0).
    sleep 0.3
    if kill -0 "$OPENBOX_PID" 2>/dev/null; then
        echo "OPENBOX: started PID=$OPENBOX_PID DISPLAY=:0 cfg=$ob_cfg" | tee -a "$LOGFILE"
    else
        echo "OPENBOX-FAIL: launched but exited immediately (see tail of $LOGFILE for stderr)" | tee -a "$LOGFILE"
        OPENBOX_PID=""
    fi
}
start_openbox_native

# Surface the JIT/heap config we just set as a single grep-able line so post-mortem
# analysis can verify they actually landed in this run vs a stale APK.
echo "JIT-CONFIG: AlwaysPreTouch=on ReservedCodeCacheSize=256m CICompilerCount=4 UseStringDeduplication=on jit-events-log=$HOME/jit-events.log" | tee -a "$LOGFILE"

# ===================================================================
# Direct-surface mode env: rlawt JVM connects to DirectSurfaceHostActivity's
# RlawtSurfaceServer on an abstract socket. Pass the device dims so the
# producer's PRODUCER_HELLO request matches the SurfaceView's allocation.
# ===================================================================
if [[ "${RLT_DIRECT_SURFACE:-0}" == "1" ]]; then
    echo "=== Direct-surface mode active ===" | tee -a "$LOGFILE"
    echo "  abstract_name=${RLAWT_SURFACE_NAME:-rlt-rlawt-surface}" | tee -a "$LOGFILE"
    # Probe device dims via xdpyinfo; fall back to 2960x1848 (Tab S10 Ultra).
    DS_W=2960; DS_H=1848
    if command -v xdpyinfo >/dev/null 2>&1; then
        DS_DIMS="$(DISPLAY=:0 timeout 3 xdpyinfo 2>/dev/null | awk '/dimensions:/ {print $2; exit}' || true)"
        if [[ "$DS_DIMS" =~ ^([0-9]+)x([0-9]+)$ ]]; then
            DS_W="${BASH_REMATCH[1]}"; DS_H="${BASH_REMATCH[2]}"
        fi
    fi
    export RLAWT_INITIAL_WIDTH="${DS_W}"
    export RLAWT_INITIAL_HEIGHT="${DS_H}"
    export RLAWT_SURFACE_NAME="${RLAWT_SURFACE_NAME:-rlt-rlawt-surface}"
    export RLAWT_ACTIVITY_CONTROL_HOST="${RLAWT_ACTIVITY_CONTROL_HOST:-127.0.0.1}"
    export RLAWT_ACTIVITY_CONTROL_PORT="${RLAWT_ACTIVITY_CONTROL_PORT:-49382}"
    export RLAWT_ACTIVITY_CONTROL_NAME="${RLAWT_ACTIVITY_CONTROL_NAME:-rlt-direct-control}"
    # Raw per-frame CSV is invasive and only enabled for BURST/DEEP captures.
    if [[ "$RLT_TELEMETRY_MODE" == "BURST" || "$RLT_TELEMETRY_MODE" == "DEEP" ]]; then
        export RLAWT_PERF_CSV="${RLAWT_PERF_CSV:-$RLT_CAPTURE_DIR/rlawt-direct-surface-frames.csv}"
        mkdir -p "$(dirname "$RLAWT_PERF_CSV")"
    else
        unset RLAWT_PERF_CSV
    fi
    echo "  RLAWT_INITIAL_WIDTH=$RLAWT_INITIAL_WIDTH RLAWT_INITIAL_HEIGHT=$RLAWT_INITIAL_HEIGHT" | tee -a "$LOGFILE"
    echo "  RLAWT_ACTIVITY_CONTROL=${RLAWT_ACTIVITY_CONTROL_HOST}:${RLAWT_ACTIVITY_CONTROL_PORT} local_fallback=@$RLAWT_ACTIVITY_CONTROL_NAME" | tee -a "$LOGFILE"
    echo "  RLAWT_PERF_CSV=${RLAWT_PERF_CSV:-disabled}" | tee -a "$LOGFILE"
    echo "  DIRECT-SURFACE-DIAG LD_LIBRARY_PATH=${LD_LIBRARY_PATH:-<unset>} PATH=$PATH" | tee -a "$LOGFILE"
    if command -v sha256sum >/dev/null 2>&1; then
        echo "  DIRECT-SURFACE-DIAG rlawt-jar-sha256=$(sha256sum "$RLAWT_BIONIC_JAR" | awk '{print $1}')" | tee -a "$LOGFILE"
    fi
    echo "  DIRECT-SURFACE-DIAG system-lib-files:" | tee -a "$LOGFILE"
    ls -l /system/lib64/liblzma.so /system/lib64/libunwindstack.so /system/lib64/libEGL.so /system/lib64/libGLESv3.so >>"$LOGFILE" 2>&1 || true
    if command -v readelf >/dev/null 2>&1; then
        echo "  DIRECT-SURFACE-DIAG libEGL NEEDED:" | tee -a "$LOGFILE"
        readelf -d /system/lib64/libEGL.so 2>/dev/null | grep NEEDED >>"$LOGFILE" || true
        echo "  DIRECT-SURFACE-DIAG libunwindstack/liblzma symbols:" | tee -a "$LOGFILE"
        readelf -Ws /system/lib64/libunwindstack.so 2>/dev/null | grep Xzs_Construct >>"$LOGFILE" || true
        readelf -Ws /system/lib64/liblzma.so 2>/dev/null | grep Xzs_Construct >>"$LOGFILE" || true
    fi

    # Keep the X11/Hybrid login path visible until the rlawt direct-surface
    # backend actually creates a GPU context. At that point the native producer
    # starts DirectSurfaceHostActivity and connects to its consumer socket.
    echo "  DirectSurfaceHostActivity will start on first rlawt createGLContext" | tee -a "$LOGFILE"
fi

echo "=== Invoking JVM ===" | tee -a "$LOGFILE"
SAFE_JVM_ARGS=()
for arg in "${JVM_ARGS[@]}"; do
    case "$arg" in
        -DJX_SESSION_ID=*|-DJX_CHARACTER_ID=*|-DJX_DISPLAY_NAME=*)
            SAFE_JVM_ARGS+=("${arg%%=*}=***")
            ;;
        *)
            SAFE_JVM_ARGS+=("$arg")
            ;;
    esac
done
echo "  cmd: $JAVA_BIN ${SAFE_JVM_ARGS[*]} -cp <classpath> $MAIN_CLASS $CLIENT_ARGS" | tee -a "$LOGFILE"
: > "$CLASSLOAD_LOG"

# Forward only the low-rate structured producer, Mesa, and VirGL host summaries to the app-owned,
# loopback-only ingress. Android logcat is UID-scoped and cannot reliably carry
# Termux records into Tablite. Producers still write on the JVM stderr thread;
# this background bridge performs all cross-process socket I/O.
bridge_process_snapshot() {
    local root_pid="$1" pending pid child stat_line ticks=0 bytes=0 cmdline rchar
    pending="$root_pid"
    for _depth in 1 2 3; do
        local next=""
        for pid in $pending; do
            [ -r "/proc/$pid/stat" ] || continue
            stat_line="$(cat "/proc/$pid/stat" 2>/dev/null || true)"
            if [ -n "$stat_line" ]; then
                ticks=$((ticks + $(printf '%s\n' "$stat_line" | awk '{print $14 + $15}')))
            fi
            cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
            if [[ "$cmdline" == *socat* ]]; then
                rchar="$(awk '/^rchar:/ {print $2}' "/proc/$pid/io" 2>/dev/null || echo 0)"
                bytes=$((bytes + ${rchar:-0}))
            fi
            for child in $(cat "/proc/$pid/task/$pid/children" 2>/dev/null || true); do
                next="$next $child"
            done
        done
        pending="$next"
        [ -z "$pending" ] && break
    done
    printf '%s %s\n' "$ticks" "$bytes"
}

start_telemetry_bridge_monitor() {
    local bridge_pid="$1"
    [ "$RLT_TELEMETRY_SELF_PROFILE" = "1" ] || return 0
    (
        local clock_ticks previous_ticks previous_bytes previous_ns
        local snapshot now_ns ticks bytes delta_ticks delta_bytes wall_ns cpu_ns
        clock_ticks="$(getconf CLK_TCK 2>/dev/null || echo 100)"
        snapshot="$(bridge_process_snapshot "$bridge_pid")"
        previous_ticks="${snapshot%% *}"
        previous_bytes="${snapshot##* }"
        previous_ns="$(date +%s%N)"
        while kill -0 "$bridge_pid" 2>/dev/null; do
            sleep 5
            now_ns="$(date +%s%N)"
            snapshot="$(bridge_process_snapshot "$bridge_pid")"
            ticks="${snapshot%% *}"
            bytes="${snapshot##* }"
            delta_ticks=$((ticks >= previous_ticks ? ticks - previous_ticks : 0))
            delta_bytes=$((bytes >= previous_bytes ? bytes - previous_bytes : 0))
            wall_ns=$((now_ns >= previous_ns ? now_ns - previous_ns : 0))
            cpu_ns=$((delta_ticks * 1000000000 / clock_ticks))
            printf '%s\n' \
                "RLT_TELEMETRY_COST_V1 run_id=$RLT_CAPTURE_ID mode=$RLT_TELEMETRY_MODE stage=native_bridge outcome=measured calls=1 measured_samples=1 measured_wall_ns=$wall_ns measured_thread_cpu_ns=$cpu_ns bytes=$delta_bytes dropped=0 queue_high_water_mark=0 dropped_status=unobservable queue_status=unobservable observer=procfs record_emit_excluded=1" \
                >> "$LOGFILE"
            previous_ticks="$ticks"
            previous_bytes="$bytes"
            previous_ns="$now_ns"
        done
    ) &
    TELEMETRY_BRIDGE_MONITOR_PID=$!
}

if [ -x "$PREFIX/bin/socat" ]; then
    (
        tail -F -n 0 "$LOGFILE" "$VIRGL_LOG" 2>/dev/null |
            grep --line-buffered -E "RLT_(FRAME_RATE|FRAME_STREAM|AHB_STREAM|AHB_GPU|DIRECT_TRANSFER|MESA_VTEST|VIRGL_HOST|VTEST_TRANSPORT|VTEST_FENCE|TELEMETRY_COST)_V1" |
            "$PREFIX/bin/socat" -u - TCP4:127.0.0.1:8100
    ) &
    RLAWT_RATE_TAIL_PID=$!
    start_telemetry_bridge_monitor "$RLAWT_RATE_TAIL_PID"
    echo "NATIVE-TELEMETRY-BRIDGE: started PID=$RLAWT_RATE_TAIL_PID sources=rlawt,frame-stream,mesa-vtest,virgl-host,vtest-transport transport=loopback-tcp destination=127.0.0.1:8100" | tee -a "$LOGFILE"
else
    echo "NATIVE-TELEMETRY-BRIDGE: socat unavailable; structured summaries remain in $LOGFILE" | tee -a "$LOGFILE"
fi

"$JAVA_BIN" "${JVM_ARGS[@]}" -cp "$FULL_CLASSPATH" "$MAIN_CLASS" $CLIENT_ARGS >> "$LOGFILE" 2>&1 &
JAVA_PID=$!
echo "$JAVA_PID" > "$HOME/.rlt-native.pid"
rlt_log_cpuset "java-started" "$JAVA_PID"
echo "JVM PID=$JAVA_PID (log $LOGFILE, classload $CLASSLOAD_LOG)" | tee -a "$LOGFILE"

# CRITICAL: SessionHealthMonitor.checkHealth polls $PREFIX/tmp/.rlt-session-alive
# every 5s after a 30s initial delay. If this sentinel is missing, three Stopped
# readings (15s) flip session state to Stopped and the HybridX11 activity finishes,
# kicking the user back to the RLT MainActivity even though the JVM is fine. The
# proot path (launch-runelite.sh) creates this; the native path was missing it
# since inception, so every native launch was flagging Stopped on first poll.
touch "$PREFIX/tmp/.rlt-session-alive"
echo "SESSION-SENTINEL: created $PREFIX/tmp/.rlt-session-alive (session-alive ack for SessionHealthMonitor)" | tee -a "$LOGFILE"

# Pin JVM and virgl to big/prime cores (4-7). Same policy as proot path for A/B parity.
# Read back Cpus_allowed_list afterwards: cpuset (e.g. /moderate cpus=0-3) intersects
# the requested affinity, so a successful taskset call can still leave the process
# clamped to little cores. The post-call status read is what tells us what actually
# took effect — see S81 CPUSET SETTLE evidence in pipeline-observability.md.
rlt_apply_affinity() {
    local label="$1" pid="$2" mask="${3:-0xF0}"
    [ -z "$pid" ] && { echo "AFFINITY: $label PID empty — skipped" | tee -a "$LOGFILE"; return; }
    [ -d "/proc/$pid" ] || { echo "AFFINITY: $label (PID=$pid) gone before taskset" | tee -a "$LOGFILE"; return; }
    local out
    out="$(taskset -p "$mask" "$pid" 2>&1 || echo "<taskset returned $?>")"
    local effective
    effective="$(grep '^Cpus_allowed_list' "/proc/$pid/status" 2>/dev/null | awk '{print $2}' || echo unreadable)"
    # S82 escalation: previously we logged the line and moved on, masking failures.
    # Detect failure by either an explicit error in `out` OR effective list that
    # doesn't include any of the requested big-core slots, then dump cgroup +
    # cpuset.cpus content so we know *why* the kernel refused — usually because
    # the process's cpuset (e.g. /moderate) restricts cpus_allowed to 0-3 and
    # the requested 0xF0 mask intersects to empty.
    case "$out" in
        *"failed"*|*"Invalid argument"*|*"Operation not permitted"*|*"<taskset returned"*)
            local cgroup cpuset cpuset_file cpus_content
            cgroup="$(cat /proc/$pid/cgroup 2>/dev/null | tr '\n' ';' || echo unreadable)"
            cpuset="$(cat /proc/$pid/cpuset 2>/dev/null || echo unreadable)"
            cpuset_file="/dev/cpuset${cpuset}/cpuset.cpus"
            cpus_content="$(cat "$cpuset_file" 2>/dev/null || echo unreadable)"
            echo "AFFINITY-FAIL: $label (PID=$pid) requested=$mask effective_Cpus_allowed_list=$effective taskset=$out" | tee -a "$LOGFILE"
            echo "AFFINITY-FAIL:   /proc/$pid/cpuset=$cpuset cpus_allowed_in_cpuset=$cpus_content cgroup=$cgroup" | tee -a "$LOGFILE"
            ;;
        *)
            echo "AFFINITY: $label (PID=$pid) requested=$mask effective_Cpus_allowed_list=$effective taskset=$out" | tee -a "$LOGFILE"
            ;;
    esac
}
rlt_apply_affinity "JVM" "$JAVA_PID" 0xF0
rlt_apply_affinity "VIRGL" "${VIRGL_PID:-}" 0xF0

# ===================================================================
# S82 verification telemetry
#
# Three samplers + one one-shot probe write to dedicated CSVs. We keep
# them as simple shell loops over /proc rather than a dedicated tool so
# the data is always captured even if a tooling dep (xprop, top -H,
# etc.) is missing.
#
# Files (all under the run-scoped capture directory):
#   affinity-timeline.csv  — 1Hz for first 60s, 10s after, until JVM dies
#   thread-cpu.csv         — top-N JVM threads by CPU% every 5s
#   x11-window-state.log   — one-shot xprop dump of RuneLite frame at T+10s
#
# All three exit cleanly when /proc/$JAVA_PID disappears.
# ===================================================================
AFFINITY_TIMELINE_CSV="$RLT_CAPTURE_DIR/affinity-timeline.csv"
THREAD_CPU_CSV="$RLT_CAPTURE_DIR/thread-cpu.csv"
X11_WINDOW_STATE_LOG="$RLT_CAPTURE_DIR/x11-window-state.log"
AFFINITY_TIMELINE_PID=""
THREAD_CPU_SAMPLER_PID=""
X11_WINDOW_PROBE_PID=""

# Find JVM thread tids by /proc/$pid/task/*/comm name. Returns first match
# or empty. Re-evaluated every sample because tids shift across launches.
_find_tid_by_comm() {
    local pattern="$1" t comm tid
    for t in /proc/$JAVA_PID/task/*; do
        [ -d "$t" ] || continue
        comm=$(cat "$t/comm" 2>/dev/null || echo)
        if [[ "$comm" == "$pattern" ||
            ("$pattern" == "AWT-EventQueue*" && "$comm" == AWT-EventQueue*) ]]; then
            tid="${t##*/}"
            echo "$tid"
            return 0
        fi
    done
    return 1
}

_cpus_allowed_for_pid() {
    local pid="$1"
    [ -d "/proc/$pid" ] || { echo "gone"; return; }
    grep '^Cpus_allowed_list' "/proc/$pid/status" 2>/dev/null | awk '{print $2}' || echo "unreadable"
}

_cpus_allowed_for_tid() {
    local tid="$1"
    [ -z "$tid" ] && { echo "no-tid"; return; }
    [ -d "/proc/$JAVA_PID/task/$tid" ] || { echo "gone"; return; }
    grep '^Cpus_allowed_list' "/proc/$JAVA_PID/task/$tid/status" 2>/dev/null | awk '{print $2}' || echo "unreadable"
}

start_affinity_timeline() {
    : > "$AFFINITY_TIMELINE_CSV"
    echo "ts,jvm_cpus,virgl_cpus,client_tid,client_cpus,awt_tid,awt_cpus" >> "$AFFINITY_TIMELINE_CSV"
    (
        local ts jvm_cpus virgl_cpus client_tid client_cpus awt_tid awt_cpus
        # First 60 samples at 1Hz catches the AMS demotion window; then 10s
        # cadence to keep the file size sane on long sessions.
        for _ in {1..60}; do
            [ ! -d "/proc/$JAVA_PID" ] && exit 0
            ts=$(date +%s)
            jvm_cpus=$(_cpus_allowed_for_pid "$JAVA_PID")
            virgl_cpus=$(_cpus_allowed_for_pid "${VIRGL_PID:-0}")
            client_tid=$(_find_tid_by_comm "Client" || true)
            client_cpus=$(_cpus_allowed_for_tid "${client_tid:-}")
            awt_tid=$(_find_tid_by_comm "AWT-EventQueue*" || true)
            awt_cpus=$(_cpus_allowed_for_tid "${awt_tid:-}")
            echo "$ts,$jvm_cpus,$virgl_cpus,${client_tid:-},$client_cpus,${awt_tid:-},$awt_cpus" >> "$AFFINITY_TIMELINE_CSV"
            sleep 1
        done
        while [ -d "/proc/$JAVA_PID" ]; do
            ts=$(date +%s)
            jvm_cpus=$(_cpus_allowed_for_pid "$JAVA_PID")
            virgl_cpus=$(_cpus_allowed_for_pid "${VIRGL_PID:-0}")
            client_tid=$(_find_tid_by_comm "Client" || true)
            client_cpus=$(_cpus_allowed_for_tid "${client_tid:-}")
            awt_tid=$(_find_tid_by_comm "AWT-EventQueue*" || true)
            awt_cpus=$(_cpus_allowed_for_tid "${awt_tid:-}")
            echo "$ts,$jvm_cpus,$virgl_cpus,${client_tid:-},$client_cpus,${awt_tid:-},$awt_cpus" >> "$AFFINITY_TIMELINE_CSV"
            sleep 10
        done
    ) &
    AFFINITY_TIMELINE_PID=$!
    echo "TELEMETRY: affinity-timeline sampler PID=$AFFINITY_TIMELINE_PID csv=$AFFINITY_TIMELINE_CSV" | tee -a "$LOGFILE"
}

# Per-thread CPU% via `top -H`. Termux ships busybox top by default, which
# does NOT support -H. We probe `procps top` first; if absent we fall back
# to a /proc/$pid/task/*/stat parser that computes utime+stime delta. Both
# paths produce the same CSV so analysis doesn't care.
start_thread_cpu_sampler() {
    : > "$THREAD_CPU_CSV"
    echo "ts,tid,comm,cpu_pct" >> "$THREAD_CPU_CSV"
    local raw_log="$RLT_CAPTURE_DIR/thread-cpu-raw.log"
    : > "$raw_log"
    local has_proc_top=""
    # `top -b -H -n 1 -p $JAVA_PID` works on procps. Detect by trying once
    # with a 2s timeout so a hang doesn't block JVM stderr. S82 found that
    # busybox top on Termux does NOT support -H even though `timeout 2 top`
    # exits 0 — the previous detection was a false positive. We now also
    # require a non-empty stdout AND that the output contains "PID" header
    # text before declaring procps-top usable.
    local probe_out
    probe_out="$(timeout 2 top -b -H -n 1 -p "$JAVA_PID" 2>&1 || true)"
    if [ -n "$probe_out" ] && echo "$probe_out" | grep -q '^[[:space:]]*PID'; then
        has_proc_top=1
    fi
    {
        echo "=== thread-cpu sampler probe @ $(date +%s) ==="
        echo "has_proc_top=${has_proc_top:-0}"
        echo "--- probe stdout (first 30 lines) ---"
        echo "$probe_out" | head -30
        echo "--- probe end ---"
    } >> "$raw_log"
    (
        local ts t tid comm utime1 stime1 utime2 stime2 cpu_jiffies line
        local pass=0
        # /proc-based fallback samples utime+stime over a 1s window per pass.
        # Bash 4 associative arrays — required (-A is not portable to dash).
        while [ -d "/proc/$JAVA_PID" ]; do
            ts=$(date +%s)
            pass=$((pass + 1))
            if [ -n "$has_proc_top" ]; then
                # Dump raw `top` output every 6th pass (~30s) so we can audit
                # the column layout if the awk filter ever rejects everything.
                if [ $((pass % 6)) -eq 1 ]; then
                    echo "--- top -b -H -n 1 -p $JAVA_PID @ ts=$ts ---" >> "$raw_log"
                    top -b -H -n 1 -p "$JAVA_PID" 2>&1 | head -25 >> "$raw_log"
                fi
                # awk: skip until "PID" header row, then take rows whose first
                # field is a number. Column 9 = %CPU on procps top; column 12 = COMMAND.
                # Some procps builds put COMMAND at $NF rather than $12 (depends on
                # locale + width); use $NF as fallback.
                local rows_added
                rows_added=$(top -b -H -n 1 -p "$JAVA_PID" 2>/dev/null \
                    | awk -v ts="$ts" '
                        /^[[:space:]]*PID/ { in_h=1; next }
                        in_h && $1 ~ /^[0-9]+$/ {
                            tid=$1; cpu=$9; comm=($12 ~ /[A-Za-z]/ ? $12 : $NF)
                            printf "%s,%s,%s,%s\n", ts, tid, comm, cpu
                            n++
                        }
                        END { print "ROWS=" n+0 > "/dev/stderr" }
                    ' 2>>"$raw_log" \
                    | sort -t, -k4 -nr \
                    | head -12 \
                    | tee -a "$THREAD_CPU_CSV" \
                    | wc -l)
                # Log a one-liner per pass so we can see "did this pass write rows".
                echo "THREAD-CPU pass=$pass ts=$ts mode=procps-top rows=$rows_added" >> "$raw_log"
            else
                # /proc fallback. Snapshot now, sleep 1s, snapshot again.
                # The previous version mis-piped the inner-loop append (used `>>` inside
                # the loop AND piped the empty `done` stdout to sort), so neither path
                # wrote rows. Now: emit to stdout inside the loop, pipe to sort/head/tee.
                declare -A start_jiffies
                for t in /proc/$JAVA_PID/task/*; do
                    [ -d "$t" ] || continue
                    tid="${t##*/}"
                    line=$(cat "$t/stat" 2>/dev/null) || continue
                    utime1=$(echo "$line" | awk '{print $14}')
                    stime1=$(echo "$line" | awk '{print $15}')
                    start_jiffies[$tid]=$((utime1 + stime1))
                done
                sleep 1
                local rows_added=0
                for t in /proc/$JAVA_PID/task/*; do
                    [ -d "$t" ] || continue
                    tid="${t##*/}"
                    [ -z "${start_jiffies[$tid]:-}" ] && continue
                    line=$(cat "$t/stat" 2>/dev/null) || continue
                    utime2=$(echo "$line" | awk '{print $14}')
                    stime2=$(echo "$line" | awk '{print $15}')
                    comm=$(cat "$t/comm" 2>/dev/null || echo '?')
                    cpu_jiffies=$(( utime2 + stime2 - ${start_jiffies[$tid]} ))
                    [ "$cpu_jiffies" -gt 0 ] || continue
                    echo "$ts,$tid,$comm,$cpu_jiffies"
                    rows_added=$((rows_added + 1))
                done | sort -t, -k4 -nr | head -12 >> "$THREAD_CPU_CSV"
                echo "THREAD-CPU pass=$pass ts=$ts mode=proc-fallback raw_rows=$rows_added" >> "$raw_log"
            fi
            sleep 4
        done
    ) &
    THREAD_CPU_SAMPLER_PID=$!
    echo "TELEMETRY: thread-cpu sampler PID=$THREAD_CPU_SAMPLER_PID has_proc_top=${has_proc_top:-0} csv=$THREAD_CPU_CSV raw=$raw_log" | tee -a "$LOGFILE"
}

# One-shot X11 window state probe. xprop/xwininfo aren't in Termux's default
# install; pkg-install on demand if missing. Probe runs at T+10s so RL has
# time to call setExtendedState(MAXIMIZED_BOTH) post-frame.show.
ensure_x11_utils() {
    if command -v xprop >/dev/null 2>&1 && command -v xwininfo >/dev/null 2>&1; then
        echo "TELEMETRY: xprop/xwininfo already on PATH — skip pkg install" | tee -a "$LOGFILE"
        return 0
    fi
    # Termux package names differ from Debian: xorg-xprop / xorg-xwininfo, NOT x11-utils.
    # The previous attempt logged "Unable to locate package x11-utils" — see s82-capture/runelite-native.log:1279.
    echo "TELEMETRY: xprop/xwininfo missing — pkg install xorg-xprop xorg-xwininfo" | tee -a "$LOGFILE"
    timeout 90 pkg install -y xorg-xprop xorg-xwininfo >>"$LOGFILE" 2>&1 || {
        echo "TELEMETRY: xorg-xprop/xorg-xwininfo install failed (offline?) — window-state probe will skip" | tee -a "$LOGFILE"
        return 1
    }
    if command -v xprop >/dev/null 2>&1 && command -v xwininfo >/dev/null 2>&1; then
        echo "TELEMETRY: pkg install OK — xprop=$(command -v xprop) xwininfo=$(command -v xwininfo)" | tee -a "$LOGFILE"
        return 0
    fi
    echo "TELEMETRY: pkg install reported success but xprop/xwininfo still missing" | tee -a "$LOGFILE"
    return 1
}

probe_x11_window_state() {
    : > "$X11_WINDOW_STATE_LOG"
    (
        sleep 10
        [ ! -d "/proc/$JAVA_PID" ] && exit 0
        if ! ensure_x11_utils; then
            echo "x11-utils unavailable — skipped" >> "$X11_WINDOW_STATE_LOG"
            exit 0
        fi
        {
            echo "=== X11 window-state probe ts=$(date +%s) ==="
            echo "--- xwininfo -root -tree ---"
            DISPLAY=:0 timeout 5 xwininfo -root -tree 2>&1 | head -60
            echo "--- xprop -name RuneLite ---"
            DISPLAY=:0 timeout 5 xprop -name "RuneLite" 2>&1 \
                | grep -E '_NET_WM_STATE|_NET_FRAME_EXTENTS|WM_NORMAL_HINTS|WM_NAME|geometry' \
                || echo "(no RuneLite-named window — fallback below)"
            echo "--- xwininfo -name RuneLite ---"
            DISPLAY=:0 timeout 5 xwininfo -name "RuneLite" 2>&1 | head -30
            # Also dump root window props and the most-recent X11 client list
            # since RL might title the window differently.
            echo "--- xprop -root _NET_CLIENT_LIST ---"
            DISPLAY=:0 timeout 5 xprop -root _NET_CLIENT_LIST 2>&1
            echo "=== probe end ==="
        } >> "$X11_WINDOW_STATE_LOG" 2>&1
    ) &
    X11_WINDOW_PROBE_PID=$!
    echo "TELEMETRY: x11-window probe PID=$X11_WINDOW_PROBE_PID log=$X11_WINDOW_STATE_LOG (fires at T+10s)" | tee -a "$LOGFILE"
}

# rlawt CSV summary — runs at exit for BURST/DEEP captures. Swap/gap
# proportions are observations only; neither proves a CPU nor IPC bottleneck
# without scheduler, GPU, transport, and presentation evidence.
summarize_rlawt_swap_gap() {
    local csv="${RLAWT_PERF_CSV:-$RLT_CAPTURE_DIR/rlawt-perframe.csv}"
    [ -f "$csv" ] || { echo "RLAWT-SUMMARY: csv missing at $csv" | tee -a "$LOGFILE"; return; }
    local rows
    rows=$(wc -l < "$csv" 2>/dev/null || echo 0)
    [ "$rows" -lt 100 ] && { echo "RLAWT-SUMMARY: only $rows rows — too few to summarize" | tee -a "$LOGFILE"; return; }
    # Header is the first commented line (e.g., `# rlawt per-frame CSV ...`),
    # then a column header. Skip both. Last 1000 rows are the steady-state.
    tail -1000 "$csv" \
        | awk -F, '
            NR==1 { next }  # skip column header if present
            $1 !~ /^[0-9]+$/ { next }
            {
                swap_sum += $3
                gap_sum  += $4
                n++
                if ($3 > swap_max) swap_max = $3
                if ($4 > gap_max)  gap_max  = $4
            }
            END {
                if (n == 0) { print "RLAWT-SUMMARY: no numeric rows in tail"; exit }
                swap_mean = swap_sum / n
                gap_mean  = gap_sum  / n
                ratio = (gap_mean > 0) ? swap_mean / gap_mean : -1
                printf "RLAWT-SUMMARY: n=%d swap_us mean=%.0f max=%.0f gap_us mean=%.0f max=%.0f ratio=%.2f\n", \
                    n, swap_mean, swap_max, gap_mean, gap_max, ratio
                print "RLAWT-SUMMARY: classification=unknown confidence=insufficient (swap/gap ratio alone is non-causal)"
            }' | tee -a "$LOGFILE"
}

start_affinity_timeline
start_thread_cpu_sampler
probe_x11_window_state

if [ "${RLT_PERF_SAMPLE:-0}" = "1" ]; then
    echo "PERF: RLT_PERF_SAMPLE=1 — spawning perf-sampler" | tee -a "$LOGFILE"
    # fps-log-tail.sh was removed in the S81 audit: it matched `fps=` in client.log
    # but the RuneLite FpsPlugin overlay value is never written to the log (it's a
    # pixel overlay on the game canvas). rlawt [rlawt-perf] + XloriePerf give us
    # producer and consumer FPS via real signals; we rely on those instead.
    PERF_SAMPLER_LOG="$RLT_CAPTURE_DIR/system-sampler.log"
    : > "$PERF_SAMPLER_LOG"
    "$HOME/scripts/perf-sampler.sh" "$JAVA_PID" "${VIRGL_PID:-}" 1 "$PERF_SAMPLER_LOG" "${GPU_BUSY_PATH:-}" >/dev/null 2>&1 &
    PERF_SAMPLER_PID=$!
    echo "PERF: sampler PID=$PERF_SAMPLER_PID log=$PERF_SAMPLER_LOG" | tee -a "$LOGFILE"
else
    echo "PERF: RLT_PERF_SAMPLE unset or !=1 — no sampler spawned" | tee -a "$LOGFILE"
fi

# ===================================================================
# Cpuset "settle" snapshots — entry-time CPUSET lines already emitted
# above capture what AMS grants on process creation. Android re-nices
# Termux within ~25s (S81 obs: JVM /top-app → /moderate, Client cpus=0-7
# → 0-3). Fire at 30/60/120/300s so we see both the first demotion AND
# whether the S81 periodic rebind holds long-term.
# ===================================================================
(
    _emit_cpuset_snapshot() {
        local T="$1"
        [ ! -d "/proc/$JAVA_PID" ] && return 0
        {
            printf '=== CPUSET SETTLE (T=%ss) ts=%s ===\n' "$T" "$(date +%s)"
            printf 'JVM     pid=%s cpuset=%s Cpus_allowed_list=%s\n' \
                "$JAVA_PID" \
                "$(cat /proc/$JAVA_PID/cpuset 2>/dev/null || echo unreadable)" \
                "$(grep '^Cpus_allowed_list' /proc/$JAVA_PID/status 2>/dev/null | awk '{print $2}' || echo unreadable)"
            if [ -n "${VIRGL_PID:-}" ] && [ -d "/proc/$VIRGL_PID" ]; then
                printf 'VIRGL   pid=%s cpuset=%s Cpus_allowed_list=%s\n' \
                    "$VIRGL_PID" \
                    "$(cat /proc/$VIRGL_PID/cpuset 2>/dev/null || echo unreadable)" \
                    "$(grep '^Cpus_allowed_list' /proc/$VIRGL_PID/status 2>/dev/null | awk '{print $2}' || echo unreadable)"
            fi
            # Find the RL Client thread + AWT-EventQueue-0 under the JVM tgid.
            # These are the threads whose /moderate demotion caps RL FPS.
            for t in /proc/$JAVA_PID/task/*; do
                [ -d "$t" ] || continue
                _comm=$(cat "$t/comm" 2>/dev/null || echo '?')
                case "$_comm" in
                    Client|AWT-EventQueue*|gles-renderer)
                        _tid="${t##*/}"
                        printf 'THREAD  tid=%s comm=%s cpuset=%s Cpus_allowed_list=%s\n' \
                            "$_tid" "$_comm" \
                            "$(cat /proc/$JAVA_PID/task/$_tid/cpuset 2>/dev/null || echo unreadable)" \
                            "$(grep '^Cpus_allowed_list' /proc/$JAVA_PID/task/$_tid/status 2>/dev/null | awk '{print $2}' || echo unreadable)"
                        ;;
                esac
            done
            printf '=== CPUSET SETTLE (T=%ss) end ===\n' "$T"
        } >> "$LOGFILE" 2>&1
    }

    # 30s catches first AMS re-evaluation. 60/120 check rebind-loop stickiness.
    # 300s is the long-haul. `sleep` increments are deltas from the prior wait.
    sleep 30;  _emit_cpuset_snapshot 30
    sleep 30;  _emit_cpuset_snapshot 60
    sleep 60;  _emit_cpuset_snapshot 120
    sleep 180; _emit_cpuset_snapshot 300
) &
CPUSET_SETTLE_PID=$!

wait "$JAVA_PID"
JAVA_EXIT=$?
echo "JVM exited with code $JAVA_EXIT" | tee -a "$LOGFILE"
exit "$JAVA_EXIT"
