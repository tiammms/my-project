# NeuroShield — measured experimental results

> **All numbers below are measured on SYNTHETIC data** produced by `ml/data/generate.py` (seed 42, dataset hash `0bcd3f62fb4a`). They show that the pipeline works and how the components compare *under the generator's assumptions*. They are **not** evidence of performance on real bank traffic.

Generated from `experiments/results/evaluation_default.json` (model `ns-hgb-hybrid-1.0.0+0bcd3f62fb4a`, trained 2026-10-14T18:10:37+00:00).

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
| C1. Logistic regression | hybrid | 0.983 [0.977–0.988] | 0.768 [0.727–0.809] |
| C2. Random forest | hybrid | 0.983 [0.973–0.992] | 0.850 [0.814–0.884] |
| C3. Gradient boosting **(selected)** | hybrid | 0.990 [0.985–0.994] | 0.879 [0.842–0.912] |

Winner chosen by validation PR-AUC among hybrid models: **hgb_hybrid**.

## 2. False-positive rate vs the rule baseline

Rule baseline (flag if ≥2 points): test recall 66.8%, FPR 4.78%, precision 22.5%.

**(a) Matched operating point** — each model's ROC curve read at exactly the baseline's test recall (the like-for-like comparison required by the protocol; a diagnostic point, not a deployable threshold):

| System | Test recall | Test FPR | Relative FPR reduction vs rules |
|---|---|---|---|
| B1. Logistic regression (txn) | 66.8% | 5.69% | -19.2% |
| B2. Gradient boosting (txn) | 66.8% | 2.73% | 42.9% |
| C1. Logistic regression (hybrid) | 66.8% | 0.48% | 89.9% |
| C2. Random forest (hybrid) | 66.8% | 0.09% | 98.2% |
| C3. Gradient boosting (hybrid) | 66.8% | 0.07% | 98.5% |

**(b) Deployable protocol** — threshold set on validation to reach the baseline's validation recall, then applied to test:

| System | Test recall | Test FPR | Relative FPR reduction vs rules |
|---|---|---|---|
| B1. Logistic regression (txn) | 70.3% | 7.05% | -47.6% |
| B2. Gradient boosting (txn) | 66.4% | 2.70% | 43.5% |
| C1. Logistic regression (hybrid) | 64.3% | 0.31% | 93.5% |
| C2. Random forest (hybrid) | 58.7% | 0.06% | 98.8% |
| C3. Gradient boosting (hybrid) | 59.0% | 0.02% | 99.5% |

Note: thresholds transferred from validation do not hit exactly the same test recall (temporal drift: new mule rings appear in the test period). Compare recall and FPR together.

FPR at fixed validation-recall targets (test):

| Target recall (val) | B2. Gradient boosting | C3. Gradient boosting |
|---|---|---|
| 0.60 | recall 48.1%, FPR 0.70% | recall 40.6%, FPR 0.00% |
| 0.70 | recall 57.6%, FPR 1.56% | recall 48.4%, FPR 0.00% |
| 0.80 | recall 66.4%, FPR 2.66% | recall 58.7%, FPR 0.02% |
| 0.90 | recall 73.9%, FPR 5.54% | recall 75.3%, FPR 0.18% |
| 0.95 | recall 81.3%, FPR 10.27% | recall 85.9%, FPR 1.04% |

## 3. Decision policy (thresholds from validation)

| Mode | Medium (step-up) | High (review/hold) | Test recall ≥medium | Test FPR ≥medium | Test precision ≥high | Alert rate |
|---|---|---|---|---|---|---|
| balanced | 8.06 | 46.77 | 85.9% | 1.04% | 91.6% | 2.77% |
| strict | 2.83 | 20.50 | 91.2% | 2.42% | 80.2% | 4.23% |

Rationale: medium (step-up) = threshold reaching 95% validation recall, but never flagging more than 4% of validation transactions; high (review / temporary hold recommendation) = lowest score where validation precision >= 90%.

## 4. Legitimate-but-unusual customers (balanced policy, test)

| Segment | n | Step-up or higher | High | Rule baseline flag rate |
|---|---|---|---|---|
| big_purchase | 297 | 3.0% | 1.3% | 68.7% |
| habit_change | 81 | 3.7% | 0.0% | 2.5% |
| new_device | 311 | 3.5% | 0.0% | 7.1% |
| new_recipient | 1850 | 1.4% | 0.3% | 6.3% |
| no_telemetry | 2589 | 1.9% | 0.1% | 2.8% |
| normal | 7111 | 0.3% | 0.1% | 1.7% |
| relay | 525 | 1.9% | 0.4% | 5.1% |
| travel | 774 | 0.4% | 0.0% | 10.7% |
| young_recipient | 88 | 9.1% | 1.1% | 1.1% |

