#!/usr/bin/env bash
# Integration test: spins up a throwaway bash tmux session + bridge on
# 127.0.0.1:8799, runs tests/test_ws.py, tears everything down.
# NEVER touches the live 'opencode' tmux session or port 8788.
set -euo pipefail
cd "$(dirname "$0")/.."
SESS=ocr-test
PORT=8799
PY=.venv/bin/python
[ -x "$PY" ] || PY=python3
LOG=/tmp/ocr-bridge-integ.log

tmux has-session -t "$SESS": 2>/dev/null && tmux kill-session -t "$SESS":
tmux new-session -d -s "$SESS" -x 220 -y 50

cleanup() {
  if [ -n "${SRV_PID:-}" ] && kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null || true
  fi
  tmux kill-session -t "$SESS": 2>/dev/null || true
}
trap cleanup EXIT

PROMPTER_TMUX_SESSION="$SESS" PROMPTER_PORT="$PORT" PROMPTER_HOST=127.0.0.1 \
  setsid nohup "$PY" -m uvicorn main:app --host 127.0.0.1 --port "$PORT" \
  >"$LOG" 2>&1 &
SRV_PID=$!

for _ in $(seq 1 50); do
  curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break
  sleep 0.2
done

BRIDGE_PORT=$PORT BRIDGE_SESSION=$SESS "$PY" tests/test_ws.py
