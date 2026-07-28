#!/data/data/com.termux/files/usr/bin/bash
# No set -e — we want to see ALL errors, not exit on the first one
set -uo pipefail

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -f "$TERMUX_PREFIX/etc/profile" ]; then
    # Populate PREFIX, PATH, TMPDIR, and the usual Termux shell env even when
    # this script is launched from a non-login session.
    # shellcheck disable=SC1091
    . "$TERMUX_PREFIX/etc/profile"
fi
export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"

LOGFILE="$HOME/runelite-launch.log"

# S75 Path A instrumentation: log Termux's cpuset at script entry so we can
# tell whether RLT's bindService hoist moved us from /background to /top-app.
# Children (proot, JVM, virgl, openbox) inherit this cpuset; if it's /background
# the whole producer pipeline is pinned to little cores 0-3.
rlt_log_cpuset() {
    local label="$1"
    local pid="$2"
    local file="/proc/$pid/cpuset"
    local cset
    cset=$(cat "$file" 2>/dev/null || echo "<unreadable>")
    local line="CPUSET $label pid=$pid cpuset=$cset"
    echo "$line" | tee -a "$LOGFILE" 2>/dev/null || true
    if [ -x /system/bin/log ]; then
        /system/bin/log -t RLT -p i "$line" 2>/dev/null || true
    fi
}
rlt_log_cpuset "launch-script-entry" "$$"
INTERNAL_X11_LOGFILE="$HOME/internal-x11-start.log"
TERMUX_RUNELITE_SETTINGS_DIR="$HOME/.runelite"
TERMUX_DIRECT_CLASSPATH_FILE="$TERMUX_RUNELITE_SETTINGS_DIR/direct-classpath.txt"
PERF_MONITOR_ENABLED="${RLT_ENABLE_PERF_MONITOR:-0}"
LAUNCH_LOCK_DIR="$PREFIX/tmp/.rlt-launch.lock"
LAUNCH_LOCK_PID_FILE="$LAUNCH_LOCK_DIR/pid"
SESSION_ROOT_DIR="$PREFIX/tmp/rlt-session"
CURRENT_SESSION_FILE="$SESSION_ROOT_DIR/current"
# Termux uses $PREFIX/tmp, not /tmp — resolve the actual X11 socket path
X11_SOCKET_DIR="$PREFIX/tmp/.X11-unix"
TERMUX_X11_BIN="$PREFIX/bin/termux-x11"
TERMUX_X11_PREF_BIN="$PREFIX/bin/termux-x11-preference"
HYBRID_X11_OVERRIDE_PACKAGE="com.runelitetablet"
HYBRID_X11_HOST_COMPONENT="com.runelitetablet/.presentation.hybrid.HybridX11HostActivity"
STOCK_X11_HOST_COMPONENT="com.termux.x11/.MainActivity"
X11_PRESENTATION_VARIANT="hybrid"
X11_OVERRIDE_PACKAGE="$HYBRID_X11_OVERRIDE_PACKAGE"
X11_HOST_COMPONENT="$HYBRID_X11_HOST_COMPONENT"
X11_SERVER_MODE="external"
GPU_DISPLAY_RESOLUTION_OVERRIDE=""
RUNELITE_SCALE_OVERRIDE=""
JAVA2D_PROFILE_OVERRIDE=""
MESA_PROFILE_OVERRIDE=""
VIRGL_SERVER_PROFILE_OVERRIDE=""
RUNELITE_LAUNCH_MODE_OVERRIDE=""
# Initialize PIDs for cleanup trap (set -u requires these to exist)
X11_PID=""
PERF_MONITOR_PID=""
RL_LOG_TAIL_PID=""
mkdir -p "$TERMUX_RUNELITE_SETTINGS_DIR"
echo "=== RuneLite launch $(date) ===" | tee -a "$LOGFILE"

acquire_launch_lock() {
    if mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null; then
        echo "$$" > "$LAUNCH_LOCK_PID_FILE"
        return 0
    fi

    if [ -f "$LAUNCH_LOCK_PID_FILE" ]; then
        local existing_pid
        existing_pid="$(cat "$LAUNCH_LOCK_PID_FILE" 2>/dev/null || true)"
        if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
            echo "Another RuneLite launch is already in progress (PID $existing_pid); exiting duplicate launcher." | tee -a "$LOGFILE"
            exit 0
        fi
    fi

    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
    if mkdir "$LAUNCH_LOCK_DIR" 2>/dev/null; then
        echo "$$" > "$LAUNCH_LOCK_PID_FILE"
        return 0
    fi

    echo "Failed to acquire RuneLite launch lock; exiting." | tee -a "$LOGFILE"
    exit 1
}

release_launch_lock() {
    rm -rf "$LAUNCH_LOCK_DIR" 2>/dev/null || true
}

parse_launcher_args() {
    ENV_FILE=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --x11-presentation)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --x11-presentation requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                X11_PRESENTATION_VARIANT="$2"
                shift 2
                ;;
            --gpu-display-resolution)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --gpu-display-resolution requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                GPU_DISPLAY_RESOLUTION_OVERRIDE="$2"
                shift 2
                ;;
            --runelite-scale)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --runelite-scale requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                RUNELITE_SCALE_OVERRIDE="$2"
                shift 2
                ;;
            --java2d-profile)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --java2d-profile requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                JAVA2D_PROFILE_OVERRIDE="$2"
                shift 2
                ;;
            --mesa-profile)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --mesa-profile requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                MESA_PROFILE_OVERRIDE="$2"
                shift 2
                ;;
            --virgl-server-profile)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --virgl-server-profile requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                VIRGL_SERVER_PROFILE_OVERRIDE="$2"
                shift 2
                ;;
            --runelite-launch-mode)
                if [ "$#" -lt 2 ]; then
                    echo "ERROR: --runelite-launch-mode requires a value" | tee -a "$LOGFILE"
                    exit 1
                fi
                RUNELITE_LAUNCH_MODE_OVERRIDE="$2"
                shift 2
                ;;
            *)
                if [ -z "$ENV_FILE" ]; then
                    ENV_FILE="$1"
                    shift
                else
                    echo "WARNING: ignoring unexpected launcher argument: $1" | tee -a "$LOGFILE"
                    shift
                fi
                ;;
        esac
    done

    case "$X11_PRESENTATION_VARIANT" in
        hybrid)
            X11_OVERRIDE_PACKAGE="$HYBRID_X11_OVERRIDE_PACKAGE"
            X11_HOST_COMPONENT="$HYBRID_X11_HOST_COMPONENT"
            X11_SERVER_MODE="external"
            ;;
        stock)
            X11_OVERRIDE_PACKAGE=""
            X11_HOST_COMPONENT="$STOCK_X11_HOST_COMPONENT"
            X11_SERVER_MODE="external"
            ;;
        internal-hybrid)
            X11_OVERRIDE_PACKAGE=""
            X11_HOST_COMPONENT="$HYBRID_X11_HOST_COMPONENT"
            X11_SERVER_MODE="internal"
            ;;
        *)
            echo "ERROR: unsupported X11 presentation variant '$X11_PRESENTATION_VARIANT'" | tee -a "$LOGFILE"
            exit 1
            ;;
    esac
}

session_file() {
    printf '%s/%s' "$SESSION_DIR" "$1"
}

write_session_value() {
    local key="$1"
    local value="$2"
    printf '%s\n' "$value" > "$(session_file "$key")"
}

clear_current_session_marker() {
    if [ -f "$CURRENT_SESSION_FILE" ] && [ "$(cat "$CURRENT_SESSION_FILE" 2>/dev/null || true)" = "$SESSION_DIR" ]; then
        rm -f "$CURRENT_SESSION_FILE" 2>/dev/null || true
    fi
}

parse_launcher_args "$@"

# Test-harness sentinel: if $HOME/.rlt-virgl-profile-override exists and CLI
# didn't pass --virgl-server-profile, use the sentinel's first line as the
# profile. Lets session-level tests (H4/H5/H6) iterate without rebuilding.
VIRGL_PROFILE_SENTINEL_FILE="$HOME/.rlt-virgl-profile-override"
if [ -z "$VIRGL_SERVER_PROFILE_OVERRIDE" ] && [ -f "$VIRGL_PROFILE_SENTINEL_FILE" ]; then
    SENTINEL_PROFILE="$(head -1 "$VIRGL_PROFILE_SENTINEL_FILE" 2>/dev/null | tr -d '[:space:]')"
    if [ -n "$SENTINEL_PROFILE" ]; then
        VIRGL_SERVER_PROFILE_OVERRIDE="$SENTINEL_PROFILE"
        echo "VirGL profile sentinel active: override='$SENTINEL_PROFILE' (from $VIRGL_PROFILE_SENTINEL_FILE)" | tee -a "$LOGFILE"
    fi
fi

acquire_launch_lock

