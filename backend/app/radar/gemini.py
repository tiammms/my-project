"""Minimal Gemini API client (standard library only).

The key is read from the GEMINI_API_KEY environment variable on the server. It is sent only in the
x-goog-api-key header (never in the URL, so it does not end up in proxy or server logs), and it is
removed from every error message.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Callable

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
# first model that the key can use wins; set NS_GEMINI_MODEL to pin one
DEFAULT_MODELS = ("gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash")


class GeminiError(Exception):
    pass


def api_key() -> str:
    return os.environ.get("GEMINI_API_KEY", "").strip()


def models() -> tuple[str, ...]:
    pinned = os.environ.get("NS_GEMINI_MODEL", "").strip()
    return (pinned,) if pinned else DEFAULT_MODELS


def _scrub(text: str, key: str) -> str:
    return text.replace(key, "***") if key else text


def _http_post(url: str, headers: dict, body: bytes, timeout: float) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def generate_json(prompt: str, schema: dict, *, timeout: float = 90,
                  post: Callable[[str, dict, bytes, float], tuple[int, bytes]] = _http_post) -> tuple[dict, str]:
    """Send one prompt, get back (parsed JSON object, model used)."""
    key = api_key()
    if not key:
        raise GeminiError("GEMINI_API_KEY не задан")
    body = json.dumps({
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json", "responseSchema": schema},
    }, ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json", "x-goog-api-key": key}
    last = ""
    for model in models():
        try:
            status, raw = post(ENDPOINT.format(model=model), headers, body, timeout)
        except OSError as e:                                   # no internet, DNS, timeout
            raise GeminiError(_scrub(f"нет связи с Gemini: {e}", key)) from None
        if status == 404:                                       # this key cannot use this model: try the next one
            last = f"модель {model} недоступна"
            continue
        text = _scrub(raw.decode("utf-8", "replace"), key)
        if status != 200:
            try:
                msg = json.loads(text).get("error", {}).get("message", "")
            except ValueError:
                msg = text[:200]
            raise GeminiError(f"Gemini ответил {status}: {msg[:300]}")
        try:
            data = json.loads(text)
            part = data["candidates"][0]["content"]["parts"][0]["text"]
            return json.loads(part), model
        except (KeyError, IndexError, ValueError, TypeError):
            raise GeminiError("Gemini вернул ответ не в ожидаемом формате") from None
    raise GeminiError(f"{last}; укажите доступную модель в NS_GEMINI_MODEL")
