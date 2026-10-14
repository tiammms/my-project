"""Shared feature contract for NeuroShield.

This module is the single source of truth for turning a *raw transaction
record* (flat dict, identical for the offline dataset and the online API)
into model features and component scores. Training (ml/) and serving
(backend/) both import it, so there is no train/serve skew by construction.
A parity test in backend/tests checks this.

Component scorers here are deterministic, documented heuristics:
  * behavioral_component  - deviation of a session from the user's own baseline
  * graph_component       - structural money-flow signals around sender/recipient
  * recipient_component   - recipient reputation (confirmed reports, account age)
  * social_component      - co-occurrence of independent social-engineering signals
They are NOT trained models; the trained model is the hybrid gradient-boosting
classifier that consumes their outputs together with transaction features.
"""
from __future__ import annotations

import math
from typing import Any, Optional

TRANSACTION_TYPES = ["card_payment", "bill_payment", "p2p_transfer", "international_transfer"]

# ---------------------------------------------------------------------------
# Raw record schema (flat). None means "not available / not provided".
# ---------------------------------------------------------------------------
RAW_FIELDS: dict[str, str] = {
    "amount": "Transaction amount (synthetic KZT)",
    "transaction_type": "One of " + ", ".join(TRANSACTION_TYPES),
    "transaction_hour": "Local hour 0-23",
    "account_age_days": "Sender account age in days",
    "recipient_age_days": "Recipient account age in days (None if external/unknown)",
    "transactions_last_1h": "Sender transactions in previous hour",
    "transactions_last_24h": "Sender transactions in previous 24 hours",
    "baseline_log_mean": "Mean of log(amount) over sender history (None if <3 tx)",
    "baseline_log_std": "Std of log(amount) over sender history",
    "baseline_tx_count": "Number of past sender transactions",
    "new_recipient": "Sender never paid this recipient before",
    "geographic_anomaly": "Session location outside usual region (None if unknown)",
    "device_anomaly": "Device not previously seen for this user (None if unknown)",
    # behavioral telemetry (aggregated, consent-based)
    "behavioral_consent": "User consented to aggregated interaction telemetry",
    "typing_interval_mean_ms": "Mean inter-key interval in this session",
    "typing_interval_cv": "Coefficient of variation of inter-key intervals",
    "confirm_pause_s": "Pause before pressing confirm (seconds)",
    "correction_rate": "Share of corrected keystrokes",
    "screen_sequence_deviation": "0-1 distance of screen path from user's usual flow",
    "bl_typing_interval_mean_ms": "Baseline mean", "bl_typing_interval_mean_ms_std": "Baseline std",
    "bl_typing_interval_cv": "Baseline mean", "bl_typing_interval_cv_std": "Baseline std",
    "bl_confirm_pause_s": "Baseline mean", "bl_confirm_pause_s_std": "Baseline std",
    "bl_correction_rate": "Baseline mean", "bl_correction_rate_std": "Baseline std",
    "bl_screen_sequence_deviation": "Baseline mean", "bl_screen_sequence_deviation_std": "Baseline std",
    "bl_sessions": "Number of past sessions in behavioral baseline",
    "bl_screen_flow": "Flow the navigation baseline was built on: new_payee | known_payee (must match this session)",
    "bl_screen_sessions": "Past sessions of the same flow behind the navigation baseline",
    # session context (consent-based, may be None)
    "active_call_during_session": "A phone call was active during the session (None if unknown)",
    "user_reported_external_instruction": "User answered that someone instructed this transfer (None if not asked)",
    # graph context (time-respecting, computed from past transfers only)
    "recipient_distinct_senders_7d": "Distinct senders to recipient in past 7 days",
    "recipient_passthrough_ratio_7d": "Recipient account-to-account outflow / inflow in past 7 days (merchant spending excluded)",
    "recipient_reported": "Recipient was the subject of a confirmed fraud report before now",
    "recipient_linked_to_reported": "Recipient exchanged money with a reported node in past 14 days",
    "sender_inbound_distinct_7d": "Distinct counterparties that paid the sender in past 7 days",
    "sender_passthrough_ratio_24h": "Sender account-to-account outflow (incl. this tx) / inflow in past 24 hours (merchant spending excluded)",
}

