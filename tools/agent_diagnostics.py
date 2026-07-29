#!/usr/bin/env python3
"""Canonical, observation-only client for Tablite's agent diagnostics API."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterator


PACKAGE = "com.runelitetablet"
REMOTE_PORT = 8099
SESSION_PATH = "files/diagnostics/agent-session.json"


@dataclass(frozen=True)
class AgentSession:
    run_id: str
    token: str
    base_url: str

    @classmethod
    def from_json(cls, value: dict[str, object]) -> "AgentSession":
        run_id = str(value.get("runId") or "")
        token = str(value.get("token") or "")
        base_url = str(value.get("baseUrl") or "")
        if not run_id or len(token) < 32 or not base_url.startswith("http://127.0.0.1:"):
            raise ValueError("invalid_agent_diagnostics_session")
        return cls(run_id=run_id, token=token, base_url=base_url)

    def public(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "runId": self.run_id,
            "baseUrl": self.base_url,
            "credential": "redacted",
        }


class AgentDiagnosticsClient:
    def __init__(self, session: AgentSession, host_port: int) -> None:
        self.session = session
        self.base_url = f"http://127.0.0.1:{host_port}"

    def get_json(self, route: str) -> dict[str, object]:
        request = urllib.request.Request(
            f"{self.base_url}{route}",
            headers={"Authorization": f"Bearer {self.session.token}"},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            value = json.load(response)
        if not isinstance(value, dict):
            raise RuntimeError("agent_endpoint_returned_non_object")
        return value

    def state_events(self, last_event_id: str | None = None) -> Iterator[dict[str, object]]:
        headers = {
            "Authorization": f"Bearer {self.session.token}",
            "Accept": "text/event-stream",
        }
        if last_event_id:
            headers["Last-Event-ID"] = last_event_id
        request = urllib.request.Request(
            f"{self.base_url}/v1/state/events",
            headers=headers,
        )
        with urllib.request.urlopen(request, timeout=70) as response:
            yield from parse_sse(response)


def parse_sse(stream: BinaryIO) -> Iterator[dict[str, object]]:
    event: dict[str, object] = {}
    data_lines: list[str] = []
    while True:
        raw = stream.readline()
        if not raw:
            return
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data_lines:
                event["data"] = json.loads("\n".join(data_lines))
                yield event
            event = {}
            data_lines = []
            continue
        if line.startswith("id:"):
            event["id"] = line[3:].strip()
        elif line.startswith("event:"):
            event["event"] = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].strip())


def adb_command(adb: str, serial: str | None, arguments: list[str]) -> list[str]:
    command = [adb]
    if serial:
        command.extend(["-s", serial])
    command.extend(arguments)
    return command


def load_session(adb: str, serial: str | None) -> AgentSession:
    result = subprocess.run(
        adb_command(
            adb,
            serial,
            ["exec-out", "run-as", PACKAGE, "cat", SESSION_PATH],
        ),
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return AgentSession.from_json(json.loads(result.stdout))


def ensure_forward(adb: str, serial: str | None, host_port: int) -> None:
    subprocess.run(
        adb_command(
            adb,
            serial,
            ["forward", f"tcp:{host_port}", f"tcp:{REMOTE_PORT}"],
        ),
        check=True,
        capture_output=True,
        text=True,
    )


def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def capture_bundle(
    client: AgentDiagnosticsClient,
    session: AgentSession,
    output: Path,
) -> dict[str, object]:
    capabilities = client.get_json("/v1/capabilities")
    health = client.get_json("/v1/health")
    state = client.get_json("/v1/state")
    logs = read_complete_log_window(client)
    gap = bool(((logs.get("gap") or {}) if isinstance(logs.get("gap"), dict) else {}).get("detected"))
    dropped = int(logs.get("droppedCount") or 0)
    summary = {
        "schemaVersion": 1,
        "runId": session.run_id,
        "capturedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "passed": not gap and dropped == 0,
        "statePosture": (
            ((state.get("stateMachine") or {}) if isinstance(state.get("stateMachine"), dict) else {})
            .get("posture")
        ),
        "stateObservationReady": (
            ((state.get("stateMachine") or {}) if isinstance(state.get("stateMachine"), dict) else {})
            .get("observationReady")
        ),
        "logEntryCount": len(logs.get("entries") or []),
        "logGapDetected": gap,
        "logDroppedCount": dropped,
        "websocketDroppedCount": int(health.get("websocketDropped") or 0),
        "artifacts": [
            "session-public.json",
            "capabilities.json",
            "health.json",
            "state.json",
            "logs.json",
        ],
    }
    save_json(output / "session-public.json", session.public())
    save_json(output / "capabilities.json", capabilities)
    save_json(output / "health.json", health)
    save_json(output / "state.json", state)
    save_json(output / "logs.json", logs)
    save_json(output / "summary.json", summary)
    if gap or dropped:
        raise RuntimeError("agent_diagnostics_evidence_incomplete")
    return summary


def read_complete_log_window(
    client: AgentDiagnosticsClient,
    *,
    max_pages: int = 60,
    max_bytes: int = 32 * 1024 * 1024,
) -> dict[str, object]:
    entries: list[object] = []
    after = 0
    encoded_bytes = 0
    newest: int | None = None
    oldest: int | None = None
    evicted = 0
    dropped = 0
    for _ in range(max_pages):
        page = client.get_json(f"/v1/logs?after={after}&limit=500")
        gap_value = page.get("gap")
        gap = gap_value if isinstance(gap_value, dict) else {}
        if bool(gap.get("detected")):
            raise RuntimeError("agent_diagnostics_log_gap")
        page_entries = page.get("entries")
        if not isinstance(page_entries, list):
            raise RuntimeError("agent_diagnostics_invalid_log_page")
        encoded_bytes += len(
            json.dumps(page, separators=(",", ":")).encode("utf-8")
        )
        if encoded_bytes > max_bytes:
            raise RuntimeError("agent_diagnostics_log_window_too_large")
        entries.extend(page_entries)
        after = int(page.get("nextCursor") or after)
        retained = page.get("retainedRange")
        retained_range = retained if isinstance(retained, dict) else {}
        oldest_value = retained_range.get("oldest")
        newest_value = retained_range.get("newest")
        oldest = int(oldest_value) if oldest_value is not None else oldest
        newest = int(newest_value) if newest_value is not None else newest
        evicted = int(page.get("evictedCount") or 0)
        dropped = int(page.get("droppedCount") or 0)
        if newest is None or after >= newest:
            return {
                "runId": page.get("runId"),
                "entries": entries,
                "nextCursor": after,
                "retainedRange": {"oldest": oldest, "newest": newest},
                "gap": {"detected": False, "requestedAfter": 0},
                "evictedCount": evicted,
                "droppedCount": dropped,
                "truncated": False,
                "sourceEncodedBytes": encoded_bytes,
            }
        if not page_entries:
            raise RuntimeError("agent_diagnostics_log_cursor_stalled")
    raise RuntimeError("agent_diagnostics_log_page_limit")


def default_output(root: Path, run_id: str) -> Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    return root / "perf-captures" / f"{stamp}-{run_id}-agent-diagnostics"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action",
        choices=("capabilities", "health", "state", "logs", "watch-state", "capture"),
    )
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--host-port", type=int, default=REMOTE_PORT)
    parser.add_argument("--after", type=int, default=0)
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--last-event-id")
    parser.add_argument("--max-events", type=int, default=0)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.host_port < 1 or args.limit < 1 or args.limit > 500 or args.after < 0:
        raise SystemExit("invalid bounded diagnostics arguments")
    session = load_session(args.adb, args.serial)
    ensure_forward(args.adb, args.serial, args.host_port)
    client = AgentDiagnosticsClient(session, args.host_port)
    if args.action == "watch-state":
        count = 0
        for event in client.state_events(args.last_event_id):
            print(json.dumps(event, sort_keys=True))
            count += 1
            if args.max_events and count >= args.max_events:
                break
        return 0
    if args.action == "capture":
        output = args.output or default_output(args.project_root, session.run_id)
        summary = capture_bundle(client, session, output.resolve())
        print(json.dumps({"output": str(output.resolve()), "summary": summary}, indent=2))
        return 0
    routes = {
        "capabilities": "/v1/capabilities",
        "health": "/v1/health",
        "state": "/v1/state",
        "logs": f"/v1/logs?after={args.after}&limit={args.limit}",
    }
    print(json.dumps(client.get_json(routes[args.action]), indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"agent diagnostics failed: {error}", file=sys.stderr)
        raise SystemExit(1)
