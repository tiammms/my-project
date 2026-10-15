import { useState } from "react";
import { bio, type Privacy } from "../api";
import { Async, Card, ErrorBox, PageHead, Seg, fmt, useAsync } from "../components/ui";
import { ModelList } from "../components/bio";

export function PrivacyPage() {
  const st = useAsync(() => bio.status(), []);
  const subs = useAsync(() => bio.subjects(), []);
  const [err, setErr] = useState<unknown>(null);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const put = async (ch: Partial<Privacy>) => {
    setErr(null);
    try { await bio.putPrivacy(ch); st.reload(); } catch (e) { setErr(e); }
  };
  const del = async (ref: string) => { await bio.deleteSubject(ref); setConfirm(null); setDone(`Шаблон «${ref}» удалён.`); subs.reload(); st.reload(); };
  const erase = async () => { const r = await bio.eraseAll(); setConfirm(null); setDone(`Удалено: проверок ${r.history_deleted}, шаблонов лиц ${r.templates_deleted}.`); subs.reload(); st.reload(); };

  const Toggle = ({ k, on, off, label, note }: { k: keyof Privacy; on: string; off: string; label: string; note: string }) =>
    <Async s={st}>{(s) => <div className="pref">
      <div><div>{label}</div><div className="small muted">{note}</div></div>
      <Seg value={s.privacy[k] ? "on" : "off"} onChange={(v) => put({ [k]: v === "on" } as any)} options={[{ v: "on", label: on }, { v: "off", label: off }]} />
    </div>}</Async>;

  return <>
    <PageHead title="Настройки и конфиденциальность" desc="Что хранится, сколько и как это удалить. Все изменения записываются в журнал аудита." />
    {err != null && <ErrorBox error={err} />}
    {done && <div className="banner" role="status">{done}</div>}
    <div className="grid g2">
      <Card title="Хранение данных">
        <Toggle k="store_history" label="Записывать историю проверок" on="Да" off="Нет" note="Только результаты: уровень риска и причины" />
        <Toggle k="store_transcripts" label="Сохранять текст разговоров" on="Да" off="Нет" note="По умолчанию выключено: в историю идут только найденные тактики" />
        <Toggle k="allow_enrollment" label="Разрешить регистрацию лиц" on="Да" off="Нет" note="Даже при «Да» каждая регистрация требует отдельного согласия" />
        <Toggle k="speech_to_text" label="Распознавание речи" on="Вкл" off="Выкл" note="Работает на этом компьютере, без облака" />
        <Async s={st}>{(s) => <div className="pref">
          <div><div>Срок хранения истории</div><div className="small muted">Старые записи удаляются автоматически</div></div>
          <select className="input" value={s.privacy.history_retention_days} onChange={(e: any) => put({ history_retention_days: Number(e.target.value) })}>
            {[1, 7, 30, 90, 365].map((d) => <option key={d} value={d}>{d} дн.</option>)}</select>
        </div>}</Async>
      </Card>
      <Card title="Что хранится и что нет">
        <div className="table-wrap"><table>
          <thead><tr><th>Данные</th><th>Хранится?</th></tr></thead>
          <tbody>
            <tr><td>Фото и кадры с камеры</td><td>Нет — только в памяти на время проверки</td></tr>
            <tr><td>Аудиозаписи</td><td>Нет — только в памяти</td></tr>
            <tr><td>Шаблон лица (128 чисел)</td><td>Только с согласием, зашифрован (Fernet: AES + HMAC), ключ в <span className="mono">backend/data/biometric.key</span> или NS_BIOMETRIC_KEY</td></tr>
            <tr><td>Текст разговора</td><td>Только если включено выше</td></tr>
            <tr><td>Результаты проверок</td><td>Да, если включена история; срок — по настройке</td></tr>
            <tr><td>Передача третьим лицам / в облако</td><td>Нет. Интернет нужен только для первой загрузки моделей</td></tr>
          </tbody></table></div>
      </Card>
    </div>
    <div style={{ height: 16 }} />
    <div className="grid g2">
      <Card title="Зарегистрированные лица" sub="Шаблоны лиц, сохранённые с согласия">
        <Async s={subs}>{(d) => d.items.length ? <div className="table-wrap"><table>
          <thead><tr><th>Идентификатор</th><th>Согласие</th><th>Версия текста</th><th></th></tr></thead>
          <tbody>{d.items.map((s) => <tr key={s.subject_ref}><td className="mono">{s.subject_ref}</td><td className="small muted">{fmt.time(s.created_at)}</td>
            <td className="small">{s.consent_version}</td>
            <td className="num">{confirm === s.subject_ref ? <><button className="btn" style={{ borderColor: "var(--high)" }} onClick={() => del(s.subject_ref)}>Удалить</button>{" "}
              <button className="btn" onClick={() => setConfirm(null)}>Отмена</button></> : <button className="btn" onClick={() => setConfirm(s.subject_ref)}>Удалить</button>}</td></tr>)}</tbody>
        </table></div> : <div className="empty">Нет зарегистрированных лиц.</div>}</Async>
        <div className="hr" />
        {confirm === "__all" ? <div className="toolbar"><span>Удалить все шаблоны лиц и всю историю? Это необратимо.</span>
          <button className="btn" style={{ borderColor: "var(--high)", color: "#ffb3b3" }} onClick={erase}>Да, удалить всё</button>
          <button className="btn" onClick={() => setConfirm(null)}>Отмена</button></div>
          : <button className="btn" onClick={() => setConfirm("__all")}>Удалить все мои биометрические данные</button>}
      </Card>
      <Card title="Модели" sub="Где лежат и как установить недостающие">
        <Async s={st}>{(s) => <>
          <ModelList models={s.models} />
          <div className="small muted" style={{ marginTop: 10 }}>Папка моделей: <span className="mono">{s.model_dir}</span><br />Декодирование аудио: {s.audio_decoding}</div>
          <pre className="json" style={{ marginTop: 10 }}>{`python -m scripts.download_models        # модели лица (проверка SHA-256)
pip install -r requirements-voice.txt     # распознавание речи (faster-whisper)
# run_windows.bat делает оба шага автоматически`}</pre>
        </>}</Async>
      </Card>
    </div>
  </>;
}
