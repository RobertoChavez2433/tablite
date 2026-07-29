#!/usr/bin/env python3
"""Analyze an invasive strace capture of the active VirGL renderer worker.

This is deliberately an observation tool, not a frame-time estimator. Syscall
wall times from different threads overlap and GPU driver call duration is not
equivalent to GPU execution duration.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


LINE_RE = re.compile(
    r"^(?P<tid>\d+)\s+(?P<timestamp>\d+(?:\.\d+)?)\s+(?P<body>.*)$"
)
SYSCALL_RE = re.compile(r"^(?P<name>[A-Za-z_][A-Za-z0-9_]*)\(")
UNFINISHED_RE = re.compile(r"\s+<unfinished \.\.\.>$")
RESUMED_RE = re.compile(r"^<\.\.\. (?P<name>[A-Za-z_][A-Za-z0-9_]*) resumed>(?P<tail>.*)$")
DURATION_RE = re.compile(r"\s+<(?P<seconds>\d+(?:\.\d+)?)>$")
RESULT_RE = re.compile(r"=\s+(-?\d+)(?=\s|$)")
UNIX_SOCKET_RE = re.compile(r"UNIX-STREAM:\[(?P<inode>\d+)\]")

IO_SYSCALLS = {
    "read",
    "readv",
    "recvfrom",
    "recvmsg",
    "write",
    "writev",
    "sendto",
    "sendmsg",
}
POLL_SYSCALLS = {"poll", "ppoll", "epoll_wait", "epoll_pwait"}


@dataclass(frozen=True)
class PendingCall:
    tid: int
    timestamp: float
    name: str
    body: str


@dataclass(frozen=True)
class ObservedCall:
    tid: int
    timestamp: float
    name: str
    body: str
    duration_seconds: float
    result: int | None
    category: str
    socket_inode: str | None

    @property
    def transferred_bytes(self) -> int:
        if self.name not in IO_SYSCALLS or self.result is None:
            return 0
        return max(0, self.result)


def _has_zero_timeout(body: str) -> bool:
    return (
        "{tv_sec=0, tv_nsec=0}" in body
        or re.search(r"\bpoll\([^)]*,\s*\d+,\s*0\)", body) is not None
    )


def classify_call(name: str, body: str) -> str:
    if "UNIX-STREAM:[" in body and name in IO_SYSCALLS:
        return "unix_socket_io"
    if "/dev/mali0" in body:
        if name == "ioctl":
            return "mali_driver_ioctl"
        if name in POLL_SYSCALLS:
            return "mali_event_wait"
        if name in {"read", "readv"}:
            return "mali_event_read"
        return "mali_driver_other"
    if "sync_file" in body and name in POLL_SYSCALLS:
        return "native_fence_probe" if _has_zero_timeout(body) else "native_fence_wait"
    if "/proc/ged" in body:
        return "ged_driver_ioctl" if name == "ioctl" else "ged_driver_other"
    if "eventfd-count=" in body:
        if name in POLL_SYSCALLS:
            return "eventfd_wait"
        if name in IO_SYSCALLS:
            return "eventfd_io"
        return "eventfd_other"
    if "binder" in body:
        return "binder_driver"
    if "trace_marker" in body:
        return "trace_marker_io"
    if name == "futex" and ("FUTEX_WAIT" in body or "FUTEX_LOCK" in body):
        return "thread_sync_wait"
    return "other_traced"


def _parse_completed(
    tid: int,
    timestamp: float,
    name: str,
    body: str,
) -> ObservedCall | None:
    duration_match = DURATION_RE.search(body)
    if duration_match is None:
        return None
    duration_seconds = float(duration_match.group("seconds"))
    result_matches = RESULT_RE.findall(body[: duration_match.start()])
    result = int(result_matches[-1]) if result_matches else None
    socket_match = UNIX_SOCKET_RE.search(body)
    return ObservedCall(
        tid=tid,
        timestamp=timestamp,
        name=name,
        body=body,
        duration_seconds=duration_seconds,
        result=result,
        category=classify_call(name, body),
        socket_inode=socket_match.group("inode") if socket_match else None,
    )


def parse_strace_lines(
    lines: Iterable[str],
) -> tuple[list[ObservedCall], list[PendingCall], dict[str, int]]:
    calls: list[ObservedCall] = []
    pending: dict[int, PendingCall] = {}
    counters: defaultdict[str, int] = defaultdict(int)

    for raw_line in lines:
        counters["input_lines"] += 1
        line_match = LINE_RE.match(raw_line.rstrip("\r\n"))
        if line_match is None:
            counters["unparsed_lines"] += 1
            continue

        tid = int(line_match.group("tid"))
        timestamp = float(line_match.group("timestamp"))
        body = line_match.group("body")
        resumed_match = RESUMED_RE.match(body)
        if resumed_match is not None:
            prior = pending.pop(tid, None)
            if prior is None or prior.name != resumed_match.group("name"):
                counters["orphan_resumed_lines"] += 1
                continue
            combined = UNFINISHED_RE.sub("", prior.body) + resumed_match.group("tail")
            call = _parse_completed(prior.tid, prior.timestamp, prior.name, combined)
            if call is None:
                counters["completed_without_duration"] += 1
            else:
                calls.append(call)
                counters["completed_calls"] += 1
            continue

        syscall_match = SYSCALL_RE.match(body)
        if syscall_match is None:
            counters["unparsed_lines"] += 1
            continue
        name = syscall_match.group("name")
        if UNFINISHED_RE.search(body):
            if tid in pending:
                counters["overwritten_pending_calls"] += 1
            pending[tid] = PendingCall(tid, timestamp, name, body)
            counters["unfinished_entries"] += 1
            continue

        call = _parse_completed(tid, timestamp, name, body)
        if call is None:
            counters["completed_without_duration"] += 1
        else:
            calls.append(call)
            counters["completed_calls"] += 1

    counters["unfinished_at_end"] = len(pending)
    return calls, list(pending.values()), dict(counters)


def percentile(values: Sequence[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[rank]


def summarize_calls(calls: Sequence[ObservedCall], main_pid: int | None) -> dict[str, object]:
    durations_ms = [call.duration_seconds * 1000.0 for call in calls]
    main_calls = [call for call in calls if main_pid is not None and call.tid == main_pid]
    main_durations_ms = [call.duration_seconds * 1000.0 for call in main_calls]
    return {
        "calls": len(calls),
        "threads": sorted({call.tid for call in calls}),
        "transferred_bytes": sum(call.transferred_bytes for call in calls),
        "syscall_wall_ms": round(sum(durations_ms), 6),
        "p50_ms": _rounded(percentile(durations_ms, 0.50)),
        "p95_ms": _rounded(percentile(durations_ms, 0.95)),
        "max_ms": _rounded(max(durations_ms) if durations_ms else None),
        "calls_over_1ms": sum(duration > 1.0 for duration in durations_ms),
        "main_thread_calls": len(main_calls),
        "main_thread_syscall_wall_ms": round(sum(main_durations_ms), 6),
        "main_thread_p95_ms": _rounded(percentile(main_durations_ms, 0.95)),
        "main_thread_max_ms": _rounded(max(main_durations_ms) if main_durations_ms else None),
    }


def _rounded(value: float | None) -> float | None:
    return round(value, 6) if value is not None else None


def build_summary(
    calls: Sequence[ObservedCall],
    pending: Sequence[PendingCall],
    parse_counters: dict[str, int],
    main_pid: int | None,
    trace_files: Sequence[Path],
    metadata: dict[str, str] | None = None,
) -> dict[str, object]:
    by_category: defaultdict[str, list[ObservedCall]] = defaultdict(list)
    by_socket: defaultdict[str, list[ObservedCall]] = defaultdict(list)
    for call in calls:
        by_category[call.category].append(call)
        if call.socket_inode is not None:
            by_socket[call.socket_inode].append(call)

    socket_summaries = []
    for inode, socket_calls in by_socket.items():
        metrics = summarize_calls(socket_calls, main_pid)
        metrics["inode"] = inode
        socket_summaries.append(metrics)
    socket_summaries.sort(
        key=lambda item: (
            int(item["transferred_bytes"]),
            int(item["main_thread_calls"]),
            int(item["calls"]),
        ),
        reverse=True,
    )

    candidate_socket = socket_summaries[0] if socket_summaries else None
    timestamps = [call.timestamp for call in calls]
    return {
        "schema_version": 1,
        "capture_mode": "invasive_deep_syscall_trace",
        "trace_files": [str(path) for path in trace_files],
        "target_main_pid": main_pid,
        "clock_alignment": _clock_alignment(metadata or {}),
        "observed_window_seconds": _rounded(max(timestamps) - min(timestamps))
        if len(timestamps) > 1
        else None,
        "parse": {
            **parse_counters,
            "pending_categories": _pending_category_counts(pending),
        },
        "categories": {
            category: summarize_calls(category_calls, main_pid)
            for category, category_calls in sorted(by_category.items())
        },
        "unix_sockets": socket_summaries,
        "candidate_vtest_transport": {
            "selection": "highest_observed_unix_socket_payload",
            "confidence": "heuristic_until_source_level_vtest_instrumentation",
            "socket": candidate_socket,
        },
        "limitations": [
            "ptrace is invasive; captured throughput is diagnostic and must not be used as a benchmark result",
            "syscall wall times from different threads overlap and must not be added into a frame waterfall",
            "Mali ioctl duration measures host CPU/driver interaction, not GPU execution time",
            "Mali event and native-fence waits can include idle worker time outside the serialized producer boundary",
            "the vtest socket is selected heuristically until source-level command instrumentation carries producer sequence identity",
            "calls still blocked when tracing stopped are counted as incomplete and have no duration",
            "syscall string payloads are truncated to one byte; byte totals come from return values",
        ],
    }


def _clock_alignment(metadata: dict[str, str]) -> dict[str, object]:
    anchors: dict[str, dict[str, int]] = {}
    for side in ("start", "end"):
        try:
            realtime_before = int(metadata[f"clock_{side}_realtime_before_ns"])
            monotonic = int(metadata[f"clock_{side}_monotonic_ns"])
            realtime_after = int(metadata[f"clock_{side}_realtime_after_ns"])
        except (KeyError, ValueError):
            continue
        midpoint = realtime_before + ((realtime_after - realtime_before) // 2)
        anchors[side] = {
            "realtime_before_ns": realtime_before,
            "monotonic_ns": monotonic,
            "realtime_after_ns": realtime_after,
            "realtime_minus_monotonic_ns": midpoint - monotonic,
            "uncertainty_ns": max(0, realtime_after - realtime_before),
        }
    drift_ns = None
    if "start" in anchors and "end" in anchors:
        drift_ns = (
            anchors["end"]["realtime_minus_monotonic_ns"]
            - anchors["start"]["realtime_minus_monotonic_ns"]
        )
    return {
        "status": "bounded_realtime_to_monotonic_anchor" if anchors else "unavailable",
        "anchors": anchors,
        "offset_drift_ns": drift_ns,
    }


def _pending_category_counts(pending: Sequence[PendingCall]) -> dict[str, int]:
    counts: defaultdict[str, int] = defaultdict(int)
    for call in pending:
        counts[classify_call(call.name, call.body)] += 1
    return dict(sorted(counts.items()))


def render_report(summary: dict[str, object]) -> str:
    categories = summary["categories"]
    assert isinstance(categories, dict)
    lines = [
        "# VirGL Seam Capture",
        "",
        "- Mode: invasive DEEP syscall trace; rates from this capture are not benchmark results.",
        f"- Target main PID: {summary['target_main_pid']}",
        f"- Observed parsed window: {summary['observed_window_seconds']} seconds",
        f"- Completed calls: {summary['parse']['completed_calls']}",
        f"- Calls still blocked at stop: {summary['parse']['unfinished_at_end']}",
        "",
        "| Observable seam | Calls | Bytes | Syscall wall ms | p95 ms | Max ms | Main-thread wall ms |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for category, metrics in categories.items():
        lines.append(
            "| {name} | {calls} | {bytes} | {wall} | {p95} | {maximum} | {main} |".format(
                name=category,
                calls=metrics["calls"],
                bytes=metrics["transferred_bytes"],
                wall=metrics["syscall_wall_ms"],
                p95=_display(metrics["p95_ms"]),
                maximum=_display(metrics["max_ms"]),
                main=metrics["main_thread_syscall_wall_ms"],
            )
        )
    candidate = summary["candidate_vtest_transport"]["socket"]
    lines.extend(["", "## Interpretation", ""])
    if candidate is None:
        lines.append("- No Unix-socket payload was observed; vtest transport is unresolved.")
    else:
        lines.append(
            "- Candidate vtest socket inode {inode}: {calls} calls, {bytes} bytes, "
            "{wall} ms observed syscall wall time ({main} ms on the target main thread).".format(
                inode=candidate["inode"],
                calls=candidate["calls"],
                bytes=candidate["transferred_bytes"],
                wall=candidate["syscall_wall_ms"],
                main=candidate["main_thread_syscall_wall_ms"],
            )
        )
    lines.extend(
        [
            "- Mali ioctl time is driver interaction only; GPU execution remains unresolved until source-level fence correlation or AGI counters are joined.",
            "- Native-fence and Mali event waits may run on worker threads and overlap other work.",
            "",
            "## Limitations",
            "",
        ]
    )
    for limitation in summary["limitations"]:
        lines.append(f"- {limitation}")
    lines.append("")
    return "\n".join(lines)


def _display(value: object) -> str:
    return "unavailable" if value is None else str(value)


def load_trace_files(paths: Sequence[Path]) -> Iterable[str]:
    for path in paths:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            yield from handle


def load_metadata(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    metadata: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            metadata[key] = value
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", nargs="+", required=True, type=Path)
    parser.add_argument("--main-pid", type=int)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    trace_files: list[Path] = []
    for path in args.input:
        if path.is_dir():
            trace_files.extend(sorted(path.glob("virgl.strace*")))
        elif path.exists():
            trace_files.append(path)
    if not trace_files:
        parser.error("no trace files found")

    calls, pending, counters = parse_strace_lines(load_trace_files(trace_files))
    summary = build_summary(
        calls,
        pending,
        counters,
        args.main_pid,
        trace_files,
        load_metadata(args.metadata),
    )
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if args.report is not None:
        args.report.write_text(render_report(summary), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