BEHAVIOR_METRICS = {
    # metric: (std floor, role)
    "typing_interval_mean_ms": (15.0, "rhythm"),
    "typing_interval_cv": (0.04, "rhythm"),
    "confirm_pause_s": (0.6, "hesitation"),
    "correction_rate": (0.03, "hesitation"),
    "screen_sequence_deviation": (0.08, "flow"),
}

HUMAN_METRIC = {
    "typing_interval_mean_ms": "typing speed",
    "typing_interval_cv": "typing rhythm regularity",
    "confirm_pause_s": "pause before confirming",
    "correction_rate": "rate of input corrections",
    "screen_sequence_deviation": "navigation path through the app",
}


def _num(v: Any) -> Optional[float]:
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if math.isnan(f):
        return None
    return f


def _bool(v: Any) -> Optional[bool]:
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    if isinstance(v, str):
        return v.strip().lower() in ("1", "true", "yes")
    return bool(v)


def _noisy_or(weights: list[float]) -> float:
    p = 1.0
    for w in weights:
        p *= 1.0 - max(0.0, min(1.0, w))
    return 1.0 - p


# ---------------------------------------------------------------------------
# Behavioral analytics
# ---------------------------------------------------------------------------
def behavioral_component(raw: dict) -> dict:
    consent = _bool(raw.get("behavioral_consent"))
    n_sessions = _num(raw.get("bl_sessions")) or 0.0
    out: dict[str, Any] = {
        "score": None, "status": "unavailable", "reliability": "none",
        "rhythm_deviation": None, "hesitation_index": None,
        "available_features": [], "missing_features": [], "deviations": {},
    }
    if not consent:
        out["reason"] = "No consent for interaction telemetry - component disabled"
        out["missing_features"] = list(BEHAVIOR_METRICS)
        return out
    zs: dict[str, float] = {}
    signed: dict[str, float] = {}
    not_comparable = []
    for m, (floor, _role) in BEHAVIOR_METRICS.items():
        x, mu, sd = _num(raw.get(m)), _num(raw.get(f"bl_{m}")), _num(raw.get(f"bl_{m}_std"))
        if m == "screen_sequence_deviation" and x is not None:
            # Adding a new payee is a different screen flow: only compare against a baseline built
            # on the same flow, with at least 3 such sessions. Otherwise the metric is not comparable.
            ns = _num(raw.get("bl_screen_sessions"))
            ns = n_sessions if ns is None else ns
            if ns < 3:
                not_comparable.append(m)
                out["missing_features"].append(m)
                continue
        if x is None or mu is None:
            out["missing_features"].append(m)
            continue
        s = (x - mu) / max(sd or 0.0, floor)
        signed[m] = s
        zs[m] = abs(s)
        out["available_features"].append(m)
    if not zs or n_sessions < 3:
        out["reason"] = ("Insufficient baseline (fewer than 3 past sessions)" if zs
                         else "No telemetry metrics in this session")
        out["missing_features"] = sorted(set(out["missing_features"]) | (set(BEHAVIOR_METRICS) if not zs else set()))
        return out
    rms = math.sqrt(sum(z * z for z in zs.values()) / len(zs))
    score = 100.0 * (1.0 - math.exp(-max(rms - 1.0, 0.0) / 1.5))
    rhythm = [zs[m] for m in ("typing_interval_mean_ms", "typing_interval_cv") if m in zs]
    hes = [max(signed[m], 0.0) for m in ("confirm_pause_s", "correction_rate") if m in signed]
    rhythm_dev = 100.0 * (1.0 - math.exp(-max((sum(rhythm) / len(rhythm)) - 1.0, 0.0) / 1.5)) if rhythm else None
    hes_idx = 100.0 * (1.0 - math.exp(-max((sum(hes) / len(hes)) - 1.0, 0.0) / 1.5)) if hes else None
    coverage = len(zs) / len(BEHAVIOR_METRICS)
    rel = min(1.0, n_sessions / 20.0) * coverage
    out.update({
        "score": round(score, 2), "status": "available",
        "reliability": "high" if rel >= 0.7 else ("medium" if rel >= 0.35 else "low"),
        "reliability_value": round(rel, 3),
        "rhythm_deviation": None if rhythm_dev is None else round(rhythm_dev, 2),
        "hesitation_index": None if hes_idx is None else round(hes_idx, 2),
        "deviations": {k: round(v, 2) for k, v in signed.items()},
        "not_comparable": not_comparable,
        "screen_flow": raw.get("bl_screen_flow"),
        "baseline_sessions": int(n_sessions),
    })
    return out


