import re
import subprocess
from config import TMUX_SESSION

class TmuxError(Exception):
    pass

def _sess() -> str:
    """Unambiguous session target.

    A bare session name can ALSO match a WINDOW name in another session
    (tmux resolves bare target-pane names against window titles when no
    client context exists) — e.g. the sandbox session ocr-tui runs the
    opencode binary, so its window is literally named 'opencode' and
    `capture-pane -t opencode` from the systemd service captured the WRONG
    pane. The trailing colon forces session:window parsing.
    """
    return TMUX_SESSION + ":"

def _run(args: list[str]) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            ["tmux", *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
        )
    except subprocess.TimeoutExpired:
        raise TmuxError("tmux command timed out")
    except FileNotFoundError:
        raise TmuxError("tmux not found")
    except Exception as e:
        raise TmuxError(str(e))

def session_exists() -> bool:
    res = _run(["has-session", "-t", _sess()])
    return res.returncode == 0

def send_keys_raw(keys: str):
    res = _run(["send-keys", "-t", _sess(), keys])
    if res.returncode != 0:
        raise TmuxError(res.stderr.decode().strip() or "send-keys failed")
    return True

def send_text(text: str, submit: bool = False):
    if not text:
        if submit:
            send_keys_raw("Enter")
        return True
    # Send in chunks for very long text
    chunk_size = 200
    for i in range(0, len(text), chunk_size):
        chunk = text[i : i + chunk_size]
        res = _run(["send-keys", "-t", _sess(), "-l", "--", chunk])
        if res.returncode != 0:
            raise TmuxError(res.stderr.decode().strip() or "send-keys failed")
        if i + chunk_size < len(text):
            _run(["send-keys", "-t", _sess(), "-l", "--", ""])  # no-op guard
            import time
            time.sleep(0.01)
    if submit:
        send_keys_raw("Enter")
    return True

def capture_screen() -> str:
    """Visible content of the session's active pane (trailing blanks stripped)."""
    res = _run(["capture-pane", "-p", "-t", _sess()])
    if res.returncode != 0:
        raise TmuxError(res.stderr.decode().strip() or "capture-pane failed")
    text = res.stdout.decode(errors="replace")
    lines = [ln.rstrip() for ln in text.splitlines()]
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


# --- ANSI (SGR) capture: colored pane for the phone preview -------------------
#
# `capture-pane -e` emits the pane text with SGR escape sequences. We parse
# them into per-line "runs" of uniform style so the app can render an
# AnnotatedString without knowing anything about terminal escapes.
#
# Run format (kept compact for the wire): {"t": text} plus optional
#   "f" fg / "b" bg  -> "#rrggbb" or decimal 0..255 (xterm palette index)
#   "a" attrs        -> chars from "bdiur" (bold/dim/italic/underline/reverse)

_CSI_RE = re.compile(r"\x1b\[([0-9;:]*)([A-Za-z])")


class _SGRState:
    __slots__ = ("fg", "bg", "bold", "dim", "italic", "underline", "reverse")

    def __init__(self):
        self.fg = None  # None | ("i", idx) | ("#", "rrggbb")
        self.bg = None
        self.bold = self.dim = self.italic = self.underline = self.reverse = False

    def attrs(self):
        a = ""
        if self.bold: a += "b"
        if self.dim: a += "d"
        if self.italic: a += "i"
        if self.underline: a += "u"
        if self.reverse: a += "r"
        return a or None

    def sig(self):
        def c(v):
            if v is None:
                return None
            return str(v[1]) if v[0] == "i" else "#" + v[1]
        return (c(self.fg), c(self.bg), self.attrs())


def _apply_sgr(params, st: _SGRState):
    i = 0
    n = len(params)
    while i < n:
        p = params[i]
        if p == 0:
            st.__init__()
        elif p == 1:
            st.bold = True
        elif p == 2:
            st.dim = True
        elif p == 3:
            st.italic = True
        elif p == 4:
            st.underline = True
        elif p == 7:
            st.reverse = True
        elif p in (21, 22):
            st.bold = st.dim = False
        elif p == 23:
            st.italic = False
        elif p == 24:
            st.underline = False
        elif p == 27:
            st.reverse = False
        elif 30 <= p <= 37:
            st.fg = ("i", p - 30)
        elif p == 38:
            if i + 2 < n and params[i + 1] == 5:
                st.fg = ("i", params[i + 2] & 255); i += 2
            elif i + 4 < n and params[i + 1] == 2:
                st.fg = ("#", "%02x%02x%02x" % (
                    params[i + 2] & 255, params[i + 3] & 255, params[i + 4] & 255))
                i += 4
        elif p == 39:
            st.fg = None
        elif 40 <= p <= 47:
            st.bg = ("i", p - 40)
        elif p == 48:
            if i + 2 < n and params[i + 1] == 5:
                st.bg = ("i", params[i + 2] & 255); i += 2
            elif i + 4 < n and params[i + 1] == 2:
                st.bg = ("#", "%02x%02x%02x" % (
                    params[i + 2] & 255, params[i + 3] & 255, params[i + 4] & 255))
                i += 4
        elif p == 49:
            st.bg = None
        elif 90 <= p <= 97:
            st.fg = ("i", p - 90 + 8)
        elif 100 <= p <= 107:
            st.bg = ("i", p - 100 + 8)
        i += 1


