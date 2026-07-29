# rlawt Bionic Rebuild — Task 21 Multi-Phase Plan

**Date**: 2026-04-17
**Status**: Approved — Session 78 execution
**Parent Plan**: `.claude/plans/2026-04-16-direct-surface-path-to-120fps.md` (Option B pivot supersedes its slice sequence)
**Blocker For**: Phase 2.1 (native-Termux launch), Phase 2.2 (RuneLiteSessionService wiring), Phase 3 (A/B FPS measurement)
**Branch**: `spike/direct-android-surface`

---

## Goal

Produce a Bionic-compatible `librlawt.so` (aarch64) that loads under Termux-native OpenJDK-21 without going through proot. Drop it into a repackaged `rlawt-1.8-bionic.jar` at `net/runelite/rlawt/linux-aarch64/librlawt.so`, so the existing upstream `AWTContext.class` loader picks it up under Termux with zero Java changes.

**Non-goals**:
- No change to rlawt Java source. We rebuild only the native library.
- No change to RuneLite. We only change what we ship alongside it.
- No ELF post-surgery (patchelf / gobjcopy). Build correctly from source or fail.
- No glibc environment / gcompat shim / LD_LIBRARY_PATH tricks. (Rejected in S77 — see `slice5-option-b-rlawt-blocker.md`.)

---

## Exit Criteria (device-verified on R52X90378YB)

**S21-FINAL**: under Termux-native (no proot) openjdk-21-x with Mesa + X11 libs loaded:

1. `java -Djava.library.path=$PREFIX/lib MiniRlawtLoad <path-to-librlawt.so>` prints `SUCCESS: librlawt.so loaded`.
2. `MiniAwtContext` harness constructs an `AWTContext` for a live X window, calls `createGLContext()`, and `eglGetCurrentContext()` returns non-NULL.
3. `readelf -d librlawt.so` shows **only** Bionic sonames in `NEEDED` (no `.6`, no `ld-linux-aarch64.so.1`) and reports **no** `VERNEED`, `VERNEEDNUM`, or `VERSYM` sections.
4. `nm -u librlawt.so` returns ≤ 30 undefined symbols, every one of which is exported by a file under `$PREFIX/lib/` on the device.

---

## Evidence Foundation (cite; do not re-derive)

- **Bottleneck diagnosis settled**: proot ptrace syscall interception caps the RuneLite Client thread at ~2000 syscalls/s → ~12 FPS at 164 syscalls/frame. See `runelite-tablet/docs/logs/slice5-jvm-wait-analysis.md`.
- **Seccomp-bpf shortcut blocked**: `RLT_PROOT_SECCOMP=1` fails on Samsung OneUI kernel (`execve` ENOSYS). See `slice5-seccomp-ab.md`.
- **rlawt blocker full probe trail**: four YOLO ELF workarounds each failed at a distinct Bionic-linker rejection layer. See `slice5-option-b-rlawt-blocker.md`.
- **Symbol surface is Bionic-compatible**: 30 undefined (5 libc, 2 stack-protector, 4 weak, 11 GLX/GL, 6 X11, 1 JAWT_GetAWT). Zero pthread, zero exotic glibc usage. Metadata is the only blocker.
- **Termux-native stack already installed** (S77, Task 12): openjdk-21, openjdk-21-x, Mesa 26.0.5, libx11 family, patchelf, binutils.
- **Cpuset is not the bottleneck** (S76): refuted Path A. Do not reintroduce scheduler work.

---

## Architecture / Design Decisions

**AD1 — Rebuild from source, not patch binary.**
Clean toolchain change from glibc cross-compile to Android NDK. Source is public (`github.com/runelite/rlawt`). Zero code change.

**AD2 — NDK 28.2.13676358.**
Newest installed (`C:\Users\rseba\AppData\Local\Android\Sdk\ndk\28.2.13676358`), clang-19-based, handles aarch64-linux-android sysroot with Bionic sonames out of the box. Pin this exact version in the build script for reproducibility.

**AD3 — Target API 31 (Android 12).**
Minimum SDK matching RLT app. Bionic ABI stable; JAWT/openjdk-21 requires no higher.

**AD4 — Vendor rlawt source at `third_party/rlawt/`.**
Matches existing third-party vendoring pattern (`third_party/termux-x11-upstream/`). Keeps the build reproducible offline.

