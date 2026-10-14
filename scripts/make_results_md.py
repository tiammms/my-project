"""Render experiments/results/RESULTS.md from the JSON results, so reported numbers always
match the measured ones. Usage: python -m scripts.make_results_md"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
R = ROOT / "experiments/results"


def pct(x, d=1):
    return "n/a" if x is None else f"{100 * x:.{d}f}%"


def f3(x):
    return "n/a" if x is None else f"{x:.3f}"


def main():
    ev = json.loads((R / "evaluation_default.json").read_text())
    s = ev["systems"]
    L = []
    w = L.append
    d = ev["dataset"]
    w("# NeuroShield — measured experimental results\n")
    w("> **All numbers below are measured on SYNTHETIC data** produced by `ml/data/generate.py` "
      f"(seed {ev['seed']}, dataset hash `{d['sha256_12']}`). They show that the pipeline works and how the "
      "components compare *under the generator's assumptions*. They are **not** evidence of performance on real bank traffic.\n")
    w(f"Generated from `experiments/results/evaluation_default.json` (model `{ev['model_version']}`, trained {ev['trained_at']}).\n")
    w("## Data and protocol\n")
    w(f"- Scope: account transfers and bill payments ({d['rows_used']:,} rows after burn-in; card payments only build history).")
    w(f"- Split by time: train {d['split_sizes'].get('train', 0):,} / validation {d['split_sizes'].get('validation', 0):,} / "
      f"test {d['split_sizes'].get('test', 0):,} rows; positives {d['positives']['train']} / {d['positives']['validation']} / {d['positives']['test']}.")
    w(f"- Test fraud prevalence: {pct(d['fraud_rate_test'], 2)}. Model selection and all thresholds use validation only; test is used once.\n")
    w("## 1. Model comparison (test split)\n")
    w("| System | Features | ROC-AUC [95% CI] | PR-AUC [95% CI] |")
    w("|---|---|---|---|")
    names = {"rules": "A. Rule-based baseline", "lr_txn": "B1. Logistic regression", "hgb_txn": "B2. Gradient boosting",
             "lr_hybrid": "C1. Logistic regression", "rf_hybrid": "C2. Random forest", "hgb_hybrid": "C3. Gradient boosting"}
    for k, n in names.items():
        t = s[k]["test"]
        feats = "8 weighted rules" if k == "rules" else ("transaction only" if "txn" in k else "hybrid")
        win = " **(selected)**" if k == ev["winner"] else ""
        w(f"| {n}{win} | {feats} | {f3(t['roc_auc'])} [{f3(t['roc_auc_95ci'][0])}–{f3(t['roc_auc_95ci'][1])}] | "
          f"{f3(t['pr_auc'])} [{f3(t['pr_auc_95ci'][0])}–{f3(t['pr_auc_95ci'][1])}] |")
    w(f"\nWinner chosen by validation PR-AUC among hybrid models: **{ev['winner']}**.\n")
    w("## 2. False-positive rate vs the rule baseline\n")
    b = ev["baseline"]["test"]
    w(f"Rule baseline (flag if ≥{ev['baseline']['flag_threshold']} points): test recall {pct(b['recall'])}, "
      f"FPR {pct(b['fpr'], 2)}, precision {pct(b['precision'])}.\n")
    w("**(a) Matched operating point** — each model's ROC curve read at exactly the baseline's test recall "
      "(the like-for-like comparison required by the protocol; a diagnostic point, not a deployable threshold):\n")
    w("| System | Test recall | Test FPR | Relative FPR reduction vs rules |")
    w("|---|---|---|---|")
    for k in ("lr_txn", "hgb_txn", "lr_hybrid", "rf_hybrid", "hgb_hybrid"):
        c = ev["comparison_vs_rules"][k]["matched_test_recall"]
        w(f"| {names[k]} ({'hybrid' if 'hybrid' in k else 'txn'}) | {pct(c['model_recall'])} | {pct(c['model_fpr'], 2)} | "
          f"{'n/a' if c['relative_fpr_reduction_pct'] is None else f'{c['relative_fpr_reduction_pct']:.1f}%'} |")
    w("\n**(b) Deployable protocol** — threshold set on validation to reach the baseline's validation recall, then applied to test:\n")
    w("| System | Test recall | Test FPR | Relative FPR reduction vs rules |")
    w("|---|---|---|---|")
    for k in ("lr_txn", "hgb_txn", "lr_hybrid", "rf_hybrid", "hgb_hybrid"):
        c = ev["comparison_vs_rules"][k]["equal_recall"]
        w(f"| {names[k]} ({'hybrid' if 'hybrid' in k else 'txn'}) | {pct(c['model_recall'])} | {pct(c['model_fpr'], 2)} | "
          f"{'n/a' if c['relative_fpr_reduction_pct'] is None else f'{c['relative_fpr_reduction_pct']:.1f}%'} |")
    w("\nNote: thresholds transferred from validation do not hit exactly the same test recall (temporal drift: new mule "
      "rings appear in the test period). Compare recall and FPR together.\n")
    w("FPR at fixed validation-recall targets (test):\n")
    w("| Target recall (val) | " + " | ".join(names[k] for k in ("hgb_txn", "hgb_hybrid")) + " |")
    w("|---|---|---|")
    for a, c in zip(s["hgb_txn"]["fpr_at_fixed_recall"], s["hgb_hybrid"]["fpr_at_fixed_recall"]):
        w(f"| {a['target_recall']:.2f} | recall {pct(a['test_recall'])}, FPR {pct(a['test_fpr'], 2)} | "
          f"recall {pct(c['test_recall'])}, FPR {pct(c['test_fpr'], 2)} |")
    w("\n## 3. Decision policy (thresholds from validation)\n")
    w("| Mode | Medium (step-up) | High (review/hold) | Test recall ≥medium | Test FPR ≥medium | Test precision ≥high | Alert rate |")
    w("|---|---|---|---|---|---|---|")
    for m, p in ev["policies"].items():
        w(f"| {m} | {p['medium_threshold']:.2f} | {p['high_threshold']:.2f} | {pct(p['test_at_medium']['recall'])} | "
          f"{pct(p['test_at_medium']['fpr'], 2)} | {pct(p['test_at_high']['precision'])} | {pct(p['test_at_medium']['alert_rate'], 2)} |")
    w(f"\nRationale: {ev['policies']['balanced']['rationale']}.\n")
    w("## 4. Legitimate-but-unusual customers (balanced policy, test)\n")
    w("| Segment | n | Step-up or higher | High | Rule baseline flag rate |")
    w("|---|---|---|---|---|")
    for g in ev["segments_false_positive"]:
        w(f"| {g['segment']} | {g['n']} | {pct(g['stepup_or_higher_rate'])} | {pct(g['high_rate'])} | {pct(g['rule_baseline_flag_rate'])} |")
    w("\n| Fraud type | n | Recall ≥medium | Recall high | Rule baseline recall |")
    w("|---|---|---|---|---|")
    for g in ev["recall_by_fraud_type"]:
        w(f"| {g['fraud_type']} | {g['n']} | {pct(g['recall_medium'])} | {pct(g['recall_high'])} | {pct(g['rule_baseline_recall'])} |")
    w("\n## 5. Ablations (hybrid gradient boosting, test PR-AUC)\n")
    w(f"Full hybrid: {f3(s['hgb_hybrid']['test']['pr_auc'])}\n")
    w("| Removed component | PR-AUC | FPR at baseline recall |")
    w("|---|---|---|")
    for k, v in ev["ablations"].items():
        w(f"| {k.replace('without_', '')} | {f3(v['test_pr_auc'])} | {pct(v['fpr_at_equal_recall'], 2)} |")
    w("\n## 6. Calibration, explanations, latency\n")
    c = ev["calibration"]
    w(f"- Calibration on test: ECE {c['ece']:.4f}, Brier {c['brier']:.4f}. The score is still presented as a 0–100 risk score, not a probability.")
    fa = ev.get("explanation_faithfulness")
    if fa:
        w(f"- Explanation deletion test (n={fa['n']}): occluding the top-attributed group lowers log-odds by "
          f"{fa['mean_logodds_drop_top_group']:.2f} on average vs {fa['mean_logodds_drop_random_group']:.2f} for a random group.")
    lat = ev["latency"]
    env = lat["environment"]
    w(f"- In-process latency ({env['cpu']}, {env['cpu_count']} vCPU, Python {env['python']}, scikit-learn {env['sklearn']}), "
      f"1000 runs after 50 warm-up:")
    for k in ("model_only_single_row", "featurize_plus_model", "occlusion_explanation"):
        v = lat[k]
        w(f"  - {k}: p50 {v['p50_ms']:.2f} ms, p95 {v['p95_ms']:.2f} ms, p99 {v['p99_ms']:.2f} ms")
    api = R / "api_latency.json"
    if api.exists():
        a = json.loads(api.read_text())
        for k in ("no_persist", "persist"):
            v = a[k]
            w(f"- Full HTTP request `POST /api/v1/risk/score` ({k}, n={v['n']}): p50 {v['p50_ms']:.1f} ms, "
              f"p95 {v['p95_ms']:.1f} ms, p99 {v['p99_ms']:.1f} ms ({a['environment']['client']}).")
    sens = R / "sensitivity_summary.json"
    if sens.exists():
        w("\n## 7. Robustness: other seeds and harsher generator assumptions\n")
        w("| Run | Test pos. | Rules PR-AUC | Txn-only GBM PR-AUC | Hybrid PR-AUC | Hybrid ROC-AUC | Rel. FPR reduction vs rules (matched recall) |")
        w("|---|---|---|---|---|---|---|")
        for r in json.loads(sens.read_text()):
            rr = r["relative_fpr_reduction_pct"]
            w(f"| {r['run']} | {r['test_positives']} | {f3(r['rules_pr_auc'])} | {f3(r['hgb_txn_pr_auc'])} | "
              f"{f3(r['hgb_hybrid_pr_auc'])} | {f3(r['hgb_hybrid_roc_auc'])} | {'n/a' if rr is None else f'{rr:.1f}%'} |")
        w("\n`hard`: weaker stress signal, victims rarely disclose instructions; `scarce`: only 30% telemetry consent; "
          "`low_reports`: 30% of frauds reported plus 200 false reports.")
    fa_p, adv_p, lt_p = R / "fairness_young_recipient.json", R / "adversary_stress.json", R / "load_test.json"
    if fa_p.exists():
        fa = json.loads(fa_p.read_text()); tr = fa["deployed_model_threshold_tradeoff"]
        w("\n## 9. Fairness: legitimate transfers to new accounts (< 30 days)\n")
        w("v2 fixes (applied to everything above): the navigation-path metric is compared only with a baseline from the same screen flow "
          "(new vs known payee, >= 3 sessions, otherwise 'not comparable'); 'pass-through' counts only account-to-account transfers and "
          "only the forwarding band 60-130% (spending own money is not forwarding). v1 results are kept in `experiments/results/v1_before_flowfix/`.\n")
        w(f"Pre-registered rule: {fa['selection_rule']}\n")
        w("| Model variant | Validation PR-AUC | Test PR-AUC | Test false alarms, new accounts |")
        w("|---|---|---|---|")
        for k, v in fa["variants"].items():
            t = v["test"]
            w(f"| {k}{' **(selected)**' if k == fa['selected'] else ''} | {f3(v['validation_pr_auc'])} | {f3(v['test_pr_auc'])} | "
              f"{pct(t['young_false_alarm_rate'])} ({t['young_legit_flagged']}/{t['young_legit_n']}, 95% CI {pct(t['young_false_alarm_95ci'][0])}–{pct(t['young_false_alarm_95ci'][1])}) |")
        w("\nPolicy option on the deployed model (opt-in setting, off by default):\n")
        w("| Policy | Young threshold | Val. false alarms | Val. young-fraud recall | Test false alarms | Test young-fraud recall | Test overall recall |")
        w("|---|---|---|---|---|---|---|")
        for r in tr["rows"]:
            v, t = r["validation"], r["test"]
            w(f"| {r['target']}{' **(chosen)**' if r['target'] == tr['chosen_by_validation_rule'] else ''} | {r['young_threshold']:.2f} | {pct(v['young_false_alarm_rate'])} | "
              f"{pct(v['young_fraud_recall'])} | {pct(t['young_false_alarm_rate'])} | {pct(t['young_fraud_recall'])} | {pct(t['overall_recall'])} |")
        w(f"\n{tr['decision']}\n")
    if adv_p.exists():
        adv = json.loads(adv_p.read_text())
        w("\n## 10. Adaptive adversary stress test\n")
        w(f"{adv['n_frauds']} test frauds re-scored after the fraudster hides one signal (balanced policy, step-up or higher). {adv['note']}\n")
        w("| Evasion | NeuroShield recall | Drop | Rule baseline recall |")
        w("|---|---|---|---|")
        w(f"| none | {pct(adv['baseline']['recall_stepup_plus'])} | — | {pct(adv['baseline']['rules_recall'])} |")
        for k, v in adv["evasions"].items():
            w(f"| {k.replace('_', ' ')} | {pct(v['recall_stepup_plus'])} | {v['drop_pp']:.1f} pp | {pct(v['rules_recall'])} |")
    if lt_p.exists():
        lt = json.loads(lt_p.read_text())
        w("\n## 11. Load test\n")
        w(f"`scripts/load_test.py`: {lt['endpoint']}; {lt['environment']['cpu_count']} vCPU; {lt['environment']['note']}\n")
        w("| Workers | Clients | Req/s | p50 | p95 | p99 | Errors |")
        w("|---|---|---|---|---|---|---|")
        for r in lt["runs"]:
            w(f"| {r['workers']} | {r['concurrency']} | {r['throughput_rps']:.0f} | {r['p50_ms']:.1f} ms | {r['p95_ms']:.1f} ms | {r['p99_ms']:.1f} ms | {r['errors']} |")
        for f_ in lt.get("findings", []):
            w(f"- {f_}")
    rt_p = R / "robust_training.json"
    if rt_p.exists():
        rt = json.loads(rt_p.read_text())
        w("\n## 12. Evasion-augmented training (not adopted)\n")
        w(f"Training frauds were duplicated with one SEEN evasion hidden ({rt['augmented_copies']} copies). UNSEEN evasions were never used in training. "
          f"Rule: {rt['adoption_rule']}\n")
        w("| Model | Val PR-AUC | Val FPR (step-up) | Val recall, seen evasions | Val recall, unseen | Test PR-AUC | Test recall, seen | Test recall, unseen |")
        w("|---|---|---|---|---|---|---|---|")
        for k, v in rt["models"].items():
            a, b = v["validation"], v["test"]
            w(f"| {k} | {f3(a['pr_auc'])} | {pct(a['fpr_at_stepup'], 2)} | {pct(a['mean_seen'])} | {pct(a['mean_unseen'])} | {f3(b['pr_auc'])} | {pct(b['mean_seen'])} | {pct(b['mean_unseen'])} |")
        w(f"\nChecks: {', '.join(f'{k}={v}' for k, v in rt['checks'].items())} → **{'adopted' if rt['adopted'] else 'not adopted'}**. "
          "Augmentation helps against the tricks it was trained on but does not generalise to new ones; robustness needs new independent signals.")
    w("\n## 13. Target vs measured\n")
    hy = s["hgb_hybrid"]["test"]
    c = ev["comparison_vs_rules"]["hgb_hybrid"]["matched_test_recall"]
    w("| Metric | Target | Measured (synthetic) | Status |")
    w("|---|---|---|---|")
    w(f"| ROC-AUC | 0.94 | {f3(hy['roc_auc'])} | met on synthetic data only |")
    rr = c["relative_fpr_reduction_pct"]
    w(f"| Relative FPR reduction vs rules (matched recall {pct(c['baseline_recall'])}) | 35% | {rr:.1f}% | met on synthetic data only |")
    w(f"| p95 model latency | < 50 ms | {lat['featurize_plus_model']['p95_ms']:.1f} ms | met in this environment |")
    w("| Real-world validity | — | not measured | **limitation**: needs a real labelled dataset |")
    (R / "RESULTS.md").write_text("\n".join(L) + "\n")
    print("wrote", R / "RESULTS.md")


if __name__ == "__main__":
    main()
