"""Risk Engine: model + component scores + policy + explanation -> decision."""
from __future__ import annotations

import json
import math
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Optional

import joblib
import numpy as np

from . import explain
from .features import HUMAN_METRIC, featurize

ACTIONS = {
    "ALLOW": "Recommend allowing the transaction.",
    "STEP_UP_VERIFICATION": "Recommend additional confirmation (e.g. re-confirm the recipient, show a scam warning, "
                            "or a call-back) before the transfer proceeds.",
    "MANUAL_REVIEW": "Recommend analyst review. Hold recommendations are disabled in the current settings.",
    "REVIEW_OR_TEMPORARY_HOLD": "Recommend analyst review and, if the integrating bank's policy allows, a temporary "
                                "hold until the customer is contacted. NeuroShield never blocks payments itself.",
}


def tx_to_raw(tx) -> dict:
    """Map an API TransactionIn into the flat raw record used by features.featurize."""
    raw = {
        "amount": tx.amount, "transaction_type": tx.transaction_type, "transaction_hour": tx.transaction_hour,
        "account_age_days": tx.account_age_days, "recipient_age_days": tx.recipient_age_days,
        "transactions_last_1h": tx.transactions_last_1h, "transactions_last_24h": tx.transactions_last_24h,
        "new_recipient": tx.new_recipient, "geographic_anomaly": tx.geographic_anomaly,
        "device_anomaly": tx.device_anomaly, "active_call_during_session": tx.active_call_during_session,
        "user_reported_external_instruction": tx.user_reported_external_instruction,
        "baseline_log_mean": None, "baseline_log_std": None, "baseline_tx_count": 0,
        "behavioral_consent": False, "bl_sessions": 0,
    }
    if tx.sender_baseline:
        b = tx.sender_baseline
        raw.update({"baseline_log_mean": math.log(b.median_amount), "baseline_log_std": b.log_std,
                    "baseline_tx_count": b.tx_count})
    beh = tx.behavioral
    if beh:
        raw["behavioral_consent"] = beh.consent
        raw["bl_sessions"] = beh.baseline_sessions
        if beh.screen_flow:
            raw["bl_screen_flow"] = beh.screen_flow
        if beh.screen_flow_sessions is not None:
            raw["bl_screen_sessions"] = beh.screen_flow_sessions
        if beh.consent and beh.session:
            for k, v in beh.session.model_dump().items():
                raw[k] = v
        if beh.consent and beh.baseline:
            for k, v in beh.baseline.model_dump().items():
                if v:
                    raw[f"bl_{k}"] = v["mean"]
                    raw[f"bl_{k}_std"] = v["std"]
    g = tx.graph
    if g:
        raw.update(g.model_dump())
    return raw


class LatencyRecorder:
    def __init__(self, n=2000):
        self.buf = deque(maxlen=n)
        self.lock = threading.Lock()

    def add(self, ms: float):
        with self.lock:
            self.buf.append(ms)

    def stats(self) -> dict:
        with self.lock:
            a = np.array(self.buf)
        if len(a) == 0:
            return {"count": 0}
        return {"count": int(len(a)), "p50_ms": float(np.percentile(a, 50)), "p95_ms": float(np.percentile(a, 95)),
                "p99_ms": float(np.percentile(a, 99))}


