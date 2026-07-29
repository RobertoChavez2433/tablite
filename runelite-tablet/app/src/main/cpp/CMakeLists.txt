cmake_minimum_required(VERSION 3.22)
project(runelite_tablet_app C CXX)

# AGP supports a single CMake path per android block. This wrapper:
#   1. Pulls in the vendored termux-x11 native build (Xlorie + family).
#   2. Adds librlawtsurface — the consumer for the rlawt direct-Android-surface
#      path. Lives here, not in third_party/, because it consumes a project-
#      level wire protocol header (third_party/rlawt-bionic/include/) and
#      ships in the app APK.

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")
get_filename_component(REPO_ROOT "${REPO_ROOT}" ABSOLUTE)

# Vendored termux-x11 source tree. The upstream CMakeLists targets and link
# rules are unchanged from the rebase in 58fa88b.
add_subdirectory(
    "${REPO_ROOT}/third_party/termux-x11-upstream/app/src/main/cpp"
    "${CMAKE_BINARY_DIR}/termux-x11-build")

# librlawtsurface — the app-side consumer for the rlawt-on-Surface protocol.
add_library(rlawtsurface SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/rlawtsurface/rlawt_surface_consumer.c")

target_include_directories(rlawtsurface PRIVATE
    "${REPO_ROOT}/third_party/rlawt-bionic/include"
    "${REPO_ROOT}/third_party/virglrenderer-1.3.0/vtest")

target_compile_options(rlawtsurface PRIVATE
    -Wall
    -fvisibility=hidden)

target_link_libraries(rlawtsurface PRIVATE
    EGL
    GLESv3
    nativewindow
    android
    log)