**AD5 — Repackage JAR, do not patch classpath.**
Existing `rlawt-1.8.jar` already has the correct `AWTContext.class` loader — it reads `linux-aarch64/librlawt.so` from the JAR. We ship a drop-in replacement JAR and leave the loader alone.

**AD6 — No ELF post-processing.**
If the NDK toolchain cannot produce the target metadata directly, we fix the `CMakeLists.txt` / link flags, not the output binary. patchelf is explicitly out (S77 failures documented).

**AD7 — Every phase has a verification gate.**
Each sub-phase exits on a concrete `readelf`/`nm`/`java` command + expected output. No "looks good, moving on."

---

## Open Unknowns (resolve before 21A)

| # | Unknown | Recon step | Consumer |
|---|---|---|---|
| U1 | Upstream rlawt build system — CMake? Make? Gradle-native? | `git clone` + `cat build.gradle.kts CMakeLists.txt` | 21B |
| U2 | Which rlawt source tag matches `rlawt-1.8.jar`? | Check `rlawt-1.8.pom` or `META-INF/MANIFEST.MF` inside the jar | 21A |
| U3 | Where is `jawt_md.h` sourced? Upstream rlawt vendors it? Uses OpenJDK headers? | Grep rlawt source for `jawt_md.h` include | 21B |
| U4 | Can the RuneLite RuneLite-client JAR be resolved on Termux-native without proot's filesystem view? | List `ls proot-distro/ubuntu-22.04/root/.runelite/cache/jagex-jagex-runelite-*.jar` paths | Phase 2.1 |

---

## Implementation Order

---

### SLICE 21A — Vendor upstream rlawt and audit the source

**Why first**: Every downstream decision (CMake edits, link flags, header paths) is driven by what's in the source tree. Audit before modifying.

**Estimated size**: 30-60 min, no build yet.

**Tasks**:
1. `git clone https://github.com/runelite/rlawt third_party/rlawt-scratch` (temporary scratch location outside the repo).
2. Identify the tag matching `rlawt-1.8.jar`. Check the jar's `META-INF/MANIFEST.MF` and any `*.pom` files. `git checkout <matching-tag>`.
3. Copy the cleaned source tree to `third_party/rlawt/`. Retain `LICENSE`, `.cpp`/`.c`/`.h` sources, `CMakeLists.txt`, and upstream README. Drop build outputs, gradle caches, `.git/`.
4. Audit source files:
   - List all `.c`/`.cpp` files under `src/main/c/`. Count LOC.
   - Grep for glibc-specific headers (`<malloc.h>`, `<sys/epoll.h>`, `<features.h>`). Expect none.
   - Grep for `JAWT_GetAWT`, `dlopen`, `dlsym`, `glXCreateContext`. Map each to the target NEEDED .so.
   - Read `CMakeLists.txt`: what libraries does it link? What include paths? Any `-D` flags that gate glibc behavior?
5. Produce `third_party/rlawt/AUDIT.md` recording: source file list, symbol map (undefined → expected provider .so), CMake link-line summary, any red flags.

**Exit Criteria**:
- `third_party/rlawt/` exists with upstream source at the tag matching `rlawt-1.8.jar`.
- `AUDIT.md` is present and answers U1/U2/U3.
- Zero glibc-specific header includes found (or each one documented as non-issue).

**Deferred**:
- Any CMakeLists.txt edits. That's Phase 21B.

---

### SLICE 21B — NDK toolchain wiring + header harvest

**Why second**: Before we can compile, CMake needs to find the NDK sysroot, the JAWT header, and the X11/GL headers. All of those must be resolved to concrete paths.

**Estimated size**: 30-90 min.

