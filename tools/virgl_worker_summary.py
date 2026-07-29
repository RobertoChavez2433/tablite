#!/usr/bin/env python3
"""Collect and analyze non-invasive VirGL worker SUMMARY/BURST observations.

The sampler reads procfs scheduling counters without ptrace, GPU API hooks, or
changes to the VirGL process. It can publish one bounded summary record to
Tablite's canonical native telemetry ingress. The analyzer joins that window
to producer summary identities retained by the agent diagnostics endpoint.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import socket
import statistics
import time
from collections import Counter
from pathlib import Path
from typing import Iterable, Sequence


PRODUCER_RE = re.compile(
    r"\bRLT_FRAME_RATE_V1\s+"
    r"run_id=(?P<run_id>\S+)\s+"
    r"stage=producer\s+outcome=produced\s+"
    r"first_seq=(?P<first_seq>\d+)\s+"
    r"last_seq=(?P<last_seq>\d+)\s+"
    r"interval_count=(?P<interval_count>\d+)\s+"
    r"elapsed_ns=(?P<elapsed_ns>\d+)\s+"
    r"end_monotonic_ns=(?P<end_ns>\d+)\s+"
    r"produced_fps=(?P<produced_fps>\d+(?:\.\d+)?)"
)
TARGET_NAME = "virgl_test_server_android"


def parse_proc_stat(line: str) -> dict[str, object]:
    close = line.rfind(")")
    open_ = line.find("(")
    if open_ < 0 or close <= open_:
        raise ValueError("invalid_proc_stat")
    pid = int(line[:open_].strip())
    name = line[open_ + 1 : close]
    fields = line[close + 2 :].split()
    if len(fields) < 37:
        raise ValueError("short_proc_stat")
    return {
        "tid": pid,
        "name": name,
        "state": fields[0],
        "start_ticks": int(fields[19]),
        "processor": int(fields[36]),
    }


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace").strip()


def find_active_worker(proc_root: Path = Path("/proc")) -> int:
    candidates: list[int] = []
    for path in proc_root.iterdir():
        if not path.name.isdigit():
            continue
        try:
            cmdline = (path / "cmdline").read_bytes().replace(b"\0", b" ")
            if TARGET_NAME.encode() not in cmdline:
                continue
            owns_mali = any(
                os.readlink(fd).startswith("/dev/mali")
                for fd in (path / "fd").iterdir()
            )
            if owns_mali:
                candidates.append(int(path.name))
        except (FileNotFoundError, PermissionError, OSError):
            continue
    if not candidates:
        raise RuntimeError("no_active_virgl_worker")
    return min(candidates)


def read_thread(tid_path: Path) -> dict[str, object]:
    stat = parse_proc_stat(read_text(tid_path / "stat"))
    schedstat = read_text(tid_path / "schedstat").split()
    if len(schedstat) < 3:
        raise ValueError("short_schedstat")
    try:
        wchan = read_text(tid_path / "wchan") or "unavailable"
    except (FileNotFoundError, PermissionError, OSError):
        wchan = "unavailable"
    return {
        **stat,
        "run_ns": int(schedstat[0]),
        "runqueue_wait_ns": int(schedstat[1]),
        "timeslices": int(schedstat[2]),
        "wchan": wchan[:96],
    }


def read_sample(pid: int, proc_root: Path = Path("/proc")) -> dict[str, object]:
    before = time.monotonic_ns()
    task_root = proc_root / str(pid) / "task"
    threads: list[dict[str, object]] = []
    errors = 0
    for tid_path in sorted(task_root.iterdir(), key=lambda path: int(path.name)):
        try:
            threads.append(read_thread(tid_path))
        except (FileNotFoundError, PermissionError, OSError, ValueError):
            errors += 1
    after = time.monotonic_ns()
    if not threads:
        raise RuntimeError("virgl_worker_disappeared")
    return {
        "monotonic_ns": before + ((after - before) // 2),
        "read_duration_ns": after - before,
        "read_errors": errors,
        "threads": threads,
    }


def _delta(first: dict[str, object], last: dict[str, object], field: str) -> int:
    return max(0, int(last[field]) - int(first[field]))


def _percentile(values: Sequence[int], quantile: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * quantile) - 1)]


def summarize_samples(
    samples: Sequence[dict[str, object]],
    *,
    sampler_cpu_ns: int,
    cpu_count: int,
) -> dict[str, object]:
    if len(samples) < 2:
        raise ValueError("insufficient_virgl_samples")
    start_ns = int(samples[0]["monotonic_ns"])
    end_ns = int(samples[-1]["monotonic_ns"])
    duration_ns = end_ns - start_ns
    if duration_ns <= 0:
        raise ValueError("invalid_virgl_sample_window")

    first_threads = {
        int(thread["tid"]): thread
        for thread in samples[0]["threads"]  # type: ignore[index]
    }
    last_threads = {
        int(thread["tid"]): thread
        for thread in samples[-1]["threads"]  # type: ignore[index]
    }
    stable_tids = sorted(first_threads.keys() & last_threads.keys())
    worker_run_ns = sum(
        _delta(first_threads[tid], last_threads[tid], "run_ns")
        for tid in stable_tids
    )
    worker_wait_ns = sum(
        _delta(first_threads[tid], last_threads[tid], "runqueue_wait_ns")
        for tid in stable_tids
    )
    pid = min(first_threads)
    main_first = first_threads.get(pid)
    main_last = last_threads.get(pid)
    main_run_ns = (
        _delta(main_first, main_last, "run_ns")
        if main_first is not None and main_last is not None
        else 0
    )
    main_wait_ns = (
        _delta(main_first, main_last, "runqueue_wait_ns")
        if main_first is not None and main_last is not None
        else 0
    )
    main_blocked_or_idle_ns = max(0, duration_ns - main_run_ns - main_wait_ns)

    states: Counter[str] = Counter()
    wchans: Counter[str] = Counter()
    read_durations: list[int] = []
    intervals: list[int] = []
    read_errors = 0
    prior_ns: int | None = None
    for sample in samples:
        sample_ns = int(sample["monotonic_ns"])
        if prior_ns is not None:
            intervals.append(sample_ns - prior_ns)
        prior_ns = sample_ns
        read_durations.append(int(sample["read_duration_ns"]))
        read_errors += int(sample.get("read_errors") or 0)
        for thread in sample["threads"]:  # type: ignore[index]
            if int(thread["tid"]) == pid:
                states[str(thread["state"])] += 1
                wchans[str(thread["wchan"])] += 1
                break

    one_core_pct = 100.0 * worker_run_ns / duration_ns
    capacity_pct = one_core_pct / max(1, cpu_count)
    sampler_one_core_pct = 100.0 * sampler_cpu_ns / duration_ns
    return {
        "window_start_monotonic_ns": start_ns,
        "window_end_monotonic_ns": end_ns,
        "duration_ns": duration_ns,
        "sample_count": len(samples),
        "thread_sample_count": sum(
            len(sample["threads"]) for sample in samples  # type: ignore[arg-type]
        ),
        "stable_thread_count": len(stable_tids),
        "first_thread_count": len(first_threads),
        "last_thread_count": len(last_threads),
        "worker_run_ns": worker_run_ns,
        "worker_runqueue_wait_ns": worker_wait_ns,
        "worker_one_core_utilization_pct": round(one_core_pct, 4),
        "worker_device_capacity_pct": round(capacity_pct, 4),
        "main_thread_run_ns": main_run_ns,
        "main_thread_runqueue_wait_ns": main_wait_ns,
        "main_thread_blocked_or_idle_ns": main_blocked_or_idle_ns,
        "main_thread_state_samples": dict(sorted(states.items())),
        "main_thread_wchan_samples": dict(wchans.most_common(12)),
        "read_errors": read_errors,
        "sample_read_p50_ns": _percentile(read_durations, 0.50),
        "sample_read_p95_ns": _percentile(read_durations, 0.95),
        "sample_interval_p50_ns": _percentile(intervals, 0.50),
        "sample_interval_p95_ns": _percentile(intervals, 0.95),
        "sampler_cpu_ns": sampler_cpu_ns,
        "sampler_one_core_utilization_pct": round(sampler_one_core_pct, 4),
        "sampler_device_capacity_pct": round(
            sampler_one_core_pct / max(1, cpu_count), 4
        ),
    }


def make_ingress_line(run_id: str, pid: int, summary: dict[str, object]) -> str:
    fields = {
        "run_id": run_id,
        "pid": pid,
        "window_start_monotonic_ns": summary["window_start_monotonic_ns"],
        "window_end_monotonic_ns": summary["window_end_monotonic_ns"],
        "sample_count": summary["sample_count"],
        "thread_sample_count": summary["thread_sample_count"],
        "worker_run_ns": summary["worker_run_ns"],
        "runqueue_wait_ns": summary["worker_runqueue_wait_ns"],
        "main_run_ns": summary["main_thread_run_ns"],
        "main_blocked_or_idle_ns": summary["main_thread_blocked_or_idle_ns"],
        "worker_one_core_pct": summary["worker_one_core_utilization_pct"],
        "sampler_one_core_pct": summary["sampler_one_core_utilization_pct"],
        "read_errors": summary["read_errors"],
    }
    return "RLT_VIRGL_WORKER_V1 " + " ".join(
        f"{key}={value}" for key, value in fields.items()
    )


def deliver_ingress(line: str, port: int) -> dict[str, object]:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2) as client:
            client.sendall((line + "\n").encode("utf-8"))
        return {"delivered": True, "endpoint": f"127.0.0.1:{port}"}
    except OSError as error:
        return {
            "delivered": False,
            "endpoint": f"127.0.0.1:{port}",
            "error": type(error).__name__,
        }


def producer_log_offset(path: Path | None) -> int | None:
    if path is None:
        return None
    try:
        return path.stat().st_size
    except OSError:
        return None


def forward_new_producer_summaries(
    path: Path | None,
    start_offset: int | None,
    ingress_port: int,
) -> dict[str, object]:
    if path is None or start_offset is None:
        return {"available": False, "matched": 0, "delivered": 0, "failed": 0}
    matched = 0
    delivered = 0
    failed = 0
    try:
        with path.open("r", encoding="utf-8", errors="replace") as source:
            source.seek(start_offset)
            for raw_line in source:
                line = raw_line.rstrip("\r\n")
                if (
                    not PRODUCER_RE.search(line)
                    or len(line) > 2_048
                    or not (
                        line.startswith("RLT_FRAME_RATE_V1 ")
                        or line.startswith("[rlawt] RLT_FRAME_RATE_V1 ")
                    )
                ):
                    continue
                matched += 1
                if bool(deliver_ingress(line, ingress_port)["delivered"]):
                    delivered += 1
                else:
                    failed += 1
    except OSError as error:
        return {
            "available": False,
            "matched": matched,
            "delivered": delivered,
            "failed": failed,
            "error": type(error).__name__,
        }
    return {
        "available": True,
        "matched": matched,
        "delivered": delivered,
        "failed": failed,
    }


def collect(
    *,
    duration_seconds: float,
    interval_ms: int,
    run_id: str,
    ingress_port: int,
    producer_log: Path | None,
) -> dict[str, object]:
    pid = find_active_worker()
    identity = parse_proc_stat(read_text(Path("/proc") / str(pid) / "stat"))
    producer_start_offset = producer_log_offset(producer_log)
    samples: list[dict[str, object]] = []
    wall_start = time.monotonic_ns()
    cpu_start = time.process_time_ns()
    deadline = wall_start + int(duration_seconds * 1_000_000_000)
    interval_ns = interval_ms * 1_000_000
    next_sample = wall_start
    while True:
        samples.append(read_sample(pid))
        now = time.monotonic_ns()
        if now >= deadline:
            break
        next_sample += interval_ns
        sleep_ns = next_sample - now
        if sleep_ns > 0:
            time.sleep(sleep_ns / 1_000_000_000)
        else:
            next_sample = now
    sampler_cpu_ns = time.process_time_ns() - cpu_start
    summary = summarize_samples(
        samples,
        sampler_cpu_ns=sampler_cpu_ns,
        cpu_count=os.cpu_count() or 1,
    )
    end_identity = parse_proc_stat(read_text(Path("/proc") / str(pid) / "stat"))
    if identity["start_ticks"] != end_identity["start_ticks"]:
        raise RuntimeError("virgl_worker_identity_changed")
    ingress_line = make_ingress_line(run_id, pid, summary)
    ingress = deliver_ingress(ingress_line, ingress_port)
    producer_ingress = forward_new_producer_summaries(
        producer_log,
        producer_start_offset,
        ingress_port,
    )
    return {
        "schema_version": 1,
        "capture_mode": "noninvasive_procfs_burst",
        "run_id": run_id,
        "target": {
            "pid": pid,
            "name": identity["name"],
            "start_ticks": identity["start_ticks"],
        },
        "requested_duration_seconds": duration_seconds,
        "requested_interval_ms": interval_ms,
        "summary": summary,
        "ingress": {**ingress, "record": ingress_line},
        "producer_ingress": producer_ingress,
        "samples": samples,
        "limitations": [
            "procfs sampling is observational but not free; sampler CPU cost is reported",
            "schedstat runqueue wait is scheduler delay, not GPU or fence wait",
            "blocked_or_idle time cannot yet distinguish socket, GPU, fence, or intentional idle",
            "producer correlation uses low-rate summary windows until BURST carries per-frame identity",
            "no VirGL source or binary is modified by this capture",
        ],
    }


def producer_windows(entries: Iterable[object]) -> list[dict[str, object]]:
    windows: list[dict[str, object]] = []
    seen: set[tuple[str, int, int]] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        message = str(entry.get("message") or entry.get("msg") or "")
        match = PRODUCER_RE.search(message)
        if match is None:
            continue
        values = match.groupdict()
        first_seq = int(values["first_seq"])
        last_seq = int(values["last_seq"])
        interval_count = int(values["interval_count"])
        elapsed_ns = int(values["elapsed_ns"])
        end_ns = int(values["end_ns"])
        key = (values["run_id"], first_seq, last_seq)
        if key in seen:
            continue
        seen.add(key)
        valid = (
            last_seq > first_seq
            and interval_count == last_seq - first_seq
            and elapsed_ns > 0
        )
        windows.append(
            {
                "run_id": values["run_id"],
                "first_seq": first_seq,
                "last_seq": last_seq,
                "interval_count": interval_count,
                "elapsed_ns": elapsed_ns,
                "start_monotonic_ns": end_ns - elapsed_ns,
                "end_monotonic_ns": end_ns,
                "reported_produced_fps": float(values["produced_fps"]),
                "derived_produced_fps": round(
                    interval_count * 1_000_000_000.0 / elapsed_ns, 6
                ),
                "valid": valid,
            }
        )
    return sorted(windows, key=lambda item: int(item["end_monotonic_ns"]))


def join_with_producer(
    capture: dict[str, object],
    logs: dict[str, object],
) -> dict[str, object]:
    summary = capture.get("summary")
    if not isinstance(summary, dict):
        raise ValueError("missing_virgl_summary")
    start_ns = int(summary["window_start_monotonic_ns"])
    end_ns = int(summary["window_end_monotonic_ns"])
    entries = logs.get("entries")
    if not isinstance(entries, list):
        raise ValueError("invalid_agent_log_bundle")
    windows = producer_windows(entries)
    overlapping = [
        window
        for window in windows
        if bool(window["valid"])
        and int(window["end_monotonic_ns"]) >= start_ns
        and int(window["start_monotonic_ns"]) <= end_ns
    ]
    fully_contained = [
        window
        for window in overlapping
        if int(window["start_monotonic_ns"]) >= start_ns
        and int(window["end_monotonic_ns"]) <= end_ns
    ]
    selected = fully_contained or overlapping
    interval_count = sum(int(window["interval_count"]) for window in selected)
    elapsed_ns = sum(int(window["elapsed_ns"]) for window in selected)
    status = (
        "summary_window_correlated_not_per_frame"
        if selected
        else "unavailable_no_canonical_producer_window"
    )
    return {
        "schema_version": 1,
        "capture_mode": capture.get("capture_mode"),
        "run_id": capture.get("run_id"),
        "target": capture.get("target"),
        "virgl_worker": summary,
        "canonical_ingress": capture.get("ingress"),
        "producer_correlation": {
            "status": status,
            "fully_contained_window_count": len(fully_contained),
            "overlapping_window_count": len(overlapping),
            "selected_windows": selected,
            "producer_interval_count": interval_count,
            "aggregate_produced_fps": round(
                interval_count * 1_000_000_000.0 / elapsed_ns, 6
            )
            if elapsed_ns
            else None,
            "per_frame_assignment_pct": None,
        },
        "decision": {
            "virgl_modification_justified": False,
            "reason": (
                "This slice distinguishes worker CPU, scheduler delay, and "
                "unclassified blocked/idle time but does not yet separate "
                "transport, host GPU execution, or fence completion."
            ),
            "next_measurement": (
                "Carry producer sequence identity through a bounded BURST "
                "record before considering source-level VirGL instrumentation."
            ),
        },
        "limitations": capture.get("limitations"),
    }


def render_report(result: dict[str, object]) -> str:
    worker = result["virgl_worker"]
    correlation = result["producer_correlation"]
    decision = result["decision"]
    assert isinstance(worker, dict)
    assert isinstance(correlation, dict)
    assert isinstance(decision, dict)
    return "\n".join(
        [
            "# VirGL Worker SUMMARY/BURST Observation",
            "",
            "- Capture mode: non-invasive procfs scheduling sampler; no ptrace and no VirGL modification.",
            f"- Samples: {worker['sample_count']} over {int(worker['duration_ns']) / 1_000_000_000:.3f} seconds.",
            f"- Worker CPU: {worker['worker_one_core_utilization_pct']}% of one core; {worker['worker_device_capacity_pct']}% of device CPU capacity.",
            f"- Main-thread runqueue delay: {int(worker['main_thread_runqueue_wait_ns']) / 1_000_000:.3f} ms.",
            f"- Main-thread blocked or idle time: {int(worker['main_thread_blocked_or_idle_ns']) / 1_000_000:.3f} ms; this is not yet attributable to transport, GPU, or fences.",
            f"- Sampler cost: {worker['sampler_one_core_utilization_pct']}% of one core; {worker['sampler_device_capacity_pct']}% of device CPU capacity.",
            f"- Producer correlation: {correlation['status']} ({correlation['producer_interval_count']} intervals).",
            "",
            "## Decision",
            "",
            f"- VirGL modification justified: {str(decision['virgl_modification_justified']).lower()}.",
            f"- Reason: {decision['reason']}",
            f"- Next measurement: {decision['next_measurement']}",
            "",
        ]
    )


def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    sample = subparsers.add_parser("sample")
    sample.add_argument("--duration-seconds", type=float, default=5.0)
    sample.add_argument("--interval-ms", type=int, default=20)
    sample.add_argument("--run-id", required=True)
    sample.add_argument("--ingress-port", type=int, default=8100)
    sample.add_argument("--producer-log", type=Path)
    sample.add_argument("--output", type=Path, required=True)

    analyze = subparsers.add_parser("analyze")
    analyze.add_argument("--sample-json", type=Path, required=True)
    analyze.add_argument("--agent-logs", type=Path, required=True)
    analyze.add_argument("--output", type=Path, required=True)
    analyze.add_argument("--report", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.action == "sample":
        if (
            args.duration_seconds <= 0
            or args.duration_seconds > 30
            or args.interval_ms < 5
            or args.interval_ms > 1000
            or args.ingress_port < 1
            or args.ingress_port > 65535
        ):
            raise SystemExit("invalid_bounded_sampler_arguments")
        result = collect(
            duration_seconds=args.duration_seconds,
            interval_ms=args.interval_ms,
            run_id=args.run_id,
            ingress_port=args.ingress_port,
            producer_log=args.producer_log,
        )
        save_json(args.output, result)
        print(result["ingress"]["record"])
        return 0 if bool(result["ingress"]["delivered"]) else 2

    capture = json.loads(args.sample_json.read_text(encoding="utf-8"))
    logs = json.loads(args.agent_logs.read_text(encoding="utf-8"))
    result = join_with_producer(capture, logs)
    save_json(args.output, result)
    args.report.write_text(render_report(result), encoding="utf-8")
    print(json.dumps(result["decision"], indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"virgl worker summary failed: {error}", file=os.sys.stderr)
        raise SystemExit(1)
