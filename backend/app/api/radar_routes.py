"""Scheme radar endpoints (/api/v1/radar/...): phones send anonymised phrases, an admin runs the Gemini analysis."""
from __future__ import annotations

import asyncio
import hmac
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from ..radar import gemini
from ..radar.analyze import analyze
from ..radar.anonymize import anonymize
from ..radar.store import RadarStore
from .framework import HTTPError
from .routes import STATE, api

TAG = ["radar"]


def _store() -> RadarStore:
    if "radar" not in STATE:
        STATE["radar"] = RadarStore()
    return STATE["radar"]


def _admin(request):
    token = STATE["settings"].admin_token
    if not token:
        raise HTTPError(403, "Set NS_ADMIN_TOKEN on the server to use the radar analysis")
    if not hmac.compare_digest(request.headers.get("x-admin-token", ""), token):
        raise HTTPError(401, "Admin token required")


class ReportIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(..., min_length=3, max_length=1000)
    lang: Literal["ru", "kk", "en"] = "ru"
    source: Literal["sms", "messenger", "voice", "image", "retell", "manual"] = "manual"


@api.route("/api/v1/radar/report", "POST", summary="Send one suspicious phrase to the radar (anonymised again on the server)",
           tags=TAG, body=ReportIn, errors=(400, 413, 422, 429))
def radar_report(body: ReportIn):
    text = anonymize(body.text)
    if len(text) < 3:
        raise HTTPError(422, "Nothing left after anonymisation")
    _store().add(text, body.lang, body.source)
    return {"stored": True, "text": text}


@api.route("/api/v1/radar/status", "GET", summary="Is the Gemini key set (yes/no only) and how many phrases are stored", tags=TAG)
def radar_status():
    return {"gemini_key_set": bool(gemini.api_key()), "models": list(gemini.models()), "phrases_30_days": _store().count()}


@api.route("/api/v1/radar/analyze", "POST", summary="Group the last 7 days of phrases into schemes with Gemini (admin)",
           tags=TAG, errors=(401, 403, 502, 503))
async def radar_analyze(request):
    _admin(request)
    if not gemini.api_key():
        raise HTTPError(503, "GEMINI_API_KEY is not set on the server")
    phrases = _store().recent(days=7)
    try:
        report, model = await asyncio.to_thread(analyze, phrases)
    except gemini.GeminiError as e:
        raise HTTPError(502, str(e))
    _store().save_report(model, len(phrases), report)
    return {"model": model, "phrases": len(phrases), **report}


@api.route("/api/v1/radar/summary", "GET", summary="Latest draft summary (admin)", tags=TAG, errors=(401, 403, 404))
def radar_summary(request):
    _admin(request)
    r = _store().last_report()
    if not r:
        raise HTTPError(404, "No radar report yet")
    return r
