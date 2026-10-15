"""Runtime configuration from environment variables (see .env.example)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _load_dotenv() -> None:
    """Minimal .env loader (no external dependency). Real env vars take precedence."""
    p = ROOT / ".env"
    if not p.exists():
        return
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_dotenv()


@dataclass(frozen=True)
class Settings:
    env: str = field(default_factory=lambda: _env("NS_ENV", "development"))
    db_path: Path = field(default_factory=lambda: Path(_env("NS_DB_PATH", str(ROOT / "backend/data/neuroshield.db"))))
    model_path: Path = field(default_factory=lambda: Path(_env("NS_MODEL_PATH", str(ROOT / "ml/artifacts/model.joblib"))))
    policy_path: Path = field(default_factory=lambda: Path(_env("NS_POLICY_PATH", str(ROOT / "ml/artifacts/policy.json"))))
    demo_data_path: Path = field(default_factory=lambda: Path(_env("NS_DEMO_DATA_PATH", str(ROOT / "ml/artifacts/demo_transactions.csv.gz"))))
    results_dir: Path = field(default_factory=lambda: Path(_env("NS_RESULTS_DIR", str(ROOT / "experiments/results"))))
    frontend_dist: Path = field(default_factory=lambda: Path(_env("NS_FRONTEND_DIST", str(ROOT / "frontend/dist"))))
    cors_origins: tuple = field(default_factory=lambda: tuple(
        o.strip() for o in _env("NS_CORS_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173").split(",") if o.strip()))
    max_body_bytes: int = field(default_factory=lambda: int(_env("NS_MAX_BODY_BYTES", "262144")))
    bio_max_body_bytes: int = field(default_factory=lambda: int(_env("NS_BIO_MAX_BODY_BYTES", str(24 * 1024 * 1024))))
    batch_limit: int = field(default_factory=lambda: int(_env("NS_BATCH_LIMIT", "100")))
    rate_limit_per_minute: int = field(default_factory=lambda: int(_env("NS_RATE_LIMIT_PER_MINUTE", "600")))
    admin_token: str = field(default_factory=lambda: _env("NS_ADMIN_TOKEN", ""))
    log_level: str = field(default_factory=lambda: _env("NS_LOG_LEVEL", "INFO"))


settings = Settings()
