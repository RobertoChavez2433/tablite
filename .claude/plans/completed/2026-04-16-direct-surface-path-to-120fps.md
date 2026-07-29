# Direct-Surface Path to 120 FPS — Multi-Slice Plan

**Date**: 2026-04-16
**Status**: Approved — session 72 starts at Slice 1
**Parent Plan**: `.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md`
**Approach**: Resume the paused `spike/direct-android-surface` work. Start by resolving the unknown hot-path function inside `libXlorie.so`; use that evidence to decide whether to (a) optimize the existing X11 compositor path or (b) move presentation onto an app-owned Android Surface. Six slices, each individually testable and device-verified.

---

## Overview / Goal

Bring in-game RuneLite FPS on Samsung Tab S10 Ultra from **~10 FPS ground truth** (RuneLite `FpsPlugin` overlay, session 71) to **120 FPS sustained at native resolution** (2960×1848), without modifying RuneLite or stock Jagex client behavior.

**Non-goals**:
- Modifying RuneLite internals, GPU plugin code, or runtime settings as a workaround. (Per `feedback_scope_our_pipeline_not_runelite.md`.)
- Rebuilding Mesa or virglrenderer with Venus. (Deferred — treat as backpocket if slices 1-5 cannot reach target.)
- Replacing VirGL with ANGLE. (Already rejected in session 71 — H6 showed GL_INVALID_OPERATION on texImage2D and 10 FPS in-game regression.)
- Rendering RuneLite's scene on the Android side. (Would require reimplementing GPU plugin — breaks "real RuneLite" goal.)

---

## Exit Criteria (device-verified on R52X90378YB)

**S-FINAL**: RuneLite `FpsPlugin` overlay reports ≥ 100 FPS in-game steady state at a standard location (e.g., Lumbridge Castle exterior, not Grand Exchange), while a 10-minute session shows no visible frame drops, no rendering artifacts (black lines, missing textures), and no session-health alarms. Compositor `damage-triggered redraws` correlates within ±10% of overlay FPS after the instrumentation added in Slice 2.

---

## Evidence Foundation (already captured)

Do not re-derive these; cite them.

- **Direct SurfaceView probe sustains 120 FPS** on this tablet (`docs/hybrid-x11-iteration-log.md` line ~120). Hardware is not the limit.
- **`present after-flips = 0 FPS`** across all captured runs (iteration log line 3515). Present extension is not on the fast path.
- **Hot PutImage path is neither `fbPutImage` nor `miPutImage`** (iteration log line 3689) — it's a GC-installed op inside extracted `libXlorie.so`, `dladdr` reports `sym=unknown`.
- **Session 70 "44-51 FPS ceiling" was login-screen damage events** (session 71 finding), not in-game scene FPS. In-game with GPU plugin: ~10 FPS.
- **Content production gap** — compositor produces ~40 damage events/sec while RuneLite renders ~10 scene frames/sec. Source of the 30-event delta is unknown (overlays, cursor, per-frame multi-damage?).
- **XloriePerf metrics** are stable: `content_util=100%`, `avg_fence_ms=2.7`, `avg_swap_ms=0.6`. Renderer side is not the bottleneck (`fps-ceiling-research-session70.md`).

---

## Architecture / Design Decisions

**AD1 — Resume at symbol resolution, not architectural redesign.**
The iteration log's last concrete action is dladdr offset logging. Before proposing new architecture, finish the diagnostic that was in flight. Slice 1 = that diagnostic.

**AD2 — Retain the Linux RuneLite path.**
RuneLite's JVM + rlawt + Mesa path stays inside proot. We change the presentation layer, not the rendering-producer layer.

**AD3 — Prefer the smallest bypass that works.**
Order of escalation: (a) optimize the hot PutImage impl in place, (b) enable DRI3 Present for zero-copy flips, (c) move presentation to app-owned Android Surface. Don't jump to (c) until (a) and (b) are proven insufficient.

**AD4 — Keep the stock path runnable as a fallback.**
Every slice must leave `HybridX11HostActivity` + stock `CmdEntryPoint` path bootable. No irreversible changes.

**AD5 — Every slice is device-verified.**
Exit criteria are measured on R52X90378YB with the `scripts/hybrid-x11-runelite-evidence.ps1` harness, never assumed from code inspection alone.

