"""NeuroShield synthetic data generator (SYNTHETIC - not real banking data).

Simulates a small retail bank over N days with a discrete-event loop:
  * legitimate customers with personal amount / time / device / typing baselines,
  * hard-negative legitimate behaviour (travel, new phones, big purchases,
    habit changes, legitimately young recipients, missing telemetry),
  * account takeover (ATO) attacks,
  * authorised-push-payment (APP) social-engineering scams,
  * money-mule rings that forward stolen funds (layering).

Every context feature is computed *time-respectingly* from state that exists
before the transaction (no look-ahead). Fraud reports reach the system only
after a realistic delay, so "recipient_reported" can never leak the label of
the current transaction.

Usage:  python -m ml.data.generate [--seed 42] [--variant default|hard|scarce]
Output: ml/data/generated/<variant>/transactions.csv + generation_config.json
"""
from __future__ import annotations

import argparse
import heapq
import json
import math
from collections import deque
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

OUT_ROOT = Path(__file__).resolve().parent / "generated"
START_DATE = pd.Timestamp("2026-01-05 00:00:00")


@dataclass
class GenConfig:
    seed: int = 42
    variant: str = "default"
    n_users: int = 3000
    n_merchants: int = 800
    n_external: int = 400
    days: int = 90
    activity_mean: float = 0.55          # legit transactions / user / day
    telemetry_rate: float = 0.75         # share of customers who consented to telemetry
    stress_effect: float = 0.7           # strength of hesitation pattern in scam victims
    instruction_disclosure: float = 0.25 # victims who admit external instruction when asked
    scam_call_rate: float = 0.55         # victims on an active call during the session
    ato_per_day: float = 3.5
    app_per_day: float = 6.5
    n_rings: int = 16
    report_rate: float = 0.8
    new_user_share: float = 0.06
    habit_change_share: float = 0.03
    travel_rate: float = 0.02
    new_device_rate: float = 0.025
    relay_share: float = 0.06
    opened_during_share: float = 0.06
    recruited_mule_share: float = 0.5
    false_reports: int = 60             # legitimate accounts wrongly reported (disputes, mistakes)
    big_purchase_rate: float = 0.004
    notes: list = field(default_factory=list)


VARIANTS = {
    "default": {},
    # weaker behavioural signal, victims rarely disclose, fewer active-call signals
    "hard": {"stress_effect": 0.4, "instruction_disclosure": 0.12, "scam_call_rate": 0.3,
             "notes": ["Hard variant: weaker stress effect, low disclosure, fewer calls"]},
    # telemetry available for only 30% of customers
    "scarce": {"telemetry_rate": 0.30, "notes": ["Scarce variant: telemetry consent only 30%"]},
    # fraud reports are rare and noisy: recipient reputation is much weaker
    "low_reports": {"report_rate": 0.3, "false_reports": 200,
                    "notes": ["Low-reports variant: 30% of frauds reported, 200 false reports"]},
}

METRICS = ["typing_interval_mean_ms", "typing_interval_cv", "confirm_pause_s",
           "correction_rate", "screen_sequence_deviation"]


class Welford:
    __slots__ = ("n", "mean", "m2")

    def __init__(self):
        self.n, self.mean, self.m2 = 0, 0.0, 0.0

    def add(self, x: float):
        self.n += 1
        d = x - self.mean
        self.mean += d / self.n
        self.m2 += d * (x - self.mean)

    @property
    def std(self) -> float:
        return math.sqrt(self.m2 / (self.n - 1)) if self.n > 1 else 0.0


class Account:
    def __init__(self, aid: str, created_t: float, kind: str):
        self.id, self.created_t, self.kind = aid, created_t, kind
        self.amount_stats = Welford()
        self.out_times: deque = deque()          # for velocity
        self.paid: set = set()
        self.devices: set = {0}
        self.beh = {m: Welford() for m in METRICS}
        # navigation path depends on the screen flow, so it gets one baseline per flow
        self.nav = {"new_payee": Welford(), "known_payee": Welford()}
        self.inflow: deque = deque()             # (t, amount, from)
        self.outflow: deque = deque()            # (t, amount, to)
        self.contacts: deque = deque()           # (t, counterparty) non-merchant, 14d
        self.reported_at = math.inf
        # profile (legit users & mules)
        self.mu = self.sigma = self.rate = self.hour_pref = 0.0
        self.telemetry = False
        self.prof: dict = {}
        self.usual_merchants: list = []
        self.usual_p2p: list = []
        self.habit_shift_day = None
        self.ring = None
        self.role = None
        self.pending_forward = 0.0
        self.relay = False
        self.forward_scheduled = False


