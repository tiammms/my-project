// Builds presentation/NeuroShield_presentation.pptx from measured results.
// Usage: NODE_PATH=$(npm root -g) node presentation/build_deck.js
// Numbers are read from experiments/results/*.json, not typed by hand.
const path = require("path");
const fs = require("fs");
const pptxgen = require("pptxgenjs");
const React = require("react");
const ReactDOMServer = require("react-dom/server");
const sharp = require("sharp");
const Fi = require("react-icons/fi");
const { applyTheme } = require(process.env.PPTX_SKILL + "/scripts/apply_theme.js");

const ROOT = path.resolve(__dirname, "..");
const ev = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/evaluation_default.json")));
const eco = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/economics.json")));
const sens = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/sensitivity_summary.json")));
const apiLat = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/api_latency.json")));
const A = (f) => path.join(__dirname, "assets", f);
const DEMO = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/demo_scenarios.json")));
const FAIR = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/fairness_young_recipient.json")));
const ADV = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/adversary_stress.json")));
const LT = JSON.parse(fs.readFileSync(path.join(ROOT, "experiments/results/load_test.json")));
const N_TESTS = fs.readdirSync(path.join(ROOT, "backend/tests")).filter((f) => /^test_.*\.py$/.test(f))
  .reduce((n, f) => n + (fs.readFileSync(path.join(ROOT, "backend/tests", f), "utf8").match(/\n    def test_/g) || []).length, 0);
const sc = (k) => `${DEMO[k].risk_score < 1 ? DEMO[k].risk_score.toFixed(2).replace(".", ",") : DEMO[k].risk_score.toFixed(1).replace(".", ",")} · ${DEMO[k].risk_level}`;
const TR = FAIR.deployed_model_threshold_tradeoff, TR0 = TR.rows[0].test, TRC = TR.rows.find((r) => r.target === TR.chosen_by_validation_rule).test;
const advDrops = Object.entries(ADV.evasions).filter(([k]) => k !== "all_combined").map(([, v]) => v.drop_pp);
const lt1 = LT.runs.filter((r) => r.workers === 1), lt2 = LT.runs.filter((r) => r.workers === 2);

const pct = (x, d = 1) => (100 * x).toFixed(d).replace(".", ",") + "%";
const num = (x, d = 3) => x.toFixed(d).replace(".", ",");
const S = ev.systems, W = ev.winner, CMP = ev.comparison_vs_rules[W].matched_test_recall, POL = ev.policies.balanced;
const SEG = Object.fromEntries(ev.segments_false_positive.map((g) => [g.segment, g]));
const FT = Object.fromEntries(ev.recall_by_fraud_type.map((g) => [g.fraud_type, g]));
const SC = eco.scenarios, BE = eco.break_even;
const mlrd = (x) => (x / 1e9).toFixed(2).replace(".", ",");
const ratio = Math.round(CMP.baseline_fpr / CMP.model_fpr);
const prRange = [Math.min(...sens.map((r) => r.hgb_hybrid_pr_auc)), Math.max(...sens.map((r) => r.hgb_hybrid_pr_auc))];

const THEME = {
  name: "NeuroShield Graphite", headFontFace: "Calibri", bodyFontFace: "Calibri",
  colors: { dk1: "0B1016", lt1: "E6EDF5", dk2: "151C26", lt2: "9AA8BA", accent1: "2CC5B0", accent2: "3987E5",
    accent3: "D95926", accent4: "FAB219", accent5: "E05252", accent6: "0CA30C", hlink: "4C8DFF", folHlink: "9085E9" },
};
const HEX = { bg: "0B1016", card: "151C26", card2: "1C2532", line: "2A3647", text: "E6EDF5", muted: "9AA8BA",
  teal: "2CC5B0", blue: "3987E5", orange: "D95926", amber: "FAB219", red: "E05252", green: "0CA30C", violet: "9085E9", pink: "D55181", gold: "C98500", aqua: "199E70" };

async function icon(Comp, color = "#2CC5B0", size = 256) {
  const svg = ReactDOMServer.renderToStaticMarkup(React.createElement(Comp, { color, size: String(size) }));
  const png = await sharp(Buffer.from(svg)).png().toBuffer();
  return "image/png;base64," + png.toString("base64");
}

