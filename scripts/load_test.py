"""Concurrent load test for POST /api/v1/risk/score.

Starts the API with uvicorn (1 and 2 worker processes), drives it with N concurrent keep-alive clients for a fixed
duration per level, and records throughput and latency percentiles. Client and server share the same machine, so
results are a lower bound for a dedicated server. The rate limiter is raised for the test (it would otherwise
return 429 by design).

Usage: python -m scripts.load_test [--duration 8] [--levels 1,4,8,16] [--workers 1,2]
Output: experiments/results/load_test.json
"""
from __future__ import annotations

import argparse
import http.client
import json
import os
import platform
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

import numpy as np

from backend.app.services.scenarios import scenario_payload

ROOT = Path(__file__).resolve().parents[1]


def _cpu():
    try:   # Linux; other systems fall back to platform.processor()
        return next(l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name"))
    except (OSError, StopIteration):
        return platform.processor() or "unknown"
PORT = 8765


def wait_up(timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=2); c.request("GET", "/health")
            if c.getresponse().status == 200:
                return True
        except OSError:
            time.sleep(0.5)
    return False


def drive(concurrency, duration, body):
    data = json.dumps(body).encode()
    lat, errors, lock = [], [0], threading.Lock()
    stop = time.time() + duration

    def conn():
        c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=30)
        c.connect()
        c.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)   # avoid Nagle + delayed-ACK 40 ms artefact
        return c

    def worker():
        c = conn()
        local = []
        while time.time() < stop:
            t0 = time.perf_counter()
            try:
                c.request("POST", "/api/v1/risk/score", body=data, headers={"content-type": "application/json"})
                r = c.getresponse(); r.read()
                if r.status != 200:
                    errors[0] += 1
                else:
                    local.append((time.perf_counter() - t0) * 1000)
            except OSError:
                errors[0] += 1
                c = conn()
        with lock:
            lat.extend(local)

    th = [threading.Thread(target=worker) for _ in range(concurrency)]
    t0 = time.time()
    for t in th:
        t.start()
    for t in th:
        t.join()
    el = time.time() - t0
    a = np.array(lat) if lat else np.array([np.nan])
    return {"concurrency": concurrency, "requests": len(lat), "errors": errors[0], "seconds": round(el, 2),
            "throughput_rps": len(lat) / el, "p50_ms": float(np.nanpercentile(a, 50)), "p95_ms": float(np.nanpercentile(a, 95)),
            "p99_ms": float(np.nanpercentile(a, 99))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=float, default=8)
    ap.add_argument("--levels", default="1,4,8,16")
    ap.add_argument("--workers", default="1,2")
    a = ap.parse_args()
    body = {**scenario_payload("social_engineering"), "persist": False}
    db = ROOT / "backend/data/loadtest.db"
    env = {**os.environ, "NS_RATE_LIMIT_PER_MINUTE": "100000000", "NS_DB_PATH": str(db), "OMP_NUM_THREADS": "1", "NS_LOG_LEVEL": "WARNING"}
    # seed the DB once (single process) so that multiple workers do not race on first-start seeding
    subprocess.run([sys.executable, "-c", "from backend.app.main import create_app; create_app()"], cwd=ROOT, env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out = {"endpoint": "POST /api/v1/risk/score (persist=false, full explanation)", "runs": [],
           "environment": {"cpu": _cpu(),
                           "cpu_count": os.cpu_count(), "platform": platform.platform(),
                           "note": "Client threads and server share the same machine; numbers are a lower bound."}}
    for w in [int(x) for x in a.workers.split(",")]:
        proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "backend.app.main:app", "--port", str(PORT), "--workers", str(w),
                                 "--log-level", "warning", "--no-access-log"], cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            if not wait_up():
                raise RuntimeError("server did not start")
            drive(2, 2, body)   # warm-up
            for c in [int(x) for x in a.levels.split(",")]:
                r = drive(c, a.duration, body); r["workers"] = w
                out["runs"].append(r)
                print(f"workers={w} conc={c:3d} rps={r['throughput_rps']:7.1f} p50={r['p50_ms']:6.1f} p95={r['p95_ms']:6.1f} p99={r['p99_ms']:6.1f} errors={r['errors']}")
        finally:
            proc.terminate(); proc.wait(timeout=20)
    prev = ROOT / "experiments/results/load_test.json"
    if prev.exists():   # keep the manually verified findings (see docs) when re-running
        out["findings"] = json.loads(prev.read_text()).get("findings", [])
    (ROOT / "experiments/results/load_test.json").write_text(json.dumps(out, indent=2))
    db.unlink(missing_ok=True)
    for sfx in ("-wal", "-shm"):
        Path(str(db) + sfx).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
