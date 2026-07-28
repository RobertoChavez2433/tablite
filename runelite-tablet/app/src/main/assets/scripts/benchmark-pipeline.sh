#!/data/data/com.termux/files/usr/bin/bash
# Pipeline benchmark — runs glxgears through proot+VirGL to measure ceiling FPS
# Usage: benchmark-pipeline.sh [duration_seconds]
# Output: structured BENCHMARK: lines to stdout

DURATION=${1:-30}
LOG_DIR="/tmp"
GLXGEARS_LOG="$LOG_DIR/glxgears.log"
GLXINFO_LOG="$LOG_DIR/glxinfo.log"

echo "BENCHMARK: start duration=${DURATION}s"

# Capture GL info
DISPLAY=:0 glxinfo 2>/dev/null > "$GLXINFO_LOG" 2>&1
GL_RENDERER=$(grep "OpenGL renderer" "$GLXINFO_LOG" 2>/dev/null | head -1 | sed 's/.*: //')
GL_VERSION=$(grep "OpenGL version" "$GLXINFO_LOG" 2>/dev/null | head -1 | sed 's/.*: //')
echo "BENCHMARK: gl_renderer=$GL_RENDERER"
echo "BENCHMARK: gl_version=$GL_VERSION"

# Run glxgears
if ! command -v glxgears >/dev/null 2>&1; then
    echo "BENCHMARK: error=glxgears_not_found"
    exit 1
fi

DISPLAY=:0 timeout "$DURATION" glxgears 2>&1 | tee "$GLXGEARS_LOG"

# Parse FPS from last 5 measurements
FPS_LINES=$(grep "frames in" "$GLXGEARS_LOG" 2>/dev/null | tail -5)
if [ -z "$FPS_LINES" ]; then
    echo "BENCHMARK: error=no_fps_output"
    exit 1
fi

# Calculate average FPS
AVG_FPS=$(echo "$FPS_LINES" | awk -F'=' '{sum += $2; n++} END {if (n>0) printf "%.1f", sum/n; else print "0"}')
echo "BENCHMARK: glxgears_render_rate_hz=$AVG_FPS samples=$(echo "$FPS_LINES" | wc -l)"
echo "BENCHMARK: done"
