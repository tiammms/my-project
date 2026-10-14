# NeuroShield — measured experimental results

> **All numbers below are measured on SYNTHETIC data** produced by `ml/data/generate.py` (seed 42, dataset hash `8a5fba95f70f`). They show that the pipeline works and how the components compare *under the generator's assumptions*. They are **not** evidence of performance on real bank traffic.

Generated from `experiments/results/evaluation_default.json` (model `ns-hgb-hybrid-1.0.0+8a5fba95f70f`, trained 2026-10-12T12:45:03+00:00).

## Data and protocol

- Scope: account transfers and bill payments (73,145 rows after burn-in; card payments only build history).
- Split by time: train 45,943 / validation 13,293 / test 13,909 rows; positives 987 / 254 / 283.
- Test fraud prevalence: 2.03%. Model selection and all thresholds use validation only; test is used once.

## 1. Model comparison (test split)

| System | Features | ROC-AUC [95% CI] | PR-AUC [95% CI] |
|---|---|---|---|
| A. Rule-based baseline | 8 weighted rules | 0.857 [0.832–0.880] | 0.185 [0.151–0.224] |
| B1. Logistic regression | transaction only | 0.934 [0.923–0.945] | 0.410 [0.360–0.468] |
| B2. Gradient boosting | transaction only | 0.946 [0.935–0.957] | 0.537 [0.485–0.596] |
| C1. Logistic regression | hybrid | 0.981 [0.975–0.986] | 0.743 [0.697–0.786] |
| C2. Random forest | hybrid | 0.982 [0.971–0.990] | 0.831 [0.789–0.865] |
| C3. Gradient boosting **(selected)** | hybrid | 0.988 [0.981–0.992] | 0.863 [0.825–0.893] |

Winner chosen by validation PR-AUC among hybrid models: **hgb_hybrid**.

## 2. False-positive rate vs the rule baseline

Rule baseline (flag if ≥2 points): test recall 66.8%, FPR 4.78%, precision 22.5%.

**(a) Matched operating point** — each model's ROC curve read at exactly the baseline's test recall (the like-for-like comparison required by the protocol; a diagnostic point, not a deployable threshold):

| System | Test recall | Test FPR | Relative FPR reduction vs rules |
|---|---|---|---|
| B1. Logistic regression (txn) | 66.8% | 5.69% | -19.2% |
| B2. Gradient boosting (txn) | 66.8% | 2.73% | 42.9% |
| C1. Logistic regression (hybrid) | 66.8% | 0.80% | 83.3% |
| C2. Random forest (hybrid) | 66.8% | 0.17% | 96.5% |
| C3. Gradient boosting (hybrid) | 66.8% | 0.07% | 98.5% |

**(b) Deployable protocol** — threshold set on validation to reach the baseline's validation recall, then applied to test:

| System | Test recall | Test FPR | Relative FPR reduction vs rules |
|---|---|---|---|
| B1. Logistic regression (txn) | 70.3% | 7.05% | -47.6% |
| B2. Gradient boosting (txn) | 66.4% | 2.70% | 43.5% |
| C1. Logistic regression (hybrid) | 62.2% | 0.41% | 91.4% |
| C2. Random forest (hybrid) | 59.7% | 0.11% | 97.7% |
| C3. Gradient boosting (hybrid) | 59.0% | 0.04% | 99.2% |

Note: thresholds transferred from validation do not hit exactly the same test recall (temporal drift: new mule rings appear in the test period). Compare recall and FPR together.

FPR at fixed validation-recall targets (test):

| Target recall (val) | B2. Gradient boosting | C3. Gradient boosting |
|---|---|---|
| 0.60 | recall 48.1%, FPR 0.70% | recall 43.8%, FPR 0.00% |
| 0.70 | recall 57.6%, FPR 1.56% | recall 52.3%, FPR 0.01% |
| 0.80 | recall 66.4%, FPR 2.66% | recall 59.0%, FPR 0.03% |
| 0.90 | recall 73.9%, FPR 5.54% | recall 74.2%, FPR 0.21% |
| 0.95 | recall 81.3%, FPR 10.27% | recall 84.8%, FPR 1.09% |

## 3. Decision policy (thresholds from validation)

| Mode | Medium (step-up) | High (review/hold) | Test recall ≥medium | Test FPR ≥medium | Test precision ≥high | Alert rate |
|---|---|---|---|---|---|---|
| balanced | 8.33 | 46.54 | 84.8% | 1.09% | 91.8% | 2.79% |
| strict | 2.27 | 23.64 | 91.2% | 2.93% | 82.0% | 4.72% |