# ---------------------------------------------------------------------------
# Recipient reputation & graph structure
# ---------------------------------------------------------------------------
def recipient_component(raw: dict) -> dict:
    reported = bool(_bool(raw.get("recipient_reported")))
    age = _num(raw.get("recipient_age_days"))
    signals = []
    w = []
    if reported:
        w.append(0.85); signals.append("Recipient was previously the subject of a confirmed fraud report")
    if age is not None and age < 7:
        w.append(0.35); signals.append(f"Recipient account is very new ({int(age)} days)")
    elif age is not None and age < 30:
        w.append(0.18); signals.append(f"Recipient account is young ({int(age)} days)")
    return {"score": round(100 * _noisy_or(w), 2), "signals": signals}


def graph_component(raw: dict) -> dict:
    d_in = _num(raw.get("recipient_distinct_senders_7d")) or 0.0
    pt = _num(raw.get("recipient_passthrough_ratio_7d"))
    linked = bool(_bool(raw.get("recipient_linked_to_reported")))
    s_in = _num(raw.get("sender_inbound_distinct_7d")) or 0.0
    s_pt = _num(raw.get("sender_passthrough_ratio_24h"))
    age = _num(raw.get("recipient_age_days"))
    # Merchants legitimately receive from many payers and never forward funds,
    # so recipient fan-in / pass-through is only evaluated for account-to-account flows.
    if raw.get("transaction_type") in ("card_payment", "bill_payment"):
        d_in, pt = 0.0, None
    w, signals = [], []
    if linked:
        w.append(0.45); signals.append("Recipient exchanged funds with a reported account in the past 14 days")
    if d_in >= 3:
        young = age is not None and age < 60
        ww = min(1.0, (d_in - 2) / 6.0) * (0.55 if young else 0.25)
        w.append(ww)
        signals.append(f"Fan-in: recipient received from {int(d_in)} distinct senders in 7 days"
                       + (" while being a young account" if young else ""))
    # Forwarding band: a mule moves on most of what it received (~60-130%). Sending far MORE than was received
    # means spending one's own money, which is not pass-through (v2 fix).
    if pt is not None and 0.6 <= pt <= 1.3 and d_in >= 2:
        w.append(0.3 * min(1.0, pt)); signals.append(f"Recipient forwards most incoming funds (pass-through {pt:.0%})")
    if s_pt is not None and 0.7 <= s_pt <= 1.3 and s_in >= 2:
        ww = min(1.0, s_in / 5.0) * 0.6 * min(1.0, s_pt)
        w.append(ww)
        signals.append(f"Sender is forwarding {min(s_pt,9.99):.0%} of funds received from {int(s_in)} counterparties within 24h (layering pattern)")
    return {"score": round(100 * _noisy_or(w), 2), "signals": signals}


# ---------------------------------------------------------------------------
# Social engineering (APP scam) heuristic
# ---------------------------------------------------------------------------
def amount_deviation(raw: dict) -> Optional[float]:
    a = _num(raw.get("amount"))
    mu, sd = _num(raw.get("baseline_log_mean")), _num(raw.get("baseline_log_std"))
    n = _num(raw.get("baseline_tx_count")) or 0
    if a is None or a <= 0 or mu is None or n < 3:
        return None
    return (math.log(a) - mu) / max(sd or 0.0, 0.35)


