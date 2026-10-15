"""NeuroShield API application factory.

Run:  python -m backend.app.main          (or: uvicorn backend.app.main:app --port 8000)
"""
from __future__ import annotations

import os

# Single-row inference is fastest single-threaded; avoids OpenMP oversubscription under load.
os.environ.setdefault("OMP_NUM_THREADS", "1")

import asyncio.base_events as _asyncio_base_events
import json
import logging
import socket as _socket


def _set_nodelay_any_tcp(sock):
    """Enable TCP_NODELAY on every accepted TCP connection.

    asyncio only sets it when sock.proto == IPPROTO_TCP, but uvicorn's multi-worker mode binds its listening socket
    with proto 0, so accepted connections kept Nagle's algorithm on. A response written in two parts (headers, body)
    then waited for the client's delayed ACK: a measured ~44 ms floor on every request with --workers 2.
    """
    if sock.family in (_socket.AF_INET, _socket.AF_INET6) and sock.type == _socket.SOCK_STREAM:
        sock.setsockopt(_socket.IPPROTO_TCP, _socket.TCP_NODELAY, 1)


_asyncio_base_events._set_nodelay = _set_nodelay_any_tcp
import time
from pathlib import Path

import pandas as pd
from starlette.applications import Starlette
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import FileResponse, HTMLResponse, JSONResponse
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles

from .api.framework import SWAGGER_HTML
from .api.routes import STATE, api, relabel_dataset
from .api import bio_routes  # noqa: F401  (registers /api/v1/bio/* on the same API)
from .biometrics.store import BioStore
from .core.config import Settings, settings as default_settings
from .database.db import Database, now_iso
from .security.middleware import SecurityMiddleware
from .services.graph_engine import GraphEngine
from .services.risk_engine import RiskEngine

log = logging.getLogger("neuroshield")

DATASET_COLS_EXCLUDED = {"transaction_id", "timestamp", "t_hours", "sender_id", "recipient_id", "is_fraud",
                         "fraud_type", "legit_tag", "event_id", "ring_id", "day", "split"}


def seed_from_dataset(db: Database, engine: RiskEngine, path: Path) -> int:
    """Load the synthetic demo set (test split) and score every row with the real Risk Engine."""
    if not path.exists():
        log.warning("Demo dataset %s not found - starting with an empty transaction log", path)
        return 0
    df = pd.read_csv(path, low_memory=False)
    recs = df.astype(object).where(pd.notna(df), None).to_dict("records")
    t0 = time.time()
    rows = []
    raws = [{k: v for k, v in r.items() if k not in DATASET_COLS_EXCLUDED} for r in recs]
    results = engine.score_bulk(raws, [r["transaction_id"] for r in recs])
    # Flagged rows get their full explanation up front (analysts open these); LOW rows stay lazy.
    for i, res in enumerate(results):
        if res["risk_level"] != "LOW":
            results[i] = engine.score(raws[i], res["transaction_id"])
    engine.latency.buf.clear()   # seeding is not live API traffic
    for r, raw, res in zip(recs, raws, results):
        rows.append({"transaction_id": r["transaction_id"], "created_at": now_iso(), "source": "dataset",
                     "event_time": str(r["timestamp"]).replace(" ", "T"), "sender_ref": r["sender_id"],
                     "recipient_ref": r["recipient_id"], "amount": float(r["amount"]),
                     "transaction_type": r["transaction_type"], "raw_json": json.dumps(raw, default=str),
                     "label": int(r["is_fraud"]), "fraud_type": r["fraud_type"], "legit_tag": r["legit_tag"],
                     "risk_score": res["risk_score"], "risk_level": res["risk_level"],
                     "action": res["recommended_action"], "model_version": res["model_version"],
                     "policy_version": res["policy_version"], "policy_mode": res["policy_mode"],
                     "result_json": json.dumps(res), "explanation_json": None})
    db.insert_transactions(rows)
    db.audit("dataset_loaded", {"rows": len(rows), "path": path.name, "seconds": round(time.time() - t0, 1),
                                "model_version": engine.model_version})
    log.info("seeded %d synthetic transactions in %.1fs", len(rows), time.time() - t0)
    return len(rows)


def create_app(cfg: Settings = default_settings, db_path: str | None = None) -> Starlette:
    logging.basicConfig(level=cfg.log_level, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    db = Database(Path(db_path) if db_path else cfg.db_path)
    engine = RiskEngine(cfg.model_path, cfg.policy_path)
    saved = db.get_setting("policy")
    if saved:
        engine.mode = saved.get("mode", engine.mode)
        engine.hold_enabled = saved.get("hold_recommendations_enabled", True)
        engine.young_override_enabled = bool(saved.get("young_recipient_override", engine.young_override_enabled)) and bool(engine.young_override)
    STATE.update({"db": db, "engine": engine, "settings": cfg})
    key_dir = Path(db_path).parent if db_path else cfg.db_path.parent
    STATE["bio"] = BioStore(db, key_dir / "biometric.key")
    ev = cfg.results_dir / "evaluation_default.json"
    STATE["evaluation"] = json.loads(ev.read_text()) if ev.exists() else None
    if db.count_transactions() == 0:
        seed_from_dataset(db, engine, cfg.demo_data_path)
    elif saved:
        relabel_dataset()
    graph = GraphEngine()
    graph.build(db.query("SELECT transaction_id, sender_ref, recipient_ref, amount, event_time, risk_score, "
                         "risk_level, transaction_type FROM transactions WHERE source='dataset'"))
    STATE["graph"] = graph
    log.info("graph: %s", graph.stats())

    async def openapi_json(request: Request):
        return JSONResponse(api.openapi())

    async def docs(request: Request):
        return HTMLResponse(SWAGGER_HTML)

    routes = list(api.routes) + [Route("/openapi.json", openapi_json), Route("/docs", docs)]
    dist = cfg.frontend_dist
    if (dist / "index.html").exists():
        async def spa(request: Request):
            return FileResponse(dist / "index.html")
        if (dist / "assets").exists():
            routes.append(Mount("/assets", StaticFiles(directory=dist / "assets")))
        routes.append(Route("/", spa))
    else:
        async def root(request: Request):
            return JSONResponse({"name": "NeuroShield API", "docs": "/docs", "openapi": "/openapi.json",
                                 "note": "Frontend not built (see README)."})
        routes.append(Route("/", root))

    async def server_error(request: Request, exc: Exception):
        log.exception("unhandled error rid=%s", request.scope.get("state", {}).get("request_id"))
        return JSONResponse({"error": "Internal server error",
                             "request_id": request.scope.get("state", {}).get("request_id")}, status_code=500)

    async def not_found(request: Request, exc: Exception):
        return JSONResponse({"error": "Not found"}, status_code=404)

    app = Starlette(routes=routes, exception_handlers={500: server_error, 404: not_found})
    app.add_middleware(CORSMiddleware, allow_origins=list(cfg.cors_origins), allow_methods=["GET", "POST", "PUT", "DELETE"],
                       allow_headers=["content-type", "x-admin-token"], max_age=600)
    app.add_middleware(SecurityMiddleware, max_body_bytes=cfg.max_body_bytes, rate_per_minute=cfg.rate_limit_per_minute,
                       large_body_prefix="/api/v1/bio/", large_body_bytes=cfg.bio_max_body_bytes)
    return app


app = None
if __name__ != "__main__":
    import os
    if os.environ.get("NS_NO_AUTOAPP") != "1":
        app = create_app()

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(create_app(), host="127.0.0.1", port=8000, log_level="info")
