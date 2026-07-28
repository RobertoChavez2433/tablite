#!/data/data/com.termux/files/usr/bin/bash
# perf-sampler.sh — Long-running GPU/CPU/context-switch telemetry daemon.
#
# Spawned by launch-runelite-native.sh when RLT_PERF_SAMPLE=1.
# Writes per-thread scheduler stats for the JVM (and optionally virgl) every
# INTERVAL_SEC seconds, plus GPU busy-counter samples if GPU_BUSY_PATH is
# readable. Self-terminates when either monitored PID disappears.
#
# Usage:
#   perf-sampler.sh JVM_PID [VIRGL_PID] [INTERVAL_SEC] [OUT_FILE] [GPU_BUSY_PATH]
#
# Output format mirrors jvm-wait-sampler.sh (Slice 5) so the same analysis
# tooling can compare native-path vs proot-path runs.

set -uo pipefail

# ===================================================================
# Arguments
# ===================================================================
JVM_PID="${1:-}"
VIRGL_PID="${2:-}"
INTERVAL_SEC="${3:-1}"
OUT_FILE="${4:-${HOME}/runelite-native-perf.log}"
GPU_BUSY_PATH="${5:-}"
CAPTURE_ID="${RLT_CAPTURE_ID:-unknown}"

if [ -z "$JVM_PID" ]; then
    echo "usage: perf-sampler.sh JVM_PID [VIRGL_PID] [INTERVAL_SEC] [OUT_FILE] [GPU_BUSY_PATH]" >&2
    exit 2
fi

# Validate INTERVAL_SEC — must be numeric; default to 1 if not.
case "$INTERVAL_SEC" in
    ''|*[!0-9.]*) INTERVAL_SEC=1 ;;
esac

# ===================================================================
# PID file — so the EXIT trap in the launcher can kill us by PID
# instead of using pkill -f which would self-kill the launcher's own
# bash process (the cmdline contains "perf-sampler" substring).
# ===================================================================
SAMPLER_PID_FILE="${HOME}/.rlt-perf-sampler.pid"
echo "$$" > "$SAMPLER_PID_FILE"

# ===================================================================
# XloriePerf / LorieNative capture — logcat side-car
# ===================================================================
# The XloriePerf line and LorieNative 5-sec counters land only in Android
# logcat. logcat's ring buffer rolls on high-volume devices (e.g. during long
# sessions), so we tee the relevant tags to a durable on-device file aligned
# with the perf TICK timeline. Termux UID IS allowed to read logcat; dumpsys
# is not (see scripts/capture-android-frame-stats.sh for the host-side
# companion that handles SurfaceFlinger + gfxinfo).
XLORIE_LOG="${OUT_FILE%.log}-xlorie.log"
: > "$XLORIE_LOG"
# Filter: gles-renderer (XloriePerf D) + LorieNative (counter I). *:S silences
# everything else. -b main for app logs; -v threadtime for consistent timestamps.
logcat -b main -v threadtime -s 'gles-renderer:D' 'LorieNative:I' >> "$XLORIE_LOG" 2>&1 &
XLORIE_LOG_PID=$!
printf '=== XLORIE LOG START pid=%s file=%s ===\n' "$XLORIE_LOG_PID" "$XLORIE_LOG" >> "$OUT_FILE"

# ===================================================================
# Signal handling
# ===================================================================
EXIT_REQUESTED=0
cleanup_xlorie_tail() {
    [ -n "${XLORIE_LOG_PID:-}" ] && kill "$XLORIE_LOG_PID" 2>/dev/null || true
}
trap 'EXIT_REQUESTED=1; cleanup_xlorie_tail' TERM INT

