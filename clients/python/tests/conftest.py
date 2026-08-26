"""In-process stub of the HermesMQ REST API for hermetic client tests.

A plain `http.server` on an ephemeral port, run in a daemon thread. It records
the last request (method, path, JSON body, auth headers) on the server object so
tests can assert exact wire shapes, and answers with canned responses mirroring
the broker's contract.
"""

from __future__ import annotations

import json
import re
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pytest

from hermesmq_client import HermesClient


class _StubHandler(BaseHTTPRequestHandler):
    server: StubServer  # narrowed for mypy

    def _body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else {}

    def _record(self, body: dict[str, Any]) -> None:
        self.server.last_request = {
            "method": self.command,
            "path": self.path,
            "body": body,
            "authorization": self.headers.get("Authorization", ""),
            "x_api_key": self.headers.get("X-API-Key", ""),
            "x_correlation_id": self.headers.get("X-Correlation-Id", ""),
        }

    def _send(
        self, status: int, payload: Any | None = None, content_type: str = "application/json"
    ) -> None:
        self.send_response(status)
        if payload is None:
            self.end_headers()
            return
        data = json.dumps(payload).encode()
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - stdlib signature
        pass  # keep test output quiet

    def do_GET(self) -> None:  # noqa: N802 - stdlib naming
        self._record({})
        if self.path == "/v1/topics":
            self._send(200, [{"topicId": "orders", "publishedTotal": 42, "deleted": False}])
        elif self.path == "/v1/topics/orders":
            self._send(200, {"topicId": "orders", "labels": {"team": "payments"}})
        elif self.path.startswith("/v1/topics/"):
            self._send(404, {"error": "no such topic"})
        elif self.path == "/v1/subscriptions":
            self._send(
                200,
                [
                    {
                        "subscriptionId": "s1",
                        "topicId": "orders",
                        "backlog": 3,
                        "oldestUnackedAgeSeconds": 7,
                        "redeliveredTotal": 1,
                        "deadLetteredTotal": 0,
                    }
                ],
            )
        elif self.path == "/health":
            self._send(200, {"status": "UP", "service": "hermesmq", "version": "1.11.0"})
        else:
            self._send(404)

    def do_POST(self) -> None:  # noqa: N802
        body = self._body()
        self._record(body)
        if self.path == "/v1/topics":
            self._send(409 if body.get("topicId") == "dup" else 201)
        elif m := re.fullmatch(r"/v1/topics/([^/]+)/messages", self.path):
            if m.group(1) == "ghost":
                self._send(404, {"error": "no such topic"})
            elif m.group(1) == "plaintype":
                # 2xx, JSON body, non-JSON content-type label — delivery must be
                # judged by status, not the label (the Demeter trap).
                self._send(
                    202,
                    {"messageId": "m-plain", "deduplicated": False},
                    content_type="text/plain",
                )
            elif body.get("idempotencyKey") == "idem-1":
                self._send(202, {"messageId": "m-orig", "deduplicated": True})
            else:
                self._send(202, {"messageId": "m-123", "deduplicated": False})
        elif self.path == "/v1/subscriptions":
            self._send(409 if body.get("subscriptionId") == "dupsub" else 201)
        elif m := re.fullmatch(r"/v1/subscriptions/([^/]+)/pull", self.path):
            if m.group(1) == "ghost":
                self._send(404, {"error": "no such subscription"})
            elif m.group(1) == "corr-sub":
                self._send(
                    200,
                    {
                        "messages": [
                            {
                                "ackId": "a1",
                                "payload": "hello",
                                "attributes": {},
                                "publishTime": "2026-07-08T00:00:00Z",
                                "correlationId": "corr-42",
                            }
                        ]
                    },
                )
            else:
                self._send(
                    200,
                    {
                        "messages": [
                            {
                                "ackId": "a1",
                                "payload": "hello",
                                "attributes": {"k": "v"},
                                "publishTime": "2026-07-08T00:00:00Z",
                            }
                        ]
                    },
                )
        elif re.fullmatch(r"/v1/subscriptions/[^/]+/ack", self.path):
            self._send(200, {"acknowledged": ["a1"], "unknown": ["a-stale"]})
        elif re.fullmatch(r"/v1/subscriptions/[^/]+/modifyAckDeadline", self.path):
            self._send(200, {"modified": ["a1"], "unknown": ["a-stale"]})
        else:
            self._send(404)

    def do_PATCH(self) -> None:  # noqa: N802
        self._record(self._body())
        self._send(200 if self.path == "/v1/topics/orders" else 404)

    def do_DELETE(self) -> None:  # noqa: N802
        self._record({})
        if self.path == "/v1/topics/orders" or self.path == "/v1/subscriptions/s1":
            self._send(204)
        else:
            self._send(404, {"error": "not found"})


class StubServer(ThreadingHTTPServer):
    last_request: dict[str, Any] = {}


@pytest.fixture(scope="session")
def stub() -> Iterator[StubServer]:
    server = StubServer(("127.0.0.1", 0), _StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield server
    server.shutdown()


@pytest.fixture(scope="session")
def base_url(stub: StubServer) -> str:
    return f"http://127.0.0.1:{stub.server_address[1]}"


@pytest.fixture
def client(base_url: str) -> Iterator[HermesClient]:
    with HermesClient(base_url) as c:
        yield c
