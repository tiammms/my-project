"""SQLite persistence (stdlib sqlite3).

SQLAlchemy was not installable in the build environment, so this module is a
small repository layer with parameterised SQL only. All SQL lives here, so a
move to PostgreSQL means re-implementing this file (schema is portable).
Only synthetic, pseudonymous references are stored - no names, card numbers,
passwords, message contents or raw telemetry streams.
"""
from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id TEXT PRIMARY KEY,
    created_at     TEXT NOT NULL,
    source         TEXT NOT NULL,           -- dataset | api | scenario
    event_time     TEXT,
    sender_ref     TEXT,
    recipient_ref  TEXT,
    amount         REAL NOT NULL,
    transaction_type TEXT NOT NULL,
    raw_json       TEXT NOT NULL,
    label          INTEGER,                 -- synthetic ground truth (dataset rows only)
    fraud_type     TEXT,
    legit_tag      TEXT,
    risk_score     REAL NOT NULL,
    risk_level     TEXT NOT NULL,
    action         TEXT NOT NULL,
    model_version  TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    policy_mode    TEXT NOT NULL,
    result_json    TEXT NOT NULL,
    explanation_json TEXT
);
CREATE INDEX IF NOT EXISTS ix_tx_score ON transactions(risk_score);
CREATE INDEX IF NOT EXISTS ix_tx_level ON transactions(risk_level);
CREATE INDEX IF NOT EXISTS ix_tx_time  ON transactions(event_time);
CREATE INDEX IF NOT EXISTS ix_tx_recipient ON transactions(recipient_ref);
CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    transaction_id TEXT,
    details_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL
);
"""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Database:
    def __init__(self, path: Path):
        self.path = Path(path)
        if str(path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.RLock()
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._conn.execute("PRAGMA journal_mode=WAL") if str(path) != ":memory:" else None
            self._conn.commit()

    # -- generic ------------------------------------------------------------
    def execute(self, sql: str, params: tuple = ()) -> sqlite3.Cursor:
        with self._lock:
            cur = self._conn.execute(sql, params)
            self._conn.commit()
            return cur

    def executescript(self, script: str) -> None:
        with self._lock:
            self._conn.executescript(script)
            self._conn.commit()

    def query(self, sql: str, params: tuple = ()) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, params).fetchall()]

    def scalar(self, sql: str, params: tuple = ()) -> Any:
        with self._lock:
            r = self._conn.execute(sql, params).fetchone()
            return r[0] if r else None

    # -- transactions -------------------------------------------------------
    def insert_transactions(self, rows: list[dict]) -> None:
        cols = ["transaction_id", "created_at", "source", "event_time", "sender_ref", "recipient_ref", "amount",
                "transaction_type", "raw_json", "label", "fraud_type", "legit_tag", "risk_score", "risk_level",
                "action", "model_version", "policy_version", "policy_mode", "result_json", "explanation_json"]
        sql = f"INSERT OR REPLACE INTO transactions ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})"
        with self._lock:
            self._conn.executemany(sql, [tuple(r.get(c) for c in cols) for r in rows])
            self._conn.commit()

    def get_transaction(self, tx_id: str) -> Optional[dict]:
        rows = self.query("SELECT * FROM transactions WHERE transaction_id = ?", (tx_id,))
        return rows[0] if rows else None

    def count_transactions(self) -> int:
        return int(self.scalar("SELECT COUNT(*) FROM transactions") or 0)

    def set_explanation(self, tx_id: str, explanation: dict) -> None:
        self.execute("UPDATE transactions SET explanation_json = ? WHERE transaction_id = ?",
                     (json.dumps(explanation), tx_id))

    # -- audit ----------------------------------------------------------------
    def audit(self, event_type: str, details: dict, actor: str = "system", transaction_id: Optional[str] = None):
        self.execute("INSERT INTO audit_log (ts, event_type, actor, transaction_id, details_json) VALUES (?,?,?,?,?)",
                     (now_iso(), event_type, actor, transaction_id, json.dumps(details)))

    # -- settings -------------------------------------------------------------
    def get_setting(self, key: str, default: Any = None) -> Any:
        v = self.scalar("SELECT value_json FROM settings WHERE key = ?", (key,))
        return json.loads(v) if v is not None else default

    def set_setting(self, key: str, value: Any) -> None:
        self.execute("INSERT OR REPLACE INTO settings (key, value_json) VALUES (?, ?)", (key, json.dumps(value)))
