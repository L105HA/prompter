import asyncio
import hashlib
import json
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, WebSocketException, status
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

from config import HOST, PORT, TOKEN, TMUX_SESSION
import tmux_ctl

app = FastAPI(title="Prompter Bridge", version="1.0.0")

# WS traffic is not subject to CORS; this only guards the plain HTTP endpoints.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # LAN-only tool; no credentials are used
    allow_methods=["*"],
    allow_headers=["*"],
)

AUTH_TIMEOUT = 5.0
SCREEN_MIN_INTERVAL = 0.2   # sec, per subscriber
SCREEN_DEFAULT_INTERVAL_MS = 700
SCREEN_BUSY_INTERVAL = 0.5  # sec; polls faster while a spinner is visible
SCROLLBACK_MIN = -10000     # deepest history offset
SCROLLBACK_MAX_LINES = 1000


@app.get("/health")
async def health():
    try:
        exists = await asyncio.to_thread(tmux_ctl.session_exists)
    except Exception:
        exists = False
    return {
        "status": "ok",
        "tmux_session": TMUX_SESSION,
        "tmux_exists": exists,
        "port": PORT,
    }


def _bearer_token(header: str) -> str:
    if header.lower().startswith("bearer "):
        return header[7:].strip()
    return ""


SCREEN_RUNS_MAX_BYTES = 768_000  # fall back to plain text if runs JSON exceeds this


def screen_payload(plain: str, runs, kind: str = "screen", **extra) -> dict:
    """{"type":kind,"text":...} plus compact "runs"/"tabs"/"busy" when available."""
    msg = {"type": kind, "text": plain}
    msg.update(extra)
    try:
        if len(json.dumps(runs, separators=(",", ":"))) <= SCREEN_RUNS_MAX_BYTES:
            msg["runs"] = runs
            tabs = tmux_ctl.parse_tabs(runs)
            if tabs:
                msg["tabs"] = tabs
            msg["busy"] = tmux_ctl.detect_busy(runs)
    except (TypeError, ValueError):
        pass
    return msg


