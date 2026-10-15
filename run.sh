#!/usr/bin/env bash
# NeuroShield: install dependencies and start API + dashboard on http://127.0.0.1:8000 (Linux/macOS)
set -euo pipefail
cd "$(dirname "$0")"
python3 -m pip install -r requirements.txt
python3 -m pip install -r requirements-voice.txt || echo "WARNING: speech-to-text not installed (voice check works with typed text)"
python3 -m scripts.download_models || echo "WARNING: face models not downloaded - check the connection and re-run"
NS_OPEN_BROWSER=1 python3 -m backend.app.main