def parse_ansi_screen(raw: str):
    """CSI/SGR text -> (plain_text, lines_of_runs). Style carries across lines."""
    lines: list = [[]]
    buf: list = []
    st = _SGRState()
    cur_sig = st.sig()
    pos = 0

    def flush():
        nonlocal buf
        if not buf:
            return
        run = {"t": "".join(buf)}
        f, b, a = cur_sig
        if f: run["f"] = f
        if b: run["b"] = b
        if a: run["a"] = a
        lines[-1].append(run)
        buf = []

    def emit(plain: str):
        nonlocal cur_sig
        for j, seg in enumerate(plain.split("\n")):
            if j > 0:
                flush()
                lines.append([])
            if not seg:
                continue
            new_sig = st.sig()
            if new_sig != cur_sig:
                flush()
                cur_sig = new_sig
            buf.append(seg)

    for m in _CSI_RE.finditer(raw):
        if m.start() > pos:
            emit(raw[pos:m.start()])
        pos = m.end()
        if m.group(2) == "m":
            body = m.group(1).replace(":", ";")
            params = [int(x) if x else 0 for x in body.split(";")] if body else [0]
            _apply_sgr(params, st)
    if pos < len(raw):
        emit(raw[pos:])
    flush()

    # Trim: drop trailing default-styled whitespace (keep leading blanks for
    # layout and any styled bg-fill spaces), then drop trailing empty lines.
    for line in lines:
        while line:
            last = line[-1]
            if "f" not in last and "b" not in last and "a" not in last:
                if last["t"].strip() == "":
                    line.pop()
                    continue
                last["t"] = last["t"].rstrip()
            break
    while lines and not lines[-1]:
        lines.pop()

    plain = "\n".join("".join(r["t"] for r in line) for line in lines)
    return plain, lines


def truncate_screen(plain: str, runs: list, cols):
    """Crop both representations to `cols` characters per line.

    Used to hide the right-hand sidebar (context/MCP panel) of wide TUI
    layouts so the chat does not interleave with it in wrap mode.
    cols <= 0 keeps the full width.
    """
    try:
        cols = int(cols)
    except (TypeError, ValueError):
        return plain, runs
    if cols <= 0:
        return plain, runs
    plain = "\n".join(ln[:cols] for ln in plain.split("\n"))
    out_runs = []
    for line in runs:
        budget = cols
        new_line = []
        for run in line:
            if budget <= 0:
                break
            t = run["t"]
            if len(t) > budget:
                run = dict(run)
                run["t"] = t[:budget]
                new_line.append(run)
                budget = 0
            else:
                new_line.append(run)
                budget -= len(t)
        out_runs.append(new_line)
    return plain, out_runs


# Characters of pure-decoration lines: input box borders, status bar
# edges, horizontal rules (spaces allowed between them).
_BORDER_CHARS = set("╹╵╶╷╴╭╮╰╯┗┓┛┏└┘┌┐┬┴┼┤├━─═▀▄▁▂▃▅▆▇█░■⬝―-= ")


def _is_status_line(line: str) -> bool:
    s = line.strip()
    return "esc interrupt" in s or "ctrl+p commands" in s


def _is_decoration_line(line: str) -> bool:
    s = line.strip()
    return len(s) >= 8 and all(ch in _BORDER_CHARS for ch in s)


def _is_gutter_line(line: str) -> bool:
    return line.strip() in ("┃", "│", "|")


def _gutter_prefixed(line: str) -> bool:
    return line.lstrip()[:1] in ("┃", "│", "|")


_BADGE_RE = re.compile(r"^\s*[┃│|]\s*(Build|Plan|Approve|Topic|Custom|Review)\b.*·")


