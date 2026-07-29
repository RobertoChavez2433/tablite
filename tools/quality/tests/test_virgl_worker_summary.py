import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))

from virgl_worker_summary import (  # noqa: E402
    forward_new_producer_summaries,
    join_with_producer,
    make_ingress_line,
    parse_proc_stat,
    producer_windows,
    summarize_samples,
)


def thread(tid: int, run_ns: int, wait_ns: int, state: str = "R") -> dict[str, object]:
    return {
        "tid": tid,
        "name": "virgl",
        "state": state,
        "start_ticks": 5,
        "processor": 3,
        "run_ns": run_ns,
        "runqueue_wait_ns": wait_ns,
        "timeslices": 1,
        "wchan": "0" if state == "R" else "do_sys_poll",
    }


def test_parse_proc_stat_handles_parentheses_in_name() -> None:
    suffix = ["R"] + ["0"] * 36
    suffix[19] = "987"
    suffix[36] = "6"
    parsed = parse_proc_stat("42 (virgl worker) " + " ".join(suffix))
    assert parsed == {
        "tid": 42,
        "name": "virgl worker",
        "state": "R",
        "start_ticks": 987,
        "processor": 6,
    }


def test_summary_reports_worker_and_sampler_cost() -> None:
    samples = [
        {
            "monotonic_ns": 1_000,
            "read_duration_ns": 10,
            "read_errors": 0,
            "threads": [thread(42, 100, 20), thread(43, 200, 30, "S")],
        },
        {
            "monotonic_ns": 2_000,
            "read_duration_ns": 20,
            "read_errors": 0,
            "threads": [thread(42, 500, 120, "S"), thread(43, 500, 80, "S")],
        },
    ]
    summary = summarize_samples(samples, sampler_cpu_ns=80, cpu_count=8)
    assert summary["worker_run_ns"] == 700
    assert summary["worker_runqueue_wait_ns"] == 150
    assert summary["main_thread_blocked_or_idle_ns"] == 500
    assert summary["sampler_device_capacity_pct"] == 1.0
    line = make_ingress_line("run-1", 42, summary)
    assert line.startswith("RLT_VIRGL_WORKER_V1 run_id=run-1 pid=42 ")
    assert "worker_run_ns=700" in line


def test_join_uses_canonical_producer_windows_without_claiming_frames() -> None:
    capture = {
        "capture_mode": "noninvasive_procfs_burst",
        "run_id": "run-1",
        "target": {"pid": 42},
        "summary": {
            "window_start_monotonic_ns": 1_000,
            "window_end_monotonic_ns": 5_000,
        },
        "ingress": {"delivered": True},
        "limitations": [],
    }
    producer_line = (
        "RLT_FRAME_RATE_V1 run_id=producer-1 stage=producer outcome=produced "
        "first_seq=10 last_seq=14 interval_count=4 elapsed_ns=2000 "
        "end_monotonic_ns=4000 produced_fps=2000000.000"
    )
    logs = {"entries": [{"message": producer_line}, {"message": producer_line}]}
    parsed = producer_windows(logs["entries"])
    assert len(parsed) == 1
    result = join_with_producer(capture, logs)
    correlation = result["producer_correlation"]
    assert correlation["status"] == "summary_window_correlated_not_per_frame"
    assert correlation["producer_interval_count"] == 4
    assert correlation["per_frame_assignment_pct"] is None
    assert result["decision"]["virgl_modification_justified"] is False


def test_producer_forwarder_reads_only_new_allowlisted_lines(
    tmp_path: Path, monkeypatch
) -> None:
    log = tmp_path / "native.log"
    log.write_text("old private line\n", encoding="utf-8")
    offset = log.stat().st_size
    with log.open("a", encoding="utf-8") as output:
        output.write("new private line\n")
        output.write(
            "[rlawt] RLT_FRAME_RATE_V1 run_id=r stage=producer outcome=produced "
            "first_seq=1 last_seq=2 interval_count=1 elapsed_ns=10 "
            "end_monotonic_ns=20 produced_fps=100000000.000\n"
        )
    delivered: list[str] = []
    monkeypatch.setattr(
        "virgl_worker_summary.deliver_ingress",
        lambda line, port: delivered.append(line) or {"delivered": True},
    )

    result = forward_new_producer_summaries(log, offset, 8100)

    assert result == {
        "available": True,
        "matched": 1,
        "delivered": 1,
        "failed": 0,
    }
    assert delivered[0].startswith("[rlawt] RLT_FRAME_RATE_V1 ")