find_pids_by_cmdline() {
    local pattern="$1"
    local proc cmdline
    for proc in /proc/[0-9]*; do
        [ -r "$proc/cmdline" ] || continue
        cmdline="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
        case "$cmdline" in
            *"$pattern"*)
                basename "$proc"
                ;;
        esac
    done
}

stop_termux_x11() {
    local pid=""
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

# ===================================================================
# Credential injection — MUST happen before cleanup_previous because
# the old launch script's EXIT trap deletes .rlt-launch-env.sh
# ===================================================================
if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    echo "Sourcing credentials from env file..." | tee -a "$LOGFILE"
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    rm -f "$ENV_FILE"
    # Never log credential values — only confirm presence
    [ -n "${JX_SESSION_ID:-}" ] && echo "  JX_SESSION_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_CHARACTER_ID:-}" ] && echo "  JX_CHARACTER_ID=***" | tee -a "$LOGFILE"
    [ -n "${JX_DISPLAY_NAME:-}" ] && echo "  JX_DISPLAY_NAME=***" | tee -a "$LOGFILE"
else
    echo "No credentials env file provided — RuneLite will show its own login" | tee -a "$LOGFILE"
fi

# ===================================================================
# Clean shutdown of any previous session
# ===================================================================
# Kill everything from a prior run so we start fresh every time.
# This prevents zombie processes when the Android app restarts us.
cleanup_previous() {
    echo "Cleaning up previous session..." | tee -a "$LOGFILE"
    local killed=0
    local pid=""

    # Kill Java (RuneLite) inside proot — match the main class or jar
    for pid in $(pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null) \
               $(pgrep -f 'RuneLite.jar' 2>/dev/null); do
        kill "$pid" 2>/dev/null && killed=$((killed+1))
    done

    # Kill openbox window manager
    pkill -f 'openbox' 2>/dev/null && killed=$((killed+1))

    # Kill any lingering proot-distro sessions
    pkill -f 'proot-distro' 2>/dev/null && killed=$((killed+1))
    pkill -f 'proot --' 2>/dev/null && killed=$((killed+1))

    # Kill PulseAudio (we'll restart it fresh)
    pulseaudio --kill 2>/dev/null && killed=$((killed+1))

    # Kill previous Termux:X11 server process by cmdline. `ps` only reports the
    # binary name (`app_process`) on this tablet, so match /proc/*/cmdline.
    for pid in $(find_pids_by_cmdline 'com.termux.x11.Loader') \
               $(find_pids_by_cmdline 'com.termux.x11.CmdEntryPoint') \
               $(find_pids_by_cmdline 'termux-x11'); do
        kill "$pid" 2>/dev/null && killed=$((killed+1))
    done
    stop_termux_x11
    sleep 0.5

    # Kill previous perf monitor if still running
    pkill -f 'perf-monitor.log' 2>/dev/null && killed=$((killed+1))

    # Kill virgl server and clean up socket — UNLESS the external-virgl sentinel
    # is active (Slice 4.5 proof gate). If so, leave the pre-spawned server
    # running; the later external-virgl detection block will adopt it.
    if [ ! -f "$HOME/.rlt-external-virgl" ]; then
        pkill -f 'virgl_test_server' 2>/dev/null && killed=$((killed+1))
        rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true
    fi

    # Clean up stale credential, PID, and sentinel files
    # NOTE: Do NOT delete $HOME/.rlt-launch-env.sh here — it hasn't been read yet.
    # It gets cleaned up after sourcing (line 115) and in cleanup_on_exit().
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true
    rm -f "$INTERNAL_X11_LOGFILE" 2>/dev/null || true

    # Brief pause to let processes die
    if [ "$killed" -gt 0 ]; then
        echo "  Killed $killed leftover process(es), waiting for cleanup..." | tee -a "$LOGFILE"
        sleep 1
    else
        echo "  No leftover processes found" | tee -a "$LOGFILE"
    fi
}

cleanup_previous

SESSION_ID="$(date +%Y%m%d-%H%M%S)-$$"
SESSION_DIR="$SESSION_ROOT_DIR/$SESSION_ID"
mkdir -p "$SESSION_DIR"
printf '%s\n' "$SESSION_DIR" > "$CURRENT_SESSION_FILE"
write_session_value launcher.pid "$$"
write_session_value state starting
echo "Session directory: $SESSION_DIR" | tee -a "$LOGFILE"

