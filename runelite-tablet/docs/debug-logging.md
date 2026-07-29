# Debug Logging System

## Quick Start

1. Build and deploy a DEBUG build to the device
2. Forward the debug port: `adb forward tcp:8099 tcp:8099`
3. Use `scripts/agent-diagnostics.ps1 state` for canonical agent access.
4. The legacy HTML viewer remains available at `http://localhost:8099/`.

The server binds device loopback only. The host client reads the current
ephemeral credential from app-private storage through `adb run-as`; it never
prints the credential or writes it to evidence.

## Architecture

The logging system has three destinations, all active in DEBUG builds:

- **Logcat** — standard Android log output
- **File** — rotated log files in app's cache directory (`rlt-session-*.log`, `rlt-perf-*.log`)
- **Canonical `/v1` API** — authenticated retained logs, state, health, and
  capabilities on port 8099
- **WebSocket** — legacy live structured JSON stream on port 8099

All sinks receive the same sanitized events. The retained cursor API is the
evidence source; `/ws` remains a compatibility live view and does not provide
replay.

## Canonical agent API

Use the observation-only host wrapper:

```powershell
scripts/agent-diagnostics.ps1 capabilities
scripts/agent-diagnostics.ps1 health
scripts/agent-diagnostics.ps1 state
scripts/agent-diagnostics.ps1 logs -After 0 -Limit 100
scripts/agent-diagnostics.ps1 watch-state -MaxEvents 2
scripts/agent-diagnostics.ps1 capture
```

The authenticated routes are:

| Route | Purpose |
|---|---|
| `GET /v1/capabilities` | Protocol, routes, event catalog, and limits |
| `GET /v1/health` | Server, retention, loss, and subscriber health |
| `GET /v1/logs?after=N&limit=N` | Bounded retained cursor page |
| `GET /v1/state` | Production-derived Tablite state snapshot |
| `GET /v1/state/events` | SSE state changes, reset events, and heartbeats |

Evidence capture fails if retained logs report a cursor gap or dropped event.
Raw per-frame telemetry does not traverse this JSON API; only bounded summaries
and loss counters do.

## Native and Termux ingress

Port 8100 is the sanctioned cross-UID adapter for bounded native summaries. It
binds device loopback, normalizes the known rlawt prefix, and accepts only the
declared `RLT_FRAME_RATE_V1`, `RLT_VIRGL_WORKER_V1`,
`RLT_MESA_VTEST_V1`, `RLT_VIRGL_HOST_V1`, `RLT_VTEST_TRANSPORT_V1`,
`RLT_FRAME_STREAM_V1`, `RLT_AHB_STREAM_V1`, `RLT_AHB_GPU_V1`,
`RLT_DIRECT_TRANSFER_V1`, and
`RLT_TELEMETRY_COST_V1` record families. Accepted
records immediately pass through
`AppLog`, redaction, the canonical event catalog, local files, Logcat, and the
retained `/v1/logs` cursor store.

The native launcher creates a private, run-scoped 64-byte
`rlt-frame-telemetry-v1.shm` page whenever telemetry is not `OFF`. rlawt writes
only the active producer sequence and monotonic start time around
`glXSwapBuffers`; Mesa reads that identity and emits one bounded stage summary
per 30 completed frames. The six stages are command submit, transfer request,
completion wait, CPU copy, display, and fence wait.

When VirGL runs under the Termux UID, the same page joins those windows to
server-side submit receive/decode, fence creation, transfer-get execution,
busy-wait poll/retirement, response transport, and queue depth. App-owned
VirGL cannot map that Termux-owned page. In BURST/DEEP mode Mesa therefore
sends one private 24-byte vtest frame marker immediately before the full-frame
transfer. The marker contains only the producer sequence and monotonic begin
time, deactivates after the completion wait, and is absent from SUMMARY. The
host emits one bounded `RLT_VIRGL_HOST_V1` record per 30 completed producer
frames on either route.

Detailed BURST/DEEP VirGL records also time:

- transfer preparation, framebuffer setup, `glReadPixels`, framebuffer
  cleanup, and postprocessing;
- submit draw, transfer, object, state, query/sync, and other command
  categories.

The parent `transfer_exec` contains its five transfer-detail stages, and
`submit_decode` contains its six command-category stages. Reports must not add
parents to children. On the current SM-X920 evidence, submit transfer is the
same dominant full-frame readback already represented by `transfer_exec`;
the category split is retained as a guardrail, not as a reason to add more
hot-path telemetry.

`telemetry-cost-summary.json` is telemetry-on-telemetry. It attributes calls,
sampled and expanded wall/thread-CPU time, bytes, queue high-water marks,
drops, incomplete frames, and unattributed events across Android, rlawt,
Mesa/vtest, VirGL, the native bridge, and export. Per-stage wall time can
overlap and is never a critical-path sum. True drops are separate from
incomplete or unattributed records. The independent SurfaceFlinger
FrameTimeline comparison remains the authority for total observer effect.
The vtest bridge additionally reports marker calls, bytes, wall time, and
thread-CPU time; VirGL reports `marker_receive` separately from renderer work.

