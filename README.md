# Prompter

**Voice remote control for [OpenCode](https://opencode.ai)** — drive your AI coding agent from the couch, or from anywhere in the world.

🌐 **Site:** [l105ha.github.io/prompter](https://l105ha.github.io/prompter/) · ✈️ **Telegram:** [@L105HA](https://t.me/L105HA)

Prompter mirrors the OpenCode TUI (running inside tmux) to your phone and lets you talk to it: dictate messages, tap TUI keys, switch sessions — with a live color preview of what the agent is doing.

```
┌──────────────┐   WebSocket (LAN / Tailscale)   ┌─────────────────┐
│  Android app │ ◄──────────────────────────────► │  Bridge (Python) │
│  voice + keys│     token auth, TLS optional     │  systemd service │
└──────────────┘                                  └────────┬────────┘
                                                           │ tmux send-keys / capture-pane
                                                   ┌───────▼────────┐
                                                   │ tmux + OpenCode │
                                                   └────────────────┘
```

Nothing leaves your machine: the bridge binds to your LAN, and remote access goes through your own private [Tailscale](https://tailscale.com) network. No ports are opened to the internet.

## Features

- 🖥 **Live screen mirror** — true-color ANSI rendering of the TUI, with wrap/pan modes, font & width controls, and a compact mode that hides the non-functional input zone
- 🎙 **Voice input** — dictate a message, edit it, send
- ⌨️ **TUI keys** — arrows (auto-repeat), Enter/Esc/Tab, Ctrl-combos, PageUp/PageDown, backspace
- 🗂 **Sessions** — live tab bar of OpenCode sessions; tap to switch, create or close
- 🔔 **Notifications** — “generation finished” right on your phone; faster screen updates while the agent works
- 📜 **History scrollback** — page up through tmux scrollback from the preview
- ⚡ **Macros** — define your own button sequences (`text! deploy`, `key Enter`, `combo ctrl x`…)
- 🔗 **QR connect** — `prompt` your phone with one scan; second QR for Tailscale access
- 🌍 **RU/EN interface**, dark/light theme, works over Wi-Fi or the internet

## Quick start

**Laptop (the machine running OpenCode):**

```bash
git clone https://github.com/<you>/prompter && cd prompter
./install.sh          # no root needed; any distro
prompter              # starts everything, prints QR codes
```

Requirements: Python ≥ 3.10, tmux. The installer puts the program in `~/.local/share/prompter`, the `prompter` command on your PATH, and a systemd user service (falls back to background mode without systemd).

**Phone:** install the APK ([releases](../../releases)) and scan the QR code from `prompter` (Settings → *Scan QR code*). The token, host and port are applied automatically.

**Uninstall:** `./uninstall.sh` — removes everything except your tmux sessions.

## CLI

```
prompter            # start: tmux session + bridge + QR codes (default)
prompter attach     # open the TUI in a local terminal window
prompter status     # bridge / tmux / tailscale summary
prompter qr         # reprint the QR code(s)
prompter stop       # stop the bridge (TUI keeps running)
prompter logs       # follow bridge logs
```

The OpenCode TUI runs hidden in a detached tmux session named `opencode`. Attach manually with `tmux attach -t opencode`.

## Access over the internet (Tailscale)

1. Install Tailscale on the laptop and on the phone, log in to the same account.
2. `prompter` auto-enables it (with `--accept-dns=false --exit-node=` so your normal internet keeps working) and prints a second QR with the tailnet IP (`100.x.y.z`).

Your traffic is end-to-end encrypted (WireGuard) and your machine is not exposed publicly — the token never has to face the open internet.

## Security

- Auth: random 64-hex token in `~/.local/share/prompter/bridge/.token` (mode 0600), sent as an `Authorization: Bearer` header — never in URLs or logs
- Rotate the token: `rm ~/.local/share/prompter/bridge/.token && prompter restart`, then re-scan the QR
- ⚠️ The bridge injects keystrokes into your agent — treat the token like a password

## Building the Android app

Requires JDK 17–21 and the Android SDK (API 35).

```bash
cd android
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Protocol (WebSocket)

Connect to `ws://<host>:8788/ws` with the `Authorization: Bearer <token>` header (or send `{"type":"auth","token":...}` as the first message).

Commands: `cmd` = `type` (text + optional submit), `key`, `combo`, `action`, `raw`, `ping`, `screen` (snapshot with `cols`/`trim` options), `subscribe_screen` (push on change, `interval_ms`/`cols`/`trim`), `scrollback` (`from`/`to` negative line offsets), `unsubscribe_screen`.

Screen payloads carry `text`, optional `runs` (per-line style runs: `{"t":text,"f":fg,"b":bg,"a":attrs}`), optional `tabs` (session tab bar), `busy` (agent generating), and `kind` (`screen`/`scrollback`).

## License

[MIT](LICENSE)
