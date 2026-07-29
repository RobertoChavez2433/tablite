from __future__ import annotations

import io
import json
import tempfile
import unittest
from pathlib import Path

from tools.agent_diagnostics import (
    AgentDiagnosticsClient,
    AgentSession,
    capture_bundle,
    parse_sse,
    read_complete_log_window,
)


class AgentDiagnosticsTest(unittest.TestCase):
    def test_session_public_projection_never_contains_token(self) -> None:
        session = AgentSession(
            run_id="run-a",
            token="x" * 32,
            base_url="http://127.0.0.1:8099",
        )
        encoded = json.dumps(session.public())
        self.assertNotIn("x" * 32, encoded)
        self.assertIn("redacted", encoded)

    def test_sse_parser_preserves_id_kind_and_payload(self) -> None:
        source = io.BytesIO(
            b"id: generation:4\n"
            b"event: state\n"
            b'data: {"revision":4,"snapshot":{"stateMachine":{"posture":"idle"}}}\n\n'
        )
        events = list(parse_sse(source))
        self.assertEqual(1, len(events))
        self.assertEqual("generation:4", events[0]["id"])
        self.assertEqual("state", events[0]["event"])
        self.assertEqual(4, events[0]["data"]["revision"])

    def test_capture_fails_closed_on_log_gap(self) -> None:
        session = AgentSession(
            run_id="run-a",
            token="x" * 32,
            base_url="http://127.0.0.1:8099",
        )

        class FixtureClient(AgentDiagnosticsClient):
            def __init__(self) -> None:
                pass

            def get_json(self, route: str):
                if route == "/v1/state":
                    return {"stateMachine": {"posture": "idle", "observationReady": True}}
                if route.startswith("/v1/logs"):
                    return {"entries": [], "gap": {"detected": True}, "droppedCount": 0}
                return {"websocketDropped": 0}

        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(
                RuntimeError, "agent_diagnostics_log_gap"
            ):
                capture_bundle(FixtureClient(), session, Path(temp))

    def test_log_window_follows_cursor_pages(self) -> None:
        class FixtureClient:
            def get_json(self, route: str):
                after = int(route.split("after=")[1].split("&")[0])
                if after == 0:
                    return {
                        "runId": "run-a",
                        "entries": [{"cursor": 1}, {"cursor": 2}],
                        "nextCursor": 2,
                        "retainedRange": {"oldest": 1, "newest": 3},
                        "gap": {"detected": False},
                        "evictedCount": 0,
                        "droppedCount": 0,
                        "truncated": True,
                    }
                return {
                    "runId": "run-a",
                    "entries": [{"cursor": 3}],
                    "nextCursor": 3,
                    "retainedRange": {"oldest": 1, "newest": 3},
                    "gap": {"detected": False},
                    "evictedCount": 0,
                    "droppedCount": 0,
                    "truncated": False,
                }

        result = read_complete_log_window(FixtureClient())
        self.assertEqual(3, len(result["entries"]))
        self.assertFalse(result["truncated"])


if __name__ == "__main__":
    unittest.main()
