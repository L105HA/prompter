#!/usr/bin/env bash
# Unit tests: pure parser logic, no tmux / no server needed.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=.venv/bin/python
[ -x "$PY" ] || PY=python3
fail=0
for t in tests/test_screen.py tests/test_tabs.py; do
  echo "== $t"
  "$PY" "$t" || fail=1
done
exit $fail
