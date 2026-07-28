#!/usr/bin/env bash
# Reproducible Bionic-native rebuild of rlawt for Termux.
#
# Context: rlawt-1.8.jar ships a glibc-linked librlawt.so which Bionic's
# dynamic linker rejects at four layers (NEEDED soname, namespace
# permitted_paths, VERNEED glibc versions, VERSYM/vn_version). This
# script cross-compiles the same rlawt C sources through the Android NDK
# against Termux-harvested stub libs, producing a drop-in librlawt.so
# with Bionic NEEDED sonames.
#
# Requirements:
#   - Android NDK 28.2.13676358 at the path below (or set ANDROID_NDK_ROOT)
#   - Host JDK 11+ (for the pre-generated JNI header — already checked in
#     at third_party/rlawt-bionic/generated/, so JDK not needed at build
#     time unless you regenerate)
#   - Harvested target headers + stub libs at
#     third_party/rlawt-bionic/harvest/ (see harvest README).
#
# Output: third_party/rlawt-bionic/build-bionic/librlawt.so

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

: "${ANDROID_NDK_ROOT:=C:/Users/rseba/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
: "${ANDROID_SDK_CMAKE:=C:/Users/rseba/AppData/Local/Android/Sdk/cmake/3.22.1/bin}"
: "${ANDROID_ABI:=arm64-v8a}"
: "${ANDROID_PLATFORM:=android-31}"

# Variant selection. By default we build the GLX/X11 backend that drops into
# librlawt.so — same as the file we've been shipping since S78. Pass
# --direct-surface to build the EGL-on-AHardwareBuffer variant intended for
# the rlawt-on-Surface engineering block (see docs/s82-capture/
# direct-android-blocker-3-scope.md). The direct-surface build also
# repackages a complete drop-in JAR with our patched AWTContext.class
# (ComponentListener + notifyResized + java-side logging).
VARIANT="default"

if [[ -d "${ANDROID_SDK_CMAKE}" ]]; then
    # Git-Bash needs MSYS-form paths on PATH for command lookups to work.
    _msys_cmake_bin="$(cygpath -u "${ANDROID_SDK_CMAKE}" 2>/dev/null || echo "${ANDROID_SDK_CMAKE}")"
    export PATH="${_msys_cmake_bin}:${PATH}"
fi

if ! command -v cmake >/dev/null 2>&1; then
    echo "ERROR: cmake not on PATH. Install Android SDK cmake or set ANDROID_SDK_CMAKE." >&2
    exit 1
fi

if [[ ! -f "${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" ]]; then
    echo "ERROR: NDK toolchain file not found at:" >&2
    echo "  ${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" >&2
    echo "Set ANDROID_NDK_ROOT to a valid NDK install." >&2
    exit 1
fi

CLEAN=0
INSTALL_APP_JAR=0
EXTRA_CMAKE_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean) CLEAN=1; shift ;;
        --direct-surface) VARIANT="direct-surface"; shift ;;
        --install-app-jar) INSTALL_APP_JAR=1; shift ;;
        *) echo "ERROR: unknown arg '$1' (expected --clean, --direct-surface, or --install-app-jar)" >&2; exit 1 ;;
    esac
done

if [[ "$VARIANT" == "direct-surface" ]]; then
    BUILD_DIR="third_party/rlawt-bionic/build-direct"
    EXTRA_CMAKE_ARGS+=(-DRLAWT_DIRECT_SURFACE=ON)
else
    BUILD_DIR="third_party/rlawt-bionic/build-bionic"
fi

if [[ "$CLEAN" == "1" ]]; then
    echo "Cleaning ${BUILD_DIR}..."
    rm -rf "${BUILD_DIR}"
fi

mkdir -p "${BUILD_DIR}"

echo "Configuring rlawt-bionic (variant=${VARIANT}) with NDK at ${ANDROID_NDK_ROOT}..."
cmake \
    -S third_party/rlawt-bionic \
    -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ANDROID_ABI}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DCMAKE_BUILD_TYPE=Release \
    "${EXTRA_CMAKE_ARGS[@]}" \
    -G "Ninja"