**AD6 — Observability stays on.**
The session-71 `RL-LOG-TAIL` + `DebugLogServer` source=runelite pipeline must continue to work at every checkpoint. Regressions here are treated as blockers.

---

## Implementation Order

---

### SLICE 1 — dladdr symbol resolution for the hot PutImage/Composite paths

**Why this first**: The iteration log's committed-but-undeployed next step (line 3731-3733). The 120-FPS direction depends on knowing *which* function is absorbing the frame time; without that, every later slice is guessing.

**Estimated size**: 1-2 sessions, ~50 LoC in `damage.c`, one build, one evidence-harness run, one summary.

**Tasks**:
1. **Extend `damageTraceOpWithFunction()` in `third_party/termux-x11-upstream/app/src/main/cpp/xserver/miext/damage/damage.c`** (around line 183). Add `dladdr(fn, &info)`, compute `(uintptr_t)fn - (uintptr_t)info.dli_fbase`, log as `DamageTraceV2: op=<name> fn_offset=0x%lx dli_fname=<so> dli_sname=<sym>`. Handle the `sym=unknown` case by emitting the offset anyway (we can map it post-hoc).
2. **Gate under `RLT_DAMAGE_TRACE_V2=1`** env var, propagated through `launch-runelite.sh → proot env → PROOT_ENV_FILE`. Default off to avoid log flood in normal runs.
3. **Confirm both call sites hit**: `damagePutImage()` (damage.c:874, calls tracer at ~892) and `damageComposite()` (damage.c:618, tracer at ~642). Both are called from the GC-installed hot path — each frame hits one or both.
4. **Locate the unstripped `libXlorie.so`**. It lives at `runelite-tablet/app/.cxx/cxx/Debug/<hash>/arm64-v8a/libXlorie.so` after a debug build. Verify it has symbols: `aarch64-linux-android-nm --defined-only <path> | grep -i putimage`.
5. **Run evidence harness**: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`, then `powershell scripts/hybrid-x11-runelite-evidence.ps1 -Variant internal-hybrid` with `-LaunchEnv RLT_DAMAGE_TRACE_V2=1`. Capture 60 seconds at the same location used for session 71 baseline.
6. **Resolve offsets to symbols**. For each unique `fn_offset` in the capture, run `aarch64-linux-android-addr2line -f -C -e <unstripped.so> 0x<offset>` and record in a mapping file `runelite-tablet/docs/logs/slice1-putimage-symbols.txt`.
7. **Append findings to `docs/hybrid-x11-iteration-log.md`** as a new "Checkpoint N+1" section. Name the hot PutImage implementation.

**Exit Criteria (device-verified)**:
- DebugLogServer (port 8099) shows `DamageTraceV2: ... fn_offset=0x... dli_sname=<resolved>` entries at ≥ 10 Hz when GPU plugin is active.
- The hot PutImage implementation is named in the iteration log (e.g., "exaCopyNtoN", "glamor_poly_fill_rect_gl", or a specific `lorie*` function).
- The hot Composite implementation is similarly named.
- FPS probe remains at baseline (~10 FPS) — this slice is observability-only, no perf regression expected.

**Shell scripts / manifest changes**:
- `launch-runelite.sh`: propagate `RLT_DAMAGE_TRACE_V2` through to proot via the env file, matching existing pattern used for `RLT_VIRGL_TUNE`.

**Error handling**:
- `dladdr()` can return 0 on stripped pages; log `dli_fname=NULL dli_fbase=NULL` and continue. Don't crash.
- Skip if `fn == NULL` (shouldn't happen, defensive).

**Deferred**:
- Instrumenting every PutImage variant. Stay focused on the hot path revealed by counters.

**Research refs**:
- `docs/hybrid-x11-iteration-log.md` section "Current conclusion" (end of log)
- `third_party/termux-x11-upstream/app/src/main/cpp/xserver/miext/damage/damage.c` lines 71-75 (counters), 78-87 (backend-name helper), 183-195 (tracer)
- Android NDK `dladdr(3)`: https://developer.android.com/ndk/reference/group/dl

---

### SLICE 2 — Attribute damage events to source (scene vs overlay vs cursor)

**Why this second**: Session 71 revealed a mystery — 40 damage events/sec vs 10 user-visible FPS. Before we optimize, we need to know whether the 30 extra events are a pipeline problem to fix OR normal multi-source redraw behavior to ignore. Slice 1's data feeds directly in.

**Estimated size**: 1 session, ~30 LoC in `damage.c` + renderer, one capture.

**Tasks**:
1. **In `damagePutImage()`, classify the region geometry.** Add log line capturing `(x, y, w, h)`, `depth`, and the parent drawable type (WINDOW / PIXMAP). Small-area updates (<100px²) are almost certainly overlays/cursor; full-window updates are scene frames.
2. **In renderer.c `rendererRedrawLocked()` (line 738-809)**, when a full-window damage event fires, emit `XloriePerf: scene_redraw ts=<ns>`; when sub-region damage fires, emit `XloriePerf: partial_redraw region=(x,y,w,h)`.
3. **Cross-correlate with RuneLite swap events**. The `RL-LOG-TAIL` from session 71 already forwards lines matching `rlawt|OpenGL` — add a one-line `RuneLite GL swap` log hook if there's a natural emission point in rlawt (likely not — rlawt is upstream). If there isn't, use GL plugin's `Scene upload time` + next frame gap as a proxy.
4. **Produce a "damage attribution" report** in `runelite-tablet/docs/logs/slice2-damage-attribution.md`. For a 60-second capture at the same test location, report: scene_redraws/sec, partial_redraws/sec, avg region size of partials, top-3 most-common partial regions (likely FPS overlay + minimap + chat box).

**Exit Criteria (device-verified)**:
- Scene-redraw rate quantified (expected: ~10/s matching FpsPlugin). If it's higher, session 70's interpretation was wrong and we have additional investigation.
- The 30 extra damage events attributed to specific overlays (e.g., 10 from FpsPlugin, 5 from minimap, 2 from chat, 13 unknown).
- A clear verdict: **damage-FPS ≠ scene FPS, gap is benign** (expected outcome) OR **damage-FPS > scene-FPS because of redundant scene redraws in the pipeline** (actionable bug).

**Shell scripts / manifest changes**: none.

**Error handling**: logs only; no behavior change. `damageExtents()` can fail on NULL region; guard it.

**Deferred**:
- Fixing any redundant scene redraws discovered — that becomes a Slice 3 task if it exists.

**Research refs**:
- Slice 1 output (hot path symbol names)
- `third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c:738-809` (renderer main loop)
- Session 71 finding: 10 FPS overlay vs 40 FPS damage

---

### SLICE 3 — First optimization pass on the resolved hot path

**Why this third**: Now we know exactly which function is eating the frame time (S1) and whether the 30-event gap is real or benign (S2). S3 applies the first surgical fix. This is the first slice where FPS should move.

**Estimated size**: 2-3 sessions depending on which class of fix applies. ~100-300 LoC modified in Xlorie/xserver native code.

**Tasks** (pick the subset matching S1 findings — one of A / B / C / D):

**A. If hot path is `exa*` (EXA acceleration framework)**:
1. Check whether `exa_render.c` / `exa_accel.c` is hitting the software fallback. EXA has a migration mechanism that punts to software when pixmap isn't in GPU memory.
2. Audit the migration-policy function and verify our pixmap allocations are GPU-backed. The iteration log patches at commit `b50947a` touched `exa_accel.c` and `exa_render.c` — review those and see if they need completion.
3. Force-GPU all WINDOW-backed pixmaps at allocation time.

**B. If hot path is `glamor*` (GLSL-backed acceleration)**:
1. Verify glamor is actually active (check `glamor_init` success at X server startup).
2. Audit `glamor_image.c` (modified in `b50947a`) — the spike may have half-landed optimizations.
3. Benchmark glamor vs EXA for the specific op — sometimes EXA wins for full-window PutImage because glamor pays shader compile + FBO bind cost per op.

**C. If hot path is a `lorie*` or `fb*` internal (software fallback)**:
1. This confirms every frame copies pixels CPU→GPU. Audit `fbimage.c` (modified in `b50947a`).
2. Implement AHARDWAREBUFFER-backed fast path: instead of `memcpy` + GL `glTexSubImage2D`, map AHARDWAREBUFFER with `AHardwareBuffer_lock()`, write directly to it, unlock, and let the existing renderer pick it up on next frame.
3. This is the "zero-copy PutImage" optimization and is the single biggest win per the iteration log's hypotheses.

**D. If hot path is `miPutImage` (generic fallback)**:
1. This is the absolute worst case — nothing is accelerated. Verify `b50947a` didn't break the acceleration registration chain.
2. Reinstate GPU pixmap backing (same as A/B above).

**General acceptance for all variants**:
- In-game FPS moves from ~10 to ≥ 25 (2.5× improvement is the minimum that justifies the change).
- No scene corruption, no flicker, no crash on scene transitions.
- `XloriePerf` still shows `content_util=100%` (means renderer hasn't starved) and `avg_fence_ms` stays under 5.

**Exit Criteria (device-verified)**:
- FpsPlugin overlay ≥ 25 FPS in-game, same test location as session 71.
- All S1 + S2 observability still works.
- Stock `HybridX11HostActivity` bootable (AD4).

**Shell scripts / manifest changes**: none.

**Error handling**:
- If the optimization causes any scene corruption, revert behind a feature flag `RLT_XLORIE_FAST_PUTIMAGE=1` default off, investigate, re-enable.

**Deferred**:
- Further squeezing. This slice targets 2.5× improvement, not the full 10×.

**Research refs**:
- `b50947a` diff (22 files, +1773 / -50 LoC) — that commit has partial work across EXA, glamor, DRI3, damage, fb, render. Read it end-to-end before implementing.
- xserver EXA documentation: https://www.x.org/wiki/XorgEVoC/EXA (outdated but explains migration policy)
- AHardwareBuffer NDK: https://developer.android.com/ndk/reference/group/a-hardware-buffer

---

### SLICE 4 — Enable DRI3 Present extension for zero-copy flip

**Why this fourth**: `present after-flips = 0 FPS` means the X server's Present extension never accepts a flip even though our code paths exist. Session 70 tested `TERMUX_X11_FORCE_FLIP=1` and it had no effect because VirGL doesn't use DRI3/Present at all. If we make DRI3 actually route through our code, we get true front-buffer flipping and the per-frame compositor cost goes to zero.

**Estimated size**: 2 sessions, native DRI3 code surgery.

**Tasks**:
1. **Instrument `dri3_request.c` and `dri3_screen.c`** (both touched by `b50947a`) to log every DRI3 request type received. Confirm whether RuneLite's Linux-side stack issues PresentPixmap or PresentNotifyMSC at all.
2. **If DRI3 is never requested** (likely): figure out why. Mesa with `GALLIUM_DRIVER=virpipe` may route through DRI2 or PutImage instead. Check `xf86/dri2/dri2.c` (also modified in `b50947a`). Decision point: enable DRI3 on VirGL?
3. **If DRI3 IS requested but `loriePresentFlip` rejects**: the rejection gate is in `InitOutput.c:874-913`. The `TERMUX_X11_FORCE_FLIP=1` check is there. Audit why even FORCE_FLIP doesn't accept. Likely cause: fd from VirGL isn't dma-buf compatible, so AHARDWAREBUFFER import fails.
4. **If the blocker is fd-compat**: implement a memcpy-once-into-AHB bridge. The VirGL fd becomes a guest-side pixmap; we copy once per PresentPixmap call into a proper AHB; subsequent flips are zero-copy. Single memcpy per flip is far cheaper than per-pixel PutImage.

**Exit Criteria (device-verified)**:
- `LorieNative: present after-flips in 5.0 seconds = <non-zero> FPS` in steady-state logs.
- In-game FpsPlugin ≥ 45 FPS (Slice 3 improved to 25; Slice 4 another 1.8× via flip zero-copy).
- No visible tearing, no stuck frames.

**Shell scripts / manifest changes**:
- `launch-runelite.sh` currently exports `TERMUX_X11_FORCE_FLIP=1` unconditionally (session 70 harmless addition). Make it conditional on whether AHB bridge is in place.

**Error handling**:
- If PresentPixmap fails mid-session, fall back to PutImage path (already exists via damage-copy path). Must be automatic, not require restart.

**Deferred**:
- Full dma-buf interop with VirGL (big undertaking, requires Mesa changes).

**Research refs**:
- `b50947a` commit — partial DRI3 work: `dri3_request.c` (+98 LoC), `dri3_screen.c` (+24 LoC)
- `InitOutput.c:840-913` (loriePresentFlip)
- DRI3 protocol: https://gitlab.freedesktop.org/xorg/proto/xorgproto/-/blob/master/dri3proto.txt

---

### SLICE 5 — App-owned direct Android Surface for presentation

**Why this fifth**: If S3 + S4 land us in 45-60 FPS range but can't close the gap to 120, the remaining cost is Xlorie's own composite-to-screen step (`lorieRedraw` → AHB texture upload → `eglSwapBuffers`). The direct-surface probe already proved an app-owned SurfaceView sustains 120 FPS. This slice moves RuneLite's output directly onto that proven surface, eliminating our in-app GL compositor for the main content.

**Estimated size**: 3-5 sessions. Biggest slice.

**Tasks**:
1. **Re-enable the `internal-hybrid` variant** (`HybridX11TestReceiver.kt:684` constant, partial impl). Goal: X server runs in-process with the activity instead of remote CmdEntryPoint. Eliminates cross-process binder IPC for every frame.
2. **Connect X server's root-window pixmap AHB directly to activity's SurfaceView**. Instead of `LorieView` binding the AHB as a GL texture and redrawing every frame, let Android SurfaceFlinger composite the AHB directly. Android supports this via `SurfaceControl.setBuffer()` or `SurfaceView.getHolder().getSurface().attachToGLContext()`.
3. **Remove the activity-side GL compositor pass** for the main content layer. Cursor overlay still needs a GL layer (it's drawn by `drawCursor` in renderer.c:1008) but moves to a small separate surface.
4. **Validate handoff**: when GPU plugin toggles, when resolution changes, when focus changes, the path must not crash. Lifecycle edge cases are where this slice will get stuck.

**Exit Criteria (device-verified)**:
- FpsPlugin overlay ≥ 100 FPS in-game at standard location.
- 10-minute session with movement, combat, camera zoom — no crashes, no visible artifacts.
- HybridX11HostActivity bootable via stock path as fallback (AD4).

**Shell scripts / manifest changes**:
- `launch-runelite.sh` may need to detect `internal-hybrid` mode and skip CmdEntryPoint invocation entirely.

**Error handling**:
- If SurfaceFlinger rejects the AHB format (BGRA vs RGBA), fall back to activity-side GL blit.
- If X server in-process crashes, activity must survive and restart it.

**Deferred**:
- Non-standard resolutions. Native resolution only in this slice.

**Research refs**:
- Direct-surface probe in `runelite-tablet/app/src/main/java/com/runelitetablet/presentation/directsurface/` (if it exists — check first)
- Android `SurfaceControl` API: https://developer.android.com/reference/android/view/SurfaceControl
- Iteration log section on DirectSurfaceProbe (search the log for "DirectSurfaceProbe")

---

### SLICE 6 — Validation, cleanup, spec update

**Why last**: We've gone through the surgery. Now consolidate.

**Estimated size**: 1 session.

**Tasks**:
1. **Remove diagnostic probes that are no longer needed**: `DamageTraceV2 dladdr logging` can go behind DEBUG-only builds; `XloriePerf partial_redraw` log can stay (useful); session-71 `RL-LOG-TAIL` stays (general-purpose).
2. **Update `.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md`** to reflect the actual root-cause found in Slice 1-3, not the pre-investigation hypothesis (CPU/ptrace/VirGL-serialization). Mark phase statuses.
3. **Update `.claude/autoload/_state.md`** with new baseline.
4. **Update memory `~/.claude/projects/…/memory/` ** with any durable learnings (e.g., "damage-FPS is not scene-FPS; use RuneLite's FpsPlugin overlay for ground truth").
5. **10-minute soak test** at 3 different world locations (Lumbridge, Varrock, Wilderness wilderness). FPS ≥ 100 for ≥ 95% of time.
6. **Regression test** stock `CmdEntryPoint` path — still launches, still works, AD4 honored.

**Exit Criteria (device-verified)**:
- Soak test passes.
- Stock path still functional.
- Docs reflect reality.

---

## Dependencies & Build Config

- NDK: as existing (per `runelite-tablet/app/build.gradle`)
- Gradle: `:app:assembleDebug` + `:app:installDebug` workflow unchanged
- Unstripped `.so` must be available — ensure `packaging.jniLibs.keepDebugSymbols` or equivalent is set for debug builds. Verify before Slice 1.
- `adb` access + Termux permission re-grant workflow (session 71 verified)
- Evidence harness: `scripts/hybrid-x11-runelite-evidence.ps1` (PowerShell, host-side)

---

## Error Handling & Edge Cases

1. **Reinstall revokes `com.termux.permission.RUN_COMMAND`.** Every slice's deploy step must include `adb shell pm grant com.runelitetablet com.termux.permission.RUN_COMMAND`. Consider wrapping in `scripts/deploy-and-grant.sh` after Slice 1.
2. **Unstripped .so missing.** If debug symbols aren't present, Slice 1 can't resolve `fn_offset` to symbol names. Add a pre-flight check to the evidence harness.
3. **Scene upload cost.** Session 71 captured a one-time 585ms `Scene upload time` at login. Slice 3-4 optimizations should not make this worse; Slice 5 may improve it.
4. **GE workload is worst case.** User established baseline at the Grand Exchange is unreliable due to player-density variance. Use Lumbridge Castle exterior as canonical test location for all slices.
5. **ANGLE profiles remain available via sentinel** for diagnostic A/B but are not production — documented in session-71 findings.

---

## Deferred / Not in This Plan

- Mesa Venus protocol (backpocket — revisit if slices 1-5 can't reach 120 FPS)
- RuneLite GPU plugin port to Android native (breaks "real RuneLite" goal)
- Vulkan-direct rendering path (out of scope for this plan)
- Fixing `EnablePermissions` setup step to check Android permission grant (tracked separately as a session-71 side-finding)
- Black-line-at-top cosmetic bug (deferred per session 71 user direction)

---

## Research References

- Parent spec: `.claude/specs/2026-04-14-120fps-rendering-pipeline-spec.md`
- Iteration log: `runelite-tablet/docs/hybrid-x11-iteration-log.md`
- Session 70 findings: `runelite-tablet/docs/fps-ceiling-research-session70.md`
- Session 71 findings: `runelite-tablet/docs/fps-ceiling-research-session71.md`
- Session 71 direction: `runelite-tablet/docs/session-71-findings-and-direction.md`
- Previous bottleneck research: `.claude/docs/fps-bottleneck-research.md`
- b50947a commit — partial direct-surface work across 22 native files
- DebugLogServer viewer: `http://<tablet-ip>:8099/`, filter `source=runelite` for RLClient events
- Feedback memory: `~/.claude/projects/C--Users-rseba-Projects-Tablite/memory/feedback_scope_our_pipeline_not_runelite.md`

