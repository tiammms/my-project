// Small dependency-free SVG charts. Every chart has a hover layer and a text legend;
// colours follow the entity (fixed categorical order), never its rank.
import { useMemo, useRef, useState, type ReactNode } from "react";

export const SERIES_COLORS = ["var(--c1)", "var(--c2)", "var(--c3)", "var(--c4)", "var(--c5)", "var(--c6)"];

function useWidth(initial = 600) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [w, setW] = useState(initial);
  const setRef = (el: HTMLDivElement | null) => {
    if (el && el !== ref.current) {
      ref.current = el;
      const ro = new ResizeObserver(() => setW(el.clientWidth || initial));
      ro.observe(el);
      setW(el.clientWidth || initial);
    }
  };
  return [setRef, w] as const;
}

const ticks = (min: number, max: number, n = 5) => Array.from({ length: n + 1 }, (_, i) => min + ((max - min) * i) / n);

// ---------------------------------------------------------------------------------------
export interface Series { id: string; label: string; color: string; points: [number, number][]; dashed?: boolean }

export function LineChart({ series, xLabel, yLabel, height = 300, diagonal, xFmt = (v) => v.toFixed(1), yFmt = (v) => v.toFixed(2), xMax = 1, yMax = 1 }:
  { series: Series[]; xLabel: string; yLabel: string; height?: number; diagonal?: boolean;
    xFmt?: (v: number) => string; yFmt?: (v: number) => string; xMax?: number; yMax?: number }) {
  const [setRef, w] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const m = { l: 46, r: 14, t: 10, b: 38 };
  const iw = Math.max(50, w - m.l - m.r), ih = height - m.t - m.b;
  const x = (v: number) => m.l + (v / xMax) * iw, y = (v: number) => m.t + ih - (v / yMax) * ih;
  const path = (pts: [number, number][]) => pts.map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join("");
  const at = (pts: [number, number][], xv: number) => {   // step-interpolate the curve at xv
    let best = pts[0];
    for (const p of pts) { if (p[0] <= xv) best = p; else break; }
    return best;
  };
  const xv = hover == null ? null : Math.max(0, Math.min(xMax, ((hover - m.l) / iw) * xMax));
  return <div className="chart-wrap" ref={setRef}>
    <svg width={w} height={height} onMouseMove={(e: any) => setHover(e.nativeEvent.offsetX)} onMouseLeave={() => setHover(null)} role="img" aria-label={`${yLabel} vs ${xLabel}`}>
      {ticks(0, yMax).map((t) => <g key={"y" + t}><line x1={m.l} x2={m.l + iw} y1={y(t)} y2={y(t)} stroke="var(--grid)" />
        <text x={m.l - 6} y={y(t) + 4} textAnchor="end">{yFmt(t)}</text></g>)}
      {ticks(0, xMax).map((t) => <text key={"x" + t} x={x(t)} y={m.t + ih + 16} textAnchor="middle">{xFmt(t)}</text>)}
      <text x={m.l + iw / 2} y={height - 4} textAnchor="middle">{xLabel}</text>
      <text transform={`translate(11 ${m.t + ih / 2}) rotate(-90)`} textAnchor="middle">{yLabel}</text>
      {diagonal && <line x1={x(0)} y1={y(0)} x2={x(xMax)} y2={y(yMax)} stroke="var(--border-strong)" strokeDasharray="4 4" />}
      {series.map((s) => <path key={s.id} d={path(s.points)} fill="none" stroke={s.color} strokeWidth={2}
        strokeDasharray={s.dashed ? "5 4" : undefined} strokeLinejoin="round" />)}
      {xv != null && <line x1={x(xv)} x2={x(xv)} y1={m.t} y2={m.t + ih} stroke="var(--border-strong)" />}
      {xv != null && series.map((s) => { const p = at(s.points, xv); return <circle key={s.id} cx={x(p[0])} cy={y(p[1])} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />; })}
    </svg>
    {xv != null && <div className="tip" style={{ left: Math.min(x(xv) + 12, w - 210), top: 8 }}>
      <div className="muted">{xLabel}: {xFmt(xv)}</div>
      {series.map((s) => <div key={s.id}><span style={{ color: s.color }}>■</span> {s.label}: <b>{yFmt(at(s.points, xv)[1])}</b></div>)}
    </div>}
  </div>;
}

// ---------------------------------------------------------------------------------------
export interface Bar { label: string; value: number; color?: string; note?: string; display?: string }

