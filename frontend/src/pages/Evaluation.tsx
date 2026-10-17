import { useState } from "react";
import { api } from "../api";
import { Async, Card, PageHead, Seg, Stat, Syn, fmt, useAsync } from "../components/ui";
import { HBars, Legend, LineChart, SERIES_COLORS, type Series } from "../components/charts";

// Fixed entity → colour mapping (never by rank).
const SYS = [
  { id: "rules", label: "Rule baseline", color: SERIES_COLORS[1], dashed: true },
  { id: "lr_txn", label: "Logistic reg. (txn)", color: SERIES_COLORS[3] },
  { id: "hgb_txn", label: "Gradient boosting (txn)", color: SERIES_COLORS[2] },
  { id: "lr_hybrid", label: "Logistic reg. (hybrid)", color: SERIES_COLORS[4] },
  { id: "rf_hybrid", label: "Random forest (hybrid)", color: SERIES_COLORS[5] },
  { id: "hgb_hybrid", label: "Saqtan: gradient boosting (hybrid)", color: SERIES_COLORS[0] },
];

function Confusion({ c, title }: { c: any; title: string }) {
  return <div>
    <div className="section-title">{title}</div>
    <div className="cm">
      <div className="h" /><div className="h">Flagged</div><div className="h">Not flagged</div>
      <div className="h" style={{ textAlign: "right" }}>Fraud</div>
      <div style={{ boxShadow: "inset 0 0 0 1px rgba(12,163,12,.5)" }}><b>{fmt.int(c.tp)}</b>true positive</div>
      <div style={{ boxShadow: "inset 0 0 0 1px rgba(224,82,82,.5)" }}><b>{fmt.int(c.fn)}</b>missed (FN)</div>
      <div className="h" style={{ textAlign: "right" }}>Legit</div>
      <div style={{ boxShadow: "inset 0 0 0 1px rgba(250,178,25,.5)" }}><b>{fmt.int(c.fp)}</b>false alarm (FP)</div>
      <div><b>{fmt.int(c.tn)}</b>true negative</div>
    </div>
    <dl className="kv small" style={{ marginTop: 10 }}>
      <dt>Precision</dt><dd>{fmt.pct(c.precision)}</dd><dt>Recall</dt><dd>{fmt.pct(c.recall)}</dd>
      <dt>F1</dt><dd>{fmt.n(c.f1)}</dd><dt>False-positive rate</dt><dd>{fmt.pct(c.fpr, 2)}</dd>
      <dt>False-negative rate</dt><dd>{fmt.pct(c.fnr)}</dd><dt>Alert rate</dt><dd>{fmt.pct(c.alert_rate, 2)}</dd>
    </dl>
  </div>;
}

