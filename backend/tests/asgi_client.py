"""Minimal synchronous ASGI test client (httpx / TestClient were not installable here)."""
from __future__ import annotations

import asyncio
import json
from urllib.parse import urlencode


class Response:
    def __init__(self, status, headers, body):
        self.status_code, self.headers, self.content = status, headers, body

    def json(self):
        return json.loads(self.content)


class Client:
    def __init__(self, app, client_ip="127.0.0.1"):
        self.app, self.ip = app, client_ip

    def request(self, method, path, json_body=None, raw=None, params=None, headers=None):
        body = raw if raw is not None else (json.dumps(json_body).encode() if json_body is not None else b"")
        hdrs = [(b"host", b"testserver"), (b"content-length", str(len(body)).encode())]
        if json_body is not None or raw is not None:
            hdrs.append((b"content-type", b"application/json"))
        for k, v in (headers or {}).items():
            hdrs.append((k.lower().encode(), v.encode()))
        scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": method,
                 "scheme": "http", "path": path, "raw_path": path.encode(),
                 "query_string": urlencode(params or {}).encode(), "headers": hdrs,
                 "client": (self.ip, 50000), "server": ("testserver", 80), "root_path": ""}
        sent = {"done": False}
        out = {"status": None, "headers": {}, "body": b""}

        async def receive():
            if not sent["done"]:
                sent["done"] = True
                return {"type": "http.request", "body": body, "more_body": False}
            await asyncio.sleep(3600)

        async def send(msg):
            if msg["type"] == "http.response.start":
                out["status"] = msg["status"]
                out["headers"] = {k.decode(): v.decode() for k, v in msg.get("headers", [])}
            elif msg["type"] == "http.response.body":
                out["body"] += msg.get("body", b"")

        asyncio.run(self.app(scope, receive, send))
        return Response(out["status"], out["headers"], out["body"])

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, json=None, **kw):
        return self.request("POST", path, json_body=json, **kw)

    def put(self, path, json=None, **kw):
        return self.request("PUT", path, json_body=json, **kw)
