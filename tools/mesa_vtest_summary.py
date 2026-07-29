#!/usr/bin/env python3
"""Analyze canonical Mesa virpipe/vtest frame-stage summary records."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable, Sequence


PREFIX = "RLT_MESA_VTEST_V1 "
STAGES = (
    "submit",
    "transfer_get",
    "completion_wait",
    "copy",
    "display",
    "fence_wait",
)
TOKEN_RE = re.compile(r"([a-z][a-z0-9_]*)=([^\s]+)")


def parse_record(message: str) -> dict[str, object] | None:
    marker = message.find(PREFIX)
    if marker < 0:
        return None
    payload = message[marker + len(PREFIX) :]
    values = dict(TOKEN_RE.findall(payload))
    required = {
        "run_id",
        "stage",
        "outcome",
        "first_seq",
        "last_seq",
        "frame_count",
        "attribution_pct",
        "incomplete_frames",
        "unattributed_events",
    }
    required.update(f"{stage}_us_p95" for stage in STAGES)
    if not required.issubset(values):
        return None
    if values["stage"] != "mesa_vtest" or values["outcome"] != "measured":
        return None

    record: dict[str, object] = {
        "run_id": values["run_id"],
        "display_policy": values.get("display_policy", "unreported"),
        "transfer_policy": values.get("transfer_policy", "unreported"),
        "precopy_fence_policy": values.get(
            "precopy_fence_policy", "unreported"
        ),
        "first_seq": int(values["first_seq"]),
        "last_seq": int(values["last_seq"]),
        "frame_count": int(values["frame_count"]),
        "attribution_pct": float(values["attribution_pct"]),
        "incomplete_frames": int(values["incomplete_frames"]),
        "unattributed_events": int(values["unattributed_events"]),
    }
    for stage in STAGES:
        for statistic in ("mean", "p50", "p95", "max"):
            key = f"{stage}_us_{statistic}"
            record[key] = int(values.get(key, "0"))
        record[f"{stage}_calls"] = int(values.get(f"{stage}_calls", "0"))
    record["submit_bytes"] = int(values.get("submit_bytes", "0"))
    record["copy_bytes"] = int(values.get("copy_bytes", "0"))
    return record


def records_from_entries(entries: Iterable[object]) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    seen: set[tuple[str, int, int]] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        message = str(entry.get("message") or entry.get("msg") or "")
        record = parse_record(message)
        if record is None:
            continue
        key = (
            str(record["run_id"]),
            int(record["first_seq"]),
            int(record["last_seq"]),
        )
        if key in seen:
            continue
        seen.add(key)
        records.append(record)
    return sorted(records, key=lambda item: int(item["first_seq"]))


def analyze_records(
    records: Sequence[dict[str, object]],
    run_id: str | None = None,
) -> dict[str, object]:
    if not records:
        raise ValueError("no_mesa_vtest_records")
    selected_run = run_id or str(records[-1]["run_id"])
    selected = [record for record in records if record["run_id"] == selected_run]
    if not selected:
        raise ValueError("requested_run_id_not_found")

    stage_summary: dict[str, dict[str, object]] = {}
    for stage in STAGES:
        frame_weight = sum(int(record["frame_count"]) for record in selected)
        weighted_mean = (
            sum(
                int(record[f"{stage}_us_mean"]) * int(record["frame_count"])
                for record in selected
            )
            / frame_weight
            if frame_weight
            else 0.0
        )
        stage_summary[stage] = {
            "weighted_mean_us": round(weighted_mean, 3),
            "max_window_p95_us": max(
                int(record[f"{stage}_us_p95"]) for record in selected
            ),
            "max_observed_us": max(
                int(record[f"{stage}_us_max"]) for record in selected
            ),
            "calls": sum(int(record[f"{stage}_calls"]) for record in selected),
        }

    dominant_stage = max(
        STAGES,
        key=lambda stage: int(stage_summary[stage]["max_window_p95_us"]),
    )
    minimum_attribution = min(
        float(record["attribution_pct"]) for record in selected
    )
    incomplete_frames = sum(
        int(record["incomplete_frames"]) for record in selected
    )
    assignment_gate_met = minimum_attribution >= 99.9 and incomplete_frames == 0
    host_boundary_unresolved = dominant_stage == "completion_wait"
    precopy_fence_policies = sorted(
        {str(record["precopy_fence_policy"]) for record in selected}
    )
    return {
        "schema_version": 1,
        "run_id": selected_run,
        "window_count": len(selected),
        "frame_count": sum(int(record["frame_count"]) for record in selected),
        "first_seq": min(int(record["first_seq"]) for record in selected),
        "last_seq": max(int(record["last_seq"]) for record in selected),
        "minimum_attribution_pct": minimum_attribution,
        "incomplete_frames": incomplete_frames,
        "unattributed_events": sum(
            int(record["unattributed_events"]) for record in selected
        ),
        "per_frame_assignment_gate_met": assignment_gate_met,
        "display_policies": sorted(
            {str(record["display_policy"]) for record in selected}
        ),
        "transfer_policies": sorted(
            {str(record["transfer_policy"]) for record in selected}
        ),
        "precopy_fence_policies": precopy_fence_policies,
        "precopy_fence_delegated": (
            precopy_fence_policies == ["delegated_to_transfer_completion"]
        ),
        "stages": stage_summary,
        "dominant_stage_by_max_window_p95": dominant_stage,
        "host_gpu_interval_remains_inside_completion_wait": host_boundary_unresolved,
        "virgl_instrumentation_justified": (
            assignment_gate_met and host_boundary_unresolved
        ),
        "virgl_optimization_patch_justified": False,
        "decision": (
            "instrument_host_virgl_completion_boundary_next"
            if assignment_gate_met and host_boundary_unresolved
            else "continue_without_virgl_source_change"
        ),
        "limitations": [
            "completion_wait includes host GPU execution, server scheduling, and response transport",
            "p95 values are exact per-frame percentiles within each 30-frame window",
            "max_window_p95 values select a seam but are not an end-to-end percentile",
            "this evidence can justify host instrumentation, not an optimization patch by itself",
        ],
        "records": selected,
    }


def render_report(summary: dict[str, object]) -> str:
    stages = summary["stages"]
    assert isinstance(stages, dict)
    rows = "\n".join(
        f"| {stage} | {stages[stage]['weighted_mean_us']} | "
        f"{stages[stage]['max_window_p95_us']} | "
        f"{stages[stage]['max_observed_us']} | {stages[stage]['calls']} |"
        for stage in STAGES
    )
    return f"""# Mesa virpipe/vtest frame-stage summary

