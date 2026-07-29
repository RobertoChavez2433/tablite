from tools.mesa_vtest_summary import (
    analyze_records,
    load_entries,
    parse_record,
    records_from_entries,
)


def make_line(first: int, last: int, completion_p95: int = 42000) -> str:
    stage_fields = []
    for stage, p95 in (
        ("submit", 900),
        ("transfer_get", 200),
        ("completion_wait", completion_p95),
        ("copy", 1400),
        ("display", 300),
        ("fence_wait", 0),
    ):
        stage_fields.extend(
            [
                f"{stage}_calls=30",
                f"{stage}_us_mean={p95 // 2}",
                f"{stage}_us_p50={p95 // 2}",
                f"{stage}_us_p95={p95}",
                f"{stage}_us_max={p95 + 100}",
            ]
        )
    return (
        "RLT_MESA_VTEST_V1 run_id=run-1 stage=mesa_vtest outcome=measured "
        f"first_seq={first} last_seq={last} frame_count=30 "
        "attribution_pct=100.000 incomplete_frames=0 unattributed_events=4 "
        "display_policy=bypassed_frame_stream "
        "transfer_policy=host_present_no_guest_payload "
        "precopy_fence_policy=delegated_to_transfer_completion "
        + " ".join(stage_fields)
    )


def test_parse_record_rejects_partial_records() -> None:
    assert parse_record("RLT_MESA_VTEST_V1 run_id=x") is None
    assert parse_record(make_line(1, 30)) is not None


def test_analysis_selects_completion_wait_and_justifies_instrumentation() -> None:
    entries = [
        {"message": make_line(1, 30)},
        {"message": make_line(31, 60, completion_p95=51000)},
        {"message": make_line(31, 60, completion_p95=51000)},
    ]
    summary = analyze_records(records_from_entries(entries))
    assert summary["window_count"] == 2
    assert summary["frame_count"] == 60
    assert summary["minimum_attribution_pct"] == 100.0
    assert summary["per_frame_assignment_gate_met"] is True
    assert summary["precopy_fence_delegated"] is True
    assert summary["precopy_fence_policies"] == [
        "delegated_to_transfer_completion"
    ]
    assert summary["dominant_stage_by_max_window_p95"] == "completion_wait"
    assert summary["virgl_instrumentation_justified"] is True
    assert summary["virgl_optimization_patch_justified"] is False


def test_assignment_gap_blocks_virgl_escalation() -> None:
    record = parse_record(make_line(1, 30))
    assert record is not None
    record["attribution_pct"] = 96.0
    summary = analyze_records([record])
    assert summary["per_frame_assignment_gate_met"] is False
    assert summary["virgl_instrumentation_justified"] is False


def test_load_entries_accepts_raw_native_log(tmp_path) -> None:
    path = tmp_path / "virgl-server.log"
    path.write_text("noise\n" + make_line(1, 30) + "\n", encoding="utf-8")
    entries = load_entries(path)
    assert records_from_entries(entries)[0]["first_seq"] == 1
