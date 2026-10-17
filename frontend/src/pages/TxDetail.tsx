import { Fragment } from "react";
import { api, type Behavioral } from "../api";
import { Async, Card, ComponentRow, Factors, Json, LevelBadge, PageHead, fmt, levelColor, useAsync } from "../components/ui";

const METRIC = {
  typing_interval_mean_ms: "Typing speed (interval)",
  typing_interval_cv: "Typing rhythm variability",
  confirm_pause_s: "Pause before confirm",
  correction_rate: "Input corrections",
  screen_sequence_deviation: "Navigation path",
} as Record<string, string>;

const INPUT_LABELS: [string, string, (v: any) => string][] = [
  ["amount", "Amount", fmt.kzt], ["transaction_type", "Type", (v) => fmt.type(String(v))],
  ["transaction_hour", "Hour", (v) => `${String(v).padStart(2, "0")}:00`],
  ["account_age_days", "Sender account age", (v) => `${Math.round(v)} days`],
  ["recipient_age_days", "Recipient account age", (v) => (v == null ? "external / unknown" : `${Math.round(v)} days`)],
  ["new_recipient", "New recipient", (v) => (v ? "yes" : "no")],
  ["transactions_last_1h", "Transactions last 1h", String], ["transactions_last_24h", "Transactions last 24h", String],
  ["device_anomaly", "New device", (v) => (v == null ? "unknown" : v ? "yes" : "no")],
  ["geographic_anomaly", "Unusual location", (v) => (v == null ? "unknown" : v ? "yes" : "no")],
  ["active_call_during_session", "Call during session", (v) => (v == null ? "not available" : v ? "yes" : "no")],
  ["user_reported_external_instruction", "Said someone instructed it", (v) => (v == null ? "not asked" : v ? "yes" : "no")],
  ["behavioral_consent", "Telemetry consent", (v) => (v ? "yes" : "no")],
];

function Deviations({ b, amountSigma }: { b: Behavioral; amountSigma: number | null }) {
  const rows: [string, number][] = [];
  if (amountSigma != null) rows.push(["Amount vs usual", amountSigma]);
  for (const [k, v] of Object.entries(b.deviations || {})) rows.push([METRIC[k] || k, v]);
  if (!rows.length) return <div className="empty">No customer baseline available for comparison.</div>;
  const lim = 6;
  return <div>
    {rows.map(([k, v]) => {
      const c = Math.max(-lim, Math.min(lim, v)), w = (Math.abs(c) / lim) * 50;
      return <div key={k} className="comp-row" style={{ gridTemplateColumns: "170px 1fr 64px" }}>
        <span className="small" style={{ color: "var(--text-2)" }}>{k}</span>
        <div style={{ position: "relative", height: 10, background: "var(--surface-3)", borderRadius: 4 }}>
          <div style={{ position: "absolute", left: "50%", top: -3, bottom: -3, width: 1, background: "var(--border-strong)" }} />
          <div style={{ position: "absolute", top: 0, bottom: 0, borderRadius: 3, background: Math.abs(v) > 2 ? "var(--medium)" : "var(--c1)",
            left: c >= 0 ? "50%" : `${50 - w}%`, width: `${w}%` }} />
        </div>
        <span className="mono small" style={{ textAlign: "right" }}>{v > 0 ? "+" : ""}{v.toFixed(1)}σ</span>
      </div>;
    })}
    <div className="small muted" style={{ marginTop: 6 }}>Standard deviations from this customer's own history. Unusual ≠ fraudulent: travel, new phones and new habits also deviate.</div>
  </div>;
}