- Run: `{summary['run_id']}`
- Windows / frames: {summary['window_count']} / {summary['frame_count']}
- Producer sequence range: {summary['first_seq']}–{summary['last_seq']}
- Minimum attribution: {summary['minimum_attribution_pct']}%
- Per-frame 99.9% assignment gate: {summary['per_frame_assignment_gate_met']}
- Pre-copy fence policies: `{summary['precopy_fence_policies']}`
- Pre-copy fence delegated: {summary['precopy_fence_delegated']}
- Dominant stage by worst window p95: `{summary['dominant_stage_by_max_window_p95']}`
- Decision: `{summary['decision']}`
- VirGL instrumentation justified: {summary['virgl_instrumentation_justified']}
- VirGL optimization patch justified: {summary['virgl_optimization_patch_justified']}

| Stage | Weighted mean (us) | Max window p95 (us) | Max observed (us) | Calls |
|---|---:|---:|---:|---:|
{rows}

`completion_wait` still combines host GPU execution, VirGL server scheduling,
and response transport. If it dominates after the assignment gate passes, the
next evidence patch belongs at the host VirGL completion boundary. No result
from this report alone justifies an optimization patch.
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
    parser.add_argument("--agent-logs", type=Path, nargs="+", required=True)
    parser.add_argument("--run-id")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    summary = analyze_records(
        records_from_entries(
            entry
            for path in args.agent_logs
            for entry in load_entries(path)
        ),
        run_id=args.run_id,
    )
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    args.report.write_text(render_report(summary), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
