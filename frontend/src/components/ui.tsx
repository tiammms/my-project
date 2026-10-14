import { useEffect, useState, type ReactNode } from "react";
import { ApiError, type Level, type Factor } from "../api";

// ---------- formatting ----------
export const fmt = {
  int: (n?: number | null) => (n == null ? "—" : Math.round(n).toLocaleString("en-US")),
  kzt: (n?: number | null) => (n == null ? "—" : `${Math.round(n).toLocaleString("en-US")} ₸`),
  pct: (x?: number | null, d = 1) => (x == null ? "—" : `${(100 * x).toFixed(d)}%`),
  n: (x?: number | null, d = 3) => (x == null ? "—" : x.toFixed(d)),
  score: (x?: number | null) => (x == null ? "—" : x < 1 ? x.toFixed(2) : x.toFixed(1)),
  ms: (x?: number | null) => (x == null ? "—" : `${x.toFixed(1)} ms`),
  time: (s?: string | null) => (s ? s.replace("T", " ").slice(0, 16) : "—"),
  action: (a: string) => a.replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase()),
  type: (t: string) => t.replace(/_/g, " "),
};

// ---------- data loading ----------
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: undefined }));
    fn().then((data) => alive && setState({ data, loading: false }))
      .catch((error) => alive && setState({ error, loading: false }));
    return () => { alive = false; };
  }, [...deps, tick]);
  return { ...state, reload: () => setTick((t) => t + 1) };
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return <div className="loading"><span className="spin" />{label}…</div>;
}

export function ErrorBox({ error }: { error: unknown }) {
  const e = error as ApiError;
  const detail = e?.detail ? (typeof e.detail === "string" ? e.detail : JSON.stringify(e.detail, null, 1)) : "";
  return <div className="err"><b>{e?.status ? `Error ${e.status}: ` : ""}</b>{e?.message || String(error)}
    {detail && <pre className="small" style={{ whiteSpace: "pre-wrap", margin: "6px 0 0" }}>{detail}</pre>}</div>;
}

export function Async<T>({ s, children }: { s: { data?: T; error?: Error; loading: boolean }; children: (d: T) => ReactNode }) {
  if (s.error) return <ErrorBox error={s.error} />;
  if (s.data === undefined) return <Loading />;
  return <>{children(s.data)}</>;
}

// ---------- building blocks ----------
export function PageHead({ title, desc, right }: { title: string; desc?: ReactNode; right?: ReactNode }) {
  return <div className="page-head"><div><h1>{title}</h1>{desc && <p>{desc}</p>}</div>{right}</div>;
}

export function Card({ title, sub, right, children, className = "" }:
  { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children?: ReactNode; className?: string }) {
  return <section className={"card " + className}>
    {(title || right) && <div className="card-head"><div>{title && <h2>{title}</h2>}{sub && <p className="sub" style={{ margin: 0 }}>{sub}</p>}</div>{right}</div>}
    {children}
  </section>;
}

export function Stat({ label, value, note, tag }: { label: ReactNode; value: ReactNode; note?: ReactNode; tag?: ReactNode }) {
  return <section className="card"><div className="stat-label">{label}{tag}</div>
    <div className="stat-value">{value}</div>{note && <div className="stat-note">{note}</div>}</section>;
}

export const Syn = () => <span className="tag syn" title="Computed on synthetic data">synthetic</span>;

export function LevelBadge({ level }: { level: Level }) {
  return <span className={"lvl " + level}>{level}</span>;
}

export const levelColor = (l: Level) => (l === "HIGH" ? "var(--high)" : l === "MEDIUM" ? "var(--medium)" : "var(--low)");

export function ScoreBar({ value, color = "var(--accent)" }: { value: number | null; color?: string }) {
  return <div className="bar-track"><div className="bar-fill" style={{ width: `${Math.max(0, Math.min(100, value ?? 0))}%`, background: color }} /></div>;
}

export function ComponentRow({ label, value, note }: { label: string; value: number | null; note?: string }) {
  return <div className="comp-row" title={note}>
    <span className="muted">{label}</span>
    {value == null ? <span className="small muted">unavailable — not inferred</span> : <ScoreBar value={value} color="var(--accent-2)" />}
    <span className="mono" style={{ textAlign: "right" }}>{value == null ? "—" : value.toFixed(0)}</span>
  </div>;
}

export function Factors({ items, empty }: { items: Factor[]; empty: string }) {
  if (!items.length) return <div className="empty">{empty}</div>;
  const max = Math.max(...items.map((f) => Math.abs(f.contribution)), 1);
  return <div>{items.map((f) => (
    <div className="factor" key={f.group}>
      <div><div className="factor-name">{f.label}</div>
        <div className="bar-track" title={`${f.contribution > 0 ? "+" : ""}${f.contribution.toFixed(2)} log-odds`}>
          <div className="bar-fill" style={{ width: `${(100 * Math.abs(f.contribution)) / max}%`, background: f.contribution > 0 ? "var(--high)" : "var(--low)" }} /></div>
        <div className="small muted mono">{f.contribution > 0 ? "+" : ""}{f.contribution.toFixed(2)} log-odds</div></div>
      <div className="factor-detail">{f.detail}</div>
    </div>))}</div>;
}

export function Seg<T extends string>({ value, options, onChange }: { value: T; options: { v: T; label: string }[]; onChange: (v: T) => void }) {
  return <div className="seg">{options.map((o) => (
    <button key={o.v} className={o.v === value ? "on" : ""} onClick={() => onChange(o.v)}>{o.label}</button>))}</div>;
}

export function Json({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}
