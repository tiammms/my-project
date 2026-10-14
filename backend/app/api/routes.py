"""REST endpoints for NeuroShield API v1."""
from __future__ import annotations

import json
import time
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

from ..core.config import settings
from ..database.db import now_iso
from ..schemas.api import BatchIn, BatchOut, RiskResult, ScenarioIn, SettingsIn, TransactionIn
from ..services import reports, scenarios
from ..services.risk_engine import tx_to_raw
from .framework import API, HTTPError

api = API("NeuroShield API", "1.0.0",
          "Explainable risk scoring for transfer fraud - research prototype on SYNTHETIC data. "
          "Recommendations are simulated and not connected to any payment system.")
STATE: dict = {}   # filled by main.create_app: db, engine, graph


class TxQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    page: int = Field(1, ge=1, le=100000)
    page_size: int = Field(25, ge=1, le=200)
    q: Optional[str] = Field(None, max_length=40, pattern=r"^[A-Za-z0-9_\-]*$", description="Search transaction/sender/recipient id")
    risk_level: Optional[Literal["LOW", "MEDIUM", "HIGH"]] = None
    action: Optional[str] = Field(None, max_length=40, pattern=r"^[A-Z_]*$")
    source: Optional[Literal["dataset", "api", "scenario"]] = None
    transaction_type: Optional[Literal["card_payment", "bill_payment", "p2p_transfer", "international_transfer"]] = None
    min_score: float = Field(0, ge=0, le=100)
    max_score: float = Field(100, ge=0, le=100)
    sort: Literal["risk_score", "amount", "event_time", "created_at"] = "event_time"
    order: Literal["asc", "desc"] = "desc"


class AuditQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    page: int = Field(1, ge=1, le=100000)
    page_size: int = Field(50, ge=1, le=200)
    event_type: Optional[str] = Field(None, max_length=40, pattern=r"^[a-z_]*$")


class AlertQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Optional[Literal["fan_in", "rapid_chain", "shared_recipient"]] = None
    min_severity: float = Field(0, ge=0, le=100)
    limit: int = Field(50, ge=1, le=500)


class GraphViewQuery(BaseModel):
    model_config = ConfigDict(extra="forbid")
    node: Optional[str] = Field(None, max_length=40, pattern=r"^[A-Za-z0-9_\-]+$")
    min_severity: float = Field(0, ge=0, le=100)
    max_alerts: int = Field(40, ge=1, le=200)


def _row_summary(r: dict) -> dict:
    res = json.loads(r["result_json"])
    top = res.get("top_risk_factors") or []
    return {"transaction_id": r["transaction_id"], "event_time": r["event_time"], "source": r["source"],
            "sender_ref": r["sender_ref"], "recipient_ref": r["recipient_ref"], "amount": r["amount"],
            "transaction_type": r["transaction_type"], "risk_score": r["risk_score"], "risk_level": r["risk_level"],
            "action": r["action"], "reason": top[0]["label"] + ": " + top[0]["detail"] if top else None,
            "label": r["label"], "fraud_type": r["fraud_type"], "legit_tag": r["legit_tag"]}


def _store(raw: dict, res: dict, source: str, tx: TransactionIn | None = None, event_time: str | None = None):
    db = STATE["db"]
    db.insert_transactions([{
        "transaction_id": res["transaction_id"], "created_at": now_iso(), "source": source,
        "event_time": event_time or now_iso(), "sender_ref": tx.sender_ref if tx else None,
        "recipient_ref": tx.recipient_ref if tx else None, "amount": raw["amount"],
        "transaction_type": raw["transaction_type"], "raw_json": json.dumps(raw), "label": None,
        "fraud_type": None, "legit_tag": None, "risk_score": res["risk_score"], "risk_level": res["risk_level"],
        "action": res["recommended_action"], "model_version": res["model_version"],
        "policy_version": res["policy_version"], "policy_mode": res["policy_mode"],
        "result_json": json.dumps(res), "explanation_json": None}])
    db.audit("decision", {"risk_score": res["risk_score"], "risk_level": res["risk_level"],
                          "action": res["recommended_action"], "policy_mode": res["policy_mode"],
                          "model_version": res["model_version"], "source": source},
             actor=source, transaction_id=res["transaction_id"])


def _score_tx(tx: TransactionIn, source: str) -> dict:
    db = STATE["db"]
    if tx.transaction_id and db.get_transaction(tx.transaction_id):
        raise HTTPError(409, "transaction_id already exists", tx.transaction_id)
    raw = tx_to_raw(tx)
    res = STATE["engine"].score(raw, tx.transaction_id)
    if tx.persist:
        _store(raw, res, source, tx)
    return res


