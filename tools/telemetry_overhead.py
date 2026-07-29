#!/usr/bin/env python3
"""Validate a counterbalanced OFF/SUMMARY telemetry overhead campaign."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import statistics
from typing import Any


THROUGHPUT_LOSS_LIMIT_PERCENT = 2.0
P95_INCREASE_LIMIT_MS = 0.2
MINIMUM_PAIR_COUNT = 3


def _load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _thermal_status(device: dict[str, Any]) -> int | None:
    match = re.search(r"Thermal Status:\s*(\d+)", str(device.get("thermal", "")))
    return int(match.group(1)) if match else None


def _display_rate(device: dict[str, Any]) -> float | None:
    match = re.search(
        r'modeId\s+\d+,\s+renderFrameRate\s+([0-9]+(?:\.[0-9]+)?)',
        str(device.get("display", "")),
    )
    return float(match.group(1)) if match else None


def _median(values: list[float]) -> float | None:
    return round(statistics.median(values), 6) if values else None


def _capture(campaign_dir: Path, item: dict[str, Any]) -> dict[str, Any]:
    capture_dir = Path(item["capture_dir"])
    if not capture_dir.is_absolute():
        capture_dir = (campaign_dir / capture_dir).resolve()
    manifest = _load(capture_dir / "manifest.json")
    timeline = _load(capture_dir / "frame-timeline-summary.json")
    cost_path = capture_dir / "telemetry-cost-summary.json"
    cost = _load(cost_path) if cost_path.exists() else None
    expected_mode = item["mode"]
    issues: list[str] = []
    if manifest.get("telemetry_mode") != expected_mode:
        issues.append(
            f"mode_mismatch:{manifest.get('telemetry_mode')}!=expected_{expected_mode}"
        )
    if manifest.get("run_id") != item.get("run_id"):
        issues.append("run_id_mismatch")
    if manifest.get("run_identity_source") != "app_logcat":
        issues.append("untrusted_run_identity")
    if timeline.get("source") != "android.surfaceflinger.frametimeline":
        issues.append("wrong_timeline_source")
    if timeline.get("duplicate_timestamp_count") != 0:
        issues.append("duplicate_frame_timestamps")
    if timeline.get("interval_count", 0) < 2:
        issues.append("insufficient_frame_intervals")
    thermal_status = _thermal_status(manifest.get("device", {}))
    if thermal_status != 0:
        issues.append(f"thermal_status_{thermal_status}")
    display_rate = _display_rate(manifest.get("device", {}))
    if display_rate is None or abs(display_rate - manifest.get("target_hz", 0)) > 0.1:
        issues.append(f"display_rate_{display_rate}")
    if cost is None:
        issues.append("telemetry_self_cost_missing")
        cost_stages: dict[str, int] = {}
        cost_cpu_ns = None
    else:
        if not cost.get("valid"):
            issues.append("telemetry_self_cost_invalid")
        if cost.get("run_id") != item.get("run_id"):
            issues.append("telemetry_self_cost_run_id_mismatch")
        if cost.get("mode") != expected_mode:
            issues.append("telemetry_self_cost_mode_mismatch")
        if int(cost.get("totals", {}).get("dropped", 0)):
            issues.append("telemetry_self_cost_drops")
        cost_cpu_ns = int(
            cost.get("totals", {}).get("estimated_thread_cpu_ns", 0)
        )
        cost_stages = {
            str(stage["stage"]): int(stage.get("estimated_thread_cpu_ns", 0))
            for stage in cost.get("stages", [])
            if stage.get("source") == "android_app"
        }
    duration_seconds = int(manifest.get("duration_seconds", 0))
    cost_cpu_percent = (
        cost_cpu_ns / (duration_seconds * 1_000_000_000) * 100.0
        if cost_cpu_ns is not None and duration_seconds > 0
        else None
    )
    return {
        "pair_index": int(item["pair_index"]),
        "order_index": int(item["order_index"]),
        "mode": expected_mode,
        "run_id": item["run_id"],
        "capture_dir": str(capture_dir),
        "duration_seconds": manifest.get("duration_seconds"),
        "target_hz": manifest.get("target_hz"),
        "renderer_backend": manifest.get("renderer_backend"),
        "device_serial": manifest.get("device", {}).get("serial"),
        "device_fingerprint": manifest.get("device", {}).get("fingerprint"),
        "device_boot_id": manifest.get("device", {}).get("boot_id"),
        "thermal_status": thermal_status,
        "display_rate_hz": display_rate,
        "frame_count": timeline["frame_count"],
        "throughput_hz": timeline["throughput_hz"],
        "frame_interval_p95_ms": timeline["interval_ms"]["p95"],
        "trace_processor_version": timeline.get("trace_processor", {}).get("version"),
        "telemetry_self_cost": {
            "estimated_thread_cpu_ns": cost_cpu_ns,
            "single_core_utilization_percent": (
                round(cost_cpu_percent, 6)
                if cost_cpu_percent is not None
                else None
            ),
            "android_stage_cpu_ns": cost_stages,
        },
        "issues": issues,
    }


def _internal_stage_deltas(
    captures: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    stages = sorted(
        {
            stage
            for capture in captures
            for stage in capture["telemetry_self_cost"]["android_stage_cpu_ns"]
        }
    )
    output: list[dict[str, Any]] = []
    for stage in stages:
        by_mode: dict[str, list[float]] = {"OFF": [], "SUMMARY": []}
        for capture in captures:
            duration = int(capture.get("duration_seconds") or 0)
            if duration <= 0:
                continue
            cpu_ns = int(
                capture["telemetry_self_cost"]["android_stage_cpu_ns"].get(stage, 0)
            )
            by_mode[capture["mode"]].append(
                cpu_ns / (duration * 1_000_000_000) * 100.0
            )
        off_median = statistics.median(by_mode["OFF"]) if by_mode["OFF"] else 0.0
        summary_median = (
            statistics.median(by_mode["SUMMARY"])
            if by_mode["SUMMARY"]
            else 0.0
        )
        output.append(
            {
                "stage": stage,
                "off_median_single_core_utilization_percent": round(
                    off_median, 6
                ),
                "summary_median_single_core_utilization_percent": round(
                    summary_median, 6
                ),
                "summary_minus_off_percentage_points": round(
                    summary_median - off_median, 6
                ),
            }
        )
    return sorted(
        output,
        key=lambda item: item["summary_minus_off_percentage_points"],
        reverse=True,
    )


def analyze_campaign(
    campaign: dict[str, Any],
    campaign_dir: Path,
) -> dict[str, Any]:
    captures = [_capture(campaign_dir, item) for item in campaign["captures"]]
    shared_fields = (
        "duration_seconds",
        "target_hz",
        "renderer_backend",
        "device_serial",
        "device_fingerprint",
        "device_boot_id",
        "trace_processor_version",
    )
    campaign_issues: list[str] = []
    for field in shared_fields:
        values = {item[field] for item in captures}
        if len(values) != 1:
            campaign_issues.append(f"non_constant_{field}")

    by_pair: dict[int, list[dict[str, Any]]] = {}
    for item in captures:
        by_pair.setdefault(item["pair_index"], []).append(item)

    pairs: list[dict[str, Any]] = []
    for pair_index, items in sorted(by_pair.items()):
        modes = {item["mode"]: item for item in items}
        pair_issues: list[str] = []
        if set(modes) != {"OFF", "SUMMARY"} or len(items) != 2:
            pair_issues.append("pair_requires_exactly_one_off_and_one_summary")
            pairs.append({"pair_index": pair_index, "issues": pair_issues})
            continue
        off = modes["OFF"]
        summary = modes["SUMMARY"]
        pair_issues.extend(off["issues"])
        pair_issues.extend(summary["issues"])
        throughput_delta_percent = (
            summary["throughput_hz"] / off["throughput_hz"] - 1.0
        ) * 100.0
        throughput_loss_percent = max(0.0, -throughput_delta_percent)
        p95_delta_ms = (
            summary["frame_interval_p95_ms"] - off["frame_interval_p95_ms"]
        )
        pair_passed = (
            not pair_issues
            and throughput_loss_percent < THROUGHPUT_LOSS_LIMIT_PERCENT
            and p95_delta_ms < P95_INCREASE_LIMIT_MS
        )
        pairs.append(
            {
                "pair_index": pair_index,
                "order": [item["mode"] for item in sorted(items, key=lambda value: value["order_index"])],
                "off_run_id": off["run_id"],
                "summary_run_id": summary["run_id"],
                "off_throughput_hz": off["throughput_hz"],
                "summary_throughput_hz": summary["throughput_hz"],
                "throughput_delta_percent": round(throughput_delta_percent, 6),
                "throughput_loss_percent": round(throughput_loss_percent, 6),
                "off_frame_interval_p95_ms": off["frame_interval_p95_ms"],
                "summary_frame_interval_p95_ms": summary["frame_interval_p95_ms"],
                "frame_interval_p95_delta_ms": round(p95_delta_ms, 6),
                "off_telemetry_self_cpu_percent": off[
                    "telemetry_self_cost"
                ]["single_core_utilization_percent"],
                "summary_telemetry_self_cpu_percent": summary[
                    "telemetry_self_cost"
                ]["single_core_utilization_percent"],
                "telemetry_self_cpu_delta_percentage_points": round(
                    summary["telemetry_self_cost"][
                        "single_core_utilization_percent"
                    ]
                    - off["telemetry_self_cost"][
                        "single_core_utilization_percent"
                    ],
                    6,
                ),
                "issues": sorted(set(pair_issues)),
                "passed": pair_passed,
            }
        )

    complete_pairs = [item for item in pairs if "throughput_loss_percent" in item]
    losses = [item["throughput_loss_percent"] for item in complete_pairs]
    p95_deltas = [item["frame_interval_p95_delta_ms"] for item in complete_pairs]
    off_self_cpu = [
        item["off_telemetry_self_cpu_percent"] for item in complete_pairs
    ]
    summary_self_cpu = [
        item["summary_telemetry_self_cpu_percent"] for item in complete_pairs
    ]
    self_cpu_deltas = [
        item["telemetry_self_cpu_delta_percentage_points"]
        for item in complete_pairs
    ]
    gate_passed = (
        len(complete_pairs) >= MINIMUM_PAIR_COUNT
        and not campaign_issues
        and all(item["passed"] for item in complete_pairs)
    )
    return {
        "schema_version": 1,
        "campaign_id": campaign["campaign_id"],
        "observer": "android.surfaceflinger.frametimeline",
        "observer_semantics": (
            "external display-frame timestamps common to OFF and SUMMARY; "
            "no Tablite producer telemetry required"
        ),
        "thresholds": {
            "minimum_pair_count": MINIMUM_PAIR_COUNT,
            "maximum_throughput_loss_percent": THROUGHPUT_LOSS_LIMIT_PERCENT,
            "maximum_frame_interval_p95_increase_ms": P95_INCREASE_LIMIT_MS,
            "all_pairs_must_pass": True,
        },
        "capture_count": len(captures),
        "pair_count": len(complete_pairs),
        "campaign_issues": campaign_issues,
        "captures": captures,
        "pairs": pairs,
        "aggregate": {
            "median_throughput_loss_percent": _median(losses),
            "maximum_throughput_loss_percent": round(max(losses), 6) if losses else None,
            "median_frame_interval_p95_delta_ms": _median(p95_deltas),
            "maximum_frame_interval_p95_delta_ms": (
                round(max(p95_deltas), 6) if p95_deltas else None
            ),
            "off_median_telemetry_self_cpu_percent": _median(off_self_cpu),
            "summary_median_telemetry_self_cpu_percent": _median(
                summary_self_cpu
            ),
            "median_telemetry_self_cpu_delta_percentage_points": _median(
                self_cpu_deltas
            ),
        },
        "internal_stage_deltas": _internal_stage_deltas(captures),
        "gate_passed": gate_passed,
        "decision": (
            "summary_overhead_gate_passed"
            if gate_passed
            else "summary_overhead_gate_failed_closed"
        ),
    }


def render_report(summary: dict[str, Any]) -> str:
    lines = [
        "# Telemetry OFF/SUMMARY Overhead Campaign",
        "",
        f"- Campaign: `{summary['campaign_id']}`",
        f"- External observer: `{summary['observer']}`",
        f"- Pairs: {summary['pair_count']}; gate passed: {summary['gate_passed']}",
        (
            "- Median throughput loss: "
            f"{summary['aggregate']['median_throughput_loss_percent']}%"
        ),
        (
            "- Median frame-interval p95 delta: "
            f"{summary['aggregate']['median_frame_interval_p95_delta_ms']} ms"
        ),
        f"- Decision: `{summary['decision']}`",
        (
            "- Median telemetry self CPU (OFF → SUMMARY): "
            f"{summary['aggregate']['off_median_telemetry_self_cpu_percent']}% → "
            f"{summary['aggregate']['summary_median_telemetry_self_cpu_percent']}% "
            "of one core"
        ),
        "",
        "| Pair | Order | Throughput loss | Frame-interval p95 delta | Self CPU delta | Pass |",
        "|---:|---|---:|---:|---:|:---:|",
    ]
    for pair in summary["pairs"]:
        if "throughput_loss_percent" not in pair:
            lines.append(
                f"| {pair['pair_index']} | invalid | unavailable | unavailable | "
                "unavailable | no |"
            )
            continue
        lines.append(
            f"| {pair['pair_index']} | {' → '.join(pair['order'])} | "
            f"{pair['throughput_loss_percent']:.3f}% | "
            f"{pair['frame_interval_p95_delta_ms']:.3f} ms | "
            f"{pair['telemetry_self_cpu_delta_percentage_points']:.3f} pp | "
            f"{'yes' if pair['passed'] else 'no'} |"
        )
    lines.extend(
        (
            "",
            "## Internal telemetry CPU attribution",
            "",
            "| Stage | OFF median | SUMMARY median | SUMMARY − OFF |",
            "|---|---:|---:|---:|",
        )
    )
    for stage in summary["internal_stage_deltas"]:
        lines.append(
            f"| {stage['stage']} | "
            f"{stage['off_median_single_core_utilization_percent']:.3f}% | "
            f"{stage['summary_median_single_core_utilization_percent']:.3f}% | "
            f"{stage['summary_minus_off_percentage_points']:.3f} pp |"
        )
    lines.extend(
        (
            "",
            "The gate fails closed unless at least three counterbalanced pairs are valid",
            "and every pair is below both the 2% throughput-loss and 0.2 ms p95",
            "limits. A failed gate does not authorize renderer optimization.",
            "",
        )
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("campaign_manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    campaign_path = args.campaign_manifest.resolve()
    summary = analyze_campaign(_load(campaign_path), campaign_path.parent)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    args.report.write_text(render_report(summary), encoding="utf-8")
    print(json.dumps({"gate_passed": summary["gate_passed"], "decision": summary["decision"]}))
    return 0 if summary["gate_passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
