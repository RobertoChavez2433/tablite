#!/data/data/com.termux/files/usr/bin/bash
# Server-side Zink evaluation — tests if Mali Vulkan supports Zink as VirGL backend
# Must run in Termux-native (not proot)
# Usage: test-server-zink.sh [benchmark_seconds]

DURATION=${1:-30}
LOG_DIR="$HOME"
ZINK_LOG="$LOG_DIR/zink-eval.log"

echo "ZINK_EVAL: start" | tee "$ZINK_LOG"

# Step 1: Check Vulkan feature availability
echo "ZINK_EVAL: checking vulkan features..." | tee -a "$ZINK_LOG"
VULKAN_INFO=$(vulkaninfo --summary 2>/dev/null)
if [ -z "$VULKAN_INFO" ]; then
    echo "ZINK_EVAL: error=vulkaninfo_not_found" | tee -a "$ZINK_LOG"
    echo "ZINK_EVAL: verdict=blocked reason=no_vulkaninfo" | tee -a "$ZINK_LOG"
    exit 1
fi

echo "$VULKAN_INFO" | tee -a "$ZINK_LOG"

# Check required features
FILL_MODE=$(vulkaninfo 2>/dev/null | grep -i fillModeNonSolid | head -1 | awk '{print $NF}')
CLIP_DIST=$(vulkaninfo 2>/dev/null | grep -i shaderClipDistance | head -1 | awk '{print $NF}')
LOGIC_OP=$(vulkaninfo 2>/dev/null | grep -i "logicOp " | head -1 | awk '{print $NF}')

echo "ZINK_EVAL: fillModeNonSolid=$FILL_MODE shaderClipDistance=$CLIP_DIST logicOp=$LOGIC_OP" | tee -a "$ZINK_LOG"

# Decision gate
VIABLE=true
for feature in "$FILL_MODE" "$CLIP_DIST" "$LOGIC_OP"; do
    case "$feature" in
        TRUE|true|VK_TRUE|1) ;;
        *) VIABLE=false ;;
    esac
done

if [ "$VIABLE" != "true" ]; then
    echo "ZINK_EVAL: verdict=blocked reason=missing_vulkan_features" | tee -a "$ZINK_LOG"
    exit 0
fi

echo "ZINK_EVAL: vulkan features OK, testing Zink backend..." | tee -a "$ZINK_LOG"

# Step 2: Kill existing VirGL server
pkill -f virgl_test_server 2>/dev/null || true
sleep 1

# Step 3: Start VirGL server with Zink backend
if ! command -v virgl_test_server_android >/dev/null 2>&1; then
    echo "ZINK_EVAL: error=virgl_server_not_found" | tee -a "$ZINK_LOG"
    exit 1
fi

export GALLIUM_DRIVER=zink
env -u LD_LIBRARY_PATH GALLIUM_DRIVER=zink virgl_test_server_android > "$LOG_DIR/virgl-zink.log" 2>&1 &
ZINK_PID=$!
echo "ZINK_EVAL: zink virgl server started PID=$ZINK_PID" | tee -a "$ZINK_LOG"

# Wait for socket
for _ in {1..10}; do
    [ -S "$PREFIX/tmp/.virgl_test" ] && break
    sleep 0.5
done

if [ ! -S "$PREFIX/tmp/.virgl_test" ]; then
    echo "ZINK_EVAL: error=socket_not_ready" | tee -a "$ZINK_LOG"
    kill $ZINK_PID 2>/dev/null
    echo "ZINK_EVAL: verdict=blocked reason=zink_server_failed" | tee -a "$ZINK_LOG"
    exit 1
fi

# Step 4: Run glxgears benchmark (inside proot, connecting to Zink-backed VirGL)
echo "ZINK_EVAL: running glxgears benchmark (${DURATION}s)..." | tee -a "$ZINK_LOG"
GEARS_OUTPUT=$(proot-distro login ubuntu --shared-tmp -- bash -c "DISPLAY=:0 GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=/tmp/.virgl_test timeout $DURATION glxgears 2>&1" || true)
echo "$GEARS_OUTPUT" | tee -a "$ZINK_LOG"

ZINK_FPS=$(echo "$GEARS_OUTPUT" | grep "frames in" | tail -3 | awk -F'=' '{sum+=$2; n++} END {if(n>0) printf "%.1f",sum/n; else print "0"}')
echo "ZINK_EVAL: zink_fps=$ZINK_FPS" | tee -a "$ZINK_LOG"

# Cleanup
kill $ZINK_PID 2>/dev/null || true

echo "ZINK_EVAL: verdict=viable zink_fps=$ZINK_FPS" | tee -a "$ZINK_LOG"
echo "ZINK_EVAL: done" | tee -a "$ZINK_LOG"
