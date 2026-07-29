cmake_minimum_required(VERSION 3.22)
project(rlawt-bionic C)

# Bionic-native build of rlawt for Termux (Samsung OneUI + Android 12+).
#
# Drop-in replacement for rlawt-1.8.jar's
# net/runelite/rlawt/linux-aarch64/librlawt.so. Compiled against Termux
# openjdk-21-x JAWT + Termux Mesa libGL + Termux libX11 (all Bionic
# sonames, no glibc). The upstream rlawt source under ../rlawt/ is
# untouched; we just compile it through the NDK toolchain with harvested
# target headers and link stubs.

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# RLAWT_DIRECT_SURFACE: build the EGL-on-AHardwareBuffer direct-Android-surface
# variant (rlawt_android.c) instead of the GLX/X11 variant (rlawt_nix.c).
# Both produce librlawt.so but link against very different system libs. The
# direct-surface variant skips the harvested X11/GLX stubs and links Bionic
# EGL/GLES/nativewindow/libandroid from the NDK sysroot.
option(RLAWT_DIRECT_SURFACE "Build the EGL-on-AHB direct-surface backend" OFF)

set(RLAWT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../rlawt")
set(HARVEST "${CMAKE_CURRENT_SOURCE_DIR}/harvest")
set(GENERATED "${CMAKE_CURRENT_SOURCE_DIR}/generated")
set(RLAWT_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(RLT_TELEMETRY_INCLUDE
    "${CMAKE_CURRENT_SOURCE_DIR}/../termux-packages/packages/mesa")

# Sanity: harvest paths shared by both backends must resolve.
set(_REQUIRED_HARVEST
    "${RLAWT_ROOT}/rlawt.c"
    "${RLAWT_ROOT}/rlawt_nix.c"
    "${RLAWT_ROOT}/rlawt_windows.c"
    "${RLAWT_ROOT}/rlawt.h"
    "${HARVEST}/jdk-include/jni.h"
    "${HARVEST}/jdk-include/jawt.h"
    "${HARVEST}/jdk-include/linux/jni_md.h"
    "${HARVEST}/jdk-include/linux/jawt_md.h"
    "${HARVEST}/sysroot-stubs/libjawt.so"
    "${GENERATED}/net_runelite_rlawt_AWTContext.h")

# JAWT headers transitively need X11/Xlib.h on Linux even for the direct-
# surface variant — required regardless.
list(APPEND _REQUIRED_HARVEST "${HARVEST}/x11-include/X11/Xlib.h")

if(NOT RLAWT_DIRECT_SURFACE)
    list(APPEND _REQUIRED_HARVEST
        "${HARVEST}/gl-include/GL/glx.h"
        "${HARVEST}/sysroot-stubs/libGL.so"
        "${HARVEST}/sysroot-stubs/libGLX.so"
        "${HARVEST}/sysroot-stubs/libX11.so")
else()
    list(APPEND _REQUIRED_HARVEST
        "${RLAWT_ROOT}/rlawt_android.c"
        "${RLAWT_INCLUDE}/rlawt_surface_protocol.h")
endif()

foreach(path ${_REQUIRED_HARVEST})
    if(NOT EXISTS "${path}")
        message(FATAL_ERROR "rlawt-bionic: missing harvest artifact: ${path}")
    endif()
endforeach()

if(RLAWT_DIRECT_SURFACE)
    add_library(rlawt SHARED
        "${RLAWT_ROOT}/rlawt.c"
        "${RLAWT_ROOT}/rlawt_android.c"
        "${RLAWT_ROOT}/rlawt_windows.c")
    target_compile_definitions(rlawt PRIVATE RLAWT_DIRECT_SURFACE)
else()
    add_library(rlawt SHARED
        "${RLAWT_ROOT}/rlawt.c"
        "${RLAWT_ROOT}/rlawt_nix.c"
        "${RLAWT_ROOT}/rlawt_windows.c")
endif()

set_target_properties(rlawt PROPERTIES
    OUTPUT_NAME "rlawt"
    PREFIX "lib"
    SUFFIX ".so")

target_include_directories(rlawt PRIVATE
    "${GENERATED}"
    "${HARVEST}/jdk-include"
    "${HARVEST}/jdk-include/linux"
    "${RLAWT_INCLUDE}"
    "${RLT_TELEMETRY_INCLUDE}"
    # JAWT's jawt_md.h transitively includes <X11/Xlib.h> even on the direct
    # surface path; keep X11 headers visible so jawt parses, but only LINK
    # against X11 in the GLX/X11 variant below.
    "${HARVEST}/x11-include")

if(NOT RLAWT_DIRECT_SURFACE)
    target_include_directories(rlawt PRIVATE "${HARVEST}/gl-include")
endif()

target_compile_options(rlawt PRIVATE
    -Wall
    -fvisibility=hidden)

target_link_options(rlawt PRIVATE
    -Wl,-soname,librlawt.so
    -Wl,--no-undefined)

if(RLAWT_DIRECT_SURFACE)
    # Direct-Android-surface: link against NDK Bionic libs only. EGL/GLES3/
    # nativewindow are part of the NDK sysroot at this API level. AHardwareBuffer
    # symbols are provided by libnativewindow on the target; linking libandroid
    # pulls in libandroid_runtime and a much larger Android framework dependency
    # chain, which is fragile inside the Termux JVM namespace. libjawt.so is
    # still harvested from Termux openjdk-21-x because the NDK sysroot does not
    # ship a JAWT stub.
    target_link_libraries(rlawt PRIVATE
        "${HARVEST}/sysroot-stubs/libjawt.so"
        EGL
        GLESv3
        nativewindow
        log)
else()
    target_link_libraries(rlawt PRIVATE
        "${HARVEST}/sysroot-stubs/libjawt.so"
        "${HARVEST}/sysroot-stubs/libGL.so"
        "${HARVEST}/sysroot-stubs/libGLX.so"
        "${HARVEST}/sysroot-stubs/libX11.so")
endif()
