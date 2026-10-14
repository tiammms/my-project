"""Prepared synthetic demo scenarios. Every scenario is sent through the real Risk Engine.

The customer "Aigerim (synthetic)" is a fictional profile; all values are invented.
Scenarios beyond A/B cover legitimate-but-unusual behaviour (false-positive tests).
"""
from __future__ import annotations

import copy

BASE_BEHAVIOR_BASELINE = {
    "typing_interval_mean_ms": {"mean": 190, "std": 14},
    "typing_interval_cv": {"mean": 0.36, "std": 0.035},
    "confirm_pause_s": {"mean": 2.3, "std": 0.8},
    "correction_rate": {"mean": 0.06, "std": 0.02},
    "screen_sequence_deviation": {"mean": 0.10, "std": 0.06},
}
NORMAL_SESSION = {"typing_interval_mean_ms": 193, "typing_interval_cv": 0.37, "confirm_pause_s": 2.1,
                  "correction_rate": 0.05, "screen_sequence_deviation": 0.08}

BASE = {
    "amount": 25000, "transaction_type": "p2p_transfer", "transaction_hour": 13,
    "account_age_days": 2400, "recipient_age_days": 1900, "transactions_last_1h": 0, "transactions_last_24h": 1,
    "new_recipient": False, "geographic_anomaly": False, "device_anomaly": False,
    "active_call_during_session": False, "user_reported_external_instruction": None,
    "sender_baseline": {"median_amount": 18000, "log_std": 0.8, "tx_count": 140},
    "behavioral": {"consent": True, "baseline_sessions": 60, "screen_flow": "known_payee", "screen_flow_sessions": 54,
                   "session": NORMAL_SESSION, "baseline": BASE_BEHAVIOR_BASELINE},
    "graph": {"recipient_distinct_senders_7d": 1, "recipient_passthrough_ratio_7d": 0.3,
              "recipient_reported": False, "recipient_linked_to_reported": False,
              "sender_inbound_distinct_7d": 1, "sender_passthrough_ratio_24h": None},
    "sender_ref": "DEMO-AIGERIM", "recipient_ref": "DEMO-SISTER",
}


