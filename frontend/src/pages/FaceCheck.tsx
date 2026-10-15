import { useState } from "react";
import { bio } from "../api";
import { Async, Card, ErrorBox, PageHead, useAsync } from "../components/ui";
import { Advice, Privacy, Reasons, Verdict } from "../components/bio";
import { fileToDataURL, lastChecks, sleep, useCamera } from "../components/media";

const POSE_ARROW: Record<string, string> = { left: "←", right: "→", center: "●" };

function YawTrace({ trace, steps }: { trace: { step: number; yaw: number | null }[]; steps: { pose: string }[] }) {
  const W = 520, H = 120, pad = 24;
  const pts = trace.map((t, i) => ({ i, y: t.yaw, step: t.step }));
  const x = (i: number) => pad + (i * (W - 2 * pad)) / Math.max(1, pts.length - 1);
  const y = (v: number) => H / 2 - (v / 0.5) * (H / 2 - 12);
  const line = pts.filter((p) => p.y != null).map((p) => `${x(p.i)},${y(p.y as number)}`).join(" ");
  return <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", maxWidth: W, display: "block" }} role="img" aria-label="Поворот головы по кадрам">
    {[0.14, -0.14].map((t) => <line key={t} x1={pad} x2={W - pad} y1={y(t)} y2={y(t)} stroke="var(--border-strong)" strokeDasharray="3 4" />)}
    <line x1={pad} x2={W - pad} y1={y(0)} y2={y(0)} stroke="var(--grid)" />
    <text x={2} y={y(0.3) + 4}>влево</text><text x={2} y={y(-0.3) + 4}>вправо</text>
    {steps.map((s, i) => { const idx = pts.filter((p) => p.step === i).map((p) => p.i); if (!idx.length) return null;
      return <text key={i} x={(x(idx[0]) + x(idx[idx.length - 1])) / 2} y={H - 2} textAnchor="middle">{POSE_ARROW[s.pose]}</text>; })}
    <polyline points={line} fill="none" stroke="var(--accent-2)" strokeWidth={2} />
    {pts.filter((p) => p.y != null).map((p) => <circle key={p.i} cx={x(p.i)} cy={y(p.y as number)} r={2.6} fill="var(--accent-2)" />)}
  </svg>;
}

