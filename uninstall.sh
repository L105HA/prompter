#!/usr/bin/env bash
# Prompter uninstaller — removes the installed program, command and service.
# The tmux session with your OpenCode TUI is NEVER touched.
set -euo pipefail

DEST="${PROMPTER_HOME:-$HOME/.local/share/prompter}"
BIN="${PROMPTER_BIN:-$HOME/.local/bin}/prompter"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE="prompter.service"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }

# Safety confirmation: this script disables services reachable via the
# current systemd user bus and deletes files. Interactive runs must confirm;
# non-interactive runs (scripts, tests) must pass --yes explicitly.
if [ "${1:-}" != "--yes" ]; then
    echo "This will stop the bridge, disable $SERVICE and remove:"
    echo "  $BIN"
    echo "  $DEST   (includes the auth token)"
    echo "  $UNIT_DIR/$SERVICE"
    if [ -t 0 ]; then
        printf "Continue? [y/N] "
        read -r answer
        case "$answer" in
            y|Y|yes|YES) ;;
            *) echo "Aborted."; exit 1 ;;
        esac
    else
        echo "Refusing to run non-interactively without --yes. Aborted."
        exit 1
    fi
fi

say "Stopping the bridge"
if command -v systemctl >/dev/null 2>&1 && systemctl --user is-active --quiet "$SERVICE" 2>/dev/null; then
    systemctl --user disable --now "$SERVICE" 2>/dev/null || true
fi
if [ -f "${XDG_STATE_HOME:-$HOME/.local/state}/prompter/bridge.pid" ]; then
    pid="$(cat "${XDG_STATE_HOME:-$HOME/.local/state}/prompter/bridge.pid")"
    kill "$pid" 2>/dev/null || true
fi

say "Removing service unit"
rm -f "$UNIT_DIR/$SERVICE"
command -v systemctl >/dev/null 2>&1 && systemctl --user daemon-reload 2>/dev/null || true

say "Removing command: $BIN"
rm -f "$BIN"

say "Removing program: $DEST (includes the auth token)"
rm -rf "$DEST"
rm -rf "${XDG_STATE_HOME:-$HOME/.local/state}/prompter"

echo "Prompter uninstalled. (tmux sessions were not touched.)"
