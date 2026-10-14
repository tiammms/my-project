import { useState } from "react";
import { api, type RiskResult, type ScenarioRun } from "../api";
import { Card, ComponentRow, ErrorBox, LevelBadge, Loading, PageHead, fmt, levelColor, useAsync } from "../components/ui";

const yn = (v: any, unknown = "unknown") => (v == null ? unknown : v ? "yes" : "no");

function InputSummary({ input }: { input: any }) {
  const b = input.behavioral || {};
  const s = b.session || {}, bl = b.baseline || {};
  const row = (label: string, value: string) => <><dt>{label}</dt><dd>{value}</dd></>;
  return <dl className="kv small">
    {row("Amount", `${fmt.kzt(input.amount)} (usual ≈ ${fmt.kzt(input.sender_baseline?.median_amount)})`)}
    {row("Recipient", `${input.new_recipient ? "new" : "paid before"} · account ${input.recipient_age_days == null ? "external" : Math.round(input.recipient_age_days) + " days old"}`)}
    {row("Time · device · location", `${String(input.transaction_hour).padStart(2, "0")}:00 · ${input.device_anomaly ? "new device" : "known device"} · ${input.geographic_anomaly ? "unusual region" : "usual region"}`)}
    {row("Call during session", yn(input.active_call_during_session, "not available"))}
    {row("“Is someone telling you to make this transfer?”", yn(input.user_reported_external_instruction, "not asked"))}
    {row("Telemetry consent", b.consent ? "yes" : "no — behavioral component disabled")}
    {b.consent && s.confirm_pause_s != null && row("Pause before confirm", `${s.confirm_pause_s}s (baseline ${bl.confirm_pause_s?.mean}s)`)}
    {b.consent && s.correction_rate != null && row("Input corrections", `${fmt.pct(s.correction_rate, 0)} (baseline ${fmt.pct(bl.correction_rate?.mean, 0)})`)}
    {b.consent && s.typing_interval_mean_ms != null && row("Typing interval", `${s.typing_interval_mean_ms} ms (baseline ${bl.typing_interval_mean_ms?.mean} ms)`)}
    {input.graph && row("Recipient graph context", `${input.graph.recipient_distinct_senders_7d ?? "?"} senders in 7d · pass-through ${fmt.pct(input.graph.recipient_passthrough_ratio_7d, 0)} · linked to reported: ${yn(input.graph.recipient_linked_to_reported)}`)}
  </dl>;
}

function ScenarioPanel({ run, letter }: { run: ScenarioRun; letter: string }) {
  const r = run.result;
  return <Card title={<>{letter && <span className="tag" style={{ marginRight: 8 }}>{letter}</span>}{run.title.replace(/^[AB] - /, "")}</>} sub={run.story}>
    <div className="scenario">
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
        <span className="score-big" style={{ color: levelColor(r.risk_level) }}>{fmt.score(r.risk_score)}</span><span className="muted">/ 100</span>
        <LevelBadge level={r.risk_level} /><span className="act">{fmt.action(r.recommended_action)}</span>
      </div>
      <div className="small muted">{r.action_description}</div>
      <div><div className="section-title">Component results</div>
        <ComponentRow label="Hybrid model" value={r.risk_score} />
        <ComponentRow label="Behavioral anomaly" value={r.behavioral_anomaly_score} note={r.components.behavioral.reason} />
        <ComponentRow label="Money-flow graph" value={r.graph_risk_score} />
        <ComponentRow label="Social engineering" value={r.social_engineering_risk_score} />
        <ComponentRow label="Recipient reputation" value={r.recipient_risk_score} /></div>
      <div><div className="section-title">{r.risk_level === "LOW" ? "Minor upward factors (overall risk stays low)" : "Main reasons"}</div>
        {r.top_risk_factors.length ? <ul className="list small">{r.top_risk_factors.slice(0, 4).map((f) => <li key={f.group}><b>{f.label}</b> — <span className="muted">{f.detail}</span></li>)}</ul>
          : <div className="small muted">No factor raised the risk materially.</div>}</div>
      <details><summary className="small muted" style={{ cursor: "pointer" }}>Input data sent to the API</summary><div style={{ marginTop: 8 }}><InputSummary input={run.input} /></div></details>
      <div className="small" style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
        <span className="muted">confidence {r.confidence} · {fmt.ms(r.processing_ms)} server time</span>
        <a href={"#/tx/" + r.transaction_id}>Open full explanation →</a></div>
    </div>
  </Card>;
}

const ROWS: [string, (r: RiskResult) => number | null][] = [
  ["Final risk score", (r) => r.risk_score], ["Behavioral anomaly", (r) => r.behavioral_anomaly_score],
  ["Money-flow graph", (r) => r.graph_risk_score], ["Social engineering", (r) => r.social_engineering_risk_score],
  ["Recipient reputation", (r) => r.recipient_risk_score],
];

const FP_TESTS = ["legit_large_first_transfer", "traveling_customer", "new_phone", "changed_typing_habits", "no_behavioral_data", "account_takeover"];

