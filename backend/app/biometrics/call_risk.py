"""Risk of an incoming call from the number alone, and one-time codes in SMS (same rules as the Android app, core/CallRisk.kt).

It cannot know whether a stranger is a scammer; it only says how much caution fits.
"""
from __future__ import annotations

import re

_KEYWORD = re.compile(r"(код|code|пароль|password|otp|parol|құпия|kod|подтвержд|никому)", re.I)
_CODE = re.compile(r"(?<![0-9])([0-9]{4,8})(?![0-9])")


def normalize(raw: str | None) -> str | None:
    if not raw or not raw.strip():
        return None
    d = "".join(ch for ch in raw if ch.isdigit() or ch == "+")
    return d or None


def assess(number: str | None, in_contacts: bool = False, home_code: str = "+7", calls_last_hour: int = 1) -> dict:
    n = normalize(number)
    reasons: list[tuple[str, int]] = []
    if n is None:
        reasons.append(("Номер скрыт", 30))
    else:
        if not in_contacts:
            reasons.append(("Номера нет в ваших контактах", 10))
        if (n.startswith("+") and not n.startswith(home_code)) or n.startswith("00"):
            reasons.append((f"Международный номер ({n})", 25))
        if calls_last_hour >= 3:
            reasons.append((f"Звонит уже {calls_last_hour}-й раз за час", 20))
        local = n.removeprefix(home_code).removeprefix("+")
        if not in_contacts and 3 <= len(local) <= 6:
            reasons.append(("Короткий номер: мошенники подделывают номера банков", 10))
    score = 0.0
    if reasons:
        p = 1.0
        for _, pts in reasons:
            p *= 1 - pts / 100
        score = 100 * (1 - p)
    level = "HIGH" if score >= 70 else "MEDIUM" if score >= 30 else "LOW"
    advice = [] if in_contacts else [
        "Если звонящий представится банком или полицией — положите трубку и перезвоните сами по официальному номеру.",
        "Никому не называйте коды из SMS."]
    return {"risk_score": round(score, 1), "risk_level": level, "reasons": [r for r, _ in reasons], "advice": advice}


def find_code(text: str) -> dict | None:
    t = text.lower()
    if not _KEYWORD.search(t):
        return None
    m = _CODE.search(t)
    if not m:
        return None
    c = m.group(1)
    return {"masked": c[0] + "•" * max(1, len(c) - 2) + c[-1], "length": len(c)}
