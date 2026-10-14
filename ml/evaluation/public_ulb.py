"""Check the transaction-model methodology on the public ULB credit-card fraud dataset (real labels).

Download `creditcard.csv` yourself (Kaggle: "Credit Card Fraud Detection", ULB Machine Learning Group; 284,807 rows,
492 frauds, columns Time, V1..V28 (PCA), Amount, Class), then:

    python -m ml.evaluation.public_ulb path/to/creditcard.csv

What it does - the same protocol as the main experiment, on real data:
  * time-based split (first 60% train, next 20% validation, last 20% test; the data covers ~2 days)
  * models: logistic regression and gradient boosting (same factories as ml/training/pipeline.py)
  * metrics: ROC-AUC / PR-AUC with bootstrap CIs, recall/FPR at a threshold chosen on validation, calibration
What it does NOT do: test the behavioral, graph or social-engineering components - the dataset has none of these
signals (anonymised card transactions, no recipients). Output: experiments/results/public_ulb.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from ml.training import pipeline as P

ROOT = Path(__file__).resolve().parents[2]
FEATURES = [f"V{i}" for i in range(1, 29)] + ["log_amount", "hour_sin", "hour_cos"]


def prepare(df: pd.DataFrame) -> pd.DataFrame:
    need = {"Time", "Amount", "Class", *[f"V{i}" for i in range(1, 29)]}
    missing = need - set(df.columns)
    if missing:
        raise SystemExit(f"Not the ULB schema, missing columns: {sorted(missing)[:5]}...")
    df = df.sort_values("Time").reset_index(drop=True)
    df["log_amount"] = np.log1p(df["Amount"].clip(lower=0))
    hour = (df["Time"] / 3600.0) % 24
    df["hour_sin"], df["hour_cos"] = np.sin(2 * np.pi * hour / 24), np.cos(2 * np.pi * hour / 24)
    n = len(df)
    df["split"] = np.where(np.arange(n) < 0.6 * n, "train", np.where(np.arange(n) < 0.8 * n, "validation", "test"))
    return df


def run(path: Path, out_path: Path | None = None) -> dict:
    df = prepare(pd.read_csv(path))
    y = df["Class"].to_numpy().astype(int)
    sp = df["split"].to_numpy()
    tr, va, te = sp == "train", sp == "validation", sp == "test"
    res = {"dataset": str(path.name), "rows": int(len(df)), "frauds": int(y.sum()), "fraud_rate": float(y.mean()),
           "split": {k: int((sp == k).sum()) for k in ("train", "validation", "test")},
           "positives": {k: int(y[sp == k].sum()) for k in ("train", "validation", "test")}, "models": {},
           "scope_note": "Transaction model only: no behavioral / graph / social-engineering signals exist in this dataset."}
    X = df[FEATURES]
    for name, kind in (("logistic_regression", "lr"), ("gradient_boosting", "hgb")):
        m = P.fit_model(kind, FEATURES, X[tr], y[tr], seed=42)
        sv, st = P.predict(m, kind, FEATURES, X[va]), P.predict(m, kind, FEATURES, X[te])
        thr = P.threshold_for_recall(y[va], sv, 0.80) if y[va].sum() else 0.5
        res["models"][name] = {"validation": P.ranking_metrics(y[va], sv) if y[va].sum() else None,
                               "test": {**P.ranking_metrics(y[te], st), **P.bootstrap_ci(y[te], st, n=200, seed=42)},
                               "threshold_for_80pct_validation_recall": thr, "test_at_threshold": P.confusion_at(y[te], st, thr),
                               "calibration": P.calibration(y[te], st)}
        t = res["models"][name]["test"]
        print(f"{name:20s} test ROC-AUC {t['roc_auc']:.4f}  PR-AUC {t['pr_auc']:.4f}  | at val-80%-recall threshold: "
              f"recall {res['models'][name]['test_at_threshold']['recall']:.3f} FPR {res['models'][name]['test_at_threshold']['fpr']:.5f}")
    out_path = out_path or ROOT / "experiments/results/public_ulb.json"
    out_path.write_text(json.dumps(res, indent=2, default=float))
    return res


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    run(Path(sys.argv[1]))
