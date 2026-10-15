// End-to-end UI check: opens every dashboard page in headless Chromium against the running
// backend, performs the key interactions, fails on console errors / failed requests, and saves
// screenshots to docs/screenshots/.
// Usage (backend running on :8000):  node scripts/e2e_check.mjs [http://127.0.0.1:8000]
import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
const require = createRequire(import.meta.url);
let pw;
try { pw = require("playwright"); } catch { pw = require("/opt/npm-tools/node_modules/playwright"); }

const BASE = process.argv[2] || "http://127.0.0.1:8000";
const OUT = new URL("../docs/screenshots/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
// fake camera/microphone (test pattern + beep) so the biometric pages can be exercised headless
const browser = await pw.chromium.launch({ args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.grantPermissions(["camera", "microphone"], { origin: BASE });
const page = await ctx.newPage();
const problems = [];
page.on("console", (m) => { if (m.type() === "error") problems.push("console: " + m.text()); });
page.on("pageerror", (e) => problems.push("pageerror: " + e.message));
page.on("response", (r) => { if (r.status() >= 400 && !r.url().includes("risk/score")) problems.push(`HTTP ${r.status()} ${r.url()}`); });

async function shot(name, hash, ready, act) {
  await page.goto(`${BASE}/#/${hash}`);
  await page.waitForSelector(ready, { timeout: 20000 });
  if (act) await act();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}${name}.png`, fullPage: true });
  const text = await page.locator("main").innerText();
  if (/Error \d|not reachable/i.test(text)) problems.push(`${name}: error box shown`);
  console.log("ok", name);
}

await shot("1-overview", "overview", "text=Highest-risk transactions");
await shot("2-transactions", "transactions?risk_level=HIGH", "tr.click");
const id = await page.locator("tr.click td.mono").first().innerText();
await shot("3-transaction-details", "tx/" + id, "text=Analyst report");
await shot("4-network-graph", "graph", ".graph-svg circle", async () => {
  await page.locator(".graph-svg .node").first().click();
});
await shot("5-demo-lab", "demo", "text=Run scenarios A", async () => {
  await page.click("text=Run scenarios A");
  await page.waitForSelector("text=What changed between A and B");
  await page.click("text=Run 6 checks");
  await page.waitForSelector("text=✓ as expected");
  const bad = await page.locator("text=✗ unexpected").count();
  if (bad) problems.push(`demo: ${bad} false-positive checks unexpected`);
});
await shot("6-model-evaluation", "evaluation", "text=Model comparison");
await shot("7-api-playground", "playground", "text=Evaluate risk", async () => {
  await page.click("text=Suspicious transfer");
  await page.click("text=Evaluate risk");
  await page.waitForSelector("text=Response JSON");
});
await shot("8-settings", "settings", "text=Model card", async () => {
  await page.click(".seg >> text=Strict");
  await page.waitForSelector("tr:has-text('strict') >> text=active");
  await page.click(".seg >> text=Balanced");
  await page.waitForSelector("tr:has-text('balanced') >> text=active");
});
// ---- biometrics & call checks ----
await shot("10-security-center", "security", "text=Модели и их состояние");
await shot("11-voice-check", "voice", "text=Текст разговора", async () => {
  await page.click("text=● Записать с микрофона");
  await page.waitForTimeout(2500);
  await page.click("text=■ Остановить");
  await page.waitForSelector("audio");
  await page.click("button:has-text('Пример')");
  await page.click("button:has-text('Проверить')");
  await page.waitForSelector("text=Рекомендации по безопасности", { timeout: 120000 });
  if (!(await page.locator(".verdict.HIGH").count())) problems.push("voice: scam example not rated HIGH");
});
const faceReady = await (await page.request.get(`${BASE}/api/v1/bio/status`)).json()
  .then((s) => s.models.filter((m) => m.key.startsWith("face_")).every((m) => m.verified));
await shot("12-face-check", "face", "text=Камера", async () => {
  await page.click("text=Включить камеру");
  await page.waitForFunction(() => { const v = document.querySelector("video"); return v && v.videoWidth > 0; });
  if (faceReady) {   // the fake camera shows a test pattern, not a face: the check must NOT pass
    await page.click("text=Проверка живости");
    await page.waitForSelector(".verdict", { timeout: 60000 });
    if (await page.locator("text=Живой человек подтверждён").count()) problems.push("face: liveness passed without a face");
  }
});
await shot("13-combined-check", "combined", "text=Выполнить общую проверку", async () => {
  await page.locator(".chip").first().click();
  await page.click("text=Выполнить общую проверку");
  await page.waitForSelector("text=Результаты каждой проверки");
});
await shot("14-check-history", "history", "tr.click");
await shot("15-privacy", "privacy", "text=Что хранится и что нет");
await page.setViewportSize({ width: 390, height: 844 });
await shot("9-mobile-overview", "overview", "text=Highest-risk transactions");
await browser.close();
if (problems.length) { console.error("PROBLEMS:\n" + problems.join("\n")); process.exit(1); }
console.log("E2E OK - screenshots in docs/screenshots/");
