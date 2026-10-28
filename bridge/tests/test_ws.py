"""Integration test: WS protocol against an isolated bash tmux session.

Launched by run_integration.sh, which starts a throwaway bridge on
127.0.0.1:$BRIDGE_PORT bound to tmux session $BRIDGE_SESSION (bash, NOT the
live opencode session). Fixtures are typed through the WS itself.
"""
import asyncio
import json
import os

from websockets.asyncio.client import connect

PORT = os.environ.get("BRIDGE_PORT", "8799")
SESSION = os.environ.get("BRIDGE_SESSION", "ocr-test")
URL = f"ws://127.0.0.1:{PORT}/ws"
TOKEN_FILE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".token"
)
TOKEN = open(TOKEN_FILE).read().strip()

PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
    else:
        FAIL += 1
    print(f" {'ok' if cond else 'FAIL':>4} {name} {extra if not cond else ''}")


async def recv_type(ws, kind, timeout=5):
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if msg.get("type") == kind:
            return msg
        if msg.get("type") == "screen_error":
            raise RuntimeError(f"screen_error: {msg}")


async def main():
    # 1. Bad token in header -> rejected with HTTP 403 before the handshake.
    try:
        async with connect(URL, additional_headers={"Authorization": "Bearer wrong"}) as ws:
            await ws.recv()
        check("bad header token rejected", False)
    except Exception as e:
        check("bad header token rejected",
              "403" in str(e) or "policy" in str(e).lower(), str(e))

    # 2. First-message auth works and ready carries the session name.
    async with connect(URL) as ws:
        await ws.send(json.dumps({"type": "auth", "token": TOKEN}))
        msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
        check("first-message auth", msg.get("type") == "auth_ok", str(msg))
        msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
        check("ready carries session",
              msg.get("type") == "ready" and msg.get("session") == SESSION, str(msg))

    async with connect(URL, additional_headers={"Authorization": f"Bearer {TOKEN}"}) as ws:
        await recv_type(ws, "ready")

        # 3. ping/pong
        await ws.send(json.dumps({"cmd": "ping"}))
        check("ping/pong", (await recv_type(ws, "pong")).get("type") == "pong")

        # 4. type + Enter renders a fixture; screen reflects it.
        await ws.send(json.dumps({"cmd": "type",
                                  "text": "printf 'INTEG-A\\nINTEG-B\\n'",
                                  "submit": True}))
        await recv_type(ws, "ok")
        await asyncio.sleep(0.6)
        await ws.send(json.dumps({"cmd": "screen"}))
        scr = await recv_type(ws, "screen")
        check("typed command visible", "INTEG-A" in scr.get("text", ""))
        check("screen carries busy flag", "busy" in scr, str(scr.keys()))
        check("bash header: not busy", scr.get("busy") is False)
        check("bash header: no tabs", "tabs" not in scr, str(scr.get("tabs")))

        # 5. cols crops each line.
        await ws.send(json.dumps({"cmd": "screen", "cols": 5}))
        scr = await recv_type(ws, "screen")
        check("cols crop",
              all(len(ln) <= 5 for ln in scr.get("text", "").split("\n")),
              repr(scr.get("text", "")[:80]))

        # 6. scrollback: 400 numbered lines, visible keeps ~last 50.
        await ws.send(json.dumps({"cmd": "type", "text": "clear; seq 1 400",
                                  "submit": True}))
        await recv_type(ws, "ok")
        await asyncio.sleep(0.8)
        await ws.send(json.dumps({"cmd": "screen"}))
        scr = await recv_type(ws, "screen")
        check("seq rendered", "400" in scr.get("text", ""))
        await ws.send(json.dumps({"cmd": "scrollback", "from": -60, "to": -11,
                                  "cols": 220}))
        sb = await recv_type(ws, "scrollback")
        sbt = sb.get("text", "")
        check("scrollback range echoed",
              sb.get("from") == -60 and sb.get("to") == -11, str(sb.get("from")))
        check("scrollback has older lines", "330" in sbt, repr(sbt[:120]))
        check("scrollback excludes visible", "400" not in sbt, repr(sbt[-80:]))

        # 7. bad scrollback range -> error, not crash.
        await ws.send(json.dumps({"cmd": "scrollback", "from": -10, "to": -50}))
        msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
        check("scrollback bad range",
              msg.get("type") == "error" and msg.get("error") == "bad_range", str(msg))

        # 8. subscribe: typing a marker produces pushes that carry it.
        await ws.send(json.dumps({"cmd": "subscribe_screen", "interval_ms": 300}))
        await recv_type(ws, "ok")
        await recv_type(ws, "screen")  # initial push
        await ws.send(json.dumps({"cmd": "type", "text": "echo PUSHMARK-42",
                                  "submit": True}))
        found = False
        for _ in range(12):
            pushed = await recv_type(ws, "screen", timeout=5)
            if "PUSHMARK-42" in pushed.get("text", ""):
                found = True
                break
        check("subscribe pushes changes", found)

        # 9. unsubscribe stops pushes.
        await ws.send(json.dumps({"cmd": "unsubscribe_screen"}))
        await recv_type(ws, "ok")
        await ws.send(json.dumps({"cmd": "type", "text": "echo AFTER-UNSUB",
                                  "submit": True}))
        await recv_type(ws, "ok")
        got_push = False
        try:
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), 1.2))
                if msg.get("type") == "screen":
                    got_push = True
        except asyncio.TimeoutError:
            pass
        check("no pushes after unsubscribe", not got_push)

    print(f"\n{PASS} passed, {FAIL} failed")
    raise SystemExit(1 if FAIL else 0)


asyncio.run(main())