export function Evaluation() {
  const s = useAsync(() => api.evaluation(), []);
  const [off, setOff] = useState<Set<string>>(new Set(["lr_txn", "lr_hybrid", "rf_hybrid"]));
  const [zoom, setZoom] = useState<"full" | "zoom">("zoom");
  const [mode, setMode] = useState<"balanced" | "strict">("balanced");
  const toggle = (id: string) => setOff((o) => { const n = new Set(o); n.has(id) ? n.delete(id) : n.add(id); return n; });
  return <>
    <PageHead title="Model evaluation" desc="Offline experiment results, loaded from experiments/results via the API. Nothing on this page is hard-coded." />
    <div className="banner">ⓘ <span>Measured on the <b>synthetic</b> held-out test period. Model choice and every threshold were fixed on the validation period first; the test period was used once. Real-world performance is <b>not</b> measured.</span></div>
    <Async s={s}>{(ev) => {
      const sys = SYS.filter((x) => ev.systems[x.id]);
      const xMax = zoom === "zoom" ? 0.1 : 1;
      const roc: Series[] = sys.filter((x) => !off.has(x.id)).map((x) => ({ ...x, points: (ev.curves[x.id].roc as [number, number][]).filter((p) => p[0] <= xMax).concat([[xMax, ev.curves[x.id].roc.find((p: number[]) => p[0] >= xMax)?.[1] ?? 1]]) }));
      const pr: Series[] = sys.filter((x) => !off.has(x.id)).map((x) => ({ ...x, points: (ev.curves[x.id].pr as [number, number][]).slice().sort((a, b) => a[0] - b[0]) }));
      const hy = ev.systems[ev.winner], cmp = ev.comparison_vs_rules[ev.winner].matched_test_recall, pol = ev.policies[mode];
      const lat = ev.latency, api_l = ev.api_latency;
      return <div className="grid">
        <div className="grid g4">
          <Stat label="ROC-AUC · Saqtan" tag={<Syn />} value={fmt.n(hy.test.roc_auc)} note={`target 0.94 · rules ${fmt.n(ev.systems.rules.test.roc_auc)}`} />
          <Stat label="PR-AUC · Saqtan" tag={<Syn />} value={fmt.n(hy.test.pr_auc)} note={`rules ${fmt.n(ev.systems.rules.test.pr_auc)} · txn-only GBM ${fmt.n(ev.systems.hgb_txn?.test.pr_auc)}`} />
          <Stat label="Relative FPR reduction vs rules" tag={<Syn />} value={cmp.relative_fpr_reduction_pct == null ? "—" : `${cmp.relative_fpr_reduction_pct.toFixed(1)}%`}
            note={`same recall ${fmt.pct(cmp.baseline_recall)}: FPR ${fmt.pct(cmp.baseline_fpr, 2)} → ${fmt.pct(cmp.model_fpr, 2)} (target 35%)`} />
          <Stat label="Latency p95" value={fmt.ms(lat.featurize_plus_model.p95_ms)}
            note={api_l ? `model · full HTTP request p95 ${fmt.ms(api_l.no_persist.p95_ms)}` : "model only; HTTP benchmark not run"} />
        </div>

        <Card title="Model comparison" sub={`Test split: ${fmt.int(ev.dataset.split_sizes.test)} transactions, ${ev.dataset.positives.test} frauds (${fmt.pct(ev.dataset.fraud_rate_test, 2)}). Winner chosen by validation PR-AUC: ${ev.winner}.`}>
          <div className="table-wrap"><table>
            <thead><tr><th>System</th><th>Features</th><th className="num">ROC-AUC [95% CI]</th><th className="num">PR-AUC [95% CI]</th>
              <th className="num">FPR at rules' recall</th><th className="num">Δ FPR vs rules</th></tr></thead>
            <tbody>{sys.map((x) => { const t = ev.systems[x.id].test, c = ev.comparison_vs_rules[x.id]?.matched_test_recall;
              return <tr key={x.id} style={x.id === ev.winner ? { background: "rgba(57,135,229,.08)" } : undefined}>
                <td><span style={{ color: x.color }}>■</span> {x.label}</td>
                <td className="small muted">{x.id === "rules" ? "8 weighted rules" : x.id.includes("hybrid") ? "transaction + behavioral + graph + SE + recipient" : "transaction only"}</td>
                <td className="num mono">{fmt.n(t.roc_auc)} <span className="muted">[{fmt.n(t.roc_auc_95ci[0])}–{fmt.n(t.roc_auc_95ci[1])}]</span></td>
                <td className="num mono">{fmt.n(t.pr_auc)} <span className="muted">[{fmt.n(t.pr_auc_95ci[0])}–{fmt.n(t.pr_auc_95ci[1])}]</span></td>
                <td className="num mono">{x.id === "rules" ? fmt.pct(ev.baseline.test.fpr, 2) : fmt.pct(c?.model_fpr, 2)}</td>
                <td className="num mono">{x.id === "rules" ? "baseline" : c?.relative_fpr_reduction_pct == null ? "—" : `${c.relative_fpr_reduction_pct > 0 ? "−" : "+"}${Math.abs(c.relative_fpr_reduction_pct).toFixed(1)}%`}</td></tr>; })}</tbody>
          </table></div>
          <div className="small muted" style={{ marginTop: 8 }}>“FPR at rules' recall” reads each ROC curve at exactly the rule baseline's test recall ({fmt.pct(ev.baseline.test.recall)}). 95% CIs from {hy.test.n_bootstrap} bootstrap resamples of the test set.</div>
        </Card>

        <div className="grid g2">
          <Card title="ROC curve" sub="True-positive rate vs false-positive rate (test)" right={<Seg value={zoom} onChange={setZoom} options={[{ v: "zoom", label: "FPR ≤ 10%" }, { v: "full", label: "Full" }]} />}>
            <LineChart series={roc} xLabel="False-positive rate" yLabel="True-positive rate (recall)" xMax={xMax} diagonal={zoom === "full"}
              xFmt={(v) => fmt.pct(v, zoom === "zoom" ? 0 : 0)} yFmt={(v) => v.toFixed(1)} />
            <Legend items={sys} off={off} onToggle={toggle} />
          </Card>
          <Card title="Precision–recall curve" sub={`More informative than ROC at ${fmt.pct(ev.dataset.fraud_rate_test, 1)} fraud prevalence`}>
            <LineChart series={pr} xLabel="Recall" yLabel="Precision" yFmt={(v) => v.toFixed(1)} />
            <Legend items={sys} off={off} onToggle={toggle} />
          </Card>
        </div>

        <Card title="Decision policy on the test period" sub={pol.rationale}
          right={<Seg value={mode} onChange={setMode} options={[{ v: "balanced", label: "Balanced" }, { v: "strict", label: "Strict" }]} />}>
          <div className="grid g2">
            <Confusion c={pol.test_at_medium} title={`Step-up or higher (score ≥ ${fmt.score(pol.medium_threshold)})`} />
            <Confusion c={pol.test_at_high} title={`Review / hold recommendation (score ≥ ${fmt.score(pol.high_threshold)})`} />
          </div>
        </Card>

        <div className="grid g2">
          <Card title="Legitimate but unusual customers" sub="Share escalated, balanced policy vs the rule baseline (test)">
            <div className="table-wrap"><table>
              <thead><tr><th>Segment</th><th className="num">n</th><th className="num">Step-up+</th><th className="num">High</th><th className="num">Rules flag</th></tr></thead>
              <tbody>{ev.segments_false_positive.map((g: any) => <tr key={g.segment}><td>{g.segment.replace(/_/g, " ")}</td><td className="num">{fmt.int(g.n)}</td>
                <td className="num mono">{fmt.pct(g.stepup_or_higher_rate)}</td><td className="num mono">{fmt.pct(g.high_rate)}</td><td className="num mono muted">{fmt.pct(g.rule_baseline_flag_rate)}</td></tr>)}</tbody>
            </table></div>
          </Card>
          <Card title="Recall by fraud type" sub="Balanced policy vs the rule baseline (test)">
            <div className="table-wrap"><table>
              <thead><tr><th>Fraud type</th><th className="num">n</th><th className="num">Recall step-up+</th><th className="num">Recall high</th><th className="num">Rules recall</th></tr></thead>
              <tbody>{ev.recall_by_fraud_type.map((g: any) => <tr key={g.fraud_type}><td>{g.fraud_type.replace(/_/g, " ")}</td><td className="num">{g.n}</td>
                <td className="num mono">{fmt.pct(g.recall_medium)}</td><td className="num mono">{fmt.pct(g.recall_high)}</td><td className="num mono muted">{fmt.pct(g.rule_baseline_recall)}</td></tr>)}</tbody>
            </table></div>
            <div className="hr" />
            <div className="section-title">Error analysis (balanced, step-up threshold)</div>
            <div className="small" style={{ color: "var(--text-2)" }}>
              False alarms by segment: {Object.entries(ev.error_analysis.false_positives_by_segment).map(([k, v]) => `${k.replace(/_/g, " ")} ${v}`).join(", ") || "none"}.<br />
              Missed frauds by type: {Object.entries(ev.error_analysis.false_negatives_by_type).map(([k, v]) => `${k.replace(/_/g, " ")} ${v}`).join(", ") || "none"}
              {" "}({ev.error_analysis.false_negatives_without_behavioral_data} of them without behavioral data).
            </div>
          </Card>
        </div>

        <div className="grid g2">
          <Card title="Ablation: remove one component" sub="Test PR-AUC of the hybrid model retrained without that component">
            <HBars max={1} labelWidth={170} bars={[{ label: "Full hybrid", value: hy.test.pr_auc, color: "var(--c1)" },
              ...Object.entries(ev.ablations).map(([k, v]: [string, any]) => ({ label: "without " + k.replace("without_", "").replace(/_/g, " "), value: v.test_pr_auc, color: "#5d6f86",
                note: `FPR at baseline recall ${fmt.pct(v.fpr_at_equal_recall, 2)}` }))]} />
          </Card>
          <Card title="Global feature importance" sub="Permutation importance on validation (drop in PR-AUC when shuffled)">
            <HBars labelWidth={190} bars={ev.global_importance.slice(0, 10).map((g: any) => ({ label: g.label, value: g.importance, color: "var(--c1)", note: `± ${g.std.toFixed(3)}` }))} />
          </Card>
        </div>

        <div className="grid g3">
          <Card title="Latency" sub={`${lat.environment.cpu} · ${lat.environment.cpu_count} vCPU · Python ${lat.environment.python}`}>
            <div className="table-wrap"><table>
              <thead><tr><th>Measured path</th><th className="num">p50</th><th className="num">p95</th><th className="num">p99</th></tr></thead>
              <tbody>
                {[["Model only", lat.model_only_single_row], ["Features + model", lat.featurize_plus_model], ["Explanation", lat.occlusion_explanation],
                  ...(api_l ? [["HTTP request", api_l.no_persist], ["HTTP + DB write", api_l.persist]] : [])].map(([k, v]: any) =>
                  <tr key={k}><td>{k}</td><td className="num mono">{fmt.ms(v.p50_ms)}</td><td className="num mono">{fmt.ms(v.p95_ms)}</td><td className="num mono">{fmt.ms(v.p99_ms)}</td></tr>)}
              </tbody></table></div>
            <div className="small muted" style={{ marginTop: 6 }}>In-process: 1000 runs after 50 warm-up. HTTP: sequential client on the same host. Training time excluded.</div>
          </Card>
          <Card title="Calibration & explanation checks">
            <dl className="kv small">
              <dt>Expected calibration error</dt><dd>{fmt.n(ev.calibration.ece, 4)}</dd>
              <dt>Brier score</dt><dd>{fmt.n(ev.calibration.brier, 4)}</dd>
              {ev.explanation_faithfulness && <><dt>Deletion test (n={ev.explanation_faithfulness.n})</dt>
                <dd>removing the top factor lowers log-odds by {ev.explanation_faithfulness.mean_logodds_drop_top_group.toFixed(2)} vs {ev.explanation_faithfulness.mean_logodds_drop_random_group.toFixed(2)} for a random factor</dd></>}
            </dl>
            <div className="small muted" style={{ marginTop: 8 }}>Despite good calibration on synthetic data, the UI presents a 0–100 risk score rather than a probability.</div>
          </Card>
          <Card title="Protocol">
            <dl className="kv small">
              <dt>Dataset</dt><dd>{ev.dataset.path} <span className="tag syn">synthetic</span></dd>
              <dt>Hash · seed</dt><dd className="mono">{ev.dataset.sha256_12} · {ev.seed}</dd>
              <dt>Split</dt><dd>train {fmt.int(ev.dataset.split_sizes.train)} · val {fmt.int(ev.dataset.split_sizes.validation)} · test {fmt.int(ev.dataset.split_sizes.test)} (by time)</dd>
              <dt>Model</dt><dd className="mono" style={{ fontSize: 11 }}>{ev.model_version}</dd>
            </dl>
          </Card>
        </div>

        <StressTests ev={ev} />

        {ev.sensitivity && <Card title="Robustness: other seeds and harsher assumptions" sub="Full protocol re-run on regenerated data. hard = weaker stress signal & rare disclosure; scarce = 30% telemetry consent; low_reports = 30% of frauds reported + 200 false reports.">
          <div className="table-wrap"><table>
            <thead><tr><th>Run</th><th className="num">Test frauds</th><th className="num">Rules PR-AUC</th><th className="num">Txn-only GBM PR-AUC</th><th className="num">Hybrid PR-AUC</th><th className="num">Hybrid ROC-AUC</th><th className="num">FPR reduction vs rules</th></tr></thead>
            <tbody>{ev.sensitivity.map((r: any) => <tr key={r.run}><td>{r.run}</td><td className="num">{r.test_positives}</td>
              <td className="num mono">{fmt.n(r.rules_pr_auc)}</td><td className="num mono">{fmt.n(r.hgb_txn_pr_auc)}</td><td className="num mono">{fmt.n(r.hgb_hybrid_pr_auc)}</td>
              <td className="num mono">{fmt.n(r.hgb_hybrid_roc_auc)}</td><td className="num mono">{r.relative_fpr_reduction_pct == null ? "—" : r.relative_fpr_reduction_pct.toFixed(1) + "%"}</td></tr>)}</tbody>
          </table></div>
        </Card>}
      </div>;
    }}</Async>
  </>;
}