# ---------------------------------------------------------------------------
@api.route("/health", summary="Liveness / readiness", tags=["system"])
def health():
    e = STATE.get("engine")
    return {"status": "ok" if e else "degraded", "model_loaded": e is not None,
            "transactions_stored": STATE["db"].count_transactions(), "time": now_iso()}


@api.route("/api/v1/model/info", summary="Model and policy metadata", tags=["model"])
def model_info():
    e = STATE["engine"]
    ev = STATE.get("evaluation") or {}
    return {"model_version": e.model_version, "model_type": "HistGradientBoostingClassifier (scikit-learn), hybrid features",
            "trained_at": e.trained_at, "status": "loaded", "features": e.features,
            "hyperparameters": e.bundle_meta.get("hyperparameters"), "sklearn_version": e.bundle_meta.get("sklearn"),
            "training_data": {"synthetic": True, **(ev.get("dataset") or {})},
            "policy_version": e.policy["policy_version"], "policy_mode": e.mode, "thresholds": e.thresholds,
            "available_modes": {k: v for k, v in e.policy["modes"].items()},
            "hold_recommendations_enabled": e.hold_enabled,
            "explainability": "Group occlusion attribution against a reference legitimate profile (not SHAP)",
            "calibration": (ev.get("calibration") or {}).get("ece") and {
                "ece": ev["calibration"]["ece"], "brier": ev["calibration"]["brier"],
                "note": "Measured on the synthetic test split only"},
            "limitations": [
                "Trained and evaluated on synthetic data generated by ml/data/generate.py; real-world performance is unknown.",
                "Behavioral, graph and social-engineering components are deterministic heuristics whose design matches "
                "the generator's assumptions; sensitivity runs (experiments/results/sensitivity_summary.json) quantify this.",
                "Risk score is a model output x100; it is reasonably calibrated on synthetic test data but should be "
                "treated as a ranking score.",
                "Not certified under PCI DSS, ISO 27001 or any regulation; not connected to any bank."]}


@api.route("/api/v1/risk/score", "POST", summary="Score one transaction", tags=["risk"], body=TransactionIn,
           response=RiskResult, errors=(400, 409, 422))
def risk_score(body: TransactionIn):
    """Runs featurization, the hybrid model, component scorers, the policy engine and the explanation."""
    return _score_tx(body, "api")


@api.route("/api/v1/risk/batch", "POST", summary="Score up to NS_BATCH_LIMIT transactions", tags=["risk"],
           body=BatchIn, response=BatchOut, errors=(400, 409, 413, 422))
def risk_batch(body: BatchIn):
    if len(body.transactions) > STATE["settings"].batch_limit:
        raise HTTPError(413, f"Batch exceeds limit of {STATE["settings"].batch_limit} transactions", len(body.transactions))
    ids = [t.transaction_id for t in body.transactions if t.transaction_id]
    if len(ids) != len(set(ids)):
        raise HTTPError(422, "Duplicate transaction_id in batch")
    t0 = time.perf_counter()
    out = [_score_tx(t, "api") for t in body.transactions]
    return {"results": out, "count": len(out), "processing_ms": round((time.perf_counter() - t0) * 1000, 2)}


@api.route("/api/v1/transactions", summary="List stored transactions (pagination, search, filters)",
           tags=["transactions"], query=TxQuery)
def list_tx(query: TxQuery):
    where, params = ["risk_score >= ?", "risk_score <= ?"], [query.min_score, query.max_score]
    for col in ("risk_level", "action", "source", "transaction_type"):
        v = getattr(query, col)
        if v:
            where.append(f"{col} = ?"); params.append(v)
    if query.q:
        where.append("(transaction_id LIKE ? OR sender_ref LIKE ? OR recipient_ref LIKE ?)")
        params += [f"%{query.q}%"] * 3
    w = " AND ".join(where)
    db = STATE["db"]
    total = db.scalar(f"SELECT COUNT(*) FROM transactions WHERE {w}", tuple(params))
    rows = db.query(f"SELECT * FROM transactions WHERE {w} ORDER BY {query.sort} {query.order.upper()}, transaction_id "
                    f"LIMIT ? OFFSET ?", tuple(params) + (query.page_size, (query.page - 1) * query.page_size))
    return {"items": [_row_summary(r) for r in rows], "total": total, "page": query.page, "page_size": query.page_size}


