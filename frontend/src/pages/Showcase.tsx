import { useEffect, useRef, useState } from "react";
import { bio } from "../api";
import { ErrorBox, PageHead } from "../components/ui";
import { LEVEL_RU } from "../components/bio";

type Lvl = "LOW" | "MEDIUM" | "HIGH";
interface Line { caller: boolean; text: string }
interface Script { number: string; call: Line[]; sms: string; phishing: string; normal: string; note: string }
interface Res { level: Lvl; score: number; title: string; reasons: string[]; advice: string[]; evidence?: string[] }
interface Alert { level: Lvl; score: number; title: string; action: string }

const STEPS = [
  { n: 1, title: "Звонок с незнакомого номера", sub: "Оценка номера ещё до ответа" },
  { n: 2, title: "Звонящий говорит", sub: "Анализ речи по репликам: риск растёт" },
  { n: 3, title: "Приходит SMS с кодом", sub: "Код во время звонка — главный признак обмана" },
  { n: 4, title: "Сообщение со ссылкой", sub: "Поддельный сайт банка" },
  { n: 5, title: "Для сравнения: обычное сообщение", sub: "Без ложной тревоги" },
];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const callerText = (s: Script, n: number) => s.call.slice(0, n).filter((l) => l.caller).map((l) => l.text).join(" ");
const fromText = (r: any, title: string): Res => ({
  level: r.risk_level, score: r.risk_score, title,
  reasons: r.tactics.map((t: any) => t.title), advice: r.tactics.map((t: any) => t.advice),
  evidence: r.tactics.flatMap((t: any) => (t.evidence || []).map((e: any) => e.match)),
});

function Segs({ level }: { level: Lvl }) {
  const i = level === "HIGH" ? 3 : level === "MEDIUM" ? 2 : 1;
  return <div className="sc-segs">{[0, 1, 2].map((k) => <span key={k} className={k < i ? "on " + level : ""} />)}</div>;
}

function PhoneAlert({ a, onClose }: { a: Alert; onClose: () => void }) {
  const head = a.level === "HIGH" ? "Возможно, мошенник!" : a.level === "MEDIUM" ? "Есть признаки обмана" : "Незнакомый номер";
  return <div className={"sc-alert " + a.level} role="alert">
    <div className="sc-alert-head"><span className="sc-ring">!</span>
      <div><div className="sc-label">{a.level === "HIGH" ? "⚠ ВНИМАНИЕ" : "ОСТОРОЖНО"} · ДЕМО</div><div className="sc-headline">{head}</div></div></div>
    <div className="sc-meter"><Segs level={a.level} /><b>{LEVEL_RU[a.level]} · {Math.round(a.score)}</b></div>
    <div className="sc-why">{a.title}</div>
    {a.action && <div className="sc-action">→ {a.action}</div>}
    <button className="sc-ok" onClick={onClose}>Понятно</button>
  </div>;
}