# Empty input-editor row: vim-style '~' placeholder, optionally followed by
# the context counter (e.g. '66.8K (33%') on the right. The closing paren may
# be missing when the line was cropped to `cols`.
_INPUT_INTERIOR_RE = re.compile(r"~\s*(?:[\d.]+[KM]?\s*\(\d+%?\s*\)?)?\s*~?\s*$")


def _is_input_interior_line(line: str) -> bool:
    s = line.strip()
    return s.startswith("~") and _INPUT_INTERIOR_RE.match(s) is not None


def _is_badge_line(line: str) -> bool:
    """Input-editor header: mode + model, e.g. '┃  Build · Synth-9 …'."""
    s = line.strip()
    return 0 < len(s) <= 90 and "·" in s and _BADGE_RE.match(line) is not None


def trim_input_block(plain: str, runs: list, max_lines: int = 15):
    """Drop the trailing non-informative block at the bottom of the pane.

    Removes, bottom-up: empty lines, the TUI status bar (esc interrupt /
    ctrl+p hints), input-box border lines, the empty-editor placeholder row
    ('~' + context counter) and bare chat-gutter (┃/│) padding lines. Then,
    if the input-editor header (mode/model badge like '┃  Build · Synth-9 …')
    sits within the trimmed bottom window, the whole non-functional input
    zone is dropped: badge + draft text lines + gutter padding above it.
    Chat content is never touched: the zone is only removed when every line
    below the badge is itself a zone line (empty/gutter/decoration/status/
    placeholder). Intended for phone previews where the wrapped input area
    and 150-char borders are pure noise.
    """
    lines = plain.split("\n")
    if len(runs) != len(lines):
        return plain, runs
    n = 0
    while lines and n < max_lines:
        ln = lines[-1]
        if (
            not ln.strip()
            or _is_status_line(ln)
            or _is_decoration_line(ln)
            or _is_gutter_line(ln)
            or _is_input_interior_line(ln)
        ):
            lines.pop()
            runs.pop()
            n += 1
        else:
            break
    # Phase 2: cut the input zone (mode/model badge + draft field).
    badge_idx = None
    for i in range(len(lines) - 1, max(0, len(lines) - 8) - 1, -1):
        if _is_badge_line(lines[i]):
            badge_idx = i
            break
    if badge_idx is not None and len(lines) - badge_idx <= max_lines:
        below = lines[badge_idx + 1 :]
        if all(
            not ln.strip()
            or _gutter_prefixed(ln)
            or _is_decoration_line(ln)
            or _is_status_line(ln)
            or _is_input_interior_line(ln)
            for ln in below
        ):
            cut = badge_idx
            while cut > 0 and len(lines) - cut < max_lines:
                prev = lines[cut - 1]
                if not prev.strip() or _is_gutter_line(prev):
                    cut -= 1
                else:
                    break
            lines = lines[:cut]
            runs = runs[:cut]
    return "\n".join(lines), runs


# --- Session tab bar (header line) parsing ---------------------------------
#
# opencode TUI renders its session tabs in the first pane line:
#     ' [•] Title one  Title two  +'
# Words of the ACTIVE tab are bold with fg ~#626262; INACTIVE tabs fade out
# (fg gradient #353535 -> #080808, no bold). Inter-word spaces are bright
# (#ffffff) for every tab, so only word runs carry the signal. A braille
# spinner (⠋..⠸) marks a generating session and sits between titles; '•'
# marks unread output; the trailing '+' is the "new session" button.

_BRAILLE = set("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")


def _fg_lum(run) -> int:
    """Perceived luminance 0..255 of a run's truecolor fg, -1 if unknown."""
    f = run.get("f")
    if not f or not f.startswith("#") or len(f) != 7:
        return -1
    try:
        r, g, b = int(f[1:3], 16), int(f[3:5], 16), int(f[5:7], 16)
    except ValueError:
        return -1
    return (r * 299 + g * 587 + b * 114) // 1000


