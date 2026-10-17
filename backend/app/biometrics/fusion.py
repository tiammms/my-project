"""Combined risk: face + voice/call content + transaction (with its behavioural component).

Rule-based and transparent on purpose: there is no labelled multimodal dataset to train a fusion model on,
so a trained model here would be fiction. Rules:
- each available check contributes a 0-100 component score; missing checks are listed, never guessed;
- combined score = noisy-OR of components (1 - prod(1 - s/100));
- two or more independent components at MEDIUM or above escalate the result to HIGH;
- a single component can reach HIGH on its own only if it is itself HIGH.
"""
from __future__ import annotations

import math

ADVICE = {
    "liveness_failed": "Проверка живости не пройдена: не подтверждайте операцию, пока личность не подтверждена другим способом.",
    "face_mismatch": "Лицо не совпало с зарегистрированным: остановите операцию и подтвердите личность в отделении или через банк.",
    "call_tactics": "Положите трубку и перезвоните в банк сами по номеру на обратной стороне карты.",
    "credentials_request": "Никому не сообщайте коды из SMS, пароли, PIN и CVV — банк их никогда не спрашивает.",
    "transaction": "Перед переводом проверьте получателя; при сомнениях отложите перевод.",
    "behaviour": "Поведение в приложении необычно для этого клиента: уточните, не диктует ли кто-то действия.",
    "low": "Явных признаков мошенничества не найдено. Оставайтесь внимательны: мошенники часто звонят повторно.",
}


def level_of(score: float) -> str:
    return "HIGH" if score >= 70 else "MEDIUM" if score >= 30 else "LOW"


def combine(face: dict | None, voice: dict | None, tx: dict | None) -> dict:
    comps, reasons, advice, missing = [], [], [], []

    if face:
        s = float(face.get("risk_score") or 0)
        comps.append({"component": "face", "title": "Лицо и живость", "risk_score": s, "risk_level": level_of(s),
                      "source_check": face.get("check_id"), "details": face.get("summary")})
        for r in face.get("reasons", [])[:4]:
            reasons.append(f"Лицо: {r}")
        if face.get("kind") == "liveness" and not face.get("passed"):
            advice.append(ADVICE["liveness_failed"])
        if face.get("kind") == "face_verify" and face.get("match") is False:
            advice.append(ADVICE["face_mismatch"])
    else:
        missing.append("проверка лица")

    if voice:
        s = float(voice.get("risk_score") or 0)
        comps.append({"component": "voice", "title": "Голос и содержание звонка", "risk_score": s, "risk_level": level_of(s),
                      "source_check": voice.get("check_id"), "details": voice.get("summary")})
        tactics = (voice.get("call_content") or {}).get("tactics", [])
        for t in tactics[:5]:
            reasons.append(f"Звонок: {t['title'].lower()}")
        if tactics:
            advice.append(ADVICE["call_tactics"])
        if any(t["category"] == "credentials_request" for t in tactics):
            advice.append(ADVICE["credentials_request"])
        for t in (voice.get("advice") or []):
            if t not in advice:
                advice.append(t)
    else:
        missing.append("проверка голоса / звонка")

    if tx:
        s = float(tx["risk_score"])
        comps.append({"component": "transaction", "title": "Перевод (модель Saqtan)", "risk_score": s,
                      "risk_level": tx["risk_level"], "source_check": tx["transaction_id"],
                      "details": f"{tx['risk_level']} · действие {tx['recommended_action']}"})
        for f in tx.get("top_risk_factors", [])[:3]:
            reasons.append(f"Перевод: {f['label']} — {f['detail']}")
        if tx["risk_level"] != "LOW":
            advice.append(ADVICE["transaction"])
        beh = (tx.get("components") or {}).get("behavioral") or {}
        if beh.get("score") is not None and beh["score"] >= 50:
            reasons.append(f"Поведение: отклонение от обычного для клиента ({beh['score']:.0f}/100)")
            advice.append(ADVICE["behaviour"])
    else:
        missing.append("перевод")

    if not comps:
        return {"risk_level": None, "risk_score": None, "components": [], "missing": missing,
                "reasons": ["нет ни одной проверки для объединения"], "advice": []}

    score = 100 * (1 - math.prod(1 - c["risk_score"] / 100 for c in comps))
    elevated = [c for c in comps if c["risk_level"] in ("MEDIUM", "HIGH")]
    level = max((c["risk_level"] for c in comps), key=["LOW", "MEDIUM", "HIGH"].index)
    escalated = False
    if len(elevated) >= 2 and level != "HIGH":
        level, escalated = "HIGH", True
    if level == "LOW":
        score = min(score, 29.9)
    elif level == "MEDIUM":
        score = min(max(score, 30.0), 69.9)
    else:
        score = max(score, 70.0)
    if escalated:
        reasons.insert(0, "Несколько независимых проверок одновременно показывают риск")
    if level == "LOW":
        advice.append(ADVICE["low"])
    action = {"LOW": "Можно продолжать", "MEDIUM": "Дополнительное подтверждение: перезвонить клиенту по известному номеру",
              "HIGH": "Остановить операцию и передать на проверку аналитику"}[level]
    return {"risk_level": level, "risk_score": round(score, 1), "recommended_action": action, "components": comps,
            "missing": missing, "escalated_by_multiple_signals": escalated, "reasons": reasons,
            "advice": list(dict.fromkeys(advice)),
            "method": "Прозрачные правила (noisy-OR + эскалация при ≥2 независимых сигналах); не обученная модель — "
                      "размеченных мультимодальных данных для обучения нет",
            "disclaimer": "Рекомендация, а не автоматическая блокировка. Решение принимает человек."}