def _get(tx_id: str) -> dict:
    if len(tx_id) > 40:
        raise HTTPError(404, "Transaction not found")
    r = STATE["db"].get_transaction(tx_id)
    if not r:
        raise HTTPError(404, "Transaction not found")
    return r


@api.route("/api/v1/transactions/{transaction_id}", summary="Transaction details", tags=["transactions"], errors=(404,))
def get_tx(transaction_id: str):
    r = _get(transaction_id)
    res = json.loads(r["result_json"])
    raw = json.loads(r["raw_json"])
    g = STATE["graph"]
    return {**_row_summary(r), "result": res, "inputs": raw, "model_version": r["model_version"],
            "policy_version": r["policy_version"], "policy_mode": r["policy_mode"],
            "graph_alerts": [{"alert_id": a["alert_id"], "type": a["type"], "severity": a["severity"],
                              "explanation": a["explanation"]} for a in g.node_alerts(r["recipient_ref"] or "")][:10]
            if r["recipient_ref"] else []}


@api.route("/api/v1/transactions/{transaction_id}/explanation", summary="Explanation of the decision",
           tags=["transactions"], errors=(404,))
def explanation(transaction_id: str):
    r = _get(transaction_id)
    if r["explanation_json"]:
        return json.loads(r["explanation_json"])
    raw = json.loads(r["raw_json"])
    res = json.loads(r["result_json"])
    if not res.get("top_risk_factors") and not res.get("mitigating_factors"):
        # dataset rows are bulk-scored without explanations; compute lazily with the stored policy context
        full = STATE["engine"].score(raw, transaction_id)
        res.update({k: full[k] for k in ("top_risk_factors", "mitigating_factors", "components", "confidence",
                                         "confidence_notes", "evaluation_warnings")})
        STATE["db"].execute("UPDATE transactions SET result_json = ? WHERE transaction_id = ?", (json.dumps(res), transaction_id))
    exp = {"transaction_id": transaction_id, "risk_score": res["risk_score"], "risk_level": res["risk_level"],
           "recommended_action": res["recommended_action"], "top_risk_factors": res["top_risk_factors"],
           "mitigating_factors": res["mitigating_factors"], "components": res["components"],
           "confidence": res.get("confidence"), "confidence_notes": res.get("confidence_notes"),
           "method": "Group occlusion: each feature group is replaced by a typical legitimate value and the change in "
                     "model log-odds is reported. Not SHAP; contributions need not sum to the total.",
           "report": reports.build_report(r, raw, res)}
    STATE["db"].set_explanation(transaction_id, exp)
    return exp


@api.route("/api/v1/graph/alerts", summary="Suspicious transaction structures", tags=["graph"], query=AlertQuery)
def graph_alerts(query: AlertQuery):
    g = STATE["graph"]
    al = [a for a in g.alerts if a["severity"] >= query.min_severity and (not query.type or a["type"] == query.type)]
    return {"alerts": al[:query.limit], "total": len(al), "graph": g.stats(),
            "method": "NetworkX graph algorithms over stored demo transfers (fan-in windows, rapid forwarding chains, "
                      "shared high-risk recipients). Not a graph neural network."}


@api.route("/api/v1/graph/view", summary="Nodes/edges for visualisation (alert overview or one node's neighbourhood)",
           tags=["graph"], query=GraphViewQuery)
def graph_view(query: GraphViewQuery):
    g = STATE["graph"]
    return g.neighborhood(query.node) if query.node else g.overview(query.min_severity, query.max_alerts)


