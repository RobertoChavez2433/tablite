#!/usr/bin/env python3
"""Canonical Tablite static-analysis entrypoint.

The repository owns architectural policy here. Language-aware tools remain
the authority for their syntax and type systems; this command invokes them
and normalizes their pass/fail result behind one developer/CI entrypoint.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True, order=True)
class Diagnostic:
    path: str
    line: int
    code: str
    rule: str
    message: str
    severity: str = "error"
    source: str = "tablite"


@dataclass(frozen=True)
class Timing:
    name: str
    elapsed_ms: float
    budget_ms: float
    passed: bool


KOTLIN_ROOT = Path(
    "runelite-tablet/app/src/main/java/com/runelitetablet"
)
ACTIVE_METRIC_ROOTS = (
    Path("runelite-tablet/app/src"),
    Path("scripts"),
    Path("third_party/rlawt"),
    Path("third_party/termux-x11-upstream/app/src/main/cpp/lorie"),
)
PRODUCTION_LAUNCH_ROOT = Path("runelite-tablet/app/src/main/assets/scripts")

INFRASTRUCTURE_CONSTRUCTORS = (
    "ApkDownloader",
    "ApkInstaller",
    "CredentialManager",
    "DebugLogServer",
    "ExternalXlorieLoader",
    "LocalCaptureController",
    "NativeTelemetryIngressServer",
    "RuntimeLineProcessLauncher",
    "ScriptManager",
    "TermuxCommandRunner",
    "TermuxProcessPin",
)
UI_FORBIDDEN_IMPORT_PACKAGES = (
    "directsurface",
    "installer",
    "logging",
    "probe",
    "termux",
)
MISLEADING_FRAME_IDENTIFIERS = (
    "estimated_fps",
    "renderer_fps",
    "rendererFps",
    "xlorie_estimated_fps",
)
ALLOWED_FPS_IDENTIFIERS = frozenset(
    {
        "produced_fps",
        "accepted_fps",
        "submitted_fps",
        "presented_fps",
    }
)
FPS_IDENTIFIER_PATTERN = re.compile(r"\b[a-z][a-z0-9_]*_fps\b")
UNQUALIFIED_FRAME_RATE_ASSIGNMENT_PATTERN = re.compile(
    r"\b((?:accepted|buffer|choreographer|consumer|damage|display_vsync|frame|glxgears|"
    r"host_vsync|present|produced|release|renderer|submitted|x11)[a-z0-9_]*_rate)="
)
INJECTION_PATTERNS = (
    re.compile(r"(^|\s)-javaagent(?::|=|\s)"),
    re.compile(r"(^|\s)-agentlib(?::|=|\s)"),
    re.compile(r"(^|\s)-agentpath(?::|=|\s)"),
    re.compile(r"\bLD_PRELOAD\s*="),
)
RUNELITE_CONFIG_MUTATION_MARKERS = (
    ".rlt-fps-probe",
    "RLT_DEBUG_FPS_PROBE",
    "gpu.antiAliasingMode=",
    "gpu.drawDistance=",
    "gpu.fpsTarget=",
    "gpu.uiScalingMode=",
    "gpu.unlockFps=",
    "gpu.vsyncMode=",
    "runelite.clientMaximized=",
    "runelite.gameSize=",
    "runelite.gpuplugin=",
    "stretchedmode.keepAspectRatio=",
    "stretchedmode.scalingFactor=",
)
CAPTURE_INTERACTION_PATTERNS = (
    re.compile(r"\binput\s+(tap|swipe|keyevent|text)\b", re.IGNORECASE),
    re.compile(r"\buiautomator\b", re.IGNORECASE),
    re.compile(r"\b(screencap|screenrecord)\b", re.IGNORECASE),
)
POLICY_CONTRACT = (
    "runelite_client_gamepack_gpu_plugin_must_match_official = $true",
    "rlawt_java_classes_must_match_upstream = $true",
    "gameplay_automation_allowed = $false",
    "protocol_or_packet_changes_allowed = $false",
)


def _source_files(root: Path, relative_root: Path, suffixes: set[str]) -> list[Path]:
    target = root / relative_root
    if not target.exists():
        return []
    return sorted(
        path
        for path in target.rglob("*")
        if path.is_file()
        and path.suffix.lower() in suffixes
        and not any(part in {"build", ".cxx", ".gradle"} for part in path.parts)
    )


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _relative(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def _line_diagnostic(
    root: Path,
    path: Path,
    line: int,
    code: str,
    rule: str,
    message: str,
) -> Diagnostic:
    return Diagnostic(
        path=_relative(root, path),
        line=line,
        code=code,
        rule=rule,
        message=message,
    )


def check_domain_purity(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    domain_root = KOTLIN_ROOT / "domain"
    for path in _source_files(root, domain_root, {".kt", ".java"}):
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            if line.startswith("import android.") or line.startswith("import androidx."):
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL001",
                        "domain_purity",
                        "Domain code must not import Android framework APIs.",
                    )
                )
            if line.startswith("import com.runelitetablet.") and not line.startswith(
                "import com.runelitetablet.domain."
            ):
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL001",
                        "domain_purity",
                        "Domain code may depend only on domain-owned packages.",
                    )
                )
    return diagnostics


def check_ui_boundaries(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    ui_root = KOTLIN_ROOT / "ui"
    for path in _source_files(root, ui_root, {".kt"}):
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            for package in UI_FORBIDDEN_IMPORT_PACKAGES:
                if line.startswith(f"import com.runelitetablet.{package}."):
                    diagnostics.append(
                        _line_diagnostic(
                            root,
                            path,
                            line_no,
                            "TBL002",
                            "ui_infrastructure_boundary",
                            f"UI code must not import the {package} implementation package.",
                        )
                    )
    return diagnostics


def check_composition_ownership(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for path in _source_files(root, KOTLIN_ROOT, {".kt"}):
        relative = path.relative_to(root / KOTLIN_ROOT)
        if relative.parts[0] == "di":
            continue
        source = _read(path)
        for line_no, line in enumerate(source.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("//", "/*", "*")):
                continue
            for type_name in INFRASTRUCTURE_CONSTRUCTORS:
                if path.stem == type_name or stripped.startswith(
                    (f"class {type_name}", f"object {type_name}")
                ):
                    continue
                if re.search(rf"\b{re.escape(type_name)}\s*\(", line):
                    diagnostics.append(
                        _line_diagnostic(
                            root,
                            path,
                            line_no,
                            "TBL003",
                            "composition_root_ownership",
                            f"{type_name} must be constructed by com.runelitetablet.di.",
                        )
                    )
    return diagnostics


def check_process_ownership(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for path in _source_files(root, KOTLIN_ROOT, {".kt", ".java"}):
        relative = path.relative_to(root / KOTLIN_ROOT)
        allowed = relative.parts[:2] == ("platform", "process")
        if allowed:
            continue
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            if "Runtime.getRuntime().exec" in line or "ProcessBuilder(" in line:
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL004",
                        "process_adapter_ownership",
                        "Raw OS process creation belongs in platform/process.",
                    )
                )
    return diagnostics


def check_runelite_boundary(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for path in _source_files(root, PRODUCTION_LAUNCH_ROOT, {".sh", ".kt", ".java"}):
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            if line.lstrip().startswith("#"):
                continue
            if any(pattern.search(line) for pattern in INJECTION_PATTERNS):
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL005",
                        "runelite_no_injection",
                        "Production RuneLite launch paths may not inject agents or preload libraries.",
                    )
                )
            if any(marker in line for marker in RUNELITE_CONFIG_MUTATION_MARKERS):
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL006",
                        "runelite_configuration_ownership",
                        "RuneLite profile, visual, plugin, and pacing settings are user-owned.",
                    )
                )
    return diagnostics


def check_metric_semantics(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for active_root in ACTIVE_METRIC_ROOTS:
        for path in _source_files(
            root, active_root, {".c", ".h", ".java", ".kt", ".ps1", ".py", ".sh"}
        ):
            for line_no, line in enumerate(_read(path).splitlines(), 1):
                identifiers = {
                    identifier
                    for identifier in MISLEADING_FRAME_IDENTIFIERS
                    if identifier in line
                }
                identifiers.update(
                    match.group(0)
                    for match in FPS_IDENTIFIER_PATTERN.finditer(line)
                    if match.group(0) not in ALLOWED_FPS_IDENTIFIERS
                )
                identifiers.update(
                    match.group(1)
                    for match in UNQUALIFIED_FRAME_RATE_ASSIGNMENT_PATTERN.finditer(line)
                )
                for identifier in sorted(identifiers):
                    diagnostics.append(
                        _line_diagnostic(
                            root,
                            path,
                            line_no,
                            "TBL007",
                            "honest_frame_metrics",
                            f"Ambiguous metric '{identifier}': use one of the four lifecycle "
                            "*_fps stages or a stage-specific *_rate_hz cadence.",
                        )
                    )
    return diagnostics


def check_capture_noninteraction(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    scripts = root / "scripts"
    if not scripts.exists():
        return diagnostics
    for path in sorted(scripts.glob("capture-*")):
        if not path.is_file():
            continue
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            if any(pattern.search(line) for pattern in CAPTURE_INTERACTION_PATTERNS):
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL008",
                        "capture_is_observation_only",
                        "Capture tooling may not synthesize input or collect screen content.",
                    )
                )
    return diagnostics


def check_integrity_contract(root: Path) -> list[Diagnostic]:
    path = root / "scripts/verify-artifact-integrity.ps1"
    if not path.exists():
        return [
            Diagnostic(
                path=path.relative_to(root).as_posix(),
                line=1,
                code="TBL009",
                rule="artifact_integrity_contract",
                message="The artifact-integrity verifier is required.",
            )
        ]
    source = _read(path)
    diagnostics = []
    for marker in POLICY_CONTRACT:
        if marker not in source:
            diagnostics.append(
                _line_diagnostic(
                    root,
                    path,
                    1,
                    "TBL009",
                    "artifact_integrity_contract",
                    f"Missing required policy: {marker}",
                )
            )
    return diagnostics


def check_telemetry_hot_path(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    path = root / KOTLIN_ROOT / "telemetry/FrameFlightRecorder.kt"
    if not path.exists():
        return diagnostics
    forbidden = ("android.util.Log", "java.io.", "kotlin.io.", "AppLog")
    for line_no, line in enumerate(_read(path).splitlines(), 1):
        if line.startswith("import ") and any(token in line for token in forbidden):
            diagnostics.append(
                _line_diagnostic(
                    root,
                    path,
                    line_no,
                    "TBL010",
                    "telemetry_hot_path",
                    "The frame-recorder hot path may not perform logging or file I/O.",
                )
            )
    return diagnostics


def check_capture_lifecycle_ownership(root: Path) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    telemetry_root = KOTLIN_ROOT / "telemetry"
    for path in _source_files(root, KOTLIN_ROOT, {".kt"}):
        if path.is_relative_to(root / telemetry_root):
            continue
        for line_no, line in enumerate(_read(path).splitlines(), 1):
            if "TelemetryExporter." in line:
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        line_no,
                        "TBL011",
                        "capture_lifecycle_ownership",
                        "Telemetry export lifecycle is owned by CaptureController.",
                    )
                )
    return diagnostics


def check_frame_handoff_contract(root: Path) -> list[Diagnostic]:
    contracts = {
        Path("third_party/rlawt/rlawt_nix.c"): (
            "RLT_FRAME_RATE_V1",
            "first_seq=",
            "last_seq=",
            "end_monotonic_ns=",
        ),
        Path("third_party/termux-x11-upstream/app/src/main/cpp/lorie/InitOutput.c"): (
            "XlorieStageRatesV1:",
            "x11_glx_swap_request_rate_hz=",
            "first_glx_ordinal=",
            "XlorieCopyStatsV1:",
            "upload_bytes=",
            "upload_copy_ns=",
        ),
        Path("third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c"): (
            "XlorieHandoffV1:",
            "source_generation=",
            "ordinal_source=",
            "source_ordinal_gap_count=",
            "content_only_redraw_count=",
            "cursor_only_redraw_count=",
            "accepted_to_damage_status=",
            "damage_to_redraw_sample_count=",
            "avg_accepted_to_damage_ms=",
            "avg_damage_to_redraw_ms=",
            "XloriePresentationV1:",
            "display_present_event_rate_hz=",
            "unique_source_present_rate_hz=",
            "duplicate_source_present_count=",
            "telemetry_lost_count=",
            "XlorieServerSnapshotV1:",
        ),
        Path("scripts/analyze-frame-capture.ps1"): (
            "x11_glx_swap_request_rate_hz",
            "x11_upload_call_rate_hz",
            "display_present_event_rate_hz",
            "unique_source_present_rate_hz",
            "duplicate_source_presentations",
            "source_ordinal_gaps",
        ),
    }
    diagnostics: list[Diagnostic] = []
    for relative, markers in contracts.items():
        path = root / relative
        if not path.exists():
            continue
        source = _read(path)
        for marker in markers:
            if marker not in source:
                diagnostics.append(
                    _line_diagnostic(
                        root,
                        path,
                        1,
                        "TBL012",
                        "frame_handoff_contract",
                        f"Required frame-handoff telemetry marker '{marker}' is missing.",
                    )
                )
    return diagnostics


ARCHITECTURE_CHECKS = (
    check_domain_purity,
    check_ui_boundaries,
    check_composition_ownership,
    check_process_ownership,
    check_runelite_boundary,
    check_metric_semantics,
    check_capture_noninteraction,
    check_integrity_contract,
    check_telemetry_hot_path,
    check_capture_lifecycle_ownership,
    check_frame_handoff_contract,
)
ARCHITECTURE_RULE_BUDGET_MS = 1_000.0
ARCHITECTURE_TOTAL_BUDGET_MS = 5_000.0
FAST_ANALYZER_BUDGET_MS = 60_000.0
EXTERNAL_CHECK_BUDGET_MS = {
    "shell syntax": 10_000.0,
    "ShellCheck": 10_000.0,
    "PowerShell AST": 10_000.0,
    "Android Lint": 50_000.0,
    "Native clang-tidy": 8_000.0,
}
DEFAULT_TIMING_OUTPUT = Path(
    "runelite-tablet/app/build/reports/tablite-analyzer/timings.json"
)


def analyze_architecture(root: Path) -> list[Diagnostic]:
    diagnostics = [
        item
        for check in ARCHITECTURE_CHECKS
        for item in check(root.resolve())
    ]
    return sorted(diagnostics)


def analyze_architecture_timed(root: Path) -> tuple[list[Diagnostic], list[Timing]]:
    resolved = root.resolve()
    diagnostics: list[Diagnostic] = []
    timings: list[Timing] = []
    architecture_start = time.perf_counter()
    for check in ARCHITECTURE_CHECKS:
        start = time.perf_counter()
        diagnostics.extend(check(resolved))
        elapsed_ms = (time.perf_counter() - start) * 1_000.0
        passed = elapsed_ms <= ARCHITECTURE_RULE_BUDGET_MS
        timings.append(
            Timing(
                name=check.__name__,
                elapsed_ms=elapsed_ms,
                budget_ms=ARCHITECTURE_RULE_BUDGET_MS,
                passed=passed,
            )
        )
        if not passed:
            diagnostics.append(
                Diagnostic(
                    path="tools/quality/analyzer.py",
                    line=1,
                    code="TBL900",
                    rule="analyzer_timing_budget",
                    message=(
                        f"{check.__name__} took {elapsed_ms:.1f} ms; "
                        f"budget is {ARCHITECTURE_RULE_BUDGET_MS:.1f} ms."
                    ),
                )
            )
    total_ms = (time.perf_counter() - architecture_start) * 1_000.0
    total_passed = total_ms <= ARCHITECTURE_TOTAL_BUDGET_MS
    timings.append(
        Timing(
            name="architecture total",
            elapsed_ms=total_ms,
            budget_ms=ARCHITECTURE_TOTAL_BUDGET_MS,
            passed=total_passed,
        )
    )
    if not total_passed:
        diagnostics.append(
            Diagnostic(
                path="tools/quality/analyzer.py",
                line=1,
                code="TBL900",
                rule="analyzer_timing_budget",
                message=(
                    f"Architecture analysis took {total_ms:.1f} ms; "
                    f"budget is {ARCHITECTURE_TOTAL_BUDGET_MS:.1f} ms."
                ),
            )
        )
    return sorted(diagnostics), timings


def _run(
    command: Sequence[str], cwd: Path, timeout_seconds: float | None = None
) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=timeout_seconds,
        )
        return completed.returncode, completed.stdout
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode(errors="replace")
        return 124, f"{output}\nTimed out after {timeout_seconds:.1f} seconds"


def _wsl_path(path: Path) -> str:
    resolved = path.resolve()
    drive = resolved.drive.rstrip(":").lower()
    tail = resolved.as_posix().split(":", 1)[-1]
    return f"/mnt/{drive}{tail}"


def _find_native_bash() -> str | None:
    if os.name != "nt":
        return shutil.which("bash")
    candidates = [
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git/bin/bash.exe",
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/Git/bin/bash.exe",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    discovered = shutil.which("bash")
    if discovered and "system32" not in discovered.lower():
        return discovered
    return None


def run_shell_syntax(root: Path) -> tuple[bool, str]:
    scripts = [
        path
        for search_root in (root / "scripts", root / PRODUCTION_LAUNCH_ROOT)
        if search_root.exists()
        for path in search_root.rglob("*.sh")
    ]
    bash = _find_native_bash()
    wsl = shutil.which("wsl")
    outputs: list[str] = []
    for path in sorted(set(scripts)):
        if bash:
            code, output = _run(
                [bash, "-n", str(path)],
                root,
                EXTERNAL_CHECK_BUDGET_MS["shell syntax"] / 1_000.0,
            )
        elif os.name == "nt" and wsl:
            code, output = _run(
                [wsl, "bash", "-n", _wsl_path(path)],
                root,
                EXTERNAL_CHECK_BUDGET_MS["shell syntax"] / 1_000.0,
            )
        else:
            return False, "bash was not found"
        if code:
            outputs.append(f"{_relative(root, path)}\n{output}")
    return not outputs, "\n".join(outputs)


def run_shellcheck(root: Path, required: bool) -> tuple[bool, str]:
    executable = shutil.which("shellcheck")
    scripts = [
        path
        for search_root in (root / "scripts", root / PRODUCTION_LAUNCH_ROOT)
        if search_root.exists()
        for path in search_root.rglob("*.sh")
    ]
    if not executable:
        wsl = shutil.which("wsl")
        if os.name == "nt" and wsl:
            code, _ = _run(
                [wsl, "sh", "-lc", "command -v shellcheck"],
                root,
                5.0,
            )
            if code == 0:
                code, output = _run(
                    [
                        wsl,
                        "shellcheck",
                        "--severity=warning",
                        *[_wsl_path(path) for path in sorted(scripts)],
                    ],
                    root,
                    EXTERNAL_CHECK_BUDGET_MS["ShellCheck"] / 1_000.0,
                )
                return code == 0, output
        message = "shellcheck is unavailable; install 0.9+ for semantic shell analysis"
        return (not required), message
    code, output = _run(
        [executable, "--severity=warning", *map(str, sorted(scripts))],
        root,
        EXTERNAL_CHECK_BUDGET_MS["ShellCheck"] / 1_000.0,
    )
    return code == 0, output


def run_powershell_parse(root: Path, required: bool) -> tuple[bool, str]:
    executable = shutil.which("pwsh")
    if not executable:
        return (not required), "pwsh was not found; PowerShell AST parsing skipped"
    scripts = sorted((root / "scripts").rglob("*.ps1")) + sorted(
        (root / "tools").rglob("*.ps1")
    )
    encoded_paths = json.dumps([str(path) for path in scripts]).replace("'", "''")
    parser = (
        f"$paths=ConvertFrom-Json '{encoded_paths}';"
        "$failed=$false; foreach($path in $paths){"
        "$tokens=$null;$errors=$null;"
        "[System.Management.Automation.Language.Parser]::ParseFile("
        "$path,[ref]$tokens,[ref]$errors)|Out-Null;"
        "foreach($error in $errors){$failed=$true;"
        "Write-Output (('{0}:{1}: {2}' -f $path,"
        "$error.Extent.StartLineNumber,$error.Message))}};"
        "if($failed){exit 1}"
    )
    code, output = _run(
        [executable, "-NoProfile", "-NonInteractive", "-Command", parser],
        root,
        EXTERNAL_CHECK_BUDGET_MS["PowerShell AST"] / 1_000.0,
    )
    return code == 0, output


def run_android_lint(root: Path) -> tuple[bool, str]:
    project = root / "runelite-tablet"
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    code, output = _run(
        [
            str(project / wrapper),
            "lintDebug",
            "configureCMakeDebug[arm64-v8a]",
            "--no-daemon",
        ],
        project,
        EXTERNAL_CHECK_BUDGET_MS["Android Lint"] / 1_000.0,
    )
    return code == 0, output


def _find_clang_tidy() -> str | None:
    executable = shutil.which("clang-tidy")
    if executable:
        return executable
    sdk_roots = [
        Path(value)
        for value in (
            os.environ.get("ANDROID_SDK_ROOT"),
            os.environ.get("ANDROID_HOME"),
            (
                str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
                if os.environ.get("LOCALAPPDATA")
                else None
            ),
        )
        if value
    ]
    executable_name = "clang-tidy.exe" if os.name == "nt" else "clang-tidy"
    candidates = [
        path
        for sdk_root in sdk_roots
        for path in (sdk_root / "ndk").glob(
            f"*/toolchains/llvm/prebuilt/*/bin/{executable_name}"
        )
    ]
    return str(sorted(candidates, reverse=True)[0]) if candidates else None


def run_native_clang_tidy(root: Path, required: bool) -> tuple[bool, str]:
    executable = _find_clang_tidy()
    if not executable:
        return (not required), "clang-tidy was not found in PATH or the Android NDK"
    compile_databases = sorted(
        (root / "runelite-tablet/app/.cxx/Debug").glob(
            "*/arm64-v8a/compile_commands.json"
        ),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not compile_databases:
        return (
            not required,
            "arm64-v8a compile_commands.json was not generated by Android configuration",
        )
    owned_units = (
        root
        / "runelite-tablet/app/src/main/cpp/rlawtsurface/rlawt_surface_consumer.c",
        root
        / "third_party/termux-x11-upstream/app/src/main/cpp/lorie/renderer.c",
    )
    database_source = _read(compile_databases[0]).replace("\\\\", "/").lower()
    missing = [
        _relative(root, path)
        for path in owned_units
        if path.name.lower() not in database_source
    ]
    if missing:
        return False, "compile database does not own: " + ", ".join(missing)
    code, output = _run(
        [
            executable,
            "-p",
            str(compile_databases[0].parent),
            "-checks=-*,clang-analyzer-core.*,clang-analyzer-unix.*,clang-analyzer-deadcode.*",
            "-warnings-as-errors=*",
            "--quiet",
            *map(str, owned_units),
        ],
        root,
        EXTERNAL_CHECK_BUDGET_MS["Native clang-tidy"] / 1_000.0,
    )
    return code == 0, output


def _run_timed_check(
    name: str,
    check,
) -> tuple[str, bool, str, Timing]:
    start = time.perf_counter()
    passed, output = check()
    elapsed_ms = (time.perf_counter() - start) * 1_000.0
    budget_ms = EXTERNAL_CHECK_BUDGET_MS[name]
    timing_passed = elapsed_ms < budget_ms
    if not timing_passed:
        output = (
            f"{output.rstrip()}\n" if output.strip() else ""
        ) + f"{name} took {elapsed_ms:.1f} ms; budget is {budget_ms:.1f} ms."
    return (
        name,
        passed and timing_passed,
        output,
        Timing(
            name=name,
            elapsed_ms=elapsed_ms,
            budget_ms=budget_ms,
            passed=timing_passed,
        ),
    )


def _write_timings(
    path: Path,
    timings: Sequence[Timing],
    total_elapsed_ms: float,
    total_passed: bool,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "overall": asdict(
            Timing(
                name="analyzer total",
                elapsed_ms=total_elapsed_ms,
                budget_ms=FAST_ANALYZER_BUDGET_MS,
                passed=total_passed,
            )
        ),
        "checks": [asdict(item) for item in timings],
    }
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _print_diagnostics(diagnostics: Iterable[Diagnostic], json_output: bool) -> None:
    items = list(diagnostics)
    if json_output:
        for item in items:
            print(json.dumps(asdict(item), sort_keys=True))
        return
    for item in items:
        print(
            f"{item.path}:{item.line}: {item.severity} {item.code} "
            f"[{item.rule}] {item.message}"
        )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument(
        "--architecture-only",
        action="store_true",
        help="Run only fast repository-owned architectural rules.",
    )
    parser.add_argument(
        "--require-external-tools",
        action="store_true",
        help="Fail if ShellCheck or PowerShell are unavailable (used by CI).",
    )
    parser.add_argument(
        "--timing-output",
        type=Path,
        default=None,
        help=(
            "Write machine-readable timing data here. The default is "
            "runelite-tablet/app/build/reports/tablite-analyzer/timings.json."
        ),
    )
    parser.add_argument("--jsonl", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    analyzer_start = time.perf_counter()

    diagnostics, timings = analyze_architecture_timed(root)
    _print_diagnostics(diagnostics, args.jsonl)
    failed = bool(diagnostics)

    if not args.architecture_only:
        checks = {
            ("shell syntax", lambda: run_shell_syntax(root)),
            (
                "ShellCheck",
                lambda: run_shellcheck(root, args.require_external_tools),
            ),
            (
                "PowerShell AST",
                lambda: run_powershell_parse(root, args.require_external_tools),
            ),
            ("Android Lint", lambda: run_android_lint(root)),
        }
        results = []
        with ThreadPoolExecutor(max_workers=len(checks)) as executor:
            futures = {
                executor.submit(_run_timed_check, name, check): name
                for name, check in checks
            }
            for future in as_completed(futures):
                results.append(future.result())
        for name, passed, output, timing in sorted(results):
            timings.append(timing)
            print(f"{'PASS' if passed else 'FAIL'} {name}")
            if output.strip() and (not passed or "unavailable" in output or "not found" in output):
                print(output.rstrip())
            failed = failed or not passed

        name, passed, output, timing = _run_timed_check(
            "Native clang-tidy",
            lambda: run_native_clang_tidy(root, args.require_external_tools),
        )
        timings.append(timing)
        print(f"{'PASS' if passed else 'FAIL'} {name}")
        if output.strip() and (not passed or "not found" in output or "not generated" in output):
            print(output.rstrip())
        failed = failed or not passed

    total_elapsed_ms = (time.perf_counter() - analyzer_start) * 1_000.0
    total_passed = total_elapsed_ms < FAST_ANALYZER_BUDGET_MS
    failed = failed or not total_passed
    for timing in timings:
        print(
            f"TIMING {timing.name}: {timing.elapsed_ms:.1f} ms / "
            f"{timing.budget_ms:.1f} ms "
            f"({'PASS' if timing.passed else 'FAIL'})"
        )
    print(
        f"TIMING analyzer total: {total_elapsed_ms:.1f} ms / "
        f"{FAST_ANALYZER_BUDGET_MS:.1f} ms "
        f"({'PASS' if total_passed else 'FAIL'})"
    )
    timing_output = args.timing_output
    if timing_output is None:
        timing_output = root / DEFAULT_TIMING_OUTPUT
    elif not timing_output.is_absolute():
        timing_output = root / timing_output
    _write_timings(timing_output, timings, total_elapsed_ms, total_passed)

    if failed:
        print(f"Tablite analyzer failed with {len(diagnostics)} architecture diagnostic(s).")
        return 1
    print("Tablite analyzer passed with zero architecture diagnostics.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
