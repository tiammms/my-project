"""Pydantic v2 request/response schemas. These also generate the OpenAPI document."""
from __future__ import annotations

import math
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

TxType = Literal["card_payment", "bill_payment", "p2p_transfer", "international_transfer"]
ID_PATTERN = r"^[A-Za-z0-9_\-]{1,40}$"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SenderBaseline(Strict):
    median_amount: float = Field(..., gt=0, le=1e10, description="Median of the sender's past transaction amounts")
    log_std: float = Field(0.8, ge=0, le=5, description="Std of log(amount) over sender history")
    tx_count: int = Field(..., ge=0, le=10_000_000, description="Number of past transactions in the baseline")


class MetricBaseline(Strict):
    mean: float = Field(..., ge=0, le=100_000)
    std: float = Field(..., ge=0, le=100_000)


class BehavioralSession(Strict):
    typing_interval_mean_ms: Optional[float] = Field(None, ge=10, le=5000)
    typing_interval_cv: Optional[float] = Field(None, ge=0, le=5)
    confirm_pause_s: Optional[float] = Field(None, ge=0, le=600)
    correction_rate: Optional[float] = Field(None, ge=0, le=1)
    screen_sequence_deviation: Optional[float] = Field(None, ge=0, le=1)


class BehavioralBaseline(Strict):
    typing_interval_mean_ms: Optional[MetricBaseline] = None
    typing_interval_cv: Optional[MetricBaseline] = None
    confirm_pause_s: Optional[MetricBaseline] = None
    correction_rate: Optional[MetricBaseline] = None
    screen_sequence_deviation: Optional[MetricBaseline] = None


class BehavioralInput(Strict):
    consent: bool = Field(..., description="Customer consented to aggregated interaction telemetry. If false, the "
                                           "behavioral component is disabled and nothing is inferred.")
    session: Optional[BehavioralSession] = None
    baseline: Optional[BehavioralBaseline] = None
    baseline_sessions: int = Field(0, ge=0, le=100_000)
    screen_flow: Optional[Literal["new_payee", "known_payee"]] = Field(
        None, description="Screen flow the navigation baseline was built on. Must match this transfer: new_payee when "
                          "new_recipient is true for a p2p/international transfer, otherwise known_payee. If omitted, the "
                          "navigation baseline is assumed to be flow-matched.")
    screen_flow_sessions: Optional[int] = Field(None, ge=0, le=100_000,
        description="Past sessions of the same flow behind the navigation baseline; fewer than 3 = not comparable")


class GraphContext(Strict):
    recipient_distinct_senders_7d: Optional[int] = Field(None, ge=0, le=1_000_000)
    recipient_passthrough_ratio_7d: Optional[float] = Field(None, ge=0, le=1000)
    recipient_reported: bool = False
    recipient_linked_to_reported: bool = False
    sender_inbound_distinct_7d: Optional[int] = Field(None, ge=0, le=1_000_000)
    sender_passthrough_ratio_24h: Optional[float] = Field(None, ge=0, le=1000)