# ===================================================================
# EXIT trap — comprehensive cleanup when this session ends
# ===================================================================
cleanup_on_exit() {
    echo "" | tee -a "$LOGFILE"
    echo "=== Shutting down RuneLite session $(date) ===" | tee -a "$LOGFILE"

    # Kill perf monitor and affinity monitor
    [ -n "${PERF_MONITOR_PID:-}" ] && kill "$PERF_MONITOR_PID" 2>/dev/null
    [ -n "${AFFINITY_MONITOR_PID:-}" ] && kill "$AFFINITY_MONITOR_PID" 2>/dev/null
    # Kill RuneLite client.log tail (feeds DebugLogServer via logcat tag RLClient)
    [ -n "${RL_LOG_TAIL_PID:-}" ] && kill "$RL_LOG_TAIL_PID" 2>/dev/null

    # Kill Java/RuneLite
    pkill -f 'net.runelite.client.RuneLite' 2>/dev/null || true
    pkill -f 'RuneLite.jar' 2>/dev/null || true

    # Kill openbox
    pkill -f 'openbox' 2>/dev/null || true

    # Kill proot sessions
    pkill -f 'proot-distro' 2>/dev/null || true
    pkill -f 'proot --' 2>/dev/null || true

    # Stop PulseAudio
    pulseaudio --kill 2>/dev/null || true

    # Kill virgl server and clean up socket
    [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null
    pkill -f 'virgl_test_server' 2>/dev/null || true
    rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true

    # Kill X11 server (binary is 'com.termux.x11.Loader', not 'termux-x11')
    [ -n "${X11_PID:-}" ] && kill "$X11_PID" 2>/dev/null
    stop_termux_x11

    # Send stop broadcast to Termux:X11 Android app
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

    # Clean up credential, PID, and sentinel files
    # NOTE: Do NOT delete $HOME/.rlt-launch-env.sh here — a new launch may have
    # deployed one already. It's cleaned up after sourcing at the top of this script.
    rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
    rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true
    write_session_value state stopped
    clear_current_session_marker
    rm -rf "$SESSION_DIR" 2>/dev/null || true
    release_launch_lock

    echo "Shutdown complete" | tee -a "$LOGFILE"
}

trap cleanup_on_exit EXIT

# ===================================================================
# Start services
# ===================================================================
# Start PulseAudio for game audio
echo "Starting PulseAudio..." | tee -a "$LOGFILE"
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1" \
    --exit-idle-time=-1 2>&1 | tee -a "$LOGFILE" || true

# Start Termux:X11
echo "Starting Termux:X11..." | tee -a "$LOGFILE"
if [ ! -x "$TERMUX_X11_BIN" ]; then
    echo "ERROR: Termux:X11 launcher missing at $TERMUX_X11_BIN" | tee -a "$LOGFILE"
    exit 1
fi

# Enable Present extension zero-copy flip for VirGL-imported FD buffers.
# Without this, loriePresentFlip rejects imported buffers and every frame
# goes through the slow damage-copy path (caps at ~50 FPS).
# The rejection was added for Turnip (Adreno) — Mali + VirGL is unaffected.
export TERMUX_X11_FORCE_FLIP=1
echo "TERMUX_X11_FORCE_FLIP=$TERMUX_X11_FORCE_FLIP (Present flip enabled for VirGL)" | tee -a "$LOGFILE"

# Slice 4: sticky AHB lock. When sentinel $HOME/.rlt-sticky-ahb exists, skip
# the defensive unlock/relock in lorieRedraw (InitOutput.c:~467). Safe on
# Mali/Immortalis because AHB is DMA-coherent. Default off for A/B baseline.
STICKY_AHB_SENTINEL="$HOME/.rlt-sticky-ahb"
if [ -f "$STICKY_AHB_SENTINEL" ]; then
    export RLT_STICKY_AHB_LOCK=1
    echo "STICKY-AHB: enabled via sentinel $STICKY_AHB_SENTINEL" | tee -a "$LOGFILE"
else
    export RLT_STICKY_AHB_LOCK=0
    echo "STICKY-AHB: disabled (sentinel $STICKY_AHB_SENTINEL absent, upstream unlock/relock preserved)" | tee -a "$LOGFILE"
fi

# FPS probe sentinel companion: $HOME/.rlt-fps-probe enables the FpsPlugin
# overlay + unlocks gpu.fpsTarget/fpscontrol.maxFps per RL profile. Matches
# the existing RLT_DEBUG_FPS_PROBE=1 gate below (session 71).
FPS_PROBE_SENTINEL="$HOME/.rlt-fps-probe"
if [ -f "$FPS_PROBE_SENTINEL" ]; then
    export RLT_DEBUG_FPS_PROBE=1
    echo "FPS-PROBE: enabled via sentinel $FPS_PROBE_SENTINEL" | tee -a "$LOGFILE"
fi

mkdir -p "$X11_SOCKET_DIR"
if [ -n "$X11_OVERRIDE_PACKAGE" ]; then
    export TERMUX_X11_OVERRIDE_PACKAGE="$X11_OVERRIDE_PACKAGE"
    echo "Termux:X11 presentation variant: $X11_PRESENTATION_VARIANT server=$X11_SERVER_MODE override=$TERMUX_X11_OVERRIDE_PACKAGE" | tee -a "$LOGFILE"
else
    unset TERMUX_X11_OVERRIDE_PACKAGE
    echo "Termux:X11 presentation variant: $X11_PRESENTATION_VARIANT server=$X11_SERVER_MODE override=<none>" | tee -a "$LOGFILE"
fi
echo "Waiting for X11 socket..." | tee -a "$LOGFILE"
X11_READY=false
for attempt in 1 2; do
    if [ "$X11_SERVER_MODE" = "internal" ]; then
        APP_APK_PATH="$(pm path com.runelitetablet 2>/dev/null | head -n 1 | sed 's/^package://')"
        if [ -z "$APP_APK_PATH" ]; then
            echo "ERROR: failed to resolve com.runelitetablet base.apk for internal CmdEntryPoint launch" | tee -a "$LOGFILE"
            exit 1
        fi
        echo "Internal CmdEntryPoint attempt $attempt apk=$APP_APK_PATH" | tee -a "$LOGFILE"
        : > "$INTERNAL_X11_LOGFILE"
        env CLASSPATH="$APP_APK_PATH" /system/bin/app_process -Xnoimage-dex2oat / \
            --nice-name="termux-x11 com.runelitetablet :0" com.termux.x11.CmdEntryPoint :0 \
            > "$INTERNAL_X11_LOGFILE" 2>&1 &
    else
        "$TERMUX_X11_BIN" :0 &
    fi
    X11_PID=$!
    echo "X11 launch pid: $X11_PID" | tee -a "$LOGFILE"

    for i in $(seq 1 60); do
        if [ -e "$X11_SOCKET_DIR/X0" ]; then
            X11_READY=true
            break
        fi
        if ! kill -0 "$X11_PID" 2>/dev/null; then
            echo "X11 process $X11_PID exited before socket creation on poll $i" | tee -a "$LOGFILE"
            break
        fi
        sleep 0.2
    done

    [ "$X11_READY" = true ] && break

    echo "X11 socket attempt $attempt failed; restarting Termux:X11..." | tee -a "$LOGFILE"
    if [ "$X11_SERVER_MODE" = "internal" ]; then
        echo "--- internal x11 start log (attempt $attempt) ---" | tee -a "$LOGFILE"
        tail -n 200 "$INTERNAL_X11_LOGFILE" 2>&1 | tee -a "$LOGFILE" || true
        echo "--- internal x11 process snapshot (attempt $attempt) ---" | tee -a "$LOGFILE"
        toybox ps -A -o PID,PPID,NAME,ARGS 2>/dev/null | grep -E "($X11_PID|app_process|CmdEntryPoint|termux-x11)" | tee -a "$LOGFILE" || true
    fi
    stop_termux_x11
    sleep 1
done

if [ "$X11_READY" = false ]; then
    echo "ERROR: X11 socket not ready after 30 seconds" | tee -a "$LOGFILE"
    echo "Contents of $PREFIX/tmp/:" | tee -a "$LOGFILE"
    ls -laR "$PREFIX/tmp/" 2>&1 | tee -a "$LOGFILE"
    if [ "$X11_SERVER_MODE" = "internal" ]; then
        echo "" | tee -a "$LOGFILE"
        echo "Contents of $INTERNAL_X11_LOGFILE:" | tee -a "$LOGFILE"
        tail -n 200 "$INTERNAL_X11_LOGFILE" 2>&1 | tee -a "$LOGFILE" || true
        echo "" | tee -a "$LOGFILE"
        echo "Internal X11 process snapshot:" | tee -a "$LOGFILE"
        toybox ps -A -o PID,PPID,NAME,ARGS 2>/dev/null | grep -E '(app_process|CmdEntryPoint|termux-x11)' | tee -a "$LOGFILE" || true
    fi
    echo "" | tee -a "$LOGFILE"
    echo "Termux:X11 launch is handled by the Android app; socket never appeared." | tee -a "$LOGFILE"
    echo "" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
    exit 1
fi

echo "X11 socket ready" | tee -a "$LOGFILE"
write_session_value x11.pid "$X11_PID"
write_session_value state x11-ready
write_session_value x11.variant "$X11_PRESENTATION_VARIANT"
write_session_value x11.server "$X11_SERVER_MODE"
write_session_value x11.override "${TERMUX_X11_OVERRIDE_PACKAGE:-<stock>}"
write_session_value x11.internal.log "$INTERNAL_X11_LOGFILE"

if [ -n "$X11_HOST_COMPONENT" ]; then
    echo "Launching X11 host activity: $X11_HOST_COMPONENT" | tee -a "$LOGFILE"
    if am start --activity-single-top --activity-clear-top -n "$X11_HOST_COMPONENT" >/dev/null 2>&1; then
        echo "X11 host activity launched" | tee -a "$LOGFILE"
    else
        echo "WARNING: failed to launch X11 host activity $X11_HOST_COMPONENT" | tee -a "$LOGFILE"
    fi
else
    echo "No app-owned X11 host activity requested; using stock Termux:X11 activity" | tee -a "$LOGFILE"
fi

# Set Termux:X11 preferences from the shell as backup.
# The primary mechanism is the CHANGE_PREFERENCE broadcast sent from the Kotlin launch() method.
# Syntax: termux-x11-preference key:value (colon separator, NOT equals)
echo "Setting Termux:X11 preferences (shell backup)..." | tee -a "$LOGFILE"
if [ -x "$TERMUX_X11_PREF_BIN" ]; then
    timeout 5 "$TERMUX_X11_PREF_BIN" fullscreen:true 2>&1 | tee -a "$LOGFILE" || true
    timeout 5 "$TERMUX_X11_PREF_BIN" showAdditionalKbd:false 2>&1 | tee -a "$LOGFILE" || true
else
    echo "WARNING: Termux:X11 preference helper missing at $TERMUX_X11_PREF_BIN" | tee -a "$LOGFILE"
fi

# Build credential env var forwarding for proot.
# Env vars set in the outer Termux shell are NOT inherited by proot-distro login (Spike C result).
# We write a temp env file inside proot and source it in the bash -c block.
PROOT_ENV_FILE=""
if [ -n "${JX_SESSION_ID:-}" ]; then
    PROOT_ENV_FILE="/tmp/.rlt-creds-$$.sh"
    # Write the env file into the Termux tmp dir (which gets bind-mounted into proot as /tmp)
    TERMUX_ENV_FILE="$PREFIX/tmp/.rlt-creds-$$.sh"
    {
        printf "export JX_SESSION_ID=%q\n" "${JX_SESSION_ID}"
        [ -n "${JX_CHARACTER_ID:-}" ] && printf "export JX_CHARACTER_ID=%q\n" "${JX_CHARACTER_ID}"
        [ -n "${JX_DISPLAY_NAME:-}" ] && printf "export JX_DISPLAY_NAME=%q\n" "${JX_DISPLAY_NAME}"
    } > "$TERMUX_ENV_FILE"
    chmod 600 "$TERMUX_ENV_FILE"
    echo "Credential env file written for proot forwarding" | tee -a "$LOGFILE"
fi

# ===================================================================
# GPU acceleration setup
# ===================================================================
SCRIPTS_DIR="$HOME/scripts"
GPU_VENDOR=$("$SCRIPTS_DIR/detect-gpu.sh" 2>/dev/null || echo "unknown")
echo "GPU vendor detected: $GPU_VENDOR" | tee -a "$LOGFILE"

GPU_BIND=""
VIRGL_PID=""
PROOT_GPU_ENV=""

if [ "$GPU_VENDOR" = "adreno" ]; then
    # Adreno: bind kgsl device node for Zink+Turnip
    if [ -e /dev/kgsl-3d0 ]; then
        GPU_BIND="--bind /dev/kgsl-3d0:/dev/kgsl-3d0"
        PROOT_GPU_ENV="adreno"
        echo "Adreno GPU: binding /dev/kgsl-3d0" | tee -a "$LOGFILE"
    fi
elif [ "$GPU_VENDOR" = "mali" ]; then
    # Mali: native VirGL is the validated baseline on the tablet. Alternate
    # server profiles are exposed only for bounded A/B testing.
    # S4.5 proof gate: if $HOME/.rlt-external-virgl exists and a virgl socket
    # is already present, assume an externally-spawned virgl_test_server is
    # running (pinned into a wider cpuset than Termux's /background). Skip
    # our own spawn and reuse the existing socket.
    EXTERNAL_VIRGL_SENTINEL="$HOME/.rlt-external-virgl"
    if [ -f "$EXTERNAL_VIRGL_SENTINEL" ] && [ -S "$PREFIX/tmp/.virgl_test" ]; then
        EXISTING_VIRGL_PID=$(pgrep -f virgl_test_server_android | head -1 || true)
        if [ -n "$EXISTING_VIRGL_PID" ]; then
            VIRGL_PID="$EXISTING_VIRGL_PID"
            PROOT_GPU_ENV="mali-native"
            echo "VIRGL-EXTERNAL: using pre-spawned virgl PID=$VIRGL_PID socket=$PREFIX/tmp/.virgl_test (sentinel $EXTERNAL_VIRGL_SENTINEL)" | tee -a "$LOGFILE"
            echo "VIRGL-EXTERNAL: cpuset=$(cat /proc/$VIRGL_PID/cpuset 2>/dev/null) Cpus_allowed_list=$(grep Cpus_allowed_list /proc/$VIRGL_PID/status 2>/dev/null | awk '{print $2}')" | tee -a "$LOGFILE"
            write_session_value virgl.pid "$VIRGL_PID"
            write_session_value virgl.profile "external"
        else
            echo "VIRGL-EXTERNAL: sentinel set but no virgl_test_server_android process found; falling through to local spawn" | tee -a "$LOGFILE"
        fi
    fi

    if [ -z "$VIRGL_PID" ]; then
        pkill -f virgl_test_server 2>/dev/null || true
        sleep 0.5
    fi

    if [ -z "$VIRGL_PID" ] && ! command -v virgl_test_server_android >/dev/null 2>&1; then
        echo "virgl_test_server_android not found — software rendering" | tee -a "$LOGFILE"
    elif [ -z "$VIRGL_PID" ]; then
        VIRGL_SOCKET="$PREFIX/tmp/.virgl_test"
        VIRGL_SERVER_PROFILE="${RLT_VIRGL_SERVER_PROFILE:-${VIRGL_SERVER_PROFILE_OVERRIDE:-default}}"
        VIRGL_SERVER_ARGS=""
        VIRGL_SERVER_DESCRIPTION="native-gles"

        case "$VIRGL_SERVER_PROFILE" in
            default)
                VIRGL_SERVER_ARGS=""
                VIRGL_SERVER_DESCRIPTION="native-gles"
                ;;
            no-loop-or-fork)
                VIRGL_SERVER_ARGS="--no-loop-or-fork"
                VIRGL_SERVER_DESCRIPTION="native-gles no-loop-or-fork"
                ;;
            angle-gl)
                VIRGL_SERVER_ARGS="--angle-gl"
                VIRGL_SERVER_DESCRIPTION="angle-gl"
                ;;
            angle-vulkan)
                VIRGL_SERVER_ARGS="--angle-vulkan"
                VIRGL_SERVER_DESCRIPTION="angle-vulkan"
                ;;
            *)
                echo "WARNING: unknown VirGL server profile '$VIRGL_SERVER_PROFILE'; using default" | tee -a "$LOGFILE"
                VIRGL_SERVER_ARGS=""
                VIRGL_SERVER_DESCRIPTION="native-gles"
                VIRGL_SERVER_PROFILE="default"
                ;;
        esac

        VIRGL_LOG="$HOME/virgl-server.log"
        echo "Starting VirGL server profile='$VIRGL_SERVER_PROFILE' ($VIRGL_SERVER_DESCRIPTION)..." | tee -a "$LOGFILE"
        # VirGL server-side threading: pipeline command processing to reduce
        # per-frame socket round-trip cost. Previously behind RLT_VIRGL_TUNE flag;
        # now always enabled — no regressions observed, improves throughput.
        VIRGL_TUNE_ENVS="VIRGL_RENDERER_THREAD=1 VIRGL_RENDERER_ASYNC=1"
        echo "VirGL server tuning: $VIRGL_TUNE_ENVS" | tee -a "$LOGFILE"
        env -u LD_LIBRARY_PATH $VIRGL_TUNE_ENVS virgl_test_server_android $VIRGL_SERVER_ARGS > "$VIRGL_LOG" 2>&1 &
        VIRGL_PID=$!
        echo "VirGL server forked PID=$VIRGL_PID args='$VIRGL_SERVER_ARGS' log=$VIRGL_LOG" | tee -a "$LOGFILE"

        # Wait for server to be ready (PID alive AND socket exists)
        VIRGL_READY=false
        VIRGL_PROBE_START=$(date +%s%3N 2>/dev/null || date +%s)
        for i in $(seq 1 30); do
            ALIVE=$(kill -0 $VIRGL_PID 2>/dev/null && echo "yes" || echo "no")
            SOCKET_EXISTS=$([ -S "$VIRGL_SOCKET" ] && echo "yes" || echo "no")
            if [ "$ALIVE" = "yes" ] && [ "$SOCKET_EXISTS" = "yes" ]; then
                VIRGL_READY=true
                echo "VirGL health probe $i/30: pid_alive=$ALIVE socket=$SOCKET_EXISTS — READY" | tee -a "$LOGFILE"
                break
            fi
            if [ "$ALIVE" = "no" ]; then
                echo "VirGL health probe $i/30: pid_alive=no — server died during startup (exit=$(wait $VIRGL_PID 2>/dev/null; echo $?))" | tee -a "$LOGFILE"
                echo "VirGL stderr (last 20 lines):" | tee -a "$LOGFILE"
                tail -20 "$VIRGL_LOG" 2>/dev/null | tee -a "$LOGFILE"
                break
            fi
            # Only log every 5th probe to avoid spam
            if [ $((i % 5)) -eq 0 ]; then
                echo "VirGL health probe $i/30: pid_alive=$ALIVE socket=$SOCKET_EXISTS — waiting" | tee -a "$LOGFILE"
            fi
            sleep 0.2
        done

        if [ "$VIRGL_READY" = true ]; then
            echo "VirGL server started profile='$VIRGL_SERVER_PROFILE' (PID $VIRGL_PID, socket ready)" | tee -a "$LOGFILE"
            PROOT_GPU_ENV="mali-native"
            write_session_value virgl.pid "$VIRGL_PID"
            write_session_value virgl.profile "$VIRGL_SERVER_PROFILE"
            rlt_log_cpuset "virgl-ready" "$VIRGL_PID"
        else
            echo "VirGL server profile='$VIRGL_SERVER_PROFILE' failed (socket: $(ls -la "$VIRGL_SOCKET" 2>&1)), using software rendering" | tee -a "$LOGFILE"
            kill $VIRGL_PID 2>/dev/null || true
            rm -f "$VIRGL_SOCKET" 2>/dev/null || true
            VIRGL_PID=""
            PROOT_GPU_ENV=""
            rm -f "$(session_file virgl.pid)" 2>/dev/null || true
        fi
    fi
