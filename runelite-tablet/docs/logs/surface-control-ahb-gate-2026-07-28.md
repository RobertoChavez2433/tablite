# SurfaceControl/AHardwareBuffer Gate — 2026-07-28

## Scope

This is the Android-only first rung of the direct-presentation ladder. It does
not run RuneLite or VirGL. The probe submits a fixed triple pool of
native-resolution RGBA HardwareBuffers to a child SurfaceControl using public
Android APIs, then correlates submit, transaction latch, present fence, release
fence, and buffer reuse.

Device:

- Stock, unrooted Samsung SM-X920.
- Android API 36.
- Landscape buffer size 2960×1848.
- Requested and active display cadence 120 Hz.

## Result

The primitive passed its bounded feasibility gate:

- HardwareBuffer usage `0xb20` was accepted. This includes composer overlay,
  GPU sampled image, GPU color output, and CPU-write access used only to
  initialize the test pattern.
- SurfaceFlinger reported the child as `RGBA_8888`, `V0_SRGB`, 2960×1848,
  120 Hz, and `composition type=DEVICE`.
- A captured screenshot showed the expected full-screen checkerboard, proving
  the submitted pixels reached the physical composition path.
- The long run reached 11,791 submitted transactions and 11,743 confirmed
  present-fence signals. The final steady one-second windows were generally
  118–120 confirmed presentations per second.
- The run observed five transient buffer-starvation callbacks and zero fence
  timeouts.
- 11,788 release callbacks were observed and 11,788 subsequent submissions
  reused signaled release fences as acquire fences.
- After warm-up, the process FD count remained flat at 206 throughout the
  remainder of the 10,000-frame run.
- One hundred foreground/background pool-replacement cycles completed in the
  same process. Logs contained 101 `POOL-CREATED` and 101 `POOL-DESTROYED`
  records (the initial run plus 100 cycles), zero fence timeouts, zero submit
  failures, zero teardown failures, and zero fatal exceptions.
- Three seconds after the final teardown, the process had 162 open FDs; there
  was no cumulative descriptor growth across the cycle test.
- The SUMMARY recorder reported zero telemetry loss.

Commands used:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.runelitetablet/.presentation.DirectSurfaceProbeActivity
adb shell dumpsys SurfaceFlinger --layers
```

## Interpretation

The panel, public SurfaceControl buffer submission, native-resolution
HardwareBuffer allocation, and explicit fence lifecycle are not themselves a
hard 120 FPS ceiling on this device. This removes the consumer fullscreen GLES
blit from the proposed direct route and makes VirGL final-frame export/import
the next material gate.

This result does **not** prove:

- Desktop OpenGL/VirGL can export its resolved color resource into this pool.
- Cross-UID HardwareBuffer and native-fence transport is stable.
- A host-side VirGL-to-AHB copy meets the 8.33 ms service budget.
- RuneLite's stock GPU plugin reaches 120 FPS end to end.

The probe uses CPU access only once to initialize a known pixel pattern. The
production route must replace that initializer with measured VirGL image
export, copy, or direct rendering without adding a consumer-side fullscreen
blit.
