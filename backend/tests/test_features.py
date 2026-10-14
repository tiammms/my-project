"""Unit tests for the shared feature contract and component scorers."""
import math
import unittest

from backend.app.services.features import (behavioral_component, featurize, graph_component, rule_score,
                                           social_component, recipient_component)

BL = {"bl_sessions": 40, "behavioral_consent": True,
      "typing_interval_mean_ms": 190, "bl_typing_interval_mean_ms": 190, "bl_typing_interval_mean_ms_std": 14,
      "confirm_pause_s": 2.2, "bl_confirm_pause_s": 2.3, "bl_confirm_pause_s_std": 0.8,
      "correction_rate": 0.06, "bl_correction_rate": 0.06, "bl_correction_rate_std": 0.02}


class TestFeatures(unittest.TestCase):
    def test_behavioral_requires_consent(self):
        b = behavioral_component({**BL, "behavioral_consent": False})
        self.assertIsNone(b["score"]); self.assertEqual(b["status"], "unavailable")

    def test_behavioral_requires_baseline(self):
        self.assertIsNone(behavioral_component({**BL, "bl_sessions": 1})["score"])

    def test_behavioral_normal_vs_hesitant(self):
        normal = behavioral_component(BL)["score"]
        hes = behavioral_component({**BL, "confirm_pause_s": 9.0, "correction_rate": 0.2})
        self.assertLess(normal, 10)
        self.assertGreater(hes["hesitation_index"], 50)
        self.assertLess(hes["rhythm_deviation"], 10)   # same person, different state

    def test_navigation_needs_flow_matched_baseline(self):
        raw = {**BL, "screen_sequence_deviation": 0.5, "bl_screen_sequence_deviation": 0.1, "bl_screen_sequence_deviation_std": 0.05}
        self.assertIn("screen_sequence_deviation", behavioral_component({**raw, "bl_screen_sessions": 2})["not_comparable"])
        self.assertIn("screen_sequence_deviation", behavioral_component({**raw, "bl_screen_sessions": 5})["deviations"])

    def test_passthrough_band(self):
        base = {"transaction_type": "p2p_transfer", "recipient_distinct_senders_7d": 3, "recipient_age_days": 400}
        self.assertTrue(any("forwards" in s for s in graph_component({**base, "recipient_passthrough_ratio_7d": 0.9})["signals"]))
        # spending far more than was received is not forwarding
        self.assertFalse(any("forwards" in s for s in graph_component({**base, "recipient_passthrough_ratio_7d": 12.0})["signals"]))

    def test_social_single_signal_capped(self):
        rec = recipient_component({})
        s = social_component({"user_reported_external_instruction": True, "transaction_type": "p2p_transfer"},
                             {"status": "unavailable"}, rec)
        self.assertLessEqual(s["score"], 45); self.assertTrue(s["capped_single_signal"])

    def test_graph_ignores_merchant_fan_in(self):
        g = graph_component({"transaction_type": "card_payment", "recipient_distinct_senders_7d": 500,
                             "recipient_passthrough_ratio_7d": 0.0})
        self.assertEqual(g["score"], 0)

    def test_featurize_missing_is_nan(self):
        f, _ = featurize({"amount": 100, "transaction_type": "p2p_transfer", "transaction_hour": 3})
        self.assertTrue(math.isnan(f["device_anomaly"]))
        self.assertTrue(math.isnan(f["behavioral_anomaly_score"]))
        self.assertEqual(f["behavioral_available"], 0.0)

    def test_rules(self):
        pts, hits = rule_score({"amount": 300000, "new_recipient": True, "transaction_type": "p2p_transfer"})
        self.assertIn("R1", hits); self.assertIn("R2", hits); self.assertEqual(pts, 3)


if __name__ == "__main__":
    unittest.main()


class TestPublicDatasetScript(unittest.TestCase):
    """The ULB check script must run end-to-end on a file with the public schema (mock data, not real results)."""

    def test_runs_on_mock_ulb_schema(self):
        import tempfile
        from pathlib import Path
        import numpy as np
        import pandas as pd
        from ml.evaluation.public_ulb import run
        rng = np.random.default_rng(0)
        n = 4000
        y = (rng.random(n) < 0.03).astype(int)
        df = pd.DataFrame({f"V{i}": rng.normal(size=n) + y * (1.5 if i < 4 else 0) for i in range(1, 29)})
        df["Time"] = np.sort(rng.uniform(0, 172800, n)); df["Amount"] = rng.lognormal(3, 1, n); df["Class"] = y
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "creditcard.csv"; df.to_csv(p, index=False)
            res = run(p, Path(d) / "out.json")
        self.assertGreater(res["models"]["gradient_boosting"]["test"]["roc_auc"], 0.8)
        self.assertIn("scope_note", res)
