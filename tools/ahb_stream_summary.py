#!/usr/bin/env python3
"""Analyze canonical same-UID AHardwareBuffer handoff records."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable, Sequence


CPU_PREFIX = "RLT_AHB_STREAM_V1 "
GPU_PREFIX = "RLT_AHB_GPU_V1 "
TOKEN_RE = re.compile(r"([a-z][a-z0-9_]*)=([^\s]+)")
CPU_STAGES = (
    "acquire_wait",
    "lock",
    "copy",
    "unlock",
    "fence_wait",
    "ready_send",
)
GPU_STAGES = (
    "acquire_wait",
    "import",
    "blit",
    "finish",
    "ready_send",
)


def _values(message: str) -> dict[str, str] | None:
    for prefix, path in ((CPU_PREFIX, "cpu_copy"), (GPU_PREFIX, "gpu_blit")):
        marker = message.find(prefix)
        if marker >= 0:
            values = dict(
                TOKEN_RE.findall(message[marker + len(prefix) :])
            )
            values["_path"] = path
            return values
    return None


def parse_presented(message: str) -> dict[str, object] | None:
    values = _values(message)
    if values is None:
        return None
    path = values["_path"]
    stages = GPU_STAGES if path == "gpu_blit" else CPU_STAGES
    required = {
        "run_id",
        "frame_seq",
        "stage",
        "outcome",
        "width",
        "height",
        "frames",
        "failures",
    }
    if path == "cpu_copy":
        required.add("total_bytes")
    required.update(f"total_{stage}_ns" for stage in stages)
    if (
        not required.issubset(values)
        or values["stage"] != "host_handoff"
        or values["outcome"] != "presented"
    ):
        return None
    record: dict[str, object] = {
        "run_id": values["run_id"],
        "path": path,
        "frame_seq": int(values["frame_seq"]),
        "width": int(values["width"]),
        "height": int(values["height"]),
        "frames": int(values["frames"]),
        "total_bytes": int(values.get("total_bytes", "0")),
        "failures": int(values["failures"]),
    }
    for optional in ("pool_frames", "pool_elapsed_ns", "pool_rate_millihz"):
        if optional in values:
            record[optional] = int(values[optional])
    for stage in stages:
        record[f"total_{stage}_ns"] = int(values[f"total_{stage}_ns"])
    return record


def parse_probe(message: str) -> dict[str, object] | None:
    values = _values(message)
    required = {
        "run_id",
        "stage",
        "outcome",
        "width",
        "height",
        "frames",
        "duration_ns",
        "rate_millihz",
        "failures",
    }
    if (
        values is None
        or not required.issubset(values)
        or values["stage"] != "startup_probe"
        or values["outcome"] != "complete"
    ):
        return None
    return {
        "run_id": values["run_id"],
        "width": int(values["width"]),
        "height": int(values["height"]),
        "frames": int(values["frames"]),
        "duration_ns": int(values["duration_ns"]),
        "rate_millihz": int(values["rate_millihz"]),
        "failures": int(values["failures"]),
    }


def records_from_entries(
    entries: Iterable[object],
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    presented: dict[tuple[str, str, int], dict[str, object]] = {}
    probes: dict[str, dict[str, object]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        message = str(entry.get("message") or entry.get("msg") or "")
        frame = parse_presented(message)
        if frame is not None:
            presented[
                (
                    str(frame["run_id"]),
                    str(frame["path"]),
                    int(frame["frames"]),
                )
            ] = frame
        probe = parse_probe(message)
        if probe is not None:
            probes[str(probe["run_id"])] = probe
    return (
        sorted(presented.values(), key=lambda item: int(item["frames"])),
        list(probes.values()),
    )


def analyze_records(
    presented: Sequence[dict[str, object]],
    probes: Sequence[dict[str, object]],
    run_id: str | None = None,
) -> dict[str, object]:
    if not presented:
        raise ValueError("no_ahb_presented_records")
    selected_run = run_id or str(presented[-1]["run_id"])
    run_frames = [
        item for item in presented if item["run_id"] == selected_run
    ]
    if not run_frames:
        raise ValueError("requested_run_id_not_found")
    latest = max(run_frames, key=lambda item: int(item["frame_seq"]))
    selected_path = str(latest["path"])
    frames = [
        item for item in run_frames if item["path"] == selected_path
    ]
    if not frames:
        raise ValueError("requested_run_id_not_found")
    final = max(frames, key=lambda item: int(item["frames"]))
    probe = next(
        (item for item in probes if item["run_id"] == selected_run),
        None,
    )
    frame_count = int(final["frames"])
    stages = GPU_STAGES if selected_path == "gpu_blit" else CPU_STAGES
    stage_means_us = {
        stage: round(
            int(final[f"total_{stage}_ns"]) / frame_count / 1000.0,
            3,
        )
        for stage in stages
    }
    active_stages = tuple(
        stage for stage in stages if stage != "acquire_wait"
    )
    active_work_us = round(sum(stage_means_us[s] for s in active_stages), 3)
    if "pool_rate_millihz" in final:
        rate_hz = round(int(final["pool_rate_millihz"]) / 1000.0, 3)
        rate_source = "production_pool"
    elif probe is not None:
        rate_hz = round(int(probe["rate_millihz"]) / 1000.0, 3)
        rate_source = "startup_probe"
    else:
        rate_hz = None
        rate_source = None
    failures = max(
        int(final["failures"]),
        int(probe["failures"]) if probe is not None else 0,
    )
    target_gate_met = rate_hz is not None and rate_hz >= 90.0 and failures == 0
    dominant_stage = max(stages, key=stage_means_us.__getitem__)
    dominant_active_stage = max(active_stages, key=stage_means_us.__getitem__)
    copy_ns = int(final.get("total_copy_ns", 0))
    if failures:
        decision = "repair_transport_failures_before_optimization"
    elif rate_hz is None:
        decision = "join_with_producer_rate_before_optimization"
    elif target_gate_met:
        decision = "integrate_and_measure_logged_in_game_loop"
    elif active_work_us >= (1_000_000.0 / 120.0):
        decision = f"optimize_{dominant_active_stage}_handoff"
    else:
        decision = "optimize_upstream_producer_before_handoff"
    return {
        "schema_version": 1,
        "run_id": selected_run,
        "path": selected_path,
        "width": int(final["width"]),
        "height": int(final["height"]),
        "frame_count": frame_count,
        "total_bytes": int(final["total_bytes"]),
        "failures": failures,
        "rate_hz": rate_hz,
        "rate_source": rate_source,
        "target_90_fps_gate_met": target_gate_met,
        "stage_means_us": stage_means_us,
        "active_handoff_work_us": active_work_us,
        "dominant_stage": dominant_stage,
        "dominant_active_stage": dominant_active_stage,
        "copy_throughput_gb_s": round(
            int(final["total_bytes"]) / copy_ns if copy_ns else 0.0,
            3,
        ) if selected_path == "cpu_copy" else None,
        "decision": decision,
        "limitations": [
            "startup_probe measures transport and presentation capacity, not game rendering",
            "acquire_wait includes consumer release and display pacing",
            "stage means use cumulative monotonic counters and exclude startup pool setup",
            "production pool rate resets when the AHardwareBuffer pool reconnects or resizes",
            "GPU finish is an explicit correctness wait until the ABI carries a native fence fd",
        ],
        "presented_records": frames,
        "probe": probe,
    }


def render_report(summary: dict[str, object]) -> str:
    means = summary["stage_means_us"]
    assert isinstance(means, dict)
    rows = "\n".join(
        f"| {stage} | {means[stage]} |" for stage in means
    )
    return f"""# AHardwareBuffer frame-handoff summary

