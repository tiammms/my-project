"""NeuroShield business case: scenario model + sensitivity + break-even.

All bank parameters are SCENARIO ASSUMPTIONS (not data from any real bank). Model quality
parameters are deliberately NOT taken 1:1 from the synthetic experiment, which overstates real-world
performance; the synthetic result is shown only as an upper-bound reference row.

Net Benefit = Avoided Fraud Losses (incremental vs current rules)
            + Reduced False-Positive Handling Costs
            - Infrastructure - Integration (amortised) - Operating costs

Usage: python -m scripts.economics      -> experiments/results/economics.json, docs/ECONOMICS.md,
                                           docs/figures/econ_scenarios.png, docs/figures/econ_tornado.png
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
B = 1e9

SCENARIOS = {
    "conservative": dict(
        transfers_per_year=20e6, fraud_rate=0.0002, avg_fraud_loss=300_000,
        baseline_fpr=0.005, baseline_recall=0.50,
        fpr_reduction=0.20, recall_uplift_pp=0.0, stepup_share=0.70,
        review_cost=3_000, stepup_cost=300, prevention_hold=0.70, prevention_stepup=0.30,
        infra_per_year=40e6, integration_one_time=150e6, amortisation_years=3, operating_per_year=120e6),
    "base": dict(
        transfers_per_year=50e6, fraud_rate=0.0005, avg_fraud_loss=400_000,
        baseline_fpr=0.01, baseline_recall=0.60,
        fpr_reduction=0.35, recall_uplift_pp=0.05, stepup_share=0.80,
        review_cost=2_500, stepup_cost=200, prevention_hold=0.80, prevention_stepup=0.40,
        infra_per_year=30e6, integration_one_time=120e6, amortisation_years=3, operating_per_year=100e6),
    "optimistic": dict(
        transfers_per_year=100e6, fraud_rate=0.001, avg_fraud_loss=500_000,
        baseline_fpr=0.02, baseline_recall=0.67,
        fpr_reduction=0.60, recall_uplift_pp=0.10, stepup_share=0.88,
        review_cost=2_000, stepup_cost=100, prevention_hold=0.90, prevention_stepup=0.50,
        infra_per_year=25e6, integration_one_time=100e6, amortisation_years=3, operating_per_year=80e6),
}

LABELS = {
    "transfers_per_year": "Transfers per year", "fraud_rate": "Fraud rate (share of transfers)",
    "avg_fraud_loss": "Average loss per fraud, KZT", "baseline_fpr": "Current rules: false-positive rate",
    "baseline_recall": "Current rules: recall", "fpr_reduction": "NeuroShield: relative FPR reduction at equal recall",
    "recall_uplift_pp": "NeuroShield: extra recall (percentage points)",
    "stepup_share": "Share of false alerts closed by step-up instead of manual review (same for rules and NeuroShield)",
    "review_cost": "Manual review cost per alert, KZT", "stepup_cost": "Step-up friction cost per legit customer, KZT",
    "prevention_hold": "Loss prevented when flagged for review/hold", "prevention_stepup": "Loss prevented by step-up only",
    "infra_per_year": "Infrastructure per year, KZT", "integration_one_time": "Integration (one-time), KZT",
    "amortisation_years": "Integration amortisation, years", "operating_per_year": "Operating (team, monitoring) per year, KZT",
}


def compute(p: dict) -> dict:
    n = p["transfers_per_year"]
    frauds = n * p["fraud_rate"]
    legit = n - frauds
    s = p["stepup_share"]
    unit_fp = s * p["stepup_cost"] + (1 - s) * p["review_cost"]
    # current rules, given the SAME tiered handling (step-up for most alerts, review for the rest),
    # so that only the model's own contribution (fewer false alerts, more frauds caught) is credited
    b_fp = legit * p["baseline_fpr"]
    b_tp = frauds * p["baseline_recall"]
    b_cost = b_fp * unit_fp + b_tp * p["review_cost"]
    b_prevented = b_tp * p["avg_fraud_loss"] * p["prevention_hold"]
    # NeuroShield: same-or-better recall, fewer false alerts, tiered handling
    m_recall = min(1.0, p["baseline_recall"] + p["recall_uplift_pp"])
    m_fp = b_fp * (1 - p["fpr_reduction"])
    m_tp = frauds * m_recall
    m_cost = m_fp * unit_fp + m_tp * p["review_cost"]
    # extra frauds caught are assumed to be caught at step-up level (weaker prevention)
    m_prevented = b_tp * p["avg_fraud_loss"] * p["prevention_hold"] + (m_tp - b_tp) * p["avg_fraud_loss"] * p["prevention_stepup"]
    avoided = m_prevented - b_prevented
    fp_saving = b_cost - m_cost
    integ = p["integration_one_time"] / p["amortisation_years"]
    costs = p["infra_per_year"] + integ + p["operating_per_year"]
    net = avoided + fp_saving - costs
    return {
        "frauds_per_year": frauds, "baseline_false_alerts": b_fp, "neuroshield_false_alerts": m_fp,
        "false_alerts_avoided": b_fp - m_fp, "manual_reviews_baseline": b_fp * (1 - s) + b_tp,
        "manual_reviews_neuroshield": m_fp * (1 - s) + m_tp, "stepups_neuroshield": m_fp * s,
        "avoided_fraud_losses": avoided, "reduced_fp_handling_costs": fp_saving,
        "infrastructure": p["infra_per_year"], "integration_amortised": integ, "operating": p["operating_per_year"],
        "total_costs": costs, "net_benefit": net, "roi": net / costs if costs else None,
        "payback_months": (p["integration_one_time"] / ((avoided + fp_saving - p["infra_per_year"] - p["operating_per_year"]) / 12))
        if (avoided + fp_saving - p["infra_per_year"] - p["operating_per_year"]) > 0 else None,
    }


def break_even(p: dict, key: str, lo: float, hi: float) -> float | None:
    """Smallest value of `key` in [lo, hi] giving net >= 0 (bisection, net assumed monotone)."""
    def f(v):
        q = dict(p); q[key] = v
        return compute(q)["net_benefit"]
    if f(hi) < 0:
        return None
    if f(lo) >= 0:
        return lo
    for _ in range(60):
        mid = (lo + hi) / 2
        if f(mid) >= 0:
            hi = mid
        else:
            lo = mid
    return hi


def tornado(p: dict) -> list[dict]:
    base = compute(p)["net_benefit"]
    out = []
    for k in ("transfers_per_year", "fraud_rate", "avg_fraud_loss", "baseline_fpr", "fpr_reduction", "recall_uplift_pp",
              "stepup_share", "review_cost", "prevention_stepup", "operating_per_year", "integration_one_time"):
        lo, hi = dict(p), dict(p)
        lo[k] = p[k] * 0.5
        hi[k] = min(p[k] * 1.5, 1.0) if k in ("stepup_share", "fpr_reduction") else p[k] * 1.5
        out.append({"parameter": k, "label": LABELS[k], "low_value": lo[k], "high_value": hi[k],
                    "net_at_low": compute(lo)["net_benefit"], "net_at_high": compute(hi)["net_benefit"], "base": base})
    out.sort(key=lambda d: -abs(d["net_at_high"] - d["net_at_low"]))
    return out


def main():
    ev = json.loads((ROOT / "experiments/results/evaluation_default.json").read_text())
    cmp = ev["comparison_vs_rules"][ev["winner"]]["matched_test_recall"]
    pol = ev["policies"]["balanced"]
    results = {k: {"assumptions": v, "result": compute(v)} for k, v in SCENARIOS.items()}
    # upper-bound reference: synthetic measurements applied 1:1 (NOT a forecast)
    syn = dict(SCENARIOS["base"])
    syn.update(baseline_fpr=cmp["baseline_fpr"], baseline_recall=cmp["baseline_recall"],
               fpr_reduction=cmp["relative_fpr_reduction_pct"] / 100, recall_uplift_pp=0.0)
    results["synthetic_upper_bound"] = {"assumptions": syn, "result": compute(syn),
                                        "note": "Base bank parameters with the synthetic test-set rule baseline and the measured 98.5% FPR "
                                                "reduction. Shown only to illustrate how much the synthetic result overstates; not a forecast."}
    base = SCENARIOS["base"]
    be = {
        "fpr_reduction_needed_base_without_recall_gain": break_even({**base, "recall_uplift_pp": 0.0}, "fpr_reduction", 0.0, 1.0),
        "recall_uplift_needed_base_without_fpr_gain": break_even({**base, "fpr_reduction": 0.0}, "recall_uplift_pp", 0.0, 0.4),
        "fpr_reduction_needed_conservative": break_even(SCENARIOS["conservative"], "fpr_reduction", 0.0, 1.0),
        "transfers_needed_base": break_even(base, "transfers_per_year", 1e5, 5e8),
        "transfers_needed_conservative": break_even(SCENARIOS["conservative"], "transfers_per_year", 1e5, 5e8),
    }
    out = {"currency": "KZT", "scenarios": results, "tornado_base": tornado(base), "break_even": be,
           "measured_reference": {"synthetic_relative_fpr_reduction_pct": cmp["relative_fpr_reduction_pct"],
                                  "synthetic_baseline_fpr": cmp["baseline_fpr"], "synthetic_recall": cmp["baseline_recall"],
                                  "balanced_policy_stepup_share_of_false_alerts":
                                      1 - pol["test_at_high"]["fp"] / max(1, pol["test_at_medium"]["fp"])},
           "disclaimer": "Scenario assumptions only. Model metrics alone do not prove savings; the effect depends on volume, "
                         "error costs and implementation quality."}
    (ROOT / "experiments/results/economics.json").write_text(json.dumps(out, indent=2))
    write_md(out)
    plot(out)
    for k, v in results.items():
        r = v["result"]
        print(f"{k:22s} net {r['net_benefit'] / B:7.2f} B KZT | avoided {r['avoided_fraud_losses'] / B:6.2f} | "
              f"fp-saving {r['reduced_fp_handling_costs'] / B:6.2f} | costs {r['total_costs'] / B:5.2f} | ROI {r['roi']:.2f}")
    print("break-even", be)


def kzt(x):
    return f"{x / B:,.2f} млрд ₸" if abs(x) >= 1e9 else f"{x / 1e6:,.0f} млн ₸"


def write_md(out):
    S = out["scenarios"]
    names = {"conservative": "Консервативный", "base": "Базовый", "optimistic": "Оптимистичный"}
    L = ["# Экономическая модель NeuroShield\n",
         "> Все параметры банка — **сценарные допущения**, а не данные реального банка. Качество модели **не** переносится 1:1 из "
         "синтетического эксперимента: там снижение FPR — 98.5%, а в сценариях взято 20–60%. Метрики модели сами по себе "
         "не доказывают экономию: эффект зависит от объёма операций, стоимости ошибок и качества внедрения. "
         "Файл сгенерирован скриптом `scripts/economics.py`.\n",
         "## Формула\n",
         "```\nNet Benefit = Avoided Fraud Losses + Reduced False-Positive Handling Costs\n            − Infrastructure − Integration (амортизация) − Operating\n```\n",
         "Сравнение идёт с **текущими правилами банка** при том же или лучшем recall:\n",
         "- Обе системы получают **одинаковую** многоуровневую обработку: доля `stepup_share` ложных алертов закрывается дешёвым step-up, остальные идут на ручную проверку. Поэтому эффект от самой политики step-up модели **не** засчитывается.",
         "- NeuroShield даёт меньше ложных алертов при том же recall (`fpr_reduction`).",
         "- Дополнительно пойманные мошенничества (`recall_uplift_pp`) считаются пойманными только на уровне step-up, то есть с более слабым предотвращением (`prevention_stepup`).\n",
         "## Допущения по сценариям\n",
         "| Параметр | " + " | ".join(names.values()) + " |", "|---|---|---|---|"]
    for k, lab in LABELS.items():
        vals = []
        for s in names:
            v = S[s]["assumptions"][k]
            vals.append(f"{v:.2%}" if k in ("fraud_rate", "baseline_fpr") else f"{v:.0%}" if k in ("baseline_recall", "fpr_reduction", "stepup_share", "prevention_hold", "prevention_stepup")
                        else f"+{v * 100:.0f} п.п." if k == "recall_uplift_pp" else f"{v:,.0f}")
        L.append(f"| {lab} | " + " | ".join(vals) + " |")
    L += ["\n## Результат (в год)\n", "| Показатель | " + " | ".join(names.values()) + " |", "|---|---|---|---|"]
    rows = [("Мошеннических переводов в год", "frauds_per_year", "int"), ("Ложных алертов: сейчас", "baseline_false_alerts", "int"),
            ("Ложных алертов: NeuroShield", "neuroshield_false_alerts", "int"), ("Ручных проверок: сейчас", "manual_reviews_baseline", "int"),
            ("Ручных проверок: NeuroShield", "manual_reviews_neuroshield", "int"),
            ("Предотвращённые потери (прирост)", "avoided_fraud_losses", "kzt"), ("Экономия на ложных срабатываниях", "reduced_fp_handling_costs", "kzt"),
            ("Инфраструктура", "infrastructure", "kzt"), ("Интеграция (амортизация)", "integration_amortised", "kzt"),
            ("Эксплуатация", "operating", "kzt"), ("**Чистый эффект**", "net_benefit", "kzt"), ("ROI (чистый эффект / затраты)", "roi", "x"),
            ("Окупаемость интеграции", "payback_months", "m")]
    for lab, k, f in rows:
        vals = []
        for s in names:
            v = S[s]["result"][k]
            vals.append(f"{v:,.0f}" if f == "int" else kzt(v) if f == "kzt" else (f"{v:.2f}×" if f == "x" else ("не окупается" if v is None else f"{v:.1f} мес.")))
        L.append(f"| {lab} | " + " | ".join(vals) + " |")
    u = S["synthetic_upper_bound"]["result"]
    L += [f"\n**Справочно (не прогноз):** если перенести синтетические результаты 1:1 (базовые параметры банка, FPR правил 4.78%, "
          f"снижение 98.5%), чистый эффект был бы {kzt(u['net_benefit'])}. Эта цифра показывает, насколько синтетика завышает результат, "
          "поэтому в защите её использовать нельзя.\n",
          "## Точка безубыточности\n"]
    be = out["break_even"]
    f0 = be["fpr_reduction_needed_base_without_recall_gain"]; r0 = be["recall_uplift_needed_base_without_fpr_gain"]
    L.append("- Базовый сценарий, только за счёт меньшего числа ложных алертов (без прироста recall): "
             + (f"нужно снижение FPR не меньше **{f0:.1%}**." if f0 is not None else "**не окупается даже при 100% снижении FPR** — экономия на проверках слишком мала по сравнению с затратами."))
    L.append("- Базовый сценарий, только за счёт прироста recall (без снижения FPR): "
             + (f"нужно ловить на **{r0 * 100:.1f} п.п.** больше мошенничества, чем правила." if r0 is not None else "недостижимо."))
    L.append("- Консервативный сценарий: " + (f"нужно снижение FPR не меньше **{be['fpr_reduction_needed_conservative']:.1%}**." if be["fpr_reduction_needed_conservative"] is not None else "не окупается даже при 100% снижении FPR."))
    L.append(f"- Минимальный объём для безубыточности: **{be['transfers_needed_base'] / 1e6:,.1f} млн** переводов в год в базовом сценарии, "
             + (f"**{be['transfers_needed_conservative'] / 1e6:,.1f} млн** в консервативном." if be["transfers_needed_conservative"] else "в консервативном недостижимо в рассмотренном диапазоне."))
    L += ["\n## Чувствительность (базовый сценарий, каждый параметр ±50%)\n", "![tornado](figures/econ_tornado.png)\n",
          "| Параметр | Чистый эффект при −50% | при +50% |", "|---|---|---|"]
    for t in out["tornado_base"]:
        L.append(f"| {t['label']} | {kzt(t['net_at_low'])} | {kzt(t['net_at_high'])} |")
    L += ["\n## Ограничения\n",
          "- Нет данных реального банка: объёмы, доля мошенничества и стоимость операций — допущения. На пилоте их нужно заменить фактическими.",
          "- Снижение FPR и прирост recall нужно измерить в shadow mode на реальном трафике (этап 2 дорожной карты).",
          "- Не учтены регуляторные расходы, репутационный эффект, отток клиентов из-за трения, стоимость дообучения при дрейфе и юридические издержки.",
          "- Предотвращение потерь после алерта зависит от процесса банка (скорость звонка клиенту, возможность задержать перевод)."]
    (ROOT / "docs/ECONOMICS.md").write_text("\n".join(L) + "\n")


def plot(out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig_dir = ROOT / "docs/figures"; fig_dir.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.edgecolor": "#8b98a9", "axes.labelcolor": "#33404f", "xtick.color": "#33404f", "ytick.color": "#33404f"})
    # scenarios: stacked components
    S = out["scenarios"]; keys = ["conservative", "base", "optimistic"]; ru = ["Консервативный", "Базовый", "Оптимистичный"]
    fig, ax = plt.subplots(figsize=(8, 4.2), dpi=200)
    import numpy as np
    x = np.arange(3); w = 0.22
    a = [S[k]["result"]["avoided_fraud_losses"] / B for k in keys]
    f = [S[k]["result"]["reduced_fp_handling_costs"] / B for k in keys]
    c = [-S[k]["result"]["total_costs"] / B for k in keys]
    n = [S[k]["result"]["net_benefit"] / B for k in keys]
    for i, (vals, col, lab) in enumerate([(a, "#2a78d6", "Предотвращённые потери"), (f, "#1baf7a", "Экономия на ложных срабатываниях"),
                                          (c, "#eb6834", "Затраты"), (n, "#33404f", "Чистый эффект")]):
        bars = ax.bar(x + (i - 1.5) * w, vals, w * 0.92, color=col, label=lab)
        for b, v in zip(bars, vals):
            ax.text(b.get_x() + b.get_width() / 2, v + (0.06 if v >= 0 else -0.06) * max(1, max(n)), f"{v:.2f}",
                    ha="center", va="bottom" if v >= 0 else "top", fontsize=8, color="#33404f")
    ax.axhline(0, color="#8b98a9", lw=1); ax.set_xticks(x, ru); ax.set_ylabel("млрд ₸ в год")
    ax.set_ylim(min(min(c), min(n)) - 0.45, max(max(a), max(n)) * 1.25)
    ax.tick_params(axis="x", length=0, pad=8)
    ax.legend(frameon=False, fontsize=9, ncol=2, loc="upper left"); ax.grid(axis="y", color="#e6e9ee", lw=0.8); ax.set_axisbelow(True)
    fig.tight_layout(); fig.savefig(fig_dir / "econ_scenarios.png", transparent=False, facecolor="white"); plt.close(fig)
    # tornado
    T = out["tornado_base"][:8][::-1]; base = T[0]["base"] / B
    short = {"transfers_per_year": "Объём переводов", "fraud_rate": "Доля мошенничества", "avg_fraud_loss": "Средняя потеря",
             "baseline_fpr": "FPR текущих правил", "fpr_reduction": "Снижение FPR моделью", "recall_uplift_pp": "Прирост recall",
             "stepup_share": "Доля step-up", "review_cost": "Стоимость проверки", "prevention_stepup": "Эффект step-up",
             "operating_per_year": "Эксплуатация", "integration_one_time": "Интеграция"}
    fig, ax = plt.subplots(figsize=(8, 4.2), dpi=200)
    for i, t in enumerate(T):
        lo, hi = t["net_at_low"] / B, t["net_at_high"] / B
        ax.barh(i, lo - base, left=base, color="#eb6834", height=0.6)
        ax.barh(i, hi - base, left=base, color="#2a78d6", height=0.6)
    ax.axvline(base, color="#33404f", lw=1); ax.axvline(0, color="#8b98a9", lw=1, ls="--")
    ax.set_yticks(range(len(T)), [short[t["parameter"]] for t in T]); ax.set_xlabel("Чистый эффект, млрд ₸ в год (база = %.2f)" % base)
    from matplotlib.patches import Patch
    ax.legend(handles=[Patch(color="#eb6834", label="параметр −50%"), Patch(color="#2a78d6", label="параметр +50%")], frameon=False, fontsize=9, loc="lower right")
    ax.grid(axis="x", color="#e6e9ee", lw=0.8); ax.set_axisbelow(True)
    fig.tight_layout(); fig.savefig(fig_dir / "econ_tornado.png", facecolor="white"); plt.close(fig)


if __name__ == "__main__":
    main()
