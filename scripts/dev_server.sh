#!/usr/bin/env bash
# Start / stop the NeuroShield backend in the background (dev helper).
#   scripts/dev_server.sh start [--fresh]   |   scripts/dev_server.sh stop
set -euo pipefail
cd "$(dirname "$0")/.."
PIDFILE=backend/data/server.pid
LOG=backend/data/server.log
mkdir -p backend/data
case "${1:-start}" in
  stop)
    if [ -f "$PIDFILE" ]; then
      PID="$(cat "$PIDFILE")"; kill "$PID" 2>/dev/null || true
      for i in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done   # wait until the port is free
    fi
    rm -f "$PIDFILE"; echo stopped ;;
  start)
    "$0" stop > /dev/null
    if [ "${2:-}" = "--fresh" ]; then rm -f backend/data/neuroshield.db*; fi
    nohup python3 -m backend.app.main > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    for i in $(seq 1 60); do
      if curl -sf http://127.0.0.1:8000/health > /dev/null; then echo "up (pid $(cat $PIDFILE))"; exit 0; fi
      sleep 1
    done
    echo "server did not start; see $LOG"; tail -20 "$LOG"; exit 1 ;;
esac
