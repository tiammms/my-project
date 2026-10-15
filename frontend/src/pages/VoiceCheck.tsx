import { useState } from "react";
import { bio, type Tactic } from "../api";
import { Card, ErrorBox, PageHead, Seg, useAsync } from "../components/ui";
import { Advice, Meter, Privacy, Reasons, Verdict } from "../components/bio";
import { blobToDataURL, fileToDataURL, lastChecks, useRecorder } from "../components/media";

const EXAMPLE = "Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. " +
  "Чтобы отменить операцию, срочно продиктуйте код из СМС и никому не говорите об этом звонке.";

function Highlight({ text, tactics }: { text: string; tactics: Tactic[] }) {
  const words = tactics.flatMap((t) => (t.evidence || []).map((e) => e.match)).filter((w) => w.length > 2);
  if (!words.length) return <>{text}</>;
  const esc = words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");
  const parts = text.split(new RegExp(`(${esc})`, "gi"));
  return <>{parts.map((p, i) => i % 2 ? <mark key={i}>{p}</mark> : <span key={i}>{p}</span>)}</>;
}

export function VoiceCheck() {
  const rec = useRecorder(60);
  const status = useAsync(() => bio.status(), []);
  const [audio, setAudio] = useState<{ url: string; data: string; name: string } | null>(null);
  const [text, setText] = useState("");
  const [lang, setLang] = useState<"auto" | "ru" | "kk" | "en">("auto");
  const [asr, setAsr] = useState(true);
  const [res, setRes] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<unknown>(null);

  const setBlob = async (b: Blob, name: string) => setAudio({ url: URL.createObjectURL(b), data: await blobToDataURL(b), name });
  const toggle = async () => {
    if (rec.state === "recording") { const b = rec.stop(); if (b) await setBlob(b, "запись с микрофона.wav"); }
    else { setAudio(null); await rec.start((b) => setBlob(b, "запись с микрофона.wav")); }
  };
  const upload = async (f?: File) => {
    if (!f) return;
    if (f.size > 15 * 1024 * 1024) { setErr(new Error("Файл больше 15 МБ")); return; }
    setAudio({ url: URL.createObjectURL(f), data: await fileToDataURL(f), name: f.name });
  };
  const analyze = async () => {
    setBusy(true); setErr(null);
    try {
      const r = await bio.voice({ audio: audio?.data, transcript: text.trim() || undefined, transcribe: asr, language: lang });
      setRes(r);
      if (r.risk_level) lastChecks.set("voice", { id: r.check_id, summary: r.summary, level: r.risk_level });
    } catch (e) { setErr(e); } finally { setBusy(false); }
  };

  const asrModel = status.data?.models.find((m) => m.key === "speech_to_text");
  const call = res?.call_content;
  const shownText = res?.transcript?.text || (res?.text_source === "введён пользователем" ? text : "");

  return <>
    <PageHead title="Проверка голоса и звонка" desc="Запись разговора или его текст: качество записи, распознавание речи на этом компьютере и поиск тактик мошенников." />
    <Privacy>Аудио обрабатывается в памяти и не сохраняется. Распознавание речи работает локально (faster-whisper), без облака.
      Полный текст разговора в историю не записывается, если вы не включите это в настройках.</Privacy>
    {err != null && <div style={{ marginBottom: 16 }}><ErrorBox error={err} /></div>}
    <div className="grid g2">
      <Card title="1. Запись или файл" sub="До 60 секунд с микрофона или файл до 15 МБ">
        <div className="toolbar">
          <button className={"btn " + (rec.state === "recording" ? "" : "primary")} onClick={toggle} aria-pressed={rec.state === "recording"}>
            {rec.state === "recording" ? <>■ Остановить ({rec.seconds.toFixed(0)} с)</> : <>● Записать с микрофона</>}</button>
          <label className="btn" style={{ cursor: "pointer" }}>Загрузить аудио
            <input type="file" accept="audio/*,.wav,.mp3,.m4a,.ogg,.webm,.flac" style={{ display: "none" }} onChange={(e: any) => upload(e.target.files?.[0])} /></label>
        </div>
        {rec.state === "recording" && <div className="bar-track" style={{ height: 10 }} aria-label="Уровень громкости"><div className="bar-fill" style={{ width: `${rec.level * 100}%`, background: "var(--accent-2)" }} /></div>}
        {rec.error && <div className="err" style={{ marginTop: 8 }}>{rec.error}</div>}
        {audio && <div style={{ marginTop: 10 }}><div className="small muted">{audio.name}</div><audio controls src={audio.url} style={{ width: "100%" }} /></div>}
        <div className="small muted" style={{ marginTop: 8 }}>Форматы: WAV всегда; MP3, M4A, OGG, WEBM, FLAC — {status.data?.audio_decoding?.startsWith("любой") ? "поддерживаются" : "после установки голосовых пакетов"}.</div>
        <div className="hr" />
        <div className="toolbar">
          <span className="small muted">Язык</span>
          <Seg value={lang} onChange={setLang} options={[{ v: "auto", label: "Авто" }, { v: "ru", label: "Рус" }, { v: "kk", label: "Қаз" }, { v: "en", label: "Eng" }]} />
          <label className="check"><input type="checkbox" checked={asr} onChange={(e: any) => setAsr(e.target.checked)} /> Распознать речь</label>
        </div>
        {asrModel && !asrModel.verified && <div className="small" style={{ color: "var(--medium)" }}>Распознавание речи не установлено: {asrModel.problem} Пока можно вставить текст разговора ниже.</div>}
      </Card>
      <Card title="2. Текст разговора (необязательно)" sub="Если записи нет — вставьте, что говорил собеседник, или текст SMS / сообщения"
        right={<button className="btn" onClick={() => setText(EXAMPLE)}>Пример</button>}>
        <textarea id="call-text" className="input" rows={9} maxLength={20000} value={text} onChange={(e: any) => setText(e.target.value)}
          placeholder="Например: «Это служба безопасности банка, продиктуйте код из СМС…»" style={{ fontFamily: "var(--sans)", fontSize: 13 }} />
        <div className="toolbar" style={{ marginTop: 10 }}>
          <button className="btn primary" disabled={busy || rec.state === "recording" || (!audio && !text.trim())} onClick={analyze}>
            {busy ? "Анализ…" : "Проверить"}</button>
          {busy && audio && asr && <span className="small muted">Первое распознавание дольше: модель загружается в память.</span>}
        </div>
      </Card>
    </div>
    {res && <><div style={{ height: 16 }} />
      <div className="grid g2">
        <Card title="Итог">
          <Verdict level={res.risk_level} title={call ? call.summary : "Содержание разговора не проанализировано"}
            sub={res.disclaimer} />
          <Reasons items={res.reasons} />
          <Advice items={res.advice} />
          {res.speech_to_text_note && <div className="small" style={{ color: "var(--medium)", marginTop: 8 }}>{res.speech_to_text_note}</div>}
        </Card>
        <Card title="Проверки по отдельности">
          <div className="section-title">Содержание разговора</div>
          {call ? <>{call.tactics.length ? call.tactics.map((t: Tactic) => <div key={t.category} className="factor">
            <div><div className="factor-name">{t.title}</div><div className="small muted">вес {t.weight}</div></div>
            <div className="factor-detail">{(t.evidence || []).map((e, i) => <div key={i}>«{e.context}»</div>)}</div></div>)
            : <div className="small muted">Тактик мошенников не найдено.</div>}
            <div className="small muted" style={{ marginTop: 6 }}>{call.method}{call.short_text_note ? ` · ${call.short_text_note}` : ""}</div></>
            : <div className="small muted">Нет текста для анализа.</div>}
          <div className="hr" />
          <div className="section-title">Синтетический голос (дипфейк)</div>
          <div className="small" style={{ color: "var(--text-2)" }}><span className="tag">не подключено</span> {res.synthetic_voice.reason}</div>
          <div className="hr" />
          <div className="section-title">Качество записи</div>
          {res.signal_quality ? <>
            <Meter label="Доля речи" value={res.signal_quality.speech_ratio} good={res.signal_quality.speech_ratio >= 0.1} />
            <dl className="kv small" style={{ marginTop: 6 }}>
              <dt>Длительность</dt><dd>{res.signal_quality.duration_s} с</dd>
              <dt>Громкость (RMS)</dt><dd>{res.signal_quality.rms_dbfs} dBFS</dd>
              <dt>Шум (оценка SNR)</dt><dd>{res.signal_quality.snr_estimate_db} дБ</dd>
              <dt>Перегрузка</dt><dd>{(res.signal_quality.clipping_ratio * 100).toFixed(2)}%</dd>
            </dl>
            <Reasons items={res.signal_quality.issues} title="Замечания" />
            <div className="small muted">{res.signal_quality.note}</div></> : <div className="small muted">Аудио не было.</div>}
        </Card>
      </div>
      {shownText && <><div style={{ height: 16 }} />
        <Card title={res.transcript ? `Распознанный текст (${res.transcript.language}, ${res.transcript.seconds} с)` : "Проанализированный текст"}
          sub={res.transcript?.engine}>
          <p style={{ lineHeight: 1.7, margin: 0 }}><Highlight text={shownText} tactics={call?.tactics || []} /></p>
        </Card></>}
      <div style={{ height: 16 }} />
      <div className="toolbar"><a className="btn" href="#/combined">Использовать в общей проверке →</a><span className="small muted">ID проверки: <span className="mono">{res.check_id}</span></span></div>
    </>}
  </>;
}
