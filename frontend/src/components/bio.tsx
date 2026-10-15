import type { ReactNode } from "react";
import type { BioModel, BioLevel } from "../api";
import { LevelBadge, fmt } from "./ui";

export const KIND: Record<string, string> = {
  face_photo: "Фото лица", liveness: "Проверка живости", face_verify: "Сравнение лица",
  voice: "Голос / звонок", call_text: "Текст звонка", combined: "Общая проверка",
};
export const LEVEL_RU: Record<string, string> = { LOW: "Низкий риск", MEDIUM: "Средний риск", HIGH: "Высокий риск" };

export function Lvl({ level }: { level: BioLevel | undefined }) {
  return level ? <LevelBadge level={level} /> : <span className="tag">нет оценки</span>;
}

export function Verdict({ level, title, sub }: { level: BioLevel; title: ReactNode; sub?: ReactNode }) {
  const cls = level ? "verdict " + level : "verdict NONE";
  return <div className={cls}>
    <div className="verdict-level">{level ? LEVEL_RU[level] : "Нет оценки риска"}</div>
    <div className="verdict-title">{title}</div>
    {sub && <div className="small" style={{ color: "var(--text-2)", marginTop: 4 }}>{sub}</div>}
  </div>;
}

export function Reasons({ items, title = "Причины" }: { items: string[]; title?: string }) {
  if (!items?.length) return null;
  return <><div className="section-title" style={{ marginTop: 12 }}>{title}</div>
    <ul className="list small" style={{ color: "var(--text-2)" }}>{items.map((r, i) => <li key={i}>{r}</li>)}</ul></>;
}

export function Advice({ items }: { items: string[] }) {
  if (!items?.length) return null;
  return <div className="advice"><div className="section-title">Рекомендации по безопасности</div>
    <ul className="list small">{items.map((r, i) => <li key={i}>{r}</li>)}</ul></div>;
}

export function ModelList({ models }: { models: BioModel[] }) {
  return <div className="models">{models.map((m) => <div key={m.key} className="model-row">
    <span className={"dot " + (m.verified ? "ok" : "off")} aria-hidden="true" />
    <div style={{ minWidth: 0 }}>
      <div><b>{m.title}</b> <span className="tag" style={{ marginLeft: 4 }}>{m.verified ? "работает" : m.installed ? "ошибка" : "не установлено"}</span></div>
      <div className="small muted">{m.purpose}{m.license && m.license !== "-" ? ` · лицензия ${m.license}` : ""}</div>
      {m.problem && <div className="small" style={{ color: "var(--medium)", marginTop: 2 }}>{m.problem}</div>}
    </div>
  </div>)}</div>;
}

export function Meter({ value, max = 1, label, good }: { value: number; max?: number; label: string; good?: boolean }) {
  return <div className="comp-row"><span className="muted">{label}</span>
    <div className="bar-track"><div className="bar-fill" style={{ width: `${Math.min(100, (100 * value) / max)}%`, background: good === false ? "var(--medium)" : "var(--accent-2)" }} /></div>
    <span className="mono" style={{ textAlign: "right" }}>{fmt.n(value, 2)}</span></div>;
}

export function Privacy({ children }: { children: ReactNode }) {
  return <div className="banner" role="note"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} aria-hidden="true" style={{ flex: "none", marginTop: 1 }}><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg><div>{children}</div></div>;
}