export function FaceCheck() {
  const cam = useCamera();
  const status = useAsync(() => bio.status(), []);
  const subjects = useAsync(() => bio.subjects(), []);
  const [photo, setPhoto] = useState<string | null>(null);
  const [photoRes, setPhotoRes] = useState<any>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [prompt, setPrompt] = useState<{ text: string; pose: string; i: number; n: number } | null>(null);
  const [live, setLive] = useState<any>(null);
  const [steps, setSteps] = useState<{ pose: string }[]>([]);
  const [ref, setRef] = useState("client_001");
  const [consent, setConsent] = useState(false);
  const [action, setAction] = useState<any>(null);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label); setErr(null);
    try { await fn(); } catch (e) { setErr(e); } finally { setBusy(null); setPrompt(null); }
  };

  const snap = () => { const d = cam.capture(1280, 0.92); if (d) { setPhoto(d); setPhotoRes(null); } };
  const upload = async (f?: File) => {
    if (!f) return;
    if (!/^image\/(jpeg|png|webp|bmp)$/.test(f.type)) { setErr(new Error("Нужен файл JPEG, PNG, WEBP или BMP")); return; }
    if (f.size > 6 * 1024 * 1024) { setErr(new Error("Файл больше 6 МБ")); return; }
    setPhoto(await fileToDataURL(f)); setPhotoRes(null);
  };
  const analyze = () => run("Анализ фото…", async () => {
    const r = await bio.analyzeFace(photo!);
    setPhotoRes(r);
  });

  const liveness = () => run("Проверка живости…", async () => {
    if (!cam.on) { await cam.start(); await sleep(900); }
    if (!cam.capture(64)) throw new Error(cam.error || "Камера не включилась. Нажмите «Включить камеру» и разрешите доступ.");
    setLive(null); setAction(null);
    const ch = await bio.challenge();
    setSteps(ch.steps);
    const frames: { step: number; image: string }[] = [];
    for (let i = 0; i < ch.steps.length; i++) {
      const s = ch.steps[i];
      setPrompt({ text: s.instruction, pose: s.pose, i, n: ch.steps.length });
      await sleep(700);                                   // time to react
      const gap = (ch.step_duration_ms - 700) / ch.frames_per_step;
      for (let k = 0; k < ch.frames_per_step; k++) {
        const f = cam.capture(480, 0.8);
        if (f) frames.push({ step: i, image: f });
        await sleep(gap);
      }
    }
    setPrompt({ text: "Проверяем…", pose: "center", i: ch.steps.length, n: ch.steps.length });
    const r = await bio.verifyLiveness(ch.challenge_id, frames);
    setLive(r);
    lastChecks.set("face", { id: r.check_id, summary: r.summary, level: r.risk_level });
  });

  const enroll = () => run("Регистрация…", async () => {
    const r = await bio.enroll({ subject_ref: ref, liveness_token: live.liveness_token, consent: true, consent_version: status.data!.consent.version });
    setAction({ kind: "enroll", ...r }); setLive({ ...live, liveness_token: undefined }); subjects.reload();
  });
  const verify = (useLive: boolean) => run("Сравнение…", async () => {
    const r = await bio.verifyFace(useLive ? { subject_ref: ref, liveness_token: live.liveness_token } : { subject_ref: ref, image: photo! });
    setAction({ kind: "verify", ...r });
    if (useLive) setLive({ ...live, liveness_token: undefined });
    lastChecks.set("face", { id: r.check_id, summary: r.summary, level: r.risk_level });
  });

  const models = status.data?.models || [];
  const faceReady = models.filter((m) => m.key.startsWith("face_")).every((m) => m.verified);
  const subjectList = subjects.data?.items || [];

  return <>
    <PageHead title="Проверка лица" desc="Детекция лица, проверка живости поворотами головы и сравнение с зарегистрированным лицом (OpenCV YuNet + SFace)." />
    <Privacy>Кадры с камеры и фото обрабатываются в памяти и не сохраняются. Шаблон лица сохраняется только после вашего явного согласия.</Privacy>
    {status.data && !faceReady && <div className="err" style={{ marginBottom: 16 }}><b>Модели лица не установлены.</b> Запустите <span className="mono">run_windows.bat</span> заново
      или выполните <span className="mono">python -m scripts.download_models</span>, затем обновите страницу. Подробнее — в разделе <a href="#/privacy">настроек</a>.</div>}
    {err != null && <div style={{ marginBottom: 16 }}><ErrorBox error={err} /></div>}
    <div className="grid g2">
      <Card title="Камера" sub="Разрешите доступ к камере, когда браузер спросит"
        right={cam.on ? <button className="btn" onClick={cam.stop}>Выключить</button> : <button className="btn primary" onClick={cam.start}>Включить камеру</button>}>
        <div className="cam">
          <video ref={cam.videoRef} playsInline muted className="mirror" style={{ display: cam.on ? "block" : "none" }} />
          {!cam.on && <div className="cam-off">{cam.error || "Камера выключена"}</div>}
          {prompt && <div className="cam-prompt" aria-live="assertive">
            <div className="cam-arrow">{POSE_ARROW[prompt.pose]}</div><div>{prompt.text}</div>
            <div className="small" style={{ opacity: .8 }}>{Math.min(prompt.i + 1, prompt.n)} / {prompt.n}</div></div>}
        </div>
        <div className="toolbar" style={{ marginTop: 12 }}>
          <button className="btn primary" disabled={!!busy || !faceReady} onClick={liveness}>Проверка живости</button>
          <button className="btn" disabled={!cam.on || !!busy} onClick={snap}>Сделать снимок</button>
          <label className="btn" style={{ cursor: "pointer" }}>Загрузить фото
            <input type="file" accept="image/jpeg,image/png,image/webp,image/bmp" style={{ display: "none" }} onChange={(e: any) => upload(e.target.files?.[0])} /></label>
          {busy && <span className="small muted"><span className="spin" style={{ display: "inline-block", verticalAlign: "middle", marginRight: 6 }} />{busy}</span>}
        </div>
        <div className="small muted">Проверка живости займёт около 10 секунд: следуйте подсказкам на экране и поворачивайте голову плавно.</div>
      </Card>

      <Card title="Результат проверки живости">
        {!live ? <div className="empty">Нажмите «Проверка живости». Система попросит повернуть голову в случайном порядке.</div> : <>
          <Verdict level={live.risk_level} title={live.summary} sub={live.passed ? "Порядок поворотов был случайным и не известен заранее" : undefined} />
          <Reasons items={live.reasons} />
          <div className="section-title" style={{ marginTop: 12 }}>Поворот головы по кадрам</div>
          <YawTrace trace={live.yaw_trace} steps={steps} />
          <div className="table-wrap"><table>
            <thead><tr><th>Шаг</th><th>Задание</th><th className="num">Кадров с лицом</th><th>Выполнено</th></tr></thead>
            <tbody>{live.steps.map((s: any) => <tr key={s.step}><td>{s.step + 1}</td><td>{POSE_ARROW[s.pose]} {s.instruction}</td>
              <td className="num">{s.frames_with_face}</td><td>{s.passed ? "да" : <span style={{ color: "var(--medium)" }}>нет</span>}</td></tr>)}</tbody>
          </table></div>
          <div className="small muted" style={{ marginTop: 8 }}>
            Тот же человек во всех кадрах: {live.identity_consistency.checked ? (live.identity_consistency.same_person ? `да (сходство ≥ ${live.identity_consistency.min_similarity})` : "НЕТ") : "не проверялось"}.
            {" "}{live.limitations}</div>
          {live.liveness_token && <>
            <div className="hr" />
            <div className="section-title">Что сделать с подтверждённым лицом</div>
            <div className="small muted" style={{ marginBottom: 8 }}>Подтверждение одноразовое и действует 5 минут.</div>
            <label className="field">Идентификатор клиента (псевдоним, без имени)
              <input id="subject-ref" className="input" value={ref} maxLength={40} onChange={(e: any) => setRef(e.target.value.replace(/[^A-Za-z0-9_\-]/g, ""))} /></label>
            <div className="toolbar" style={{ marginTop: 10 }}>
              <button className="btn" disabled={!!busy || !ref || !subjectList.some((s) => s.subject_ref === ref)} onClick={() => verify(true)}>Сравнить с зарегистрированным</button>
            </div>
            <div className="consent">
              <label className="check" style={{ alignItems: "flex-start" }}><input id="consent" type="checkbox" checked={consent} onChange={(e: any) => setConsent(e.target.checked)} />
                <span>{status.data?.consent.text}</span></label>
              <button className="btn primary" style={{ marginTop: 8 }} disabled={!consent || !!busy || !ref || !status.data?.privacy.allow_enrollment} onClick={enroll}>Зарегистрировать лицо</button>
              {status.data && !status.data.privacy.allow_enrollment && <div className="small muted">Регистрация выключена в настройках конфиденциальности.</div>}
            </div>
          </>}
        </>}
        {action && <><div className="hr" />
          {action.kind === "enroll" ? <div className="small"><b>Лицо зарегистрировано</b> как <span className="mono">{action.subject_ref}</span>: {action.stored}. Удалить: {action.how_to_delete}.</div>
            : <><Verdict level={action.risk_level} title={action.match ? "Совпадает с зарегистрированным лицом" : "НЕ совпадает с зарегистрированным лицом"}
              sub={`Сходство ${action.similarity} при пороге ${action.threshold}`} /><Reasons items={action.reasons} />
              {!action.match && <Advice items={["Остановите операцию и подтвердите личность другим способом (звонок по известному номеру, отделение)."]} />}</>}
        </>}
      </Card>
    </div>
    <div style={{ height: 16 }} />
    <div className="grid g2">
      <Card title="Фото" sub="Снимок с камеры или загруженный файл: поиск лица и проверка качества">
        {photo ? <div className="photo-wrap">
          <img src={photo} alt="Фото для проверки" />
          {photoRes && <svg viewBox={`0 0 ${photoRes.image_size[0]} ${photoRes.image_size[1]}`} className="photo-boxes" aria-hidden="true">
            {photoRes.faces.map((f: any, i: number) => <g key={i}><rect x={f.box[0]} y={f.box[1]} width={f.box[2]} height={f.box[3]} fill="none" stroke="var(--accent-2)" strokeWidth={Math.max(2, photoRes.image_size[0] / 300)} />
              {Object.values(f.landmarks).map((p: any, j) => <circle key={j} cx={p[0]} cy={p[1]} r={Math.max(2, photoRes.image_size[0] / 250)} fill="var(--medium)" />)}</g>)}
          </svg>}
        </div> : <div className="empty">Сделайте снимок или загрузите фото (JPEG, PNG, WEBP, BMP до 6 МБ).</div>}
        <div className="toolbar" style={{ marginTop: 12 }}>
          <button className="btn primary" disabled={!photo || !!busy || !faceReady} onClick={analyze}>Найти лицо</button>
          <button className="btn" disabled={!photo || !!busy || !subjectList.some((s) => s.subject_ref === ref)} onClick={() => verify(false)}>Сравнить с «{ref}» по фото</button>
          {photo && <button className="btn" onClick={() => { setPhoto(null); setPhotoRes(null); }}>Убрать фото</button>}
        </div>
        <div className="small muted">Сравнение по фото не подтверждает живость: фото могли предъявить вместо человека. Поэтому его риск не ниже среднего.</div>
      </Card>
      <Card title="Результат по фото">
        {!photoRes ? <div className="empty">Здесь появится результат анализа фото.</div> : <>
          <Verdict level={null} title={photoRes.summary} sub={photoRes.liveness_note} />
          <Reasons items={photoRes.issues} title="Замечания" />
          {photoRes.quality?.sharpness != null && <dl className="kv small" style={{ marginTop: 12 }}>
            <dt>Найдено лиц</dt><dd>{photoRes.faces_found}</dd>
            <dt>Уверенность детектора</dt><dd>{photoRes.quality.detection_score}</dd>
            <dt>Резкость (дисперсия Лапласиана)</dt><dd>{photoRes.quality.sharpness}</dd>
            <dt>Яркость лица (0–255)</dt><dd>{photoRes.quality.brightness}</dd>
            <dt>Ширина лица / кадра</dt><dd>{photoRes.quality.face_width_ratio}</dd>
          </dl>}
        </>}
      </Card>
    </div>
    <div style={{ height: 16 }} />
    <Card title="Зарегистрированные лица" right={<a className="small" href="#/privacy">Управление и удаление →</a>}>
      <Async s={subjects}>{(d) => d.items.length ? <div className="chips">{d.items.map((s) =>
        <button key={s.subject_ref} className={"chip" + (s.subject_ref === ref ? " on" : "")} onClick={() => setRef(s.subject_ref)}>{s.subject_ref}</button>)}</div>
        : <div className="empty">Нет зарегистрированных лиц. Пройдите проверку живости и дайте согласие, чтобы зарегистрировать.</div>}</Async>
    </Card>
  </>;
}
