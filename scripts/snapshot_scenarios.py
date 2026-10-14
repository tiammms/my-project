"""Score every demo scenario with the real Risk Engine (no server needed) and save the results,
so the presentation and docs quote the current numbers. Usage: python -m scripts.snapshot_scenarios"""
import json
from pathlib import Path

from backend.app.core.config import settings
from backend.app.schemas.api import TransactionIn
from backend.app.services.risk_engine import RiskEngine, tx_to_raw
from backend.app.services.scenarios import SCENARIOS, scenario_payload

ROOT = Path(__file__).resolve().parents[1]
e = RiskEngine(settings.model_path, settings.policy_path)
out = {}
for k in SCENARIOS:
    r = e.score(tx_to_raw(TransactionIn.model_validate(scenario_payload(k))), "SNAP-" + k)
    out[k] = {"risk_score": r["risk_score"], "risk_level": r["risk_level"], "action": r["recommended_action"],
              "behavioral_deviations": r["components"]["behavioral"].get("deviations"),
              "top_factors": [f["label"] for f in r["top_risk_factors"][:4]]}
(ROOT / "experiments/results/demo_scenarios.json").write_text(json.dumps(out, indent=2))
print({k: (v["risk_score"], v["risk_level"]) for k, v in out.items()})
