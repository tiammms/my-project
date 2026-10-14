"""Adaptive-adversary stress test.

Takes the frauds of the synthetic TEST period and lets the fraudster hide one signal at a time (then all at once),
re-computes every feature/component from the modified raw record, and measures how much fraud the deployed model
(balanced policy) and the rule baseline still catch. Legitimate traffic is unchanged, so false-positive rates do not move.

This is a what-if on synthetic data, not a measured attack: it shows which signals the detection depends on.
Usage: python -m ml.evaluation.adversary  ->  experiments/results/adversary_stress.json
"""
from __future__ import annotations

import copy
import json
import math
from pathlib import Path

import joblib
import numpy as np

from backend.app.services.features import RULE_FLAG_THRESHOLD, featurize, rule_score
from ml.training import pipeline as P

ROOT = Path(__file__).resolve().parents[2]
BEH = ["typing_interval_mean_ms", "typing_interval_cv", "confirm_pause_s", "correction_rate", "screen_sequence_deviation"]


def calm(r):          # victim / attacker behaves exactly like the customer's own baseline
    for m in BEH:
        if r.get(m) is not None and r.get(f"bl_{m}") is not None:
            r[m] = r[f"bl_{m}"]


def no_social(r):     # no phone call during the session, never admits being instructed
    if r.get("active_call_during_session") is not None:
        r["active_call_during_session"] = False
    if r.get("user_reported_external_instruction") is not None:
        r["user_reported_external_instruction"] = False


def clean_device(r):  # remote-access tool / SIM swap on the customer's own device and region
    r["device_anomaly"] = False if r.get("device_anomaly") is not None else None
    r["geographic_anomaly"] = False if r.get("geographic_anomaly") is not None else None


def aged_mule(r):     # mule account recruited from long-standing customers
    if r.get("recipient_age_days") is not None:
        r["recipient_age_days"] = float(r["recipient_age_days"]) + 365


def fresh_mule(r):    # brand-new mule nobody has reported yet
    r["recipient_reported"] = False
    r["recipient_linked_to_reported"] = False


def slow_layering(r):  # mule network slows down: one sender per mule, no visible forwarding
    if r.get("recipient_distinct_senders_7d") is not None:
        r["recipient_distinct_senders_7d"] = min(1, int(r["recipient_distinct_senders_7d"]))
    r["recipient_passthrough_ratio_7d"] = None
    r["sender_passthrough_ratio_24h"] = None
    r["sender_inbound_distinct_7d"] = 0


def small_amounts(r):  # split into amounts typical for the customer
    mu = r.get("baseline_log_mean")
    if mu is not None:
        r["amount"] = min(float(r["amount"]), 1.5 * math.exp(float(mu)))


def night_to_day(r):
    r["transaction_hour"] = 14


EVASIONS = {"calm_behavior": [calm], "no_call_no_admission": [no_social], "own_device_and_region": [clean_device],
            "aged_mule_account": [aged_mule], "unreported_mule": [fresh_mule], "slow_layering": [slow_layering],
            "small_amounts": [small_amounts], "daytime_only": [night_to_day]}
EVASIONS["all_combined"] = [f for fs in EVASIONS.values() for f in fs]
LABELS = {"calm_behavior": "Спокойное поведение (как обычно)", "no_call_no_admission": "Нет звонка и признания",
          "own_device_and_region": "Своё устройство и регион", "aged_mule_account": "Старый счёт мула (+1 год)",
          "unreported_mule": "Мул без жалоб", "slow_layering": "Медленная сеть мулов", "small_amounts": "Обычные суммы",
          "daytime_only": "Только днём", "all_combined": "Всё сразу"}


def main():
    bnd = joblib.load(ROOT / "ml/artifacts/model.joblib")
    pol = json.loads((ROOT / "ml/artifacts/policy.json").read_text())["modes"]["balanced"]
    m, feats = bnd["model"], bnd["features"]
    df = P.load_dataset(ROOT / "ml/data/generated/default/transactions.csv")
    df = df[(df["split"] == "test") & (df["transaction_type"] != "card_payment") & (df["is_fraud"] == 1)].reset_index(drop=True)
    recs = P.raw_records(df)
    types = df["fraud_type"].to_numpy()

    def evaluate(rs):
        X = np.array([[featurize(r)[0][f] for f in feats] for r in rs])
        s = 100 * m.predict_proba(X)[:, 1]
        rules = np.array([rule_score(r)[0] for r in rs])
        res = {"recall_stepup_plus": float((s >= pol["medium_threshold"]).mean()), "recall_high": float((s >= pol["high_threshold"]).mean()),
               "rules_recall": float((rules >= RULE_FLAG_THRESHOLD).mean()), "by_type": {}}
        for t in sorted(set(types)):
            k = types == t
            res["by_type"][t] = {"n": int(k.sum()), "recall_stepup_plus": float((s[k] >= pol["medium_threshold"]).mean()),
                                 "rules_recall": float((rules[k] >= RULE_FLAG_THRESHOLD).mean())}
        return res

    out = {"n_frauds": len(recs), "policy": "balanced", "baseline": evaluate(recs), "evasions": {}, "labels": LABELS,
           "note": "What-if on synthetic test frauds; legitimate traffic unchanged, so FPR is unchanged."}
    b = out["baseline"]
    print(f"{'baseline':24s} model {b['recall_stepup_plus']:.3f} (high {b['recall_high']:.3f}) | rules {b['rules_recall']:.3f}")
    for name, fns in EVASIONS.items():
        rs = copy.deepcopy(recs)
        for r in rs:
            for f in fns:
                f(r)
        e = evaluate(rs)
        e["drop_pp"] = 100 * (b["recall_stepup_plus"] - e["recall_stepup_plus"])
        out["evasions"][name] = e
        print(f"{name:24s} model {e['recall_stepup_plus']:.3f} (high {e['recall_high']:.3f}) drop {e['drop_pp']:5.1f}pp | rules {e['rules_recall']:.3f} | "
              + " ".join(f"{t}:{v['recall_stepup_plus']:.2f}" for t, v in e["by_type"].items()))
    (ROOT / "experiments/results/adversary_stress.json").write_text(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
