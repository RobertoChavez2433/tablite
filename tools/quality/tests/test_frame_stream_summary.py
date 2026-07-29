from tools.frame_stream_summary import (
    compare_summaries,
    parse_record,
    records_from_lines,
    summarize_ahb_run,
    summarize_run,
)


def fixture_lines(run_id: str, fps: float, display_us: int) -> list[str]:
    elapsed = round(60 * 1_000_000_000 / fps)
    common = f"run_id={run_id}"
    return [
        f"RLT_FRAME_RATE_V1 {common} stage=producer outcome=produced "
        f"first_seq=300 last_seq=360 interval_count=60 elapsed_ns={elapsed}",
        f"RLT_MESA_VTEST_V1 {common} stage=mesa_vtest outcome=measured "
        "first_seq=301 last_seq=330 frame_count=30 attribution_pct=100.000 "
        "incomplete_frames=0 completion_wait_us_mean=3000 copy_us_mean=400 "
        f"display_us_mean={display_us} display_calls={30 if display_us else 0} "
        "display_policy=bypassed_frame_stream "
        "precopy_fence_policy=delegated_to_transfer_completion",
        f"RLT_VIRGL_HOST_V1 {common} stage=host_completion outcome=measured "
        "first_seq=301 last_seq=330 frame_count=30 attribution_pct=100.000 "
        "incomplete_frames=0 transfer_exec_us_mean=2200",
        f"RLT_FRAME_STREAM_V1 {common} frame_seq=300 stage=host_enqueue "
        "outcome=queued frames_queued=300 total_copy_ns=90000000",
        f"RLT_FRAME_STREAM_V1 {common} frame_seq=360 stage=host_enqueue "
        "outcome=queued frames_queued=360 total_copy_ns=111000000",
        f"RLT_FRAME_STREAM_V1 {common} frame_seq=300 stage=host_send outcome=sent "
        "frames_sent=300 total_send_ns=1500000000 frames_superseded=0 "
        "send_failures=0",
        f"RLT_FRAME_STREAM_V1 {common} frame_seq=360 stage=host_send outcome=sent "
        "frames_sent=360 total_send_ns=1800000000 frames_superseded=0 "
        "send_failures=0",
        f"RLT_FRAME_STREAM_V1 {common} frame_seq=360 stage=app_ingress "
        "outcome=presented ingress_to_present_ns=5000000 payload_receive_ns=4000000 "
        "payload_layout=scatter_packed_rows payload_recv_calls=1 "
        "sequence_gaps=0 invalid_frames=0",
    ]


def test_parse_and_dedupe_mirrored_records() -> None:
    line = fixture_lines("run-1", 50.0, 0)[0]
    assert parse_record(line)["family"] == "RLT_FRAME_RATE_V1"
    assert len(records_from_lines([line, line])) == 1


def test_summary_requires_join_and_loss_gates() -> None:
    summary = summarize_run(
        records_from_lines(fixture_lines("run-1", 50.0, 0)),
        run_id="run-1",
        minimum_seq=300,
        maximum_seq=360,
    )
    assert summary["producer_fps"] == 50.0
    assert summary["mesa_virgl_join_complete"] is True
    assert summary["mesa_display_calls"] == 0
    assert summary["host_copy_mean_ms"] == 0.35
    assert summary["host_send_mean_ms"] == 5.0
    assert summary["app_payload_recv_calls_mean"] == 1.0
    assert summary["app_payload_layouts"] == ["scatter_packed_rows"]
    assert summary["evidence_gate_met"] is True


def test_comparison_reports_renderer_delta() -> None:
    baseline = summarize_run(
        records_from_lines(fixture_lines("baseline", 40.0, 6000)),
        run_id="baseline",
        minimum_seq=300,
        maximum_seq=360,
    )
    candidate = summarize_run(
        records_from_lines(fixture_lines("candidate", 50.0, 0)),
        run_id="candidate",
        minimum_seq=300,
        maximum_seq=360,
    )
    comparison = compare_summaries(baseline, candidate)
    assert comparison["deltas"]["producer_fps"]["percent_delta"] == 25.0
    assert comparison["deltas"]["mesa_display_mean_ms"]["candidate"] == 0.0


