import { useState } from "react";
import { bio } from "../api";
import { Async, Card, Json, PageHead, fmt, useAsync } from "../components/ui";
import { Advice, KIND, Lvl, Reasons, Verdict } from "../components/bio";

function Detail({ id }: { id: string }) {
  const d = useAsync(() => bio.check(id), [id]);
  return <Card title={`Проверка ${id}`} right={<a className="small" href="#/history">← К списку</a>}>
    <Async s={d}>{(c) => <>
      <Verdict level={c.risk_level} title={c.summary} sub={`${KIND[c.kind] || c.kind} · ${fmt.time(c.created_at)} UTC`} />
      <Reasons items={c.result.reasons || []} />
      <Advice items={c.result.advice || []} />
      <div className="section-title" style={{ marginTop: 14 }}>Сохранённые данные (без фото, аудио и шаблонов лица)</div>
      <Json value={c.result} />
    </>}</Async>
  </Card>;
}

export function BioHistory({ id }: { id?: string }) {
  const [kind, setKind] = useState("");
  const [page, setPage] = useState(0);
  const [confirm, setConfirm] = useState(false);
  const [tick, setTick] = useState(0);
  const h = useAsync(() => bio.history({ kind, limit: 25, offset: page * 25 }), [kind, page, tick]);
  if (id) return <><PageHead title="История проверок" /><Detail id={id} /></>;
  const clear = async () => { await bio.clearHistory(); setConfirm(false); setPage(0); setTick((t) => t + 1); };
  return <>
    <PageHead title="История проверок" desc="Сохраняются только результаты: уровень риска, причины, параметры. Фото, аудио, кадры и шаблоны лиц сюда не попадают."
      right={<div className="toolbar" style={{ margin: 0 }}>
        <select className="input" value={kind} onChange={(e: any) => { setKind(e.target.value); setPage(0); }}>
          <option value="">Все проверки</option>{Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        {confirm ? <><span className="small">Удалить всю историю?</span><button className="btn" style={{ borderColor: "var(--high)" }} onClick={clear}>Да, удалить</button>
          <button className="btn" onClick={() => setConfirm(false)}>Отмена</button></> : <button className="btn" onClick={() => setConfirm(true)}>Очистить историю</button>}
      </div>} />
    <Card>
      <Async s={h}>{(d) => <>
        <div className="table-wrap"><table>
          <thead><tr><th>Время (UTC)</th><th>ID</th><th>Тип</th><th>Риск</th><th className="num">Балл</th><th>Итог</th></tr></thead>
          <tbody>{d.items.map((r) => <tr key={r.check_id} className="click" onClick={() => { window.location.hash = "#/history/" + r.check_id; }}>
            <td className="small muted">{fmt.time(r.created_at)}</td><td className="mono">{r.check_id}</td><td>{KIND[r.kind] || r.kind}</td>
            <td><Lvl level={r.risk_level} /></td><td className="num mono">{r.risk_score == null ? "—" : fmt.score(r.risk_score)}</td>
            <td className="small clip" style={{ maxWidth: 420 }} title={r.summary}>{r.summary}</td></tr>)}
            {!d.items.length && <tr><td colSpan={6} className="empty">Пока пусто.</td></tr>}</tbody>
        </table></div>
        <div className="pager"><span>{fmt.int(d.total)} всего</span>
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>←</button>
          <button className="btn" disabled={(page + 1) * 25 >= d.total} onClick={() => setPage(page + 1)}>→</button></div>
      </>}</Async>
    </Card>
  </>;
}