def run(cfg: GenConfig) -> pd.DataFrame:
    rng = np.random.default_rng(cfg.seed)
    H = 24.0
    accounts: dict[str, Account] = {}
    users: list[Account] = []

    def new_profile(a: Account, elderly=False):
        a.prof = {
            "typing_interval_mean_ms": float(np.clip(rng.normal(185, 40) * (1.35 if elderly else 1), 90, 420)),
            "typing_interval_cv": float(np.clip(rng.normal(0.36, 0.06), 0.18, 0.6)),
            "confirm_pause_s": float(np.clip(rng.lognormal(math.log(2.2), 0.35) * (1.6 if elderly else 1), 0.6, 9)),
            "correction_rate": float(np.clip(rng.normal(0.06, 0.025) * (1.4 if elderly else 1), 0.0, 0.3)),
            "screen_sequence_deviation": float(np.clip(rng.normal(0.08, 0.03), 0.01, 0.25)),
        }

    # ---- legit customers --------------------------------------------------
    for i in range(cfg.n_users):
        r0 = rng.random()
        if r0 < cfg.opened_during_share:          # account opened during the simulation
            created = rng.uniform(0, (cfg.days - 10) * H)
        elif r0 < cfg.opened_during_share + cfg.new_user_share:
            created = -rng.uniform(0, 30) * H
        else:
            created = -float(np.clip(rng.lognormal(math.log(900), 0.8), 31, 7000)) * H
        a = Account(f"AC{i:05d}", created, "user")
        a.relay = rng.random() < cfg.relay_share
        a.mu = rng.normal(math.log(9000), 0.5)
        a.sigma = rng.uniform(0.6, 1.1)
        a.rate = rng.gamma(2.0, cfg.activity_mean / 2.0)
        a.hour_pref = float(np.clip(rng.normal(14, 3), 7, 22))
        a.region = int(rng.integers(0, 6))
        a.telemetry = rng.random() < cfg.telemetry_rate
        new_profile(a, elderly=rng.random() < 0.08)
        if rng.random() < cfg.habit_change_share:
            a.habit_shift_day = int(rng.integers(15, cfg.days - 5))
        accounts[a.id] = a
        users.append(a)
    merchants = []
    for j in range(cfg.n_merchants):
        m = Account(f"MR{j:04d}", -rng.uniform(200, 4000) * H, "merchant")
        accounts[m.id] = m
        merchants.append(m)
    merchant_pop = rng.zipf(1.6, cfg.n_merchants).astype(float)
    merchant_p = merchant_pop / merchant_pop.sum()
    externals = [f"EX{k:04d}" for k in range(cfg.n_external)]
    for u in users:
        u.usual_merchants = list(rng.choice(cfg.n_merchants, size=int(rng.integers(4, 12)), replace=False, p=merchant_p))
        u.usual_p2p = [users[k] for k in rng.choice(cfg.n_users, size=int(rng.integers(2, 7)), replace=False)]

    # ---- mule rings -------------------------------------------------------
    rings = []
    mule_idx = cfg.n_users
    for r in range(cfg.n_rings):
        start = rng.uniform(0, cfg.days - 8) * H
        dur = rng.uniform(12, 25) * H
        layer1, collectors = [], []
        for role, n in (("layer1", int(rng.integers(3, 7))), ("collector", int(rng.integers(1, 3)))):
            for _ in range(n):
                if rng.random() < cfg.recruited_mule_share:   # recruited existing customer
                    created = start - rng.uniform(150, 3000) * H
                else:
                    created = start - rng.uniform(5, 60 if role == "layer1" else 90) * H
                m = Account(f"AC{mule_idx:05d}", created, "mule")
                mule_idx += 1
                m.ring, m.role = r, role
                m.mu, m.sigma = rng.normal(math.log(6000), 0.4), 0.6
                m.telemetry = rng.random() < 0.6
                m.region = int(rng.integers(0, 6))
                new_profile(m)
                accounts[m.id] = m
                (layer1 if role == "layer1" else collectors).append(m)
        rings.append({"id": r, "start": start, "end": start + dur, "layer1": layer1, "collectors": collectors})

    events: list = []
    seq = [0]

    def push(t, kind, payload):
        seq[0] += 1
        heapq.heappush(events, (t, seq[0], kind, payload))

    T_END = cfg.days * H
    # legit initiations
    for u in users:
        travel_days = set(np.nonzero(rng.random(cfg.days) < cfg.travel_rate)[0].tolist())
        u.travel_days = set()
        for d in travel_days:  # trips last 2-5 days
            for k in range(int(rng.integers(2, 6))):
                u.travel_days.add(d + k)
        n = rng.poisson(u.rate * cfg.days)
        for _ in range(n):
            day = rng.integers(0, cfg.days)
            hour = float(np.clip(rng.normal(u.hour_pref, 3.2), 0, 23.99))
            t = day * H + hour
            if t < max(u.created_t, 0):
                continue
            push(t, "legit", {"u": u.id})
    # mule warm-up activity (small card payments before the ring starts)
    for ring in rings:
        for m in ring["layer1"] + ring["collectors"]:
            for _ in range(int(rng.integers(1, 6))):
                t = rng.uniform(max(m.created_t, 0), max(ring["start"], m.created_t + 1))
                if 0 <= t < T_END:
                    push(t, "mule_warmup", {"u": m.id})

    def pick_mule(ring, t):
        fresh = [m for m in ring["layer1"] if m.reported_at > t]
        if fresh and rng.random() < 0.7:    # criminals rotate away from reported accounts
            return fresh[int(rng.integers(0, len(fresh)))]
        return ring["layer1"][int(rng.integers(0, len(ring["layer1"])))]

    def active_rings(t):
        return [r for r in rings if r["start"] <= t <= r["end"]]

    non_mule_users = users
    for kind, per_day in (("ato", cfg.ato_per_day), ("app", cfg.app_per_day)):
        n = rng.poisson(per_day * cfg.days)
        for _ in range(n):
            t = rng.uniform(0, T_END)
            if not active_rings(t):
                continue
            v = non_mule_users[int(rng.integers(0, len(non_mule_users)))]
            if t < v.created_t + 2 * H:
                continue
            if kind == "app":   # scams happen when the victim is normally active (calls in daytime/evening)
                t = (t // H) * H + float(np.clip(rng.normal(v.hour_pref, 3.2), 0, 23.99))
            push(t, kind, {"u": v.id, "event": f"{kind}-{seq[0]}"})

    for k in rng.choice(len(users), size=cfg.false_reports, replace=False):
        users[int(k)].reported_at = rng.uniform(0, T_END)

    rows: list[dict] = []
    tx_counter = [0]

    # ---------------------------------------------------------------------
    def prune(dq: deque, t: float, win: float):
        while dq and dq[0][0] < t - win:
            dq.popleft()

    def session_metrics(a: Account, t: float, mode: str, new_payee: bool) -> dict:
        p = dict(a.prof)
        if a.habit_shift_day is not None and t >= a.habit_shift_day * H:
            p["typing_interval_mean_ms"] *= 0.78
            p["typing_interval_cv"] *= 0.85
        if mode == "attacker":
            p = {
                "typing_interval_mean_ms": float(np.clip(rng.normal(175, 45), 70, 400)),
                "typing_interval_cv": float(np.clip(rng.normal(0.34, 0.08), 0.1, 0.7)),
                "confirm_pause_s": float(np.clip(rng.lognormal(math.log(1.6), 0.5), 0.2, 8)),
                "correction_rate": float(np.clip(rng.normal(0.05, 0.03), 0, 0.3)),
                "screen_sequence_deviation": float(np.clip(rng.normal(0.3, 0.15), 0.02, 0.95)),
            }
        x = {
            "typing_interval_mean_ms": rng.normal(p["typing_interval_mean_ms"], 0.07 * p["typing_interval_mean_ms"]),
            "typing_interval_cv": rng.normal(p["typing_interval_cv"], 0.035),
            "confirm_pause_s": p["confirm_pause_s"] * rng.lognormal(0, 0.3),
            "correction_rate": rng.normal(p["correction_rate"], 0.02),
            "screen_sequence_deviation": abs(rng.normal(p["screen_sequence_deviation"], 0.05)),
        }
        if new_payee:  # adding a payee is a different flow for everyone
            x["screen_sequence_deviation"] += abs(rng.normal(0.12, 0.06))
        if mode == "new_device":
            x["typing_interval_mean_ms"] *= rng.uniform(0.85, 1.18)
            x["typing_interval_cv"] *= rng.uniform(0.9, 1.15)
        if mode == "distracted":   # legitimate but hesitant session (busy, unsure, elderly relative helping)
            x["confirm_pause_s"] *= rng.uniform(1.5, 3.5)
            x["correction_rate"] *= rng.uniform(1.0, 2.2)
        if mode == "stressed":
            s = cfg.stress_effect
            x["confirm_pause_s"] *= 1 + s * rng.uniform(0.8, 2.6)
            x["correction_rate"] *= 1 + s * rng.uniform(0.5, 2.0)
            x["typing_interval_mean_ms"] *= 1 + s * rng.uniform(0.03, 0.18)
            x["screen_sequence_deviation"] += s * abs(rng.normal(0.15, 0.08))
        x["typing_interval_cv"] = float(np.clip(x["typing_interval_cv"], 0.05, 0.9))
        x["correction_rate"] = float(np.clip(x["correction_rate"], 0.0, 0.6))
        x["screen_sequence_deviation"] = float(np.clip(x["screen_sequence_deviation"], 0.0, 1.0))
        return {k: round(float(v), 4) for k, v in x.items()}

    def do_tx(t, sender: Account, rid: str, amount: float, ttype: str, *, mode="normal",
              device_new=False, geo_anom=False, telemetry_ok=None, call=None, instructed=None,
              label=0, ftype="none", legit_tag="normal", event_id=None):
        rec = accounts.get(rid)
        prune(sender.out_times, t, 24.0)   # 24 hours
        n1h = sum(1 for (tt,) in sender.out_times if tt >= t - 1)
        n24 = len(sender.out_times)
        st = sender.amount_stats
        new_rec = rid not in sender.paid
        row = {
            "transaction_id": None, "timestamp": None, "t_hours": round(t, 4),
            "sender_id": sender.id, "recipient_id": rid,
            "amount": round(float(amount), 2), "transaction_type": ttype,
            "transaction_hour": int(t % 24),
            "account_age_days": round((t - sender.created_t) / H, 2),
            "recipient_age_days": round((t - rec.created_t) / H, 2) if rec is not None else None,
            "transactions_last_1h": n1h, "transactions_last_24h": n24,
            "baseline_log_mean": round(st.mean, 4) if st.n >= 3 else None,
            "baseline_log_std": round(st.std, 4) if st.n >= 3 else None,
            "baseline_tx_count": st.n,
            "new_recipient": new_rec,
            "geographic_anomaly": geo_anom,
            "device_anomaly": device_new,
        }
        # behavioural telemetry: only for in-app flows of consenting customers
        in_app = ttype != "card_payment"
        tele = sender.telemetry and in_app and (telemetry_ok if telemetry_ok is not None else True)
        row["behavioral_consent"] = bool(sender.telemetry)
        flow = "new_payee" if (new_rec and ttype in ("p2p_transfer", "international_transfer")) else "known_payee"
        if tele:
            sm = session_metrics(sender, t, mode, flow == "new_payee")
            row.update(sm)
        else:
            sm = None
            for m in METRICS:
                row[m] = None
        for m in METRICS:
            w = sender.beh[m]
            row[f"bl_{m}"] = round(w.mean, 4) if w.n >= 1 else None
            row[f"bl_{m}_std"] = round(w.std, 4) if w.n >= 2 else None
        row["bl_sessions"] = sender.beh[METRICS[0]].n
        nw = sender.nav[flow]   # flow-matched navigation baseline (v2 fix)
        row["bl_screen_sequence_deviation"] = round(nw.mean, 4) if nw.n >= 1 else None
        row["bl_screen_sequence_deviation_std"] = round(nw.std, 4) if nw.n >= 2 else None
        row["bl_screen_flow"] = flow
        row["bl_screen_sessions"] = nw.n
        row["active_call_during_session"] = call if in_app else None
        asked = in_app and new_rec and ttype in ("p2p_transfer", "international_transfer")
        row["user_reported_external_instruction"] = instructed if asked else None
        # graph context for recipient
        if rec is not None:
            prune(rec.inflow, t, 7 * H); prune(rec.outflow, t, 7 * H)
            inf = sum(x[1] for x in rec.inflow)
            # pass-through = money moved on to OTHER ACCOUNTS; spending at merchants is not forwarding (v2 fix)
            outf = sum(x[1] for x in rec.outflow if not str(x[2]).startswith("MR"))
            row["recipient_distinct_senders_7d"] = len({x[2] for x in rec.inflow})
            row["recipient_passthrough_ratio_7d"] = round(outf / inf, 4) if inf > 0 else None
            row["recipient_reported"] = rec.reported_at <= t
            if rec.kind != "merchant":
                prune(rec.contacts, t, 14 * H)
                row["recipient_linked_to_reported"] = any(accounts[c].reported_at <= t for _, c in rec.contacts if c in accounts)
            else:
                row["recipient_linked_to_reported"] = False
        else:
            row.update({"recipient_distinct_senders_7d": None, "recipient_passthrough_ratio_7d": None,
                        "recipient_reported": False, "recipient_linked_to_reported": False})
        prune(sender.inflow, t, 7 * H); prune(sender.outflow, t, 7 * H)
        row["sender_inbound_distinct_7d"] = len({x[2] for x in sender.inflow})
        in24 = sum(x[1] for x in sender.inflow if x[0] >= t - 24)
        out24 = sum(x[1] for x in sender.outflow if x[0] >= t - 24 and not str(x[2]).startswith("MR")) + (0 if rid.startswith("MR") else amount)
        row["sender_passthrough_ratio_24h"] = round(out24 / in24, 4) if in24 > 0 else None
        row.update({"is_fraud": int(label), "fraud_type": ftype, "legit_tag": legit_tag if not label else "fraud",
                    "event_id": event_id, "ring_id": rec.ring if (rec is not None and rec.ring is not None) else (sender.ring if sender.ring is not None else -1)})
        tx_counter[0] += 1
        row["transaction_id"] = f"TX{tx_counter[0]:07d}"
        row["timestamp"] = str(START_DATE + pd.Timedelta(hours=t))
        rows.append(row)
        # ---- update state AFTER emitting the row (no look-ahead) ----
        sender.out_times.append((t,))
        st.add(math.log(max(amount, 1.0)))
        sender.paid.add(rid)
        sender.outflow.append((t, amount, rid))
        if sm:
            for m in METRICS:
                sender.beh[m].add(sm[m])
            sender.nav[flow].add(sm["screen_sequence_deviation"])
        if rec is not None:
            rec.inflow.append((t, amount, sender.id))
            if rec.kind != "merchant":
                rec.contacts.append((t, sender.id))
                sender.contacts.append((t, rid))
            if rec.kind == "user" and rec.relay and ttype == "p2p_transfer" and legit_tag != "relay" and not label:
                push(t + rng.uniform(1, 20), "relay", {"u": rec.id, "amount": amount * rng.uniform(0.5, 1.0)})
            if rec.kind == "mule" and rec.reported_at > t:
                rec.pending_forward += amount
                if not rec.forward_scheduled:   # mules batch incoming funds into one onward transfer
                    rec.forward_scheduled = True
                    delay = rng.uniform(0.3, 6) if rec.role == "layer1" else rng.uniform(2, 18)
                    push(t + delay, "mule_forward", {"u": rec.id})
        return row

    # ---------------------------------------------------------------------
    while events:
        t, _, kind, p = heapq.heappop(events)
        if t >= T_END:
            break
        a = accounts[p["u"]]
        if kind == "legit":
            day = int(t // H)
            ttype = rng.choice(["card_payment", "bill_payment", "p2p_transfer", "international_transfer"],
                               p=[0.46, 0.16, 0.34, 0.04])
            traveling = day in a.travel_days
            if traveling and rng.random() < 0.2:
                ttype = "international_transfer" if rng.random() < 0.3 else "card_payment"
            mult = {"card_payment": 1.0, "bill_payment": 1.5, "p2p_transfer": 2.0, "international_transfer": 6.0}[ttype]
            amount = math.exp(rng.normal(a.mu, a.sigma)) * mult
            if rng.random() < 0.01:      # occasional one-off large legitimate payment
                amount *= rng.uniform(4, 20)
            tag = "normal"
            if ttype in ("card_payment", "bill_payment"):
                rid = merchants[int(rng.choice(a.usual_merchants) if rng.random() < 0.85 else rng.choice(cfg.n_merchants, p=merchant_p))].id
            elif ttype == "p2p_transfer":
                if rng.random() < 0.85:
                    rid = a.usual_p2p[int(rng.integers(0, len(a.usual_p2p)))].id
                else:
                    rid = users[int(rng.integers(0, len(users)))].id
                if rid == a.id or t < accounts[rid].created_t:
                    continue
                if rng.random() < cfg.big_purchase_rate * 10:   # within p2p share
                    amount *= rng.uniform(5, 40)
                    cand = users[int(rng.integers(0, len(users)))]
                    if cand.id != a.id and t >= cand.created_t:
                        rid = cand.id
                    tag = "big_purchase"
            else:
                rid = externals[int(rng.integers(0, len(externals)))]
            device_new = rng.random() < cfg.new_device_rate
            if device_new:
                a.devices.add(len(a.devices))
                tag = "new_device" if tag == "normal" else tag
            if traveling and tag == "normal":
                tag = "travel"
            mode = "new_device" if device_new else ("distracted" if rng.random() < 0.05 else "normal")
            if tag == "normal" and a.habit_shift_day is not None and a.habit_shift_day * H <= t < (a.habit_shift_day + 20) * H:
                tag = "habit_change"
            rec = accounts.get(rid)
            if tag == "normal" and rid not in a.paid and rec is not None and rec.kind == "user" and (t - rec.created_t) / H < 30:
                tag = "young_recipient"
            if tag == "normal" and not a.telemetry and ttype != "card_payment":
                tag = "no_telemetry"
            if tag == "normal" and rid not in a.paid and ttype in ("p2p_transfer", "international_transfer"):
                tag = "new_recipient"
            call = bool(rng.random() < 0.08)
            instructed = bool(rng.random() < 0.03)
            do_tx(t, a, rid, amount, ttype, mode=mode, device_new=device_new, geo_anom=traveling,
                  call=call, instructed=instructed, legit_tag=tag)
        elif kind == "relay":     # legitimate pass-through (e.g. student forwarding rent money)
            if a.usual_p2p:
                r_ = a.usual_p2p[int(rng.integers(0, len(a.usual_p2p)))]
                if r_.id != a.id and t >= r_.created_t:
                    do_tx(t, a, r_.id, p["amount"], "p2p_transfer", call=bool(rng.random() < 0.08),
                          instructed=False, legit_tag="relay")
        elif kind == "mule_warmup":
            rid = merchants[int(rng.choice(cfg.n_merchants, p=merchant_p))].id
            do_tx(t, a, rid, math.exp(rng.normal(a.mu, 0.5)), "card_payment", legit_tag="normal",
                  label=0)
        elif kind == "mule_forward":
            a.forward_scheduled = False
            if a.pending_forward <= 0:
                continue
            ring = rings[a.ring]
            if a.role == "layer1":
                rid = ring["collectors"][int(rng.integers(0, len(ring["collectors"])))].id
                ttype = "p2p_transfer"
            else:
                rid = externals[int(rng.integers(0, len(externals)))]
                ttype = "international_transfer"
            amt = a.pending_forward * rng.uniform(0.85, 0.98)
            a.pending_forward = 0.0
            do_tx(t, a, rid, amt, ttype, label=1, ftype="mule_layering", call=False,
                  instructed=False, event_id=f"ring-{a.ring}")
        elif kind in ("ato", "app"):
            rs = active_rings(t)
            if not rs:
                continue
            ring = rs[int(rng.integers(0, len(rs)))]
            base = math.exp(a.mu)
            if kind == "ato":
                n_tx = int(rng.integers(1, 4))
                hour_t = t if rng.random() < 0.7 else (t // H) * H + rng.uniform(0, 5)
                total = base * math.exp(rng.uniform(math.log(3), math.log(60)))
                geo = bool(rng.random() < 0.35)
                dev = bool(rng.random() < 0.6)    # the rest: remote-access malware / SIM swap on a known device
                tel_ok = bool(rng.random() < 0.7)
                mule = pick_mule(ring, t)
                tt = hour_t
                for k in range(n_tx):
                    share = total / n_tx * rng.uniform(0.7, 1.3)
                    r = do_tx(tt, a, mule.id, share, "p2p_transfer", mode="attacker", device_new=dev,
                              geo_anom=geo, telemetry_ok=tel_ok, call=False, instructed=False,
                              label=1, ftype="ato", event_id=p["event"])
                    tt += rng.uniform(0.08, 0.6)
                if rng.random() < cfg.report_rate:
                    mule.reported_at = min(mule.reported_at, t + rng.uniform(6, 72))
            else:
                n_tx = 2 if rng.random() < 0.3 else 1
                safe_account = rng.random() < 0.15
                call = bool(rng.random() < cfg.scam_call_rate)
                tt = t
                mule = pick_mule(ring, t)
                for k in range(n_tx):
                    amount = base * math.exp(rng.uniform(math.log(1.5), math.log(35)))
                    if safe_account:
                        rid, ttype = externals[int(rng.integers(0, len(externals)))], "international_transfer"
                    else:
                        rid, ttype = mule.id, "p2p_transfer"
                    instructed = bool(rng.random() < cfg.instruction_disclosure)
                    do_tx(tt, a, rid, amount, ttype, mode="stressed", call=call, instructed=instructed,
                          label=1, ftype="app_scam", event_id=p["event"])
                    tt += rng.uniform(1, 30)
                if not safe_account and rng.random() < cfg.report_rate:
                    mule.reported_at = min(mule.reported_at, t + rng.uniform(24, 120))
    df = pd.DataFrame(rows)
    df["day"] = (df["t_hours"] // 24).astype(int)
    df["split"] = np.where(df["day"] < 7, "burn_in",
                  np.where(df["day"] < 60, "train", np.where(df["day"] < 75, "validation", "test")))
    return df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--variant", default="default", choices=list(VARIANTS))
    args = ap.parse_args()
    cfg = GenConfig(seed=args.seed, variant=args.variant, **VARIANTS[args.variant])
    df = run(cfg)
    out = OUT_ROOT / (args.variant if args.seed == 42 else f"{args.variant}_seed{args.seed}")
    out.mkdir(parents=True, exist_ok=True)
    df.to_csv(out / "transactions.csv", index=False)
    meta = {
        "synthetic": True,
        "description": "Synthetic NeuroShield dataset. Not real banking data. See ml/data/generate.py.",
        "config": asdict(cfg),
        "rows": int(len(df)),
        "fraud_rate": float(df["is_fraud"].mean()),
        "by_split": df.groupby("split")["is_fraud"].agg(["count", "sum"]).reset_index().to_dict("records"),
        "by_fraud_type": df["fraud_type"].value_counts().to_dict(),
        "by_legit_tag": df["legit_tag"].value_counts().to_dict(),
        "split_rule": "time-based: days 0-6 burn-in (baselines forming, excluded), 7-59 train, 60-74 validation, 75-89 test",
    }
    (out / "generation_config.json").write_text(json.dumps(meta, indent=2, default=str))
    print(json.dumps({k: meta[k] for k in ("rows", "fraud_rate", "by_split", "by_fraud_type")}, indent=1, default=str))


if __name__ == "__main__":
    main()
