#!/usr/bin/env python3
"""Extract an external per-frame presentation cadence from a Perfetto trace.

The Android SurfaceFlinger FrameTimeline data source observes both telemetry
OFF and SUMMARY without adding code to either renderer path. This tool keeps
that common observer separate from Tablite's producer telemetry so an
OFF-versus-SUMMARY overhead gate can compare the same per-frame measurement.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import urllib.request
from typing import Any, Iterable


TRACE_PROCESSOR_URL = "https://get.perfetto.dev/trace_processor"
SURFACEFLINGER_PROCESS = "/system/bin/surfaceflinger"
QUERY = f"""
SELECT
  a.ts,
  a.dur,
  a.display_frame_token,
  COALESCE(a.present_type, '') AS present_type,
  COALESCE(a.jank_type, '') AS jank_type
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
WHERE p.name = '{SURFACEFLINGER_PROCESS}'
  AND a.surface_frame_token IS NULL
ORDER BY a.ts;
""".strip()


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return ordered[index]


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(value, 6)


def _stats(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "mean": _rounded(statistics.fmean(values)) if values else None,
        "p50": _rounded(percentile(values, 0.50)),
        "p90": _rounded(percentile(values, 0.90)),
        "p95": _rounded(percentile(values, 0.95)),
        "p99": _rounded(percentile(values, 0.99)),
        "max": _rounded(max(values)) if values else None,
    }


def summarize_frames(rows: Iterable[dict[str, str]]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    frames: list[dict[str, Any]] = []
    for row in rows:
        frames.append(
            {
                "ts": int(row["ts"]),
                "dur": int(row["dur"]),
                "display_frame_token": int(row["display_frame_token"]),
                "present_type": row.get("present_type", ""),
                "jank_type": row.get("jank_type", ""),
            }
        )
    frames.sort(key=lambda item: item["ts"])
    if len(frames) < 2:
        raise ValueError("SurfaceFlinger FrameTimeline contained fewer than two display frames")

    intervals: list[dict[str, Any]] = []
    for previous, current in zip(frames, frames[1:]):
        interval_ms = (current["ts"] - previous["ts"]) / 1_000_000.0
        if interval_ms <= 0:
            raise ValueError("SurfaceFlinger FrameTimeline timestamps are not strictly increasing")
        intervals.append(
            {
                "ts": current["ts"],
                "display_frame_token": current["display_frame_token"],
                "interval_ms": round(interval_ms, 6),
                "dur_ms": round(current["dur"] / 1_000_000.0, 6),
                "present_type": current["present_type"],
                "jank_type": current["jank_type"],
            }
        )

    interval_values = [item["interval_ms"] for item in intervals]
    elapsed_ns = frames[-1]["ts"] - frames[0]["ts"]
    frame_tokens = [item["display_frame_token"] for item in frames]
    timestamps = [item["ts"] for item in frames]
    jank_frames = sum(
        1
        for item in frames
        if item["jank_type"] not in ("", "None", "Unspecified", "No Jank")
    )
    summary = {
        "schema_version": 1,
        "source": "android.surfaceflinger.frametimeline",
        "measurement_scope": (
            "external display-frame cadence shared by telemetry OFF and SUMMARY; "
            "not producer-correlated"
        ),
        "selection": {
            "process_name": SURFACEFLINGER_PROCESS,
            "surface_frame_token": None,
        },
        "frame_count": len(frames),
        "interval_count": len(intervals),
        "first_timestamp_ns": frames[0]["ts"],
        "last_timestamp_ns": frames[-1]["ts"],
        "elapsed_ns": elapsed_ns,
        "throughput_hz": round(len(intervals) * 1_000_000_000.0 / elapsed_ns, 6),
        "interval_ms": _stats(interval_values),
        "duplicate_timestamp_count": len(timestamps) - len(set(timestamps)),
        "duplicate_display_frame_token_count": len(frame_tokens) - len(set(frame_tokens)),
        "jank_frame_count": jank_frames,
        "jank_frame_percent": round(100.0 * jank_frames / len(frames), 6),
    }
    return summary, intervals


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _command_for_path(path: Path) -> list[str]:
    try:
        prefix = path.read_bytes()[:128]
    except OSError:
        prefix = b""
    if prefix.startswith(b"#!") and b"python" in prefix.lower():
        return [sys.executable, str(path)]
    return [str(path)]


def _cached_trace_processor() -> Path | None:
    candidates: list[Path] = []
    cache_root = Path.home() / ".local" / "share" / "perfetto" / "prebuilts"
    if cache_root.exists():
        candidates.extend(cache_root.glob("trace_processor_shell-*"))
        candidates.extend(cache_root.glob("trace_processor_shell-*.exe"))
    return max(candidates, key=lambda path: path.stat().st_mtime_ns) if candidates else None


def resolve_trace_processor(spec: str | None, bootstrap: bool) -> tuple[list[str], Path, str]:
    requested = spec or os.environ.get("RLT_TRACE_PROCESSOR")
    if requested:
        requested_path = Path(requested).expanduser()
        resolved = requested_path if requested_path.exists() else None
        if resolved is None:
            found = shutil.which(requested)
            resolved = Path(found) if found else None
        if resolved is None:
            raise FileNotFoundError(f"Trace Processor was not found: {requested}")
        return _command_for_path(resolved.resolve()), resolved.resolve(), "explicit"

    found = shutil.which("trace_processor_shell")
    if found:
        resolved = Path(found).resolve()
        return [str(resolved)], resolved, "path"

    cached = _cached_trace_processor()
    if cached:
        resolved = cached.resolve()
        return [str(resolved)], resolved, "perfetto_cache"

    if not bootstrap:
        raise FileNotFoundError(
            "Trace Processor is unavailable. Set RLT_TRACE_PROCESSOR or rerun with --bootstrap."
        )

    cache_root = Path(
        os.environ.get(
            "LOCALAPPDATA",
            str(Path.home() / ".cache"),
        )
    ) / "Tablite" / "perfetto"
    cache_root.mkdir(parents=True, exist_ok=True)
    wrapper = cache_root / "trace_processor"
    with urllib.request.urlopen(TRACE_PROCESSOR_URL, timeout=30) as response:
        wrapper.write_bytes(response.read())
    return [sys.executable, str(wrapper)], wrapper, "official_bootstrap_wrapper"


def analyze_trace(
    trace: Path,
    trace_processor: str | None,
    bootstrap: bool,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    command, processor_path, resolution = resolve_trace_processor(trace_processor, bootstrap)
    query_result = subprocess.run(
        [*command, "query", str(trace), QUERY],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if query_result.returncode != 0:
        raise RuntimeError(
            f"Trace Processor failed ({query_result.returncode}): {query_result.stderr.strip()}"
        )
    rows = list(csv.DictReader(io.StringIO(query_result.stdout)))
    summary, intervals = summarize_frames(rows)
    version_result = subprocess.run(
        [*command, "-v"],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    summary["trace_processor"] = {
        "resolution": resolution,
        "version": (version_result.stdout or version_result.stderr).strip(),
        "path": str(processor_path),
        "sha256": _sha256(processor_path),
        "bootstrap_url": TRACE_PROCESSOR_URL if resolution == "official_bootstrap_wrapper" else None,
    }
    return summary, intervals


def write_outputs(
    summary: dict[str, Any],
    intervals: list[dict[str, Any]],
    output: Path,
    intervals_output: Path,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    with intervals_output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(intervals[0]))
        writer.writeheader()
        writer.writerows(intervals)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--intervals-output", type=Path, required=True)
    parser.add_argument("--trace-processor")
    parser.add_argument("--bootstrap", action="store_true")
    args = parser.parse_args()

    summary, intervals = analyze_trace(
        args.trace.resolve(),
        args.trace_processor,
        args.bootstrap,
    )
    write_outputs(summary, intervals, args.output.resolve(), args.intervals_output.resolve())
    print(
        json.dumps(
            {
                "frame_count": summary["frame_count"],
                "throughput_hz": summary["throughput_hz"],
                "p95_ms": summary["interval_ms"]["p95"],
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