@api.route("/api/v1/metrics/summary", summary="Operational summary of stored (synthetic) transactions", tags=["metrics"])
def metrics_summary():
    db, e = STATE["db"], STATE["engine"]
    total = db.count_transactions()
    by_level = {r["risk_level"]: r["n"] for r in db.query("SELECT risk_level, COUNT(*) n FROM transactions GROUP BY risk_level")}
    by_source = {r["source"]: r["n"] for r in db.query("SELECT source, COUNT(*) n FROM transactions GROUP BY source")}
    lab = db.query("SELECT label, risk_level FROM transactions WHERE label IS NOT NULL")
    labelled = None
    if lab:
        tp = sum(1 for r in lab if r["label"] == 1 and r["risk_level"] != "LOW")
        fp = sum(1 for r in lab if r["label"] == 0 and r["risk_level"] != "LOW")
        fn = sum(1 for r in lab if r["label"] == 1 and r["risk_level"] == "LOW")
        tn = sum(1 for r in lab if r["label"] == 0 and r["risk_level"] == "LOW")
        labelled = {"n": len(lab), "positives": tp + fn, "flagged_definition": "risk_level MEDIUM or HIGH",
                    "precision": tp / (tp + fp) if tp + fp else None, "recall": tp / (tp + fn) if tp + fn else None,
                    "false_positive_rate": fp / (fp + tn) if fp + tn else None,
                    "note": "Demo set = synthetic test split with ALL frauds kept and legitimate transfers sub-sampled, "
                            "so precision here is higher than at natural prevalence. See /metrics/evaluation for the "
                            "unbiased test-set numbers."}
    activity = db.query("SELECT substr(event_time,1,10) day, risk_level, COUNT(*) n FROM transactions "
                        "WHERE source='dataset' GROUP BY day, risk_level ORDER BY day")
    ev = STATE.get("evaluation") or {}
    hy = (ev.get("systems") or {}).get(ev.get("winner", ""), {})
    beh_missing = db.scalar("SELECT COUNT(*) FROM transactions WHERE result_json LIKE '%\"behavioral_anomaly_score\": null%'")
    warnings = ["All data is synthetic (generated by ml/data/generate.py)."]
    if total:
        warnings.append(f"Behavioral component unavailable for {beh_missing / total:.0%} of stored transactions "
                        "(no consent, card-free flows or short baselines).")
    return {"processed": total, "by_level": by_level, "by_source": by_source,
            "suspicious": by_level.get("MEDIUM", 0) + by_level.get("HIGH", 0),
            "labelled_demo_metrics": labelled,
            "test_set_metrics": {"roc_auc": (hy.get("test") or {}).get("roc_auc"), "pr_auc": (hy.get("test") or {}).get("pr_auc"),
                                 "source": "experiments/results/evaluation_default.json"},
            "live_latency_ms": e.latency.stats(), "activity": activity,
            "policy_mode": e.mode, "thresholds": e.thresholds, "data_quality_warnings": warnings}


@api.route("/api/v1/metrics/evaluation", summary="Offline evaluation results (synthetic test split)", tags=["metrics"])
def metrics_evaluation():
    ev = STATE.get("evaluation")
    if not ev:
        raise HTTPError(404, "No evaluation results found; run python -m ml.training.train")
    rd = STATE["settings"].results_dir
    def opt(name):
        p = rd / name
        return json.loads(p.read_text()) if p.exists() else None
    return {**ev, "sensitivity": opt("sensitivity_summary.json"), "api_latency": opt("api_latency.json"),
            "fairness": opt("fairness_young_recipient.json"), "adversary": opt("adversary_stress.json"), "load_test": opt("load_test.json")}


@api.route("/api/v1/demo/scenarios", summary="List prepared demo scenarios", tags=["demo"])
def demo_list():
    return {"scenarios": [{"id": k, "title": v["title"], "story": v["story"], "expected": v["expected"]}
                          for k, v in scenarios.SCENARIOS.items()]}


@api.route("/api/v1/demo/scenario", "POST", summary="Run a prepared synthetic scenario through the Risk Engine",
           tags=["demo"], body=ScenarioIn, errors=(422,))
def demo_run(body: ScenarioIn):
    payload = scenarios.scenario_payload(body.scenario)
    tx = TransactionIn.model_validate(payload)
    res = _score_tx(tx, "scenario")
    s = scenarios.SCENARIOS[body.scenario]
    return {"scenario": body.scenario, "title": s["title"], "story": s["story"], "expected": s["expected"],
            "input": payload, "result": res,
            "disclaimer": "Synthetic experiment. It does not detect real stress or confirm fraud."}


@api.route("/api/v1/audit", summary="Decision and policy-change log", tags=["audit"], query=AuditQuery)
def audit(query: AuditQuery):
    db = STATE["db"]
    w, p = ("WHERE event_type = ?", (query.event_type,)) if query.event_type else ("", ())
    total = db.scalar(f"SELECT COUNT(*) FROM audit_log {w}", p)
    rows = db.query(f"SELECT * FROM audit_log {w} ORDER BY id DESC LIMIT ? OFFSET ?",
                    p + (query.page_size, (query.page - 1) * query.page_size))
    for r in rows:
        r["details"] = json.loads(r.pop("details_json"))
    return {"items": rows, "total": total, "page": query.page}


