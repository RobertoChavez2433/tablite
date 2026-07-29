from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.quality.analyzer import (
    ARCHITECTURE_CHECKS,
    FAST_ANALYZER_BUDGET_MS,
    analyze_architecture,
    analyze_architecture_timed,
)


class ArchitectureAnalyzerTest(unittest.TestCase):
    def _analyze(self, files: dict[str, str]):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            policy = root / "scripts/verify-artifact-integrity.ps1"
            policy.parent.mkdir(parents=True, exist_ok=True)
            policy.write_text(
                "\n".join(
                    (
                        "runelite_client_gamepack_gpu_plugin_must_match_official = $true",
                        "rlawt_java_classes_must_match_upstream = $true",
                        "gameplay_automation_allowed = $false",
                        "protocol_or_packet_changes_allowed = $false",
                    )
                ),
                encoding="utf-8",
            )
            for relative, source in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
            return analyze_architecture(root)

    def test_domain_android_import_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/domain/Bad.kt":
                    "package com.runelitetablet.domain\nimport android.content.Context\n"
            }
        )
        self.assertEqual(["TBL001"], [item.code for item in diagnostics])

    def test_ui_infrastructure_import_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/ui/Bad.kt":
                    "package com.runelitetablet.ui\n"
                    "import com.runelitetablet.termux.TermuxCommandRunner\n"
            }
        )
        self.assertEqual(["TBL002"], [item.code for item in diagnostics])

    def test_infrastructure_construction_outside_di_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/session/Bad.kt":
                    "package com.runelitetablet.session\n"
                    "fun bad(context: Any) = TermuxCommandRunner(context)\n"
            }
        )
        self.assertEqual(["TBL003"], [item.code for item in diagnostics])

    def test_composition_root_may_construct_infrastructure(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/di/AppContainer.kt":
                    "package com.runelitetablet.di\n"
                    "fun ok(context: Any) = TermuxCommandRunner(context)\n"
            }
        )
        self.assertEqual([], diagnostics)

    def test_runelite_agent_injection_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/assets/scripts/launch-runelite.sh":
                    "#!/bin/sh\njava -javaagent:probe.jar -jar RuneLite.jar\n"
            }
        )
        self.assertEqual(["TBL005"], [item.code for item in diagnostics])

    def test_ambiguous_consumer_fps_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "scripts/metric-probe.ps1":
                    "$summary.xlorie_consumer_fps = 42\n"
            }
        )
        self.assertEqual(["TBL007"], [item.code for item in diagnostics])

    def test_canonical_pipeline_fps_stages_are_allowed(self) -> None:
        diagnostics = self._analyze(
            {
                "scripts/metric-probe.ps1":
                    "produced_fps accepted_fps submitted_fps presented_fps\n"
            }
        )
        self.assertEqual([], diagnostics)

    def test_cadence_rate_without_unit_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/Bad.kt":
                    'logger.log("host_vsync_callback_rate=120")\n'
            }
        )
        self.assertEqual(["TBL007"], [item.code for item in diagnostics])

    def test_capture_input_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "scripts/capture-frame-pipeline.ps1":
                    'adb shell input tap 10 20\n'
            }
        )
        self.assertEqual(["TBL008"], [item.code for item in diagnostics])

    def test_capture_exporter_lifecycle_outside_telemetry_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/setup/Bad.kt":
                    "package com.runelitetablet.setup\nTelemetryExporter.start(context, session)\n"
            }
        )
        self.assertEqual(["TBL011"], [item.code for item in diagnostics])

    def test_incomplete_frame_handoff_contract_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c":
                    'log("XlorieHandoffV1:");\n'
            }
        )
        self.assertTrue(diagnostics)
        frame_contract = [item for item in diagnostics if item.code == "TBL012"]
        self.assertTrue(frame_contract)
        self.assertTrue(all(item.rule == "frame_handoff_contract" for item in frame_contract))

    def test_incomplete_producer_presentation_identity_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "third_party/rlawt/rlawt_nix.c":
                    'XInternAtom(display, "_RLT_FRAME_SEQ_V1", False);\n'
            }
        )
        self.assertTrue(diagnostics)
        identity_contract = [item for item in diagnostics if item.code == "TBL013"]
        self.assertTrue(identity_contract)
        self.assertTrue(
            all(
                item.rule == "producer_presentation_identity"
                for item in identity_contract
            )
        )

    def test_script_batch_transport_must_use_stdin(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/setup/ScriptManager.kt":
                    'val program = "large"\n'
                    'execute(arguments = arrayOf("-c", program))\n'
            }
        )
        self.assertTrue(diagnostics)
        self.assertTrue(all(item.code == "TBL014" for item in diagnostics))

    def test_direct_android_logging_outside_adapter_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/setup/Bad.kt":
                    "package com.runelitetablet.setup\n"
                    "import android.util.Log\n"
                    'fun bad() = Log.e("RLT", "bypass")\n'
            }
        )
        self.assertTrue(any(item.code == "TBL015" for item in diagnostics))

    def test_undeclared_literal_observability_tag_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/setup/Bad.kt":
                    "package com.runelitetablet.setup\n"
                    'fun bad() = AppLog.i("NEW_ISLAND", "bypass")\n'
            }
        )
        self.assertEqual(["TBL016"], [item.code for item in diagnostics])

    def test_incomplete_telemetry_overhead_contract_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "tools/telemetry_overhead.py":
                    "THROUGHPUT_LOSS_LIMIT_PERCENT = 2.0\n"
            }
        )
        overhead = [item for item in diagnostics if item.code == "TBL018"]
        self.assertTrue(overhead)
        self.assertTrue(
            all(item.rule == "telemetry_overhead_contract" for item in overhead)
        )

    def test_incomplete_telemetry_self_observation_contract_is_rejected(self) -> None:
        diagnostics = self._analyze(
            {
                "runelite-tablet/app/src/main/java/com/runelitetablet/"
                "telemetry/TelemetryCostTracker.kt":
                    'FRAME_RECORDER("frame_recorder")\n'
            }
        )
        self_cost = [item for item in diagnostics if item.code == "TBL019"]
        self.assertTrue(self_cost)
        self.assertTrue(
            all(
                item.rule == "telemetry_self_observation_contract"
                for item in self_cost
            )
        )

    def test_fast_analyzer_contract_is_strictly_under_sixty_seconds(self) -> None:
        self.assertEqual(60_000.0, FAST_ANALYZER_BUDGET_MS)

    def test_every_architecture_rule_has_an_attributable_timing(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            policy = root / "scripts/verify-artifact-integrity.ps1"
            policy.parent.mkdir(parents=True, exist_ok=True)
            policy.write_text(
                "\n".join(
                    (
                        "runelite_client_gamepack_gpu_plugin_must_match_official = $true",
                        "rlawt_java_classes_must_match_upstream = $true",
                        "gameplay_automation_allowed = $false",
                        "protocol_or_packet_changes_allowed = $false",
                    )
                ),
                encoding="utf-8",
            )
            diagnostics, timings = analyze_architecture_timed(root)

        self.assertEqual([], diagnostics)
        self.assertEqual(
            [check.__name__ for check in ARCHITECTURE_CHECKS],
            [timing.name for timing in timings[:-1]],
        )
        self.assertEqual("architecture total", timings[-1].name)
        self.assertTrue(all(timing.passed for timing in timings))


if __name__ == "__main__":
    unittest.main()