export function HBars({ bars, max, fmt = (v) => v.toFixed(3), labelWidth = 190 }:
  { bars: Bar[]; max?: number; fmt?: (v: number) => string; labelWidth?: number }) {
  const [tip, setTip] = useState<number | null>(null);
  const mx = max ?? Math.max(...bars.map((b) => b.value), 1e-9);
  return <div>{bars.map((b, i) => (
    <div key={b.label} className="comp-row" style={{ gridTemplateColumns: `${labelWidth}px 1fr 72px`, position: "relative" }}
      onMouseEnter={() => setTip(i)} onMouseLeave={() => setTip(null)}>
      <span className="small" style={{ color: "var(--text-2)" }}>{b.label}</span>
      <div className="bar-track" style={{ height: 10 }}><div className="bar-fill" style={{ width: `${(100 * Math.max(0, b.value)) / mx}%`, background: b.color || "var(--c1)", borderRadius: 4 }} /></div>
      <span className="mono small" style={{ textAlign: "right" }}>{b.display ?? fmt(b.value)}</span>
      {tip === i && b.note && <div className="tip" style={{ left: labelWidth, top: -30 }}>{b.note}</div>}
    </div>))}</div>;
}

// ---------------------------------------------------------------------------------------
export function StackedColumns({ data, keys, colors, height = 220, labels }:
  { data: { x: string; values: Record<string, number> }[]; keys: string[]; colors: Record<string, string>; height?: number; labels?: Record<string, string> }) {
  const [setRef, w] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const m = { l: 44, r: 8, t: 8, b: 28 };
  const iw = Math.max(50, w - m.l - m.r), ih = height - m.t - m.b;
  const totals = data.map((d) => keys.reduce((s, k) => s + (d.values[k] || 0), 0));
  const ymax = useMemo(() => { const mx = Math.max(1, ...totals); const p = Math.pow(10, Math.floor(Math.log10(mx))); return Math.ceil(mx / p) * p; }, [totals.join(",")]);
  const bw = iw / Math.max(1, data.length);
  const y = (v: number) => m.t + ih - (v / ymax) * ih;
  return <div className="chart-wrap" ref={setRef}>
    <svg width={w} height={height} role="img" aria-label="Stacked activity chart">
      {ticks(0, ymax, 4).map((t) => <g key={t}><line x1={m.l} x2={m.l + iw} y1={y(t)} y2={y(t)} stroke="var(--grid)" />
        <text x={m.l - 6} y={y(t) + 4} textAnchor="end">{Math.round(t)}</text></g>)}
      {data.map((d, i) => {
        let acc = 0;
        return <g key={d.x} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
          <rect x={m.l + i * bw} y={m.t} width={bw} height={ih} fill={hover === i ? "rgba(255,255,255,.03)" : "transparent"} />
          {keys.map((k) => {
            const v = d.values[k] || 0; if (!v) return null;
            const y0 = y(acc), y1 = y(acc + v); acc += v;
            return <rect key={k} x={m.l + i * bw + bw * 0.15} width={bw * 0.7} y={y1} height={Math.max(0, y0 - y1 - 1.5)} fill={colors[k]} rx={2} />;
          })}
          {(data.length <= 16 || i % Math.ceil(data.length / 12) === 0) &&
            <text x={m.l + i * bw + bw / 2} y={height - 8} textAnchor="middle">{d.x.slice(5)}</text>}
        </g>;
      })}
    </svg>
    {hover != null && <div className="tip" style={{ left: Math.min(m.l + hover * bw + bw, w - 170), top: 6 }}>
      <b>{data[hover].x}</b> — {totals[hover]}
      {keys.slice().reverse().map((k) => <div key={k}><span style={{ color: colors[k] }}>■</span> {labels?.[k] ?? k}: {data[hover].values[k] || 0}</div>)}
    </div>}
    <div className="legend">{keys.map((k) => <span key={k}><i style={{ background: colors[k], height: 8, width: 8 }} />{labels?.[k] ?? k}</span>)}</div>
  </div>;
}

export function Legend({ items, off, onToggle }: { items: { id: string; label: string; color: string; dashed?: boolean }[]; off?: Set<string>; onToggle?: (id: string) => void }) {
  return <div className="legend">{items.map((it) => {
    const inner: ReactNode = <><i style={{ background: it.dashed ? `repeating-linear-gradient(90deg, ${it.color} 0 5px, transparent 5px 8px)` : it.color }} />{it.label}</>;
    return onToggle ? <button key={it.id} className={off?.has(it.id) ? "off" : ""} onClick={() => onToggle(it.id)} title="Show / hide">{inner}</button>
      : <span key={it.id}>{inner}</span>;
  })}</div>;
}
