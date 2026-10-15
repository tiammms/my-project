import { useState } from "react";
import { api, bio } from "../api";
import { Card, ErrorBox, PageHead, ScoreBar, fmt, levelColor, useAsync } from "../components/ui";
import { Advice, KIND, Lvl, Reasons, Verdict } from "../components/bio";
import { lastChecks } from "../components/media";

export function Combined() {
  const faces = useAsync(() => Promise.all([bio.history({ kind: "liveness", limit: 10 }), bio.history({ kind: "face_verify", limit: 10 })]), []);
  const voices = useAsync(() => bio.history({ kind: "voice", limit: 15 }), []);
  const txs = useAsync(() => api.transactions({ page_size: 12, sort: "risk_score", order: "desc" }), []);
  const lf = lastChecks.get("face"), lv = lastChecks.get("voice");
  const [face, setFace] = useState(lf?.id || "");
  const [voice, setVoice] = useState(lv?.id || "");
  const [tx, setTx] = useState("");
  const [res, setRes] = useState<any>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const faceOpts = [...(faces.data?.[0].items || []), ...(faces.data?.[1].items || [])].sort((a, b) => b.created_at.localeCompare(a.created_at));
  if (lf && !faceOpts.some((o) => o.check_id === lf.id)) faceOpts.unshift({ check_id: lf.id, created_at: "", kind: "liveness", risk_level: lf.level as any, risk_score: null, summary: lf.summary + " (эта вкладка)" });
  const voiceOpts = [...(voices.data?.items || [])].filter((o) => o.risk_level);
  if (lv && !voiceOpts.some((o) => o.check_id === lv.id)) voiceOpts.unshift({ check_id: lv.id, created_at: "", kind: "voice", risk_level: lv.level as any, risk_score: null, summary: lv.summary + " (эта вкладка)" });

  const run = async () => {
    setBusy(true); setErr(null);
    try { setRes(await bio.combined({ face_check_id: face || undefined, voice_check_id: voice || undefined, transaction_id: tx.trim() || undefined })); }
    catch (e) { setErr(e); } finally { setBusy(false); }
  };

  return <>
    <PageHead title="Общая проверка на мошенничество" desc="Объединяет результат проверки лица, проверки звонка и оценку перевода моделью NeuroShield в одно решение с причинами и рекомендациями." />
    <div className="grid g3">
      <Card title="Лицо" sub="Проверка живости или сравнение лица">
        <select id="face-check" className="input" style={{ width: "100%" }} value={face} onChange={(e: any) => setFace(e.target.value)}>
          <option value="">— не использовать —</option>
          {faceOpts.map((o) => <option key={o.check_id} value={o.check_id}>{(o.created_at ? fmt.time(o.created_at) + " · " : "") + KIND[o.kind] + " · " + o.summary}</option>)}
        </select>
        <div className="small muted" style={{ marginTop: 6 }}>Нет проверки? <a href="#/face">Пройти проверку лица</a></div>
      </Card>
      <Card title="Голос / звонок" sub="Результат анализа записи или текста разговора">
        <select id="voice-check" className="input" style={{ width: "100%" }} value={voice} onChange={(e: any) => setVoice(e.target.value)}>
          <option value="">— не использовать —</option>
          {voiceOpts.map((o) => <option key={o.check_id} value={o.check_id}>{(o.created_at ? fmt.time(o.created_at) + " · " : "") + o.summary}</option>)}
        </select>
        <div className="small muted" style={{ marginTop: 6 }}>Нет проверки? <a href="#/voice">Проверить звонок</a></div>
      </Card>
      <Card title="Перевод" sub="ID операции из журнала (модель + поведение клиента)">
        <input id="tx-id" className="input" style={{ width: "100%" }} value={tx} placeholder="например, T0123456" maxLength={40}
          onChange={(e: any) => setTx(e.target.value.replace(/[^A-Za-z0-9_\-]/g, ""))} />
        {txs.data && <div className="chips" style={{ marginTop: 8 }}>{txs.data.items.slice(0, 6).map((t) =>
          <button key={t.transaction_id} className={"chip" + (tx === t.transaction_id ? " on" : "")} title={`${fmt.kzt(t.amount)} · риск ${fmt.score(t.risk_score)}`}
            onClick={() => setTx(t.transaction_id)}>{t.transaction_id} · {t.risk_level}</button>)}</div>}
      </Card>
    </div>
    <div className="toolbar" style={{ marginTop: 16 }}>
      <button className="btn primary" disabled={busy || (!face && !voice && !tx)} onClick={run}>{busy ? "Считаем…" : "Выполнить общую проверку"}</button>
      <span className="small muted">Отсутствующие проверки не додумываются: они перечисляются как «не выполнено».</span>
    </div>
    {err != null && <ErrorBox error={err} />}
    {res && <div className="grid g2">
      <Card title="Решение">
        <Verdict level={res.risk_level} title={res.recommended_action} sub={res.disclaimer} />
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginTop: 12 }}>
          <span className="score-big" style={{ color: levelColor(res.risk_level) }}>{fmt.score(res.risk_score)}</span><span className="muted">/ 100</span></div>
        {res.escalated_by_multiple_signals && <div className="small" style={{ color: "var(--high)", marginTop: 6 }}>Уровень повышен: риск показывают несколько независимых проверок.</div>}
        <Reasons items={res.reasons} />
        <Advice items={res.advice} />
      </Card>
      <Card title="Результаты каждой проверки">
        {res.components.map((c: any) => <div key={c.component} style={{ padding: "8px 0", borderBottom: "1px solid var(--grid)" }}>
          <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}><b>{c.title}</b><Lvl level={c.risk_level} /></div>
          <ScoreBar value={c.risk_score} color={levelColor(c.risk_level)} />
          <div className="small muted" style={{ marginTop: 4 }}>{c.details} · {fmt.score(c.risk_score)}/100 · <span className="mono">{c.source_check}</span></div>
        </div>)}
        {res.missing.length > 0 && <><div className="section-title" style={{ marginTop: 12 }}>Не выполнено</div>
          <div className="chips">{res.missing.map((m: string) => <span key={m} className="chip missing">{m}</span>)}</div></>}
        <div className="small muted" style={{ marginTop: 12 }}>{res.method}</div>
      </Card>
    </div>}
  </>;
}
