from __future__ import annotations

import pytest

from tools.perfetto_frame_timeline import percentile, summarize_frames


def frame(ts: int, token: int, jank: str = "None") -> dict[str, str]:
    return {
        "ts": str(ts),
        "dur": "8000000",
        "display_frame_token": str(token),
        "present_type": "On-time Present",
        "jank_type": jank,
    }


def test_surfaceflinger_intervals_are_external_per_frame_evidence() -> None:
    summary, intervals = summarize_frames(
        (
            frame(1_000_000_000, 10),
            frame(1_010_000_000, 11),
            frame(1_030_000_000, 12, "Buffer Stuffing"),
            frame(1_070_000_000, 13),
        )
    )

    assert summary["source"] == "android.surfaceflinger.frametimeline"
    assert summary["frame_count"] == 4
    assert summary["interval_count"] == 3
    assert summary["throughput_hz"] == pytest.approx(42.857143)
    assert summary["interval_ms"]["p50"] == 20.0
    assert summary["interval_ms"]["p95"] == 40.0
    assert summary["jank_frame_count"] == 1
    assert [item["interval_ms"] for item in intervals] == [10.0, 20.0, 40.0]


def test_nearest_rank_percentile_matches_capture_analyzer() -> None:
    assert percentile([1.0, 2.0, 3.0, 4.0], 0.50) == 2.0
    assert percentile([1.0, 2.0, 3.0, 4.0], 0.95) == 4.0


def test_non_increasing_timestamps_fail_closed() -> None:
    with pytest.raises(ValueError, match="strictly increasing"):
        summarize_frames((frame(1_000, 1), frame(1_000, 2)))


def test_too_few_frames_fail_closed() -> None:
    with pytest.raises(ValueError, match="fewer than two"):
        summarize_frames((frame(1_000, 1),))
