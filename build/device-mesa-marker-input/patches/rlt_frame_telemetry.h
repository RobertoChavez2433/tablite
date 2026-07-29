/*
 * Tablite producer-to-Mesa telemetry ABI.
 *
 * This header is the single source of truth shared by the Bionic rlawt
 * producer and the project-owned Mesa Termux package. The launcher creates
 * one private, run-scoped 64-byte file and exports its path through
 * RLT_VTEST_TELEMETRY_PATH. rlawt publishes the active producer sequence
 * immediately around glXSwapBuffers; Mesa only reads it.
 */
#ifndef RLT_FRAME_TELEMETRY_H
#define RLT_FRAME_TELEMETRY_H

#include <stdatomic.h>
#include <stdint.h>

#define RLT_FRAME_TELEMETRY_MAGIC 0x46544c52U /* "RLTF" on little endian. */
#define RLT_FRAME_TELEMETRY_ABI_VERSION 1U
#define RLT_FRAME_TELEMETRY_SIZE 64U

struct rlt_frame_telemetry_v1 {
   uint32_t magic;
   uint16_t abi_version;
   uint16_t struct_size;
   _Atomic uint64_t generation;
   _Atomic uint64_t active_producer_seq;
   _Atomic uint64_t active_begin_monotonic_ns;
   uint64_t reserved[4];
};

_Static_assert(sizeof(struct rlt_frame_telemetry_v1) == RLT_FRAME_TELEMETRY_SIZE,
               "rlt frame telemetry ABI must remain exactly 64 bytes");

#endif