Rationale: medium (step-up) = threshold reaching 95% validation recall, but never flagging more than 4% of validation transactions; high (review / temporary hold recommendation) = lowest score where validation precision >= 90%.

## 4. Legitimate-but-unusual customers (balanced policy, test)

| Segment | n | Step-up or higher | High | Rule baseline flag rate |
|---|---|---|---|---|
| big_purchase | 297 | 4.4% | 1.3% | 68.7% |
| habit_change | 81 | 1.2% | 0.0% | 2.5% |
| new_device | 311 | 4.5% | 0.0% | 7.1% |
| new_recipient | 1850 | 1.4% | 0.2% | 6.3% |
| no_telemetry | 2589 | 1.9% | 0.2% | 2.8% |
| normal | 7111 | 0.3% | 0.1% | 1.7% |
| relay | 525 | 1.9% | 0.2% | 5.1% |
| travel | 774 | 0.9% | 0.0% | 10.7% |
| young_recipient | 88 | 10.2% | 1.1% | 1.1% |

| Fraud type | n | Recall ≥medium | Recall high | Rule baseline recall |
|---|---|---|---|---|
| app_scam | 127 | 81.1% | 69.3% | 70.1% |
| ato | 100 | 91.0% | 80.0% | 77.0% |
| mule_layering | 56 | 82.1% | 58.9% | 41.1% |

## 5. Ablations (hybrid gradient boosting, test PR-AUC)

Full hybrid: 0.863

| Removed component | PR-AUC | FPR at baseline recall |
|---|---|---|
| behavioral | 0.807 | 0.08% |
| graph | 0.827 | 0.07% |
| social_engineering | 0.847 | 0.07% |
| recipient_reputation | 0.852 | 0.07% |

## 6. Calibration, explanations, latency

- Calibration on test: ECE 0.0022, Brier 0.0061. The score is still presented as a 0–100 risk score, not a probability.
- Explanation deletion test (n=300): occluding the top-attributed group lowers log-odds by 4.46 on average vs 1.29 for a random group.
- In-process latency (Intel(R) Xeon(R) Processor @ 2.10GHz, 2 vCPU, Python 3.13.16, scikit-learn 1.9.1), 1000 runs after 50 warm-up:
  - model_only_single_row: p50 3.50 ms, p95 5.49 ms, p99 7.91 ms
  - featurize_plus_model: p50 3.85 ms, p95 5.41 ms, p99 6.52 ms
  - occlusion_explanation: p50 3.58 ms, p95 4.39 ms, p99 4.82 ms
- Full HTTP request `POST /api/v1/risk/score` (no_persist, n=500): p50 10.7 ms, p95 14.5 ms, p99 16.9 ms (urllib, sequential, same host).
- Full HTTP request `POST /api/v1/risk/score` (persist, n=200): p50 11.3 ms, p95 14.7 ms, p99 18.7 ms (urllib, sequential, same host).

## 7. Robustness: other seeds and harsher generator assumptions

| Run | Test pos. | Rules PR-AUC | Txn-only GBM PR-AUC | Hybrid PR-AUC | Hybrid ROC-AUC | Rel. FPR reduction vs rules (matched recall) |
|---|---|---|---|---|---|---|
| default_seed42 (main) | 283 | 0.185 | 0.537 | 0.863 | 0.988 | 98.5% |
| default_seed43 | 265 | 0.204 | 0.297 | 0.859 | 0.991 | 94.3% |
| default_seed44 | 230 | 0.095 | 0.435 | 0.780 | 0.987 | 97.0% |
| hard_seed42 | 283 | 0.185 | 0.537 | 0.831 | 0.983 | 97.2% |
| scarce_seed42 | 295 | 0.184 | 0.533 | 0.797 | 0.985 | 94.8% |
| low_reports_seed42 | 325 | 0.116 | 0.497 | 0.831 | 0.987 | 98.7% |

`hard`: weaker stress signal, victims rarely disclose instructions; `scarce`: only 30% telemetry consent; `low_reports`: 30% of frauds reported plus 200 false reports.

## 8. Target vs measured

| Metric | Target | Measured (synthetic) | Status |
|---|---|---|---|
| ROC-AUC | 0.94 | 0.988 | met on synthetic data only |
| Relative FPR reduction vs rules (matched recall 66.8%) | 35% | 98.5% | met on synthetic data only |
| p95 model latency | < 50 ms | 5.4 ms | met in this environment |
| Real-world validity | — | not measured | **limitation**: needs a real labelled dataset |