export function TxDetail({ id }: { id: string }) {
  const s = useAsync(() => Promise.all([api.transaction(id), api.explanation(id)]), [id]);
  return <>
    <PageHead title={`Transaction ${id}`} desc="Risk profile, explanation and analyst report. Recommendations are simulated — Saqtan never blocks payments."
      right={<a className="btn" href="#/transactions">← Back to explorer</a>} />
    <Async s={s}>{([tx, ex]) => {
      const r = tx.result, c = ex.components, b = c.behavioral;
      return <div className="grid">
        <div className="grid g3">
          <Card title="Decision">
            <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
              <span className="score-big" style={{ color: levelColor(tx.risk_level) }}>{fmt.score(tx.risk_score)}</span>
              <span className="muted">/ 100</span><LevelBadge level={tx.risk_level} />
            </div>
            <div style={{ marginTop: 10, fontWeight: 600 }}>{fmt.action(tx.action)}</div>
            <div className="small muted">{r.action_description}</div>
            <div className="hr" />
            <dl className="kv small">
              <dt>Confidence</dt><dd>{ex.confidence}</dd>
              <dt>Policy</dt><dd>{tx.policy_mode} · {tx.policy_version}</dd>
              <dt>Thresholds</dt><dd>medium ≥ {fmt.score(r.thresholds.medium)} · high ≥ {fmt.score(r.thresholds.high)}</dd>
              <dt>Model</dt><dd className="mono" style={{ fontSize: 11 }}>{tx.model_version}</dd>
              <dt>Source</dt><dd>{tx.source}{tx.label != null && <> · synthetic label: <b>{tx.label ? `fraud (${tx.fraud_type})` : `legit (${tx.legit_tag})`}</b></>}</dd>
            </dl>
          </Card>
          <Card title="Risk profile by component" sub="Model output plus the four component scores it consumes">
            <ComponentRow label="Hybrid model score" value={tx.risk_score} />
            <ComponentRow label="Behavioral anomaly" value={b.score} note={b.reason} />
            <ComponentRow label="Money-flow graph" value={c.graph.score} />
            <ComponentRow label="Social engineering" value={c.social_engineering.score} />
            <ComponentRow label="Recipient reputation" value={c.recipient.score} />
            <div className="hr" />
            <div className="section-title">Behavioral data</div>
            <div className="small" style={{ marginBottom: 6 }}>Status: <b>{b.status}</b>{b.status === "available" && <> · reliability {b.reliability} · baseline {b.baseline_sessions} sessions</>}</div>
            <div className="chips">
              {b.available_features.map((f) => <span key={f} className="chip">{METRIC[f] || f}</span>)}
              {b.missing_features.map((f) => <span key={f} className="chip missing" title="Not available — not inferred">{METRIC[f] || f}</span>)}
            </div>
          </Card>
          <Card title="Compared with this customer's typical behavior">
            <Deviations b={b} amountSigma={c.amount_deviation_sigma} />
          </Card>
        </div>
        <div className="grid g2">
          <Card title={tx.risk_level === "LOW" ? "Minor upward factors (overall risk is low)" : "Why the risk is elevated"} sub="Group occlusion: each factor = drop in model log-odds when that group is set to a typical legitimate value (not SHAP)">
            <Factors items={ex.top_risk_factors} empty="No factor raised the risk materially." />
          </Card>
          <Card title="What lowers the risk">
            <Factors items={ex.mitigating_factors} empty="No strong mitigating factors." />
            <div className="hr" />
            <div className="section-title">Limitations of this assessment</div>
            <ul className="list small" style={{ color: "var(--text-2)" }}>{[...ex.confidence_notes, ...r.evaluation_warnings].map((n) => <li key={n}>{n}</li>)}</ul>
          </Card>
        </div>
        <div className="grid g2">
          <Card title="Inputs used" sub="Pseudonymous synthetic fields only — no names, card numbers or message contents">
            <dl className="kv">{INPUT_LABELS.map(([k, label, f]) => <Fragment key={k}><dt>{label}</dt><dd>{tx.inputs[k] === undefined ? "—" : f(tx.inputs[k])}</dd></Fragment>)}</dl>
          </Card>
          <Card title="Linked network findings" right={tx.recipient_ref && <a href={`#/graph?node=${encodeURIComponent(tx.recipient_ref)}`}>Open recipient in graph →</a>}>
            <dl className="kv small" style={{ marginBottom: 10 }}><dt>Sender</dt><dd className="mono">{tx.sender_ref || "—"}</dd><dt>Recipient</dt><dd className="mono">{tx.recipient_ref || "—"}</dd></dl>
            {c.graph.signals.length > 0 && <ul className="list small">{c.graph.signals.map((x) => <li key={x}>{x}</li>)}</ul>}
            {tx.graph_alerts.length ? tx.graph_alerts.map((a) => (
              <div key={a.alert_id} className="factor" style={{ gridTemplateColumns: "110px 1fr" }}>
                <div><div className="mono small">{a.alert_id}</div><div className="small muted">{a.type.replace("_", " ")} · {a.severity}</div></div>
                <div className="factor-detail">{a.explanation}</div></div>))
              : <div className="empty">No graph alerts involve the recipient.</div>}
            <div className="small muted">Graph alerts describe suspicious structures for review, not proof of wrongdoing.</div>
          </Card>
        </div>
        <Card title="Analyst report" sub={ex.report.generator + " · requires analyst review"}>
          <div className="grid g2">{ex.report.sections.map((sec) => (
            <div key={sec.title}><div className="section-title">{sec.title}</div>
              <ul className="list small" style={{ color: "var(--text-2)" }}>{sec.items.map((i) => <li key={i}>{i}</li>)}</ul></div>))}</div>
          <div className="small muted" style={{ marginTop: 8 }}>{ex.report.facts_vs_interpretation}</div>
        </Card>
        <details className="card"><summary className="small muted" style={{ cursor: "pointer" }}>Raw API response</summary><div style={{ marginTop: 10 }}><Json value={{ ...tx, explanation: ex }} /></div></details>
      </div>;
    }}</Async>
  </>;
}
