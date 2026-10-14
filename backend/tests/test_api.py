"""API, Risk Engine and safety tests. Run: python -m pytest backend/tests  (or python -m unittest discover backend/tests)"""
from __future__ import annotations

import copy
import dataclasses
import json
import os
import tempfile
import unittest
from pathlib import Path

os.environ["NS_NO_AUTOAPP"] = "1"
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("NS_LOG_LEVEL", "WARNING")

import numpy as np
import pandas as pd

from backend.app.core.config import settings
from backend.app.main import create_app
from backend.app.services.scenarios import scenario_payload
from backend.tests.asgi_client import Client

TMP = tempfile.mkdtemp(prefix="ns-test-")
CFG = dataclasses.replace(settings, admin_token="", rate_limit_per_minute=100000, frontend_dist=Path(TMP) / "nofrontend")
APP = create_app(CFG, db_path=str(Path(TMP) / "test.db"))
C = Client(APP)

VALID = scenario_payload("normal_transfer")


def score(body, expect=200):
    r = C.post("/api/v1/risk/score", json=body)
    assert r.status_code == expect, (r.status_code, r.content[:500])
    return r.json()


class TestSystem(unittest.TestCase):
    def test_health(self):
        d = C.get("/health").json()
        self.assertEqual(d["status"], "ok")
        self.assertGreater(d["transactions_stored"], 1000)

    def test_model_info(self):
        d = C.get("/api/v1/model/info").json()
        self.assertTrue(d["training_data"]["synthetic"])
        self.assertIn("not SHAP", d["explainability"])
        self.assertIn("medium", d["thresholds"])

    def test_openapi(self):
        d = C.get("/openapi.json").json()
        for p in ["/api/v1/risk/score", "/api/v1/risk/batch", "/api/v1/transactions", "/api/v1/transactions/{transaction_id}",
                  "/api/v1/transactions/{transaction_id}/explanation", "/api/v1/graph/alerts", "/api/v1/metrics/summary",
                  "/api/v1/metrics/evaluation", "/api/v1/demo/scenario", "/api/v1/audit", "/api/v1/model/info", "/health"]:
            self.assertIn(p, d["paths"], p)
        self.assertIn("TransactionIn", d["components"]["schemas"])

    def test_security_headers(self):
        r = C.get("/health")
        for h in ("x-content-type-options", "x-frame-options", "x-request-id", "content-security-policy"):
            self.assertIn(h, r.headers)


class TestScoring(unittest.TestCase):
    def test_valid_score_shape(self):
        d = score(VALID)
        self.assertTrue(0 <= d["risk_score"] <= 100)
        self.assertIn(d["risk_level"], ("LOW", "MEDIUM", "HIGH"))
        for k in ("transaction_id", "confidence", "top_risk_factors", "policy_version", "model_version",
                  "evaluation_warnings", "behavioral_anomaly_score", "graph_risk_score", "social_engineering_risk_score"):
            self.assertIn(k, d)
        self.assertTrue(d["simulated"])

    def test_deterministic(self):
        b = {**VALID, "persist": False}
        self.assertEqual(score(b)["risk_score"], score(b)["risk_score"])

    def test_missing_behavioral_not_invented(self):
        b = copy.deepcopy(VALID); b.pop("behavioral"); b["persist"] = False
        d = score(b)
        self.assertIsNone(d["behavioral_anomaly_score"])
        self.assertEqual(d["components"]["behavioral"]["status"], "unavailable")
        self.assertFalse(any(f["group"] == "behavior" for f in d["top_risk_factors"]))

    def test_no_consent_ignores_session(self):
        b = copy.deepcopy(VALID); b["behavioral"]["consent"] = False; b["persist"] = False
        d = score(b)
        self.assertIsNone(d["behavioral_anomaly_score"])
        self.assertIn("consent", d["components"]["behavioral"]["reason"].lower())

    def test_missing_optional_fields_minimal_payload(self):
        d = score({"amount": 5000, "transaction_type": "bill_payment", "transaction_hour": 10,
                   "account_age_days": 300, "new_recipient": False, "persist": False})
        self.assertEqual(d["confidence"] in ("low", "medium", "high"), True)

    def test_parity_with_offline_pipeline(self):
        """API score for a stored demo row must equal the offline featurize+model score."""
        import joblib
        from backend.app.services.features import featurize
        df = pd.read_csv(settings.demo_data_path).head(50)
        b = joblib.load(settings.model_path)
        recs = df.astype(object).where(pd.notna(df), None).to_dict("records")
        X = np.array([[featurize(r)[0][f] for f in b["features"]] for r in recs])
        offline = 100 * b["model"].predict_proba(X)[:, 1]
        for r, s in zip(recs[:20], offline[:20]):
            d = C.get(f"/api/v1/transactions/{r['transaction_id']}").json()
            self.assertAlmostEqual(d["risk_score"], round(float(s), 3), places=3)