class ScreenStreamer:
    """Per-connection capture loop: pushes the pane text whenever it changes."""

    def __init__(self, websocket: WebSocket):
        self.ws = websocket
        self._refresh = asyncio.Event()
        self._task: Optional[asyncio.Task] = None
        self._last_hash: Optional[str] = None
        self._cols = 0  # >0 crops each line to this many chars (hides sidebar)
        self._trim = False  # drop trailing input-box/status decoration
        self._interval = SCREEN_DEFAULT_INTERVAL_MS / 1000.0

    def start(self, interval_ms: int, cols: int = 0, trim: bool = False):
        self.stop()
        self._interval = max(int(interval_ms) / 1000.0, SCREEN_MIN_INTERVAL)
        try:
            self._cols = max(0, min(int(cols or 0), 500))
        except (TypeError, ValueError):
            self._cols = 0
        self._trim = bool(trim)
        self._task = asyncio.create_task(self._loop())

    def stop(self):
        if self._task:
            self._task.cancel()
            self._task = None

    def poke(self):
        """Request an immediate refresh (called after each injected command)."""
        self._refresh.set()

    async def _loop(self):
        try:
            while True:
                busy = False
                try:
                    plain, runs, _raw = await asyncio.to_thread(
                        tmux_ctl.capture_screen_ansi, self._cols, self._trim
                    )
                except tmux_ctl.TmuxError as e:
                    await self.ws.send_json({"type": "screen_error", "error": str(e)})
                    self._last_hash = None
                else:
                    busy = tmux_ctl.detect_busy(runs)
                    # Hash the (possibly cropped) content: sidebar-only changes
                    # outside the crop must not trigger pushes.
                    runs_json = json.dumps(runs, separators=(",", ":"))
                    h = hashlib.sha256((plain + "\x00" + runs_json).encode()).hexdigest()
                    if h != self._last_hash:
                        self._last_hash = h
                        await self.ws.send_json(screen_payload(plain, runs))
                self._refresh.clear()
                # Adaptive: poll faster while the agent is generating.
                interval = (
                    min(self._interval, SCREEN_BUSY_INTERVAL) if busy
                    else self._interval
                )
                try:
                    await asyncio.wait_for(self._refresh.wait(), timeout=interval)
                except asyncio.TimeoutError:
                    pass
        except asyncio.CancelledError:
            pass


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    header = websocket.headers.get("authorization", "")
    if header:
        # Header auth: reject a bad token before the handshake completes.
        if _bearer_token(header) != TOKEN:
            raise WebSocketException(
                code=status.WS_1008_POLICY_VIOLATION, reason="invalid token"
            )
        await websocket.accept()
    else:
        # No header: accept and expect {"type":"auth","token":...} as first message.
        await websocket.accept()
        authed = False
        try:
            raw = await asyncio.wait_for(websocket.receive_text(), timeout=AUTH_TIMEOUT)
            data = json.loads(raw)
            if data.get("type") == "auth" and data.get("token") == TOKEN:
                authed = True
                await websocket.send_json({"type": "auth_ok"})
            else:
                await websocket.send_json({"type": "auth_fail"})
        except asyncio.TimeoutError:
            await websocket.send_json({"type": "auth_fail", "error": "timeout"})
        except Exception as e:
            await websocket.send_json({"type": "auth_fail", "error": str(e)})
        if not authed:
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
            return

    await websocket.send_json({"type": "ready", "session": TMUX_SESSION})
    streamer = ScreenStreamer(websocket)

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "error": "invalid_json"})
                continue

            cmd = data.get("cmd") or data.get("type")
            try:
                if cmd == "type":
                    text = data.get("text", "")
                    submit = bool(data.get("submit", False))
                    await asyncio.to_thread(tmux_ctl.send_text, text, submit)
                    await websocket.send_json({"type": "ok", "cmd": "type"})
                elif cmd == "key":
                    await asyncio.to_thread(tmux_ctl.press, data.get("key", ""))
                    await websocket.send_json({"type": "ok", "cmd": "key"})
                elif cmd == "combo":
                    mods = data.get("mods", [])
                    key = data.get("key", "")
                    await asyncio.to_thread(tmux_ctl.combo, mods, key)
                    await websocket.send_json({"type": "ok", "cmd": "combo"})
                elif cmd == "action":
                    act = data.get("id") or data.get("action", "")
                    await asyncio.to_thread(tmux_ctl.action, act)
                    await websocket.send_json({"type": "ok", "cmd": "action"})
                elif cmd == "raw":
                    keys = data.get("keys", "")
                    await asyncio.to_thread(tmux_ctl.send_keys_raw, keys)
                    await websocket.send_json({"type": "ok", "cmd": "raw"})
                elif cmd == "screen":
                    cols = data.get("cols", 0)
                    trim = bool(data.get("trim", False))
                    plain, runs, _ = await asyncio.to_thread(
                        tmux_ctl.capture_screen_ansi, cols, trim
                    )
                    await websocket.send_json(screen_payload(plain, runs))
                elif cmd == "scrollback":
                    try:
                        frm = max(int(data.get("from", -300)), SCROLLBACK_MIN)
                        to = int(data.get("to", -1))
                    except (TypeError, ValueError):
                        frm, to = 0, 0
                    cols = data.get("cols", 0)
                    if not (frm < to <= -1) or (to - frm) > SCROLLBACK_MAX_LINES:
                        await websocket.send_json(
                            {"type": "error", "cmd": "scrollback",
                             "error": "bad_range"}
                        )
                        continue
                    plain, runs = await asyncio.to_thread(
                        tmux_ctl.capture_scrollback_ansi, frm, to, cols
                    )
                    await websocket.send_json(
                        screen_payload(plain, runs, kind="scrollback",
                                       **{"from": frm, "to": to})
                    )
                elif cmd == "subscribe_screen":
                    interval = int(data.get("interval_ms", SCREEN_DEFAULT_INTERVAL_MS))
                    cols = data.get("cols", 0)
                    trim = bool(data.get("trim", False))
                    streamer.start(interval, cols, trim)
                    await websocket.send_json({"type": "ok", "cmd": "subscribe_screen"})
                elif cmd == "unsubscribe_screen":
                    streamer.stop()
                    await websocket.send_json({"type": "ok", "cmd": "unsubscribe_screen"})
                elif cmd == "ping":
                    await websocket.send_json({"type": "pong"})
                else:
                    await websocket.send_json(
                        {"type": "error", "error": f"unknown_cmd:{cmd}"}
                    )
                if cmd in ("type", "key", "combo", "action", "raw"):
                    streamer.poke()
            except tmux_ctl.TmuxError as e:
                await websocket.send_json({"type": "error", "cmd": cmd, "error": str(e)})
            except Exception as e:
                await websocket.send_json({"type": "error", "cmd": cmd, "error": str(e)})
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        streamer.stop()


if __name__ == "__main__":
    uvicorn.run("main:app", host=HOST, port=PORT, reload=False)
