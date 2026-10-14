"""Export the demo transaction set (synthetic TEST split, never used for training) that the
backend loads into SQLite on first start. Keeps all frauds plus a seeded sample of
legitimate transfers so the dashboard has realistic volume without a 60 MB file.

Usage: python -m scripts.export_demo_set [--n-legit 5000]
"""
import argparse
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-legit", type=int, default=5000)
    a = ap.parse_args()
    df = pd.read_csv(ROOT / "ml/data/generated/default/transactions.csv", low_memory=False)
    te = df[(df.split == "test") & (df.transaction_type != "card_payment")]
    legit = te[te.is_fraud == 0].sample(n=min(a.n_legit, (te.is_fraud == 0).sum()), random_state=42)
    out = pd.concat([te[te.is_fraud == 1], legit]).sort_values("t_hours")
    p = ROOT / "ml/artifacts/demo_transactions.csv.gz"
    out.to_csv(p, index=False, compression="gzip")
    print(f"wrote {len(out)} rows ({int(out.is_fraud.sum())} fraud) -> {p.relative_to(ROOT)}")
