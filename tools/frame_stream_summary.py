#!/usr/bin/env python3
"""Summarize and compare completed-frame loopback telemetry records."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable, Sequence


TOKEN_RE = re.compile(r"([a-z][a-z0-9_]*)=([^\s|]+)")
FAMILIES = (
    "RLT_FRAME_RATE_V1",
    "RLT_MESA_VTEST_V1",
    "RLT_VIRGL_HOST_V1",
    "RLT_FRAME_STREAM_V1",
    "RLT_AHB_STREAM_V1",
    "RLT_AHB_GPU_V1",
    "RLT_DIRECT_TRANSFER_V1",
)


def parse_record(message: str) -> dict[str, str] | None:
    family = next((item for item in FAMILIES if item in message), None)
    if family is None:
        return None
    values = dict(TOKEN_RE.findall(message[message.find(family) :]))
    if "run_id" not in values:
        return None
    values["family"] = family
    return values


def records_from_lines(lines: Iterable[str]) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    seen: set[tuple[str, ...]] = set()
    for line in lines:
        record = parse_record(line)
        if record is None:
            continue
        key = (
            record["family"],
            record["run_id"],
            record.get("stage", ""),
            record.get("outcome", ""),
            record.get("first_seq", ""),
            record.get("last_seq", ""),
            record.get("frame_seq", ""),
        )
        if key in seen:
            continue
        seen.add(key)
        records.append(record)
    return records


def load_records(paths: Sequence[Path]) -> list[dict[str, str]]:
    return records_from_lines(
        line
        for path in paths
        for line in path.read_text(
            encoding="utf-8-sig", errors="replace"
        ).splitlines()
    )


def _weighted_mean(
    records: Sequence[dict[str, str]], field: str, weight: str = "frame_count"
) -> float:
    total_weight = sum(int(record[weight]) for record in records)
    if total_weight == 0:
        raise ValueError(f"zero_weight_for_{field}")
    return (
        sum(float(record[field]) * int(record[weight]) for record in records)
        / total_weight
    )


def _cumulative_mean_ns(
    records: Sequence[dict[str, str]],
    *,
    minimum_seq: int,
    maximum_seq: int,
    count_field: str,
    total_field: str,
) -> float:
    ordered = sorted(records, key=lambda record: int(record["frame_seq"]))
    baseline = [
        record for record in ordered if int(record["frame_seq"]) <= minimum_seq
    ]
    bounded = [
        record for record in ordered if int(record["frame_seq"]) <= maximum_seq
    ]
    if not baseline or not bounded:
        raise ValueError(f"missing_cumulative_boundary_for_{total_field}")
    first = baseline[-1]
    last = bounded[-1]
    count = int(last[count_field]) - int(first[count_field])
    if count <= 0:
        raise ValueError(f"empty_cumulative_window_for_{total_field}")
    return (int(last[total_field]) - int(first[total_field])) / count


def summarize_run(
    records: Sequence[dict[str, str]],
    *,
    run_id: str,
    minimum_seq: int,
    maximum_seq: int,
) -> dict[str, object]:
    selected = [record for record in records if record["run_id"] == run_id]
    if not selected:
        raise ValueError("requested_run_id_not_found")

    producer = [
        record
        for record in selected
        if record["family"] == "RLT_FRAME_RATE_V1"
        and record.get("stage") == "producer"
        and int(record["first_seq"]) >= minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    mesa = [
        record
        for record in selected
        if record["family"] == "RLT_MESA_VTEST_V1"
        and int(record["first_seq"]) > minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    virgl = [
        record
        for record in selected
        if record["family"] == "RLT_VIRGL_HOST_V1"
        and int(record["first_seq"]) > minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    stream = [
        record
        for record in selected
        if record["family"] == "RLT_FRAME_STREAM_V1"
    ]
    app = [
        record
        for record in stream
        if record.get("stage") == "app_ingress"
        and record.get("outcome") == "presented"
        and minimum_seq < int(record["frame_seq"]) <= maximum_seq
    ]
    sent = [
        record
        for record in stream
        if record.get("stage") == "host_send"
        and record.get("outcome") == "sent"
    ]
    queued = [
        record
        for record in stream
        if record.get("stage") == "host_enqueue"
        and record.get("outcome") == "queued"
    ]
    if not all((producer, mesa, virgl, app, sent, queued)):
        raise ValueError("incomplete_frame_stream_evidence")

    producer_intervals = sum(int(record["interval_count"]) for record in producer)
    producer_elapsed_ns = sum(int(record["elapsed_ns"]) for record in producer)
    mesa_ranges = {
        (int(record["first_seq"]), int(record["last_seq"])) for record in mesa
    }
    virgl_ranges = {
        (int(record["first_seq"]), int(record["last_seq"])) for record in virgl
    }
    final_sent = max(
        (record for record in sent if int(record["frame_seq"]) <= maximum_seq),
        key=lambda record: int(record["frame_seq"]),
    )
    final_app = max(app, key=lambda record: int(record["frame_seq"]))
    display_policies = sorted(
        {record.get("display_policy", "unreported") for record in mesa}
    )
    attribution_values = [
        float(record["attribution_pct"]) for record in (*mesa, *virgl)
    ]
    incomplete_frames = sum(
        int(record["incomplete_frames"]) for record in (*mesa, *virgl)
    )

    summary: dict[str, object] = {
        "schema_version": 1,
        "run_id": run_id,
        "minimum_seq_exclusive_for_frame_windows": minimum_seq,
        "maximum_seq_inclusive": maximum_seq,
        "producer_windows": len(producer),
        "producer_fps": round(
            producer_intervals * 1_000_000_000.0 / producer_elapsed_ns, 3
        ),
        "mesa_windows": len(mesa),
        "virgl_windows": len(virgl),
        "mesa_virgl_join_complete": mesa_ranges == virgl_ranges,
        "minimum_attribution_pct": min(attribution_values),
        "incomplete_frames": incomplete_frames,
        "mesa_completion_mean_ms": round(
            _weighted_mean(mesa, "completion_wait_us_mean") / 1000.0, 3
        ),
        "mesa_copy_mean_ms": round(
            _weighted_mean(mesa, "copy_us_mean") / 1000.0, 3
        ),
        "mesa_display_mean_ms": round(
            _weighted_mean(mesa, "display_us_mean") / 1000.0, 3
        ),
        "mesa_display_calls": sum(int(record["display_calls"]) for record in mesa),
        "mesa_display_policies": display_policies,
        "virgl_transfer_mean_ms": round(
            _weighted_mean(virgl, "transfer_exec_us_mean") / 1000.0, 3
        ),
        "host_copy_mean_ms": round(
            _cumulative_mean_ns(
                queued,
                minimum_seq=minimum_seq,
                maximum_seq=maximum_seq,
                count_field="frames_queued",
                total_field="total_copy_ns",
            )
            / 1_000_000.0,
            3,
        ),
        "host_send_mean_ms": round(
            _cumulative_mean_ns(
                sent,
                minimum_seq=minimum_seq,
                maximum_seq=maximum_seq,
                count_field="frames_sent",
                total_field="total_send_ns",
            )
            / 1_000_000.0,
            3,
        ),
        "app_sample_count": len(app),
        "app_ingress_mean_ms": round(
            sum(int(record["ingress_to_present_ns"]) for record in app)
            / len(app)
            / 1_000_000.0,
            3,
        ),
        "app_payload_receive_mean_ms": round(
            sum(int(record["payload_receive_ns"]) for record in app)
            / len(app)
            / 1_000_000.0,
            3,
        ),
        "frames_superseded": int(final_sent["frames_superseded"]),
        "send_failures": int(final_sent["send_failures"]),
        "sequence_gaps": int(final_app["sequence_gaps"]),
        "invalid_frames": int(final_app["invalid_frames"]),
    }
    recv_call_samples = [
        int(record["payload_recv_calls"])
        for record in app
        if "payload_recv_calls" in record
    ]
    if recv_call_samples:
        summary["app_payload_recv_calls_sample_count"] = len(recv_call_samples)
        summary["app_payload_recv_calls_mean"] = round(
            sum(recv_call_samples) / len(recv_call_samples), 3
        )
        summary["app_payload_layouts"] = sorted(
            {
                record.get("payload_layout", "unreported")
                for record in app
                if "payload_recv_calls" in record
            }
        )
    summary["evidence_gate_met"] = all(
        (
            summary["mesa_virgl_join_complete"],
            summary["minimum_attribution_pct"] >= 99.9,
            summary["incomplete_frames"] == 0,
            summary["frames_superseded"] == 0,
            summary["send_failures"] == 0,
            summary["sequence_gaps"] == 0,
            summary["invalid_frames"] == 0,
        )
    )
    return summary


def summarize_ahb_run(
    records: Sequence[dict[str, str]],
    *,
    run_id: str,
    minimum_seq: int,
    maximum_seq: int,
) -> dict[str, object]:
    selected = [record for record in records if record["run_id"] == run_id]
    if not selected:
        raise ValueError("requested_run_id_not_found")
    producer = [
        record
        for record in selected
        if record["family"] == "RLT_FRAME_RATE_V1"
        and record.get("stage") == "producer"
        and int(record["first_seq"]) >= minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    mesa = [
        record
        for record in selected
        if record["family"] == "RLT_MESA_VTEST_V1"
        and int(record["first_seq"]) > minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    virgl = [
        record
        for record in selected
        if record["family"] == "RLT_VIRGL_HOST_V1"
        and int(record["first_seq"]) > minimum_seq
        and int(record["last_seq"]) <= maximum_seq
    ]
    cpu_ahb = [
        record
        for record in selected
        if record["family"] == "RLT_AHB_STREAM_V1"
        and record.get("stage") == "host_handoff"
        and record.get("outcome") == "presented"
    ]
    gpu_ahb = [
        record
        for record in selected
        if record["family"] == "RLT_AHB_GPU_V1"
        and record.get("stage") == "host_handoff"
        and record.get("outcome") == "presented"
    ]
    ahb = gpu_ahb if gpu_ahb else cpu_ahb
    ahb_path = "gpu_blit" if gpu_ahb else "cpu_copy"
    direct_transfer = [
        record
        for record in selected
        if record["family"] == "RLT_DIRECT_TRANSFER_V1"
        and record.get("stage") == "host_present"
        and record.get("outcome") in (
            "guest_payload_suppressed",
            "gpu_readback_bypassed",
        )
    ]
    bounded_ahb = sorted(
        (
            record
            for record in ahb
            if minimum_seq <= int(record["frame_seq"]) <= maximum_seq
        ),
        key=lambda record: int(record["frame_seq"]),
    )
    if not all((producer, mesa, virgl)) or len(bounded_ahb) < 2:
        raise ValueError("incomplete_ahb_stream_evidence")

    producer_intervals = sum(int(record["interval_count"]) for record in producer)
    producer_elapsed_ns = sum(int(record["elapsed_ns"]) for record in producer)
    mesa_ranges = {
        (int(record["first_seq"]), int(record["last_seq"])) for record in mesa
    }
    virgl_ranges = {
        (int(record["first_seq"]), int(record["last_seq"])) for record in virgl
    }
    attribution_values = [
        float(record["attribution_pct"]) for record in (*mesa, *virgl)
    ]
    incomplete_frames = sum(
        int(record["incomplete_frames"]) for record in (*mesa, *virgl)
    )
    first_ahb = bounded_ahb[0]
    final_ahb = bounded_ahb[-1]
    sequence_span = int(final_ahb["frame_seq"]) - int(first_ahb["frame_seq"])
    presented_span = int(final_ahb["frames"]) - int(first_ahb["frames"])
    sequence_gaps = max(0, sequence_span - presented_span)
    ahb_stages = (
        ("acquire_wait", "import", "blit", "finish", "ready_send")
        if ahb_path == "gpu_blit"
        else (
            "acquire_wait",
            "lock",
            "copy",
            "unlock",
            "fence_wait",
            "ready_send",
        )
    )
    stage_means_ms = {
        stage: round(
            _cumulative_mean_ns(
                ahb,
                minimum_seq=minimum_seq,
                maximum_seq=maximum_seq,
                count_field="frames",
                total_field=f"total_{stage}_ns",
            )
            / 1_000_000.0,
            3,
        )
        for stage in ahb_stages
    }
    active_handoff_ms = round(
        sum(
            value
            for stage, value in stage_means_ms.items()
            if stage != "acquire_wait"
        ),
        3,
    )
    failures = int(final_ahb["failures"])
    transfer_policies = sorted(
        {record.get("transfer_policy", "unreported") for record in mesa}
    )
    precopy_fence_policies = sorted(
        {record.get("precopy_fence_policy", "unreported") for record in mesa}
    )
    direct_payload_expected = (
        transfer_policies == ["host_present_no_guest_payload"]
    )
    direct_payload_gate_met = not direct_payload_expected
    suppressed_bytes = 0
    suppressed_frames = 0
    if direct_payload_expected and direct_transfer:
        ordered_direct = sorted(
            direct_transfer, key=lambda record: int(record["frame_seq"])
        )
        baseline_direct = [
            record
            for record in ordered_direct
            if int(record["frame_seq"]) <= minimum_seq
        ]
        bounded_direct = [
            record
            for record in ordered_direct
            if int(record["frame_seq"]) <= maximum_seq
        ]
        if baseline_direct and bounded_direct:
            first_direct = baseline_direct[-1]
            final_direct = bounded_direct[-1]
            suppressed_frames = (
                int(final_direct["total_frames"])
                - int(first_direct["total_frames"])
            )
            suppressed_bytes = (
                int(final_direct["total_bytes_suppressed"])
                - int(first_direct["total_bytes_suppressed"])
            )
            direct_payload_gate_met = (
                suppressed_frames > 0 and suppressed_bytes > 0
            )
    summary: dict[str, object] = {
        "schema_version": 1,
        "transport": "unix_seqpacket_ahb",
        "ahb_path": ahb_path,
        "run_id": run_id,
        "minimum_seq_exclusive_for_frame_windows": minimum_seq,
        "maximum_seq_inclusive": maximum_seq,
        "producer_windows": len(producer),
        "producer_fps": round(
            producer_intervals * 1_000_000_000.0 / producer_elapsed_ns, 3
        ),
        "mesa_windows": len(mesa),
        "virgl_windows": len(virgl),
        "mesa_virgl_join_complete": mesa_ranges == virgl_ranges,
        "minimum_attribution_pct": min(attribution_values),
        "incomplete_frames": incomplete_frames,
        "mesa_completion_mean_ms": round(
            _weighted_mean(mesa, "completion_wait_us_mean") / 1000.0, 3
        ),
        "mesa_copy_mean_ms": round(
            _weighted_mean(mesa, "copy_us_mean") / 1000.0, 3
        ),
        "mesa_display_mean_ms": round(
            _weighted_mean(mesa, "display_us_mean") / 1000.0, 3
        ),
        "mesa_display_calls": sum(int(record["display_calls"]) for record in mesa),
        "mesa_display_policies": sorted(
            {record.get("display_policy", "unreported") for record in mesa}
        ),
        "mesa_transfer_policies": transfer_policies,
        "mesa_precopy_fence_policies": precopy_fence_policies,
        "mesa_precopy_fence_delegated": (
            precopy_fence_policies == ["delegated_to_transfer_completion"]
        ),
        "virgl_transfer_mean_ms": round(
            _weighted_mean(virgl, "transfer_exec_us_mean") / 1000.0, 3
        ),
        "ahb_stage_means_ms": stage_means_ms,
        "ahb_active_handoff_mean_ms": active_handoff_ms,
        "ahb_failures": failures,
        "ahb_sequence_gaps": sequence_gaps,
        "direct_payload_gate_met": direct_payload_gate_met,
    }
    if direct_payload_expected:
        summary["guest_payload_suppressed_frames"] = suppressed_frames
        summary["guest_payload_suppressed_bytes"] = suppressed_bytes
        summary["guest_payload_suppressed_mean_bytes"] = round(
            suppressed_bytes / suppressed_frames, 3
        ) if suppressed_frames else 0.0
    if "pool_rate_millihz" in final_ahb:
        summary["ahb_pool_rate_fps"] = round(
            int(final_ahb["pool_rate_millihz"]) / 1000.0,
            3,
        )
    summary["evidence_gate_met"] = all(
        (
            summary["mesa_virgl_join_complete"],
            summary["minimum_attribution_pct"] >= 99.9,
            summary["incomplete_frames"] == 0,
            summary["ahb_failures"] == 0,
            summary["ahb_sequence_gaps"] == 0,
            summary["direct_payload_gate_met"],
        )
    )
    return summary


def compare_summaries(
    baseline: dict[str, object], candidate: dict[str, object]
) -> dict[str, object]:
    metrics = (
        "producer_fps",
        "mesa_completion_mean_ms",
        "mesa_display_mean_ms",
        "host_copy_mean_ms",
        "host_send_mean_ms",
        "app_ingress_mean_ms",
        "app_payload_receive_mean_ms",
    )
    deltas: dict[str, dict[str, float]] = {}
    for metric in metrics:
        before = float(baseline[metric])
        after = float(candidate[metric])
        deltas[metric] = {
            "baseline": before,
            "candidate": after,
            "absolute_delta": round(after - before, 3),
            "percent_delta": round((after - before) * 100.0 / before, 3)
            if before
            else 0.0,
        }
    return {
        "schema_version": 1,
        "baseline_run_id": baseline["run_id"],
        "candidate_run_id": candidate["run_id"],
        "baseline_gate_met": baseline["evidence_gate_met"],
        "candidate_gate_met": candidate["evidence_gate_met"],
        "deltas": deltas,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, nargs="+", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--minimum-seq", type=int, required=True)
    parser.add_argument("--maximum-seq", type=int, required=True)
    parser.add_argument(
        "--transport",
        choices=("tcp", "ahb"),
        default="tcp",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    summarizer = summarize_ahb_run if args.transport == "ahb" else summarize_run
    summary = summarizer(
        load_records(args.logs),
        run_id=args.run_id,
        minimum_seq=args.minimum_seq,
        maximum_seq=args.maximum_seq,
    )
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
