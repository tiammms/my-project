"""Rule-based analysis of what is SAID in a call (transcript or pasted text).

It looks for well-known social-engineering tactics (asking for codes, "safe account", urgency, posing as the
bank or police, secrecy, remote-access apps). It is transparent and rule-based, not a trained model.
It assesses the content of the conversation - it never labels the speaker as a fraudster.
Languages: Russian, Kazakh, English (case-insensitive, ё = е).
"""
from __future__ import annotations

import re

from . import link_check

# The phrase rules live in shared/scam_rules.json (one source for the server, Android and iOS).
from .scam_rules import CATEGORIES, CRITICAL

_COMPILED = {k: [re.compile(p, re.I | re.U) for p in v["patterns"]] for k, v in CATEGORIES.items()}
MAX_TEXT = 20000


def _norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower().replace("ё", "е")).strip()


def analyze(text: str) -> dict:
    t = _norm(text[:MAX_TEXT])
    found = []
    for k, pats in _COMPILED.items():
        hits, spans = [], []
        for p in pats:
            for m in p.finditer(t):
                if any(m.start() < e and m.end() > st for st, e in spans):
                    continue          # overlapping match of another pattern: same evidence
                spans.append((m.start(), m.end()))
                a, b = max(0, m.start() - 40), min(len(t), m.end() + 40)
                hits.append({"match": m.group(0), "context": ("…" if a else "") + t[a:b] + ("…" if b < len(t) else "")})
                if len(hits) >= 3:
                    break
            if len(hits) >= 3:
                break
        if hits:
            c = CATEGORIES[k]
            found.append({"category": k, "title": c["title"], "weight": c["weight"], "advice": c["advice"], "evidence": hits})
    # links: look-alike brands, shorteners, IP hosts, punycode, throw-away domains (same rules as the Android app)
    links = [f for f in link_check.scan(text[:MAX_TEXT]) if f["score"] >= 30]
    if links:
        worst = max(links, key=lambda f: f["score"])
        found.append({"category": "phishing_link", "title": f"Подозрительная ссылка: {worst['host']}",
                      "weight": max(30, min(60, worst["score"])),
                      "advice": "Не открывайте ссылку и не вводите на ней данные. Зайдите в приложение банка или на сайт сами.",
                      "evidence": [{"match": f["host"], "context": "; ".join(f["reasons"])} for f in links[:3]]})
    # "weak" categories (just mentioning a bank, a card, a code) add a little to the score
    # but are never counted as a separate tactic: they cannot lift one tactic to HIGH on their own
    cats = {f["category"] for f in found if not CATEGORIES.get(f["category"], {}).get("weak")}
    score = 100 * (1 - __import__("math").prod(1 - f["weight"] / 100 for f in found)) if found else 0.0
    # A single tactic alone never yields HIGH (same safeguard as the transaction engine) —
    # except a forged bank/government site (brand in the address but not the official domain + risky signs).
    forged_site = any(f["score"] >= 70 for f in links)
    if len(cats) <= 1 and not forged_site:
        score = min(score, 45.0)
    if forged_site:
        score = max(score, 75.0)
    critical = bool(cats & CRITICAL) or any(f["score"] >= 50 for f in links)
    if critical and len(cats) >= 2:
        score = max(score, 75.0)
    level = "HIGH" if score >= 70 else "MEDIUM" if score >= 30 else "LOW"
    words = len(t.split())
    return {"risk_score": round(score, 1), "risk_level": level, "tactics": found, "tactics_count": len(cats),
            "words_analyzed": words,
            "summary": ("Признаков мошеннического сценария не найдено." if not cats else
                        f"Найдено тактик социальной инженерии: {len(cats)}."),
            "method": "Правила (словари фраз на русском, казахском, английском) и проверка ссылок — прозрачно, не обученная модель",
            "disclaimer": "Оценивается содержание разговора, а не личность собеседника. Голос или манера речи не делают человека мошенником.",
            "short_text_note": "Текст очень короткий — оценка ненадёжна." if words < 8 else None}