else
    echo "Unknown GPU vendor ($GPU_VENDOR) — software rendering" | tee -a "$LOGFILE"
fi

# Launch RuneLite inside proot.
# Bind-mount Termux's X11 socket directory into proot so DISPLAY=:0 can find it.
# Bind-mount GPU device node for hardware acceleration (silent failure if unavailable).
# Create sentinel file for health monitoring (SessionHealthMonitor checks this)
# $PREFIX/tmp is bind-mounted into proot as /tmp, so both sides can see it.
touch "$PREFIX/tmp/.rlt-session-alive"
echo "Session sentinel created" | tee -a "$LOGFILE"
write_session_value state backend-starting

# Re-check VirGL server is still alive (it may have crashed after initial health check)
if [ -n "$VIRGL_PID" ] && ! kill -0 "$VIRGL_PID" 2>/dev/null; then
    VIRGL_EXIT=$(wait "$VIRGL_PID" 2>/dev/null; echo $?)
    echo "WARNING: VirGL server died before launch (exit=$VIRGL_EXIT) — falling back to software rendering" | tee -a "$LOGFILE"
    echo "VirGL stderr (last 20 lines):" | tee -a "$LOGFILE"
    tail -20 "$HOME/virgl-server.log" 2>/dev/null | tee -a "$LOGFILE"
    VIRGL_PID=""
    PROOT_GPU_ENV=""
    rm -f "$(session_file virgl.pid)" 2>/dev/null || true
elif [ -n "$VIRGL_PID" ]; then
    echo "VirGL server pre-launch re-check: PID $VIRGL_PID alive, socket=$(ls -la "$PREFIX/tmp/.virgl_test" 2>&1 | tail -1)" | tee -a "$LOGFILE"
fi

