#!/usr/bin/env python3
"""Build one run-scoped telemetry-on-telemetry cost waterfall.

The report combines app-side sampled stage costs with native, explicitly gated
BURST/DEEP self-profile records. Wall time is deliberately not presented as a
critical-path sum because stages can overlap on different threads/processes.
Thread CPU is additive and is the primary internal attribution metric; the
external FrameTimeline report remains the authority for total observer effect.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


RECORD_PREFIX = "RLT_TELEMETRY_COST_V1 "
INTEGER_FIELDS = {
    "calls",
    "measured_samples",
    "measured_wall_ns",
    "measured_thread_cpu_ns",
    "bytes",
    "dropped",
    "queue_high_water_mark",
    "incomplete_frames",
    "unattributed_events",
}


def parse_native_record(line: str) -> dict[str, Any] | None:
    marker = line.find(RECORD_PREFIX)
    if marker < 0:
        return None
    fields: dict[str, Any] = {}
    for token in line[marker + len(RECORD_PREFIX) :].strip().split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = int(value) if key in INTEGER_FIELDS and value.isdigit() else value
    required = {
        "run_id",
        "mode",
        "stage",
        "outcome",
        "calls",
        "measured_samples",
        "measured_wall_ns",
        "measured_thread_cpu_ns",
        "bytes",
        "dropped",
        "queue_high_water_mark",
    }
    return fields if required.issubset(fields) else None


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _find_first(root: Path, name: str) -> Path | None:
    matches = sorted(root.rglob(name), key=lambda path: (len(path.parts), str(path)))
    return matches[0] if matches else None


def _native_log_paths(root: Path) -> list[Path]:
    preferred = [
        root / "runelite-native-capture-window.log",
        root / "virgl-server-capture-window.log",
    ]
    paths = [path for path in preferred if path.exists()]
    for path in sorted(root.rglob("*.log")):
        if "app" in path.relative_to(root).parts or path in paths:
            continue
        if path.name == "runelite-native.log" and (
            root / "runelite-native-capture-window.log"
        ).exists():
            continue
        if path.name == "virgl-server.log" and (
            root / "virgl-server-capture-window.log"
        ).exists():
            continue
        if path.name == "runelite-launch.log":
            continue
        paths.append(path)
    return paths


def _native_records(paths: Iterable[Path]) -> tuple[list[dict[str, Any]], list[str]]:
    records: list[dict[str, Any]] = []
    sources: list[str] = []
    seen: set[str] = set()
    for path in paths:
        found = False
        for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
            record = parse_native_record(line)
            if record is None:
                continue
            # App/host bundles can contain the same forwarded native line more
            # than once. Exact-line de-duplication retains distinct windows.
            canonical = json.dumps(record, sort_keys=True)
            if canonical in seen:
                continue
            seen.add(canonical)
            records.append(record)
            found = True
        if found:
            sources.append(str(path))
    return records, sources


def _app_stages(cost: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for stage in cost.get("stages", []):
        result.append(
            {
                "source": "android_app",
                "stage": stage["stage"],
                "calls": int(stage.get("calls", 0)),
                "measured_samples": int(stage.get("measuredSamples", 0)),
                "measured_wall_ns": int(stage.get("measuredWallNs", 0)),
                "measured_thread_cpu_ns": int(stage.get("measuredThreadCpuNs", 0)),
                "estimated_wall_ns": int(stage.get("estimatedWallNs", 0)),
                "estimated_thread_cpu_ns": int(stage.get("estimatedThreadCpuNs", 0)),
                "bytes": int(stage.get("bytes", 0)),
                "dropped": int(stage.get("dropped", 0)),
                "queue_high_water_mark": int(stage.get("queueHighWaterMark", 0)),
                "measurement_stride": int(cost.get("measurementStride", 1)),
            }
        )
    return result


def _aggregate_native(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = defaultdict(
        lambda: {
            "source": "native",
            "calls": 0,
            "measured_samples": 0,
            "measured_wall_ns": 0,
            "measured_thread_cpu_ns": 0,
            "estimated_wall_ns": 0,
            "estimated_thread_cpu_ns": 0,
            "bytes": 0,
            "dropped": 0,
            "queue_high_water_mark": 0,
            "record_count": 0,
            "incomplete_frames": 0,
            "unattributed_events": 0,
            "limitations": set(),
        }
    )
    for record in records:
        target = grouped[str(record["stage"])]
        target["stage"] = record["stage"]
        target["record_count"] += 1
        for field in (
            "calls",
            "measured_samples",
            "measured_wall_ns",
            "measured_thread_cpu_ns",
            "bytes",
            "dropped",
            "incomplete_frames",
            "unattributed_events",
        ):
            target[field] += int(record.get(field, 0))
        target["estimated_wall_ns"] = target["measured_wall_ns"]
        target["estimated_thread_cpu_ns"] = target["measured_thread_cpu_ns"]
        target["queue_high_water_mark"] = max(
            target["queue_high_water_mark"],
            int(record.get("queue_high_water_mark", 0)),
        )
        if record.get("record_emit_excluded") == "1":
            target["limitations"].add("record_emit_excluded")
        if record.get("dropped_status") == "unobservable":
            target["limitations"].add("drops_unobservable")
        if record.get("queue_status") == "unobservable":
            target["limitations"].add("queue_unobservable")
    output = []
    for stage in sorted(grouped):
        row = grouped[stage]
        row["limitations"] = sorted(row["limitations"])
        output.append(row)
    return output


def build_report(capture_dir: Path) -> dict[str, Any]:
    manifest_path = capture_dir / "manifest.json"
    manifest = _load_json(manifest_path) if manifest_path.exists() else {}
    app_cost_path = _find_first(capture_dir / "app", "app-telemetry-cost-v1.json")
    app_cost = _load_json(app_cost_path) if app_cost_path else {}
    native, native_sources = _native_records(_native_log_paths(capture_dir))
    run_id = str(
        manifest.get("run_id")
        or app_cost.get("runId")
        or (native[0].get("run_id") if native else "unknown")
    )
    mode = str(
        manifest.get("telemetry_mode")
        or app_cost.get("mode")
        or (native[0].get("mode") if native else "unknown")
    )

    stages = _app_stages(app_cost) + _aggregate_native(native)
    mismatched_run_ids = sorted(
        {
            str(value)
            for value in [app_cost.get("runId"), *(row.get("run_id") for row in native)]
            if value and str(value) != run_id
        }
    )
    external_path = capture_dir / "frame-timeline-summary.json"
    external = _load_json(external_path) if external_path.exists() else None
    issues: list[str] = []
    if mismatched_run_ids:
        issues.append("run_id_mismatch")
    if mode in {"BURST", "DEEP"} and not native:
        issues.append("native_self_cost_missing")
    if not app_cost_path:
        issues.append("app_self_cost_missing")
    if sum(int(stage["dropped"]) for stage in stages):
        issues.append("telemetry_drops_observed")

    return {
        "schema_version": 1,
        "run_id": run_id,
        "mode": mode,
        "interpretation": {
            "estimated_thread_cpu_ns": (
                "Additive sampled/expanded CPU attribution across telemetry stages."
            ),
            "estimated_wall_ns": (
                "Per-stage elapsed attribution; stages may overlap and MUST NOT be "
                "treated as a critical-path sum."
            ),
            "external_frame_timeline": (
                "Independent authority for total observer effect and frame impact."
            ),
        },
        "sources": {
            "manifest": str(manifest_path) if manifest_path.exists() else None,
            "app_cost": str(app_cost_path) if app_cost_path else None,
            "native_logs": native_sources,
            "external_frame_timeline": str(external_path) if external_path.exists() else None,
        },
        "coverage": {
            "app_stage_count": len(_app_stages(app_cost)),
            "native_stage_count": len(_aggregate_native(native)),
            "native_record_count": len(native),
            "mismatched_run_ids": mismatched_run_ids,
        },
        "totals": {
            "estimated_thread_cpu_ns": sum(
                int(stage["estimated_thread_cpu_ns"]) for stage in stages
            ),
            "stage_wall_ns_noncritical_path_sum": sum(
                int(stage["estimated_wall_ns"]) for stage in stages
            ),
            "bytes": sum(int(stage["bytes"]) for stage in stages),
            "dropped": sum(int(stage["dropped"]) for stage in stages),
            "incomplete_frames": sum(
                int(stage.get("incomplete_frames", 0)) for stage in stages
            ),
            "unattributed_events": sum(
                int(stage.get("unattributed_events", 0)) for stage in stages
            ),
        },
        "stages": sorted(
            stages,
            key=lambda row: int(row["estimated_thread_cpu_ns"]),
            reverse=True,
        ),
        "external_frame_timeline": external,
        "issues": issues,
        "valid": not issues,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture_directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = build_report(args.capture_directory.resolve())
    output = args.output or args.capture_directory / "telemetry-cost-summary.json"
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        f"telemetry self-cost: stages={len(report['stages'])} "
        f"cpu_ns={report['totals']['estimated_thread_cpu_ns']} "
        f"issues={','.join(report['issues']) or 'none'}"
    )
    return 0 if report["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
