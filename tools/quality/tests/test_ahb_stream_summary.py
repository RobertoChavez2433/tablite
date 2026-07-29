from tools.ahb_stream_summary import (
    analyze_records,
    parse_presented,
    parse_probe,
    records_from_entries,
)


def presented_line(
    frame: int = 240,
    pool_rate_millihz: int | None = None,
) -> str:
    pool_rate = (
        f"pool_frames={frame} pool_elapsed_ns=2050744000 "
        f"pool_rate_millihz={pool_rate_millihz} "
        if pool_rate_millihz is not None
        else ""
    )
    return (
        "RLT_AHB_STREAM_V1 run_id=ahb-1 frame_seq=240 "
        "stage=host_handoff outcome=presented slot=2 width=1280 height=1024 "
        f"bytes=5242880 frames={frame} {pool_rate}total_bytes=1258291200 "
        "total_acquire_wait_ns=1739934384 total_lock_ns=18735998 "
        "total_copy_ns=186964314 total_unlock_ns=6551150 "
        "total_fence_wait_ns=36621 total_ready_send_ns=10476312 failures=0"
    )


def probe_line() -> str:
    return (
        "RLT_AHB_STREAM_V1 run_id=ahb-1 stage=startup_probe outcome=complete "
        "width=1280 height=1024 frames=240 duration_ns=2050744000 "
        "rate_millihz=117030 failures=0"
    )


def gpu_presented_line(frame: int = 240) -> str:
    return (
        "RLT_AHB_GPU_V1 run_id=gpu-1 frame_seq=240 "
        "stage=host_handoff outcome=presented slot=2 width=1280 height=1024 "
        f"frames={frame} pool_frames={frame} pool_elapsed_ns=2050744000 "
        "pool_rate_millihz=117030 total_acquire_wait_ns=1739934384 "
        "total_import_ns=3000000 total_blit_ns=72000000 "
        "total_finish_ns=120000000 total_ready_send_ns=10476312 failures=0"
    )


def test_parsers_reject_partial_records() -> None:
    assert parse_presented("RLT_AHB_STREAM_V1 run_id=x") is None
    assert parse_probe("RLT_AHB_STREAM_V1 run_id=x") is None
    assert parse_presented(presented_line()) is not None
    assert parse_presented(gpu_presented_line()) is not None
    assert parse_probe(probe_line()) is not None


def test_analysis_separates_release_wait_from_active_work() -> None:
    entries = [
        {"message": presented_line(60)},
        {"message": presented_line()},
        {"message": presented_line()},
        {"message": probe_line()},
    ]
    presented, probes = records_from_entries(entries)
    summary = analyze_records(presented, probes)
    assert summary["frame_count"] == 240
    assert summary["rate_hz"] == 117.03
    assert summary["rate_source"] == "startup_probe"
    assert summary["target_90_fps_gate_met"] is True
    assert summary["dominant_stage"] == "acquire_wait"
    assert summary["dominant_active_stage"] == "copy"
    assert summary["stage_means_us"]["lock"] == 78.067
    assert summary["decision"] == "integrate_and_measure_logged_in_game_loop"


def test_failures_block_transport_gate() -> None:
    frame = parse_presented(presented_line())
    probe = parse_probe(probe_line())
    assert frame is not None and probe is not None
    frame["failures"] = 1
    summary = analyze_records([frame], [probe])
    assert summary["target_90_fps_gate_met"] is False
    assert summary["decision"] == "repair_transport_failures_before_optimization"


def test_production_pool_rate_takes_precedence_and_routes_upstream() -> None:
    frame = parse_presented(presented_line(pool_rate_millihz=19447))
    probe = parse_probe(probe_line())
    assert frame is not None and probe is not None
    summary = analyze_records([frame], [probe])
    assert summary["rate_hz"] == 19.447
    assert summary["rate_source"] == "production_pool"
    assert summary["active_handoff_work_us"] < 8333.334
    assert summary["decision"] == "optimize_upstream_producer_before_handoff"


def test_gpu_path_reports_blit_and_finish_without_cpu_copy() -> None:
    first = parse_presented(gpu_presented_line(60))
    final = parse_presented(gpu_presented_line())
    assert first is not None and final is not None
    summary = analyze_records([first, final], [])
    assert summary["path"] == "gpu_blit"
    assert summary["rate_hz"] == 117.03
    assert summary["copy_throughput_gb_s"] is None
    assert summary["dominant_active_stage"] == "finish"