# Set display resolution via Termux:X11 preferences (xrandr doesn't work — no transform support).
# GPU path: native resolution. Software path: half-res for 4x fewer pixels.
if [ -n "$PROOT_GPU_ENV" ]; then
    case "$GPU_DISPLAY_RESOLUTION_OVERRIDE" in
        "")
            echo "Setting display: native resolution (GPU available)" | tee -a "$LOGFILE"
            timeout 5 termux-x11-preference displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
            ;;
        native)
            echo "Setting display: forced native resolution (GPU override)" | tee -a "$LOGFILE"
            timeout 5 termux-x11-preference displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
            ;;
        custom:*)
            CUSTOM_GPU_RESOLUTION="${GPU_DISPLAY_RESOLUTION_OVERRIDE#custom:}"
            echo "Setting display: forced custom GPU resolution $CUSTOM_GPU_RESOLUTION" | tee -a "$LOGFILE"
            timeout 5 termux-x11-preference displayResolutionMode:custom "displayResolutionCustom:$CUSTOM_GPU_RESOLUTION" 2>&1 | tee -a "$LOGFILE" || true
            ;;
        *)
            echo "WARNING: unsupported GPU display resolution override '$GPU_DISPLAY_RESOLUTION_OVERRIDE'; using native" | tee -a "$LOGFILE"
            timeout 5 termux-x11-preference displayResolutionMode:native 2>&1 | tee -a "$LOGFILE" || true
            ;;
    esac
else
    echo "Setting display: 1480x924 (software rendering, half-res)" | tee -a "$LOGFILE"
    timeout 5 termux-x11-preference displayResolutionMode:custom displayResolutionCustom:1480x924 2>&1 | tee -a "$LOGFILE" || true
fi

# Start VirGL background watchdog (detects mid-session death and logs it)
if [ -n "$VIRGL_PID" ]; then
    (
        while kill -0 "$VIRGL_PID" 2>/dev/null; do
            sleep 10
        done
        VIRGL_EXIT=$(wait "$VIRGL_PID" 2>/dev/null; echo $?)
        echo "VIRGL_WATCHDOG: VirGL server died mid-session (PID=$VIRGL_PID exit=$VIRGL_EXIT) — GPU rendering will fail" | tee -a "$LOGFILE"
        echo "VIRGL_WATCHDOG: last 30 lines of virgl-server.log:" | tee -a "$LOGFILE"
        tail -30 "$HOME/virgl-server.log" 2>/dev/null | tee -a "$LOGFILE"
        # Write death marker so Kotlin side can detect it
        echo "dead:exit=$VIRGL_EXIT" > "$(session_file virgl.status)" 2>/dev/null || true
    ) &
    echo "VirGL watchdog started (background PID=$!)" | tee -a "$LOGFILE"
fi

# ── Phase 1: CPU core affinity pinning ──
# Pin VirGL server to big/prime cores (CPU 4-7, bitmask 0xF0)
BIG_PRIME_MASK="0xF0"
PRIME_MASK="0x80"  # CPU 7 only (prime, 3.4 GHz)

if [ -n "$VIRGL_PID" ]; then
    if taskset -p "$BIG_PRIME_MASK" "$VIRGL_PID" 2>/dev/null; then
        echo "AFFINITY: VirGL (PID=$VIRGL_PID) pinned to big/prime cores (mask=$BIG_PRIME_MASK)" | tee -a "$LOGFILE"
    else
        echo "AFFINITY: taskset failed for VirGL (PID=$VIRGL_PID), staying on default cores" | tee -a "$LOGFILE"
    fi
fi

# Start CPU affinity monitor in background (Task 0B + 1B persistence watchdog)
AFFINITY_MONITOR_SCRIPT="$HOME/scripts/monitor-cpu-affinity.sh"
AFFINITY_MONITOR_PID=""
if [ -f "$AFFINITY_MONITOR_SCRIPT" ] && [ -n "${VIRGL_PID:-}" ]; then
    bash "$AFFINITY_MONITOR_SCRIPT" "$VIRGL_PID" >> "$LOGFILE" 2>&1 &
    AFFINITY_MONITOR_PID=$!
    echo "AFFINITY: monitor started (PID=$AFFINITY_MONITOR_PID) tracking VirGL=$VIRGL_PID" | tee -a "$LOGFILE"
fi

echo "Launching RuneLite..." | tee -a "$LOGFILE"
echo "VirGL socket: $(ls -la "$PREFIX/tmp/.virgl_test" 2>&1)" | tee -a "$LOGFILE"
echo "Outer preflight: proot-distro=$(command -v proot-distro || echo missing) bash=$(command -v bash || echo missing) HOME=$HOME PREFIX=$PREFIX" | tee -a "$LOGFILE"
echo "Starting proot smoke test..." | tee -a "$LOGFILE"
proot-distro login ubuntu --shared-tmp $GPU_BIND -- bash -lc 'echo PROOT_SMOKE_OK; echo HOME=$HOME USER=${USER:-<unset>} PWD=$PWD; id; ls -ld /root /root/runelite 2>&1' 2>&1 | tee -a "$LOGFILE"
PROOT_SMOKE_EXIT=${PIPESTATUS[0]}
echo "Proot smoke test exit code: $PROOT_SMOKE_EXIT" | tee -a "$LOGFILE"
if [ "$PROOT_SMOKE_EXIT" -ne 0 ]; then
    exit "$PROOT_SMOKE_EXIT"
fi

PROOT_LAUNCH_SCRIPT_TERMUX="$PREFIX/tmp/.rlt-proot-launch-$SESSION_ID.sh"
PROOT_LAUNCH_SCRIPT_IN_PROOT="/tmp/.rlt-proot-launch-$SESSION_ID.sh"
cat > "$PROOT_LAUNCH_SCRIPT_TERMUX" <<'PROOT_LAUNCH_EOF'
#!/bin/bash
set -uo pipefail

export DISPLAY=:0
export PULSE_SERVER=tcp:127.0.0.1:4713
SESSION_DIR_IN_PROOT="${RLT_SESSION_DIR_IN_PROOT:?missing RLT_SESSION_DIR_IN_PROOT}"
PROOT_ENV_FILE="${RLT_PROOT_ENV_FILE:-}"
PROOT_GPU_ENV="${RLT_PROOT_GPU_ENV:-}"
PERF_MONITOR_ENABLED="${RLT_INNER_PERF_MONITOR_ENABLED:-0}"
RUNELITE_TARGET_FPS="${RLT_INNER_TARGET_FPS:-120}"
RUNELITE_UNLOCK_FPS="${RLT_INNER_UNLOCK_FPS:-true}"
RUNELITE_VSYNC_MODE="${RLT_INNER_VSYNC_MODE:-OFF}"
RUNELITE_SCALE_VALUE="${RLT_INNER_SCALE:-2}"
JAVA2D_PROFILE="${RLT_INNER_JAVA2D_PROFILE:-default}"
PERF_PID=''

if [ -n "$PROOT_ENV_FILE" ] && [ -f "$PROOT_ENV_FILE" ]; then
    source "$PROOT_ENV_FILE"
    rm -f "$PROOT_ENV_FILE"
fi

mkdir -p "$SESSION_DIR_IN_PROOT"
echo "Proot preflight: HOME=$HOME USER=${USER:-<unset>} PWD=$PWD" >&2
id >&2 || true
echo "Proot preflight: root dirs" >&2
ls -ld /root /root/runelite >&2 || true
echo "Proot preflight: commands java=$(command -v java || echo missing) openbox=$(command -v openbox || echo missing) xrandr=$(command -v xrandr || echo missing) glxinfo=$(command -v glxinfo || echo missing) glxgears=$(command -v glxgears || echo missing)" >&2

sleep 0.5
export MESA_NO_ERROR=1
GPU_AVAILABLE=false

if [ "$PROOT_GPU_ENV" = "adreno" ]; then
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export GALLIUM_DRIVER=zink
    export TU_DEBUG=noconform
    export MESA_NO_ERROR=1
    export ZINK_DESCRIPTORS=lazy

    GL_RENDERER=$(glxinfo 2>/dev/null | grep -i "opengl renderer" || true)
    if echo "$GL_RENDERER" | grep -qi "zink"; then
        GPU_AVAILABLE=true
        echo "GPU acceleration: ENABLED (Zink+Turnip)" >&2
    else
        echo "GPU acceleration: Zink failed, falling back to software" >&2
        unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER TU_DEBUG MESA_NO_ERROR ZINK_DESCRIPTORS
    fi
