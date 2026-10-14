"""Pure-ASGI security middleware: request IDs, body-size limit, rate limit, security headers."""
from __future__ import annotations

import json
import logging
import threading
import time
import uuid

log = logging.getLogger("neuroshield")


class SecurityMiddleware:
    def __init__(self, app, max_body_bytes: int, rate_per_minute: int):
        self.app = app
        self.max_body = max_body_bytes
        self.rate = rate_per_minute
        self.buckets: dict[str, tuple[float, float]] = {}
        self.lock = threading.Lock()

    def _allow(self, key: str) -> bool:
        now = time.monotonic()
        cap = float(self.rate)
        with self.lock:
            tokens, last = self.buckets.get(key, (cap, now))
            tokens = min(cap, tokens + (now - last) * cap / 60.0)
            if tokens < 1:
                self.buckets[key] = (tokens, now)
                return False
            self.buckets[key] = (tokens - 1, now)
            return True

    async def _reject(self, send, status, msg, rid):
        body = json.dumps({"error": msg, "request_id": rid}).encode()
        await send({"type": "http.response.start", "status": status,
                    "headers": [(b"content-type", b"application/json"), (b"x-request-id", rid.encode())]})
        await send({"type": "http.response.body", "body": body})

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        rid = uuid.uuid4().hex[:16]
        scope.setdefault("state", {})["request_id"] = rid
        path = scope.get("path", "")
        client = (scope.get("client") or ("unknown", 0))[0]
        t0 = time.perf_counter()
        if path.startswith("/api/") and not self._allow(client):
            return await self._reject(send, 429, "Rate limit exceeded", rid)
        headers = dict(scope.get("headers") or [])
        cl = headers.get(b"content-length")
        if cl is not None:
            try:
                if int(cl) > self.max_body:
                    return await self._reject(send, 413, f"Request body exceeds {self.max_body} bytes", rid)
            except ValueError:
                return await self._reject(send, 400, "Invalid Content-Length", rid)
        received = 0
        too_big = False

        async def limited_receive():
            nonlocal received, too_big
            msg = await receive()
            if msg["type"] == "http.request":
                received += len(msg.get("body", b""))
                if received > self.max_body:
                    too_big = True
                    raise ValueError("body too large")
            return msg

        status_holder = {}

        async def send_wrapper(msg):
            if msg["type"] == "http.response.start":
                status_holder["s"] = msg["status"]
                h = list(msg.get("headers", []))
                h += [(b"x-request-id", rid.encode()), (b"x-content-type-options", b"nosniff"),
                      (b"x-frame-options", b"DENY"), (b"referrer-policy", b"no-referrer")]
                if path.startswith("/api/") or path == "/health":
                    h.append((b"cache-control", b"no-store"))
                if not path.startswith("/docs"):
                    h.append((b"content-security-policy",
                              b"default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                              b"font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"))
                msg = {**msg, "headers": h}
            await send(msg)

        try:
            await self.app(scope, limited_receive, send_wrapper)
        except ValueError:
            if too_big:
                return await self._reject(send, 413, f"Request body exceeds {self.max_body} bytes", rid)
            raise
        finally:
            # Log metadata only - never request bodies (may contain behavioral context).
            log.info("%s %s %s %.1fms rid=%s", scope.get("method"), path, status_holder.get("s"),
                     (time.perf_counter() - t0) * 1000, rid)