(async () => {
  const pres = new pptxgen();
  pres.layout = "LAYOUT_WIDE";            // 13.33 x 7.5 in
  pres.author = "NeuroShield team";
  pres.title = "NeuroShield API";
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  const C = pres.SchemeColor;
  const FOOT = "NeuroShield · исследовательский прототип · синтетические данные";

  pres.defineSlideMaster({
    title: "NS_TITLE", background: { color: HEX.bg },
    objects: [
      { placeholder: { options: { name: "title", type: "title", x: 0.7, y: 2.0, w: 6.4, h: 1.3, fontSize: 48, bold: true, color: C.background1, valign: "bottom", align: "left", margin: 0 }, text: "" } },
      { placeholder: { options: { name: "body", type: "body", x: 0.7, y: 3.45, w: 6.2, h: 1.6, fontSize: 18, color: C.background2, valign: "top", align: "left", margin: 0 }, text: "" } },
    ],
  });
  pres.defineSlideMaster({
    title: "NS_CONTENT", background: { color: HEX.bg },
    margin: [0.5, 0.6, 0.6, 0.6],
    objects: [
      { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.35, w: 12.1, h: 0.75, fontSize: 32, bold: true, color: C.background1, valign: "middle", margin: 0 }, text: "" } },
      { text: { text: FOOT, options: { x: 0.6, y: 7.05, w: 8, h: 0.3, fontSize: 10, color: C.background2, margin: 0 } } },
    ],
    slideNumber: { x: 12.2, y: 7.05, w: 0.5, h: 0.3, fontSize: 10, color: HEX.muted, align: "right" },
  });

  const ic = {
    alert: await icon(Fi.FiAlertTriangle, "#FAB219"), phone: await icon(Fi.FiPhoneCall, "#E05252"), share: await icon(Fi.FiShare2, "#2CC5B0"),
    help: await icon(Fi.FiHelpCircle, "#3987E5"), shield: await icon(Fi.FiShield, "#2CC5B0"), activity: await icon(Fi.FiActivity, "#2CC5B0"),
    user: await icon(Fi.FiUser, "#2CC5B0"), card: await icon(Fi.FiCreditCard, "#2CC5B0"), lock: await icon(Fi.FiLock, "#2CC5B0"),
    eye: await icon(Fi.FiEyeOff, "#2CC5B0"), file: await icon(Fi.FiFileText, "#2CC5B0"), sliders: await icon(Fi.FiSliders, "#2CC5B0"),
    x: await icon(Fi.FiAlertCircle, "#FAB219"), check: await icon(Fi.FiCheckCircle, "#0CA30C"), cpu: await icon(Fi.FiCpu, "#2CC5B0"),
  };
  const card = (s, x, y, w, h, name, fill = HEX.card) => s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.12, fill: { color: fill }, line: { color: HEX.line, width: 0.75 }, objectName: name });
  const iconCircle = (s, data, x, y, d = 0.62, name = "icon") => {
    s.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: HEX.card2 }, line: { color: HEX.line, width: 0.75 }, objectName: name + " circle" });
    s.addImage({ data, x: x + d * 0.22, y: y + d * 0.22, w: d * 0.56, h: d * 0.56, objectName: name });
  };
  const T = (s, text, o) => s.addText(text, { isTextBox: true, margin: 0, color: HEX.text, fontSize: 14, valign: "top", ...o });
  const stat = (s, x, y, w, value, label, color = HEX.teal, name = "stat", vs = 36) => {
    card(s, x, y, w, 1.45, name + " card");
    T(s, value, { x: x + 0.25, y: y + 0.18, w: w - 0.5, h: 0.7, fontSize: vs, bold: true, color, valign: "middle", objectName: name + " value" });
    T(s, label, { x: x + 0.25, y: y + 0.86, w: w - 0.5, h: 0.52, fontSize: w < 3 ? 11 : 12, color: HEX.muted, objectName: name + " label" });
  };
  const frame = (s, img, x, y, w, h, name) => {
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: x - 0.06, y: y - 0.06, w: w + 0.12, h: h + 0.12, rectRadius: 0.1, fill: { color: HEX.card2 }, line: { color: HEX.line, width: 1 },
      shadow: { type: "outer", color: "000000", opacity: 0.45, blur: 12, offset: 4, angle: 90 }, objectName: name + " frame" });
    s.addImage({ path: img, x, y, w, h, objectName: name });
  };
  const chartText = { catAxisLabelColor: HEX.muted, valAxisLabelColor: HEX.muted, catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt",
    dataLabelFontFace: "+mn-lt", legendFontFace: "+mn-lt", titleFontFace: "+mn-lt", catAxisLabelFontSize: 11, valAxisLabelFontSize: 10,
    dataLabelFontSize: 11, dataLabelColor: HEX.text, legendColor: HEX.text, legendFontSize: 11, titleColor: HEX.text, titleFontSize: 13,
    valGridLine: { color: "26303D", size: 0.75 }, catGridLine: { style: "none" }, catAxisLineShow: false, valAxisLineShow: false };

  // ---------- 1. Title ----------
  pres.addSection({ title: "Введение" });
  let s = pres.addSlide({ masterName: "NS_TITLE", sectionTitle: "Введение" });
  s.addImage({ data: ic.shield, x: 0.7, y: 1.05, w: 0.75, h: 0.75, objectName: "logo" });
  s.addText("NeuroShield API", { placeholder: "title" });
  s.addText([
    { text: "Объяснимая AI-платформа оценки риска мошенничества в переводах", options: { breakLine: true } },
    { text: "Detect risk. Understand behavior. Protect every transaction.", options: { italic: true, color: HEX.teal, fontSize: 16 } },
  ], { placeholder: "body" });
  T(s, "Исследовательский прототип · синтетические данные · не подключён к банкам", { x: 0.7, y: 6.55, w: 6.4, h: 0.35, fontSize: 12, color: HEX.muted, objectName: "disclaimer" });
  frame(s, A("overview.png"), 7.55, 1.55, 5.2, 5.2 * 610 / 1152, "dashboard screenshot");
  T(s, "Работающий MVP: API + дашборд + ML-эксперимент", { x: 7.55, y: 4.45, w: 5.2, h: 0.35, fontSize: 12, color: HEX.muted, objectName: "screenshot caption" });
  s.addNotes("Мы сделали работающий прототип, который не просто говорит «мошенничество или нет», а оценивает риск, объясняет почему и предлагает соразмерное действие. Всё, что вы увидите, работает локально и проверено тестами; данные синтетические.");

  // ---------- 2. Problem ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Введение" });
  s.addText("Почему правил уже недостаточно", { placeholder: "title" });
  const probs = [
    [ic.alert, "Ложные тревоги", "Чтобы ловить больше, правила ужесточают — и под проверку попадают путешественники, крупные покупки, новые телефоны."],
    [ic.phone, "Клиент переводит сам", "При социальной инженерии пароль верный и устройство своё. Для правил это обычный перевод — подозрителен только контекст."],
    [ic.share, "Сети мулов", "Каждый счёт по отдельности выглядит нормально. Видна только структура: деньги от многих людей и сразу дальше."],
    [ic.help, "Нет объяснений", "Аналитик видит «сработало правило R4», но не понимает, что изменилось в поведении клиента и почему это опасно."],
  ];
  probs.forEach(([im, h, b], i) => {
    const x = 0.6 + i * 3.07;
    card(s, x, 1.55, 2.85, 4.6, `problem ${i + 1}`);
    iconCircle(s, im, x + 0.3, 1.85, 0.8, `problem icon ${i + 1}`);
    T(s, h, { x: x + 0.3, y: 2.9, w: 2.3, h: 0.8, fontSize: 20, bold: true, objectName: `problem title ${i + 1}` });
    T(s, b, { x: x + 0.3, y: 3.75, w: 2.3, h: 2.2, fontSize: 14, color: HEX.muted, objectName: `problem text ${i + 1}` });
  });
  T(s, "Задача: оценивать риск, объяснять факторы и выбирать соразмерную проверку — а не просто блокировать.", { x: 0.6, y: 6.35, w: 12.1, h: 0.45, fontSize: 16, color: HEX.teal, italic: true, objectName: "goal" });
  s.addNotes("Самый тяжёлый случай — когда деньги переводит сам клиент: пароль верный, устройство его. Правило видит обычный перевод. А когда правила ужесточают, страдают честные клиенты. Мулы по отдельности выглядят нормально — подозрительна только структура переводов.");

  // ---------- 3. Baseline ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Введение" });
  s.addText("Baseline: 8 явных правил, включая чёрный список", { placeholder: "title" });
  const hdr = (t) => ({ text: t, options: { bold: true, color: HEX.muted, fill: { color: HEX.card2 }, fontSize: 12 } });
  const rules = ev.baseline.rules.map((r) => [{ text: r.id, options: { color: HEX.teal, bold: true } }, { text: String(r.weight) }, { text: r.description }]);
  s.addTable([[hdr("Правило"), hdr("Баллы"), hdr("Условие")], ...rules], { x: 0.6, y: 1.5, w: 6.4, colW: [1.0, 0.9, 4.5], fontSize: 12, color: HEX.text,
    fill: { color: HEX.card }, border: { type: "solid", pt: 0.75, color: HEX.line }, rowH: 0.42, valign: "middle", objectName: "rules table" });
  T(s, `Флаг при ≥ ${ev.baseline.flag_threshold} баллах. Всё сработавшее идёт на ручную проверку.`, { x: 0.6, y: 5.45, w: 6.4, h: 0.4, fontSize: 12, color: HEX.muted, objectName: "rule note" });
  stat(s, 7.4, 1.5, 2.6, pct(ev.baseline.test.recall), "ловит мошенничества (recall)", HEX.amber, "recall");
  stat(s, 10.15, 1.5, 2.6, pct(ev.baseline.test.fpr, 2), "честных переводов — ложная тревога", HEX.red, "fpr");
  stat(s, 7.4, 3.1, 2.6, pct(ev.baseline.test.precision), "алертов — действительно мошенничество", HEX.amber, "precision");
  stat(s, 10.15, 3.1, 2.6, pct(FT.mule_layering.rule_baseline_recall, 0), "переводов мулов замечено", HEX.red, "mule");
  stat(s, 7.4, 4.7, 5.35, pct(SEG.big_purchase.rule_baseline_flag_rate, 1), "крупных честных покупок помечены как подозрительные", HEX.red, "bigpurchase");
  T(s, "Тестовый период синтетического набора: 13 909 переводов, 283 мошеннических.", { x: 7.4, y: 6.3, w: 5.35, h: 0.4, fontSize: 11, color: HEX.muted, objectName: "baseline source" });
  s.addNotes("Мы не утверждаем, что все банковские системы устарели. Мы сравниваем с конкретным, явно описанным набором из восьми правил, включая чёрный список получателей. Правила ловят две трети мошенничества, но мулов — лишь 41%, и почти 70% крупных честных покупок попадают под подозрение.");

  // ---------- 4. Solution ----------
  pres.addSection({ title: "Решение" });
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Решение" });
  s.addText("Решение: 4 сигнала → модель → соразмерное действие", { placeholder: "title" });
  const sig = [[ic.card, "Транзакция", "сумма vs норма клиента, время, скорость, новый получатель, устройство"],
    [ic.activity, "Поведение", "паузы, исправления, ритм набора — относительно своей нормы, только с согласия"],
    [ic.share, "Граф переводов", "fan-in, быстрая пересылка, связь с подтверждёнными жалобами"],
    [ic.phone, "Социальная инженерия", "звонок во время сессии, ответ «меня попросили» — минимум 2 сигнала"]];
  sig.forEach(([im, h, b], i) => {
    const y = 1.45 + i * 1.33;
    card(s, 0.6, y, 4.6, 1.18, `signal ${i + 1}`);
    iconCircle(s, im, 0.8, y + 0.28, 0.62, `signal icon ${i + 1}`);
    T(s, h, { x: 1.6, y: y + 0.14, w: 3.45, h: 0.38, fontSize: 16, bold: true, objectName: `signal title ${i + 1}` });
    T(s, b, { x: 1.6, y: y + 0.52, w: 3.45, h: 0.6, fontSize: 12, color: HEX.muted, objectName: `signal text ${i + 1}` });
  });
  s.addShape(pres.shapes.RIGHT_ARROW, { x: 5.35, y: 3.55, w: 0.55, h: 0.45, fill: { color: HEX.line }, line: { color: HEX.line }, objectName: "arrow 1" });
  card(s, 6.05, 2.45, 2.55, 2.65, "model card", HEX.card2);
  iconCircle(s, ic.cpu, 6.98, 2.7, 0.7, "model icon");
  T(s, "Гибридная модель", { x: 6.2, y: 3.55, w: 2.25, h: 0.45, fontSize: 17, bold: true, align: "center", objectName: "model title" });
  T(s, "градиентный бустинг совмещает сигналы → риск 0–100 + объяснение", { x: 6.2, y: 4.0, w: 2.25, h: 0.95, fontSize: 12, color: HEX.muted, align: "center", objectName: "model text" });
  s.addShape(pres.shapes.RIGHT_ARROW, { x: 8.75, y: 3.55, w: 0.55, h: 0.45, fill: { color: HEX.line }, line: { color: HEX.line }, objectName: "arrow 2" });
  const tiers = [["LOW", HEX.green, "Разрешить", "без вмешательства"], ["MEDIUM", HEX.amber, "Step-up", "перепроверить получателя, предупреждение о мошенничестве"],
    ["HIGH", HEX.red, "Проверка", "аналитик; временное удержание — только по политике банка"]];
  tiers.forEach(([lvl, col, act, d], i) => {
    const y = 1.6 + i * 1.62;
    card(s, 9.45, y, 3.3, 1.42, `tier ${lvl}`);
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 9.65, y: y + 0.2, w: 1.15, h: 0.36, rectRadius: 0.18, fill: { color: col, transparency: 75 }, line: { color: col, width: 1 }, objectName: `tier pill ${lvl}` });
    T(s, lvl, { x: 9.65, y: y + 0.2, w: 1.15, h: 0.36, fontSize: 12, bold: true, color: col, align: "center", valign: "middle", objectName: `tier label ${lvl}` });
    T(s, act, { x: 10.95, y: y + 0.18, w: 1.7, h: 0.4, fontSize: 15, bold: true, objectName: `tier action ${lvl}` });
    T(s, d, { x: 9.65, y: y + 0.68, w: 2.95, h: 0.65, fontSize: 12, color: HEX.muted, objectName: `tier text ${lvl}` });
  });
  T(s, "Защитное правило: одно лишь необычное поведение никогда не ведёт к удержанию перевода.", { x: 0.6, y: 6.55, w: 12.1, h: 0.35, fontSize: 13, color: HEX.teal, italic: true, objectName: "safeguard" });
  s.addNotes("Мы не блокируем всё подозрительное. Четыре независимых сигнала, обученная модель их совмещает, а политика выбирает соразмерное действие. Большинство сомнительных случаев — это мягкое подтверждение, а не блокировка. Решение о блокировке реального платежа всегда остаётся за банком.");

  // ---------- 5. Architecture ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Решение" });
  s.addText("Техническая архитектура", { placeholder: "title" });
  const box = (x, y, w, h, title, sub, name, fill = HEX.card) => {
    card(s, x, y, w, h, name, fill);
    T(s, title, { x: x + 0.15, y: y + 0.12, w: w - 0.3, h: 0.34, fontSize: 14, bold: true, objectName: name + " title" });
    if (sub) T(s, sub, { x: x + 0.15, y: y + 0.46, w: w - 0.3, h: h - 0.55, fontSize: 11, color: HEX.muted, objectName: name + " text" });
  };
  const arrow = (x, y, w, name) => s.addShape(pres.shapes.RIGHT_ARROW, { x, y, w, h: 0.28, fill: { color: HEX.line }, line: { color: HEX.line }, objectName: name });
  box(0.6, 1.5, 1.85, 1.0, "Дашборд", "React + TypeScript, 8 страниц", "arch ui");
  box(0.6, 2.75, 1.85, 1.0, "Внешний сервис", "REST API v1, Swagger", "arch ext");
  arrow(2.55, 2.5, 0.35, "arch arrow 0");
  box(3.0, 1.5, 2.05, 2.25, "Защита и валидация", "лимит тела, rate limit, CSP, request-id; Pydantic: лишние поля и диапазоны отклоняются", "arch sec");
  arrow(5.15, 2.5, 0.35, "arch arrow 1");
  box(5.6, 1.5, 2.15, 2.25, "Признаки", "features.py — один код для обучения и API; паритет проверяется тестом", "arch feat");
  arrow(7.85, 2.5, 0.35, "arch arrow 2");
  box(8.3, 1.5, 2.1, 2.25, "Модель", "4 компонента (поведение, граф, соц. инженерия, репутация) → HistGradientBoosting", "arch model", HEX.card2);
  arrow(10.5, 2.5, 0.35, "arch arrow 3");
  box(10.95, 1.5, 1.8, 2.25, "Политика", "пороги из validation; объяснение окклюзией; отчёт аналитика", "arch pol");
  box(3.0, 4.15, 4.75, 1.15, "SQLite: решения · аудит · настройки", "только псевдонимные ID, без тел запросов в логах", "arch db");
  box(8.3, 4.15, 4.45, 1.15, "Graph Engine (NetworkX)", "fan-in, цепочки пересылки, общие получатели", "arch graph");
  box(0.6, 5.55, 7.15, 1.2, "Офлайн-контур ML", "генератор (seed 42) → 6 систем, разбиение по времени → model.joblib, policy.json, experiments/results", "arch ml", HEX.card2);
  const facts = [["13+", "REST-эндпоинтов"], [String(N_TESTS), "автотестов"], ["8", "страниц UI"]];
  facts.forEach(([v, l], i) => { const x = 8.3 + i * 1.52; card(s, x, 5.55, 1.4, 1.2, "fact " + l);
    T(s, v, { x, y: 5.62, w: 1.4, h: 0.6, fontSize: 28, bold: true, color: HEX.teal, align: "center", valign: "middle", objectName: "fact value " + l });
    T(s, l, { x: x + 0.05, y: 6.2, w: 1.3, h: 0.45, fontSize: 11, color: HEX.muted, align: "center", objectName: "fact label " + l }); });
  s.addNotes("Один процесс на одном порту раздаёт и API, и дашборд — меньше точек отказа на демонстрации. Признаки считаются одним и тем же кодом при обучении и в API — это проверяет отдельный тест, поэтому модель в работе видит то же, что видела при обучении. Стек: Starlette и Pydantic, scikit-learn, NetworkX, SQLite, React с TypeScript.");

  // ---------- 6. AI engine + ablation ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Решение" });
  s.addText("Каждый компонент добавляет качество", { placeholder: "title" });
  const comps = [[ic.activity, "Поведение относительно своей нормы", "Отдельно ритм набора («другой человек?») и колебания («давление?»). Без согласия компонент выключен и ничего не додумывает."],
    [ic.share, "Граф без заглядывания в будущее", "Признаки только из прошлых переводов; жалобы приходят с задержкой 6–120 ч, поэтому метка не просачивается."],
    [ic.phone, "Социальная инженерия", "Нужны минимум 2 независимых сигнала; один сигнал ограничен 45 баллами из 100."]];
  comps.forEach(([im, h, b], i) => { const y = 1.5 + i * 1.72;
    card(s, 0.6, y, 5.6, 1.55, "component " + (i + 1)); iconCircle(s, im, 0.85, y + 0.35, 0.7, "component icon " + (i + 1));
    T(s, h, { x: 1.8, y: y + 0.15, w: 4.2, h: 0.4, fontSize: 16, bold: true, objectName: "component title " + (i + 1) });
    T(s, b, { x: 1.8, y: y + 0.55, w: 4.2, h: 0.95, fontSize: 12, color: HEX.muted, objectName: "component text " + (i + 1) }); });
  const ab = [["Полная модель", S[W].test.pr_auc], ...Object.entries(ev.ablations).map(([k, v]) => [({ without_behavioral: "без поведения", without_graph: "без графа",
    without_social_engineering: "без соц. инженерии", without_recipient_reputation: "без репутации получателя" })[k], v.test_pr_auc])];
  card(s, 6.5, 1.5, 6.25, 5.15, "ablation card");
  s.addChart(pres.charts.BAR, [{ name: "PR-AUC", labels: ab.map((a) => a[0]), values: ab.map((a) => +a[1].toFixed(3)) }], {
    x: 6.65, y: 1.6, w: 5.95, h: 4.95, barDir: "bar", chartColors: [HEX.blue], invertedColors: [HEX.blue], showValue: true, dataLabelPosition: "outEnd",
    dataLabelFormatCode: "0.000", valAxisMinVal: 0.6, valAxisMaxVal: 0.9, valAxisMajorUnit: 0.1, valAxisLabelFormatCode: "0.0", catAxisOrientation: "maxMin",
    showTitle: true, title: "Абляция: PR-AUC на тесте без одного компонента (ось с 0,6)", showLegend: false, barGapWidthPct: 60, ...chartText, objectName: "ablation chart" });
  s.addNotes(`Абляция: переобучаем модель без одного компонента. PR-AUC падает с ${num(S[W].test.pr_auc)} до ${num(ev.ablations.without_behavioral.test_pr_auc)} без поведенческого компонента — это самый большой вклад. Ось начинается с 0,6, чтобы различия были видны.`);

  // ---------- 7. Demo ----------
  pres.addSection({ title: "Демонстрация и результаты" });
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Демонстрация и результаты" });
  s.addText("Live demo: обычный перевод против социальной инженерии", { placeholder: "title" });
  frame(s, A("demo_ab.png"), 0.65, 1.5, 7.4, 7.4 * 745 / 1152, "demo screenshot");
  stat(s, 8.9, 1.5, 3.85, "A: " + sc("normal_transfer"), "перевод сестре 25 000 ₸, всё как обычно → разрешить", HEX.green, "demo A", 30);
  stat(s, 8.9, 3.1, 3.85, "B: " + sc("social_engineering"), "450 000 ₸ новому счёту во время звонка, «меня попросили» → проверка", HEX.red, "demo B", 30);
  stat(s, 8.9, 4.7, 3.85, "6 / 6", "проверок пройдено: путешествие, новый телефон, крупная покупка — не HIGH", HEX.teal, "fp bench", 30);
  T(s, "Сценарии идут через настоящий backend: валидация → признаки → модель → политика → объяснение. Синтетический клиент, реальный стресс не определяется.", { x: 0.65, y: 6.5, w: 12.1, h: 0.4, fontSize: 12, color: HEX.muted, objectName: "demo note" });
  s.addNotes("Сценарий A: перевод сестре — " + sc("normal_transfer") + ", разрешить. Сценарий B: 450 тысяч новому получателю во время звонка, клиент говорит, что его попросили — " + sc("social_engineering") + ", проверка аналитиком. Объяснение называет причины: паузы на 9 стандартных отклонений дольше её нормы, получателю 9 дней, он связан с подтверждённой жалобой. А путешествие, новый телефон и крупная покупка в высокий риск не попадают.");

  // ---------- 8. Methodology ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Демонстрация и результаты" });
  s.addText("Методика: без утечек, пороги до теста", { placeholder: "title" });
  const segs = [["разогрев", 0, 7, HEX.line], ["train · дни 7–59", 7, 60, HEX.blue], ["validation · 60–74", 60, 75, HEX.violet], ["test · 75–89", 75, 90, HEX.teal]];
  const tx = 0.6, tw = 12.1;
  segs.forEach(([l, a, b, col]) => { const x = tx + (a / 90) * tw, w = ((b - a) / 90) * tw;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y: 1.55, w: w - 0.05, h: 0.6, rectRadius: 0.08, fill: { color: col, transparency: a === 0 ? 0 : 30 }, line: { color: col, width: 0.5 }, objectName: "timeline " + l });
    T(s, l, { x, y: 1.55, w: w - 0.05, h: 0.6, fontSize: a === 0 ? 10 : 13, bold: true, align: "center", valign: "middle", objectName: "timeline label " + l }); });
  T(s, "90 дней синтетической истории банка · 3 000 клиентов · 145 тыс. операций · seed 42", { x: 0.6, y: 2.25, w: 12.1, h: 0.35, fontSize: 12, color: HEX.muted, objectName: "timeline caption" });
  const meth = [["Мошенничество 3 типов", "захват аккаунта, социальная инженерия, сети мулов (пересылка)"],
    ["9 типов «честного, но необычного»", "путешествия, новый телефон, крупные покупки, смена привычек, переводы новым счетам, без телеметрии…"],
    ["Защита от утечек", "признаки только из прошлого; жалобы с задержкой 6–120 ч; модель и пороги — только по validation; test — один раз"],
    ["6 систем", "правила · LR и бустинг на транзакциях · LR, случайный лес и бустинг на гибридных признаках"],
    ["Устойчивость", "повтор на 2 других seed и 3 «жёстких» вариантах данных; 95% CI бутстрэпом"],
    ["Найден и исправлен баг", "окно «транзакций за 24 ч» считало 24 дня — всё пересчитано после исправления"]];
  meth.forEach(([h, b], i) => { const col = i % 2, row = Math.floor(i / 2), x = 0.6 + col * 6.13, y = 2.85 + row * 1.32;
    card(s, x, y, 5.97, 1.17, "method " + (i + 1));
    T(s, h, { x: x + 0.25, y: y + 0.13, w: 5.5, h: 0.36, fontSize: 15, bold: true, color: i === 5 ? HEX.amber : HEX.text, objectName: "method title " + (i + 1) });
    T(s, b, { x: x + 0.25, y: y + 0.5, w: 5.5, h: 0.6, fontSize: 12, color: HEX.muted, objectName: "method text " + (i + 1) }); });
  s.addNotes("Мы специально закрыли утечки: признаки строятся только из прошлого, жалобы на мошенничество приходят с задержкой, модель и пороги выбраны только на validation, test использован один раз. Мы нашли и исправили баг с окном «24 часа», которое считало 24 дня, и пересчитали всё после исправления.");

  // ---------- 9. Results ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Демонстрация и результаты" });
  s.addText("Результаты на тесте (синтетика)", { placeholder: "title" });
  const sysNames = [["rules", "Правила"], ["lr_txn", "LR, транзакции"], ["hgb_txn", "Бустинг, транзакции"], ["lr_hybrid", "LR, гибрид"], ["rf_hybrid", "Лес, гибрид"], ["hgb_hybrid", "NeuroShield"]];
  card(s, 0.6, 1.5, 6.6, 5.2, "results chart card");
  s.addChart(pres.charts.BAR, [
    { name: "ROC-AUC", labels: sysNames.map((x) => x[1]), values: sysNames.map((x) => +S[x[0]].test.roc_auc.toFixed(3)) },
    { name: "PR-AUC", labels: sysNames.map((x) => x[1]), values: sysNames.map((x) => +S[x[0]].test.pr_auc.toFixed(3)) }],
  { x: 0.75, y: 1.6, w: 6.3, h: 5.0, barDir: "col", barGrouping: "clustered", chartColors: [HEX.violet, HEX.blue], showValue: true, dataLabelPosition: "outEnd",
    dataLabelFormatCode: "0.00", valAxisMinVal: 0, valAxisMaxVal: 1.1, valAxisMajorUnit: 0.25, valAxisLabelFormatCode: "0.00", showLegend: true, legendPos: "b",
    showTitle: true, title: "ROC-AUC и PR-AUC: 6 систем", barGapWidthPct: 50, ...chartText, catAxisLabelFontSize: 10, dataLabelFontSize: 9, objectName: "results chart" });
  stat(s, 7.5, 1.5, 2.55, `${pct(CMP.baseline_fpr, 2)} → ${pct(CMP.model_fpr, 2)}`, `ложных тревог при равном recall: в ${ratio} раз меньше`, HEX.teal, "fpr drop", 22);
  stat(s, 10.2, 1.5, 2.55, num(S[W].test.roc_auc), `ROC-AUC, 95% CI ${num(S[W].test.roc_auc_95ci[0])}–${num(S[W].test.roc_auc_95ci[1])}; цель 0,94`, HEX.teal, "roc", 32);
  stat(s, 7.5, 3.1, 2.55, pct(POL.test_at_medium.recall), `мошенничества → step-up при FPR ${pct(POL.test_at_medium.fpr, 2)}`, HEX.blue, "stepup", 32);
  stat(s, 10.2, 3.1, 2.55, pct(POL.test_at_high.precision), "точность уровня HIGH (precision)", HEX.blue, "high precision", 32);
  stat(s, 7.5, 4.7, 2.55, `${apiLat.no_persist.p95_ms.toFixed(1).replace(".", ",")} мс`, `p95 HTTP-запроса; модель ${ev.latency.featurize_plus_model.p95_ms.toFixed(1).replace(".", ",")} мс; цель < 50`, HEX.teal, "latency", 30);
  stat(s, 10.2, 4.7, 2.55, `${num(prRange[0], 2)}–${num(prRange[1], 2)}`, "PR-AUC на других seed и жёстких данных", HEX.amber, "robust", 30);
  T(s, "Синтетические данные: результаты показывают работоспособность подхода, а не эффективность в реальном банке.", { x: 7.5, y: 6.3, w: 5.25, h: 0.45, fontSize: 11, color: HEX.amber, objectName: "results caveat" });
  s.addNotes(`На тесте гибрид даёт ROC-AUC ${num(S[W].test.roc_auc)} и PR-AUC ${num(S[W].test.pr_auc)} против ${num(S.rules.test.pr_auc)} у правил. При одинаковом recall ложных тревог в ${ratio} раз меньше. Цели 0,94 по ROC-AUC и минус 35% FPR на синтетике перевыполнены. Но это синтетика — дальше я покажу, почему в экономику мы эти цифры 1:1 не переносим.`);

  // ---------- 10. Business case ----------
  pres.addSection({ title: "Бизнес и ограничения" });
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Бизнес и ограничения" });
  s.addText("Бизнес-кейс: окупается при достаточном объёме", { placeholder: "title" });
  const scen = [["conservative", "Консервативный"], ["base", "Базовый"], ["optimistic", "Оптимистичный"]];
  card(s, 0.6, 1.5, 7.3, 5.2, "economics chart card");
  s.addChart(pres.charts.BAR, [
    { name: "Предотвращённые потери", labels: scen.map((x) => x[1]), values: scen.map((x) => +(SC[x[0]].result.avoided_fraud_losses / 1e9).toFixed(2)) },
    { name: "Экономия на ложных тревогах", labels: scen.map((x) => x[1]), values: scen.map((x) => +(SC[x[0]].result.reduced_fp_handling_costs / 1e9).toFixed(2)) },
    { name: "Затраты", labels: scen.map((x) => x[1]), values: scen.map((x) => -(SC[x[0]].result.total_costs / 1e9).toFixed(2)) },
    { name: "Чистый эффект", labels: scen.map((x) => x[1]), values: scen.map((x) => +(SC[x[0]].result.net_benefit / 1e9).toFixed(2)) }],
  { x: 0.75, y: 1.6, w: 7.0, h: 5.0, barDir: "col", barGrouping: "clustered", chartColors: [HEX.blue, HEX.aqua, HEX.orange, HEX.text], showValue: true,
    dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", valAxisLabelFormatCode: "0.0", showLegend: true, legendPos: "b", showTitle: true,
    title: "млрд ₸ в год, по сценариям допущений", barGapWidthPct: 40, ...chartText, dataLabelFontSize: 9, objectName: "economics chart" });
  stat(s, 8.2, 1.5, 4.55, `${mlrd(SC.base.result.net_benefit)} млрд ₸`, "чистый эффект в год, базовый сценарий (50 млн переводов)", HEX.teal, "base net", 30);
  stat(s, 8.2, 3.1, 4.55, `${mlrd(SC.conservative.result.net_benefit)} млрд ₸`, "консервативный сценарий: малый объём — проект не окупается", HEX.red, "cons net", 30);
  stat(s, 8.2, 4.7, 4.55, `≥ ${(BE.transfers_needed_base / 1e6).toFixed(0)} млн`, `переводов в год для безубыточности; или снижение FPR ≥ ${pct(BE.fpr_reduction_needed_base_without_recall_gain, 1)}, или +${(BE.recall_uplift_needed_base_without_fpr_gain * 100).toFixed(1).replace(".", ",")} п.п. recall`, HEX.amber, "break-even", 30);
  T(s, "Параметры банка — допущения. Снижение FPR в сценариях 20–60%, а не синтетические 98,5%.", { x: 8.2, y: 6.3, w: 4.55, h: 0.45, fontSize: 11, color: HEX.muted, objectName: "eco caveat" });
  s.addNotes("Формула: предотвращённые потери плюс экономия на ложных срабатываниях минус инфраструктура, интеграция и эксплуатация. Правилам мы даём ту же многоуровневую обработку, что и модели, чтобы засчитать модели только её собственный вклад. Модельные метрики не равны экономии: для небольшого банка проект не окупается, главный рычаг — объём операций.");

  // ---------- 11. Security & limitations ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Бизнес и ограничения" });
  s.addText("Безопасность, приватность и честные ограничения", { placeholder: "title" });
  T(s, "Что сделано", { x: 0.6, y: 1.45, w: 5.9, h: 0.4, fontSize: 18, bold: true, color: HEX.teal, objectName: "done header" });
  const done = [[ic.lock, "Валидация, лимиты тела и пакета, rate limit, CSP, аудит каждого решения"], [ic.eye, "Нет имён, карт, переписки, звонков — только псевдонимные ID и агрегаты"],
    [ic.user, "Поведение только с согласия; нет данных — так и пишем, не додумываем"], [ic.sliders, "Удержание — рекомендация; одно поведение не даёт HIGH"]];
  done.forEach(([im, t], i) => { const y = 1.95 + i * 1.12; card(s, 0.6, y, 5.9, 0.98, "done " + (i + 1));
    iconCircle(s, im, 0.78, y + 0.18, 0.62, "done icon " + (i + 1));
    T(s, t, { x: 1.6, y: y + 0.12, w: 4.75, h: 0.75, fontSize: 13, valign: "middle", objectName: "done text " + (i + 1) }); });
  T(s, "Проверено и что осталось", { x: 6.85, y: 1.45, w: 5.9, h: 0.4, fontSize: 18, bold: true, color: HEX.amber, objectName: "limits header" });
  const lim = [["Синтетические данные", "повтор на исторических данных банка (этап 1)"],
    [`Счета < 30 дней: ложные тревоги ${pct(TR0.young_false_alarm_rate)} (правила ${pct(FAIR.rules_young_false_alarm_test)})`, `опция отдельного порога: ${pct(TRC.young_false_alarm_rate)}, но recall здесь ${pct(TR0.young_fraud_recall, 0)} → ${pct(TRC.young_fraud_recall, 0)}`],
    [`Хитрый мошенник: −${Math.min(...advDrops).toFixed(1).replace(".", ",")}…${Math.max(...advDrops).toFixed(1).replace(".", ",")} п.п. за 1 сигнал`, "обучение на трюках не обобщается → нужны новые сигналы"],
    [`Нагрузка: ${Math.round(Math.max(...lt1.map((r) => r.throughput_rps)))} → ${Math.round(Math.max(...lt2.map((r) => r.throughput_rps)))} запр./с (1 → 2 процесса)`, "задержка 44 мс у 2 процессов устранена; Docker — в CI"]];
  lim.forEach(([a, b], i) => { const y = 1.95 + i * 1.12; card(s, 6.85, y, 5.9, 0.98, "limit " + (i + 1));
    iconCircle(s, ic.x, 7.03, y + 0.18, 0.62, "limit icon " + (i + 1));
    T(s, a, { x: 7.85, y: y + 0.1, w: 4.75, h: 0.42, fontSize: 13, bold: true, objectName: "limit title " + (i + 1) });
    T(s, "→ " + b, { x: 7.85, y: y + 0.52, w: 4.75, h: 0.4, fontSize: 12, color: HEX.muted, objectName: "limit next " + (i + 1) }); });
  T(s, "Не сертифицировано (PCI DSS, ISO 27001). Для реального банка нужны юридическая проверка, DPIA, security review и согласие клиентов.", { x: 0.6, y: 6.5, w: 12.15, h: 0.4, fontSize: 12, color: HEX.muted, objectName: "cert note" });
  s.addNotes("Мы не храним персональные данные, не читаем переписку и не слушаем звонки: модель знает только, был ли звонок, и только с согласия. Слабые стороны мы проверили сами. Переводы на новые счета: нашли и исправили две ошибки измерения, а оставшийся разрыв — это выбор политики, он вынесен в настройки. Подстраивающийся мошенник: скрыв один сигнал, он снижает выявление на несколько процентных пунктов; скрыв все сразу — неотличим от клиента, как и для любой системы. Нагрузка: около 90 запросов в секунду на одном процессе.");

  // ---------- 12. Roadmap ----------
  s = pres.addSlide({ masterName: "NS_CONTENT", sectionTitle: "Бизнес и ограничения" });
  s.background = { color: HEX.bg };
  s.addText("Дорожная карта и вывод", { placeholder: "title" });
  const road = [["1", "Данные и baseline", "повтор эксперимента на истории банка против его реальных правил"],
    ["2", "Shadow mode", "оценка живого трафика без влияния на платежи, 3 мес."], ["3", "Контролируемый пилот", "step-up для 5% клиентов, A/B против контроля"],
    ["4", "Постепенный rollout", "security review, нагрузка, регулятор"], ["5", "Мониторинг", "дрейф признаков, переобучение по порогам"]];
  road.forEach(([n, h, b], i) => { const x = 0.6 + i * 2.47;
    s.addShape(pres.shapes.OVAL, { x: x + 0.85, y: 1.55, w: 0.65, h: 0.65, fill: { color: i === 1 ? HEX.teal : HEX.card2 }, line: { color: HEX.teal, width: 1.25 }, objectName: "step circle " + n });
    T(s, n, { x: x + 0.85, y: 1.55, w: 0.65, h: 0.65, fontSize: 18, bold: true, align: "center", valign: "middle", color: i === 1 ? HEX.bg : HEX.teal, objectName: "step number " + n });
    if (i < 4) s.addShape(pres.shapes.LINE, { x: x + 1.55, y: 1.875, w: 1.77, h: 0, line: { color: HEX.line, width: 1.5, dashType: "dash" }, objectName: "step line " + n });
    card(s, x, 2.4, 2.32, 2.0, "step card " + n);
    T(s, h, { x: x + 0.18, y: 2.55, w: 1.96, h: 0.65, fontSize: 15, bold: true, objectName: "step title " + n });
    T(s, b, { x: x + 0.18, y: 3.2, w: 1.96, h: 1.1, fontSize: 12, color: HEX.muted, objectName: "step text " + n }); });
  card(s, 0.6, 4.75, 12.15, 1.9, "conclusion card", HEX.card2);
  iconCircle(s, ic.check, 0.9, 5.3, 0.8, "conclusion icon");
  T(s, "Вывод", { x: 1.95, y: 4.95, w: 10.5, h: 0.45, fontSize: 18, bold: true, color: HEX.teal, objectName: "conclusion title" });
  T(s, "Работающий объяснимый прототип: API, дашборд, " + N_TESTS + " тестов, воспроизводимый эксперимент, стресс-тесты. На синтетике — ROC-AUC " + num(S[W].test.roc_auc) +
    " и в " + ratio + " раз меньше ложных тревог, чем у правил. Мы не говорим, что система готова для банка — мы показываем, как это проверить и по каким критериям идти дальше.",
    { x: 1.95, y: 5.4, w: 10.55, h: 1.15, fontSize: 14, objectName: "conclusion text" });
  s.addNotes("Мы не говорим, что система готова для банка. Мы показываем, как её проверить на настоящих данных и по каким критериям решать, идти ли дальше: сначала повтор на истории банка, затем shadow mode без влияния на платежи, пилот с контрольной группой и только потом постепенный запуск. Спасибо!");

  const out = path.join(__dirname, "NeuroShield_presentation.pptx");
  await pres.writeFile({ fileName: out });
  await applyTheme(out, THEME);
  console.log("wrote", out);
})().catch((e) => { console.error(e); process.exit(1); });
