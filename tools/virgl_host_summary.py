#!/usr/bin/env python3
"""Join canonical VirGL host-completion windows to Mesa guest windows."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable, Sequence

try:
    from .mesa_vtest_summary import parse_record as parse_mesa_record
except ImportError:
    from mesa_vtest_summary import parse_record as parse_mesa_record


PREFIX = "RLT_VIRGL_HOST_V1 "
TOKEN_RE = re.compile(r"([a-z][a-z0-9_]*)=([^\s]+)")
STAGES = (
    "submit_receive",
    "submit_decode",
    "submit_draw",
    "submit_transfer",
    "submit_object",
    "submit_state",
    "submit_query_sync",
    "submit_other",
    "fence_create",
    "transfer_receive",
    "transfer_exec",
    "transfer_prepare",
    "transfer_framebuffer_setup",
    "transfer_read_pixels",
    "transfer_framebuffer_cleanup",
    "transfer_postprocess",
    "busy_receive",
    "fence_wait",
    "poll_fd_wait",
    "renderer_poll",
    "response_write",
    "begin_to_receive",
)
BUSY_SERVICE_STAGES = (
    "transfer_receive",
    "transfer_exec",
    "busy_receive",
    "fence_wait",
    "response_write",
)


def parse_record(message: str) -> dict[str, object] | None:
    marker = message.find(PREFIX)
    if marker < 0:
        return None
    values = dict(TOKEN_RE.findall(message[marker + len(PREFIX) :]))
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
    if values["stage"] != "host_completion" or values["outcome"] != "measured":
        return None
    record: dict[str, object] = {
        "run_id": values["run_id"],
        "first_seq": int(values["first_seq"]),
        "last_seq": int(values["last_seq"]),
        "frame_count": int(values["frame_count"]),
        "attribution_pct": float(values["attribution_pct"]),
        "incomplete_frames": int(values["incomplete_frames"]),
        "unattributed_events": int(values["unattributed_events"]),
        "submit_calls": int(values.get("submit_calls", "0")),
        "submit_bytes": int(values.get("submit_bytes", "0")),
        "queue_depth_mean": float(values.get("queue_depth_mean", "0")),
        "queue_depth_max": int(values.get("queue_depth_max", "0")),
    }
    for stage in STAGES:
        for statistic in ("mean", "p50", "p95", "max"):
            record[f"{stage}_us_{statistic}"] = int(
                values.get(f"{stage}_us_{statistic}", "0")
            )
    return record


def records_from_entries(
    entries: Iterable[object],
    parser,
) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    seen: set[tuple[str, int, int]] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        record = parser(str(entry.get("message") or entry.get("msg") or ""))
        if record is None:
            continue
        key = (
            str(record["run_id"]),
            int(record["first_seq"]),
            int(record["last_seq"]),
        )
        if key not in seen:
            seen.add(key)
            records.append(record)
    return sorted(records, key=lambda item: int(item["first_seq"]))


def analyze_records(
    host_records: Sequence[dict[str, object]],
    mesa_records: Sequence[dict[str, object]],
    run_id: str | None = None,
) -> dict[str, object]:
    if not host_records:
        raise ValueError("no_virgl_host_records")
    selected_run = run_id or str(host_records[-1]["run_id"])
    host = [record for record in host_records if record["run_id"] == selected_run]
    mesa = [record for record in mesa_records if record["run_id"] == selected_run]
    if not host:
        raise ValueError("requested_run_id_not_found")

    all_host = host
    if mesa:
        mesa_first = min(int(record["first_seq"]) for record in mesa)
        mesa_last = max(int(record["last_seq"]) for record in mesa)
        host = [
            record
            for record in host
            if int(record["first_seq"]) >= mesa_first
            and int(record["last_seq"]) <= mesa_last
        ]
    if not host:
        raise ValueError("no_virgl_host_records_in_mesa_scope")

    mesa_by_range = {
        (int(record["first_seq"]), int(record["last_seq"])): record
        for record in mesa
    }
    joined = [
        (record, mesa_by_range[(int(record["first_seq"]), int(record["last_seq"]))])
        for record in host
        if (int(record["first_seq"]), int(record["last_seq"])) in mesa_by_range
    ]
    frame_weight = sum(int(record["frame_count"]) for record in host)
    stages: dict[str, dict[str, object]] = {}
    for stage in STAGES:
        stages[stage] = {
            "weighted_mean_us": round(
                sum(
                    int(record[f"{stage}_us_mean"]) * int(record["frame_count"])
                    for record in host
                )
                / frame_weight,
                3,
            ),
            "max_window_p95_us": max(
                int(record[f"{stage}_us_p95"]) for record in host
            ),
            "max_observed_us": max(
                int(record[f"{stage}_us_max"]) for record in host
            ),
        }

    joined_frames = sum(int(record["frame_count"]) for record, _ in joined)
    guest_completion_mean = (
        sum(
            int(mesa_record["completion_wait_us_mean"])
            * int(host_record["frame_count"])
            for host_record, mesa_record in joined
        )
        / joined_frames
        if joined_frames
        else 0.0
    )
    host_busy_service_mean = sum(
        float(stages[stage]["weighted_mean_us"]) for stage in BUSY_SERVICE_STAGES
    )
    minimum_attribution = min(
        float(record["attribution_pct"]) for record in host
    )
    incomplete = sum(int(record["incomplete_frames"]) for record in host)
    join_coverage = len(joined) / len(mesa) if mesa else 0.0
    gate_met = (
        minimum_attribution >= 99.9
        and incomplete == 0
        and len(joined) == len(host)
        and join_coverage == 1.0
    )
    dominant = max(
        STAGES,
        key=lambda stage: int(stages[stage]["max_window_p95_us"]),
    )
    return {
        "schema_version": 1,
        "run_id": selected_run,
        "window_count": len(host),
        "mesa_window_count": len(mesa),
        "host_windows_outside_mesa_scope": len(all_host) - len(host),
        "joined_window_count": len(joined),
        "join_coverage_pct": round(join_coverage * 100.0, 3),
        "frame_count": frame_weight,
        "first_seq": min(int(record["first_seq"]) for record in host),
        "last_seq": max(int(record["last_seq"]) for record in host),
        "minimum_attribution_pct": minimum_attribution,
        "incomplete_frames": incomplete,
        "unattributed_events": sum(
            int(record["unattributed_events"]) for record in host
        ),
        "assignment_and_join_gate_met": gate_met,
        "stages": stages,
        "dominant_host_stage_by_max_window_p95": dominant,
        "guest_completion_wait_weighted_mean_us": round(
            guest_completion_mean, 3
        ),
        "host_busy_service_weighted_mean_us": round(
            host_busy_service_mean, 3
        ),
        "guest_minus_host_busy_service_mean_us": round(
            guest_completion_mean - host_busy_service_mean, 3
        ),
        "optimization_patch_justified": False,
        "decision": (
            f"measure_summary_overhead_then_target_{dominant}"
            if gate_met
            else "repair_attribution_or_window_join"
        ),
        "limitations": [
            "fence_wait includes poll_fd_wait and renderer_poll; do not add them",
            "transfer_exec contains the five transfer detail stages; do not add it to them",
            "submit_decode contains the six submit detail stages; do not add it to them",
            "guest-minus-host residual includes scheduling and socket transport",
            "transfer detail is collected only in diagnostic capture modes",
        ],
        "records": host,
    }


def render_report(summary: dict[str, object]) -> str:
    stages = summary["stages"]
    assert isinstance(stages, dict)
    rows = "\n".join(
        f"| {stage} | {stages[stage]['weighted_mean_us']} | "
        f"{stages[stage]['max_window_p95_us']} | "
        f"{stages[stage]['max_observed_us']} |"
        for stage in STAGES
    )
    return f"""# VirGL host completion summary

