"""Fairness experiment for the 'young recipient' segment (legitimate transfers to accounts < 30 days old).

Pre-registered selection rule (fixed BEFORE looking at test):
  choose the variant with the lowest VALIDATION false-alarm rate (step-up or higher) on young-recipient legit
  transfers, among variants whose validation PR-AUC is within 0.01 of the best variant.
Each variant gets its own balanced-policy thresholds from validation (same rule as train.py).
Output: experiments/results/fairness_young_recipient.json
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

from backend.app.services.features import HYBRID_FEATURES
from ml.training import pipeline as P

ROOT = Path(__file__).resolve().parents[2]
VARIANTS = {
    "hybrid_all_features": HYBRID_FEATURES,
    "hybrid_without_direct_recipient_age": [f for f in HYBRID_FEATURES if f != "recipient_age_log"],
}
GRID = {"learning_rate": 0.05, "max_leaf_nodes": 15, "max_iter": 300}


def wilson(k, n, z=1.96):
    if n == 0:
        return [None, None]
    p = k / n; d = 1 + z * z / n; c = p + z * z / (2 * n); h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return [(c - h) / d, (c + h) / d]


def seg_stats(y, s, young, thr):
    lg = (y == 0) & young
    k, n = int((s[lg] >= thr).sum()), int(lg.sum())
    fy = (y == 1) & young
    return {"young_legit_n": n, "young_legit_flagged": k, "young_false_alarm_rate": k / n if n else None,
            "young_false_alarm_95ci": wilson(k, n), "young_fraud_n": int(fy.sum()),
            "young_fraud_recall": float((s[fy] >= thr).mean()) if fy.sum() else None,
            "overall": P.confusion_at(y, s, thr)}


def main():
    df = P.load_dataset(ROOT / "ml/data/generated/default/transactions.csv")
    df = df[(df["split"] != "burn_in") & (df["transaction_type"] != "card_payment")].reset_index(drop=True)
    X, _ = P.build_matrix(df)
    y = df["is_fraud"].to_numpy(); sp = df["split"].to_numpy()
    young = (df["recipient_age_days"] < 30).fillna(False).to_numpy()
    tr, va, te = sp == "train", sp == "validation", sp == "test"
    out = {"selection_rule": __doc__.split("Pre-registered selection rule (fixed BEFORE looking at test):")[1].split("Each variant")[0].strip(),
           "variants": {}}
    for name, feats in VARIANTS.items():
        m = P.fit_model("hgb", feats, X[tr], y[tr], seed=42, **GRID)
        sv, st = 100 * P.predict(m, "hgb", feats, X[va]), 100 * P.predict(m, "hgb", feats, X[te])
        t_high = P.threshold_for_precision(y[va], sv, 0.90)
        t_med = min(max(P.threshold_for_recall(y[va], sv, 0.95), P.threshold_for_alert_rate(sv, 0.04)), t_high * 0.999)
        out["variants"][name] = {"features": len(feats), "validation_pr_auc": P.ranking_metrics(y[va], sv)["pr_auc"],
                                 "test_pr_auc": P.ranking_metrics(y[te], st)["pr_auc"], "medium_threshold": t_med,
                                 "validation": seg_stats(y[va], sv, young[va], t_med), "test": seg_stats(y[te], st, young[te], t_med)}
        v = out["variants"][name]
        print(f"{name:38s} valPR {v['validation_pr_auc']:.4f} testPR {v['test_pr_auc']:.4f} | val young FA "
              f"{v['validation']['young_false_alarm_rate']:.3f} | test young FA {v['test']['young_false_alarm_rate']:.3f} "
              f"({v['test']['young_legit_flagged']}/{v['test']['young_legit_n']}) | test recall {v['test']['overall']['recall']:.3f} fpr {v['test']['overall']['fpr']:.4f}")
    best_pr = max(v["validation_pr_auc"] for v in out["variants"].values())
    eligible = {k: v for k, v in out["variants"].items() if v["validation_pr_auc"] >= best_pr - 0.01}
    out["selected"] = min(eligible, key=lambda k: eligible[k]["validation"]["young_false_alarm_rate"])
    print("selected (by validation rule):", out["selected"])
    # ---- policy trade-off on the DEPLOYED model: separate step-up threshold for young recipients ----
    import joblib
    bnd = joblib.load(ROOT / "ml/artifacts/model.joblib")
    pol = json.loads((ROOT / "ml/artifacts/policy.json").read_text())["modes"]["balanced"]
    feats = bnd["features"]
    sv = 100 * bnd["model"].predict_proba(X[va][feats].to_numpy())[:, 1]
    st = 100 * bnd["model"].predict_proba(X[te][feats].to_numpy())[:, 1]
    t_shared = pol["medium_threshold"]
    overall_val_fa = float((sv[(y[va] == 0) & ~young[va]] >= t_shared).mean())
    rows = []
    for mult in (1, 1.5, 2, 3, 5):
        target = mult * overall_val_fa
        lg = sv[(y[va] == 0) & young[va]]
        # never above the HIGH threshold: anything >= high is escalated by the policy anyway
        t_young = t_shared if mult == 1 else min(max(t_shared, float(np.quantile(lg, 1 - min(target, 1.0)))), pol["high_threshold"] * 0.999)
        def apply(s_, yv, yg):
            thr = np.where(yg, t_young, t_shared)
            f = s_ >= thr
            lgm, frm = (yv == 0) & yg, (yv == 1) & yg
            return {"young_false_alarm_rate": float(f[lgm].mean()), "young_fraud_recall": float(f[frm].mean()),
                    "overall_recall": float(f[yv == 1].mean()), "overall_fpr": float(f[yv == 0].mean()),
                    "young_frauds_missed": int((~f[frm]).sum()), "young_legit_flagged": int(f[lgm].sum())}
        rows.append({"target": f"young false-alarm <= {mult}x other customers" if mult > 1 else "shared threshold (current default)",
                     "young_threshold": t_young, "validation": apply(sv, y[va], young[va]), "test": apply(st, y[te], young[te])})
    # Pre-registered (validation-only) policy rule: among raised thresholds, take the one with the lowest validation
    # young false-alarm rate whose validation young-fraud recall is at most 2 percentage points below the shared threshold.
    base_rec = rows[0]["validation"]["young_fraud_recall"]
    ok = [r for r in rows[1:] if r["validation"]["young_fraud_recall"] >= base_rec - 0.02]
    chosen = min(ok, key=lambda r: r["validation"]["young_false_alarm_rate"]) if ok else None
    out["deployed_model_threshold_tradeoff"] = {"chosen_by_validation_rule": chosen["target"] if chosen else None,
                                                "chosen_young_threshold": chosen["young_threshold"] if chosen else None,"shared_medium_threshold": t_shared, "other_customers_val_false_alarm": overall_val_fa,
                                                "rows": rows, "decision": "Offered as an opt-in policy setting (off by default): it lowers false alarms for "
                                                "customers paying new accounts but can miss more frauds to young accounts. The bank decides."}
    # rule baseline on the same segment (test), for a like-for-like comparison
    from backend.app.services.features import RULE_FLAG_THRESHOLD, rule_score
    yl = df[te & young & (y == 0)]
    rs = [rule_score(r)[0] for r in P.raw_records(yl)]
    out["rules_young_false_alarm_test"] = sum(v >= RULE_FLAG_THRESHOLD for v in rs) / max(1, len(rs))
    out["rules_young_legit_n"] = len(rs)
    if chosen:   # expose as an opt-in policy override for the backend
        pp = ROOT / "ml/artifacts/policy.json"; policy = json.loads(pp.read_text())
        policy["segment_overrides"] = {"young_recipient": {
            "max_recipient_age_days": 30, "mode": "balanced", "medium_threshold": round(chosen["young_threshold"], 3),
            "enabled_by_default": False,
            "rationale": "Opt-in: step-up threshold for transfers to accounts younger than 30 days, chosen on validation "
                         "(lowest false-alarm rate with <= 2 pp loss of young-fraud recall). See experiments/results/fairness_young_recipient.json."}}
        pp.write_text(json.dumps(policy, indent=2))
    print("chosen by validation rule:", chosen["target"] if chosen else None)
    for r in rows:
        v = r["validation"]
        print(f"VAL  {r['target']:45s} thr {r['young_threshold']:6.2f} | young FA {v['young_false_alarm_rate']:.3f} young recall {v['young_fraud_recall']:.3f}")
    for r in rows:
        t = r["test"]
        print(f"{r['target']:45s} thr {r['young_threshold']:6.2f} | test young FA {t['young_false_alarm_rate']:.3f} young recall {t['young_fraud_recall']:.3f} "
              f"| overall recall {t['overall_recall']:.3f} fpr {t['overall_fpr']:.4f}")
    (ROOT / "experiments/results/fairness_young_recipient.json").write_text(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