| Fraud type | n | Recall ≥medium | Recall high | Rule baseline recall |
|---|---|---|---|---|
| app_scam | 127 | 81.1% | 70.1% | 70.1% |
| ato | 100 | 91.0% | 80.0% | 77.0% |
| mule_layering | 56 | 87.5% | 67.9% | 41.1% |

## 5. Ablations (hybrid gradient boosting, test PR-AUC)

Full hybrid: 0.879

| Removed component | PR-AUC | FPR at baseline recall |
|---|---|---|
| behavioral | 0.822 | 0.07% |
| graph | 0.832 | 0.09% |
| social_engineering | 0.861 | 0.05% |
| recipient_reputation | 0.857 | 0.04% |

## 6. Calibration, explanations, latency

- Calibration on test: ECE 0.0018, Brier 0.0056. The score is still presented as a 0–100 risk score, not a probability.
- Explanation deletion test (n=300): occluding the top-attributed group lowers log-odds by 4.19 on average vs 1.16 for a random group.
- In-process latency (Intel(R) Xeon(R) Processor @ 2.10GHz, 2 vCPU, Python 3.13.16, scikit-learn 1.9.1), 1000 runs after 50 warm-up:
  - model_only_single_row: p50 3.20 ms, p95 4.84 ms, p99 6.39 ms
  - featurize_plus_model: p50 4.04 ms, p95 6.09 ms, p99 8.28 ms
  - occlusion_explanation: p50 3.71 ms, p95 5.19 ms, p99 7.03 ms
- Full HTTP request `POST /api/v1/risk/score` (no_persist, n=500): p50 11.5 ms, p95 15.7 ms, p99 19.8 ms (urllib, sequential, same host).
- Full HTTP request `POST /api/v1/risk/score` (persist, n=200): p50 11.5 ms, p95 18.6 ms, p99 21.3 ms (urllib, sequential, same host).

## 7. Robustness: other seeds and harsher generator assumptions

| Run | Test pos. | Rules PR-AUC | Txn-only GBM PR-AUC | Hybrid PR-AUC | Hybrid ROC-AUC | Rel. FPR reduction vs rules (matched recall) |
|---|---|---|---|---|---|---|
| default_seed42 (main) | 283 | 0.185 | 0.537 | 0.879 | 0.990 | 98.5% |
| default_seed43 | 265 | 0.204 | 0.297 | 0.850 | 0.989 | 92.9% |
| default_seed44 | 230 | 0.095 | 0.435 | 0.808 | 0.988 | 98.8% |
| hard_seed42 | 283 | 0.185 | 0.537 | 0.850 | 0.985 | 97.5% |
| scarce_seed42 | 295 | 0.184 | 0.533 | 0.822 | 0.986 | 97.1% |
| low_reports_seed42 | 325 | 0.116 | 0.497 | 0.862 | 0.989 | 99.3% |

`hard`: weaker stress signal, victims rarely disclose instructions; `scarce`: only 30% telemetry consent; `low_reports`: 30% of frauds reported plus 200 false reports.

## 9. Fairness: legitimate transfers to new accounts (< 30 days)

v2 fixes (applied to everything above): the navigation-path metric is compared only with a baseline from the same screen flow (new vs known payee, >= 3 sessions, otherwise 'not comparable'); 'pass-through' counts only account-to-account transfers and only the forwarding band 60-130% (spending own money is not forwarding). v1 results are kept in `experiments/results/v1_before_flowfix/`.

Pre-registered rule: choose the variant with the lowest VALIDATION false-alarm rate (step-up or higher) on young-recipient legit
  transfers, among variants whose validation PR-AUC is within 0.01 of the best variant.

| Model variant | Validation PR-AUC | Test PR-AUC | Test false alarms, new accounts |
|---|---|---|---|
| hybrid_all_features **(selected)** | 0.956 | 0.879 | 9.0% (17/188, 95% CI 5.7%–14.0%) |
| hybrid_without_direct_recipient_age | 0.927 | 0.857 | 10.1% (19/188, 95% CI 6.6%–15.2%) |

Policy option on the deployed model (opt-in setting, off by default):

| Policy | Young threshold | Val. false alarms | Val. young-fraud recall | Test false alarms | Test young-fraud recall | Test overall recall |
|---|---|---|---|---|---|---|
| shared threshold (current default) | 8.06 | 9.6% | 96.8% | 9.0% | 95.8% | 85.9% |
| young false-alarm <= 1.5x other customers **(chosen)** | 46.73 | 2.7% | 95.2% | 1.6% | 89.6% | 84.8% |
| young false-alarm <= 2x other customers | 46.73 | 2.7% | 95.2% | 1.6% | 89.6% | 84.8% |
| young false-alarm <= 3x other customers | 27.13 | 3.7% | 95.2% | 2.7% | 95.8% | 85.9% |
| young false-alarm <= 5x other customers | 13.13 | 5.9% | 95.2% | 4.8% | 95.8% | 85.9% |