elif [ "$PROOT_GPU_ENV" = "mali-angle" ] || [ "$PROOT_GPU_ENV" = "mali-native" ]; then
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME=/tmp/.virgl_test
    export MESA_GLX_ALPHA_BITS=0
    export MESA_NO_ERROR=1
    export MESA_EXTENSION_OVERRIDE=-GL_ARB_depth_clamp,-GL_EXT_depth_clamp

    VIRGL_SOCKET_STATUS=$(ls -la /tmp/.virgl_test 2>&1)
    echo "VirGL socket visible: $VIRGL_SOCKET_STATUS" >&2
    if [ ! -S /tmp/.virgl_test ]; then
        echo "VIRGL_VALIDATION: FAIL — socket /tmp/.virgl_test does not exist or is not a socket" >&2
        echo "VIRGL_VALIDATION: This means the VirGL server (running in Termux) is not reachable from proot" >&2
        unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_GLX_ALPHA_BITS MESA_NO_ERROR MESA_EXTENSION_OVERRIDE
    else
        GEARS_OUTPUT=$(timeout 3 glxgears -info 2>&1 || true)
        GL_RENDERER=$(echo "$GEARS_OUTPUT" | grep -i "GL_RENDERER" | head -1 || true)
        GL_VENDOR=$(echo "$GEARS_OUTPUT" | grep -i "GL_VENDOR" | head -1 || true)
        GEARS_FPS=$(echo "$GEARS_OUTPUT" | grep -i "frames in" | head -1 || true)
        echo "VirGL glxgears: renderer='$GL_RENDERER' vendor='$GL_VENDOR' fps='$GEARS_FPS'" >&2

        if echo "$GL_RENDERER" | grep -qi "virgl"; then
            OVERRIDE="4.3COMPAT"
            GLSL_OVERRIDE="430"
            export MESA_GL_VERSION_OVERRIDE="$OVERRIDE"
            export MESA_GLSL_VERSION_OVERRIDE="$GLSL_OVERRIDE"
            GPU_AVAILABLE=true
            echo "VIRGL_VALIDATION: PASS — GL=$OVERRIDE GLSL=$GLSL_OVERRIDE renderer=$GL_RENDERER" >&2

            # Phase 3B: VirGL pipeline tuning (behind feature flag)
            if [ "${RLT_VIRGL_TUNE:-0}" = "1" ]; then
                export MESA_GLSL_CACHE_DISABLE=0
                export MESA_SHADER_CACHE_MAX_SIZE=256M
                echo "VIRGL_TUNE: shader cache enabled (MESA_GLSL_CACHE_DISABLE=0 MESA_SHADER_CACHE_MAX_SIZE=256M)" >&2
            fi

            # Phase 3D: GL call profiling (behind feature flag, diagnostic only)
            if [ -n "${RLT_GALLIUM_HUD:-}" ]; then
                export GALLIUM_HUD="$RLT_GALLIUM_HUD"
                echo "GALLIUM_HUD: profiling enabled ($GALLIUM_HUD)" >&2
            fi
        else
            echo "VIRGL_VALIDATION: FAIL — GL_RENDERER='$GL_RENDERER' does not contain 'virgl'" >&2
            echo "VIRGL_VALIDATION: Socket exists but rendering pipeline broken — check virgl-server.log" >&2
            unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_GLX_ALPHA_BITS MESA_NO_ERROR MESA_EXTENSION_OVERRIDE
        fi
    fi
else
    echo "GPU acceleration: UNAVAILABLE (software rendering)" >&2
fi

mkdir -p /root/.config/openbox
cat > /root/.config/openbox/rc.xml <<'OBCFG'
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
openbox --sm-disable &
OPENBOX_PID=$!
echo "Openbox launch pid=$OPENBOX_PID cwd=$PWD" >&2
echo "$OPENBOX_PID" > "$SESSION_DIR_IN_PROOT/openbox.pid"
sleep 0.5

cd /root/runelite || {
    echo "ERROR: failed to cd to /root/runelite" >&2
    exit 1
}
echo "Proot preflight: entered $(pwd)" >&2
ls -la >&2 || true

PERF_LOG="/root/runelite/perf-monitor.log"
echo '=== Performance monitor started ===' > "$PERF_LOG"
if [ "$PERF_MONITOR_ENABLED" = "1" ]; then
    (
        while true; do
            echo "--- $(date +%H:%M:%S) ---" >> "$PERF_LOG"
            free -m | head -2 >> "$PERF_LOG"
            cat /proc/loadavg >> "$PERF_LOG"
            ps aux --sort=-%cpu 2>/dev/null | head -6 >> "$PERF_LOG" || true
            echo '' >> "$PERF_LOG"
            sleep 5
        done
    ) &
    PERF_PID=$!
    echo "$PERF_PID" > "$SESSION_DIR_IN_PROOT/perf.pid"
else
    echo 'Performance monitor disabled for normal launches' >> "$PERF_LOG"
fi

echo '=== Display info ===' | tee -a "$PERF_LOG"
xrandr 2>&1 | tee -a "$PERF_LOG" || true
echo '=== GL info ===' | tee -a "$PERF_LOG"
glxinfo 2>/dev/null | grep -iE '(renderer|version|vendor|direct)' | tee -a "$PERF_LOG" || true
echo ready > "$SESSION_DIR_IN_PROOT/display.ready"

GC_LOG_FLAGS="-Xlog:gc*:file=/root/runelite/gc.log:time,uptime,level,tags:filecount=2,filesize=5M"
RUNELITE_SETTINGS_DIR="/root/.runelite"
RUNELITE_SETTINGS_FILE="$RUNELITE_SETTINGS_DIR/settings.properties"
mkdir -p "$RUNELITE_SETTINGS_DIR"
touch "$RUNELITE_SETTINGS_FILE"
MESA_PROFILE="${RLT_INNER_MESA_PROFILE:-${MESA_PROFILE_OVERRIDE:-default}}"
VIRGL_SERVER_PROFILE="${RLT_VIRGL_SERVER_PROFILE:-${VIRGL_SERVER_PROFILE_OVERRIDE:-default}}"
RUNELITE_LAUNCH_MODE="${RLT_RUNELITE_LAUNCH_MODE:-${RUNELITE_LAUNCH_MODE_OVERRIDE:-launcher}}"
upsert_runelite_setting() {
    local key="$1"
    local value="$2"
    if grep -q "^${key}=" "$RUNELITE_SETTINGS_FILE" 2>/dev/null; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$RUNELITE_SETTINGS_FILE"
    else
        printf '%s=%s\n' "$key" "$value" >> "$RUNELITE_SETTINGS_FILE"
    fi
}
remove_runelite_setting() {
    local key="$1"
    sed -i "/^${key}=.*/d" "$RUNELITE_SETTINGS_FILE" 2>/dev/null || true
}
upsert_runelite_setting "gpu.unlockFps" "$RUNELITE_UNLOCK_FPS"
upsert_runelite_setting "gpu.fpsTarget" "$RUNELITE_TARGET_FPS"
upsert_runelite_setting "gpu.vsyncMode" "$RUNELITE_VSYNC_MODE"
upsert_runelite_setting "runelite.gpuplugin" "true"
echo "Configured RuneLite GPU settings: unlockFps=$RUNELITE_UNLOCK_FPS fpsTarget=$RUNELITE_TARGET_FPS vsyncMode=$RUNELITE_VSYNC_MODE" >&2
upsert_runelite_setting "fpscontrol.limitFps" "false"
upsert_runelite_setting "fpscontrol.limitFpsUnfocused" "false"
upsert_runelite_setting "fpscontrol.maxFps" "360"
upsert_runelite_setting "fpscontrol.maxFpsUnfocused" "15"
upsert_runelite_setting "fpscontrol.drawFps" "false"
remove_runelite_setting "gpu.drawDistance"
remove_runelite_setting "gpu.expandedMapLoadingChunks"
remove_runelite_setting "gpu.smoothBanding"
remove_runelite_setting "gpu.antiAliasingMode"
# RuneLite 1.12.24 rejects NONE for gpu.uiScalingMode and falls back to HYBRID
# after logging a config unmarshal exception. Keep the profile valid so GPU
# startup is not blocked by a known-bad enum value.
upsert_runelite_setting "gpu.uiScalingMode" "HYBRID"
remove_runelite_setting "gpu.anisotropicFilteringLevel"
remove_runelite_setting "gpu.removeVertexSnapping"
echo "Configured RuneLite GPU profile: default (cleared branch-forced GPU profile overrides)" >&2

case "$MESA_PROFILE" in
    default)
        unset mesa_glthread
        unset LIBGL_DRI3_DISABLE
        echo "Configured Mesa profile: default" >&2
        ;;
    glthread)
        export mesa_glthread=true
        unset LIBGL_DRI3_DISABLE
        echo "Configured Mesa profile: glthread mesa_glthread=true" >&2
        ;;
    dri3-off)
        unset mesa_glthread
        export LIBGL_DRI3_DISABLE=1
        echo "Configured Mesa profile: dri3-off LIBGL_DRI3_DISABLE=1" >&2
        ;;
    *)
        unset mesa_glthread
        unset LIBGL_DRI3_DISABLE
        echo "WARNING: unknown Mesa profile '$MESA_PROFILE'; using default" >&2
        ;;
esac