@api.route("/api/v1/settings", summary="Current policy settings", tags=["settings"])
def get_settings():
    e = STATE["engine"]
    return {"mode": e.mode, "thresholds": e.thresholds, "hold_recommendations_enabled": e.hold_enabled,
            "modes": e.policy["modes"], "policy_version": e.policy["policy_version"],
            "demo_safe_mode": True, "demo_safe_mode_note": "Always on in the MVP: no payment system is connected.",
            "admin_token_required": bool(STATE["settings"].admin_token),
            "young_recipient_override": {"available": bool(e.young_override), "enabled": e.young_override_enabled,
                                         **(e.young_override or {}), "tradeoff": _fairness_summary()}}


def _fairness_summary():
    p = STATE["settings"].results_dir / "fairness_young_recipient.json"
    if not p.exists():
        return None
    d = json.loads(p.read_text()).get("deployed_model_threshold_tradeoff") or {}
    rows = d.get("rows") or []
    chosen = next((r for r in rows if r["target"] == d.get("chosen_by_validation_rule")), None)
    return {"shared": rows[0]["test"] if rows else None, "override": chosen["test"] if chosen else None,
            "note": "Synthetic test split. Override lowers false alarms for customers paying new accounts but may miss frauds to young accounts."}


@api.route("/api/v1/settings", "PUT", summary="Change policy mode / hold recommendations (audited)", tags=["settings"],
           body=SettingsIn, errors=(401, 422))
def put_settings(body: SettingsIn, request):
    if STATE["settings"].admin_token and request.headers.get("x-admin-token") != STATE["settings"].admin_token:
        raise HTTPError(401, "Admin token required")
    e, db = STATE["engine"], STATE["db"]
    before = {"mode": e.mode, "hold_recommendations_enabled": e.hold_enabled, "young_recipient_override": e.young_override_enabled}
    if body.mode:
        e.mode = body.mode
    if body.hold_recommendations_enabled is not None:
        e.hold_enabled = body.hold_recommendations_enabled
    if body.young_recipient_override is not None:
        if not e.young_override:
            raise HTTPError(422, "No young-recipient override in the loaded policy (run ml.evaluation.fairness_experiment)")
        e.young_override_enabled = body.young_recipient_override
    after = {"mode": e.mode, "hold_recommendations_enabled": e.hold_enabled, "young_recipient_override": e.young_override_enabled}
    db.set_setting("policy", after)
    n = relabel_dataset()
    db.audit("policy_change", {"before": before, "after": after, "thresholds": e.thresholds,
                               "dataset_rows_reevaluated": n}, actor="admin")
    return get_settings()


def relabel_dataset() -> int:
    """Re-apply current thresholds to bulk-loaded dataset rows (API/scenario decisions stay historical)."""
    e, db = STATE["engine"], STATE["db"]
    t = e.thresholds
    hold = "REVIEW_OR_TEMPORARY_HOLD" if e.hold_enabled else "MANUAL_REVIEW"
    young_sql, young_med = "0", t["medium"]
    if e.young_override and e.young_override_enabled and e.young_override.get("mode") == e.mode:
        young_sql = f"(json_extract(raw_json, '$.recipient_age_days') IS NOT NULL AND json_extract(raw_json, '$.recipient_age_days') < {float(e.young_override['max_recipient_age_days'])})"
        young_med = min(e.young_override["medium_threshold"], t["high"])
    med = f"(CASE WHEN {young_sql} THEN ? ELSE ? END)"
    db.execute(f"UPDATE transactions SET risk_level = CASE WHEN risk_score >= ? THEN 'HIGH' WHEN risk_score >= {med} "
               f"THEN 'MEDIUM' ELSE 'LOW' END, action = CASE WHEN risk_score >= ? THEN ? WHEN risk_score >= {med} THEN "
               "'STEP_UP_VERIFICATION' ELSE 'ALLOW' END, policy_mode = ? WHERE source = 'dataset'",
               (t["high"], young_med, t["medium"], t["high"], hold, young_med, t["medium"], e.mode))
    rows = db.query("SELECT transaction_id, risk_level, action, result_json, raw_json FROM transactions WHERE source='dataset'")
    for r in rows:
        res = json.loads(r["result_json"])
        res.update({"risk_level": r["risk_level"], "recommended_action": r["action"], "policy_mode": e.mode,
                    "thresholds": e.thresholds_for(json.loads(r["raw_json"]))})
        r["result_json"] = json.dumps(res)
    with db._lock:
        db._conn.executemany("UPDATE transactions SET result_json = ?, explanation_json = NULL WHERE transaction_id = ?",
                             [(r["result_json"], r["transaction_id"]) for r in rows])
        db._conn.commit()
    return len(rows)