class TransactionIn(Strict):
    transaction_id: Optional[str] = Field(None, pattern=ID_PATTERN, description="Client reference; generated if omitted")
    amount: float = Field(..., gt=0, le=1e10, description="Amount in KZT (synthetic demo currency)")
    currency: Literal["KZT"] = "KZT"
    transaction_type: TxType
    transaction_hour: int = Field(..., ge=0, le=23)
    account_age_days: float = Field(..., ge=0, le=36500)
    recipient_age_days: Optional[float] = Field(None, ge=0, le=36500, description="null for external recipients")
    transactions_last_1h: int = Field(0, ge=0, le=100_000)
    transactions_last_24h: int = Field(0, ge=0, le=1_000_000)
    new_recipient: bool
    geographic_anomaly: Optional[bool] = None
    device_anomaly: Optional[bool] = None
    active_call_during_session: Optional[bool] = Field(None, description="Consent-based session context; null if unknown")
    user_reported_external_instruction: Optional[bool] = Field(
        None, description="Answer to in-app question 'Is someone telling you to make this transfer?'; null if not asked")
    sender_baseline: Optional[SenderBaseline] = None
    behavioral: Optional[BehavioralInput] = None
    graph: Optional[GraphContext] = None
    sender_ref: Optional[str] = Field(None, pattern=ID_PATTERN, description="Pseudonymous sender reference (not a model feature)")
    recipient_ref: Optional[str] = Field(None, pattern=ID_PATTERN, description="Pseudonymous recipient reference (not a model feature)")
    persist: bool = Field(True, description="Store the decision in the demo event log")

    @field_validator("amount")
    @classmethod
    def finite(cls, v: float) -> float:
        if not math.isfinite(v):
            raise ValueError("amount must be finite")
        return v

    @model_validator(mode="after")
    def flow_matches(self):
        b = self.behavioral
        if b and b.screen_flow:
            flow = "new_payee" if (self.new_recipient and self.transaction_type in ("p2p_transfer", "international_transfer")) else "known_payee"
            if b.screen_flow != flow:
                raise ValueError(f"behavioral.screen_flow must be '{flow}' for this transfer (navigation baselines are flow-specific)")
        return self

    @field_validator("transactions_last_24h")
    @classmethod
    def velocity_consistent(cls, v: int, info) -> int:
        h1 = info.data.get("transactions_last_1h")
        if h1 is not None and v < h1:
            raise ValueError("transactions_last_24h must be >= transactions_last_1h")
        return v

    model_config = ConfigDict(extra="forbid", json_schema_extra={"examples": [{
        "amount": 450000, "transaction_type": "p2p_transfer", "transaction_hour": 19,
        "account_age_days": 2400, "recipient_age_days": 9, "transactions_last_1h": 0, "transactions_last_24h": 1,
        "new_recipient": True, "geographic_anomaly": False, "device_anomaly": False,
        "active_call_during_session": True, "user_reported_external_instruction": True,
        "sender_baseline": {"median_amount": 18000, "log_std": 0.8, "tx_count": 140},
        "behavioral": {"consent": True, "baseline_sessions": 60, "screen_flow": "new_payee", "screen_flow_sessions": 6,
                       "session": {"typing_interval_mean_ms": 205, "typing_interval_cv": 0.37, "confirm_pause_s": 9.5,
                                   "correction_rate": 0.21, "screen_sequence_deviation": 0.45},
                       "baseline": {"typing_interval_mean_ms": {"mean": 190, "std": 14},
                                    "typing_interval_cv": {"mean": 0.36, "std": 0.035},
                                    "confirm_pause_s": {"mean": 2.3, "std": 0.8},
                                    "correction_rate": {"mean": 0.06, "std": 0.02},
                                    "screen_sequence_deviation": {"mean": 0.22, "std": 0.07}}},
        "graph": {"recipient_distinct_senders_7d": 6, "recipient_passthrough_ratio_7d": 0.92,
                  "recipient_reported": False, "recipient_linked_to_reported": True}}]})


class BatchIn(Strict):
    transactions: list[TransactionIn] = Field(..., min_length=1)


class RiskFactor(BaseModel):
    group: str
    label: str
    contribution: float = Field(..., description="Change in model log-odds when this group is replaced by a typical legitimate value")
    direction: Literal["increases_risk", "decreases_risk"]
    detail: str


class ComponentScores(BaseModel):
    transaction_model_score: float
    behavioral_anomaly_score: Optional[float]
    behavioral_status: str
    behavioral_reliability: str
    graph_risk_score: float
    social_engineering_risk_score: float
    recipient_risk_score: float


class RiskResult(BaseModel):
    transaction_id: str
    risk_score: float = Field(..., description="0-100 risk score = 100 x model output. Calibration is reported in the model card; "
                                                "treat as a ranking score, not a guaranteed probability.")
    risk_level: Literal["LOW", "MEDIUM", "HIGH"]
    recommended_action: Literal["ALLOW", "STEP_UP_VERIFICATION", "MANUAL_REVIEW", "REVIEW_OR_TEMPORARY_HOLD"]
    action_description: str
    confidence: Literal["high", "medium", "low"]
    confidence_notes: list[str]
    transaction_model_score: float
    behavioral_anomaly_score: Optional[float]
    graph_risk_score: float
    social_engineering_risk_score: float
    recipient_risk_score: float
    components: dict
    top_risk_factors: list[RiskFactor]
    mitigating_factors: list[RiskFactor]
    policy_version: str
    policy_mode: str
    thresholds: dict
    model_version: str
    evaluation_warnings: list[str]
    processing_ms: float
    simulated: bool = Field(True, description="Always true: recommendations are not connected to any payment system")


class BatchOut(BaseModel):
    results: list[RiskResult]
    count: int
    processing_ms: float


class ScenarioIn(Strict):
    scenario: Literal["normal_transfer", "social_engineering", "account_takeover", "legit_large_first_transfer",
                      "traveling_customer", "new_phone", "no_behavioral_data", "changed_typing_habits"]


class SettingsIn(Strict):
    mode: Optional[Literal["balanced", "strict"]] = None
    hold_recommendations_enabled: Optional[bool] = None
    young_recipient_override: Optional[bool] = None


class ErrorOut(BaseModel):
    error: str
    detail: Optional[object] = None
    request_id: Optional[str] = None