SCALE_FLAG="--scale $RUNELITE_SCALE_VALUE"
case "$JAVA2D_PROFILE" in
    default)
        if [ "$GPU_AVAILABLE" = true ]; then
            GPU_FLAGS=''
            echo 'Using GPU-accelerated rendering' >&2
        else
            GPU_FLAGS='-Dsun.java2d.opengl=false -Dsun.java2d.xrender=true -Dsun.java2d.pmoffscreen=true'
            echo 'Using software rendering (half-res for performance)' >&2
        fi
        ;;
    xrender)
        GPU_FLAGS='-Dsun.java2d.opengl=false -Dsun.java2d.xrender=true -Dsun.java2d.pmoffscreen=true'
        echo 'Using forced Java2D XRender profile' >&2
        ;;
    opengl)
        GPU_FLAGS='-Dsun.java2d.opengl=true -Dsun.java2d.xrender=false -Dsun.java2d.pmoffscreen=false'
        echo 'Using forced Java2D OpenGL profile' >&2
        ;;
    *)
        GPU_FLAGS=''
        echo "WARNING: unknown Java2D profile '$JAVA2D_PROFILE'; using default flags" >&2
        ;;
esac

export RUNELITE_VMARGS="-Xmx4g -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 $GC_LOG_FLAGS $GPU_FLAGS"
echo "RUNELITE_VMARGS=$RUNELITE_VMARGS" >&2
echo "Configured Java2D profile: $JAVA2D_PROFILE" >&2
echo "Configured RuneLite scale: $RUNELITE_SCALE_VALUE" >&2
echo "Configured VirGL server profile: $VIRGL_SERVER_PROFILE" >&2
echo "Configured RuneLite launch mode: $RUNELITE_LAUNCH_MODE" >&2

extract_direct_classpath_from_log() {
    local log_path="${1:-/root/runelite-launch.log}"
    local direct_line
    direct_line="$(grep -F 'JvmLauncher - Running [' "$log_path" 2>/dev/null | tail -n 1 || true)"
    if [ -z "$direct_line" ]; then
        return 1
    fi

    direct_line="${direct_line#* -cp, }"
    direct_line="${direct_line%%, -XX:+DisableAttachMechanism*}"
    if [ -z "$direct_line" ]; then
        return 1
    fi

    printf '%s\n' "$direct_line"
}

apply_direct_classpath_overrides() {
    local original_classpath="$1"
    local override_dir="$2"
    local entry basename override_path
    local updated_classpath=""
    local replaced_count=0
    local IFS=':'

    [ -n "$original_classpath" ] || return 1
    [ -d "$override_dir" ] || {
        printf '%s\n' "$original_classpath"
        return 0
    }

    for entry in $original_classpath; do
        basename="${entry##*/}"
        override_path="$override_dir/$basename"
        if [ -f "$override_path" ]; then
            entry="$override_path"
            replaced_count=$((replaced_count + 1))
        fi

        if [ -n "$updated_classpath" ]; then
            updated_classpath="${updated_classpath}:$entry"
        else
            updated_classpath="$entry"
        fi
    done

    if [ "$replaced_count" -gt 0 ]; then
        echo "Applied $replaced_count direct RuneLite classpath override(s) from $override_dir" >&2
    fi

    printf '%s\n' "$updated_classpath"
}

DIRECT_CLASSPATH_FILE="$RUNELITE_SETTINGS_DIR/direct-classpath.txt"
DIRECT_CLASSPATH_OVERRIDE_DIR="$RUNELITE_SETTINGS_DIR/repository2-overrides"
DIRECT_MAIN_CLASS="net.runelite.client.RuneLite"
CLIENT_ARGS="--insecure-write-credentials --debug"

case "$RUNELITE_LAUNCH_MODE" in
    launcher)
        echo "Running RuneLite via launcher (scale=$SCALE_FLAG)" >&2
        java -jar RuneLite.jar $SCALE_FLAG --insecure-write-credentials --debug &
        JAVA_PID=$!
        ;;
    direct-jvm)
        DIRECT_CLASSPATH="${RLT_DIRECT_CLASSPATH:-}"
        if [ -n "$DIRECT_CLASSPATH" ]; then
            DIRECT_CLASSPATH="$(printf '%s' "$DIRECT_CLASSPATH" | tr -d '\r')"
        fi
        if [ -z "$DIRECT_CLASSPATH" ] && [ -f "$DIRECT_CLASSPATH_FILE" ]; then
            DIRECT_CLASSPATH="$(tr -d '\r' < "$DIRECT_CLASSPATH_FILE" 2>/dev/null || true)"
        fi
        if [ -z "$DIRECT_CLASSPATH" ]; then
            DIRECT_CLASSPATH="$(extract_direct_classpath_from_log || true)"
        fi
        if [ -z "$DIRECT_CLASSPATH" ]; then
            echo "ERROR: direct-jvm launch requested but no saved RuneLite classpath was found" >&2
            exit 1
        fi
        DIRECT_CLASSPATH="$(apply_direct_classpath_overrides "$DIRECT_CLASSPATH" "$DIRECT_CLASSPATH_OVERRIDE_DIR")"

        echo "Running RuneLite direct-jvm with saved classpath" >&2
        bash -lc "exec java $RUNELITE_VMARGS -cp \"$DIRECT_CLASSPATH\" $DIRECT_MAIN_CLASS $CLIENT_ARGS" &
        JAVA_PID=$!
        ;;
    *)
        echo "ERROR: unsupported RuneLite launch mode '$RUNELITE_LAUNCH_MODE'" >&2
        exit 1
        ;;
esac

echo "$JAVA_PID" > /root/.rlt-session.pid
echo "$JAVA_PID" > "$SESSION_DIR_IN_PROOT/java.pid"
echo running > "$SESSION_DIR_IN_PROOT/state"
echo "$RUNELITE_LAUNCH_MODE" > "$SESSION_DIR_IN_PROOT/runelite.launch.mode"
echo "RuneLite started with PID $JAVA_PID" >&2

# S75 Path A: record java cpuset so we can confirm producer inherited /top-app.
# /proc is visible inside proot so /proc/PID/cpuset resolves against the host kernel.
JAVA_CPUSET=$(cat "/proc/$JAVA_PID/cpuset" 2>/dev/null || echo "<unreadable>")
echo "CPUSET java-started pid=$JAVA_PID cpuset=$JAVA_CPUSET" >&2
if [ -x /system/bin/log ]; then
    /system/bin/log -t RLT -p i "CPUSET java-started pid=$JAVA_PID cpuset=$JAVA_CPUSET" 2>/dev/null || true
fi

# Pin RuneLite Java process to big/prime cores (CPU 4-7)
if taskset -p 0xF0 "$JAVA_PID" 2>/dev/null; then
    echo "AFFINITY: RuneLite (PID=$JAVA_PID) pinned to big/prime cores" >&2
else
    echo "AFFINITY: taskset unavailable in proot for RuneLite (PID=$JAVA_PID)" >&2
fi
# Pin proot itself to prime core (CPU 7) — proot is single-threaded
PROOT_PID=$(cat /proc/self/stat 2>/dev/null | awk '{print $4}')
if [ -n "$PROOT_PID" ] && taskset -p 0x80 "$PROOT_PID" 2>/dev/null; then
    echo "AFFINITY: proot (PID=$PROOT_PID) pinned to prime core" >&2
fi

wait $JAVA_PID
JAVA_EXIT=$?
if [ "$RUNELITE_LAUNCH_MODE" = "launcher" ]; then
    LAST_DIRECT_CLASSPATH="$(extract_direct_classpath_from_log || true)"
    if [ -n "$LAST_DIRECT_CLASSPATH" ]; then
        printf '%s\n' "$LAST_DIRECT_CLASSPATH" > "$DIRECT_CLASSPATH_FILE"
    fi
fi
rm -f /root/.rlt-session.pid
rm -f "$SESSION_DIR_IN_PROOT/java.pid"
rm -f /tmp/.rlt-session-alive
[ -n "$PERF_PID" ] && kill "$PERF_PID" 2>/dev/null || true
rm -f "$SESSION_DIR_IN_PROOT/perf.pid" "$SESSION_DIR_IN_PROOT/openbox.pid"
exit $JAVA_EXIT
PROOT_LAUNCH_EOF
chmod 700 "$PROOT_LAUNCH_SCRIPT_TERMUX"
echo "Wrote proot launch script: $PROOT_LAUNCH_SCRIPT_TERMUX" | tee -a "$LOGFILE"
OUTER_DIRECT_CLASSPATH="${RLT_DIRECT_CLASSPATH:-}"
if [ -n "$OUTER_DIRECT_CLASSPATH" ]; then
    OUTER_DIRECT_CLASSPATH="$(printf '%s' "$OUTER_DIRECT_CLASSPATH" | tr -d '\r')"