echo ""
echo "Building librlawt.so..."
cmake --build "${BUILD_DIR}" --config Release --verbose

OUTPUT="${BUILD_DIR}/librlawt.so"
if [[ ! -f "${OUTPUT}" ]]; then
    echo "ERROR: build completed but ${OUTPUT} does not exist" >&2
    exit 1
fi

echo ""
echo "=== Build succeeded ==="
echo "Output: ${OUTPUT}"
echo "Size:   $(wc -c < "${OUTPUT}") bytes"

READELF="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
if [[ -x "${READELF}" ]]; then
    echo ""
    echo "=== readelf -d ==="
    "${READELF}" -d "${OUTPUT}" | grep -E "(NEEDED|SONAME|VERNEED)" || true
fi

# The production GLX variant changes only the native payload inside rlawt.
# Repackage it reproducibly and prove every Java class remains byte-identical
# before optionally installing both APK source copies.
if [[ "$VARIANT" == "default" ]]; then
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jar" ]]; then
        JAR_BIN="${JAVA_HOME}/bin/jar"
    elif [[ -x "C:/Program Files/Android/Android Studio/jbr/bin/jar.exe" ]]; then
        JAR_BIN="C:/Program Files/Android/Android Studio/jbr/bin/jar.exe"
    else
        echo "ERROR: cannot find jar. Set JAVA_HOME to a JDK 11+ install." >&2
        exit 1
    fi

    BASE_JAR="runelite-tablet/app/libs/rlawt-1.8-bionic.jar"
    OUT_JAR="${BUILD_DIR}/rlawt-1.8-bionic.jar"
    PACKAGE_DIR="${BUILD_DIR}/jar-native-payload"
    VERIFY_BASE="${BUILD_DIR}/verify-base"
    VERIFY_OUT="${BUILD_DIR}/verify-out"
    rm -rf "${PACKAGE_DIR}" "${VERIFY_BASE}" "${VERIFY_OUT}"
    mkdir -p "${PACKAGE_DIR}/net/runelite/rlawt/linux-aarch64" "${VERIFY_BASE}" "${VERIFY_OUT}"
    cp "${OUTPUT}" "${PACKAGE_DIR}/net/runelite/rlawt/linux-aarch64/librlawt.so"
    cp "${BASE_JAR}" "${OUT_JAR}"
    "${JAR_BIN}" uf "${OUT_JAR}" \
        -C "${PACKAGE_DIR}" net/runelite/rlawt/linux-aarch64/librlawt.so

    (cd "${VERIFY_BASE}" && "${JAR_BIN}" xf "${REPO_ROOT}/${BASE_JAR}")
    (cd "${VERIFY_OUT}" && "${JAR_BIN}" xf "${REPO_ROOT}/${OUT_JAR}")
    (cd "${VERIFY_BASE}" && find . -type f -name '*.class' -print0 | sort -z | xargs -0 sha256sum) \
        > "${BUILD_DIR}/base-class-sha256s.txt"
    (cd "${VERIFY_OUT}" && find . -type f -name '*.class' -print0 | sort -z | xargs -0 sha256sum) \
        > "${BUILD_DIR}/output-class-sha256s.txt"
    if ! diff -u "${BUILD_DIR}/base-class-sha256s.txt" "${BUILD_DIR}/output-class-sha256s.txt"; then
        echo "ERROR: default rlawt repack changed Java class bytes" >&2
        exit 1
    fi
    echo "Repacked production JAR: ${OUT_JAR} ($(wc -c < "${OUT_JAR}") bytes)"
    echo "Java class integrity: PASS (all class hashes byte-identical)"

    if [[ "$INSTALL_APP_JAR" == "1" ]]; then
        cp "${OUT_JAR}" "runelite-tablet/app/libs/rlawt-1.8-bionic.jar"
        cp "${OUT_JAR}" "runelite-tablet/app/src/main/assets/libs/rlawt-1.8-bionic.jar"
        echo "Installed production JAR into app/libs and app/src/main/assets/libs"
    fi
elif [[ "$INSTALL_APP_JAR" == "1" ]]; then
    echo "ERROR: --install-app-jar is only valid for the production GLX variant" >&2
    exit 1
fi

