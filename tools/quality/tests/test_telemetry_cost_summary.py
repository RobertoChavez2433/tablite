from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.telemetry_cost_summary import build_report, parse_native_record


class TelemetryCostSummaryTest(unittest.TestCase):
    def test_native_record_requires_canonical_fields(self) -> None:
        record = parse_native_record(
            "[rlawt] RLT_TELEMETRY_COST_V1 run_id=run mode=BURST "
            "stage=rlawt_producer outcome=measured calls=60 measured_samples=60 "
            "measured_wall_ns=1200 measured_thread_cpu_ns=900 bytes=480 dropped=0 "
            "queue_high_water_mark=0 record_emit_excluded=1"
        )
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(900, record["measured_thread_cpu_ns"])
        self.assertEqual("rlawt_producer", record["stage"])

    def test_report_joins_app_native_and_external_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "manifest.json").write_text(
                json.dumps({"run_id": "run", "telemetry_mode": "BURST"}),
                encoding="utf-8",
            )
            (root / "app" / "app-telemetry-cost-v1.json").write_text(
                json.dumps(
                    {
                        "runId": "run",
                        "mode": "BURST",
                        "measurementStride": 64,
                        "stages": [
                            {
                                "stage": "json_encode",
                                "calls": 128,
                                "measuredSamples": 2,
                                "measuredWallNs": 100,
                                "measuredThreadCpuNs": 80,
                                "estimatedWallNs": 6400,
                                "estimatedThreadCpuNs": 5120,
                                "bytes": 4096,
                                "dropped": 0,
                                "queueHighWaterMark": 0,
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (root / "runelite-native-capture-window.log").write_text(
                "RLT_TELEMETRY_COST_V1 run_id=run mode=BURST stage=virgl_host "
                "outcome=measured calls=20 measured_samples=20 "
                "measured_wall_ns=200 measured_thread_cpu_ns=150 bytes=1024 "
                "dropped=0 queue_high_water_mark=1 record_emit_excluded=1\n",
                encoding="utf-8",
            )
            (root / "frame-timeline-summary.json").write_text(
                json.dumps({"frame_count": 240, "p95_interval_ms": 12.0}),
                encoding="utf-8",
            )

            report = build_report(root)

        self.assertTrue(report["valid"])
        self.assertEqual("run", report["run_id"])
        self.assertEqual(5270, report["totals"]["estimated_thread_cpu_ns"])
        self.assertEqual(2, len(report["stages"]))
        self.assertEqual(240, report["external_frame_timeline"]["frame_count"])


if __name__ == "__main__":
    unittest.main()
