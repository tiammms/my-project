"""Reusable training / evaluation primitives for NeuroShield experiments."""
from __future__ import annotations

import math
import time
from typing import Callable

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, precision_recall_curve,
                             roc_auc_score, roc_curve)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from backend.app.services.features import (CATEGORICAL, HYBRID_FEATURES, TXN_FEATURES, featurize,
                                           rule_score)

ID_COLS = ["transaction_id", "timestamp", "sender_id", "recipient_id", "event_id", "ring_id"]
LABEL_COLS = ["is_fraud", "fraud_type", "legit_tag"]


def load_dataset(path) -> pd.DataFrame:
    df = pd.read_csv(path, low_memory=False)
    return df


def raw_records(df: pd.DataFrame) -> list[dict]:
    return df.astype(object).where(pd.notna(df), None).to_dict("records")


def build_matrix(df: pd.DataFrame) -> tuple[pd.DataFrame, np.ndarray]:
    recs = raw_records(df)
    feats = pd.DataFrame([featurize(r)[0] for r in recs], index=df.index)
    rules = np.array([rule_score(r)[0] for r in recs], dtype=float)
    return feats, rules


# ---------------------------------------------------------------------------
# Model factories
# ---------------------------------------------------------------------------
def make_lr(features: list[str]):
    num = [f for f in features if f not in CATEGORICAL]
    cat = [f for f in features if f in CATEGORICAL]
    pre = ColumnTransformer([
        ("num", Pipeline([("imp", SimpleImputer(strategy="median", add_indicator=True)),
                          ("sc", StandardScaler())]), num),
        ("cat", Pipeline([("imp", SimpleImputer(strategy="most_frequent")),
                          ("oh", OneHotEncoder(handle_unknown="ignore"))]), cat),
    ])
    return Pipeline([("pre", pre), ("clf", LogisticRegression(max_iter=3000, C=1.0))])


def make_hgb(features: list[str], seed: int = 0, **kw):
    params = dict(learning_rate=0.05, max_iter=300, max_leaf_nodes=31, min_samples_leaf=40,
                  l2_regularization=1.0, early_stopping=False, random_state=seed)
    params.update(kw)
    cat_mask = [f in CATEGORICAL for f in features]
    return HistGradientBoostingClassifier(categorical_features=cat_mask, **params)


def make_rf(features: list[str], seed: int = 0):
    return RandomForestClassifier(n_estimators=300, min_samples_leaf=5, n_jobs=-1, random_state=seed)


HGB_GRID = [
    {"learning_rate": 0.05, "max_leaf_nodes": 15, "max_iter": 300},
    {"learning_rate": 0.05, "max_leaf_nodes": 31, "max_iter": 300},
    {"learning_rate": 0.1, "max_leaf_nodes": 31, "max_iter": 200},
]


def fit_model(kind: str, features: list[str], X: pd.DataFrame, y: np.ndarray, seed=0, **kw):
    Xf = X[features]
    if kind == "lr":
        m = make_lr(features); m.fit(Xf, y)
    elif kind == "hgb":
        m = make_hgb(features, seed, **kw); m.fit(Xf.to_numpy(), y)
    elif kind == "rf":
        m = make_rf(features, seed); m.fit(Xf.to_numpy(), y)
    else:
        raise ValueError(kind)
    return m


def predict(m, kind: str, features: list[str], X: pd.DataFrame) -> np.ndarray:
    Xf = X[features]
    if kind == "lr":
        return m.predict_proba(Xf)[:, 1]
    return m.predict_proba(Xf.to_numpy())[:, 1]


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------
def confusion_at(y, s, thr) -> dict:
    pred = s >= thr
    tp = int(((pred) & (y == 1)).sum()); fp = int(((pred) & (y == 0)).sum())
    fn = int(((~pred) & (y == 1)).sum()); tn = int(((~pred) & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return {"threshold": float(thr), "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "precision": prec, "recall": rec, "f1": f1,
            "fpr": fp / (fp + tn) if fp + tn else 0.0, "fnr": fn / (fn + tp) if fn + tp else 0.0,
            "alert_rate": float(pred.mean())}


def threshold_for_recall(y, s, target_recall) -> float:
    """Highest threshold whose recall >= target (on the given data)."""
    pos = np.sort(s[y == 1])[::-1]
    k = int(math.ceil(target_recall * len(pos)))
    k = min(max(k, 1), len(pos))
    return float(pos[k - 1])


def threshold_for_alert_rate(s, rate) -> float:
    return float(np.quantile(s, 1 - rate))


def threshold_for_precision(y, s, target_precision, min_alerts=10) -> float:
    order = np.argsort(-s)
    ys, ss = y[order], s[order]
    tp = np.cumsum(ys); n = np.arange(1, len(ys) + 1)
    prec = tp / n
    best = None
    for i in range(min_alerts - 1, len(ys)):
        if prec[i] >= target_precision and (i + 1 == len(ys) or ss[i] != ss[i + 1]):
            best = ss[i]
    return float(best if best is not None else ss[min_alerts - 1])


def ranking_metrics(y, s) -> dict:
    return {"roc_auc": float(roc_auc_score(y, s)), "pr_auc": float(average_precision_score(y, s))}


def bootstrap_ci(y, s, n=200, seed=0) -> dict:
    rng = np.random.default_rng(seed)
    roc, pr = [], []
    idx = np.arange(len(y))
    for _ in range(n):
        b = rng.choice(idx, size=len(idx), replace=True)
        if y[b].sum() == 0:
            continue
        roc.append(roc_auc_score(y[b], s[b])); pr.append(average_precision_score(y[b], s[b]))
    q = lambda a: [float(np.percentile(a, 2.5)), float(np.percentile(a, 97.5))]
    return {"roc_auc_95ci": q(roc), "pr_auc_95ci": q(pr), "n_bootstrap": len(roc)}


def curves(y, s, n_points=150) -> dict:
    fpr, tpr, _ = roc_curve(y, s)
    p, r, _ = precision_recall_curve(y, s)

    def down(a, b):
        if len(a) <= n_points:
            return [[float(x), float(z)] for x, z in zip(a, b)]
        idx = np.unique(np.linspace(0, len(a) - 1, n_points).astype(int))
        return [[float(a[i]), float(b[i])] for i in idx]
    return {"roc": down(fpr, tpr), "pr": down(r, p)}


def calibration(y, p, bins=10) -> dict:
    qs = np.unique(np.quantile(p, np.linspace(0, 1, bins + 1)))
    which = np.clip(np.searchsorted(qs, p, side="right") - 1, 0, len(qs) - 2)
    out, ece = [], 0.0
    for b in range(len(qs) - 1):
        m = which == b
        if m.sum() == 0:
            continue
        mp, ob = float(p[m].mean()), float(y[m].mean())
        ece += m.mean() * abs(mp - ob)
        out.append({"mean_predicted": mp, "observed_rate": ob, "count": int(m.sum())})
    return {"bins": out, "ece": float(ece), "brier": float(brier_score_loss(y, p))}


def latency_profile(fn: Callable[[], object], n=1000, warmup=50) -> dict:
    for _ in range(warmup):
        fn()
    ts = []
    for _ in range(n):
        t0 = time.perf_counter(); fn(); ts.append((time.perf_counter() - t0) * 1000)
    a = np.array(ts)
    return {"p50_ms": float(np.percentile(a, 50)), "p95_ms": float(np.percentile(a, 95)),
            "p99_ms": float(np.percentile(a, 99)), "mean_ms": float(a.mean()), "n": n, "warmup": warmup}
