// Typed client for the NeuroShield backend. The dashboard is served by the backend itself,
// so all requests are same-origin ("/api/v1/..."): no CORS, no separate frontend server.

export type Level = "LOW" | "MEDIUM" | "HIGH";

export class ApiError extends Error {
  constructor(public status: number, message: string, public detail?: unknown) { super(message); }
}

async function call<T>(path: string, init?: RequestInit & { json?: unknown }): Promise<T> {
  const opts: RequestInit = { ...init, headers: { ...(init?.headers || {}) } };
  if (init?.json !== undefined) {
    opts.body = JSON.stringify(init.json);
    (opts.headers as Record<string, string>)["content-type"] = "application/json";
  }
  let r: Response;
  try { r = await fetch(path, opts); }
  catch { throw new ApiError(0, "Backend is not reachable. Is `python -m backend.app.main` running?"); }
  const text = await r.text();
  let data: any = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON */ }
  if (!r.ok) throw new ApiError(r.status, data?.error || `HTTP ${r.status}`, data?.detail);
  return data as T;
}

const qs = (p: Record<string, unknown>) =>
  "?" + Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");

// ---- types (mirror backend/app/schemas + routes) ---------------------------------------
export interface Factor { group: string; label: string; contribution: number; direction: string; detail: string }
export interface Behavioral {
  score: number | null; status: string; reliability: string; reason?: string;
  rhythm_deviation: number | null; hesitation_index: number | null;
  available_features: string[]; missing_features: string[]; deviations: Record<string, number>;
  baseline_sessions?: number;
}
export interface Signals { score: number; signals: string[]; independent_signal_groups?: number; capped_single_signal?: boolean }
export interface RiskResult {
  transaction_id: string; risk_score: number; risk_level: Level; recommended_action: string; action_description: string;
  confidence: string; confidence_notes: string[]; transaction_model_score: number;
  behavioral_anomaly_score: number | null; graph_risk_score: number; social_engineering_risk_score: number;
  recipient_risk_score: number;
  components: { behavioral: Behavioral; graph: Signals; social_engineering: Signals; recipient: Signals;
    amount_deviation_sigma: number | null; model_output_probability: number };
  top_risk_factors: Factor[]; mitigating_factors: Factor[]; policy_version: string; policy_mode: string;
  thresholds: { medium: number; high: number }; model_version: string; evaluation_warnings: string[];
  processing_ms: number | null; simulated: boolean;
}
export interface TxRow {
  transaction_id: string; event_time: string; source: string; sender_ref: string | null; recipient_ref: string | null;
  amount: number; transaction_type: string; risk_score: number; risk_level: Level; action: string;
  reason: string | null; label: number | null; fraud_type: string | null; legit_tag: string | null;
}
export interface TxDetail extends TxRow {
  result: RiskResult; inputs: Record<string, any>; model_version: string; policy_version: string; policy_mode: string;
  graph_alerts: { alert_id: string; type: string; severity: number; explanation: string }[];
}
export interface Explanation {
  transaction_id: string; risk_score: number; risk_level: Level; recommended_action: string;
  top_risk_factors: Factor[]; mitigating_factors: Factor[]; components: RiskResult["components"];
  confidence: string; confidence_notes: string[]; method: string;
  report: { generator: string; sections: { title: string; items: string[] }[]; facts_vs_interpretation: string };
}
export interface GraphEdge { source: string; target: string; transaction_id: string; amount: number; time: string; risk_score: number; risk_level: Level }
export interface GraphNode { id: string; kind: string; max_outgoing_risk: number; in_degree: number; out_degree: number; alerts: string[]; focus: boolean }
export interface GraphAlert {
  alert_id: string; type: string; focus_node: string; severity: number; explanation: string;
  features_used: Record<string, unknown>; nodes: string[]; edges: GraphEdge[];
}
export interface Scenario { id: string; title: string; story: string; expected: string }
export interface ScenarioRun { scenario: string; title: string; story: string; expected: string; input: any; result: RiskResult; disclaimer: string }

export const api = {
  health: () => call<{ status: string; model_loaded: boolean; transactions_stored: number }>("/health"),
  modelInfo: () => call<any>("/api/v1/model/info"),
  score: (body: unknown) => call<RiskResult>("/api/v1/risk/score", { method: "POST", json: body }),
  transactions: (p: Record<string, unknown>) => call<{ items: TxRow[]; total: number; page: number; page_size: number }>("/api/v1/transactions" + qs(p)),
  transaction: (id: string) => call<TxDetail>(`/api/v1/transactions/${encodeURIComponent(id)}`),
  explanation: (id: string) => call<Explanation>(`/api/v1/transactions/${encodeURIComponent(id)}/explanation`),
  graphAlerts: (p: Record<string, unknown>) => call<{ alerts: GraphAlert[]; total: number; graph: any; method: string }>("/api/v1/graph/alerts" + qs(p)),
  graphView: (p: Record<string, unknown>) => call<{ nodes: GraphNode[]; edges: GraphEdge[] }>("/api/v1/graph/view" + qs(p)),
  summary: () => call<any>("/api/v1/metrics/summary"),
  evaluation: () => call<any>("/api/v1/metrics/evaluation"),
  scenarios: () => call<{ scenarios: Scenario[] }>("/api/v1/demo/scenarios"),
  runScenario: (scenario: string) => call<ScenarioRun>("/api/v1/demo/scenario", { method: "POST", json: { scenario } }),
  audit: (p: Record<string, unknown>) => call<{ items: any[]; total: number }>("/api/v1/audit" + qs(p)),
  settings: () => call<any>("/api/v1/settings"),
  putSettings: (body: unknown, token?: string) => call<any>("/api/v1/settings",
    { method: "PUT", json: body, headers: token ? { "x-admin-token": token } : {} }),
};