The current policy is to keep these timers and counters as a validity
guardrail while optimizing the renderer. Extend telemetry further only when a
direct VirGL/AHardwareBuffer ownership or fence boundary cannot otherwise be
measured.

The app-owned VirGL path makes that direct boundary observable without
per-frame logging. `RLT_AHB_STREAM_V1` emits the first five frames and then
one cumulative record every 60 frames. It separately times:

- free-pool acquisition/release wait;
- `AHardwareBuffer_lock`;
- the resolved-frame row copy;
- `AHardwareBuffer_unlock`;
- native-fence wait; and
- the small `FRAME_READY` send.

When the display target can remain on the GPU, `RLT_AHB_GPU_V1` uses the same
bounded cadence and run/frame identity. It separately times free-pool
acquisition, one-time EGLImage import amortization, framebuffer blit,
correctness synchronization (`glFinish` until the ABI carries a native fence
fd), and the ready-message send. `RLT_DIRECT_TRANSFER_V1
outcome=gpu_readback_bypassed` proves that the full-frame `glReadPixels`,
temporary host allocation, CPU swizzle/copy, and duplicate guest payload were
skipped. An import or GL failure reports `RLT_AHB_GPU_V1 outcome=fallback` and
retains the existing CPU path.

The record also carries run ID, producer sequence, dimensions, bytes, frame
count, and failures. Production records include a pool-scoped frame count,
elapsed time, and sustained rate derived from the timestamps already taken at
successful `FRAME_READY` sends. That window resets when the pool reconnects or
resizes, so startup dimensions and disconnected intervals do not dilute the
steady rate. `acquire_wait` is presentation/release pacing and must not be
reported as buffer-lock cost. The packaged VirGL subprocess keeps a private
raw log and mirrors only bounded `RLT_*` records through `AppLog.nativeIngress`,
so these summaries reach the canonical retained cursor without a cross-UID
bridge.

For a gated synthetic capacity run, analyze the raw app-VirGL log with:

```powershell
python tools/ahb_stream_summary.py `
    --logs <app-virgl-capture.log> --run-id <capture-id> `
    --output <ahb-stream-summary.json> `
    --report <ahb-stream-summary.md>
```

The synthetic startup probe is disabled by default and enabled only by the
explicit activity probe extras. It validates transport capacity, not game
rendering. The analyzer prefers the production pool rate when present. If that
rate is below target while active handoff work still fits inside one 120 Hz
interval, it directs optimization upstream rather than recommending a small
AHB copy change. Production acceptance still requires a logged-in game-loop
capture with exact run identity and zero loss.

For a BURST/DEEP production run, certify the producer/Mesa/VirGL/AHB join
across one bounded sequence range with:

```powershell
python tools/frame_stream_summary.py `
    --transport ahb `
    --logs <runelite-native.log> <app-virgl-capture.log> `
    --run-id <capture-id> --minimum-seq 300 --maximum-seq 2160 `
    --output <ahb-pipeline-summary.json>
```

The AHB gate requires matching Mesa/VirGL windows, at least 99.9% attribution,
zero incomplete frames, zero AHB failures, and zero producer-to-AHB sequence
gaps.

The experimental completed-frame bridge is also self-observing. VirGL copies
the latest resolved display target into a two-slot queue and a dedicated
sender thread performs the loopback transfer. `RLT_FRAME_STREAM_V1` reports
producer sequence, queue-copy time, enqueue-to-send delay, transfer-to-send
delay, socket-send time, frames queued/sent/superseded, queue high-water mark,
bytes, and failures. The Android consumer separately reports payload read,
payload layout, payload receive syscall count, ingress-to-present latency,
sequence gaps, and invalid frames. Socket send time is intentionally off the
vtest command thread; queue-copy time remains on the producer boundary and
must be counted as transport cost.

When the frame bridge is active, the launcher may explicitly set
`RLT_FRAME_STREAM_BYPASS_X11_DISPLAY=1`. Mesa then skips only the redundant
X11 display-target handoff after the completed image has been transferred to
VirGL. Each `RLT_MESA_VTEST_V1` window declares
`display_policy=bypassed_frame_stream`; ordinary launches force the policy
back to `x11`.

For the matched direct-AHB route, the same bypass gate also selects the
private `VCMD_RLT_TRANSFER_GET_DIRECT` command. VirGL resolves the complete
display target and first attempts a direct GPU copy into the AHB pool. On
success it does not read pixels to CPU memory, send the duplicate pixel
payload back to Mesa, or populate the hidden X11 display target.
`RLT_MESA_VTEST_V1` declares
`transfer_policy=host_present_no_guest_payload`.
The GPU frame-stream route also declares
`precopy_fence_policy=delegated_to_transfer_completion`: Mesa does not perform
the drisw pre-copy fence round trip because the ordered host transfer performs
the completion wait before publishing the AHardwareBuffer. Ordinary X11
presentation retains `precopy_fence_policy=explicit_guest_wait`.
`RLT_DIRECT_TRANSFER_V1` reports per-frame and cumulative bytes suppressed.
Ordinary X11/TCP paths retain the standard vtest response as the fallback.

`RLT_VTEST_FENCE_V1` observes renderer-lifecycle and implicit-fence stalls
that occur before the first frame marker. `renderer_init /
stale_state_reset` means a destroyed sequential client left unmatched fence
counters; the new renderer resets those counters before accepting work.
`busy_wait / still_waiting` is emitted after one second and then every five
seconds with submitted/completed IDs, queue depth, elapsed wait, and timed
poll count. `busy_wait / recovered` proves that periodic renderer polling
recovered a lost notification. These records contain no pixels or account
data and use the canonical native-ingress allowlist.

Generate a deduplicated, bounded join/loss/performance summary from the three
raw capture logs with:

```powershell
python tools/frame_stream_summary.py `
    --logs <runelite-native.log> <virgl-server.log> <logcat-prestop.log> `
    --run-id <capture-id> --minimum-seq 300 --maximum-seq 2160 `
    --output <frame-stream-summary.json>
```

