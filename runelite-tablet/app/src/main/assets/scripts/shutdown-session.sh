#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -f "$TERMUX_PREFIX/etc/profile" ]; then
    # shellcheck disable=SC1091
    . "$TERMUX_PREFIX/etc/profile"
fi
export PREFIX="${PREFIX:-$TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"

SESSION_ROOT_DIR="$PREFIX/tmp/rlt-session"
CURRENT_SESSION_FILE="$SESSION_ROOT_DIR/current"

echo "=== Graceful shutdown $(date) ==="

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

stop_pid() {
    local label="$1"
    local pid="$2"
    if [ -z "$pid" ]; then
        return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    echo "Stopping $label (PID $pid)..."
    kill "$pid" 2>/dev/null || true
    for _ in {1..50}; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.1
    done
    kill -9 "$pid" 2>/dev/null || true
}

stop_pid_from_file() {
    local label="$1"
    local file_path="$2"
    local pid=""
    if [ -f "$file_path" ]; then
        pid="$(cat "$file_path" 2>/dev/null || true)"
    fi
    stop_pid "$label" "$pid"
}

stop_termux_x11() {
    local pid=""
    for pid in $(find_pids_by_cmdline 'com.termux.x11.Loader') \
               $(find_pids_by_cmdline 'termux-x11'); do
        kill "$pid" 2>/dev/null || true
    done
    am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true
    rm -f "$TMPDIR/.tX0-lock" 2>/dev/null || true
    rm -f "$TMPDIR/.X0-lock" 2>/dev/null || true
    rm -f "$PREFIX/tmp/.X11-unix/X0" 2>/dev/null || true
}

ACTIVE_SESSION_DIR=""
if [ -f "$CURRENT_SESSION_FILE" ]; then
    ACTIVE_SESSION_DIR="$(cat "$CURRENT_SESSION_FILE" 2>/dev/null || true)"
fi

if [ -n "$ACTIVE_SESSION_DIR" ]; then
    echo "Active session: $ACTIVE_SESSION_DIR"
fi

# 1. RuneLite (Java) — SIGTERM first, SIGKILL fallback
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    stop_pid_from_file "RuneLite" "$ACTIVE_SESSION_DIR/java.pid"
fi
if [ ! -f "$HOME/.rlt-session.pid" ]; then
    JAVA_PID="$(pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null | head -1)"
    stop_pid "RuneLite" "$JAVA_PID"
fi

# 2. Openbox
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    stop_pid_from_file "Openbox" "$ACTIVE_SESSION_DIR/openbox.pid"
fi
pkill -f 'openbox' 2>/dev/null || true

# 3. Proot sessions
pkill -f 'proot-distro' 2>/dev/null || true
pkill -f 'proot --' 2>/dev/null || true

# 4. PulseAudio
pulseaudio --kill 2>/dev/null || true

# 5.5. VirGL server
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    stop_pid_from_file "VirGL" "$ACTIVE_SESSION_DIR/virgl.pid"
fi
pkill -f 'virgl_test_server' 2>/dev/null || true
rm -f "$PREFIX/tmp/.virgl_test" 2>/dev/null || true

# 6. X11 server process (binary is 'com.termux.x11.Loader', not 'termux-x11')
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    stop_pid_from_file "Termux:X11 loader" "$ACTIVE_SESSION_DIR/x11.pid"
fi
stop_termux_x11

# 7. Launcher shell — kill by PID file when proot path tracked it, then
# fall back to pattern match so the native launcher (no PID file) is also
# reaped. Killing the launcher shell triggers its trap cleanup_on_exit which
# cascades to any children we missed by name above.
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    stop_pid_from_file "launcher shell" "$ACTIVE_SESSION_DIR/launcher.pid"
fi
pkill -f 'launch-runelite' 2>/dev/null || true

# 6. Termux:X11 Android app
am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

# Cleanup files
# NOTE: Do NOT delete $HOME/.rlt-launch-env.sh — a new launch may have deployed one.
rm -f "$HOME/.rlt-session.pid" 2>/dev/null || true
rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
rm -f "$PREFIX/tmp/.rlt-session-alive" 2>/dev/null || true
if [ -n "$ACTIVE_SESSION_DIR" ]; then
    rm -rf "$ACTIVE_SESSION_DIR" 2>/dev/null || true
fi
rm -f "$CURRENT_SESSION_FILE" 2>/dev/null || true

echo "SHUTDOWN_COMPLETE"