def social_component(raw: dict, beh: dict, rec: dict) -> dict:
    ttype = raw.get("transaction_type")
    groups: dict[str, tuple[float, str]] = {}
    if _bool(raw.get("new_recipient")):
        groups["new_recipient"] = (0.15, "Transfer to a recipient the customer has never paid before")
    dev = amount_deviation(raw)
    if dev is not None and dev > 2.0:
        groups["amount"] = (min(0.35, 0.1 * dev), f"Amount is {dev:.1f} standard deviations above the customer's usual")
    hes = beh.get("hesitation_index")
    if beh.get("status") == "available" and hes is not None and hes >= 30:
        groups["hesitation"] = (0.3 * hes / 100, "Long hesitation and frequent corrections compared with the customer's own baseline")
    if _bool(raw.get("active_call_during_session")):
        groups["call"] = (0.3, "A phone call was active while the transfer was being made")
    if _bool(raw.get("user_reported_external_instruction")):
        groups["instruction"] = (0.6, "Customer indicated that someone else instructed this transfer")
    if rec["score"] >= 15:
        groups["recipient"] = (0.2 * rec["score"] / 100, "Recipient reputation signals (new or reported account)")
    p = _noisy_or([g[0] for g in groups.values()])
    if ttype == "card_payment":
        p *= 0.3
    score = 100 * p
    capped = False
    if len(groups) < 2 and score > 45:
        score, capped = 45.0, True
    return {
        "score": round(score, 2),
        "signals": [g[1] for g in groups.values()],
        "independent_signal_groups": len(groups),
        "capped_single_signal": capped,
    }


# ---------------------------------------------------------------------------
# Feature vector
# ---------------------------------------------------------------------------
TXN_FEATURES = [
    "amount_log", "transaction_type", "account_age_log", "recipient_age_log",
    "transaction_hour", "hour_sin", "hour_cos", "transactions_last_1h", "transactions_last_24h",
    "amount_deviation", "new_recipient", "geographic_anomaly", "device_anomaly",
]
HYBRID_EXTRA = [
    "recipient_risk_score", "behavioral_anomaly_score", "rhythm_deviation", "hesitation_index",
    "behavioral_available", "social_engineering_risk_score", "graph_risk_score",
]
HYBRID_FEATURES = TXN_FEATURES + HYBRID_EXTRA
CATEGORICAL = ["transaction_type"]

FEATURE_LABELS = {
    "amount_log": "Transaction amount",
    "transaction_type": "Transaction type",
    "account_age_log": "Sender account age",
    "recipient_age_log": "Recipient account age",
    "transaction_hour": "Time of day", "hour_sin": "Time of day", "hour_cos": "Time of day",
    "transactions_last_1h": "Transactions in the last hour",
    "transactions_last_24h": "Transactions in the last 24h",
    "amount_deviation": "Amount vs customer's usual",
    "new_recipient": "New recipient",
    "geographic_anomaly": "Unusual location",
    "device_anomaly": "New device",
    "recipient_risk_score": "Recipient reputation",
    "behavioral_anomaly_score": "Behavioral deviation",
    "rhythm_deviation": "Typing rhythm vs baseline",
    "hesitation_index": "Hesitation vs baseline",
    "behavioral_available": "Behavioral data availability",
    "social_engineering_risk_score": "Social-engineering signals",
    "graph_risk_score": "Money-flow graph structure",
}


def _f(b: Optional[bool]) -> float:
    return float("nan") if b is None else float(b)


