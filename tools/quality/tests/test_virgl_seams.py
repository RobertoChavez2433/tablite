import unittest
from pathlib import Path

from tools.virgl_seams import build_summary, parse_strace_lines


class VirglSeamAnalyzerTest(unittest.TestCase):
    def test_reconstructs_resumed_calls_and_separates_observable_seams(self):
        lines = [
            '30260 10.000000 read(4<UNIX-STREAM:[123]>, "abcd", 4) = 4 <0.001000>',
            '30260 10.002000 write(4<UNIX-STREAM:[123]>, "xy", 2 <unfinished ...>',
            '30260 10.004000 <... write resumed>) = 2 <0.002000>',
            "30260 10.005000 ioctl(5</dev/mali0<char 10:96>>, 0x1, 0) = 0 <0.003000>",
            "30260 10.009000 ppoll([{fd=7<anon_inode:sync_file>, events=POLLIN}], 1, "
            "{tv_sec=0, tv_nsec=0}, NULL, 0) = 1 <0.000100>",
            "30308 10.010000 ppoll([{fd=8<anon_inode:sync_file>, events=POLLIN}], 1, "
            "NULL, NULL, 0 <unfinished ...>",
            "30308 10.020000 <... ppoll resumed>) = 1 ([{fd=8, revents=POLLIN}]) <0.010000>",
            "30286 10.021000 ppoll([{fd=5</dev/mali0<char 10:96>>, events=POLLIN}], "
            "1, NULL, NULL, 0 <unfinished ...>",
            "30260 10.022000 ioctl(31</proc/ged>, 0x1, 0) = 0 <0.000500>",
            "30260 10.023000 ppoll([{fd=29<{eventfd-count=0, eventfd-id=3}>, "
            "events=POLLIN}], 1, NULL, NULL, 0) = 1 <0.004000>",
        ]

        calls, pending, counters = parse_strace_lines(lines)
        metadata = {
            "clock_start_realtime_before_ns": "1000000",
            "clock_start_monotonic_ns": "400000",
            "clock_start_realtime_after_ns": "1000200",
            "clock_end_realtime_before_ns": "2000000",
            "clock_end_monotonic_ns": "1400000",
            "clock_end_realtime_after_ns": "2000200",
        }
        summary = build_summary(
            calls,
            pending,
            counters,
            30260,
            [Path("trace")],
            metadata,
        )

        self.assertEqual(7, counters["completed_calls"])
        self.assertEqual(1, counters["unfinished_at_end"])
        self.assertEqual(6, summary["categories"]["unix_socket_io"]["transferred_bytes"])
        self.assertEqual(123, int(summary["candidate_vtest_transport"]["socket"]["inode"]))
        self.assertEqual(3.0, summary["categories"]["mali_driver_ioctl"]["max_ms"])
        self.assertEqual(0.1, summary["categories"]["native_fence_probe"]["max_ms"])
        self.assertEqual(10.0, summary["categories"]["native_fence_wait"]["max_ms"])
        self.assertEqual(0.5, summary["categories"]["ged_driver_ioctl"]["max_ms"])
        self.assertEqual(4.0, summary["categories"]["eventfd_wait"]["max_ms"])
        self.assertEqual(
            "bounded_realtime_to_monotonic_anchor",
            summary["clock_alignment"]["status"],
        )
        self.assertEqual(0, summary["clock_alignment"]["offset_drift_ns"])
        self.assertEqual(
            {"mali_event_wait": 1},
            summary["parse"]["pending_categories"],
        )

    def test_unparseable_and_orphan_resumed_lines_are_counted(self):
        lines = [
            "garbage",
            "12 1.000000 <... read resumed>) = 1 <0.001000>",
            '12 1.100000 read(3</tmp/file>, "x", 1) = 1',
        ]

        calls, pending, counters = parse_strace_lines(lines)

        self.assertEqual([], calls)
        self.assertEqual([], pending)
        self.assertEqual(1, counters["unparsed_lines"])
        self.assertEqual(1, counters["orphan_resumed_lines"])
        self.assertEqual(1, counters["completed_without_duration"])


if __name__ == "__main__":
    unittest.main()