- Run: `{summary['run_id']}`
- Path: `{summary['path']}`
- Size / frames: {summary['width']}×{summary['height']} / {summary['frame_count']}
- Sustained rate: {summary['rate_hz']} FPS
- Rate source: `{summary['rate_source']}`
- 90 FPS transport gate: {summary['target_90_fps_gate_met']}
- Active handoff work: {summary['active_handoff_work_us']} us/frame
- Copy throughput: {summary['copy_throughput_gb_s']} GB/s
- Dominant stage: `{summary['dominant_stage']}`
- Dominant active stage: `{summary['dominant_active_stage']}`
- Failures: {summary['failures']}
- Decision: `{summary['decision']}`

| Stage | Cumulative mean (us/frame) |
|---|---:|
{rows}

`acquire_wait` is pool release and display pacing, not buffer-lock cost. The
startup probe validates transport capacity; the next acceptance gate is a
matched logged-in game-loop capture with the same run identity.
"""


def load_entries(path: Path) -> list[object]:
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return [{"message": line} for line in text.splitlines() if line.strip()]
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("entries", "logs", "items"):
            value = payload.get(key)
            if isinstance(value, list):
                return value
    raise ValueError("unsupported_agent_log_shape")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, nargs="+", required=True)
    parser.add_argument("--run-id")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    presented, probes = records_from_entries(
        entry
        for path in args.logs
        for entry in load_entries(path)
    )
    summary = analyze_records(presented, probes, run_id=args.run_id)
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    args.report.write_text(render_report(summary), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
