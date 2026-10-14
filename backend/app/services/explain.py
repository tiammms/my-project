"""Local explanations by group occlusion (reference substitution).

For each feature group we replace the group's values with a *reference
legitimate profile* (medians of legitimate training transactions) and measure
how much the model's log-odds drop. Positive contribution = the group pushes
risk up. This is an occlusion / ablation attribution, NOT SHAP: contributions
are not guaranteed to sum to the total, and interactions are attributed to
every group involved. All groups are evaluated in a single batched predict.
"""
from __future__ import annotations

import math

import numpy as np

from .features import FEATURE_LABELS

GROUPS: dict[str, list[str]] = {
    "amount": ["amount_log", "amount_deviation"],
    "time_of_day": ["transaction_hour", "hour_sin", "hour_cos"],
    "transaction_type": ["transaction_type"],
    "sender_account_age": ["account_age_log"],
    "recipient_account_age": ["recipient_age_log"],
    "velocity": ["transactions_last_1h", "transactions_last_24h"],
    "new_recipient": ["new_recipient"],
    "location": ["geographic_anomaly"],
    "device": ["device_anomaly"],
    "recipient_reputation": ["recipient_risk_score"],
    "behavior": ["behavioral_anomaly_score", "rhythm_deviation", "hesitation_index"],
    "social_engineering": ["social_engineering_risk_score"],
    "graph": ["graph_risk_score"],
}

GROUP_LABELS = {
    "amount": "Transaction amount",
    "time_of_day": "Time of day",
    "transaction_type": "Transaction type",
    "sender_account_age": "Sender account age",
    "recipient_account_age": "Recipient account age",
    "velocity": "Transaction velocity",
    "new_recipient": "New recipient",
    "location": "Location",
    "device": "Device",
    "recipient_reputation": "Recipient reputation",
    "behavior": "Behavioral deviation",
    "social_engineering": "Social-engineering signals",
    "graph": "Money-flow graph",
}


def _logit(p: np.ndarray) -> np.ndarray:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p))


def occlusion(model, features: list[str], x: dict, reference: dict) -> list[dict]:
    """Return per-group contributions in log-odds, sorted by |contribution|."""
    base = np.array([[x[f] for f in features]], dtype=float)
    rows = [base[0]]
    names = []
    for g, cols in GROUPS.items():
        idx = [features.index(c) for c in cols if c in features]
        if not idx:
            continue
        r = base[0].copy()
        changed = False
        for i in idx:
            if math.isnan(r[i]):
                continue   # missing inputs are never attributed: absence of data is not evidence
            ref = reference.get(features[i], float("nan"))
            if r[i] != ref:
                changed = True
            r[i] = ref
        names.append((g, changed))
        rows.append(r)
    P = model.predict_proba(np.vstack(rows))[:, 1]
    L = _logit(P)
    out = []
    for k, (g, changed) in enumerate(names, start=1):
        out.append({"group": g, "label": GROUP_LABELS[g],
                    "contribution": float(L[0] - L[k]) if changed else 0.0})
    out.sort(key=lambda d: -abs(d["contribution"]))
    return out


def feature_label(f: str) -> str:
    return FEATURE_LABELS.get(f, f)
