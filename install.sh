#!/usr/bin/env bash
# Prompter installer — no root required. Works on any Linux distro.
#
#   ./install.sh                 install to ~/.local/share/prompter (+ systemd user service)
#   ./install.sh --no-systemd    install without systemd (bridge runs in background)
#
# Files created:
#   ~/.local/share/prompter/          program (bridge, CLI)
#   ~/.local/bin/prompter             command on PATH
#   ~/.config/systemd/user/prompter.service   (unless --no-systemd)
set -euo pipefail

SRC="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
DEST="${PROMPTER_HOME:-$HOME/.local/share/prompter}"
BIN_DIR="${PROMPTER_BIN:-$HOME/.local/bin}"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE="prompter.service"
PORT="${PROMPTER_PORT:-8788}"
TMUX_SESSION="${PROMPTER_TMUX_SESSION:-opencode}"
USE_SYSTEMD=1
[ "${1:-}" = "--no-systemd" ] && USE_SYSTEMD=0

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m ->\033[0m %s\n' "$*"; }

# ---------------------------------------------------------- requirements ---

missing=()

python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null \
    || { warn "Python >= 3.10 required"; missing+=(python3); }
python3 -c 'import venv' 2>/dev/null || missing+=python3-venv
command -v tmux >/dev/null 2>&1 || missing+=(tmux)

if [ ${#missing[@]} -gt 0 ]; then
    warn "Missing: ${missing[*]}"
    echo "  Debian/Ubuntu:  sudo apt install ${missing[*]}"
    echo "  Fedora:         sudo dnf install ${missing[*]}"
    echo "  Arch:           sudo pacman -S ${missing[*]/python3-venv/python}"
    echo "  openSUSE:       sudo zypper install ${missing[*]}"
    exit 1
fi

# --------------------------------------------------------------- install ---

say "Installing to $DEST"
mkdir -p "$DEST" "$BIN_DIR" "$DEST/bin"
tar -C "$SRC" --exclude='.venv' --exclude='__pycache__' --exclude='.token' \
    -cf - bridge | tar -C "$DEST" -xf -
cp "$SRC/bin/prompter" "$DEST/bin/prompter"
chmod +x "$DEST/bin/prompter"

# keep an existing token (phone keeps working across reinstalls/updates)
if [ ! -f "$DEST/bridge/.token" ] && [ -f "$SRC/bridge/.token" ]; then
    cp "$SRC/bridge/.token" "$DEST/bridge/.token"
fi

say "Creating Python virtual environment"
if [ ! -x "$DEST/bridge/.venv/bin/uvicorn" ]; then
    python3 -m venv "$DEST/bridge/.venv"
    "$DEST/bridge/.venv/bin/pip" -q install -r "$DEST/bridge/requirements.txt"
fi

say "Installing command to $BIN_DIR/prompter"
cat >"$BIN_DIR/prompter" <<EOF
#!/usr/bin/env bash
exec "$DEST/bin/prompter" "\$@"
EOF
chmod +x "$BIN_DIR/prompter"

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) warn "$BIN_DIR is not in your PATH — add it to your shell profile:"
       echo "        export PATH=\"\$HOME/.local/bin:\$PATH\"" ;;
esac

if [ "$USE_SYSTEMD" = 1 ] && command -v systemctl >/dev/null 2>&1 \
        && systemctl --user show-environment >/dev/null 2>&1; then
    say "Installing systemd user service"
    mkdir -p "$UNIT_DIR"
    cat >"$UNIT_DIR/$SERVICE" <<EOF
[Unit]
Description=Prompter bridge (tmux remote control for OpenCode)
After=network-online.target

[Service]
WorkingDirectory=$DEST/bridge
ExecStart=$DEST/bridge/.venv/bin/uvicorn main:app --host 0.0.0.0 --port $PORT
Environment=PROMPTER_PORT=$PORT
Environment=PROMPTER_TMUX_SESSION=$TMUX_SESSION
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable "$SERVICE" >/dev/null 2>&1
    echo "      enabled (starts on login; manage: systemctl --user {start|stop|status} $SERVICE)"
else
    warn "systemd user session not available — the bridge will run in background mode"
    warn "autostart on login is up to you (e.g. add 'prompter start' to your profile)"
fi

# migrate from the old pre-release name, if present (systemd installs only)
if [ "$USE_SYSTEMD" = 1 ] && systemctl --user is-enabled --quiet opencode-bridge.service 2>/dev/null; then
    say "Migrating from the old opencode-bridge service"
    systemctl --user disable --now opencode-bridge.service 2>/dev/null || true
    rm -f "$UNIT_DIR/opencode-bridge.service"
    systemctl --user daemon-reload
fi

say "Done. Start with:   prompter"
echo "  then scan the QR code with the phone app."
echo "  Uninstall:         $SRC/uninstall.sh   (or ~/.local/share/prompter/uninstall.sh)"