Offered as an opt-in policy setting (off by default): it lowers false alarms for customers paying new accounts but can miss more frauds to young accounts. The bank decides.


## 10. Adaptive adversary stress test

283 test frauds re-scored after the fraudster hides one signal (balanced policy, step-up or higher). What-if on synthetic test frauds; legitimate traffic unchanged, so FPR is unchanged.

| Evasion | NeuroShield recall | Drop | Rule baseline recall |
|---|---|---|---|
| none | 85.9% | — | 66.8% |
| calm behavior | 74.2% | 11.7 pp | 66.8% |
| no call no admission | 83.4% | 2.5 pp | 66.8% |
| own device and region | 83.4% | 2.5 pp | 65.0% |
| aged mule account | 75.6% | 10.2 pp | 66.8% |
| unreported mule | 73.5% | 12.4 pp | 24.4% |
| slow layering | 80.6% | 5.3 pp | 66.8% |
| small amounts | 78.8% | 7.1 pp | 53.7% |
| daytime only | 83.4% | 2.5 pp | 65.0% |
| all combined | 1.1% | 84.8 pp | 1.1% |

## 11. Load test

`scripts/load_test.py`: POST /api/v1/risk/score (persist=false, full explanation); 2 vCPU; Client threads and server share the same machine; numbers are a lower bound.

| Workers | Clients | Req/s | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|
| 1 | 1 | 91 | 10.2 ms | 14.6 ms | 16.0 ms | 0 |
| 1 | 4 | 89 | 41.3 ms | 58.6 ms | 62.2 ms | 0 |
| 1 | 8 | 85 | 89.6 ms | 122.2 ms | 125.9 ms | 0 |
| 1 | 16 | 91 | 162.0 ms | 248.4 ms | 265.4 ms | 0 |
| 2 | 1 | 82 | 11.2 ms | 15.5 ms | 19.4 ms | 0 |
| 2 | 4 | 165 | 28.3 ms | 43.6 ms | 47.4 ms | 0 |
| 2 | 8 | 161 | 47.0 ms | 63.5 ms | 66.2 ms | 0 |
| 2 | 16 | 169 | 115.3 ms | 170.9 ms | 194.8 ms | 0 |
- 1 worker: ~85-91 req/s (CPU-bound on 2 vCPU shared with the client); p95 14.6 ms with one client, latency grows with queueing.
- 2 workers: ~165 req/s from 4 clients; p95 15.5 ms with one client, 43.6 ms with four. 0 errors at all levels.
- Fixed in part 5: uvicorn's multi-worker socket has proto 0, so asyncio skipped TCP_NODELAY and every request waited ~44 ms for a delayed ACK (even GET /health). backend/app/main.py now enables TCP_NODELAY on all accepted TCP connections (regression test: backend/tests/test_server.py). Before: 2 workers, 1 client p50 56 ms; after: 11.2 ms.
- Client threads and server share the same machine, so these are lower bounds.

## 12. Evasion-augmented training (not adopted)

Training frauds were duplicated with one SEEN evasion hidden (987 copies). UNSEEN evasions were never used in training. Rule: adopt the augmented model if, versus the standard model,
   (a) clean validation PR-AUC drops by at most 0.005,
   (b) validation false-positive rate at the step-up threshold rises by at most 0.2 percentage points,
   (c) mean validation recall over UNSEEN single evasions rises by at least 1 percentage point.

| Model | Val PR-AUC | Val FPR (step-up) | Val recall, seen evasions | Val recall, unseen | Test PR-AUC | Test recall, seen | Test recall, unseen |
|---|---|---|---|---|---|---|---|
| standard | 0.956 | 1.28% | 90.6% | 92.9% | 0.879 | 78.0% | 80.2% |
| evasion_augmented | 0.955 | 1.09% | 92.9% | 92.4% | 0.866 | 81.0% | 77.9% |

Checks: pr_auc_drop_le_0.005=True, fpr_rise_le_0.2pp=True, unseen_recall_gain_ge_1pp=False → **not adopted**. Augmentation helps against the tricks it was trained on but does not generalise to new ones; robustness needs new independent signals.

## 13. Target vs measured

| Metric | Target | Measured (synthetic) | Status |
|---|---|---|---|
| ROC-AUC | 0.94 | 0.990 | met on synthetic data only |
| Relative FPR reduction vs rules (matched recall 66.8%) | 35% | 98.5% | met on synthetic data only |
| p95 model latency | < 50 ms | 6.1 ms | met in this environment |
| Real-world validity | — | not measured | **limitation**: needs a real labelled dataset |
