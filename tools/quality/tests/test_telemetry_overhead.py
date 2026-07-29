from __future__ import annotations

import json
from pathlib import Path

from tools.telemetry_overhead import analyze_campaign


def write_capture(
    root: Path,
    name: str,
    mode: str,
    run_id: str,
    throughput: float,
    p95: float,
) -> Path:
    capture = root / name
    capture.mkdir()
    (capture / "manifest.json").write_text(
        json.dumps(
            {
                "run_id": run_id,
                "run_identity_source": "app_logcat",
                "duration_seconds": 60,
                "target_hz": 120,
                "renderer_backend": "native_x11_virgl_gpu",
                "telemetry_mode": mode,
                "device": {
                    "serial": "device",
                    "fingerprint": "fingerprint",
                    "boot_id": "boot",
                    "thermal": "Thermal Status: 0",
                    "display": "modeId 2, renderFrameRate 120.00001",
                },
            }
        ),
        encoding="utf-8",
    )
    (capture / "frame-timeline-summary.json").write_text(
        json.dumps(
            {
                "source": "android.surfaceflinger.frametimeline",
                "frame_count": 100,
                "interval_count": 99,
                "throughput_hz": throughput,
                "interval_ms": {"p95": p95},
                "duplicate_timestamp_count": 0,
                "trace_processor": {"version": "v1"},
            }
        ),
        encoding="utf-8",
    )
    self_cpu_ns = 1_200_000_000 if mode == "SUMMARY" else 600_000_000
    (capture / "telemetry-cost-summary.json").write_text(
        json.dumps(
            {
                "run_id": run_id,
                "mode": mode,
                "valid": True,
                "totals": {
                    "estimated_thread_cpu_ns": self_cpu_ns,
                    "dropped": 0,
                },
                "stages": [
                    {
                        "source": "android_app",
                        "stage": "json_encode",
                        "estimated_thread_cpu_ns": self_cpu_ns,
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    return capture


def campaign(root: Path, losses: list[tuple[float, float]]) -> dict:
    captures = []
    order_index = 0
    for pair_index, (throughput_loss_percent, p95_delta) in enumerate(losses, 1):
        pair_order = ("OFF", "SUMMARY") if pair_index % 2 else ("SUMMARY", "OFF")
        for mode in pair_order:
            order_index += 1
            run_id = f"{mode.lower()}-{pair_index}"
            throughput = 100.0 if mode == "OFF" else 100.0 - throughput_loss_percent
            p95 = 10.0 if mode == "OFF" else 10.0 + p95_delta
            capture = write_capture(
                root,
                f"capture-{order_index}",
                mode,
                run_id,
                throughput,
                p95,
            )
            captures.append(
                {
                    "pair_index": pair_index,
                    "order_index": order_index,
                    "mode": mode,
                    "run_id": run_id,
                    "capture_dir": str(capture),
                }
            )
    return {"campaign_id": "campaign", "captures": captures}


def test_three_counterbalanced_pairs_can_pass(tmp_path: Path) -> None:
    summary = analyze_campaign(
        campaign(tmp_path, [(1.0, 0.1), (0.5, 0.0), (1.5, -0.1)]),
        tmp_path,
    )

    assert summary["pair_count"] == 3
    assert [pair["order"] for pair in summary["pairs"]] == [
        ["OFF", "SUMMARY"],
        ["SUMMARY", "OFF"],
        ["OFF", "SUMMARY"],
    ]
    assert summary["gate_passed"] is True
    assert summary["decision"] == "summary_overhead_gate_passed"
    assert (
        summary["aggregate"][
            "median_telemetry_self_cpu_delta_percentage_points"
        ]
        == 1.0
    )
    assert summary["internal_stage_deltas"][0]["stage"] == "json_encode"


def test_any_over_limit_pair_fails_closed(tmp_path: Path) -> None:
    summary = analyze_campaign(
        campaign(tmp_path, [(1.0, 0.1), (2.1, 0.0), (1.0, 0.21)]),
        tmp_path,
    )

    assert summary["gate_passed"] is False
    assert [pair["passed"] for pair in summary["pairs"]] == [True, False, False]


def test_fewer_than_three_pairs_fails_closed(tmp_path: Path) -> None:
    summary = analyze_campaign(campaign(tmp_path, [(0.0, 0.0)]), tmp_path)

    assert summary["pair_count"] == 1
    assert summary["gate_passed"] is False
