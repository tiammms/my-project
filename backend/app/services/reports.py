"""Template-based compliance report generator.

This is NOT an LLM. It deterministically renders only facts present in the
stored decision, separating observations from interpretations. A local LLM
adapter could later rephrase these sections, but must be constrained to the
same facts (see docs/HANDOFF.md).
"""
from __future__ import annotations


def build_report(tx: dict, raw: dict, result: dict) -> dict:
    comp = result.get("components", {})
    beh = comp.get("behavioral", {})
    graph = comp.get("graph", {})
    soc = comp.get("social_engineering", {})
    rec = comp.get("recipient", {})
    amt = raw.get("amount", 0)
    dev = comp.get("amount_deviation_sigma")
    summary = (f"Transaction {tx['transaction_id']} ({str(raw.get('transaction_type','')).replace('_', ' ')}, "
               f"{amt:,.0f} KZT) received risk score {result['risk_score']:.1f}/100 "
               f"({result['risk_level']}). Recommended action: {result['recommended_action'].replace('_', ' ').lower()}. "
               f"This is an automated recommendation for analyst review, not a finding of fraud.")
    context = [
        f"Amount: {amt:,.0f} KZT" + (f" ({dev:+.1f} std vs the customer's usual)" if dev is not None else " (no amount history)"),
        f"Transaction hour: {int(raw.get('transaction_hour', 0)):02d}:00",
        f"Recipient: {'new' if raw.get('new_recipient') else 'previously paid'}; account age "
        + (f"{raw['recipient_age_days']:.0f} days" if raw.get("recipient_age_days") is not None else "unknown (external)"),
        f"Velocity: {raw.get('transactions_last_1h', 0)} in last hour, {raw.get('transactions_last_24h', 0)} in last 24h",
        f"Device: {_tri(raw.get('device_anomaly'), 'new device', 'known device')}; "
        f"location: {_tri(raw.get('geographic_anomaly'), 'unusual region', 'usual region')}",
    ]
    indicators = [f"{f['label']}: {f['detail']}" for f in result.get("top_risk_factors", [])]
    findings = []
    if beh.get("status") == "available":
        findings.append(f"Behavioral deviation score {beh['score']:.0f}/100 (reliability: {beh['reliability']}). "
                        f"Hesitation index {beh.get('hesitation_index') or 0:.0f}, rhythm deviation {beh.get('rhythm_deviation') or 0:.0f}.")
    findings += [f"Graph: {s}" for s in graph.get("signals", [])]
    findings += [f"Social-engineering signal: {s}" for s in soc.get("signals", [])]
    findings += [f"Recipient: {s}" for s in rec.get("signals", [])]
    if not findings:
        findings.append("No behavioral, graph or social-engineering findings.")
    unc = list(result.get("confidence_notes", [])) + list(result.get("evaluation_warnings", []))
    if beh.get("status") != "available":
        unc.append("No behavioral observations were used; none are inferred.")
    actions = {
        "LOW": ["No action required; transaction remains in the monitoring sample."],
        "MEDIUM": ["Confirm the step-up verification outcome.",
                   "If the customer reports external instructions, follow the scam-intervention script."],
        "HIGH": ["Contact the customer through a verified channel before any release decision.",
                 "Ask open questions about who requested the transfer and why (possible social engineering).",
                 "Review linked graph alerts for the recipient.",
                 "Record the outcome to improve the model (confirmed fraud / legitimate)."],
    }[result["risk_level"]]
    return {
        "generator": "template-based report generator (deterministic, no LLM)",
        "requires_analyst_review": True,
        "sections": [
            {"title": "1. Case Summary", "items": [summary]},
            {"title": "2. Transaction Context", "items": context},
            {"title": "3. Risk Indicators", "items": indicators or ["No factor increased the risk materially."]},
            {"title": "4. Behavioral and Graph Findings", "items": findings},
            {"title": "5. Uncertainties and Missing Data", "items": unc},
            {"title": "6. Recommended Analyst Actions", "items": actions},
        ],
        "facts_vs_interpretation": "Sections 2 and 4 list observed inputs and computed signals; sections 1, 3 and 6 "
                                   "are model/policy interpretations that require analyst confirmation.",
    }


def _tri(v, yes, no):
    return yes if v else (no if v is False else "unknown")