export function DemoLab() {
  const list = useAsync(() => api.scenarios(), []);
  const [pair, setPair] = useState<{ a: ScenarioRun; b: ScenarioRun } | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);
  const [bench, setBench] = useState<ScenarioRun[] | null>(null);
  const [benchBusy, setBenchBusy] = useState(false);

  const runPair = async () => {
    setBusy(true); setErr(null);
    try { const [a, b] = await Promise.all([api.runScenario("normal_transfer"), api.runScenario("social_engineering")]); setPair({ a, b }); }
    catch (e) { setErr(e); } finally { setBusy(false); }
  };
  const runBench = async () => {
    setBenchBusy(true); setErr(null);
    try { setBench(await Promise.all(FP_TESTS.map((s) => api.runScenario(s)))); } catch (e) { setErr(e); } finally { setBenchBusy(false); }
  };
  return <>
    <PageHead title="Demo Lab" desc="Prepared synthetic scenarios sent through the real backend: validation → features → hybrid model → policy → explanation. Each run is stored and audited."
      right={<button className="btn primary" onClick={runPair} disabled={busy}>{busy ? <span className="spin" /> : "▶"} {pair ? "Re-run A & B" : "Run scenarios A & B"}</button>} />
    <div className="banner">⚠ <span>Synthetic experiment. The customer “Aigerim” and every value are invented. This shows how signals combine — it does <b>not</b> detect real stress or confirm fraud.</span></div>
    {err != null && <ErrorBox error={err} />}
    {!pair && !busy && <Card><div className="empty">Press <b>Run scenarios A &amp; B</b> to score a normal transfer and a suspected social-engineering transfer side by side.</div></Card>}
    {busy && !pair && <Loading label="Scoring" />}
    {pair && <div className="grid">
      <div className="grid g2"><ScenarioPanel run={pair.a} letter="A" /><ScenarioPanel run={pair.b} letter="B" /></div>
      <Card title="What changed between A and B">
        <div className="table-wrap"><table>
          <thead><tr><th>Signal</th><th className="num">A · normal</th><th className="num">B · suspected social engineering</th><th className="num">Difference</th></tr></thead>
          <tbody>{ROWS.map(([label, f]) => { const a = f(pair.a.result), b = f(pair.b.result), d = a != null && b != null ? b - a : null;
            return <tr key={label}><td>{label}</td><td className="num mono">{fmt.score(a)}</td><td className="num mono">{fmt.score(b)}</td>
              <td className={"num mono " + (d == null ? "" : d > 0 ? "diff-up" : "diff-down")}>{d == null ? "—" : (d > 0 ? "+" : "") + d.toFixed(1)}</td></tr>; })}
            <tr><td>Recommended action</td><td className="num act">{fmt.action(pair.a.result.recommended_action)}</td><td className="num act">{fmt.action(pair.b.result.recommended_action)}</td><td /></tr>
          </tbody></table></div>
        <div className="small muted" style={{ marginTop: 8 }}>B's escalation does not rest on a single signal: the social-engineering component needs at least two independent signals, and behavioral deviation alone can never trigger a hold recommendation.</div>
      </Card>
    </div>}
    <div style={{ height: 16 }} />
    <Card title="False-positive test bench" sub="Legitimate-but-unusual customers must not be escalated to HIGH; missing data must never be invented."
      right={<button className="btn" onClick={runBench} disabled={benchBusy}>{benchBusy ? <span className="spin" /> : "▶"} Run {FP_TESTS.length} checks</button>}>
      {list.error && <ErrorBox error={list.error} />}
      {!bench ? <div className="small muted">{list.data ? list.data.scenarios.filter((s) => FP_TESTS.includes(s.id)).map((s) => s.title).join(" · ") : ""}</div> :
        <div className="table-wrap"><table>
          <thead><tr><th>Scenario</th><th>Expected</th><th className="num">Score</th><th>Level</th><th>Behavioral</th><th>Outcome</th></tr></thead>
          <tbody>{bench.map((r) => {
            const ok = r.scenario === "account_takeover" ? r.result.risk_level === "HIGH"
              : r.scenario === "no_behavioral_data" ? r.result.behavioral_anomaly_score == null : r.result.risk_level !== "HIGH";
            return <tr key={r.scenario} className="click" onClick={() => { location.hash = "#/tx/" + r.result.transaction_id; }}>
              <td><b>{r.title}</b><div className="small muted">{r.story}</div></td><td className="small">{r.expected}</td>
              <td className="num mono">{fmt.score(r.result.risk_score)}</td><td><LevelBadge level={r.result.risk_level} /></td>
              <td className="small">{r.result.behavioral_anomaly_score == null ? "unavailable (not inferred)" : fmt.score(r.result.behavioral_anomaly_score)}</td>
              <td className="small" style={{ color: ok ? "#5fd45f" : "#ff8f8f" }}>{ok ? "✓ as expected" : "✗ unexpected"}</td></tr>;
          })}</tbody></table></div>}
    </Card>
  </>;
}