export function Showcase() {
  const [script, setScript] = useState<Script | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [active, setActive] = useState(0);
  const [lines, setLines] = useState(0);
  const [res, setRes] = useState<Record<number, Res>>({});
  const [alert, setAlert] = useState<Alert | null>(null);
  const [sms, setSms] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const runId = useRef(0);

  useEffect(() => { bio.demoScenario().then(setScript).catch(setErr); }, []);

  const put = (n: number, r: Res) => setRes((p) => ({ ...p, [n]: r }));
  const live = (id: number) => runId.current === id;

  const step1 = async (s: Script) => {
    setActive(1); setLines(0); setSms(null); setMsg(null);
    const r = await bio.demoCall({ number: s.number });
    put(1, { level: r.risk_level, score: r.risk_score, title: "Входящий звонок", reasons: r.reasons, advice: r.advice });
    setAlert({ level: r.risk_level, score: r.risk_score, title: "Подозрительный входящий звонок", action: r.advice[0] || "" });
  };
  const step2 = async (s: Script, id: number, pace: number) => {
    setActive(2); setAlert(null); setSms(null); setMsg(null);
    let warned = false;
    for (let i = 1; i <= s.call.length; i++) {
      await sleep(pace); if (!live(id)) return;
      setLines(i);
      const r = await bio.demoText(callerText(s, i));
      put(2, fromText(r, "Речь звонящего"));
      if (r.risk_level === "HIGH" && !warned) {
        warned = true;
        setAlert({ level: "HIGH", score: r.risk_score, title: r.tactics[0]?.title || "Признаки обмана в разговоре", action: r.tactics[0]?.advice || "" });
      }
    }
  };
  const step3 = async (s: Script) => {
    setActive(3); setSms(s.sms); setMsg(null);
    const r = await bio.demoSms(s.sms);
    put(3, { level: r.risk_level, score: r.risk_score, title: "SMS во время звонка", reasons: r.reasons, advice: r.advice });
    setAlert({ level: r.risk_level, score: r.risk_score, title: "Код из SMS во время звонка с незнакомым номером", action: r.advice[0] || "" });
  };
  const step4 = async (s: Script) => {
    setActive(4); setSms(null); setMsg(s.phishing);
    const r = await bio.demoText(s.phishing);
    put(4, fromText(r, "Сообщение со ссылкой"));
    const link = r.tactics.find((t: any) => t.category === "phishing_link");
    setAlert({ level: r.risk_level, score: r.risk_score, title: link?.title || r.summary, action: link?.advice || "" });
  };
  const step5 = async (s: Script) => {
    setActive(5); setAlert(null); setSms(null); setMsg(s.normal);
    const r = await bio.demoText(s.normal);
    put(5, { ...fromText(r, "Обычное сообщение"), reasons: r.tactics.length ? r.tactics.map((t: any) => t.title) : ["Тактик мошенников не найдено — предупреждения нет"] });
  };

  const run = async (fn: (id: number) => Promise<void>, reset = false) => {
    if (!script) return;
    const id = ++runId.current; setErr(null); setRunning(true);
    if (reset) { setRes({}); setLines(0); }
    try { await fn(id); } catch (e) { setErr(e); } finally { if (live(id)) setRunning(false); }
  };
  const runAll = () => run(async (id) => {
    const s = script!;
    await step1(s); await sleep(4500); if (!live(id)) return;
    await step2(s, id, 1800); await sleep(4000); if (!live(id)) return;
    await step3(s); await sleep(4500); if (!live(id)) return;
    await step4(s); await sleep(4500); if (!live(id)) return;
    await step5(s); await sleep(1500); if (live(id)) setActive(6);
  }, true);
  const runOne = (n: number) => run(async (id) => {
    const s = script!;
    if (n === 1) await step1(s); else if (n === 2) await step2(s, id, 1000);
    else if (n === 3) await step3(s); else if (n === 4) await step4(s); else await step5(s);
  });
  const stop = () => { runId.current++; setRunning(false); setAlert(null); };

  const speech = res[2];
  const hit = (t: string) => (speech?.evidence || []).some((e) => e.length > 2 && t.toLowerCase().replace(/ё/g, "е").includes(e));

  return <>
    <PageHead title="Демо-показ" desc="Сценарий звонка мошенника для презентации. Номер, речь и сообщения выдуманы; оценки считает настоящий анализ на этом компьютере. В историю ничего не записывается."
      right={running ? <button className="btn" onClick={stop}>■ Остановить</button>
        : <button className="btn primary" onClick={runAll} disabled={!script}>▶ {active ? "Показать заново" : "Запустить сценарий"}</button>} />
    {err != null && <div style={{ marginBottom: 16 }}><ErrorBox error={err} /></div>}
    <div className="sc-wrap">
      <div className="sc-phone" aria-label="Экран телефона (демо)">
        <div className="sc-notch" />
        <div className="sc-screen">
          {active === 0 && <div className="sc-idle"><div className="sc-shield">✓</div><div>NeuroShield AI</div><div className="small muted">Нажмите «Запустить сценарий»</div></div>}
          {active >= 1 && active <= 3 && <div className="sc-call">
            <div className="small muted">{active === 1 ? "Входящий вызов" : "Идёт разговор"}</div>
            <div className="sc-num">{script?.number.replace(/^\+44(\d{4})(\d{6})$/, "+44 $1 $2")}</div>
            <div className="small muted">Нет в контактах</div>
            {active >= 2 && <div className="sc-chat">{script?.call.slice(0, lines).map((l, i) =>
              <div key={i} className={"sc-bubble " + (l.caller ? (hit(l.text) ? "caller hit" : "caller") : "me")}>{l.text}</div>)}</div>}
          </div>}
          {(active === 4 || active === 5 || active === 6) && <div className="sc-call">
            <div className="small muted">{active === 4 ? "Сообщение от неизвестного" : "Сообщение от «Айгерим»"}</div>
            {msg && <div className="sc-bubble caller" style={{ marginTop: 14 }}>{msg}</div>}
            {active === 6 && <div className="sc-done">Сценарий завершён</div>}
          </div>}
          {sms && <div className="sc-sms"><b>SMS · Kaspi.kz</b><div>{sms}</div></div>}
          {alert && <PhoneAlert a={alert} onClose={() => setAlert(null)} />}
        </div>
      </div>
      <div className="sc-steps">
        {STEPS.map((st) => {
          const r = res[st.n]; const on = active === st.n;
          return <div key={st.n} className={"card sc-step" + (on ? " on" : "") + (r ? " " + r.level : "")}>
            <div className="sc-step-head">
              <span className="sc-n">{st.n}</span>
              <div style={{ flex: 1 }}><b>{st.title}</b><div className="small muted">{st.sub}</div></div>
              {!running && <button className="btn" onClick={() => runOne(st.n)} disabled={!script}>Показать</button>}
            </div>
            {r && <div className="sc-res">
              <div className="sc-meter"><Segs level={r.level} /><b className={"lvl-" + r.level}>{LEVEL_RU[r.level]} · {Math.round(r.score)}</b></div>
              <ul className="list small" style={{ color: "var(--text-2)", margin: "6px 0 0" }}>{r.reasons.slice(0, 5).map((x, i) => <li key={i}>{x}</li>)}</ul>
            </div>}
          </div>;
        })}
        <div className="small muted" style={{ marginTop: 4 }}>Ограничения: в реальном звонке речь слышна только на громкой связи и не на всех телефонах; сгенерированный (поддельный) голос не распознаётся.</div>
      </div>
    </div>
  </>;
}