---

## Slice-by-Slice Task Checklist

### Slice 1
- [ ] Verify unstripped libXlorie.so exists after debug build
- [ ] Add dladdr offset logging to `damageTraceOpWithFunction()`
- [ ] Gate under `RLT_DAMAGE_TRACE_V2=1`
- [ ] Propagate env through launch-runelite.sh + proot env file
- [ ] Build + install on R52X90378YB (remember the perm grant)
- [ ] Run evidence harness, 60s capture at Lumbridge Castle
- [ ] addr2line-resolve every unique `fn_offset`
- [ ] Append Checkpoint section to iteration log
- [ ] Exit gate: hot PutImage + Composite named

### Slice 2
- [ ] Add region-geometry logging to `damagePutImage`
- [ ] Add scene vs partial redraw tag in renderer.c
- [ ] 60s capture; compute damage attribution
- [ ] Write `docs/logs/slice2-damage-attribution.md`
- [ ] Exit gate: 30-event gap explained

### Slice 3
- [ ] Branch on S1 finding (A/B/C/D)
- [ ] Implement smallest viable fix
- [ ] Feature-flag behind `RLT_XLORIE_FAST_PUTIMAGE=1`
- [ ] 60s capture at test location
- [ ] Exit gate: in-game FPS ≥ 25

### Slice 4
- [ ] Instrument DRI3 request path
- [ ] Decision: route DRI3 through our code, OR add memcpy bridge
- [ ] Implement AHB bridge if needed
- [ ] 60s capture
- [ ] Exit gate: `present after-flips > 0`, in-game FPS ≥ 45

### Slice 5
- [ ] Audit direct-surface probe code, understand its surface-setup
- [ ] Wire internal-hybrid X server in-process
- [ ] Attach root-window AHB directly to SurfaceView
- [ ] Separate cursor layer
- [ ] 10-minute stability test
- [ ] Exit gate: in-game FPS ≥ 100, no crashes

### Slice 6
- [ ] Retire one-off debug probes
- [ ] Update 120fps spec with found root causes
- [ ] Update _state.md
- [ ] 3-location soak test
- [ ] Stock-path regression pass
- [ ] Exit gate: all docs reflect reality