# ===================================================================
# Snapshot helper — emits one line per thread of $2
# $1 = label (START|END|TICK), $2 = PID
# For TICK, caller must also pass boottime_ns as $3.
# ===================================================================
emit_snapshot() {
    local label="$1"
    local pid="$2"
    local boottime_ns="${3:-}"
    [ -d "/proc/$pid" ] || return 0
    for taskdir in /proc/$pid/task/*; do
        [ -d "$taskdir" ] || continue
        local tid
        tid="${taskdir##*/}"
        local comm
        comm=$(cat "$taskdir/comm" 2>/dev/null || echo "?")
        local state
        state=$(grep '^State:' "$taskdir/status" 2>/dev/null | awk '{print $2}' || echo "?")
        local cpus
        cpus=$(grep '^Cpus_allowed_list' "$taskdir/status" 2>/dev/null | awk '{print $2}' || echo "?")
        local vol
        vol=$(grep '^voluntary_ctxt_switches' "$taskdir/status" 2>/dev/null | awk '{print $2}' || echo "0")
        local nonvol
        nonvol=$(grep '^nonvoluntary_ctxt_switches' "$taskdir/status" 2>/dev/null | awk '{print $2}' || echo "0")
        local sched
        sched=$(cat "$taskdir/schedstat" 2>/dev/null || echo "? ? ?")
        if [ "$label" = "TICK" ]; then
            printf 'TICK boottime_ns=%s pid=%s tid=%s comm=%s state=%s cpus=%s vol=%s nonvol=%s sched=%s\n' \
                "$boottime_ns" "$pid" "$tid" "$comm" "$state" "$cpus" "$vol" "$nonvol" "$sched" >> "$OUT_FILE"
        else
            printf '%s pid=%s tid=%s comm=%s state=%s cpus=%s vol=%s nonvol=%s sched=%s\n' \
                "$label" "$pid" "$tid" "$comm" "$state" "$cpus" "$vol" "$nonvol" "$sched" >> "$OUT_FILE"
        fi
    done
}

clock_boottime_ns() {
    awk '{printf "%.0f\n", $1 * 1000000000.0}' /proc/uptime
}

clock_realtime_ns() {
    date +%s%N
}

# ===================================================================
# Open output file + header
# ===================================================================
printf '=== PERF SAMPLER START pid=%s jvm=%s virgl=%s interval=%ss gpu_busy_path=%s ===\n' \
    "$$" "$JVM_PID" "$VIRGL_PID" "$INTERVAL_SEC" "$GPU_BUSY_PATH" >> "$OUT_FILE"
printf 'CLOCK_ANCHOR capture_id=%s realtime_ns=%s boottime_ns=%s source=/proc/uptime\n' \
    "$CAPTURE_ID" "$(clock_realtime_ns)" "$(clock_boottime_ns)" >> "$OUT_FILE"

# START snapshot — one snapshot per monitored PID
printf '=== START SNAPSHOT realtime_ns=%s boottime_ns=%s ===\n' \
    "$(clock_realtime_ns)" "$(clock_boottime_ns)" >> "$OUT_FILE"
emit_snapshot START "$JVM_PID"
if [ -n "$VIRGL_PID" ]; then
    emit_snapshot START "$VIRGL_PID"
fi

# ===================================================================
# Sample loop
# ===================================================================
while [ "$EXIT_REQUESTED" -eq 0 ]; do
    sleep "$INTERVAL_SEC"

    # Check JVM still alive
    if [ ! -d "/proc/$JVM_PID" ]; then
        printf '=== JVM dead (pid=%s), exiting sampler ===\n' "$JVM_PID" >> "$OUT_FILE"
        break
    fi

    # Check virgl still alive (only if we were given a PID)
    if [ -n "$VIRGL_PID" ] && [ ! -d "/proc/$VIRGL_PID" ]; then
        printf '=== VIRGL dead (pid=%s), exiting sampler ===\n' "$VIRGL_PID" >> "$OUT_FILE"
        break
    fi

    if [ "$EXIT_REQUESTED" -ne 0 ]; then
        break
    fi

    local_ts="$(clock_boottime_ns)"

    emit_snapshot TICK "$JVM_PID" "$local_ts"
    if [ -n "$VIRGL_PID" ]; then
        emit_snapshot TICK "$VIRGL_PID" "$local_ts"
    fi

    # GPU busy counter
    if [ -n "$GPU_BUSY_PATH" ] && [ -r "$GPU_BUSY_PATH" ]; then
        local_gpu_val=$(head -1 "$GPU_BUSY_PATH" 2>/dev/null || echo "?")
        printf 'GPU_BUSY boottime_ns=%s path=%s val=%s\n' "$local_ts" "$GPU_BUSY_PATH" "$local_gpu_val" >> "$OUT_FILE"
    fi
done

# ===================================================================
# END snapshot + cleanup
# ===================================================================
printf '=== END SNAPSHOT realtime_ns=%s boottime_ns=%s ===\n' \
    "$(clock_realtime_ns)" "$(clock_boottime_ns)" >> "$OUT_FILE"
emit_snapshot END "$JVM_PID"
if [ -n "$VIRGL_PID" ] && [ -d "/proc/$VIRGL_PID" ]; then
    emit_snapshot END "$VIRGL_PID"
fi

printf '=== PERF SAMPLER END realtime_ns=%s boottime_ns=%s ===\n' \
    "$(clock_realtime_ns)" "$(clock_boottime_ns)" >> "$OUT_FILE"
cleanup_xlorie_tail
rm -f "$SAMPLER_PID_FILE"
