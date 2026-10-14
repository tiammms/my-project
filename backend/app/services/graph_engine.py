"""Graph Risk Engine (NetworkX). Reproducible graph algorithms - not a neural network.

Builds a directed multigraph of account-to-account transfers from the stored
demo transactions and detects suspicious *structures* (never verdicts about
people):
  * fan_in        - one account receives from many distinct senders in a short window
  * rapid_chain   - funds received and forwarded onward (>=70%) within 6 hours, over >=2 hops
  * shared_recipient - one recipient appears in several transactions the Risk Engine scored HIGH
Each alert carries the nodes, edges and the exact features used.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime

import networkx as nx

FAN_IN_MIN_SENDERS = 5
FAN_IN_WINDOW_H = 72
CHAIN_MAX_DELAY_H = 6
CHAIN_MIN_FORWARD = 0.7


def _t(s: str) -> float:
    return datetime.fromisoformat(s).timestamp() / 3600.0


class GraphEngine:
    def __init__(self):
        self.g = nx.MultiDiGraph()
        self.alerts: list[dict] = []

    def build(self, rows: list[dict]) -> None:
        g = nx.MultiDiGraph()
        for r in rows:
            if not r.get("sender_ref") or not r.get("recipient_ref") or r["transaction_type"] == "card_payment":
                continue
            for n in (r["sender_ref"], r["recipient_ref"]):
                if n not in g:
                    g.add_node(n, kind="external" if n.startswith("EX") else ("merchant" if n.startswith("MR") else "account"),
                               max_risk=0.0)
            g.add_edge(r["sender_ref"], r["recipient_ref"], tx=r["transaction_id"], amount=r["amount"],
                       t=_t(r["event_time"]), time=r["event_time"], risk=r["risk_score"], level=r["risk_level"])
            g.nodes[r["sender_ref"]]["max_risk"] = max(g.nodes[r["sender_ref"]]["max_risk"], r["risk_score"])
        self.g = g
        self.alerts = self._detect()

    # -- detectors -------------------------------------------------------------
    def _detect(self) -> list[dict]:
        g, alerts = self.g, []
        # fan-in
        for n in g.nodes:
            if g.nodes[n]["kind"] != "account":
                continue
            ins = sorted(((d["t"], u, d) for u, _, d in g.in_edges(n, data=True)), key=lambda x: x[0])
            best, j = (0, 0, 0), 0
            for i in range(len(ins)):
                while ins[i][0] - ins[j][0] > FAN_IN_WINDOW_H:
                    j += 1
                k = len({u for _, u, _ in ins[j:i + 1]})
                if k > best[0]:
                    best = (k, j, i)
            if best[0] >= FAN_IN_MIN_SENDERS:
                win = ins[best[1]:best[2] + 1]
                edges = [self._edge(u, n, d) for _, u, d in win]
                out_amt = sum(d["amount"] for _, _, d in g.out_edges(n, data=True)
                              if win[0][0] <= d["t"] <= win[-1][0] + 24)
                in_amt = sum(d["amount"] for _, _, d in win)
                edges += [self._edge(n, v, d) for _, v, d in g.out_edges(n, data=True)
                          if win[0][0] <= d["t"] <= win[-1][0] + 24]
                alerts.append(self._alert("fan_in", n, edges,
                    f"Potentially risky node: received transfers from {best[0]} distinct senders within "
                    f"{FAN_IN_WINDOW_H}h and forwarded {out_amt / in_amt:.0%} of the incoming amount within 24h."
                    if in_amt else f"Received from {best[0]} distinct senders within {FAN_IN_WINDOW_H}h.",
                    {"distinct_senders": best[0], "window_hours": FAN_IN_WINDOW_H,
                     "forwarded_share_24h": round(out_amt / in_amt, 3) if in_amt else None},
                    severity=min(100, 25 + 5 * best[0] + (35 if in_amt and out_amt / in_amt > 0.7 else 0))))
        # rapid chains: in-edge followed by out-edge carrying >=70% within 6h
        hop = defaultdict(list)   # node -> list of (in_edge, out_edge)
        for n in g.nodes:
            if g.nodes[n]["kind"] != "account":
                continue
            outs = [(v, d) for _, v, d in g.out_edges(n, data=True)]
            for u, _, di in g.in_edges(n, data=True):
                for v, do in outs:
                    if 0 < do["t"] - di["t"] <= CHAIN_MAX_DELAY_H and do["amount"] >= CHAIN_MIN_FORWARD * di["amount"] and v != u:
                        hop[n].append(((u, n, di), (n, v, do)))
        used = set()
        for n, pairs in hop.items():
            for (u, m, di), (m2, v, do) in pairs:
                # extend one more hop if v also forwards quickly
                path_edges = [self._edge(u, m, di), self._edge(m, v, do)]
                nodes = [u, m, v]
                for (a, b, d1), (b2, c, d2) in hop.get(v, []):
                    if d1 is do or (d1["tx"] == do["tx"]):
                        path_edges.append(self._edge(v, c, d2)); nodes.append(c)
                        break
                if len(nodes) < 3:
                    continue
                key = tuple(e["transaction_id"] for e in path_edges)
                if key in used or any(set(key) <= set(k) for k in used):
                    continue
                used.add(key)
                hours = (path_edges[-1]["t"] - path_edges[0]["t"])
                alerts.append(self._alert("rapid_chain", m, path_edges,
                    f"Suspicious transaction structure: funds moved through {len(nodes) - 1} hops in {hours:.1f}h, "
                    f"each intermediate account forwarding at least {CHAIN_MIN_FORWARD:.0%} of what it received.",
                    {"hops": len(nodes) - 1, "elapsed_hours": round(hours, 2), "path": nodes,
                     "max_hop_delay_hours": CHAIN_MAX_DELAY_H, "min_forward_share": CHAIN_MIN_FORWARD},
                    severity=min(100, 45 + 15 * (len(nodes) - 2) + (10 if hours < 3 else 0))))
        # shared recipients of HIGH-scored transactions
        high_by_rec = defaultdict(list)
        for u, v, d in g.edges(data=True):
            if d["level"] == "HIGH":
                high_by_rec[v].append((u, d))
        for v, lst in high_by_rec.items():
            senders = {u for u, _ in lst}
            if len(senders) >= 2:
                alerts.append(self._alert("shared_recipient", v, [self._edge(u, v, d) for u, d in lst],
                    f"{len(senders)} different senders made HIGH-risk transfers to the same recipient.",
                    {"high_risk_senders": len(senders), "high_risk_transactions": len(lst)},
                    severity=min(100, 50 + 10 * len(senders))))
        alerts.sort(key=lambda a: -a["severity"])
        for i, a in enumerate(alerts):
            a["alert_id"] = f"GA-{i + 1:04d}"
        return alerts

    @staticmethod
    def _edge(u, v, d) -> dict:
        return {"source": u, "target": v, "transaction_id": d["tx"], "amount": round(d["amount"], 2),
                "time": d["time"], "t": d["t"], "risk_score": d["risk"], "risk_level": d["level"]}

    @staticmethod
    def _alert(kind, focus, edges, text, features, severity) -> dict:
        nodes = sorted({e["source"] for e in edges} | {e["target"] for e in edges})
        return {"alert_id": "", "type": kind, "focus_node": focus, "severity": round(float(severity), 1),
                "explanation": text, "features_used": features, "nodes": nodes,
                "edges": [{k: v for k, v in e.items() if k != "t"} for e in edges],
                "wording_note": "Indicates a suspicious structure for analyst review, not proof of wrongdoing."}

    # -- views -----------------------------------------------------------------
    def node_alerts(self, node: str) -> list[dict]:
        return [a for a in self.alerts if node in a["nodes"]]

    def neighborhood(self, node: str, max_edges: int = 80) -> dict:
        if node not in self.g:
            return {"nodes": [], "edges": []}
        edges = []
        for u, v, d in list(self.g.in_edges(node, data=True)) + list(self.g.out_edges(node, data=True)):
            edges.append(self._edge(u, v, d))
        edges.sort(key=lambda e: -e["risk_score"])
        edges = edges[:max_edges]
        return self._view(edges, focus=node)

    def overview(self, min_severity: float = 0, max_alerts: int = 40) -> dict:
        edges, seen = [], set()
        for a in [a for a in self.alerts if a["severity"] >= min_severity][:max_alerts]:
            for e in a["edges"]:
                if e["transaction_id"] not in seen:
                    seen.add(e["transaction_id"]); edges.append(e)
        return self._view(edges)

    def _view(self, edges: list[dict], focus: str | None = None) -> dict:
        ids = {e["source"] for e in edges} | {e["target"] for e in edges}
        alert_nodes = defaultdict(list)
        for a in self.alerts:
            for n in a["nodes"]:
                alert_nodes[n].append(a["alert_id"])
        nodes = [{"id": n, "kind": self.g.nodes[n]["kind"] if n in self.g else "account",
                  "max_outgoing_risk": round(self.g.nodes[n]["max_risk"], 2) if n in self.g else 0,
                  "in_degree": self.g.in_degree(n) if n in self.g else 0,
                  "out_degree": self.g.out_degree(n) if n in self.g else 0,
                  "alerts": alert_nodes.get(n, [])[:10], "focus": n == focus} for n in sorted(ids)]
        return {"nodes": nodes, "edges": [{k: v for k, v in e.items() if k != "t"} for e in edges]}

    def stats(self) -> dict:
        by = defaultdict(int)
        for a in self.alerts:
            by[a["type"]] += 1
        return {"nodes": self.g.number_of_nodes(), "edges": self.g.number_of_edges(), "alerts": len(self.alerts),
                "alerts_by_type": dict(by)}