class RiskEngine:
    def __init__(self, model_path: Path, policy_path: Path):
        b = joblib.load(model_path)
        self.model = b["model"]
        self.kind = b["kind"]
        self.features: list[str] = b["features"]
        self.reference: dict = b["reference"]
        self.ranges: dict = b["ranges"]
        self.model_version: str = b["model_version"]
        self.trained_at: str = b["trained_at"]
        self.bundle_meta = {k: v for k, v in b.items() if k not in ("model", "reference", "ranges")}
        self.policy = json.loads(Path(policy_path).read_text())
        self.mode: str = self.policy["default_mode"]
        self.hold_enabled = True
        self.young_override = (self.policy.get("segment_overrides") or {}).get("young_recipient")
        self.young_override_enabled = bool(self.young_override and self.young_override.get("enabled_by_default"))
        self.latency = LatencyRecorder()
        if self.kind == "lr":
            raise RuntimeError("Serving expects a tree model bundle (numpy input)")

    # -- policy ---------------------------------------------------------------
    @property
    def thresholds(self) -> dict:
        m = self.policy["modes"][self.mode]
        return {"medium": m["medium_threshold"], "high": m["high_threshold"]}

    def override_applies(self, raw: Optional[dict]) -> bool:
        o = self.young_override
        if not (raw is not None and o and self.young_override_enabled and o.get("mode") == self.mode):
            return False
        age = raw.get("recipient_age_days")
        return age is not None and age == age and float(age) < o["max_recipient_age_days"]

    def thresholds_for(self, raw: Optional[dict] = None) -> dict:
        t = dict(self.thresholds)
        if self.override_applies(raw):
            t["medium"] = min(self.young_override["medium_threshold"], t["high"])
            t["segment_override"] = "young_recipient"
        return t

    def level_for(self, score: float, raw: Optional[dict] = None) -> str:
        t = self.thresholds_for(raw)
        return "HIGH" if score >= t["high"] else ("MEDIUM" if score >= t["medium"] else "LOW")

    def action_for(self, level: str) -> str:
        if level == "LOW":
            return "ALLOW"
        if level == "MEDIUM":
            return "STEP_UP_VERIFICATION"
        return "REVIEW_OR_TEMPORARY_HOLD" if self.hold_enabled else "MANUAL_REVIEW"

    # -- scoring --------------------------------------------------------------
    def vector(self, feats: dict) -> np.ndarray:
        return np.array([[feats[f] for f in self.features]], dtype=float)

    def score_many(self, raws: list[dict]) -> np.ndarray:
        X = np.array([[featurize(r)[0][f] for f in self.features] for r in raws], dtype=float)
        return 100.0 * self.model.predict_proba(X)[:, 1]

    def score_bulk(self, raws: list[dict], ids: list[str]) -> list[dict]:
        """Fast path for loading the demo dataset: batched prediction, explanation computed lazily on request."""
        fc = [featurize(r) for r in raws]
        X = np.array([[f[0][k] for k in self.features] for f in fc], dtype=float)
        P = self.model.predict_proba(X)[:, 1]
        out = []
        for raw, (feats, comp), p, tid in zip(raws, fc, P, ids):
            score = round(100.0 * float(p), 3)
            level = self.level_for(score, raw)
            warnings, conf, notes = self._quality(feats, comp, score, raw)
            act = self.action_for(level)
            out.append({"transaction_id": tid, "risk_score": score, "risk_level": level,
                        "recommended_action": act, "action_description": ACTIONS[act],
                        "confidence": conf, "confidence_notes": notes, "transaction_model_score": score,
                        "behavioral_anomaly_score": comp["behavioral"]["score"],
                        "graph_risk_score": comp["graph"]["score"],
                        "social_engineering_risk_score": comp["social"]["score"],
                        "recipient_risk_score": comp["recipient"]["score"],
                        "components": {"behavioral": comp["behavioral"], "graph": comp["graph"],
                                       "social_engineering": comp["social"], "recipient": comp["recipient"],
                                       "amount_deviation_sigma": None if comp["amount_deviation"] is None else round(comp["amount_deviation"], 2),
                                       "model_output_probability": float(p)},
                        "top_risk_factors": [], "mitigating_factors": [],
                        "policy_version": self.policy["policy_version"], "policy_mode": self.mode,
                        "thresholds": self.thresholds_for(raw), "model_version": self.model_version,
                        "evaluation_warnings": warnings, "processing_ms": None, "simulated": True})
        return out

    def score(self, raw: dict, transaction_id: Optional[str] = None, with_explanation: bool = True) -> dict:
        t0 = time.perf_counter()
        feats, comp = featurize(raw)
        p = float(self.model.predict_proba(self.vector(feats))[0, 1])
        score = round(100.0 * p, 3)
        level = self.level_for(score, raw)
        contributions = explain.occlusion(self.model, self.features, feats, self.reference) if with_explanation else []
        policy_notes = []
        # Safeguard: behavioural signals alone may never escalate to a hold recommendation.
        if level == "HIGH" and contributions:
            beh = next((c for c in contributions if c["group"] == "behavior"), None)
            if beh and beh["contribution"] > 0:
                x2 = dict(feats)
                for f in explain.GROUPS["behavior"]:
                    x2[f] = self.reference.get(f, float("nan"))
                p2 = float(self.model.predict_proba(self.vector(x2))[0, 1])
                if self.level_for(100 * p2, raw) == "LOW":
                    level = "MEDIUM"
                    policy_notes.append("Downgraded from HIGH to MEDIUM: behavioral deviation alone may not trigger a "
                                        "hold recommendation (fairness safeguard).")
        action = self.action_for(level)
        factors = [self._factor(c, raw, feats, comp) for c in contributions if abs(c["contribution"]) >= 0.25]
        top = [f for f in factors if f["direction"] == "increases_risk"][:6]
        mitig = [f for f in factors if f["direction"] == "decreases_risk"][:4]
        warnings, conf, conf_notes = self._quality(feats, comp, score, raw)
        warnings += policy_notes
        beh = comp["behavioral"]
        res = {
            "transaction_id": transaction_id or f"API-{uuid.uuid4().hex[:12].upper()}",
            "risk_score": score, "risk_level": level, "recommended_action": action,
            "action_description": ACTIONS[action],
            "confidence": conf, "confidence_notes": conf_notes,
            "transaction_model_score": score,
            "behavioral_anomaly_score": beh["score"],
            "graph_risk_score": comp["graph"]["score"],
            "social_engineering_risk_score": comp["social"]["score"],
            "recipient_risk_score": comp["recipient"]["score"],
            "components": {
                "behavioral": beh, "graph": comp["graph"], "social_engineering": comp["social"],
                "recipient": comp["recipient"],
                "amount_deviation_sigma": None if comp["amount_deviation"] is None else round(comp["amount_deviation"], 2),
                "model_output_probability": p,
            },
            "top_risk_factors": top, "mitigating_factors": mitig,
            "policy_version": self.policy["policy_version"], "policy_mode": self.mode,
            "thresholds": self.thresholds_for(raw), "model_version": self.model_version,
            "evaluation_warnings": warnings,
            "processing_ms": 0.0, "simulated": True,
        }
        ms = (time.perf_counter() - t0) * 1000
        res["processing_ms"] = round(ms, 3)
        self.latency.add(ms)
        return res

    # -- explanation text ----------------------------------------------------
    def _factor(self, c: dict, raw: dict, feats: dict, comp: dict) -> dict:
        g = c["group"]
        up = c["contribution"] > 0
        d = ""
        amt = raw.get("amount") or 0
        if g == "amount":
            dev = comp["amount_deviation"]
            d = (f"{amt:,.0f} KZT is {dev:.1f} standard deviations {'above' if dev >= 0 else 'below'} this customer's usual amounts"
                 if dev is not None else f"{amt:,.0f} KZT; no customer amount history available")
        elif g == "recipient_reputation":
            d = "; ".join(comp["recipient"]["signals"]) or "No negative reputation signals for the recipient"
        elif g == "recipient_account_age":
            a = raw.get("recipient_age_days")
            d = f"Recipient account age: {a:.0f} days" if a is not None else "Recipient is external (age unknown)"
        elif g == "sender_account_age":
            d = f"Sender account age: {raw.get('account_age_days', 0):.0f} days"
        elif g == "behavior":
            b = comp["behavioral"]
            if b["status"] != "available":
                d = "Behavioral data unavailable: " + b.get("reason", "")
            else:
                devs = sorted(b["deviations"].items(), key=lambda kv: -abs(kv[1]))[:3]
                d = "; ".join(f"{HUMAN_METRIC[k]}: {v:+.1f} std vs own baseline" for k, v in devs)
        elif g == "social_engineering":
            d = "; ".join(comp["social"]["signals"]) or "No social-engineering signals"
        elif g == "graph":
            d = "; ".join(comp["graph"]["signals"]) or "No suspicious money-flow structure around sender or recipient"
        elif g == "device":
            v = raw.get("device_anomaly")
            d = "Device not previously seen for this customer" if v else ("Known device" if v is False else "Device information unavailable")
        elif g == "location":
            v = raw.get("geographic_anomaly")
            d = "Session location outside the customer's usual region" if v else ("Usual location" if v is False else "Location unavailable")
        elif g == "velocity":
            d = f"{raw.get('transactions_last_1h', 0)} transactions in the last hour, {raw.get('transactions_last_24h', 0)} in 24 hours"
        elif g == "new_recipient":
            d = "First payment to this recipient" if raw.get("new_recipient") else "Recipient paid before"
        elif g == "time_of_day":
            d = f"Initiated at {int(raw.get('transaction_hour', 0)):02d}:00"
        elif g == "transaction_type":
            d = str(raw.get("transaction_type", "")).replace("_", " ")
        return {"group": g, "label": c["label"], "contribution": round(c["contribution"], 3),
                "direction": "increases_risk" if up else "decreases_risk", "detail": d}

    def _quality(self, feats: dict, comp: dict, score: float, raw: Optional[dict] = None):
        warnings = ["Model trained and evaluated on synthetic data only; not validated on real bank traffic."]
        notes = []
        penalty = 0
        beh = comp["behavioral"]
        if beh["status"] != "available":
            notes.append("Behavioral component unavailable (" + beh.get("reason", "no data") + "); score relies on other signals.")
            penalty += 1
        elif beh["reliability"] == "low":
            notes.append("Behavioral baseline is short; behavioral deviation is less reliable.")
            penalty += 1
        if math.isnan(feats.get("amount_deviation", float("nan"))):
            notes.append("No customer amount history (fewer than 3 past transactions).")
            penalty += 1
        ood = [f for f, (lo, hi) in self.ranges.items()
               if not math.isnan(feats.get(f, float("nan"))) and (feats[f] < lo or feats[f] > hi)]
        if ood:
            warnings.append("Inputs outside the training range for: " + ", ".join(explain.feature_label(f) for f in ood)
                            + ". Treat the score with caution.")
            penalty += 1
        t = self.thresholds_for(raw)
        if t.get("segment_override"):
            notes.append("Young-recipient policy override active: step-up threshold raised to "
                         f"{t['medium']:.1f} for transfers to accounts younger than {self.young_override['max_recipient_age_days']} days.")
        for name, thr in (("medium", t["medium"]), ("high", t["high"])):
            if thr > 0 and abs(score - thr) / thr < 0.15:
                notes.append(f"Score is close to the {name} threshold ({thr:.1f}); small input changes may change the level.")
                penalty += 1
        if comp["social"].get("capped_single_signal"):
            notes.append("Only one social-engineering signal present; that component was capped.")
        conf = "high" if penalty == 0 else ("medium" if penalty == 1 else "low")
        if not notes:
            notes.append("All components available and the score is far from decision thresholds.")
        return warnings, conf, notes
