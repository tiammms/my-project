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
const browser = await pw.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
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
await page.setViewportSize({ width: 390, height: 844 });
await shot("9-mobile-overview", "overview", "text=Highest-risk transactions");
await browser.close();
if (problems.length) { console.error("PROBLEMS:\n" + problems.join("\n")); process.exit(1); }
console.log("E2E OK - screenshots in docs/screenshots/");