The summary derives copy and send means from cumulative counters, not the
sparse detailed records, and fails closed when a required producer, Mesa,
VirGL, sender, or app record family is missing.

This ingress is not a general logging socket. Raw per-frame records, arbitrary
text, credentials, client logs, input, and gameplay content are rejected.
New native seams must extend the allowlist, canonical routing, catalog, tests,
and `TBL017` lint contract together.

The observation-only VirGL worker capture uses this path:

```powershell
scripts/capture-virgl-summary.ps1 -Serial <serial> `
    -DurationSeconds 8 -IntervalMilliseconds 100
```

The producer-attributed Mesa boundary capture uses the same endpoint:

```powershell
scripts/capture-mesa-vtest.ps1 -Serial <serial> -DurationSeconds 12
```

Require exact host/Mesa window joins after installing the host package:

```powershell
scripts/capture-mesa-vtest.ps1 -Serial <serial> `
    -DurationSeconds 12 -RequireVirglHost
```

The evidence bundle contains canonical agent evidence, producer-window joins,
reports, manifest, and hashes. It does not use ptrace, screenshots, gameplay
input, packets, or raw client logs.

## WebSocket API

Connect to `ws://device-ip:8099/ws` (or `ws://localhost:8099/ws` after adb forward).

Each message is a JSON object:

```json
{
  "ts": 1712000000000,
  "elapsed": 5234,
  "level": "D",
  "tag": "FRAME",
  "msg": "fps=118.2 jank=1 p99=9.1ms heap=45MB",
  "thread": "main",
  "correlationId": "launch-a3f7",
  "source": "kotlin",
  "file": "HybridX11HostActivity.kt",
  "line": 97
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| ts | long | Unix timestamp (ms) |
| elapsed | long | Milliseconds since server start |
| level | string | D, I, W, or E |
| tag | string | Log tag (FRAME, JANK, AUTH, CMD, etc.) |
| msg | string | Log message |
| thread | string | Thread name |
| correlationId | string? | Operation trace ID (e.g., `setup-a3f7`) |
| source | string | `kotlin`, `native`, or `shell` |
| file | string | Source file name |
| line | int | Source line number |

### Sources

- `kotlin` — Kotlin/JVM code via `Logger` interface
- `native` — C code via `__android_log_print()` -> logcat bridge -> WebSocket
- `shell` — Termux command stdout/stderr via `TermuxCommandRunner`

## HTML Viewer

The viewer at `http://localhost:8099/` provides:

- **Filters**: text, level, source, tag, correlationId
- **Color coding**: errors (red), warnings (yellow), jank (red bold), handoffs (teal)
- **Timeline**: elapsed milliseconds for cross-boundary correlation
- **Pause/Resume**: buffer events while paused, flush on resume

## Correlation IDs

Every top-level operation generates a correlation ID (format: `{action}-{4hex}`):

- `setup-a3f7` — setup orchestration chain
- `auth-b2c1` — authentication flow
- `launch-d4e5` — launch sequence
- `frames-f6g7` — rendering session

Nested operations use `/` separator: `launch-d4e5/auth-refresh-h8i9`

Filter by correlationId in the viewer to trace a complete operation across Kotlin -> JNI -> native -> shell.

## Perf Logs

Frame-rate data writes to a separate `rlt-perf-*.log` file to avoid polluting general logs. Tags routed to perf: FRAME, JANK, SURFACE, GL, BUFFER.

## File Descriptor Tracking

In DEBUG builds, `FdTracker` monitors every fd open/close/transfer/detach. On `onPause()`, it dumps any fds that haven't been closed — potential leak suspects.

## Programmatic Access

Agent tooling should use the canonical `/v1` routes. The WebSocket is retained
for the existing interactive viewer only.