fi
if [ -z "$OUTER_DIRECT_CLASSPATH" ] && [ -f "$TERMUX_DIRECT_CLASSPATH_FILE" ]; then
    OUTER_DIRECT_CLASSPATH="$(tr -d '\r' < "$TERMUX_DIRECT_CLASSPATH_FILE" 2>/dev/null || true)"
fi
if [ -z "$OUTER_DIRECT_CLASSPATH" ] && [ -f "$LOGFILE" ]; then
    OUTER_DIRECT_CLASSPATH="$(extract_direct_classpath_from_log "$LOGFILE" || true)"
fi
if [ -n "$OUTER_DIRECT_CLASSPATH" ]; then
    echo "Resolved direct RuneLite classpath from Termux state" | tee -a "$LOGFILE"
fi
# Phase 4B: proot seccomp-bpf mode (behind feature flag)
# Reduces ptrace overhead by filtering syscalls in kernel space (~5us vs ~50us)
if [ "${RLT_PROOT_SECCOMP:-0}" = "1" ]; then
    export PROOT_NO_SECCOMP=0
    echo "PTRACE_OPT: seccomp-bpf mode enabled via PROOT_NO_SECCOMP=0" | tee -a "$LOGFILE"
else
    echo "PTRACE_OPT: seccomp-bpf disabled (set RLT_PROOT_SECCOMP=1 to enable)" | tee -a "$LOGFILE"
fi

# ===================================================================
# RuneLite observability
# - Tail client.log → Android logcat tag 'RLClient' so DebugLogServer
#   forwards GPU plugin / rlawt / FPS events to the debug viewer.
# - OPT-IN FPS probe (RLT_DEBUG_FPS_PROBE=1): overwrites RuneLite
#   profile keys gpu.fpsTarget / fpscontrol.maxFps / fpscontrol.drawFps
#   to lift caps and show the on-screen FPS overlay. Default OFF so
#   user-configured profiles are not clobbered on every launch.
#   When the flag is OFF and a *.rlt-orig backup exists, the profile is
#   restored from the backup (reverts any prior probe run).
# All edits are to user config files RuneLite itself writes; nothing is
# modified in the RuneLite binaries.
# ===================================================================
ROOTFS_PATH="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
RL_PROFILE_DIR="$ROOTFS_PATH/root/.runelite/profiles2"
RL_CLIENT_LOG="$ROOTFS_PATH/root/.runelite/logs/client.log"

if [ -d "$RL_PROFILE_DIR" ]; then
    if [ "${RLT_DEBUG_FPS_PROBE:-0}" = "1" ]; then
        for CFG in "$RL_PROFILE_DIR"/*.properties; do
            [ -f "$CFG" ] || continue
            [ -f "$CFG.rlt-orig" ] || cp "$CFG" "$CFG.rlt-orig"
            sed -i \
                -e 's/^gpu\.fpsTarget=.*/gpu.fpsTarget=0/' \
                -e 's/^fpscontrol\.maxFps=.*/fpscontrol.maxFps=999/' \
                -e 's/^fpscontrol\.drawFps=.*/fpscontrol.drawFps=true/' \
                "$CFG"
            grep -q '^gpu\.fpsTarget='       "$CFG" || echo 'gpu.fpsTarget=0'        >> "$CFG"
            grep -q '^fpscontrol\.maxFps='   "$CFG" || echo 'fpscontrol.maxFps=999'   >> "$CFG"
            grep -q '^fpscontrol\.drawFps='  "$CFG" || echo 'fpscontrol.drawFps=true' >> "$CFG"
            echo "RL-CONFIG: probe-on — patched $(basename "$CFG") gpu.fpsTarget=0 maxFps=999 drawFps=true" | tee -a "$LOGFILE"
        done
    else
        # Probe disabled — restore original profile if we previously clobbered it.
        for BAK in "$RL_PROFILE_DIR"/*.properties.rlt-orig; do
            [ -f "$BAK" ] || continue
            CFG="${BAK%.rlt-orig}"
            cp "$BAK" "$CFG"
            echo "RL-CONFIG: probe-off — restored $(basename "$CFG") from .rlt-orig backup" | tee -a "$LOGFILE"
        done
    fi
else
    echo "RL-CONFIG: profile dir missing ($RL_PROFILE_DIR), skipping probe" | tee -a "$LOGFILE"
fi

if [ -x /system/bin/log ]; then
    # Background tail: wait for client.log, then stream filtered lines to
    # logcat under tag RLClient. DebugLogServer subscribes to this tag.
    # tail -F handles file-not-yet-existing; grep --line-buffered keeps
    # latency low. /system/bin/log truncates at ~4KB which is fine for
    # SLF4J lines.
    (
        for _i in $(seq 1 120); do
            [ -f "$RL_CLIENT_LOG" ] && break
            sleep 1
        done
        tail -F -n 0 "$RL_CLIENT_LOG" 2>/dev/null | \
            grep -E --line-buffered "GpuPlugin|VAOList|rlawt|FpsPlugin|OpenGL|FrameBuffer|ShaderGen|RuneLite -|ERROR|WARN" | \
            while IFS= read -r _line; do
                /system/bin/log -p i -t RLClient "$_line" 2>/dev/null || true
            done
    ) &
    RL_LOG_TAIL_PID=$!
    echo "RL-LOG-TAIL: started PID=$RL_LOG_TAIL_PID -> logcat tag RLClient ($RL_CLIENT_LOG)" | tee -a "$LOGFILE"
else
    echo "RL-LOG-TAIL: /system/bin/log unavailable, skipping client.log forwarding" | tee -a "$LOGFILE"
fi

proot-distro login ubuntu --shared-tmp $GPU_BIND -- env \
    RLT_SESSION_DIR_IN_PROOT="/tmp/rlt-session/$SESSION_ID" \
    RLT_PROOT_ENV_FILE="$PROOT_ENV_FILE" \
    RLT_PROOT_GPU_ENV="$PROOT_GPU_ENV" \
    RLT_VIRGL_TUNE="${RLT_VIRGL_TUNE:-0}" \
    RLT_INNER_PERF_MONITOR_ENABLED="$PERF_MONITOR_ENABLED" \
    RLT_INNER_TARGET_FPS="${RLT_RUNELITE_TARGET_FPS:-120}" \
    RLT_INNER_UNLOCK_FPS="${RLT_RUNELITE_UNLOCK_FPS:-true}" \
    RLT_INNER_VSYNC_MODE="${RLT_RUNELITE_VSYNC_MODE:-OFF}" \
    RLT_INNER_SCALE="${RLT_RUNELITE_SCALE:-${RUNELITE_SCALE_OVERRIDE:-2}}" \
    RLT_INNER_JAVA2D_PROFILE="${RLT_JAVA2D_PROFILE:-${JAVA2D_PROFILE_OVERRIDE:-default}}" \
    RLT_INNER_MESA_PROFILE="${RLT_MESA_PROFILE:-${MESA_PROFILE_OVERRIDE:-default}}" \
    RLT_VIRGL_SERVER_PROFILE="${RLT_VIRGL_SERVER_PROFILE:-${VIRGL_SERVER_PROFILE_OVERRIDE:-default}}" \
    RLT_RUNELITE_LAUNCH_MODE="${RLT_RUNELITE_LAUNCH_MODE:-${RUNELITE_LAUNCH_MODE_OVERRIDE:-launcher}}" \
    RLT_DIRECT_CLASSPATH="$OUTER_DIRECT_CLASSPATH" \
    bash "$PROOT_LAUNCH_SCRIPT_IN_PROOT" 2>&1 | tee -a "$LOGFILE"
rm -f "$PROOT_LAUNCH_SCRIPT_TERMUX" 2>/dev/null || true

# PIPESTATUS[0] captures proot-distro exit code, not tee's exit code.
EXIT_CODE=${PIPESTATUS[0]}
if [ "${RLT_RUNELITE_LAUNCH_MODE:-${RUNELITE_LAUNCH_MODE_OVERRIDE:-launcher}}" = "launcher" ]; then
    LAST_DIRECT_CLASSPATH="$(extract_direct_classpath_from_log "$LOGFILE" || true)"
    if [ -n "$LAST_DIRECT_CLASSPATH" ]; then
        printf '%s\n' "$LAST_DIRECT_CLASSPATH" > "$TERMUX_DIRECT_CLASSPATH_FILE"
        echo "Saved direct RuneLite classpath to $TERMUX_DIRECT_CLASSPATH_FILE" | tee -a "$LOGFILE"
    fi
fi
echo "RuneLite exited with code: $EXIT_CODE" | tee -a "$LOGFILE"

if [ $EXIT_CODE -ne 0 ]; then
    echo "" | tee -a "$LOGFILE"
    echo "RuneLite failed. Log saved to: $LOGFILE" | tee -a "$LOGFILE"
    echo "Press Enter to exit..."
    [ -t 0 ] && read -r || sleep 5
fi

# EXIT trap handles all cleanup — no need for manual cleanup here
