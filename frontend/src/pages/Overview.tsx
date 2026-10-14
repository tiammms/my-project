import { api, type Level } from "../api";
import { Async, Card, LevelBadge, PageHead, Stat, Syn, fmt, useAsync } from "../components/ui";
import { HBars, StackedColumns } from "../components/charts";

const LV: Level[] = ["LOW", "MEDIUM", "HIGH"];
const LV_COLORS = { LOW: "var(--low)", MEDIUM: "var(--medium)", HIGH: "var(--high)" };

export function Overview() {
  const s = useAsync(() => Promise.all([api.summary(), api.evaluation(), api.transactions({ risk_level: "HIGH", sort: "risk_score", page_size: 6 })]), []);
  return <>
    <PageHead title="Overview" desc="Operational view of the transactions stored in the demo event log, plus offline test-set quality. Every number links to its source." />
    <div className="banner">ⓘ <span>All transactions are <b>synthetic</b> (test split of the generated dataset plus anything sent through the API or Demo Lab). Metrics describe behaviour under the generator's assumptions, not real bank traffic.</span></div>
    <Async s={s}>{([sum, ev, high]) => {
      const lab = sum.labelled_demo_metrics;
      const hy = ev.systems[ev.winner];
      const live = sum.live_latency_ms;
      const days: Record<string, Record<string, number>> = {};
      for (const r of sum.activity as { day: string; risk_level: string; n: number }[]) (days[r.day] ||= {})[r.risk_level] = r.n;
      return <div className="grid">
        <div className="grid g4">
          <Stat label="Transactions processed" value={fmt.int(sum.processed)}
            note={Object.entries(sum.by_source).map(([k, v]) => `${k}: ${fmt.int(v as number)}`).join(" · ")} />
          <Stat label="Flagged (medium + high)" value={fmt.int(sum.suspicious)}
            note={`${fmt.pct(sum.suspicious / Math.max(1, sum.processed))} of processed · ${sum.policy_mode} policy`} />
          <Stat label="False-positive rate" tag={<Syn />} value={fmt.pct(lab?.false_positive_rate, 2)}
            note={`Labelled demo set, n=${fmt.int(lab?.n)} · legit flagged ÷ all legit`} />
          <Stat label="Precision / recall" tag={<Syn />} value={`${fmt.pct(lab?.precision, 0)} / ${fmt.pct(lab?.recall, 0)}`}
            note="Demo set keeps all frauds → precision is optimistic (see Evaluation)" />
        </div>
        <div className="grid g4">
          <Stat label="ROC-AUC (test split)" tag={<Syn />} value={fmt.n(hy.test.roc_auc)}
            note={`95% CI ${fmt.n(hy.test.roc_auc_95ci[0])}–${fmt.n(hy.test.roc_auc_95ci[1])}`} />
          <Stat label="PR-AUC (test split)" tag={<Syn />} value={fmt.n(hy.test.pr_auc)}
            note={`Rule baseline: ${fmt.n(ev.systems.rules.test.pr_auc)}`} />
          <Stat label="Live scoring latency p95" value={live.count ? fmt.ms(live.p95_ms) : "—"}
            note={live.count ? `${live.count} requests since server start (in-process, incl. explanation)` : "No live API requests yet — try Demo Lab"} />
          <Stat label="Model latency p95 (offline)" value={fmt.ms(ev.latency.featurize_plus_model.p95_ms)}
            note={`${ev.latency.environment.cpu_count} vCPU · featurize + model, after warm-up`} />
        </div>
        <div className="grid g3">
          <Card title="Risk level distribution" sub="All stored decisions, current policy thresholds">
            <HBars labelWidth={90} bars={LV.map((l) => ({ label: l, value: sum.by_level[l] || 0, color: LV_COLORS[l],
              display: fmt.int(sum.by_level[l] || 0), note: `${fmt.pct((sum.by_level[l] || 0) / Math.max(1, sum.processed))} of processed` }))} />
            <div className="hr" />
            <div className="small muted">Thresholds ({sum.policy_mode}): medium ≥ {fmt.score(sum.thresholds.medium)}, high ≥ {fmt.score(sum.thresholds.high)}</div>
          </Card>
          <Card className="span2" title="Daily activity by risk level" sub="Synthetic test period (dataset rows only)">
            <StackedColumns keys={LV} colors={LV_COLORS} data={Object.keys(days).sort().map((d) => ({ x: d, values: days[d] }))} height={200} />
          </Card>
        </div>
        <div className="grid g3">
          <Card className="span2" title="Highest-risk transactions" right={<a href="#/transactions?risk_level=HIGH">View all →</a>}>
            <div className="table-wrap"><table>
              <thead><tr><th>ID</th><th className="num">Amount</th><th className="num">Score</th><th>Level</th><th>Main reason</th></tr></thead>
              <tbody>{high.items.map((t) => (
                <tr key={t.transaction_id} className="click" onClick={() => { location.hash = "#/tx/" + t.transaction_id; }}>
                  <td className="mono">{t.transaction_id}</td><td className="num">{fmt.kzt(t.amount)}</td>
                  <td className="num mono">{fmt.score(t.risk_score)}</td><td><LevelBadge level={t.risk_level} /></td>
                  <td className="clip muted">{t.reason || "Open for explanation"}</td></tr>))}</tbody>
            </table></div>
          </Card>
          <Card title="Data-quality notes">
            <ul className="list small" style={{ color: "var(--text-2)" }}>
              {sum.data_quality_warnings.map((w: string) => <li key={w}>{w}</li>)}
              {lab?.note && <li>{lab.note}</li>}
            </ul>
          </Card>
        </div>
      </div>;
    }}</Async>
  </>;
}
