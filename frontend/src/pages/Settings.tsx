import { useState } from "react";
import { api } from "../api";
import { Async, Card, ErrorBox, PageHead, Seg, fmt, useAsync } from "../components/ui";

export function Settings() {
  const info = useAsync(() => api.modelInfo(), []);
  const st = useAsync(() => api.settings(), []);
  const [auditType, setAuditType] = useState("");
  const audit = useAsync(() => api.audit({ page_size: 15, event_type: auditType }), [auditType, st.data]);
  const [token, setToken] = useState("");
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const change = async (body: unknown) => {
    setBusy(true); setErr(null);
    try { await api.putSettings(body, token || undefined); st.reload(); info.reload(); } catch (e) { setErr(e); } finally { setBusy(false); }
  };
  return <>
    <PageHead title="Settings & model card" desc="Policy controls change live scoring and are written to the audit log. Model facts come from the loaded model bundle." />
    <div className="grid g2">
      <Card title="Decision policy">
        <Async s={st}>{(s) => <>
          <div className="toolbar">
            <span className="small muted" style={{ width: 150 }}>Policy mode</span>
            <Seg value={s.mode} onChange={(m) => !busy && change({ mode: m })} options={[{ v: "balanced", label: "Balanced" }, { v: "strict", label: "Strict" }]} />
          </div>
          <div className="toolbar">
            <span className="small muted" style={{ width: 150 }}>Hold recommendations</span>
            <Seg value={s.hold_recommendations_enabled ? "on" : "off"} onChange={(v) => !busy && change({ hold_recommendations_enabled: v === "on" })}
              options={[{ v: "on", label: "Enabled" }, { v: "off", label: "Review only" }]} />
          </div>
          {s.young_recipient_override?.available && <>
            <div className="toolbar">
              <span className="small muted" style={{ width: 150 }}>New-account recipients</span>
              <Seg value={s.young_recipient_override.enabled ? "on" : "off"} onChange={(v) => !busy && change({ young_recipient_override: v === "on" })}
                options={[{ v: "off", label: "Shared threshold" }, { v: "on", label: "Separate threshold" }]} />
            </div>
            {s.young_recipient_override.tradeoff?.override && <div className="small" style={{ color: "var(--text-2)", margin: "-4px 0 10px 162px" }}>
              Test split: false alarms on transfers to accounts &lt; {s.young_recipient_override.max_recipient_age_days} days{" "}
              {fmt.pct(s.young_recipient_override.tradeoff.shared.young_false_alarm_rate)} → {fmt.pct(s.young_recipient_override.tradeoff.override.young_false_alarm_rate)},
              but frauds to those accounts caught {fmt.pct(s.young_recipient_override.tradeoff.shared.young_fraud_recall)} → {fmt.pct(s.young_recipient_override.tradeoff.override.young_fraud_recall)}.
              Applies to the {s.young_recipient_override.mode} mode. A bank policy decision.</div>}
          </>}
          {s.admin_token_required && <div className="toolbar"><span className="small muted" style={{ width: 150 }}>Admin token</span>
            <input className="input" type="password" value={token} onChange={(e: any) => setToken(e.target.value)} placeholder="X-Admin-Token" /></div>}
          {err != null && <ErrorBox error={err} />}
          <div className="table-wrap" style={{ marginTop: 8 }}><table>
            <thead><tr><th>Mode</th><th className="num">Step-up ≥</th><th className="num">Review / hold ≥</th><th>How chosen (validation set)</th></tr></thead>
            <tbody>{Object.entries(s.modes).map(([m, v]: [string, any]) => <tr key={m} style={m === s.mode ? { background: "rgba(44,197,176,.08)" } : undefined}>
              <td>{m}{m === s.mode && <span className="tag" style={{ marginLeft: 6 }}>active</span>}</td>
              <td className="num mono">{fmt.score(v.medium_threshold)}</td><td className="num mono">{fmt.score(v.high_threshold)}</td>
              <td className="small muted">{v.rationale}</td></tr>)}</tbody>
          </table></div>
          <div className="hr" />
          <dl className="kv small">
            <dt>Policy version</dt><dd className="mono">{s.policy_version}</dd>
            <dt>Demo-safe mode</dt><dd>{s.demo_safe_mode ? "on" : "off"} — {s.demo_safe_mode_note} <span className="muted">(not configurable in the MVP)</span></dd>
            <dt>Effect of a change</dt><dd>New decisions use the new thresholds; stored dataset rows are re-levelled; API/scenario decisions stay as historically recorded.</dd>
          </dl>
        </>}</Async>
      </Card>
      <Card title="Model card">
        <Async s={info}>{(m) => <>
          <dl className="kv small">
            <dt>Model version</dt><dd className="mono">{m.model_version}</dd>
            <dt>Type</dt><dd>{m.model_type}</dd>
            <dt>Trained</dt><dd>{fmt.time(m.trained_at)} UTC · scikit-learn {m.sklearn_version}</dd>
            <dt>Hyperparameters</dt><dd className="mono">{JSON.stringify(m.hyperparameters)}</dd>
            <dt>Training data</dt><dd>{m.training_data.path} <span className="tag syn">synthetic</span> · {fmt.int(m.training_data.rows_used)} rows · {m.training_data.positives?.train} train frauds</dd>
            <dt>Explainability</dt><dd>{m.explainability}</dd>
            <dt>Calibration</dt><dd>{m.calibration ? `ECE ${fmt.n(m.calibration.ece, 4)}, Brier ${fmt.n(m.calibration.brier, 4)} — ${m.calibration.note}` : "—"}</dd>
            <dt>Features ({m.features.length})</dt><dd className="small muted">{m.features.join(", ")}</dd>
          </dl>
          <div className="hr" />
          <div className="section-title">Limitations</div>
          <ul className="list small" style={{ color: "var(--text-2)" }}>{m.limitations.map((l: string) => <li key={l}>{l}</li>)}</ul>
        </>}</Async>
      </Card>
    </div>
    <div style={{ height: 16 }} />
    <Card title="Audit log" sub="Every decision and policy change"
      right={<select className="input" value={auditType} onChange={(e: any) => setAuditType(e.target.value)}>
        <option value="">All events</option><option value="decision">Decisions</option><option value="policy_change">Policy changes</option><option value="dataset_loaded">Dataset loads</option></select>}>
      <Async s={audit}>{(a) => <div className="table-wrap"><table>
        <thead><tr><th>Time (UTC)</th><th>Event</th><th>Actor</th><th>Transaction</th><th>Details</th></tr></thead>
        <tbody>{a.items.map((r) => <tr key={r.id}><td className="small muted">{fmt.time(r.ts)}</td><td>{r.event_type.replace("_", " ")}</td><td className="small">{r.actor}</td>
          <td>{r.transaction_id ? <a className="mono" href={"#/tx/" + r.transaction_id}>{r.transaction_id}</a> : "—"}</td>
          <td className="small muted clip" style={{ maxWidth: 520 }} title={JSON.stringify(r.details)}>{Object.entries(r.details).map(([k, v]) => `${k}: ${typeof v === "object" ? JSON.stringify(v) : v}`).join(" · ")}</td></tr>)}
          {!a.items.length && <tr><td colSpan={5} className="empty">No events.</td></tr>}</tbody>
      </table><div className="small muted" style={{ marginTop: 6 }}>{fmt.int(a.total)} events total</div></div>}</Async>
    </Card>
  </>;
}