def _merge(base: dict, patch: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


SCENARIOS: dict[str, dict] = {
    "normal_transfer": {
        "title": "A - Normal transfer",
        "story": "Aigerim sends 25,000 KZT to her sister, whom she pays regularly, from her usual phone at lunchtime.",
        "patch": {},
        "expected": "LOW risk, ALLOW",
    },
    "social_engineering": {
        "title": "B - Suspected social engineering",
        "story": "During a phone call, Aigerim sends 450,000 KZT (25x her usual) to a 9-day-old account she has never "
                 "paid. She answers 'yes' to 'Is someone telling you to make this transfer?'. Her session shows long "
                 "hesitation and many corrections, but her typing rhythm is her own (no account takeover).",
        "patch": {"amount": 450000, "transaction_hour": 19, "recipient_age_days": 9, "new_recipient": True,
                  "active_call_during_session": True, "user_reported_external_instruction": True,
                  "behavioral": {"screen_flow": "new_payee", "screen_flow_sessions": 6, "baseline": {"screen_sequence_deviation": {"mean": 0.22, "std": 0.07}}, "session": {"typing_interval_mean_ms": 205, "typing_interval_cv": 0.37,
                                             "confirm_pause_s": 9.5, "correction_rate": 0.21,
                                             "screen_sequence_deviation": 0.45}},
                  "graph": {"recipient_distinct_senders_7d": 6, "recipient_passthrough_ratio_7d": 0.92,
                            "recipient_linked_to_reported": True},
                  "recipient_ref": "DEMO-NEW-RECIPIENT"},
        "expected": "HIGH risk, review / temporary hold recommendation",
    },
    "account_takeover": {
        "title": "Account takeover",
        "story": "At 03:00 a new device in another region makes three quick transfers; typing rhythm differs from Aigerim's.",
        "patch": {"amount": 300000, "transaction_hour": 3, "recipient_age_days": 20, "new_recipient": True,
                  "transactions_last_1h": 2, "transactions_last_24h": 3, "geographic_anomaly": True,
                  "device_anomaly": True, "user_reported_external_instruction": False,
                  "behavioral": {"screen_flow": "new_payee", "screen_flow_sessions": 6, "baseline": {"screen_sequence_deviation": {"mean": 0.22, "std": 0.07}}, "session": {"typing_interval_mean_ms": 128, "typing_interval_cv": 0.24,
                                             "confirm_pause_s": 0.7, "correction_rate": 0.02,
                                             "screen_sequence_deviation": 0.55}},
                  "graph": {"recipient_distinct_senders_7d": 3, "recipient_passthrough_ratio_7d": 0.85},
                  "recipient_ref": "DEMO-UNKNOWN-2"},
        "expected": "HIGH risk",
    },
    "legit_large_first_transfer": {
        "title": "Legitimate: first large transfer",
        "story": "Aigerim pays 600,000 KZT deposit for a car to a long-standing private seller account, calmly, "
                 "on her phone, no call, answers 'no' to the instruction question.",
        "patch": {"amount": 600000, "recipient_age_days": 3100, "new_recipient": True,
                  "user_reported_external_instruction": False,
                  "behavioral": {"screen_flow": "new_payee", "screen_flow_sessions": 6, "baseline": {"screen_sequence_deviation": {"mean": 0.22, "std": 0.07}}, "session": {"confirm_pause_s": 3.2, "screen_sequence_deviation": 0.24}},
                  "recipient_ref": "DEMO-CAR-SELLER"},
        "expected": "Should not be HIGH; step-up at most",
    },
    "traveling_customer": {
        "title": "Legitimate: travelling",
        "story": "Aigerim is abroad and pays her usual recipient from her own phone.",
        "patch": {"geographic_anomaly": True, "transaction_hour": 22},
        "expected": "LOW or MEDIUM",
    },
    "new_phone": {
        "title": "Legitimate: new phone",
        "story": "Aigerim bought a new phone; typing on the new keyboard is somewhat slower.",
        "patch": {"device_anomaly": True,
                  "behavioral": {"session": {"typing_interval_mean_ms": 222, "typing_interval_cv": 0.4}}},
        "expected": "LOW or MEDIUM",
    },
    "no_behavioral_data": {
        "title": "Behavioral data unavailable",
        "story": "Same as the social-engineering case but the customer did not consent to telemetry; the system "
                 "must not invent behavioral observations.",
        "patch": {"amount": 450000, "transaction_hour": 19, "recipient_age_days": 9, "new_recipient": True,
                  "active_call_during_session": None, "user_reported_external_instruction": None,
                  "behavioral": {"consent": False},
                  "graph": {"recipient_distinct_senders_7d": 6, "recipient_passthrough_ratio_7d": 0.92,
                            "recipient_linked_to_reported": True},
                  "recipient_ref": "DEMO-NEW-RECIPIENT"},
        "expected": "Elevated by graph/amount signals; behavioral component reported as unavailable",
    },
    "changed_typing_habits": {
        "title": "Legitimate: changed typing habits",
        "story": "Aigerim now types much faster than her historical baseline (new habit), usual recipient and amount.",
        "patch": {"behavioral": {"session": {"typing_interval_mean_ms": 140, "typing_interval_cv": 0.29}}},
        "expected": "Behavioral deviation visible, but overall LOW or MEDIUM",
    },
}


def scenario_payload(name: str) -> dict:
    s = SCENARIOS[name]
    p = _merge(BASE, s["patch"])
    if p.get("behavioral", {}).get("consent") is False:
        p["behavioral"] = {"consent": False, "baseline_sessions": 0}
    return p
