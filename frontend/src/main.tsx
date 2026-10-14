import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import { api } from "./api";
import { Overview } from "./pages/Overview";
import { Transactions } from "./pages/Transactions";
import { TxDetail } from "./pages/TxDetail";
import { GraphPage } from "./pages/Graph";
import { DemoLab } from "./pages/DemoLab";
import { Evaluation } from "./pages/Evaluation";
import { Playground } from "./pages/Playground";
import { Settings } from "./pages/Settings";

// ---- hash router: #/page/arg?key=value ----------------------------------------------
export interface Route { page: string; arg?: string; params: URLSearchParams }
function parse(): Route {
  const h = window.location.hash.replace(/^#\/?/, "");
  const [path, query = ""] = h.split("?");
  const [page = "overview", arg] = path.split("/").map(decodeURIComponent);
  return { page: page || "overview", arg, params: new URLSearchParams(query) };
}
export const go = (path: string) => { window.location.hash = path; };

const I = (d: string) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>;
const NAV = [
  { page: "overview", label: "Overview", icon: I("M3 13h8V3H3zM13 21h8V11h-8zM3 21h8v-6H3zM13 9h8V3h-8z") },
  { page: "transactions", label: "Transactions", icon: I("M4 6h16M4 12h16M4 18h10") },
  { page: "graph", label: "Network graph", icon: I("M6 6m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0M18 7m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0M12 18m-2 0a2 2 0 1 0 4 0a2 2 0 1 0-4 0M8 6.5l8 .5M7 8l4 8.3M17 9l-4 7.3") },
  { page: "demo", label: "Demo Lab", icon: I("M9 3v6L4 18a2 2 0 0 0 1.8 3h12.4A2 2 0 0 0 20 18l-5-9V3M8 3h8M7 14h10") },
  { page: "evaluation", label: "Model evaluation", icon: I("M4 20V10M10 20V4M16 20v-7M22 20H2") },
  { page: "playground", label: "API playground", icon: I("M8 9l-4 3 4 3M16 9l4 3-4 3M13 6l-2 12") },
  { page: "settings", label: "Settings & model card", icon: I("M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z") },
];

function App() {
  const [route, setRoute] = useState<Route>(parse());
  const [health, setHealth] = useState<string>("checking");
  useEffect(() => {
    const on = () => { setRoute(parse()); window.scrollTo(0, 0); };
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  useEffect(() => {
    const check = () => api.health().then((h) => setHealth(h.model_loaded ? "online" : "degraded")).catch(() => setHealth("offline"));
    check();
    const t = setInterval(check, 15000);
    return () => clearInterval(t);
  }, []);
  const active = route.page === "tx" ? "transactions" : route.page;
  let page;
  switch (route.page) {
    case "transactions": page = <Transactions route={route} />; break;
    case "tx": page = <TxDetail id={route.arg || ""} />; break;
    case "graph": page = <GraphPage route={route} />; break;
    case "demo": page = <DemoLab />; break;
    case "evaluation": page = <Evaluation />; break;
    case "playground": page = <Playground />; break;
    case "settings": page = <Settings />; break;
    default: page = <Overview />;
  }
  const dot = health === "online" ? "var(--low)" : health === "checking" ? "var(--muted)" : "var(--high)";
  return <div className="app">
    <aside className="side">
      <div className="brand">
        <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true"><path d="M16 2 4 7v8c0 7.5 5.1 13.4 12 15 6.9-1.6 12-7.5 12-15V7z" fill="#2cc5b0" /><path d="M10 16.5l4 4 8-9" stroke="#0b1016" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        <div><div className="brand-name">NeuroShield</div><div className="brand-sub">Fraud risk intelligence</div></div>
      </div>
      <nav className="nav">{NAV.map((n) => (
        <a key={n.page} href={"#/" + n.page} className={active === n.page ? "active" : ""} title={n.label}>{n.icon}<span>{n.label}</span></a>))}
      </nav>
      <div className="side-foot">
        <div style={{ display: "flex", gap: 6, alignItems: "center" }}><span style={{ width: 8, height: 8, borderRadius: 99, background: dot }} />API {health}</div>
        <div style={{ marginTop: 6 }}>Research prototype · synthetic data · not connected to any bank</div>
      </div>
    </aside>
    <main className="main">{page}</main>
  </div>;
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
