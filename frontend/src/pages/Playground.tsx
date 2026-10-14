import { useState } from "react";
import { api, type RiskResult } from "../api";
import { Card, ComponentRow, ErrorBox, Factors, Json, LevelBadge, PageHead, fmt, levelColor } from "../components/ui";

const BASELINE = {
  typing_interval_mean_ms: { mean: 190, std: 14 }, typing_interval_cv: { mean: 0.36, std: 0.035 },
  confirm_pause_s: { mean: 2.3, std: 0.8 }, correction_rate: { mean: 0.06, std: 0.02 }, screen_sequence_deviation: { mean: 0.1, std: 0.06 },
};
const PRESETS: Record<string, any> = {
  "Normal transfer": {
    amount: 25000, transaction_type: "p2p_transfer", transaction_hour: 13, account_age_days: 2400, recipient_age_days: 1900,
    transactions_last_1h: 0, transactions_last_24h: 1, new_recipient: false, geographic_anomaly: false, device_anomaly: false,
    active_call_during_session: false, user_reported_external_instruction: null,
    sender_baseline: { median_amount: 18000, log_std: 0.8, tx_count: 140 },
    behavioral: { consent: true, baseline_sessions: 60, screen_flow: "known_payee", screen_flow_sessions: 54, baseline: BASELINE,
      session: { typing_interval_mean_ms: 193, typing_interval_cv: 0.37, confirm_pause_s: 2.1, correction_rate: 0.05, screen_sequence_deviation: 0.08 } },
    graph: { recipient_distinct_senders_7d: 1, recipient_passthrough_ratio_7d: 0.3, recipient_reported: false, recipient_linked_to_reported: false },
    persist: true,
  },
  "Suspicious transfer": {
    amount: 450000, transaction_type: "p2p_transfer", transaction_hour: 19, account_age_days: 2400, recipient_age_days: 9,
    transactions_last_1h: 0, transactions_last_24h: 1, new_recipient: true, geographic_anomaly: false, device_anomaly: false,
    active_call_during_session: true, user_reported_external_instruction: true,
    sender_baseline: { median_amount: 18000, log_std: 0.8, tx_count: 140 },
    behavioral: { consent: true, baseline_sessions: 60, screen_flow: "new_payee", screen_flow_sessions: 6,
      baseline: { ...BASELINE, screen_sequence_deviation: { mean: 0.22, std: 0.07 } },
      session: { typing_interval_mean_ms: 205, typing_interval_cv: 0.37, confirm_pause_s: 9.5, correction_rate: 0.21, screen_sequence_deviation: 0.45 } },
    graph: { recipient_distinct_senders_7d: 6, recipient_passthrough_ratio_7d: 0.92, recipient_reported: false, recipient_linked_to_reported: true },
    persist: true,
  },
  "Minimal (no context)": { amount: 120000, transaction_type: "international_transfer", transaction_hour: 2, account_age_days: 40, new_recipient: true, persist: true },
};

type Tri = boolean | null;
const triOpts: [string, Tri][] = [["unknown", null], ["yes", true], ["no", false]];