- Run: `{summary['run_id']}`
- Windows / joined / frames: {summary['window_count']} / {summary['joined_window_count']} / {summary['frame_count']}
- Join coverage: {summary['join_coverage_pct']}%
- Minimum attribution: {summary['minimum_attribution_pct']}%
- Assignment and join gate: {summary['assignment_and_join_gate_met']}
- Dominant host stage: `{summary['dominant_host_stage_by_max_window_p95']}`
- Guest completion-wait mean: {summary['guest_completion_wait_weighted_mean_us']} us
- Host busy-service mean: {summary['host_busy_service_weighted_mean_us']} us
- Guest minus host mean residual: {summary['guest_minus_host_busy_service_mean_us']} us
- Decision: `{summary['decision']}`

| Stage | Weighted mean (us) | Max window p95 (us) | Max observed (us) |
|---|---:|---:|---:|
{rows}

`fence_wait` already contains `poll_fd_wait` and `renderer_poll`; those values
must not be added. `transfer_exec` contains the five transfer-detail values and
must not be added to them. `submit_decode` contains the six submit-detail values
and must not be added to them. The residual contains scheduling and socket transport.
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
    entries = [
        entry
        for path in args.agent_logs
        for entry in load_entries(path)
    ]
    summary = analyze_records(
        records_from_entries(entries, parse_record),
        records_from_entries(entries, parse_mesa_record),
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