**Tasks**:
1. Pin NDK: `C:\Users\rseba\AppData\Local\Android\Sdk\ndk\28.2.13676358`. Verify `bin/aarch64-linux-android31-clang` exists.
2. JAWT header: locate `jawt.h` + `jawt_md.h` in a local OpenJDK 21 install (matching the Termux `openjdk-21-x` ABI expectations). Preferred source: the NDK's jawt-header is NOT included — harvest from OpenJDK upstream (`github.com/openjdk/jdk21u` `src/java.desktop/share/native/include/jawt.h` and `linux/native/include/jawt_md.h`). Copy to `third_party/rlawt/include/jawt/` as a build-time include path.
3. X11 headers: `adb pull /data/data/com.termux/files/usr/include/X11/ third_party/rlawt/include/X11/`. Extract the subset rlawt needs (`Xlib.h`, `Xutil.h`, `keysym.h` — whatever the audit named).
4. GL headers: `adb pull /data/data/com.termux/files/usr/include/GL/ third_party/rlawt/include/GL/`. Specifically `glx.h`, `gl.h`, `glext.h`.
5. Termux sysroot stubs for link-time resolution: `adb pull` of `$PREFIX/lib/libjawt.so`, `libGL.so`, `libX11.so`, `libc.so` (Bionic), `libdl.so` into `third_party/rlawt/sysroot-stubs/`. These are the link targets whose sonames the NDK linker will embed into NEEDED.
6. Write `scripts/build-rlawt-bionic.sh` — thin wrapper that:
   - Sets `ANDROID_NDK_ROOT=C:/Users/rseba/AppData/Local/Android/Sdk/ndk/28.2.13676358`
   - Runs `cmake -B third_party/rlawt/build-bionic -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DJAWT_INCLUDE=... -DX11_INCLUDE=... -DGL_INCLUDE=... -DSYSROOT_STUBS=...`
   - Runs `cmake --build third_party/rlawt/build-bionic`.

**Exit Criteria**:
- `scripts/build-rlawt-bionic.sh` runs without CMake configure errors.
- All headers resolve (compile dry-run with `-E` succeeds on a single source file).

**Deferred**:
- Actually linking. That's Phase 21C.

---

### SLICE 21C — Link-line correctness

**Why third**: THIS is the critical part. The build must output an ELF whose metadata matches the target table exactly. Getting this wrong means we rebuild clean and hit the same Bionic rejection.

**Target ELF specification** (verified via `readelf -d`):

| Field | Expected value | How to force it |
|---|---|---|
| NEEDED entries | `libjawt.so`, `libGL.so`, `libX11.so`, `libc.so`, `libdl.so` | Link against the pulled Termux `$PREFIX/lib/*.so` (Bionic sonames); rely on NDK sysroot for `libc.so`/`libdl.so` |
| No `libc.so.6` NEEDED | — | Don't link against glibc libs; NDK's sysroot libc soname is `libc.so` |
| No `ld-linux-aarch64.so.1` NEEDED | — | NDK toolchain omits interpreter on `.so` by default |
| No VERNEED / VERSYM sections | — | Don't link against versioned glibc stubs; Bionic stubs have no symbol versioning |
| Soname | `librlawt.so` | `-Wl,-soname,librlawt.so` |
| Exports | `JNI_OnLoad`, `Java_net_runelite_rlawt_AWTContext_*` | Keep default visibility on JNI exports; `-fvisibility=hidden` otherwise |

**Tasks**:
1. Edit `third_party/rlawt/CMakeLists.txt` to:
   - Set `CMAKE_SHARED_LIBRARY_PREFIX ""` (so output is `librlawt.so`, not `liblibrlawt.so`).
   - Set `target_link_options(rlawt PRIVATE -Wl,-soname,librlawt.so -Wl,--no-undefined)`.
   - Link targets: `${SYSROOT_STUBS}/libjawt.so`, `.../libGL.so`, `.../libX11.so`. (Full paths, not `-ljawt` — that would try to find `libjawt.a` or a path-resolved .so that may have wrong soname.)
   - Include paths in order: JAWT headers, X11 headers, GL headers, rlawt own source.
2. Build: `bash scripts/build-rlawt-bionic.sh`. Expect output at `third_party/rlawt/build-bionic/librlawt.so`.
3. Inspect with `readelf -d`. Record actual output in `third_party/rlawt/build-bionic/readelf-dynamic.txt`.
4. Compare against target table. Every row must match.

**Exit Criteria**:
- `librlawt.so` builds without warnings.
- `readelf -d` output matches the target ELF specification exactly (no extra NEEDED entries, no VERNEED/VERSYM sections).

**Failure branches**:
- If NDK emits `libc.so.6`: the linker's link-against path picked up a glibc stub. Check `SYSROOT_STUBS` pull — verify `file libc.so` shows Bionic, not glibc.
- If VERNEED appears: we accidentally linked against a Linux-glibc header-stub lib. Check Mesa/X11 stubs pulled from device — they should not have version sections.

---

### SLICE 21D — Undefined-symbol audit