def featurize(raw: dict) -> tuple[dict, dict]:
    """Return (feature_dict, components). NaN encodes 'missing'."""
    nan = float("nan")
    amount = _num(raw.get("amount")) or 0.0
    hour = _num(raw.get("transaction_hour"))
    acc = _num(raw.get("account_age_days"))
    rage = _num(raw.get("recipient_age_days"))
    ttype = raw.get("transaction_type")
    beh = behavioral_component(raw)
    rec = recipient_component(raw)
    gra = graph_component(raw)
    soc = social_component(raw, beh, rec)
    dev = amount_deviation(raw)
    feats = {
        "amount_log": math.log1p(max(amount, 0.0)),
        "transaction_type": float(TRANSACTION_TYPES.index(ttype)) if ttype in TRANSACTION_TYPES else nan,
        "account_age_log": math.log1p(acc) if acc is not None else nan,
        "recipient_age_log": math.log1p(rage) if rage is not None else nan,
        "transaction_hour": hour if hour is not None else nan,
        "hour_sin": math.sin(2 * math.pi * hour / 24) if hour is not None else nan,
        "hour_cos": math.cos(2 * math.pi * hour / 24) if hour is not None else nan,
        "transactions_last_1h": _num(raw.get("transactions_last_1h")) if raw.get("transactions_last_1h") is not None else nan,
        "transactions_last_24h": _num(raw.get("transactions_last_24h")) if raw.get("transactions_last_24h") is not None else nan,
        "amount_deviation": dev if dev is not None else nan,
        "new_recipient": _f(_bool(raw.get("new_recipient"))),
        "geographic_anomaly": _f(_bool(raw.get("geographic_anomaly"))),
        "device_anomaly": _f(_bool(raw.get("device_anomaly"))),
        "recipient_risk_score": rec["score"],
        "behavioral_anomaly_score": beh["score"] if beh["score"] is not None else nan,
        "rhythm_deviation": beh["rhythm_deviation"] if beh["rhythm_deviation"] is not None else nan,
        "hesitation_index": beh["hesitation_index"] if beh["hesitation_index"] is not None else nan,
        "behavioral_available": 1.0 if beh["status"] == "available" else 0.0,
        "social_engineering_risk_score": soc["score"],
        "graph_risk_score": gra["score"],
    }
    feats = {k: (nan if v is None else float(v)) for k, v in feats.items()}
    return feats, {"behavioral": beh, "recipient": rec, "graph": gra, "social": soc, "amount_deviation": dev}


# ---------------------------------------------------------------------------
# Rule-based baseline (what a simple legacy rule set would do)
# ---------------------------------------------------------------------------
RULES = [
    # id, weight, description
    ("R1", 2, "Amount >= 250,000"),
    ("R2", 1, "New recipient and amount >= 60,000"),
    ("R3", 2, "3 or more transactions in the last hour"),
    ("R4", 1, "New device and amount >= 50,000"),
    ("R5", 1, "Unusual location on an international transfer"),
    ("R6", 1, "Night time (00-05) and amount >= 50,000"),
    ("R7", 1, "Amount > 3 std above customer's usual"),
    ("R8", 2, "Recipient is on the confirmed-fraud report list"),
]
RULE_FLAG_THRESHOLD = 2


def rule_score(raw: dict) -> tuple[int, list[str]]:
    a = _num(raw.get("amount")) or 0.0
    hit = []
    if a >= 250_000: hit.append("R1")
    if _bool(raw.get("new_recipient")) and a >= 60_000: hit.append("R2")
    if (_num(raw.get("transactions_last_1h")) or 0) >= 3: hit.append("R3")
    if _bool(raw.get("device_anomaly")) and a >= 50_000: hit.append("R4")
    if _bool(raw.get("geographic_anomaly")) and raw.get("transaction_type") == "international_transfer": hit.append("R5")
    h = _num(raw.get("transaction_hour"))
    if h is not None and 0 <= h <= 5 and a >= 50_000: hit.append("R6")
    dev = amount_deviation(raw)
    if dev is not None and dev > 3: hit.append("R7")
    if _bool(raw.get("recipient_reported")): hit.append("R8")
    w = {r[0]: r[1] for r in RULES}
    return sum(w[h_] for h_ in hit), hit
