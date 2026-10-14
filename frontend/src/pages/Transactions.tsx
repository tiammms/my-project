import { useEffect, useState } from "react";
import { api } from "../api";
import { Card, ErrorBox, LevelBadge, Loading, PageHead, fmt, useAsync } from "../components/ui";
import type { Route } from "../main";

type Sort = "event_time" | "risk_score" | "amount";

export function Transactions({ route }: { route: Route }) {
  const [q, setQ] = useState(route.params.get("q") || "");
  const [qDebounced, setQD] = useState(q);
  const [level, setLevel] = useState(route.params.get("risk_level") || "");
  const [source, setSource] = useState(route.params.get("source") || "");
  const [type, setType] = useState("");
  const [sort, setSort] = useState<Sort>("risk_score");
  const [order, setOrder] = useState<"asc" | "desc">("desc");
  const [page, setPage] = useState(1);
  const size = 25;
  useEffect(() => { const t = setTimeout(() => setQD(q.replace(/[^A-Za-z0-9_-]/g, "")), 250); return () => clearTimeout(t); }, [q]);
  useEffect(() => setPage(1), [qDebounced, level, source, type, sort, order]);
  const s = useAsync(() => api.transactions({ q: qDebounced, risk_level: level, source, transaction_type: type, sort, order, page, page_size: size }),
    [qDebounced, level, source, type, sort, order, page]);
  const sortBy = (k: Sort) => { if (k === sort) setOrder(order === "asc" ? "desc" : "asc"); else { setSort(k); setOrder("desc"); } };
  const arrow = (k: Sort) => (k === sort ? (order === "desc" ? " ↓" : " ↑") : "");
  const pages = s.data ? Math.max(1, Math.ceil(s.data.total / size)) : 1;
  return <>
    <PageHead title="Transaction explorer" desc="Search, filter and sort every scored transaction. Click a row for the full risk profile and explanation." />
    <Card>
      <div className="toolbar">
        <input className="input" style={{ width: 240 }} placeholder="Search transaction / account ID" value={q} onChange={(e: any) => setQ(e.target.value)} />
        <select className="input" value={level} onChange={(e: any) => setLevel(e.target.value)}>
          <option value="">All risk levels</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option></select>
        <select className="input" value={type} onChange={(e: any) => setType(e.target.value)}>
          <option value="">All types</option><option value="p2p_transfer">P2P transfer</option>
          <option value="international_transfer">International transfer</option><option value="bill_payment">Bill payment</option></select>
        <select className="input" value={source} onChange={(e: any) => setSource(e.target.value)}>
          <option value="">All sources</option><option value="dataset">Dataset (synthetic test split)</option>
          <option value="scenario">Demo Lab scenarios</option><option value="api">API requests</option></select>
        <span className="muted small" style={{ marginLeft: "auto" }}>{s.data ? `${fmt.int(s.data.total)} transactions` : ""}</span>
      </div>
      {s.error ? <ErrorBox error={s.error} /> : !s.data ? <Loading /> :
        <div className="table-wrap"><table>
          <thead><tr>
            <th>ID</th><th className="sortable" onClick={() => sortBy("event_time")}>Time{arrow("event_time")}</th>
            <th className="num sortable" onClick={() => sortBy("amount")}>Amount{arrow("amount")}</th>
            <th>Recipient</th><th className="num sortable" onClick={() => sortBy("risk_score")}>Risk score{arrow("risk_score")}</th>
            <th>Level</th><th>Action</th><th>Reason</th><th title="Synthetic ground truth from the generator">Synthetic label</th>
          </tr></thead>
          <tbody>{s.data.items.map((t) => (
            <tr key={t.transaction_id} className="click" onClick={() => { location.hash = "#/tx/" + t.transaction_id; }}>
              <td className="mono">{t.transaction_id}</td><td className="muted small">{fmt.time(t.event_time)}</td>
              <td className="num">{fmt.kzt(t.amount)}</td><td className="mono small">{t.recipient_ref || "—"}</td>
              <td className="num mono">{fmt.score(t.risk_score)}</td><td><LevelBadge level={t.risk_level} /></td>
              <td className="act">{fmt.action(t.action)}</td><td className="clip muted small">{t.reason || "—"}</td>
              <td className="small">{t.label == null ? <span className="muted">n/a</span> : t.label ? <span style={{ color: "#ff9a9a" }}>fraud · {t.fraud_type}</span> : <span className="muted">legit · {t.legit_tag}</span>}</td>
            </tr>))}
            {!s.data.items.length && <tr><td colSpan={9} className="empty">No transactions match the filters.</td></tr>}
          </tbody></table></div>}
      <div className="pager">
        <button className="btn" disabled={page <= 1} onClick={() => setPage(page - 1)}>← Prev</button>
        <span>Page {page} / {pages}</span>
        <button className="btn" disabled={page >= pages} onClick={() => setPage(page + 1)}>Next →</button>
      </div>
    </Card>
  </>;
}