**Why fourth**: ELF metadata can be perfect and the library can still crash at `dlopen` time if an undefined symbol isn't resolvable at runtime. Cross-check before shipping to device.

**Tasks**:
1. Run `aarch64-linux-android-nm -u third_party/rlawt/build-bionic/librlawt.so` — capture to `third_party/rlawt/build-bionic/undef-symbols.txt`.
2. For each undefined symbol, identify its expected provider per the S77 analysis table:
   - `snprintf`, `memset`, `calloc`, `free`, `strstr` → Bionic `libc.so`
   - `__stack_chk_fail`, `__stack_chk_guard` → Bionic `libc.so`
   - `__cxa_finalize`, `__gmon_start__`, `_ITM_*` → weak/optional (resolved or not, no crash)
   - `glX*`, `gl*` (11 symbols) → Termux `libGL.so` (Mesa)
   - `XFree`, `XOpenDisplay`, `XSetErrorHandler`, `XDisplayString`, `XSync`, `XCloseDisplay` → Termux `libX11.so`
   - `JAWT_GetAWT` → openjdk-21-x `libjawt.so`
3. For each provider, on device: `adb shell "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib aarch64-linux-android-objdump -T /data/data/com.termux/files/usr/lib/<lib.so> | grep <symbol>"`. Expect every non-weak symbol to show up in a `.dynsym` entry.
4. Produce `third_party/rlawt/build-bionic/symbol-resolution-map.md` — one row per undefined symbol, provider, verification command, pass/fail.

**Exit Criteria**:
- ≤ 30 undefined symbols.
- Every non-weak symbol confirmed present in a Termux-installed .so.

---

### SLICE 21E — On-device load test (MiniRlawtLoad)

**Why fifth**: Static audit is necessary but not sufficient. Bionic dynamic loading has runtime-only checks (namespace, permitted paths). The S77 `MiniRlawtLoad.java` harness already exists at `/data/data/com.termux/files/home/rlawt-test/` — reuse it.

**Tasks**:
1. `adb push third_party/rlawt/build-bionic/librlawt.so /data/sdcard/Download/librlawt-bionic.so` then `run-as com.termux cp` into `$HOME/rlawt-test/librlawt-bionic.so`.
2. `run-as com.termux sh -c "cd $HOME/rlawt-test && java -Djava.library.path=$HOME/rlawt-test MiniRlawtLoad librlawt-bionic.so"`.
3. Expect exit code 0 and `SUCCESS: librlawt.so loaded` on stdout.
4. On failure, rerun with `LD_DEBUG=libs,reloc java ... 2>ld-debug.log` and post-mortem the log — if `cannot locate symbol "<sym>"`, return to 21D; if `library "<name>" not found`, return to 21C (NEEDED mismatch).

**Exit Criteria**:
- `SUCCESS: librlawt.so loaded` printed by MiniRlawtLoad.
- No `UnsatisfiedLinkError`, no `dlopen` failure in logs.

---

### SLICE 21F — Repackage rlawt-1.8.jar

**Why sixth**: The RuneLite classpath expects a single jar with a specific internal layout. Replace the .so inside without touching class files.

