import { useMemo, useState } from "react";
import { forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY, type SimulationLinkDatum, type SimulationNodeDatum } from "d3-force";
import { api, type GraphAlert, type GraphEdge, type GraphNode } from "../api";
import { Card, ErrorBox, Loading, PageHead, Seg, fmt, useAsync } from "../components/ui";
import type { Route } from "../main";

type SimNode = GraphNode & SimulationNodeDatum;
type SimLink = SimulationLinkDatum<SimNode> & { e: GraphEdge; k: number };
type RiskFilter = "all" | "medium" | "high";

const EDGE_COLOR = { LOW: "#4a5b70", MEDIUM: "var(--medium)", HIGH: "var(--high)" };
const nodeColor = (n: GraphNode) => n.kind === "external" ? "#6b7a8f" : n.max_outgoing_risk >= 50 ? "var(--high)" : n.max_outgoing_risk >= 8 ? "var(--medium)" : "var(--c1)";

function layout(nodes: GraphNode[], edges: GraphEdge[]) {
  const sn: SimNode[] = nodes.map((n) => ({ ...n }));
  const pairCount: Record<string, number> = {};
  const links: SimLink[] = edges.map((e) => { const key = e.source + ">" + e.target; const k = (pairCount[key] = (pairCount[key] || 0) + 1) - 1; return { source: e.source, target: e.target, e, k }; });
  const sim = forceSimulation(sn)
    .force("link", forceLink<SimNode, SimLink>(links).id((d) => d.id).distance(55).strength(0.7))
    .force("charge", forceManyBody().strength(-140))
    .force("x", forceX(0).strength(0.12)).force("y", forceY(0).strength(0.12))
    .force("collide", forceCollide(14)).stop();
  sim.tick(320);   // deterministic static layout: same data -> same picture
  const xs = sn.map((n) => n.x || 0), ys = sn.map((n) => n.y || 0);
  const pad = 40;
  const box = { x: Math.min(...xs) - pad, y: Math.min(...ys) - pad, w: Math.max(...xs) - Math.min(...xs) + 2 * pad, h: Math.max(...ys) - Math.min(...ys) + 2 * pad };
  return { sn, links, box };
}