export function Playground() {
  const [body, setBody] = useState<any>(PRESETS["Normal transfer"]);
  const [text, setText] = useState(JSON.stringify(PRESETS["Normal transfer"], null, 2));
  const [parseErr, setParseErr] = useState("");
  const [res, setRes] = useState<{ r: RiskResult; rtt: number } | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const update = (b: any) => { setBody(b); setText(JSON.stringify(b, null, 2)); setParseErr(""); };
  const flowOf = (b: any) => (b.new_recipient && ["p2p_transfer", "international_transfer"].includes(b.transaction_type) ? "new_payee" : "known_payee");
  // navigation baselines are flow-specific: keep behavioral.screen_flow consistent with the transfer
  const set = (k: string, v: unknown) => {
    const nb: any = { ...body, [k]: v };
    if (nb.behavioral?.screen_flow && nb.behavioral.screen_flow !== flowOf(nb))
      nb.behavioral = { ...nb.behavioral, screen_flow: flowOf(nb), screen_flow_sessions: flowOf(nb) === "new_payee" ? 6 : 54,
        baseline: { ...nb.behavioral.baseline, screen_sequence_deviation: flowOf(nb) === "new_payee" ? { mean: 0.22, std: 0.07 } : BASELINE.screen_sequence_deviation } };
    update(nb);
  };
  const onText = (t: string) => { setText(t); try { setBody(JSON.parse(t)); setParseErr(""); } catch (e: any) { setParseErr(e.message); } };
  const submit = async () => {
    setErr(null); setBusy(true);
    let payload: unknown;
    try { payload = JSON.parse(text); } catch (e: any) { setParseErr(e.message); setBusy(false); return; }
    const t0 = performance.now();
    try { const r = await api.score(payload); setRes({ r, rtt: performance.now() - t0 }); } catch (e) { setErr(e); setRes(null); } finally { setBusy(false); }
  };
  const num = (k: string, label: string, step = 1) => <label className="field">{label}
    <input className="input" type="number" step={step} value={body[k] ?? ""} onChange={(e: any) => set(k, e.target.value === "" ? null : Number(e.target.value))} /></label>;
  const tri = (k: string, label: string) => <label className="field">{label}
    <select className="input" value={String(body[k] ?? null)} onChange={(e: any) => set(k, triOpts.find(([, v]) => String(v) === e.target.value)![1])}>
      {triOpts.map(([l, v]) => <option key={l} value={String(v)}>{l}</option>)}</select></label>;
  const consent = !!body.behavioral?.consent;

  return <>
    <PageHead title="API playground" desc={<>Build a request, send it to <span className="mono">POST /api/v1/risk/score</span> on this backend and inspect the raw response. Full schema: <a href="/docs" target="_blank" rel="noreferrer">Swagger UI</a>.</>}
      right={<div className="toolbar" style={{ margin: 0 }}>{Object.keys(PRESETS).map((p) => <button key={p} className="btn" onClick={() => update(PRESETS[p])}>{p}</button>)}</div>} />
    <div className="grid g2">
      <Card title="Request">
        <div className="grid g3" style={{ gap: 10 }}>
          {num("amount", "Amount (KZT)", 1000)}
          <label className="field">Type<select className="input" value={body.transaction_type} onChange={(e: any) => set("transaction_type", e.target.value)}>
            {["p2p_transfer", "international_transfer", "bill_payment", "card_payment"].map((t) => <option key={t} value={t}>{fmt.type(t)}</option>)}</select></label>
          {num("transaction_hour", "Hour (0–23)")}
          {num("account_age_days", "Sender account age (days)")}
          {num("recipient_age_days", "Recipient age (days, empty = external)")}
          <label className="field">New recipient<select className="input" value={String(!!body.new_recipient)} onChange={(e: any) => set("new_recipient", e.target.value === "true")}>
            <option value="true">yes</option><option value="false">no</option></select></label>
          {num("transactions_last_1h", "Transactions last 1h")}
          {num("transactions_last_24h", "Transactions last 24h")}
          {tri("device_anomaly", "New device")}
          {tri("geographic_anomaly", "Unusual location")}
          {tri("active_call_during_session", "Call during session")}
          {tri("user_reported_external_instruction", "Says someone instructed it")}
        </div>
        <div className="toolbar" style={{ marginTop: 12 }}>
          <label className="check"><input type="checkbox" checked={consent}
            onChange={(e: any) => set("behavioral", e.target.checked ? (body.behavioral?.session ? { ...body.behavioral, consent: true } : PRESETS[flowOf(body) === "new_payee" ? "Suspicious transfer" : "Normal transfer"].behavioral) : { consent: false, baseline_sessions: 0 })} />
            Behavioral telemetry consent</label>
          <label className="check"><input type="checkbox" checked={body.persist !== false} onChange={(e: any) => set("persist", e.target.checked)} />Store in event log</label>
        </div>
        {consent && body.behavioral?.session && <div className="grid g3" style={{ gap: 10 }}>
          {(["confirm_pause_s", "correction_rate", "typing_interval_mean_ms"] as const).map((k) => <label className="field" key={k}>{k.replace(/_/g, " ")} (baseline {BASELINE[k].mean})
            <input className="input" type="number" step={k === "correction_rate" ? 0.01 : k === "confirm_pause_s" ? 0.5 : 5} value={body.behavioral.session[k] ?? ""}
              onChange={(e: any) => set("behavioral", { ...body.behavioral, session: { ...body.behavioral.session, [k]: e.target.value === "" ? null : Number(e.target.value) } })} /></label>)}
        </div>}
        <div className="hr" />
        <div className="section-title">Request JSON (editable)</div>
        <textarea className="input" rows={16} value={text} onChange={(e: any) => onText(e.target.value)} spellCheck={false} />
        {parseErr && <div className="small" style={{ color: "#ff9a9a" }}>Invalid JSON: {parseErr}</div>}
        <div className="toolbar" style={{ marginTop: 10 }}>
          <button className="btn primary" onClick={submit} disabled={busy || !!parseErr}>{busy ? <span className="spin" /> : "▶"} Evaluate risk</button>
          <span className="small muted">Try a negative amount or an unknown field to see validation errors.</span>
        </div>
      </Card>
      <div className="grid" style={{ alignContent: "start" }}>
        {err != null && <ErrorBox error={err} />}
        {res ? <>
          <Card title="Result" right={<span className="small muted">server {fmt.ms(res.r.processing_ms)} · round trip {fmt.ms(res.rtt)}</span>}>
            <div style={{ display: "flex", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
              <span className="score-big" style={{ color: levelColor(res.r.risk_level) }}>{fmt.score(res.r.risk_score)}</span><span className="muted">/ 100</span>
              <LevelBadge level={res.r.risk_level} /><span className="act">{fmt.action(res.r.recommended_action)}</span></div>
            <div className="small muted" style={{ margin: "6px 0 10px" }}>{res.r.action_description} Confidence: {res.r.confidence}.</div>
            <ComponentRow label="Behavioral anomaly" value={res.r.behavioral_anomaly_score} />
            <ComponentRow label="Money-flow graph" value={res.r.graph_risk_score} />
            <ComponentRow label="Social engineering" value={res.r.social_engineering_risk_score} />
            <ComponentRow label="Recipient reputation" value={res.r.recipient_risk_score} />
            <div className="hr" />
            <div className="section-title">Risk factors</div>
            <Factors items={res.r.top_risk_factors} empty="No factor raised the risk materially." />
            {body.persist !== false && <div className="small" style={{ marginTop: 8 }}><a href={"#/tx/" + res.r.transaction_id}>Open {res.r.transaction_id} in transaction details →</a></div>}
          </Card>
          <Card title="Response JSON"><Json value={res.r} /></Card>
        </> : !err && <Card><div className="empty">Send a request to see the response.</div></Card>}
      </div>
    </div>
  </>;
}