**Tasks**:
1. Obtain the original `rlawt-1.8.jar`. Likely at `$HOME/.runelite/cache/rlawt-1.8.jar` (inside proot) or via a Maven coordinate resolved by RuneLite bootstrap. Pull to host.
2. `unzip rlawt-1.8.jar -d /tmp/rlawt-1.8-unpack/`.
3. Verify `/tmp/rlawt-1.8-unpack/net/runelite/rlawt/linux-aarch64/librlawt.so` exists (the glibc version we're replacing). Back up as `librlawt.so.glibc-original`.
4. Overwrite with our Bionic build: `cp third_party/rlawt/build-bionic/librlawt.so /tmp/rlawt-1.8-unpack/net/runelite/rlawt/linux-aarch64/librlawt.so`.
5. Re-zip: `cd /tmp/rlawt-1.8-unpack && zip -r ../rlawt-1.8-bionic.jar .`.
6. Sanity check: `unzip -p /tmp/rlawt-1.8-bionic.jar net/runelite/rlawt/linux-aarch64/librlawt.so | readelf -d - | head -20` shows the Bionic NEEDED set.
7. Verify `AWTContext.class` unchanged: `javap -c /tmp/rlawt-1.8-unpack/net/runelite/rlawt/AWTContext.class | head -30` — confirm the `System.loadLibrary` / native-method signatures are unmodified.

**Exit Criteria**:
- `rlawt-1.8-bionic.jar` exists; structure identical to `rlawt-1.8.jar` except the one .so replaced.
- MD5/SHA check proves only `librlawt.so` differs.

---

### SLICE 21G — Integration smoke test (MiniAwtContext)

**Why seventh**: Loading the library is step one. Actually creating an AWTContext and a GL context against a live X server is the real proof. This is the last gate before we rewire `launch-runelite.sh`.

**Tasks**:
1. Write `third_party/rlawt/test/MiniAwtContext.java` — minimal harness:
   - Creates an `AWT` `Frame` + `Canvas`.
   - Calls `new AWTContext(canvas)` (or whatever the upstream API is).
   - Calls `createGLContext()`.
   - Queries `eglGetCurrentContext` via JNI OR via a public rlawt API.
   - Prints `GL_VERSION` + `GL_RENDERER`.
2. Build: compile against the repackaged JAR (`javac -cp rlawt-1.8-bionic.jar MiniAwtContext.java`).
3. Push to device under `$HOME/rlawt-test/`. Launch with: `termux-x11 :1 &` (Termux-native X server), then `DISPLAY=:1 java -cp "rlawt-1.8-bionic.jar:." MiniAwtContext`.
4. Expect output: GL vendor/renderer/version, non-NULL context, no `GLXBadContext`, no segfault.

**Exit Criteria**:
- MiniAwtContext prints GL version string.
- No native crash during `createGLContext()`.
- Clean exit (no dangling X connections, no zombie JVM).

**Failure branches**:
- `GLXBadFBConfig`: Mesa on Termux isn't exposing an FB config rlawt expects. Log FBConfig list; possibly patch rlawt's FBConfig picker.
- Segfault inside rlawt: gdb-on-device, symbol resolution against `librlawt.so`. Likely a header mismatch between the JAWT headers we harvested and the openjdk-21 on device. Re-pull JAWT headers from the exact device JDK.

---

### SLICE 21H — Commit + vendor the build pipeline

**Why last**: We want the whole rebuild reproducible by any future session with one command. Vendor the source + build script; treat the JAR as a build artifact.

**Tasks**:
1. `third_party/rlawt/` — vendored source + `AUDIT.md` + `build-bionic/` README explaining outputs. Add `third_party/rlawt/build-bionic/` to `.gitignore` (build artifact).
2. `scripts/build-rlawt-bionic.sh` — committed, reproducible build. Document required env: NDK 28.2.13676358, host platform Windows/WSL.
3. Decide on JAR distribution: either (a) commit `rlawt-1.8-bionic.jar` under `runelite-tablet/app/libs/` (binary, matches pattern), or (b) produce at build time. Pick (a) — needed at runtime, path must be stable.
4. Commit grammar per `scripts/git/commit-msg` rules:
   ```
   build(native): vendor rlawt Bionic rebuild for native-Termux JVM

   Problem: rlawt-1.8.jar ships a glibc-linked linux-aarch64/librlawt.so
   which Bionic's dynamic linker refuses at four layers (NEEDED soname,
   namespace permitted_paths, VERNEED, VERSYM). Rebuilding via Android
   NDK produces a Bionic-sonamed .so with no version sections.

   Decision: vendor upstream rlawt source at third_party/rlawt/; ship a
   one-command build script at scripts/build-rlawt-bionic.sh using NDK
   28.2.13676358; ship the built JAR at runelite-tablet/app/libs/
   rlawt-1.8-bionic.jar as a binary artifact.

   Evidence: on-device MiniRlawtLoad + MiniAwtContext both succeed
   under Termux-native openjdk-21-x (no proot).

   Reason: unblocks Option B (native-Termux JVM) — eliminates the proot
   ptrace syscall interception that capped RL FPS at 12.
   Refs: slice5-option-b-rlawt-blocker.md
   ```
5. Update `.claude/autoload/_state.md` — mark Task 21 complete; set new HOT entry point at Phase 2.1 (native launch script).

**Exit Criteria**:
- Single commit lands `third_party/rlawt/` + `scripts/build-rlawt-bionic.sh` + `runelite-tablet/app/libs/rlawt-1.8-bionic.jar` + `_state.md` update.
- Commit passes hook (subject grammar, scope, body ≥ 20 chars, `Reason:` trailer).

---

## Downstream Phases (after Task 21 lands)

These are out of scope for Task 21 itself but belong in the same plan so the full arc is visible.

### Phase 2.1 — Native-Termux launch script

Write `launch-runelite-native.sh` (new file, adjacent to `launch-runelite.sh`). Gated by `RLT_NATIVE_TERMUX=1`. Skips `proot-distro login` entirely. Sets `CLASSPATH` to include the repackaged `rlawt-1.8-bionic.jar` + any RL dependencies reachable outside proot. Invokes `openjdk-21-x`'s `java` directly.

Key deltas vs current `launch-runelite.sh`:
- No `proot-distro login ubuntu-22.04 -- bash …`
- `LD_LIBRARY_PATH=$PREFIX/lib` (Termux native) instead of Ubuntu's `/usr/lib/...`
- `PROOT_ENV_FILE` path becomes irrelevant; inline env instead
- `virgl_test_server_android` stays pre-spawned (already Termux-native)
- X server unchanged

Open question (U4): can all RL runtime JARs be resolved from outside proot? If not, Phase 2.1 needs a classpath-relocation sub-step.

### Phase 2.2 — RuneLiteSessionService selector + ScriptManager fix

1. Add `RLT_NATIVE_TERMUX` env gate to `RuneLiteSessionService` — pick `launch-runelite-native.sh` vs `launch-runelite.sh` based on feature flag + device capability probe.
2. Fix ScriptManager re-deploy bug (memory `project_script_redeploy_vs_stale_apk.md`) — checksum-based re-deploy so new scripts don't get stuck behind the `scriptsDeployed` companion flag.

### Phase 3 — A/B FPS measurement

At Varrock East Bank, 60-second FpsPlugin capture under each path:
- Baseline (proot path, RLT_NATIVE_TERMUX=0): expected 12 FPS (unchanged).
- Native path (RLT_NATIVE_TERMUX=1): expected ≥ 30 FPS (ptrace removed). Spike goal ≥ 60 FPS. S-FINAL target ≥ 100 FPS.

If native path shows < 30 FPS, the ptrace hypothesis from S77 is still right but there's a second bottleneck. Re-run `jvm-wait-sampler.sh` against the native-Termux JVM to find it.

---

## Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Upstream `CMakeLists.txt` has no Android/NDK build target; we'd need to write one | Medium | Medium — adds ~2h | Write a fresh CMakeLists.txt for the Android target; the source files are small (< 1 kLoC estimate) |
| R2 | Termux `libGL.so` (Mesa) has its own VERSYM on exported symbols, which our `librlawt.so` will inherit references to and Bionic rejects | Low | High — blocks Phase 21E | Probe early: `readelf -V $PREFIX/lib/libGL.so` on device before building |
| R3 | JAWT header mismatch between harvested upstream OpenJDK-21 headers and Termux's `openjdk-21-x` JAWT ABI | Medium | Medium — runtime crash | Pull `jawt.h` directly from Termux's openjdk-21 package on device via adb; don't trust upstream headers |
| R4 | RL JARs aren't classpath-reachable outside proot — blocks Phase 2.1 | Medium | Medium — Phase 2.1 scope expands | U4 recon answers this; if blocked, Phase 2.1 adds a JAR-relocation sub-step |
| R5 | Task 21 takes > 1 day — cost of pivoting to Task 20 (fork proot, patch seccomp) rises | Low | Medium | Hard cutoff at 8h; if not verified on device by then, document state and pivot to Task 20 evaluation |

---

## Time Estimate

- **Task 21 focused**: 4–8 hours. S77 initially estimated 2–4h; bumped for NDK toolchain friction, header wrangling, and on-device iteration.
- **Phases 2.1–3**: 1–2 sessions after Task 21 lands.

---

## References

- `runelite-tablet/docs/logs/slice5-option-b-rlawt-blocker.md` — primary blocker analysis, target ELF spec, YOLO attempt graveyard.
- `runelite-tablet/docs/logs/slice5-jvm-wait-analysis.md` — bottleneck diagnosis, ptrace math.
- `runelite-tablet/docs/logs/slice5-seccomp-ab.md` — why seccomp shortcut failed.
- `.claude/memory/project_ptrace_is_the_fps_bottleneck.md` — durable lesson.
- `github.com/runelite/rlawt` — upstream source.
- `developer.android.com/ndk` — toolchain reference.