// ---- Part 4: stress tests (fairness, adaptive adversary, load) -------------------------------
export function StressTests({ ev }: { ev: any }) {
  const fa = ev.fairness, adv = ev.adversary, lt = ev.load_test;
  const tr = fa?.deployed_model_threshold_tradeoff;
  const chosen = tr?.rows.find((r: any) => r.target === tr.chosen_by_validation_rule);
  return <div className="grid">
    {fa && tr && chosen && <Card title="Fairness: legitimate transfers to new accounts (< 30 days)"
      sub="Root causes found and fixed: navigation compared against a non-matching screen flow; merchant spending counted as “pass-through”. The remaining gap is a policy choice (opt-in setting).">
      <div className="table-wrap"><table>
        <thead><tr><th>Policy (test)</th><th className="num">False alarms, new accounts</th><th className="num">Frauds to new accounts caught</th><th className="num">Overall recall</th><th className="num">Overall FPR</th></tr></thead>
        <tbody>{[["Shared threshold (default)", tr.rows[0].test], [`Separate step-up threshold ${fmt.score(chosen.young_threshold)} (opt-in)`, chosen.test]].map(([k, t]: any) =>
          <tr key={k}><td>{k}</td><td className="num mono">{fmt.pct(t.young_false_alarm_rate)}</td><td className="num mono">{fmt.pct(t.young_fraud_recall)}</td>
            <td className="num mono">{fmt.pct(t.overall_recall)}</td><td className="num mono">{fmt.pct(t.overall_fpr, 2)}</td></tr>)}</tbody>
      </table></div>
      <div className="small muted" style={{ marginTop: 6 }}>Variant without the recipient-age feature was rejected by the pre-registered validation rule (validation PR-AUC {fmt.n(fa.variants.hybrid_without_direct_recipient_age.validation_pr_auc)} vs {fmt.n(fa.variants.hybrid_all_features.validation_pr_auc)}). Toggle the override in Settings.</div>
    </Card>}
    <div className="grid g2">
      {adv && <Card title="Adaptive adversary: hide one signal at a time" sub={`${adv.n_frauds} test frauds re-scored after the fraudster hides a signal. Balanced policy, step-up or higher.`}>
        <HBars max={1} labelWidth={200} fmt={(v) => fmt.pct(v, 0)} bars={[
          { label: "No evasion", value: adv.baseline.recall_stepup_plus, color: "var(--c1)", note: `rules ${fmt.pct(adv.baseline.rules_recall, 0)}` },
          ...Object.entries(adv.evasions).map(([k, v]: [string, any]) => ({ label: k.replace(/_/g, " "), value: v.recall_stepup_plus,
            color: k === "all_combined" ? "var(--high)" : "#5d6f86", note: `rules ${fmt.pct(v.rules_recall, 0)} · drop ${v.drop_pp.toFixed(1)} pp` }))]} />
        <div className="small muted" style={{ marginTop: 6 }}>No single evasion is a single point of failure. Hiding everything at once makes a transfer indistinguishable from a legitimate one — for any system. What-if on synthetic data.</div>
      </Card>}
      {lt && <Card title="Load test: POST /risk/score with concurrent clients" sub={`${lt.environment.cpu_count} vCPU, client and server on the same machine (lower bound)`}>
        <div className="table-wrap"><table>
          <thead><tr><th>Workers</th><th className="num">Clients</th><th className="num">Req/s</th><th className="num">p50</th><th className="num">p95</th><th className="num">p99</th><th className="num">Errors</th></tr></thead>
          <tbody>{lt.runs.map((r: any) => <tr key={r.workers + "-" + r.concurrency}><td>{r.workers}</td><td className="num">{r.concurrency}</td>
            <td className="num mono">{r.throughput_rps.toFixed(0)}</td><td className="num mono">{fmt.ms(r.p50_ms)}</td><td className="num mono">{fmt.ms(r.p95_ms)}</td>
            <td className="num mono">{fmt.ms(r.p99_ms)}</td><td className="num">{r.errors}</td></tr>)}</tbody>
        </table></div>
        <ul className="list small muted" style={{ marginTop: 6 }}>{(lt.findings || []).map((f: string) => <li key={f}>{f}</li>)}</ul>
      </Card>}
    </div>
  </div>;
}