# ===================================================================
# Direct-surface variant: also compile our patched AWTContext.java +
# repackage a complete drop-in JAR. The launcher reads this JAR when
# RLT_DIRECT_SURFACE=1.
# ===================================================================
if [[ "$VARIANT" == "direct-surface" ]]; then
    echo ""
    echo "=== Repackaging direct-surface JAR ==="

    # Locate javac. Try $JAVA_HOME, then Android Studio's bundled JBR.
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
        JAVAC_BIN="${JAVA_HOME}/bin/javac"
        JAR_BIN="${JAVA_HOME}/bin/jar"
    elif [[ -x "C:/Program Files/Android/Android Studio/jbr/bin/javac.exe" ]]; then
        JAVAC_BIN="C:/Program Files/Android/Android Studio/jbr/bin/javac.exe"
        JAR_BIN="C:/Program Files/Android/Android Studio/jbr/bin/jar.exe"
    else
        echo "ERROR: cannot find javac. Set JAVA_HOME to a JDK 11+ install." >&2
        exit 1
    fi
    echo "Using javac: ${JAVAC_BIN}"

    AWTCTX_SRC="third_party/rlawt/AWTContext.java"
    JAVA_OUT_DIR="${BUILD_DIR}/java-classes"
    rm -rf "${JAVA_OUT_DIR}"
    mkdir -p "${JAVA_OUT_DIR}"

    "${JAVAC_BIN}" -source 1.8 -target 1.8 -d "${JAVA_OUT_DIR}" "${AWTCTX_SRC}"

    # Find the upstream JAR to repack. Prefer the existing bionic jar (which
    # already strips the glibc .so and ships our linux-aarch64 .so location);
    # fall back to repository2 if you have an unmodified rlawt-1.8.jar lying
    # around and want to start fresh.
    BASE_JAR=""
    if [[ -f "runelite-tablet/app/libs/rlawt-1.8-bionic.jar" ]]; then
        BASE_JAR="runelite-tablet/app/libs/rlawt-1.8-bionic.jar"
    fi
    if [[ -z "$BASE_JAR" ]]; then
        echo "ERROR: cannot find a base rlawt JAR to repack" >&2
        exit 1
    fi
    echo "Base JAR: ${BASE_JAR} ($(wc -c < "$BASE_JAR") bytes)"

    REPACK_DIR="${BUILD_DIR}/jar-repack"
    rm -rf "${REPACK_DIR}"
    mkdir -p "${REPACK_DIR}"
    (cd "${REPACK_DIR}" && "${JAR_BIN}" xf "${REPO_ROOT}/${BASE_JAR}")

    # Drop our patched AWTContext.class + AWTContext$1.class (the
    # ComponentAdapter inner class). javac emits both into JAVA_OUT_DIR.
    mkdir -p "${REPACK_DIR}/net/runelite/rlawt"
    cp "${JAVA_OUT_DIR}/net/runelite/rlawt/"*.class "${REPACK_DIR}/net/runelite/rlawt/"

    # Replace the linux-aarch64 .so with our direct-surface build.
    mkdir -p "${REPACK_DIR}/net/runelite/rlawt/linux-aarch64"
    cp "${OUTPUT}" "${REPACK_DIR}/net/runelite/rlawt/linux-aarch64/librlawt.so"

    OUT_JAR="${BUILD_DIR}/rlawt-1.8-direct-surface.jar"
    rm -f "${OUT_JAR}"
    (cd "${REPACK_DIR}" && "${JAR_BIN}" cf "${REPO_ROOT}/${OUT_JAR}" .)
    echo "Repacked direct-surface JAR: ${OUT_JAR} ($(wc -c < "${OUT_JAR}") bytes)"

    echo ""
    echo "=== Direct-surface variant ==="
    echo "  .so        : ${OUTPUT}"
    echo "  classes    : ${JAVA_OUT_DIR}"
    echo "  drop-in jar: ${OUT_JAR}"
    echo ""
    echo "To deploy: adb push ${OUT_JAR} /sdcard/ ; then adb shell run-as com.termux"
    echo "  cp /sdcard/$(basename ${OUT_JAR}) \$HOME/.rlt/"
fi
