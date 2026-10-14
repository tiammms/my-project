"""Evasion-augmented training (part 5).

Idea: add copies of TRAINING frauds in which the fraudster hides one signal, so the model learns not to rely on any
single signal. To avoid fooling ourselves, the evasion tricks are split in half:
  SEEN   - used to create the augmented training copies
  UNSEEN - never used in training; only to measure whether robustness generalises
Pre-registered adoption rule (validation only, fixed before looking at test):
  adopt the augmented model if, versus the standard model,
   (a) clean validation PR-AUC drops by at most 0.005,
   (b) validation false-positive rate at the step-up threshold rises by at most 0.2 percentage points,
   (c) mean validation recall over UNSEEN single evasions rises by at least 1 percentage point.
Thresholds for each model are derived on validation with the same balanced-policy rule as train.py.
Usage: python -m ml.evaluation.robust_training  ->  experiments/results/robust_training.json
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from backend.app.services.features import HYBRID_FEATURES, featurize
from ml.evaluation.adversary import EVASIONS
from ml.training import pipeline as P

ROOT = Path(__file__).resolve().parents[2]
SEEN = ["calm_behavior", "aged_mule_account", "small_amounts", "no_call_no_admission"]
UNSEEN = ["unreported_mule", "slow_layering", "own_device_and_region", "daytime_only"]
SEED = 42


def evade(recs, names, rng=None):
    out = copy.deepcopy(recs)
    for i, r in enumerate(out):
        name = names[i] if isinstance(names, list) else names
        for f in EVASIONS[name]:
            f(r)
    return out


def featmat(recs):
    return pd.DataFrame([featurize(r)[0] for r in recs])[HYBRID_FEATURES]


def thresholds(y, s):
    t_high = P.threshold_for_precision(y, s, 0.90)
    t_med = min(max(P.threshold_for_recall(y, s, 0.95), P.threshold_for_alert_rate(s, 0.04)), t_high * 0.999)
    return t_med, t_high


def evaluate(m, X_clean, y, fraud_recs, t_med):
    s = 100 * m.predict_proba(X_clean.to_numpy())[:, 1]
    out = {"pr_auc": P.ranking_metrics(y, s)["pr_auc"], "roc_auc": P.ranking_metrics(y, s)["roc_auc"],
           "fpr_at_stepup": float((s[y == 0] >= t_med).mean()), "recall_at_stepup": float((s[y == 1] >= t_med).mean()), "evasions": {}}
    for name in SEEN + UNSEEN + ["all_combined"]:
        Xe = featmat(evade(fraud_recs, name))
        se = 100 * m.predict_proba(Xe.to_numpy())[:, 1]
        out["evasions"][name] = float((se >= t_med).mean())
    out["mean_seen"] = float(np.mean([out["evasions"][k] for k in SEEN]))
    out["mean_unseen"] = float(np.mean([out["evasions"][k] for k in UNSEEN]))
    return out


def main():
    bnd = joblib.load(ROOT / "ml/artifacts/model.joblib")
    params = bnd.get("hyperparameters") or {"learning_rate": 0.05, "max_leaf_nodes": 15, "max_iter": 300}
    df = P.load_dataset(ROOT / "ml/data/generated/default/transactions.csv")
    df = df[(df["split"] != "burn_in") & (df["transaction_type"] != "card_payment")].reset_index(drop=True)
    recs = P.raw_records(df)
    X = featmat(recs); y = df["is_fraud"].to_numpy(); sp = df["split"].to_numpy()
    tr, va, te = sp == "train", sp == "validation", sp == "test"
    rng = np.random.default_rng(SEED)
    tr_fraud = [recs[i] for i in np.where(tr & (y == 1))[0]]
    # one augmented copy per training fraud, with a randomly chosen SEEN evasion
    aug = evade(tr_fraud, [SEEN[int(k)] for k in rng.integers(0, len(SEEN), len(tr_fraud))])
    X_aug = pd.concat([X[tr], featmat(aug)], ignore_index=True)
    y_aug = np.concatenate([y[tr], np.ones(len(aug), dtype=int)])
    va_fraud = [recs[i] for i in np.where(va & (y == 1))[0]]
    te_fraud = [recs[i] for i in np.where(te & (y == 1))[0]]
    res = {"seen_evasions": SEEN, "unseen_evasions": UNSEEN, "augmented_copies": len(aug), "hyperparameters": params,
           "adoption_rule": __doc__.split("Pre-registered adoption rule (validation only, fixed before looking at test):")[1].split("Thresholds for")[0].strip(),
           "models": {}}
    for name, (Xt, yt) in {"standard": (X[tr], y[tr]), "evasion_augmented": (X_aug, y_aug)}.items():
        m = P.make_hgb(HYBRID_FEATURES, SEED, **params); m.fit(Xt.to_numpy(), yt)
        sv = 100 * m.predict_proba(X[va].to_numpy())[:, 1]
        t_med, t_high = thresholds(y[va], sv)
        res["models"][name] = {"medium_threshold": t_med, "high_threshold": t_high,
                               "validation": evaluate(m, X[va], y[va], va_fraud, t_med),
                               "test": evaluate(m, X[te], y[te], te_fraud, t_med)}
        if name == "evasion_augmented":
            joblib.dump(m, ROOT / "ml/artifacts/candidate_evasion_augmented.joblib")
        v, t = res["models"][name]["validation"], res["models"][name]["test"]
        print(f"{name:18s} VAL pr {v['pr_auc']:.4f} fpr {v['fpr_at_stepup']:.4f} rec {v['recall_at_stepup']:.3f} seen {v['mean_seen']:.3f} unseen {v['mean_unseen']:.3f} | "
              f"TEST pr {t['pr_auc']:.4f} fpr {t['fpr_at_stepup']:.4f} rec {t['recall_at_stepup']:.3f} seen {t['mean_seen']:.3f} unseen {t['mean_unseen']:.3f} all {t['evasions']['all_combined']:.3f}")
    a, b = res["models"]["standard"]["validation"], res["models"]["evasion_augmented"]["validation"]
    checks = {"pr_auc_drop_le_0.005": a["pr_auc"] - b["pr_auc"] <= 0.005,
              "fpr_rise_le_0.2pp": (b["fpr_at_stepup"] - a["fpr_at_stepup"]) * 100 <= 0.2,
              "unseen_recall_gain_ge_1pp": (b["mean_unseen"] - a["mean_unseen"]) * 100 >= 1.0}
    res["checks"] = checks
    res["adopted"] = all(checks.values())
    print("checks", checks, "-> adopted" if res["adopted"] else "-> NOT adopted")
    (ROOT / "experiments/results/robust_training.json").write_text(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
