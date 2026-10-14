"""Short analytical report (PDF, Russian) generated from experiments/results.

Usage: python -m scripts.make_report_pdf  ->  docs/NeuroShield_report.pdf
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (Image, KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

ROOT = Path(__file__).resolve().parents[1]
R = ROOT / "experiments/results"
FIG = ROOT / "docs/figures"
FONT_DIR = Path(matplotlib.__file__).parent / "mpl-data/fonts/ttf"
pdfmetrics.registerFont(TTFont("DV", str(FONT_DIR / "DejaVuSans.ttf")))
pdfmetrics.registerFont(TTFont("DV-B", str(FONT_DIR / "DejaVuSans-Bold.ttf")))
pdfmetrics.registerFont(TTFont("DV-I", str(FONT_DIR / "DejaVuSans-Oblique.ttf")))
from reportlab.pdfbase.pdfmetrics import registerFontFamily
registerFontFamily("DV", normal="DV", bold="DV-B", italic="DV-I", boldItalic="DV-B")

INK, MUTED, TEAL, LINE, TINT = colors.HexColor("#1B2430"), colors.HexColor("#5B6878"), colors.HexColor("#0E8C7D"), colors.HexColor("#D5DCE4"), colors.HexColor("#F1F5F8")
st = {
    "h0": ParagraphStyle("h0", fontName="DV-B", fontSize=22, leading=27, textColor=INK, spaceAfter=4),
    "sub": ParagraphStyle("sub", fontName="DV", fontSize=11.5, leading=16, textColor=MUTED, spaceAfter=10),
    "h1": ParagraphStyle("h1", fontName="DV-B", fontSize=14, leading=18, textColor=INK, spaceBefore=12, spaceAfter=6),
    "p": ParagraphStyle("p", fontName="DV", fontSize=9.6, leading=14, textColor=INK, alignment=TA_LEFT, spaceAfter=5),
    "small": ParagraphStyle("small", fontName="DV", fontSize=8.2, leading=11, textColor=MUTED, spaceAfter=4),
    "cell": ParagraphStyle("cell", fontName="DV", fontSize=8.4, leading=11, textColor=INK),
    "cellb": ParagraphStyle("cellb", fontName="DV-B", fontSize=8.4, leading=11, textColor=INK),
    "box": ParagraphStyle("box", fontName="DV", fontSize=9.2, leading=13.5, textColor=INK, backColor=TINT, borderPadding=8, spaceBefore=6, spaceAfter=10),
}
P = lambda t, s="p": Paragraph(t, st[s])
pct = lambda x, d=1: f"{100 * x:.{d}f}%".replace(".", ",")
sp = lambda x: f"{x:,}".replace(",", "\u202f")
n3 = lambda x, d=3: f"{x:.{d}f}".replace(".", ",")


def table(rows, widths, header=True, bold_last=False):
    data = [[Paragraph(str(c), st["cellb" if (header and i == 0) else "cell"]) for c in r] for i, r in enumerate(rows)]
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    sty = [("GRID", (0, 0), (-1, -1), 0.4, LINE), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
           ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    if header:
        sty.append(("BACKGROUND", (0, 0), (-1, 0), TINT))
    if bold_last:
        sty.append(("BACKGROUND", (0, -1), (-1, -1), colors.HexColor("#E3F4F1")))
    t.setStyle(TableStyle(sty))
    return t


def curves_figure(ev):
    FIG.mkdir(parents=True, exist_ok=True)
    sysc = [("rules", "Правила", "#D95926", "--"), ("hgb_txn", "Бустинг, транзакции", "#199E70", "-"), ("hgb_hybrid", "NeuroShield (гибрид)", "#2A78D6", "-")]
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "axes.spines.top": False, "axes.spines.right": False})
    fig, ax = plt.subplots(1, 2, figsize=(8, 3.2), dpi=200)
    for k, lab, col, ls in sysc:
        r = sorted(ev["curves"][k]["roc"]); p = sorted(ev["curves"][k]["pr"])
        ax[0].plot([a for a, _ in r], [b for _, b in r], color=col, ls=ls, lw=1.8, label=lab)
        ax[1].plot([a for a, _ in p], [b for _, b in p], color=col, ls=ls, lw=1.8, label=lab)
    ax[0].set_xlim(0, 0.1); ax[0].set_xlabel("False-positive rate (до 10%)"); ax[0].set_ylabel("Recall"); ax[0].set_title("ROC (фрагмент)", fontsize=10)
    ax[1].set_xlabel("Recall"); ax[1].set_ylabel("Precision"); ax[1].set_title("Precision–recall", fontsize=10)
    for a in ax:
        a.grid(color="#E6E9EE", lw=0.7); a.set_axisbelow(True)
    h, l = ax[0].get_legend_handles_labels()
    fig.legend(h, l, frameon=False, fontsize=8, loc="lower center", ncol=3)
    fig.tight_layout(rect=[0, 0.08, 1, 1]); fig.savefig(FIG / "report_curves.png", facecolor="white"); plt.close(fig)
    return FIG / "report_curves.png"


def footer(c, doc):
    c.saveState(); c.setFont("DV", 7.5); c.setFillColor(MUTED)
    c.drawString(2 * cm, 1.2 * cm, "NeuroShield · исследовательский прототип · все метрики получены на синтетических данных")
    c.drawRightString(A4[0] - 2 * cm, 1.2 * cm, str(doc.page)); c.restoreState()


def main():
    ev = json.loads((R / "evaluation_default.json").read_text())
    eco = json.loads((R / "economics.json").read_text())
    sens = json.loads((R / "sensitivity_summary.json").read_text())
    lat = json.loads((R / "api_latency.json").read_text())
    S, W = ev["systems"], ev["winner"]
    fair = json.loads((R / "fairness_young_recipient.json").read_text())
    adv = json.loads((R / "adversary_stress.json").read_text())
    lt = json.loads((R / "load_test.json").read_text())
    n_tests = sum(f.read_text().count("\n    def test_") for f in (ROOT / "backend/tests").glob("test_*.py"))
    rt = json.loads((R / "robust_training.json").read_text())
    cmp = ev["comparison_vs_rules"][W]["matched_test_recall"]
    pol = ev["policies"]["balanced"]
    seg = {g["segment"]: g for g in ev["segments_false_positive"]}
    d = ev["dataset"]
    ratio = round(cmp["baseline_fpr"] / cmp["model_fpr"])
    story = []
    story += [P("NeuroShield API", "h0"),
              P("Аналитический отчёт: объяснимая оценка риска мошенничества в переводах. Исследовательский прототип.", "sub"),
              P(                f"<b>Главный вывод.</b> На синтетическом тестовом периоде ({sp(d['split_sizes']['test'])} переводов, {d['positives']['test']} мошеннических) "
                f"гибридная модель NeuroShield достигла ROC-AUC {n3(S[W]['test']['roc_auc'])} и PR-AUC {n3(S[W]['test']['pr_auc'])} "
                f"против {n3(S['rules']['test']['roc_auc'])} и {n3(S['rules']['test']['pr_auc'])} у набора из 8 правил. При равном recall "
                f"({pct(cmp['baseline_recall'])}) доля ложных тревог снизилась с {pct(cmp['baseline_fpr'], 2)} до {pct(cmp['model_fpr'], 2)} "
                f"(в {ratio} раз). Данные синтетические, поэтому результат подтверждает работоспособность подхода, но не эффективность в реальном банке. "
                "В экономической модели закладывается гораздо меньший эффект.", "box")]
    story += [P("1. Задача и решение", "h1"),
              P("Правила плохо видят переводы, которые клиент делает сам под давлением мошенника, и сети мулов, а их ужесточение бьёт по честным клиентам. "
                "NeuroShield объединяет четыре независимых сигнала: транзакционные признаки, отклонение поведения клиента от его <i>собственной</i> нормы "
                "(только с согласия), структуру графа переводов и признаки социальной инженерии (минимум два независимых сигнала). "
                "Обученная модель (градиентный бустинг) совмещает их в оценку риска 0–100. Политика выбирает действие: LOW — разрешить, MEDIUM — "
                "дополнительное подтверждение, HIGH — проверка аналитиком (временное удержание — только по политике банка). Каждое решение объясняется "
                "групповой окклюзией и записывается в журнал аудита."),
              P(f"Реализация: REST API (Starlette + Pydantic), SQLite, NetworkX, React-дашборд из 8 страниц на том же порту; {n_tests} автотестов и сквозная "
                "UI-проверка в браузере. Признаки считаются одним и тем же кодом при обучении и в API; паритет проверяет тест.")]
    story += [P("2. Данные и методика", "h1"),
              P(f"Синтетический генератор (seed 42) моделирует 90 дней банка: 3 000 клиентов, ~145 тыс. операций; мошенничество трёх типов "
                "(захват аккаунта, социальная инженерия, пересылка через мулов) и девять типов «необычного, но честного» поведения. Все признаки "
                "считаются только из прошлого; жалобы на мошенничество приходят с задержкой 6–120 ч. Разбиение по времени: разогрев (дни 0–6), "
                f"train ({sp(d['split_sizes']['train'])}), validation ({sp(d['split_sizes']['validation'])}), test ({sp(d['split_sizes']['test'])}); доля мошенничества "
                f"в тесте — {pct(d['fraud_rate_test'], 2)}. Модель и все пороги выбраны на validation; test использован один раз. Сравнивались 6 систем. "
                "В ходе работы найден и исправлен баг генератора (окно «24 часа» считало 24 дня); все результаты получены после исправления.")]
    names = [("rules", "A. Правила (8 правил)"), ("lr_txn", "B1. Лог. регрессия, транзакции"), ("hgb_txn", "B2. Бустинг, транзакции"),
             ("lr_hybrid", "C1. Лог. регрессия, гибрид"), ("rf_hybrid", "C2. Случайный лес, гибрид"), ("hgb_hybrid", "C3. Бустинг, гибрид (NeuroShield)")]
    rows = [["Система", "ROC-AUC [95% CI]", "PR-AUC [95% CI]", "FPR при recall правил"]]
    for k, lab in names:
        t = S[k]["test"]
        f = pct(ev["baseline"]["test"]["fpr"], 2) if k == "rules" else pct(ev["comparison_vs_rules"][k]["matched_test_recall"]["model_fpr"], 2)
        rows.append([lab, f"{n3(t['roc_auc'])} [{n3(t['roc_auc_95ci'][0])}–{n3(t['roc_auc_95ci'][1])}]", f"{n3(t['pr_auc'])} [{n3(t['pr_auc_95ci'][0])}–{n3(t['pr_auc_95ci'][1])}]", f])
    story += [P("3. Результаты", "h1"), table(rows, [6.0 * cm, 3.7 * cm, 3.7 * cm, 3.6 * cm], bold_last=True), Spacer(1, 6),
              Image(str(curves_figure(ev)), width=17 * cm, height=6.8 * cm),
              P(f"Политика balanced на тесте: уровень MEDIUM и выше ловит {pct(pol['test_at_medium']['recall'])} мошенничества при FPR "
                f"{pct(pol['test_at_medium']['fpr'], 2)}; уровень HIGH имеет precision {pct(pol['test_at_high']['precision'])} при FPR {pct(pol['test_at_high']['fpr'], 2)}. "
                f"Калибровка: ECE {n3(ev['calibration']['ece'], 4)}. Задержка p95: модель {ev['latency']['featurize_plus_model']['p95_ms']:.1f} мс, "
                f"полный HTTP-запрос с объяснением {lat['no_persist']['p95_ms']:.1f} мс (2 vCPU Xeon, последовательные запросы). "
                f"Deletion-тест объяснений: удаление главного фактора снижает log-odds на {ev['explanation_faithfulness']['mean_logodds_drop_top_group']:.2f} "
                f"против {ev['explanation_faithfulness']['mean_logodds_drop_random_group']:.2f} для случайного фактора.")]
    ab = ev["ablations"]
    story += [KeepTogether([P("4. Вклад компонентов и устойчивость", "h1"),
              table([["Модель", "PR-AUC (test)"], ["Полная гибридная", n3(S[W]["test"]["pr_auc"])],
                     ["без поведенческого компонента", n3(ab["without_behavioral"]["test_pr_auc"])], ["без графового", n3(ab["without_graph"]["test_pr_auc"])],
                     ["без социальной инженерии", n3(ab["without_social_engineering"]["test_pr_auc"])], ["без репутации получателя", n3(ab["without_recipient_reputation"]["test_pr_auc"])]],
                    [8 * cm, 4 * cm]), Spacer(1, 6),
              table([["Прогон", "Правила PR-AUC", "Бустинг (транз.)", "Гибрид PR-AUC", "Снижение FPR"]] +
                    [[r["run"], n3(r["rules_pr_auc"]), n3(r["hgb_txn_pr_auc"]), n3(r["hgb_hybrid_pr_auc"]), f"{r['relative_fpr_reduction_pct']:.1f}%"] for r in sens],
                    [5 * cm, 3 * cm, 3 * cm, 3 * cm, 3 * cm]),
              P("hard — слабее стресс-сигнал и жертвы реже признаются; scarce — согласие на телеметрию у 30% клиентов; low_reports — в жалобы попадает 30% "
                "мошенничества плюс 200 ложных жалоб. Гибрид во всех прогонах выше модели только на транзакциях.", "small")])]
    story += [KeepTogether([P("5. Честные клиенты с необычным поведением", "h1"),
              table([["Сегмент (test)", "NeuroShield: step-up и выше", "Правила: флаг"]] +
                    [[k.replace("_", " "), pct(seg[k]["stepup_or_higher_rate"]), pct(seg[k]["rule_baseline_flag_rate"])]
                     for k in ("big_purchase", "travel", "new_device", "new_recipient", "habit_change", "no_telemetry", "young_recipient")],
                    [6 * cm, 5 * cm, 5 * cm]),
              P("<b>Слабое место и что сделано.</b> Честные переводы на счета младше 30 дней эскалировались заметно чаще, чем у правил. Разбор показал две "
                "ошибки измерения, обе исправлены: (1) путь по экрану при добавлении нового получателя сравнивался с нормой клиента для переводов знакомым — "
                "теперь только с той же экранной сценой и минимум 3 сессиями, иначе метрика помечается как несопоставимая; (2) «пересылкой» считались и траты "
                "в магазинах — теперь только переводы на счета в диапазоне 60–130% полученного. Вариант модели без возраста получателя отклонён правилом, "
                "заданным заранее на validation. Оставшийся разрыв — выбор политики:")]),
              table([["Политика (test, счета < 30 дней)", "Ложные тревоги", "Recall по ним", "Recall в целом"],
                     ["Правила (baseline)", pct(fair["rules_young_false_alarm_test"]), "—", pct(ev["baseline"]["test"]["recall"])],
                     ["NeuroShield, общий порог (по умолчанию)", pct(fair["deployed_model_threshold_tradeoff"]["rows"][0]["test"]["young_false_alarm_rate"]),
                      pct(fair["deployed_model_threshold_tradeoff"]["rows"][0]["test"]["young_fraud_recall"]), pct(fair["deployed_model_threshold_tradeoff"]["rows"][0]["test"]["overall_recall"])]] +
                    [["NeuroShield, отдельный порог (опция)", pct(r["test"]["young_false_alarm_rate"]), pct(r["test"]["young_fraud_recall"]), pct(r["test"]["overall_recall"])]
                     for r in fair["deployed_model_threshold_tradeoff"]["rows"] if r["target"] == fair["deployed_model_threshold_tradeoff"]["chosen_by_validation_rule"]],
                    [7 * cm, 3.3 * cm, 3.3 * cm, 3.4 * cm]),
              P("Отдельный порог выбран на validation (минимум ложных тревог при потере не более 2 п.п. recall), но на тесте стоит 3 пропущенных "
                "мошенничества из 48. Поэтому он доступен в настройках и выключен по умолчанию: это решение банка.", "small")]
    ev_rows = [["Что скрывает мошенник", "NeuroShield recall", "Правила recall"], ["ничего", pct(adv["baseline"]["recall_stepup_plus"]), pct(adv["baseline"]["rules_recall"])]]
    ev_rows += [[adv["labels"][k], pct(v["recall_stepup_plus"]), pct(v["rules_recall"])] for k, v in adv["evasions"].items()]
    best1 = max((r for r in lt["runs"] if r["workers"] == 1), key=lambda r: r["throughput_rps"])
    best2 = max((r for r in lt["runs"] if r["workers"] == 2), key=lambda r: r["throughput_rps"])
    one = next(r for r in lt["runs"] if r["workers"] == 1 and r["concurrency"] == 1)
    story += [KeepTogether([P("6. Стресс-тесты: хитрый мошенник и нагрузка", "h1"),
              P(f"На {adv['n_frauds']} мошенничествах тестового периода мошенник по очереди скрывает один сигнал; признаки пересчитываются, честный трафик "
                "не меняется. Это проверка «что если» на синтетике, а не измеренная атака."),
              table(ev_rows, [8 * cm, 4 * cm, 4 * cm]),
              P("Ни один отдельный сигнал не является единой точкой отказа. Скрыть всё сразу — значит сделать перевод неотличимым от честного; "
                "такой перевод не увидит ни одна система.", "small")]),
              P(f"<b>Нагрузка</b> (POST /risk/score с объяснением, клиент и сервер на одной машине, 2 vCPU): один процесс — до {best1['throughput_rps']:.0f} "
                f"запросов/с, p95 {one['p95_ms']:.1f} мс при одном клиенте; два процесса — до {best2['throughput_rps']:.0f} запросов/с, ошибок нет. "
                "Найдена и устранена транспортная задержка ~44 мс в режиме двух процессов (uvicorn создавал сокет без TCP_NODELAY); "
                "на неё есть регрессионный тест."),
              P(f"<b>Обучение на примерах обхода</b> (не принято): копии обучающих мошенничеств со скрытым сигналом подняли recall на знакомых "
                f"приёмах ({pct(rt['models']['standard']['test']['mean_seen'])} → {pct(rt['models']['evasion_augmented']['test']['mean_seen'])} на тесте), "
                f"но не на незнакомых ({pct(rt['models']['standard']['test']['mean_unseen'])} → {pct(rt['models']['evasion_augmented']['test']['mean_unseen'])}). "
                "Правило, заданное заранее на validation, отклонило модель: устойчивость нужно строить новыми независимыми сигналами.")]
    SC = eco["scenarios"]
    rows = [["Показатель, в год", "Консервативный", "Базовый", "Оптимистичный"]]
    for lab, k in (("Переводов", "transfers_per_year"), ("Доля мошенничества", "fraud_rate"), ("Снижение FPR моделью (допущение)", "fpr_reduction")):
        rows.append([lab] + [(f"{SC[s]['assumptions'][k] / 1e6:.0f} млн" if k == "transfers_per_year" else pct(SC[s]["assumptions"][k], 2 if k == "fraud_rate" else 0)) for s in ("conservative", "base", "optimistic")])
    for lab, k in (("Предотвращённые потери", "avoided_fraud_losses"), ("Экономия на ложных тревогах", "reduced_fp_handling_costs"), ("Затраты", "total_costs"), ("Чистый эффект", "net_benefit")):
        rows.append([lab] + [f"{SC[s]['result'][k] / 1e9:.2f} млрд ₸".replace(".", ",") for s in ("conservative", "base", "optimistic")])
    be = eco["break_even"]
    story += [P("7. Экономическая модель", "h1"),
              P("Net Benefit = предотвращённые потери + экономия на обработке ложных срабатываний − инфраструктура − интеграция (амортизация 3 года) − эксплуатация. "
                "Параметры банка — сценарные допущения. Правилам дана та же многоуровневая обработка, что и модели, поэтому засчитывается только вклад самой модели. "
                "Синтетическое снижение FPR (98,5%) в расчёт не переносится: используются 20–60%."),
              table(rows, [6.2 * cm, 3.6 * cm, 3.6 * cm, 3.6 * cm], bold_last=True), Spacer(1, 6),
              Image(str(FIG / "econ_scenarios.png"), width=15 * cm, height=7.9 * cm),
              P(f"Безубыточность базового сценария: снижение FPR правил не менее {pct(be['fpr_reduction_needed_base_without_recall_gain'])} без прироста recall, "
                f"или прирост recall на {be['recall_uplift_needed_base_without_fpr_gain'] * 100:.1f} п.п. без снижения FPR, или объём не менее "
                f"{be['transfers_needed_base'] / 1e6:.0f} млн переводов в год. Консервативный сценарий (20 млн переводов) не окупается: фиксированные затраты "
                "превышают выгоду. Модельные метрики сами по себе не доказывают экономию.")]
    story += [P("8. Безопасность, приватность, ограничения", "h1"),
              P("Строгая валидация входа, лимиты размера запроса и пакета, rate limit, CSP и заголовки безопасности, журнал аудита решений и изменений политики. "
                "Персональные данные не используются: только псевдонимные ID (не признаки модели) и агрегаты поведения. Поведенческий компонент работает "
                "только при согласии, отсутствие данных не заменяется догадками, а одно лишь необычное поведение не может привести к уровню HIGH. "
                "Прототип не сертифицирован (PCI DSS, ISO 27001) и не подключён ни к одному банку."),
              P("<b>Ограничения:</b> синтетические данные, отражающие допущения генератора; компромисс по счетам младше 30 дней; "
                "устойчивость к новым приёмам обхода; проверка на реальных данных банка; отчёт аналитика шаблонный (без LLM).")]
    story += [P("9. Дорожная карта", "h1"),
              table([["Этап", "Цель", "Критерий перехода"],
                     ["1. Данные и baseline", "повтор методики на истории банка против его реальных правил", "PR-AUC выше правил с непересекающимися CI; FPR −30% при равном recall"],
                     ["2. Shadow mode", "оценка живого трафика без влияния на платежи (3 мес.)", "p99 < 100 мс; стабильные метрики; FPR по сегментам не хуже правил"],
                     ["3. Пилот", "step-up для части клиентов, A/B против контроля", "значимое снижение потерь или нагрузки; жалобы не выше контроля"],
                     ["4. Rollout", "расширение после security review и согласования с регулятором", "SLO выполнены 2 месяца подряд"],
                     ["5. Мониторинг", "дрейф признаков и схем мошенничества", "переобучение при падении PR-AUC > 10% или PSI > 0,2"]],
                    [3.6 * cm, 6.7 * cm, 6.7 * cm]),
              Spacer(1, 8),
              P("Воспроизведение: <font face='DV'>scripts/bootstrap.sh --full</font> пересоздаёт данные, обучает модели, считает все метрики и этот отчёт "
                "(<font face='DV'>python -m scripts.economics</font>, <font face='DV'>python -m scripts.make_report_pdf</font>). Исходные результаты — в "
                "experiments/results/*.json.", "small")]
    out = ROOT / "docs/NeuroShield_report.pdf"
    doc = SimpleDocTemplate(str(out), pagesize=A4, leftMargin=2 * cm, rightMargin=2 * cm, topMargin=1.8 * cm, bottomMargin=1.8 * cm,
                            title="NeuroShield — аналитический отчёт", author="NeuroShield team")
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print("wrote", out)


if __name__ == "__main__":
    main()
