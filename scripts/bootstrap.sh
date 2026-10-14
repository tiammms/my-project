#!/usr/bin/env bash
# Kept for Linux/macOS convenience; the real pipeline is the cross-platform scripts/bootstrap.py
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m scripts.bootstrap "$@"