def test_ahb_summary_requires_exact_join_and_reports_active_work() -> None:
    lines = fixture_lines("ahb-run", 100.0, 5000)[:3]
    lines.extend(
        [
            "RLT_AHB_STREAM_V1 run_id=ahb-run frame_seq=300 "
            "stage=host_handoff outcome=presented width=1280 height=1024 "
            "frames=300 total_bytes=1572864000 total_acquire_wait_ns=2100000000 "
            "total_lock_ns=18000000 total_copy_ns=180000000 "
            "total_unlock_ns=6000000 total_fence_wait_ns=30000 "
            "total_ready_send_ns=9000000 failures=0",
            "RLT_AHB_STREAM_V1 run_id=ahb-run frame_seq=360 "
            "stage=host_handoff outcome=presented width=1280 height=1024 "
            "frames=360 pool_frames=360 pool_elapsed_ns=359000000 "
            "pool_rate_millihz=100000 total_bytes=1887436800 "
            "total_acquire_wait_ns=2520000000 total_lock_ns=21600000 "
            "total_copy_ns=216000000 total_unlock_ns=7200000 "
            "total_fence_wait_ns=36000 total_ready_send_ns=10800000 failures=0",
        ]
    )
    summary = summarize_ahb_run(
        records_from_lines(lines),
        run_id="ahb-run",
        minimum_seq=300,
        maximum_seq=360,
    )
    assert summary["producer_fps"] == 100.0
    assert summary["mesa_virgl_join_complete"] is True
    assert summary["ahb_stage_means_ms"]["copy"] == 0.6
    assert summary["ahb_active_handoff_mean_ms"] == 0.71
    assert summary["ahb_pool_rate_fps"] == 100.0
    assert summary["ahb_sequence_gaps"] == 0
    assert summary["evidence_gate_met"] is True


def test_ahb_direct_transfer_requires_and_reports_suppressed_payload() -> None:
    lines = fixture_lines("direct-run", 100.0, 0)[:3]
    lines[1] += " transfer_policy=host_present_no_guest_payload"
    lines.extend(
        [
            "RLT_AHB_STREAM_V1 run_id=direct-run frame_seq=300 "
            "stage=host_handoff outcome=presented frames=300 "
            "total_acquire_wait_ns=2100000000 total_lock_ns=18000000 "
            "total_copy_ns=180000000 total_unlock_ns=6000000 "
            "total_fence_wait_ns=30000 total_ready_send_ns=9000000 failures=0",
            "RLT_AHB_STREAM_V1 run_id=direct-run frame_seq=360 "
            "stage=host_handoff outcome=presented frames=360 "
            "total_acquire_wait_ns=2520000000 total_lock_ns=21600000 "
            "total_copy_ns=216000000 total_unlock_ns=7200000 "
            "total_fence_wait_ns=36000 total_ready_send_ns=10800000 failures=0",
            "RLT_DIRECT_TRANSFER_V1 run_id=direct-run frame_seq=300 "
            "stage=host_present outcome=guest_payload_suppressed "
            "total_frames=300 total_bytes_suppressed=1500000000",
            "RLT_DIRECT_TRANSFER_V1 run_id=direct-run frame_seq=360 "
            "stage=host_present outcome=guest_payload_suppressed "
            "total_frames=360 total_bytes_suppressed=1800000000",
        ]
    )
    summary = summarize_ahb_run(
        records_from_lines(lines),
        run_id="direct-run",
        minimum_seq=300,
        maximum_seq=360,
    )
    assert summary["direct_payload_gate_met"] is True
    assert summary["guest_payload_suppressed_frames"] == 60
    assert summary["guest_payload_suppressed_bytes"] == 300000000
    assert summary["guest_payload_suppressed_mean_bytes"] == 5000000.0
    assert summary["evidence_gate_met"] is True


def test_gpu_ahb_summary_uses_gpu_stages_and_bypass_records() -> None:
    lines = fixture_lines("gpu-run", 100.0, 0)[:3]
    lines[1] += " transfer_policy=host_present_no_guest_payload"
    lines.extend(
        [
            "RLT_AHB_GPU_V1 run_id=gpu-run frame_seq=300 "
            "stage=host_handoff outcome=presented frames=300 "
            "total_acquire_wait_ns=2100000000 total_import_ns=3000000 "
            "total_blit_ns=90000000 total_finish_ns=150000000 "
            "total_ready_send_ns=9000000 failures=0",
            "RLT_AHB_GPU_V1 run_id=gpu-run frame_seq=360 "
            "stage=host_handoff outcome=presented frames=360 "
            "pool_frames=360 pool_elapsed_ns=359000000 "
            "pool_rate_millihz=100000 total_acquire_wait_ns=2520000000 "
            "total_import_ns=3000000 total_blit_ns=108000000 "
            "total_finish_ns=180000000 total_ready_send_ns=10800000 "
            "failures=0",
            "RLT_DIRECT_TRANSFER_V1 run_id=gpu-run frame_seq=300 "
            "stage=host_present outcome=gpu_readback_bypassed "
            "total_frames=300 total_bytes_suppressed=1500000000",
            "RLT_DIRECT_TRANSFER_V1 run_id=gpu-run frame_seq=360 "
            "stage=host_present outcome=gpu_readback_bypassed "
            "total_frames=360 total_bytes_suppressed=1800000000",
        ]
    )
    summary = summarize_ahb_run(
        records_from_lines(lines),
        run_id="gpu-run",
        minimum_seq=300,
        maximum_seq=360,
    )
    assert summary["ahb_path"] == "gpu_blit"
    assert summary["mesa_precopy_fence_delegated"] is True
    assert summary["ahb_stage_means_ms"]["blit"] == 0.3
    assert summary["ahb_stage_means_ms"]["finish"] == 0.5
    assert summary["guest_payload_suppressed_frames"] == 60
    assert summary["evidence_gate_met"] is True
