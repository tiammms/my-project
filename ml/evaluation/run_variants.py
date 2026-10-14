"""Robustness experiments: re-generate data under different assumptions / seeds and re-run
the full protocol. Answers the question "do the conclusions depend on one lucky dataset
or on generous generator assumptions?".

Usage: python -m ml.evaluation.run_variants
Output: experiments/results/sensitivity_summary.json
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from ml.training import train

ROOT = Path(__file__).resolve().parents[2]
RUNS = [("default", 43), ("default", 44), ("hard", 42), ("scarce", 42), ("low_reports", 42)]


def data_path(variant, seed):
    name = variant if seed == 42 else f"{variant}_seed{seed}"
    return ROOT / "ml/data/generated" / name / "transactions.csv"


def summarize(tag, res):
    s = res["systems"]
    row = {"run": tag, "test_positives": res["dataset"]["positives"]["test"], "winner": res["winner"]}
    for n in ("rules", "lr_txn", "hgb_txn", "hgb_hybrid"):
        row[f"{n}_roc_auc"] = s[n]["test"]["roc_auc"]
        row[f"{n}_pr_auc"] = s[n]["test"]["pr_auc"]
    c = res["comparison_vs_rules"]["hgb_hybrid"]["matched_test_recall"]
    row.update({"baseline_recall": c["baseline_recall"], "hybrid_recall_matched": c["model_recall"],
                "baseline_fpr": c["baseline_fpr"], "hybrid_fpr_matched": c["model_fpr"],
                "relative_fpr_reduction_pct": c["relative_fpr_reduction_pct"]})
    fr = {x["target_recall"]: x for x in s["hgb_hybrid"]["fpr_at_fixed_recall"]}
    fr_t = {x["target_recall"]: x for x in s["hgb_txn"]["fpr_at_fixed_recall"]}
    row["hybrid_fpr_at_val_recall_0.9"] = fr[0.9]["test_fpr"]
    row["hgb_txn_fpr_at_val_recall_0.9"] = fr_t[0.9]["test_fpr"]
    ct = res["comparison_vs_rules"]["hgb_txn"]["matched_test_recall"]
    row["txn_gbm_relative_fpr_reduction_pct"] = ct["relative_fpr_reduction_pct"]
    row["ablation_pr_auc"] = {k: v["test_pr_auc"] for k, v in res["ablations"].items()}
    return row


def main():
    out = []
    main_res = json.loads((ROOT / "experiments/results/evaluation_default.json").read_text())
    out.append(summarize("default_seed42 (main)", main_res))
    for variant, seed in RUNS:
        p = data_path(variant, seed)
        if not p.exists():
            subprocess.run([sys.executable, "-m", "ml.data.generate", "--variant", variant, "--seed", str(seed)],
                           cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        tag = f"{variant}_seed{seed}"
        res = train.run(p, tag, save_artifacts=False)
        out.append(summarize(tag, res))
        (ROOT / "experiments/results/sensitivity_summary.json").write_text(json.dumps(out, indent=2))
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
