"""End-to-end HTTP latency benchmark for POST /api/v1/risk/score (server must be running).

Usage: python -m scripts.bench_api [--n 500] [--url http://127.0.0.1:8000]
Writes experiments/results/api_latency.json. Measures the full request: HTTP + validation +
featurization + model + explanation + SQLite write (persist=true) or without it (persist=false).
"""
import argparse
import json
import platform
import time
import urllib.request
from pathlib import Path

import numpy as np

from backend.app.services.scenarios import scenario_payload

ROOT = Path(__file__).resolve().parents[1]


def _cpu():
    try:   # Linux; other systems fall back to platform.processor()
        return next(l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name"))
    except (OSError, StopIteration):
        return platform.processor() or "unknown"


def run(url, body, n, warmup=30):
    data = json.dumps(body).encode()
    ts = []
    for i in range(n + warmup):
        req = urllib.request.Request(url + "/api/v1/risk/score", data=data, headers={"content-type": "application/json"})
        t0 = time.perf_counter()
        with urllib.request.urlopen(req) as r:
            r.read()
        if i >= warmup:
            ts.append((time.perf_counter() - t0) * 1000)
    a = np.array(ts)
    return {"n": n, "warmup": warmup, "p50_ms": float(np.percentile(a, 50)), "p95_ms": float(np.percentile(a, 95)),
            "p99_ms": float(np.percentile(a, 99)), "mean_ms": float(a.mean()), "throughput_rps_sequential": float(1000 / a.mean())}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=500)
    ap.add_argument("--url", default="http://127.0.0.1:8000")
    a = ap.parse_args()
    body = scenario_payload("social_engineering")
    res = {"no_persist": run(a.url, {**body, "persist": False}, a.n),
           "persist": run(a.url, {k: v for k, v in body.items()}, min(a.n, 200)),
           "environment": {"cpu": _cpu(),
                           "platform": platform.platform(), "client": "urllib, sequential, same host",
                           "server": "uvicorn single worker, OMP_NUM_THREADS=1"}}
    out = ROOT / "experiments/results/api_latency.json"
    out.write_text(json.dumps(res, indent=2))
    print(json.dumps(res, indent=1))
