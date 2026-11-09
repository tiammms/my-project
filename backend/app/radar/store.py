"""Anonymised radar phrases and draft reports in a small SQLite file. Phrases older than 30 days are deleted."""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path

KEEP_DAYS = 30
ROOT = Path(__file__).resolve().parents[3]


def default_path() -> Path:
    return Path(os.environ.get("NS_RADAR_DB_PATH", str(ROOT / "backend/data/radar.db")))


class RadarStore:
    def __init__(self, path: Path | str | None = None):
        self.path = Path(path) if path else default_path()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with self._db() as c:
            c.execute("CREATE TABLE IF NOT EXISTS phrases (id INTEGER PRIMARY KEY, ts REAL NOT NULL, lang TEXT NOT NULL, "
                      "source TEXT NOT NULL, text TEXT NOT NULL)")
            c.execute("CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY, ts REAL NOT NULL, model TEXT NOT NULL, "
                      "phrases INTEGER NOT NULL, body TEXT NOT NULL)")

    def _db(self):
        return sqlite3.connect(self.path, timeout=10)

    def purge(self, now: float | None = None) -> int:
        cut = (now or time.time()) - KEEP_DAYS * 86400
        with self._lock, self._db() as c:
            n = c.execute("DELETE FROM phrases WHERE ts < ?", (cut,)).rowcount
            c.execute("DELETE FROM reports WHERE ts < ?", (cut,))
        return n

    def add(self, text: str, lang: str, source: str, ts: float | None = None) -> int:
        with self._lock, self._db() as c:
            return c.execute("INSERT INTO phrases (ts, lang, source, text) VALUES (?, ?, ?, ?)",
                             (ts or time.time(), lang, source, text)).lastrowid

    def recent(self, days: int = 7, limit: int = 400) -> list[dict]:
        self.purge()
        cut = time.time() - days * 86400
        with self._db() as c:
            rows = c.execute("SELECT id, lang, source, text FROM phrases WHERE ts >= ? ORDER BY id DESC LIMIT ?", (cut, limit)).fetchall()
        return [{"id": r[0], "lang": r[1], "source": r[2], "text": r[3]} for r in rows]

    def count(self, days: int = KEEP_DAYS) -> int:
        with self._db() as c:
            return c.execute("SELECT COUNT(*) FROM phrases WHERE ts >= ?", (time.time() - days * 86400,)).fetchone()[0]

    def save_report(self, model: str, phrases: int, body: dict) -> int:
        with self._lock, self._db() as c:
            return c.execute("INSERT INTO reports (ts, model, phrases, body) VALUES (?, ?, ?, ?)",
                             (time.time(), model, phrases, json.dumps(body, ensure_ascii=False))).lastrowid

    def last_report(self) -> dict | None:
        with self._db() as c:
            r = c.execute("SELECT id, ts, model, phrases, body FROM reports ORDER BY id DESC LIMIT 1").fetchone()
        return None if not r else {"id": r[0], "ts": r[1], "model": r[2], "phrases": r[3], **json.loads(r[4])}
