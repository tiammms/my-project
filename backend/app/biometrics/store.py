"""Persistence for the biometric module: consented face templates (encrypted), check history, privacy settings.

Never stored: images, audio, video frames. Full transcripts are stored only if the user enables it.
"""
from __future__ import annotations

import json
import os
import secrets
from pathlib import Path

import numpy as np

from ..database.db import Database, now_iso

SCHEMA = """
CREATE TABLE IF NOT EXISTS face_templates (
    subject_ref     TEXT PRIMARY KEY,
    created_at      TEXT NOT NULL,
    consent_at      TEXT NOT NULL,
    consent_version TEXT NOT NULL,
    model           TEXT NOT NULL,
    template_enc    BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS bio_checks (
    check_id    TEXT PRIMARY KEY,
    created_at  TEXT NOT NULL,
    kind        TEXT NOT NULL,          -- face_photo | liveness | face_verify | voice | call_text | combined
    risk_level  TEXT,
    risk_score  REAL,
    summary     TEXT NOT NULL,
    result_json TEXT NOT NULL           -- results only: no images, audio or embeddings
);
CREATE INDEX IF NOT EXISTS ix_bio_time ON bio_checks(created_at);
"""

CONSENT_VERSION = "2026-10-v1"
CONSENT_TEXT = ("Я соглашаюсь, что Saqtan сохранит математический шаблон моего лица (128 чисел, не фотографию) "
                "в зашифрованном виде на этом компьютере, чтобы сравнивать меня при следующих проверках. "
                "Я могу удалить шаблон в любой момент в разделе «Настройки и конфиденциальность».")

DEFAULT_PRIVACY = {"store_history": True, "store_transcripts": False, "allow_enrollment": True,
                   "history_retention_days": 30, "speech_to_text": True}


class Cipher:
    """Fernet (AES-128-CBC + HMAC-SHA256). Key from NS_BIOMETRIC_KEY or a local key file (created on first use)."""

    def __init__(self, key_path: Path):
        from cryptography.fernet import Fernet
        key = os.environ.get("NS_BIOMETRIC_KEY", "").encode()
        if not key:
            key_path.parent.mkdir(parents=True, exist_ok=True)
            if not key_path.exists():
                key_path.write_bytes(Fernet.generate_key())
                try:
                    os.chmod(key_path, 0o600)
                except OSError:
                    pass
            key = key_path.read_bytes().strip()
        self.f = Fernet(key)

    def enc(self, v: np.ndarray) -> bytes:
        return self.f.encrypt(np.asarray(v, np.float32).tobytes())

    def dec(self, b: bytes) -> np.ndarray:
        return np.frombuffer(self.f.decrypt(b), np.float32)


class BioStore:
    def __init__(self, db: Database, key_path: Path):
        self.db = db
        db.executescript(SCHEMA)
        self.key_path = key_path
        self._cipher = None

    @property
    def cipher(self) -> Cipher:
        if self._cipher is None:
            self._cipher = Cipher(self.key_path)
        return self._cipher

    # ---- privacy settings ----
    def privacy(self) -> dict:
        return {**DEFAULT_PRIVACY, **(self.db.get_setting("bio_privacy") or {})}

    def set_privacy(self, changes: dict) -> dict:
        p = {**self.privacy(), **changes}
        self.db.set_setting("bio_privacy", p)
        self.db.audit("bio_privacy_change", {"changes": changes}, actor="user")
        self.purge_expired()
        return p

    # ---- templates ----
    def enroll(self, subject_ref: str, emb: np.ndarray, model: str) -> dict:
        ts = now_iso()
        self.db.execute("INSERT OR REPLACE INTO face_templates (subject_ref, created_at, consent_at, consent_version, model, "
                        "template_enc) VALUES (?,?,?,?,?,?)", (subject_ref, ts, ts, CONSENT_VERSION, model, self.cipher.enc(emb)))
        self.db.audit("bio_enroll", {"subject_ref": subject_ref, "consent_version": CONSENT_VERSION}, actor="user")
        return {"subject_ref": subject_ref, "created_at": ts, "consent_version": CONSENT_VERSION}

    def template(self, subject_ref: str) -> np.ndarray | None:
        r = self.db.query("SELECT template_enc FROM face_templates WHERE subject_ref = ?", (subject_ref,))
        return self.cipher.dec(r[0]["template_enc"]) if r else None

    def subjects(self) -> list[dict]:
        return self.db.query("SELECT subject_ref, created_at, consent_at, consent_version, model FROM face_templates ORDER BY created_at DESC")

    def delete_subject(self, subject_ref: str) -> bool:
        n = self.db.execute("DELETE FROM face_templates WHERE subject_ref = ?", (subject_ref,)).rowcount
        if n:
            self.db.audit("bio_template_deleted", {"subject_ref": subject_ref}, actor="user")
        return bool(n)

    # ---- history ----
    def record(self, kind: str, result: dict, summary: str) -> str:
        cid = "BC-" + secrets.token_hex(5).upper()
        result["check_id"] = cid
        if not self.privacy()["store_history"]:
            result["stored"] = False
            return cid
        self.db.execute("INSERT INTO bio_checks (check_id, created_at, kind, risk_level, risk_score, summary, result_json) "
                        "VALUES (?,?,?,?,?,?,?)", (cid, now_iso(), kind, result.get("risk_level"), result.get("risk_score"),
                                                   summary[:300], json.dumps(result, default=str)))
        result["stored"] = True
        return cid

    def history(self, kind: str | None, limit: int, offset: int) -> dict:
        where, params = ("WHERE kind = ?", (kind,)) if kind else ("", ())
        items = self.db.query(f"SELECT check_id, created_at, kind, risk_level, risk_score, summary FROM bio_checks {where} "
                              f"ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?", params + (limit, offset))
        total = self.db.scalar(f"SELECT COUNT(*) FROM bio_checks {where}", params)
        return {"items": items, "total": int(total or 0)}

    def get_check(self, check_id: str) -> dict | None:
        r = self.db.query("SELECT * FROM bio_checks WHERE check_id = ?", (check_id,))
        if not r:
            return None
        d = r[0]
        d["result"] = json.loads(d.pop("result_json"))
        return d

    def counts(self) -> dict:
        rows = self.db.query("SELECT kind, risk_level, COUNT(*) n FROM bio_checks GROUP BY kind, risk_level")
        return {"by_kind_level": rows, "total": sum(r["n"] for r in rows)}

    def purge_expired(self) -> int:
        days = int(self.privacy()["history_retention_days"])
        return self.db.execute("DELETE FROM bio_checks WHERE created_at < datetime('now', ?)", (f"-{days} days",)).rowcount

    def clear_history(self) -> int:
        n = self.db.execute("DELETE FROM bio_checks").rowcount
        self.db.audit("bio_history_cleared", {"deleted": n}, actor="user")
        return n

    def erase_all(self) -> dict:
        h = self.db.execute("DELETE FROM bio_checks").rowcount
        t = self.db.execute("DELETE FROM face_templates").rowcount
        self.db.audit("bio_erase_all", {"history_deleted": h, "templates_deleted": t}, actor="user")
        return {"history_deleted": h, "templates_deleted": t}
