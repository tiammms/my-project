"""Main NeuroShield experiment: train, compare, select, calibrate thresholds, evaluate.

Protocol (all on SYNTHETIC data, see ml/data/generate.py):
  * time-based split: train days 7-59, validation 60-74, test 75-89 (burn-in 0-6 dropped)
  * every model selection / threshold decision uses validation only
  * test set is touched once, for the final report
Compared systems:
  A  rules            - weighted legacy rule set (backend/app/services/features.RULES)
  B1 lr_txn           - logistic regression, transaction features only
  B2 hgb_txn          - gradient boosting, transaction features only
  C1 lr_hybrid        - logistic regression, transaction + behavioral + graph + SE scores
  C2 rf_hybrid        - random forest, hybrid features
  C3 hgb_hybrid       - gradient boosting, hybrid features  (candidate NeuroShield model)
The deployed model is whichever hybrid model has the best validation PR-AUC.

Usage: python -m ml.training.train [--data ml/data/generated/default/transactions.csv]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.inspection import permutation_importance

from backend.app.services import explain
from backend.app.services.features import (HYBRID_FEATURES, RULE_FLAG_THRESHOLD, RULES,
                                           TXN_FEATURES, featurize)
from ml.training import pipeline as P

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "ml" / "artifacts"
RESULTS = ROOT / "experiments" / "results"
SEED = 42

ABLATIONS = {
    "without_behavioral": ["behavioral_anomaly_score", "rhythm_deviation", "hesitation_index", "behavioral_available"],
    "without_graph": ["graph_risk_score"],
    "without_social_engineering": ["social_engineering_risk_score"],
    "without_recipient_reputation": ["recipient_risk_score"],
}


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


def evaluate_system(name, y_val, s_val, y_te, s_te, base_val_recall, base_val_alert):
    t_rec = P.threshold_for_recall(y_val, s_val, base_val_recall)
    t_budget = P.threshold_for_alert_rate(s_val, base_val_alert)
    return {
        "name": name,
        "validation": P.ranking_metrics(y_val, s_val),
        "test": {**P.ranking_metrics(y_te, s_te), **P.bootstrap_ci(y_te, s_te, n=200, seed=SEED)},
        "at_equal_recall": {"threshold_from_validation": t_rec, "test": P.confusion_at(y_te, s_te, t_rec)},
        "at_equal_alert_budget": {"threshold_from_validation": t_budget, "test": P.confusion_at(y_te, s_te, t_budget)},
        "fpr_at_fixed_recall": fixed_recall_table(y_val, s_val, y_te, s_te),
    }


def fixed_recall_table(y_val, s_val, y_te, s_te):
    out = []
    for r in (0.6, 0.7, 0.8, 0.9, 0.95):
        t = P.threshold_for_recall(y_val, s_val, r)
        c = P.confusion_at(y_te, s_te, t)
        out.append({"target_recall": r, "test_recall": c["recall"], "test_fpr": c["fpr"], "test_precision": c["precision"]})
    return out


def run(data_path: Path, out_tag: str = "default", save_artifacts: bool = True, quick: bool = False) -> dict:
    t_start = time.time()
    df = P.load_dataset(data_path)
    # Scope: account-initiated transfers and bill payments. Card payments are simulated only to build
    # customer history; card-not-present fraud is a different problem and is out of scope for this MVP.
    df = df[(df["split"] != "burn_in") & (df["transaction_type"] != "card_payment")].reset_index(drop=True)
    log("rows", len(df))
    X, rules = P.build_matrix(df)
    y = df["is_fraud"].to_numpy()
    sp = df["split"].to_numpy()
    tr, va, te = sp == "train", sp == "validation", sp == "test"
    log("featurized; positives train/val/test", y[tr].sum(), y[va].sum(), y[te].sum())

    # ---- A: rule baseline ---------------------------------------------------
    base_val = P.confusion_at(y[va], rules[va], RULE_FLAG_THRESHOLD)
    base_te = P.confusion_at(y[te], rules[te], RULE_FLAG_THRESHOLD)
    R = base_val["recall"]; B = base_val["alert_rate"]
    systems = {"rules": {
        "name": "rules", "validation": P.ranking_metrics(y[va], rules[va]),
        "test": {**P.ranking_metrics(y[te], rules[te]), **P.bootstrap_ci(y[te], rules[te], n=200, seed=SEED)},
        "native_threshold": RULE_FLAG_THRESHOLD,
        "fpr_at_fixed_recall": fixed_recall_table(y[va], rules[va], y[te], rules[te]),
        "at_equal_recall": {"threshold_from_validation": RULE_FLAG_THRESHOLD, "test": base_te},
        "at_equal_alert_budget": {"threshold_from_validation": RULE_FLAG_THRESHOLD, "test": base_te},
    }}
    log("rule baseline val recall", round(R, 3), "alert rate", round(B, 4))

    # ---- B/C: ML models --------------------------------------------------
    scores_val, scores_te, models = {}, {}, {}
    specs = [("lr_txn", "lr", TXN_FEATURES), ("hgb_txn", "hgb", TXN_FEATURES),
             ("lr_hybrid", "lr", HYBRID_FEATURES), ("rf_hybrid", "rf", HYBRID_FEATURES),
             ("hgb_hybrid", "hgb", HYBRID_FEATURES)]
    grid_log = {}
    for name, kind, feats in specs:
        t0 = time.time()
        if kind == "hgb" and not quick:   # small grid, selected on validation PR-AUC
            best = None
            for g in P.HGB_GRID:
                m = P.fit_model(kind, feats, X[tr], y[tr], seed=SEED, **g)
                pr = P.ranking_metrics(y[va], P.predict(m, kind, feats, X[va]))["pr_auc"]
                grid_log.setdefault(name, []).append({**g, "val_pr_auc": pr})
                if best is None or pr > best[0]:
                    best = (pr, m, g)
            m = best[1]
        else:
            m = P.fit_model(kind, feats, X[tr], y[tr], seed=SEED)
        models[name] = (m, kind, feats)
        scores_val[name] = P.predict(m, kind, feats, X[va])
        scores_te[name] = P.predict(m, kind, feats, X[te])
        systems[name] = evaluate_system(name, y[va], scores_val[name], y[te], scores_te[name], R, B)
        systems[name]["fit_seconds"] = round(time.time() - t0, 2)
        systems[name]["features"] = feats
        log(name, "val PR-AUC", round(systems[name]["validation"]["pr_auc"], 4),
            "test ROC-AUC", round(systems[name]["test"]["roc_auc"], 4))

    hybrids = ["lr_hybrid", "rf_hybrid", "hgb_hybrid"]
    winner = max(hybrids, key=lambda n: systems[n]["validation"]["pr_auc"])
    log("winner (validation PR-AUC):", winner)
    wm, wkind, wfeats = models[winner]
    sv, st = scores_val[winner], scores_te[winner]

    # ---- comparisons vs baseline ------------------------------------------
    def rel(b, m):
        return (b - m) / b * 100 if b > 0 else None
    comparison = {}
    for n in [k for k in systems if k != "rules"]:
        er = systems[n]["at_equal_recall"]["test"]
        eb = systems[n]["at_equal_alert_budget"]["test"]
        sc = scores_te[n]
        tm = P.threshold_for_recall(y[te], sc, base_te["recall"])
        mt = P.confusion_at(y[te], sc, tm)
        comparison[n] = {
            "matched_test_recall": {"baseline_recall": base_te["recall"], "model_recall": mt["recall"],
                                    "baseline_fpr": base_te["fpr"], "model_fpr": mt["fpr"],
                                    "relative_fpr_reduction_pct": rel(base_te["fpr"], mt["fpr"]),
                                    "note": "ROC-curve point at exactly the baseline's test recall (diagnostic, not a deployable threshold)"},
            "equal_recall": {"baseline_recall": base_te["recall"], "model_recall": er["recall"],
                             "baseline_fpr": base_te["fpr"], "model_fpr": er["fpr"],
                             "relative_fpr_reduction_pct": rel(base_te["fpr"], er["fpr"])},
            "equal_alert_budget": {"baseline_recall": base_te["recall"], "model_recall": eb["recall"],
                                   "baseline_fpr": base_te["fpr"], "model_fpr": eb["fpr"]},
        }

    # ---- policy thresholds (validation only) -------------------------------
    score_val, score_te = 100 * sv, 100 * st
    policies = {}
    for mode, rec_target, budget, prec in (("balanced", 0.95, 0.04, 0.90), ("strict", 0.98, 0.06, 0.75)):
        t_high = P.threshold_for_precision(y[va], score_val, prec)
        t_rec = P.threshold_for_recall(y[va], score_val, rec_target)
        t_bud = P.threshold_for_alert_rate(score_val, budget)
        t_med = min(max(t_rec, t_bud), t_high * 0.999)
        policies[mode] = {
            "medium_threshold": round(float(t_med), 3), "high_threshold": round(float(t_high), 3),
            "rationale": (f"medium (step-up) = threshold reaching {rec_target:.0%} validation recall, but never flagging "
                          f"more than {budget:.0%} of validation transactions; high (review / temporary hold "
                          f"recommendation) = lowest score where validation precision >= {prec:.0%}"),
            "targets": {"medium_recall_target": rec_target, "stepup_budget": budget, "high_precision_target": prec},
            "validation_at_medium": P.confusion_at(y[va], score_val, t_med),
            "validation_at_high": P.confusion_at(y[va], score_val, t_high),
            "test_at_medium": P.confusion_at(y[te], score_te, t_med),
            "test_at_high": P.confusion_at(y[te], score_te, t_high),
        }
    pol = policies["balanced"]

    # ---- segment analysis (false positives on hard negatives, recall per fraud type)
    dte = df[te].copy()
    dte["score"] = score_te; dte["rules"] = rules[te]
    seg = []
    for tag, g in dte[dte.is_fraud == 0].groupby("legit_tag"):
        seg.append({"segment": tag, "n": int(len(g)),
                    "stepup_or_higher_rate": float((g.score >= pol["medium_threshold"]).mean()),
                    "high_rate": float((g.score >= pol["high_threshold"]).mean()),
                    "rule_baseline_flag_rate": float((g.rules >= RULE_FLAG_THRESHOLD).mean())})
    fr = []
    for ft, g in dte[dte.is_fraud == 1].groupby("fraud_type"):
        fr.append({"fraud_type": ft, "n": int(len(g)),
                   "recall_medium": float((g.score >= pol["medium_threshold"]).mean()),
                   "recall_high": float((g.score >= pol["high_threshold"]).mean()),
                   "rule_baseline_recall": float((g.rules >= RULE_FLAG_THRESHOLD).mean())})

    # ---- ablations ---------------------------------------------------------
    ablations = {}
    best_grid = max(grid_log.get("hgb_hybrid", [{"learning_rate": 0.05, "max_leaf_nodes": 31, "max_iter": 300, "val_pr_auc": 0}]),
                    key=lambda d: d["val_pr_auc"])
    gparams = {k: v for k, v in best_grid.items() if k != "val_pr_auc"}
    for ab, drop in ABLATIONS.items():
        feats = [f for f in HYBRID_FEATURES if f not in drop]
        m = P.fit_model("hgb", feats, X[tr], y[tr], seed=SEED, **gparams)
        s_v, s_t = P.predict(m, "hgb", feats, X[va]), P.predict(m, "hgb", feats, X[te])
        r = evaluate_system(ab, y[va], s_v, y[te], s_t, R, B)
        ablations[ab] = {"test_roc_auc": r["test"]["roc_auc"], "test_pr_auc": r["test"]["pr_auc"],
                         "fpr_at_equal_recall": r["at_equal_recall"]["test"]["fpr"],
                         "recall_at_equal_recall": r["at_equal_recall"]["test"]["recall"]}
        log("ablation", ab, round(r["test"]["pr_auc"], 4))

    # ---- error analysis ----------------------------------------------------
    t_med = pol["medium_threshold"]
    fps = dte[(dte.is_fraud == 0) & (dte.score >= t_med)].sort_values("score", ascending=False)
    fns = dte[(dte.is_fraud == 1) & (dte.score < t_med)].sort_values("score")
    keep = ["transaction_id", "amount", "transaction_type", "legit_tag", "fraud_type", "score", "new_recipient",
            "device_anomaly", "geographic_anomaly", "behavioral_consent"]
    errors = {
        "false_positives_by_segment": fps["legit_tag"].value_counts().to_dict(),
        "false_negatives_by_type": fns["fraud_type"].value_counts().to_dict(),
        "false_negatives_without_behavioral_data": int((fns["typing_interval_mean_ms"].isna()).sum()),
        "examples_false_positive": fps[keep].head(8).to_dict("records"),
        "examples_false_negative": fns[keep].head(8).to_dict("records"),
    }

    # ---- explainability: global + faithfulness ------------------------------
    pi = permutation_importance(wm if wkind != "lr" else wm, (X[va][wfeats].to_numpy() if wkind != "lr" else X[va][wfeats]),
                                y[va], scoring="average_precision", n_repeats=3, random_state=SEED, n_jobs=1)
    gimp = sorted([{"feature": f, "label": explain.feature_label(f), "importance": float(pi.importances_mean[i]),
                    "std": float(pi.importances_std[i])} for i, f in enumerate(wfeats)], key=lambda d: -d["importance"])
    legit_tr = X[tr][y[tr] == 0]
    reference = {f: (float(legit_tr[f].median()) if legit_tr[f].notna().any() else float("nan")) for f in wfeats}
    reference["behavioral_available"] = float("nan")  # never occlude availability itself
    faith = None
    if wkind != "lr":
        rng = np.random.default_rng(SEED)
        te_idx = np.where(te)[0]
        hi = te_idx[np.argsort(-st)[:300]]
        drops_top, drops_rand = [], []
        Xw = X[wfeats]
        for i in hi:
            x = Xw.iloc[i].to_dict()
            contrib = explain.occlusion(wm, wfeats, x, reference)
            nz = [c for c in contrib if c["contribution"] != 0]
            if not nz:
                continue
            top = nz[0]["contribution"]; rnd = nz[int(rng.integers(0, len(nz)))]["contribution"]
            drops_top.append(top); drops_rand.append(rnd)
        faith = {"n": len(drops_top), "mean_logodds_drop_top_group": float(np.mean(drops_top)),
                 "mean_logodds_drop_random_group": float(np.mean(drops_rand)),
                 "description": "Deletion test on the 300 highest-scored test transactions: occluding the top-attributed "
                                "group should reduce risk more than occluding a random group."}

    # ---- calibration -------------------------------------------------------
    calib = P.calibration(y[te], st)

    # ---- latency -----------------------------------------------------------
    sample_raw = P.raw_records(df[te].head(1))[0]
    feat_vec = np.array([[featurize(sample_raw)[0][f] for f in wfeats]])
    lat_model = P.latency_profile(lambda: wm.predict_proba(feat_vec), n=1000)
    lat_pipeline = P.latency_profile(lambda: wm.predict_proba(np.array([[featurize(sample_raw)[0][f] for f in wfeats]])), n=1000)
    lat_explain = P.latency_profile(lambda: explain.occlusion(wm, wfeats, featurize(sample_raw)[0], reference), n=300)
    env = {"python": sys.version.split()[0], "sklearn": sklearn.__version__, "numpy": np.__version__,
           "pandas": pd.__version__, "platform": platform.platform(),
           "cpu": _cpu_name(), "cpu_count": __import__("os").cpu_count()}

    # ---- package -----------------------------------------------------------
    data_hash = hashlib.sha256(Path(data_path).read_bytes()).hexdigest()[:12]
    trained_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    model_version = f"ns-{winner.replace('_', '-')}-1.0.0+{data_hash}"
    results = {
        "dataset": {"path": str(Path(data_path).relative_to(ROOT)), "synthetic": True, "sha256_12": data_hash,
                    "rows_used": int(len(df)), "split_sizes": {k: int(v) for k, v in df.split.value_counts().items()},
                    "positives": {"train": int(y[tr].sum()), "validation": int(y[va].sum()), "test": int(y[te].sum())},
                    "fraud_rate_test": float(y[te].mean())},
        "protocol": "time-based split; model selection and thresholds on validation; single test evaluation",
        "baseline": {"rules": [{"id": r[0], "weight": r[1], "description": r[2]} for r in RULES],
                     "flag_threshold": RULE_FLAG_THRESHOLD, "validation": base_val, "test": base_te},
        "systems": systems, "hgb_grid_search": grid_log, "winner": winner, "comparison_vs_rules": comparison,
        "policies": policies, "segments_false_positive": seg, "recall_by_fraud_type": fr,
        "ablations": ablations, "error_analysis": errors, "global_importance": gimp,
        "explanation_faithfulness": faith, "calibration": calib,
        "curves": {n: P.curves(y[te], (scores_te[n] if n != "rules" else rules[te])) for n in systems},
        "latency": {"model_only_single_row": lat_model, "featurize_plus_model": lat_pipeline,
                    "occlusion_explanation": lat_explain, "environment": env,
                    "note": "In-process timings after warm-up; full HTTP request latency is measured separately by scripts/bench_api.py"},
        "model_version": model_version, "trained_at": trained_at, "seed": SEED,
        "runtime_seconds": round(time.time() - t_start, 1),
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"evaluation_{out_tag}.json").write_text(json.dumps(sanitize(results), indent=2, default=_json_default, allow_nan=False))
    if save_artifacts:
        ART.mkdir(parents=True, exist_ok=True)
        ranges = {f: [float(np.nanmin(X[tr][f])), float(np.nanmax(X[tr][f]))]
                  for f in wfeats if X[tr][f].notna().any()}
        joblib.dump({"model": wm, "kind": wkind, "features": wfeats, "reference": reference, "ranges": ranges,
                     "model_version": model_version, "trained_at": trained_at, "winner": winner,
                     "hyperparameters": gparams if wkind == "hgb" else {}, "data_hash": data_hash,
                     "sklearn": sklearn.__version__}, ART / "model.joblib")
        policy = {"policy_version": "policy-2026.10-a", "model_version": model_version, "default_mode": "balanced",
                  "modes": {k: {kk: vv for kk, vv in v.items() if kk in ("medium_threshold", "high_threshold", "rationale")}
                            for k, v in policies.items()}}
        (ART / "policy.json").write_text(json.dumps(policy, indent=2))
        log("artifacts saved:", model_version)
    log("done in", results["runtime_seconds"], "s")
    return results


def _cpu_name():
    try:
        for line in open("/proc/cpuinfo"):
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "unknown"


def sanitize(o):
    if isinstance(o, dict):
        return {k: sanitize(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [sanitize(v) for v in o]
    if isinstance(o, (float, np.floating)):
        return None if (o != o or o in (float("inf"), float("-inf"))) else float(o)
    if isinstance(o, np.integer):
        return int(o)
    if isinstance(o, np.bool_):
        return bool(o)
    return o


def _json_default(o):
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return None if np.isnan(o) else float(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, float) and o != o:
        return None
    return str(o)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(ROOT / "ml/data/generated/default/transactions.csv"))
    ap.add_argument("--tag", default="default")
    ap.add_argument("--no-save", action="store_true")
    a = ap.parse_args()
    run(Path(a.data), a.tag, save_artifacts=not a.no_save)