class TestValidation(unittest.TestCase):
    def test_negative_amount(self):
        d = score({**VALID, "amount": -5}, 422)
        self.assertEqual(d["error"], "Validation failed")
        self.assertNotIn("input", json.dumps(d["detail"]))   # values are not echoed back

    def test_unknown_field(self):
        score({**VALID, "card_number": "4111111111111111"}, 422)

    def test_bad_enum_and_hour(self):
        score({**VALID, "transaction_type": "crypto"}, 422)
        score({**VALID, "transaction_hour": 24}, 422)

    def test_velocity_consistency(self):
        score({**VALID, "transactions_last_1h": 5, "transactions_last_24h": 2}, 422)

    def test_invalid_json(self):
        r = C.request("POST", "/api/v1/risk/score", raw=b"{not json")
        self.assertEqual(r.status_code, 400)

    def test_body_too_large(self):
        r = C.request("POST", "/api/v1/risk/score", raw=b"{" + b" " * (CFG.max_body_bytes + 10) + b"}")
        self.assertEqual(r.status_code, 413)

    def test_bad_id_pattern(self):
        score({**VALID, "transaction_id": "x'; DROP TABLE transactions;--"}, 422)

    def test_duplicate_id(self):
        score({**VALID, "transaction_id": "DUP-1"})
        score({**VALID, "transaction_id": "DUP-1"}, 409)

    def test_batch(self):
        r = C.post("/api/v1/risk/batch", json={"transactions": [{**VALID, "persist": False}] * 3})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["count"], 3)
        r = C.post("/api/v1/risk/batch", json={"transactions": [{**VALID, "persist": False}] * (CFG.batch_limit + 1)})
        self.assertEqual(r.status_code, 413)
        r = C.post("/api/v1/risk/batch", json={"transactions": []})
        self.assertEqual(r.status_code, 422)

    def test_query_validation(self):
        self.assertEqual(C.get("/api/v1/transactions", params={"page_size": 5000}).status_code, 422)
        self.assertEqual(C.get("/api/v1/transactions", params={"q": "' OR 1=1"}).status_code, 422)
        self.assertEqual(C.get("/api/v1/transactions/NOPE").status_code, 404)


class TestScenarios(unittest.TestCase):
    def run_s(self, name):
        r = C.post("/api/v1/demo/scenario", json={"scenario": name})
        self.assertEqual(r.status_code, 200)
        return r.json()["result"]

    def test_scenario_a_low(self):
        d = self.run_s("normal_transfer")
        self.assertEqual(d["risk_level"], "LOW"); self.assertEqual(d["recommended_action"], "ALLOW")

    def test_scenario_b_high(self):
        d = self.run_s("social_engineering")
        self.assertEqual(d["risk_level"], "HIGH")
        self.assertGreaterEqual(d["social_engineering_risk_score"], 50)

    def test_legitimate_unusual_not_high(self):
        """False-positive guardrails (section 12): legitimate unusual behaviour must not reach HIGH."""
        for s in ("legit_large_first_transfer", "traveling_customer", "new_phone", "changed_typing_habits"):
            self.assertNotEqual(self.run_s(s)["risk_level"], "HIGH", s)

    def test_no_behavioral_data_scenario(self):
        d = self.run_s("no_behavioral_data")
        self.assertIsNone(d["behavioral_anomaly_score"])

    def test_unknown_scenario(self):
        self.assertEqual(C.post("/api/v1/demo/scenario", json={"scenario": "hack"}).status_code, 422)


