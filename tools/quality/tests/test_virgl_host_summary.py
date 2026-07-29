from tools.virgl_host_summary import (
    analyze_records,
    load_entries,
    parse_record,
)


def _host(first: int = 1, last: int = 30):
    metrics = " ".join(
        f"{stage}_us_mean=10 {stage}_us_p50=9 "
        f"{stage}_us_p95=12 {stage}_us_max=15"
        for stage in (
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
    )
    return parse_record(
        "RLT_VIRGL_HOST_V1 run_id=run stage=host_completion outcome=measured "
        f"first_seq={first} last_seq={last} frame_count=30 "
        "attribution_pct=100.000 incomplete_frames=0 unattributed_events=0 "
        f"{metrics}"
    )


def _mesa(first: int = 1, last: int = 30):
    return {
        "run_id": "run",
        "first_seq": first,
        "last_seq": last,
        "frame_count": 30,
        "completion_wait_us_mean": 100,
    }


def test_host_record_parses_and_joins_exact_window():
    record = _host()
    assert record is not None
    summary = analyze_records([record], [_mesa()], "run")
    assert summary["assignment_and_join_gate_met"] is True
    assert summary["join_coverage_pct"] == 100.0
    assert summary["optimization_patch_justified"] is False
    assert summary["stages"]["transfer_read_pixels"]["weighted_mean_us"] == 10


def test_missing_mesa_window_fails_join_gate():
    record = _host()
    assert record is not None
    summary = analyze_records([record], [], "run")
    assert summary["assignment_and_join_gate_met"] is False
    assert summary["decision"] == "repair_attribution_or_window_join"


def test_host_windows_outside_mesa_capture_scope_do_not_break_join_gate():
    records = [_host(1, 30), _host(31, 60), _host(61, 90)]
    assert all(record is not None for record in records)
    summary = analyze_records(
        [record for record in records if record is not None],
        [_mesa(31, 60)],
        "run",
    )
    assert summary["window_count"] == 1
    assert summary["host_windows_outside_mesa_scope"] == 2
    assert summary["join_coverage_pct"] == 100.0
    assert summary["assignment_and_join_gate_met"] is True


def test_load_entries_accepts_raw_native_log(tmp_path):
    path = tmp_path / "virgl-server.log"
    path.write_text("noise\nRLT_VIRGL_HOST_V1 incomplete\n", encoding="utf-8")
    assert load_entries(path) == [
        {"message": "noise"},
        {"message": "RLT_VIRGL_HOST_V1 incomplete"},
    ]
