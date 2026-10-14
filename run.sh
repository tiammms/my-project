#!/usr/bin/env bash
# NeuroShield: install dependencies and start API + dashboard on http://127.0.0.1:8000 (Linux/macOS)
set -euo pipefail
cd "$(dirname "$0")"
python3 -m pip install -r requirements.txt
python3 -m backend.app.main