class TestDataViews(unittest.TestCase):
    def test_list_filter_sort(self):
        d = C.get("/api/v1/transactions", params={"risk_level": "HIGH", "sort": "risk_score", "page_size": 10}).json()
        self.assertTrue(all(i["risk_level"] == "HIGH" for i in d["items"]))
        s = [i["risk_score"] for i in d["items"]]
        self.assertEqual(s, sorted(s, reverse=True))

    def test_explanation_and_report(self):
        tid = C.get("/api/v1/transactions", params={"risk_level": "HIGH", "page_size": 1}).json()["items"][0]["transaction_id"]
        e = C.get(f"/api/v1/transactions/{tid}/explanation").json()
        self.assertGreater(len(e["top_risk_factors"]), 0)
        self.assertEqual(len(e["report"]["sections"]), 6)
        self.assertIn("no LLM", e["report"]["generator"])

    def test_graph_alerts(self):
        d = C.get("/api/v1/graph/alerts", params={"limit": 5}).json()
        self.assertGreater(d["total"], 0)
        a = d["alerts"][0]
        self.assertTrue(a["nodes"] and a["edges"] and a["explanation"])
        self.assertNotIn("dropper", json.dumps(d).lower())
        v = C.get("/api/v1/graph/view", params={"node": a["focus_node"]}).json()
        self.assertTrue(v["nodes"])

    def test_metrics(self):
        s = C.get("/api/v1/metrics/summary").json()
        self.assertGreater(s["processed"], 0)
        self.assertIsNotNone(s["labelled_demo_metrics"]["recall"])
        e = C.get("/api/v1/metrics/evaluation").json()
        self.assertTrue(e["dataset"]["synthetic"])
        self.assertIn("rules", e["systems"])

    def test_settings_change_is_audited_and_applied(self):
        before = C.get("/api/v1/settings").json()
        r = C.put("/api/v1/settings", json={"mode": "strict"})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["thresholds"]["medium"], before["modes"]["strict"]["medium_threshold"])
        a = C.get("/api/v1/audit", params={"event_type": "policy_change"}).json()
        self.assertGreaterEqual(a["total"], 1)
        C.put("/api/v1/settings", json={"mode": "balanced", "hold_recommendations_enabled": True})


class TestFairnessOverride(unittest.TestCase):
    def test_young_recipient_override_is_opt_in_and_audited(self):
        st = C.get("/api/v1/settings").json()
        o = st["young_recipient_override"]
        self.assertTrue(o["available"]); self.assertFalse(o["enabled"])
        # a young-recipient transfer scored between the shared and the override step-up thresholds
        body = {**VALID, "persist": False, "new_recipient": True, "recipient_age_days": 10, "amount": 60000,
                "graph": {**VALID["graph"], "recipient_distinct_senders_7d": 3}, "behavioral": {"consent": False}}
        base = score(body)
        t = st["thresholds"]
        C.put("/api/v1/settings", json={"young_recipient_override": True})
        on = score(body)
        self.assertEqual(on["risk_score"], base["risk_score"])              # the model is unchanged
        self.assertEqual(on["thresholds"].get("segment_override"), "young_recipient")
        self.assertGreaterEqual(on["thresholds"]["medium"], t["medium"])
        if t["medium"] <= base["risk_score"] < on["thresholds"]["medium"]:
            self.assertEqual(base["risk_level"], "MEDIUM"); self.assertEqual(on["risk_level"], "LOW")
        older = score({**body, "recipient_age_days": 400})
        self.assertNotIn("segment_override", older["thresholds"])          # only young recipients affected
        C.put("/api/v1/settings", json={"young_recipient_override": False})
        a = C.get("/api/v1/audit", params={"event_type": "policy_change"}).json()["items"][0]
        self.assertIn("young_recipient_override", a["details"]["after"])


class TestSecurity(unittest.TestCase):
    def test_rate_limit(self):
        cfg = dataclasses.replace(CFG, rate_limit_per_minute=3)
        app = create_app(cfg, db_path=str(Path(TMP) / "test.db"))
        c = Client(app, client_ip="10.9.9.9")
        codes = [c.get("/api/v1/settings").status_code for _ in range(6)]
        self.assertIn(429, codes)
        global C
        C = Client(create_app(CFG, db_path=str(Path(TMP) / "test.db")))   # restore STATE for other tests

    def test_admin_token(self):
        cfg = dataclasses.replace(CFG, admin_token="s3cret")
        app = create_app(cfg, db_path=str(Path(TMP) / "test.db"))
        c = Client(app)
        self.assertEqual(c.put("/api/v1/settings", json={"mode": "strict"}).status_code, 401)
        self.assertEqual(c.put("/api/v1/settings", json={"mode": "balanced"}, headers={"x-admin-token": "s3cret"}).status_code, 200)


if __name__ == "__main__":
    unittest.main()