def parse_tabs(runs) -> list:
    """Header line runs -> [{'t': title, 'a': 1|0, 'u': 0|1}, ...].

    A new tab starts on: bold <-> non-bold transition, 2+ consecutive
    spaces (tab gap; a braille spinner between tabs collapses to one), or
    an inactive title fading to near-black (lum <= 28) followed by a fresh
    dark-gray word (lum >= 45) — two adjacent inactive tabs. The trailing
    '+' cluster (new-session button) is dropped.
    """
    if not runs:
        return []
    tabs: list = []
    buf: list = []
    cur_bold = None
    unread = False
    last_lum = -1
    spaces = 0  # consecutive spaces since the last word char

    def flush():
        nonlocal buf, unread
        t = "".join(buf).strip()
        buf = []
        if t:
            tabs.append({"t": t, "a": 1 if cur_bold else 0, "u": 1 if unread else 0})
        unread = False

    for run in runs[0]:
        bold = "b" in (run.get("a") or "")
        lum = _fg_lum(run)
        for ch in run["t"]:
            if ch in _BRAILLE:
                spaces += 2  # spinner between tabs: force a boundary
                continue
            if ch == "•":
                unread = True
                continue
            if ch == " ":
                spaces += 1
                continue
            new_cluster = (
                cur_bold is None
                or bold != cur_bold
                or spaces >= 2
                or (
                    not bold
                    and last_lum != -1
                    and last_lum <= 28
                    and lum >= 45
                )
            )
            if new_cluster and buf:
                flush()
            cur_bold = bold
            if buf and spaces == 1:
                buf.append(" ")
            buf.append(ch)
            spaces = 0
            last_lum = lum if lum >= 0 else last_lum
    flush()
    if tabs and tabs[-1]["t"].rstrip() == "+":
        tabs.pop()
    return tabs


def detect_busy(runs) -> bool:
    """True when the header line shows a braille spinner (agent generating)."""
    if not runs:
        return False
    for run in runs[0]:
        for ch in run["t"]:
            if ch in _BRAILLE:
                return True
    return False


def capture_scrollback_ansi(start: int, end: int, max_cols=0):
    """History capture: lines [start, end] relative to the visible top.

    Offsets are negative: -1 is the first line ABOVE the visible pane.
    Same parse/truncate pipeline as the live screen; no input-zone trim —
    history rows are real content.
    """
    res = _run(
        [
            "capture-pane", "-e", "-p",
            "-S", str(start), "-E", str(end),
            "-t", _sess(),
        ]
    )
    if res.returncode != 0:
        raise TmuxError(res.stderr.decode().strip() or "capture-pane failed")
    raw = res.stdout.decode(errors="replace")
    plain, runs = parse_ansi_screen(raw)
    plain, runs = truncate_screen(plain, runs, max_cols)
    return plain, runs


def capture_screen_ansi(max_cols=0, trim=False):
    """Colored capture: returns (plain_text, lines_of_runs, raw_ansi).

    max_cols > 0 crops every line to that many characters (sidebar hidden).
    trim=True drops the trailing input-box/status decoration block.
    """
    res = _run(["capture-pane", "-e", "-p", "-t", _sess()])
    if res.returncode != 0:
        raise TmuxError(res.stderr.decode().strip() or "capture-pane failed")
    raw = res.stdout.decode(errors="replace")
    plain, runs = parse_ansi_screen(raw)
    plain, runs = truncate_screen(plain, runs, max_cols)
    if trim:
        plain, runs = trim_input_block(plain, runs)
    return plain, runs, raw

def press(key: str):
    mapping = {
        "enter": "Enter",
        "esc": "Escape",
        "escape": "Escape",
        "tab": "Tab",
        "btab": "BTab",
        "up": "Up",
        "down": "Down",
        "left": "Left",
        "right": "Right",
        "space": "Space",
        "backspace": "BSpace",
        "delete": "DC",
    }
    k = mapping.get(key.lower(), key)
    return send_keys_raw(k)

def combo(mods: list[str], key: str):
    mod_str = ""
    for m in mods:
        m = m.lower()
        if m == "ctrl" or m == "control":
            mod_str += "C-"
        elif m == "alt" or m == "meta" or m == "option":
            mod_str += "M-"
    k = key.lower()
    if len(k) == 1 and mod_str:
        send_keys_raw(f"{mod_str}{k}")
    else:
        send_keys_raw(f"{mod_str}{key}")
    return True

def action(act: str):
    act = act.lower()
    if act == "clear":
        return send_keys_raw("C-u")
    elif act == "confirm":
        return send_keys_raw("Enter")
    elif act == "cancel":
        return send_keys_raw("Escape")
    elif act == "yes":
        return send_text("y")
    elif act == "no":
        return send_text("n")
    elif act == "up":
        return press("up")
    elif act == "down":
        return press("down")
    elif act == "left":
        return press("left")
    elif act == "right":
        return press("right")
    elif act == "tab":
        return press("tab")
    elif act == "shifttab":
        return press("btab")
    elif act == "interrupt":
        return send_keys_raw("C-c")
    elif act == "wordback":
        return send_keys_raw("C-w")
    elif act == "killend":
        return send_keys_raw("C-k")
    else:
        raise TmuxError(f"unknown action: {act}")