export function GraphPage({ route }: { route: Route }) {
  const focusNode = route.params.get("node") || "";
  const [type, setType] = useState("");
  const [minSev, setMinSev] = useState(0);
  const [risk, setRisk] = useState<RiskFilter>("all");
  const [selected, setSelected] = useState<string>(focusNode);
  const [alertSel, setAlertSel] = useState<GraphAlert | null>(null);
  const alerts = useAsync(() => api.graphAlerts({ type, min_severity: minSev, limit: 200 }), [type, minSev]);
  const view = useAsync(() => api.graphView(focusNode ? { node: focusNode } : { min_severity: minSev, max_alerts: 15 }), [focusNode, minSev]);

  const edges = useMemo(() => (view.data?.edges || []).filter((e) => risk === "all" || (risk === "high" ? e.risk_level === "HIGH" : e.risk_level !== "LOW")), [view.data, risk]);
  const nodes = useMemo(() => { const ids = new Set(edges.flatMap((e) => [e.source, e.target])); return (view.data?.nodes || []).filter((n) => ids.has(n.id) || n.focus); }, [view.data, edges]);
  const g = useMemo(() => layout(nodes, edges), [nodes, edges]);
  const hiNodes = useMemo(() => {
    if (alertSel) return new Set(alertSel.nodes);
    if (!selected) return null;
    const s = new Set([selected]); edges.forEach((e) => { if (e.source === selected) s.add(e.target); if (e.target === selected) s.add(e.source); }); return s;
  }, [selected, alertSel, edges]);
  const hiEdge = (e: GraphEdge) => alertSel ? alertSel.edges.some((x) => x.transaction_id === e.transaction_id) : selected ? e.source === selected || e.target === selected : true;
  const selNode = nodes.find((n) => n.id === selected);
  const selEdges = selected ? edges.filter((e) => e.source === selected || e.target === selected) : [];
  const nodeAlerts = (alerts.data?.alerts || []).filter((a) => selected && a.nodes.includes(selected));

  return <>
    <PageHead title="Network graph" desc="Money flows between synthetic accounts, built from stored transfers with NetworkX. Alerts flag suspicious structures — never a verdict about a person."
      right={focusNode ? <a className="btn" href="#/graph">← All alerts</a> : undefined} />
    <div className="grid g3">
      <Card className="span2" title={focusNode ? `Neighbourhood of ${focusNode}` : "Accounts involved in the 15 most severe alerts"}
        sub="Arrows show transfer direction. Click a node to highlight its links; double-click to open its neighbourhood."
        right={<Seg value={risk} onChange={setRisk} options={[{ v: "all", label: "All edges" }, { v: "medium", label: "Medium+" }, { v: "high", label: "High" }]} />}>
        {view.error ? <ErrorBox error={view.error} /> : !view.data ? <Loading label="Computing layout" /> : !nodes.length ? <div className="empty">No edges at this filter.</div> :
          <svg className="graph-svg" viewBox={`${g.box.x} ${g.box.y} ${g.box.w} ${g.box.h}`} preserveAspectRatio="xMidYMid meet"
            onClick={(e: any) => { if (e.target.tagName === "svg") { setSelected(""); setAlertSel(null); } }}>
            <defs>{(["LOW", "MEDIUM", "HIGH"] as const).map((l) => (
              <marker key={l} id={"arr-" + l} viewBox="0 0 10 10" refX="17" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" fill={EDGE_COLOR[l]} /></marker>))}</defs>
            {g.links.map((l) => {
              const s = l.source as SimNode, t = l.target as SimNode, on = hiEdge(l.e);
              const dx = (t.x || 0) - (s.x || 0), dy = (t.y || 0) - (s.y || 0), bend = 10 + l.k * 8;
              const mx = ((s.x || 0) + (t.x || 0)) / 2 - (dy / (Math.hypot(dx, dy) || 1)) * bend, my = ((s.y || 0) + (t.y || 0)) / 2 + (dx / (Math.hypot(dx, dy) || 1)) * bend;
              return <path key={l.e.transaction_id} d={`M${s.x},${s.y} Q${mx},${my} ${t.x},${t.y}`} fill="none" stroke={EDGE_COLOR[l.e.risk_level]}
                strokeWidth={l.e.risk_level === "HIGH" ? 2 : 1.3} opacity={on ? 0.95 : 0.12} markerEnd={`url(#arr-${l.e.risk_level})`}>
                <title>{`${l.e.transaction_id}: ${l.e.source} → ${l.e.target}\n${fmt.kzt(l.e.amount)} · ${fmt.time(l.e.time)} · score ${fmt.score(l.e.risk_score)} (${l.e.risk_level})`}</title></path>;
            })}
            {g.sn.map((n) => {
              const on = !hiNodes || hiNodes.has(n.id), r = 5 + Math.min(7, Math.sqrt(n.in_degree + n.out_degree) * 1.6);
              return <g key={n.id} className="node" transform={`translate(${n.x},${n.y})`} opacity={on ? 1 : 0.2}
                onClick={(e: any) => { e.stopPropagation(); setAlertSel(null); setSelected(n.id === selected ? "" : n.id); }}
                onDoubleClick={() => { location.hash = "#/graph?node=" + encodeURIComponent(n.id); }}>
                {n.kind === "external" ? <rect x={-r} y={-r} width={2 * r} height={2 * r} rx={2} fill={nodeColor(n)} stroke="var(--bg)" strokeWidth={2} />
                  : <circle r={r} fill={nodeColor(n)} stroke={n.id === selected || n.focus ? "#fff" : n.alerts.length ? "var(--accent-2)" : "var(--bg)"} strokeWidth={n.alerts.length || n.id === selected ? 2.5 : 2} />}
                {(n.id === selected || n.focus || (hiNodes && hiNodes.has(n.id) && nodes.length < 60)) && <text y={-r - 5} textAnchor="middle" style={{ fill: "var(--text)", fontSize: 10 }}>{n.id}</text>}
                <title>{`${n.id} (${n.kind})\nin ${n.in_degree} · out ${n.out_degree} · max outgoing risk ${fmt.score(n.max_outgoing_risk)}${n.alerts.length ? "\nalerts: " + n.alerts.join(", ") : ""}`}</title>
              </g>;
            })}
          </svg>}
        <div className="legend">
          <span><i style={{ background: "var(--c1)", width: 9, height: 9, borderRadius: 9 }} />account</span>
          <span><i style={{ background: "#6b7a8f", width: 9, height: 9 }} />external recipient</span>
          <span><i style={{ background: "var(--medium)", width: 9, height: 9, borderRadius: 9 }} />sent medium-risk</span>
          <span><i style={{ background: "var(--high)", width: 9, height: 9, borderRadius: 9 }} />sent high-risk</span>
          <span><i style={{ border: "2px solid var(--accent-2)", width: 10, height: 10, borderRadius: 9, background: "none" }} />in an alert</span>
          <span><i style={{ background: "var(--high)" }} />HIGH transfer</span><span><i style={{ background: "var(--medium)" }} />MEDIUM</span><span><i style={{ background: "#4a5b70" }} />LOW</span>
        </div>
      </Card>
      <div className="grid" style={{ alignContent: "start" }}>
        {selNode && <Card title={selNode.id} sub={`${selNode.kind} · in ${selNode.in_degree} · out ${selNode.out_degree}`}
          right={<a href={"#/graph?node=" + encodeURIComponent(selNode.id)}>Neighbourhood →</a>}>
          <div className="section-title">Transfers ({selEdges.length})</div>
          <div style={{ maxHeight: 220, overflow: "auto" }}>{selEdges.map((e) => (
            <div key={e.transaction_id} className="small" style={{ padding: "4px 0", borderBottom: "1px solid var(--grid)" }}>
              <a href={"#/tx/" + e.transaction_id} className="mono">{e.transaction_id}</a> {e.source === selNode.id ? "→ " + e.target : "← " + e.source}
              <span className="muted"> · {fmt.kzt(e.amount)} · </span><span style={{ color: EDGE_COLOR[e.risk_level] }}>{e.risk_level}</span></div>))}</div>
          {nodeAlerts.length > 0 && <><div className="hr" /><div className="section-title">Alerts</div>
            {nodeAlerts.map((a) => <div key={a.alert_id} className="small" style={{ marginBottom: 6 }}><b className="mono">{a.alert_id}</b> {a.explanation}</div>)}</>}
        </Card>}
        <Card title="Graph alerts" sub={alerts.data ? `${alerts.data.total} structures · ${alerts.data.graph.nodes} nodes · ${alerts.data.graph.edges} edges` : undefined}>
          <div className="toolbar">
            <select className="input" value={type} onChange={(e: any) => setType(e.target.value)}>
              <option value="">All types</option><option value="rapid_chain">Rapid chains</option><option value="fan_in">Fan-in</option><option value="shared_recipient">Shared recipient</option></select>
            <select className="input" value={minSev} onChange={(e: any) => setMinSev(Number(e.target.value))}>
              <option value={0}>Any severity</option><option value={60}>Severity ≥ 60</option><option value={80}>Severity ≥ 80</option></select>
          </div>
          {alerts.error ? <ErrorBox error={alerts.error} /> : !alerts.data ? <Loading /> :
            <div style={{ maxHeight: 470, overflow: "auto" }}>{alerts.data.alerts.map((a) => (
              <div key={a.alert_id} onClick={() => { setSelected(""); setAlertSel(alertSel?.alert_id === a.alert_id ? null : a); }}
                style={{ padding: "8px 8px", borderRadius: 6, cursor: "pointer", background: alertSel?.alert_id === a.alert_id ? "var(--surface-3)" : undefined, borderBottom: "1px solid var(--grid)" }}>
                <div style={{ display: "flex", justifyContent: "space-between" }}><b className="mono small">{a.alert_id}</b>
                  <span className="small muted">{a.type.replace("_", " ")} · severity {a.severity}</span></div>
                <div className="small" style={{ color: "var(--text-2)" }}>{a.explanation}</div>
                {alertSel?.alert_id === a.alert_id && <div className="small muted" style={{ marginTop: 4 }}>
                  Features: {Object.entries(a.features_used).map(([k, v]) => `${k}=${Array.isArray(v) ? v.join(" → ") : v}`).join("; ")}
                  {!nodes.some((n) => a.nodes.includes(n.id)) && <> · <a href={"#/graph?node=" + encodeURIComponent(a.focus_node)}>show in graph</a></>}</div>}
              </div>))}
              {!alerts.data.alerts.length && <div className="empty">No alerts.</div>}</div>}
          <div className="small muted" style={{ marginTop: 8 }}>{alerts.data?.method}</div>
        </Card>
      </div>
    </div>
  </>;
}
