"""Unit tests for the screen pipeline: SGR parse, cols truncate, input-zone trim.

Fixtures reproduce REAL opencode TUI captures (2026-10-27) — see git history
for the idle/badge investigations. No tmux needed.
"""
from _common import check, done
import tmux_ctl
from tmux_ctl import (
    parse_ansi_screen,
    truncate_screen,
    trim_input_block,
    _is_input_interior_line,
    _is_badge_line,
    _is_status_line,
)


def sgr(code: str) -> str:
    return "\x1b[" + code + "m"


# --- SGR parsing ---------------------------------------------------------------

# bold + truecolor red
plain, runs = parse_ansi_screen(sgr("1") + sgr("38;2;255;0;0") + "hi" + sgr("0"))
check("parse: bold truecolor", plain == "hi" and runs[0][0] == {"t": "hi", "f": "#ff0000", "a": "b"},
      str(runs))

# 256-color index kept as decimal string
plain, runs = parse_ansi_screen(sgr("38;5;46") + "x")
check("parse: 256-color", runs[0][0].get("f") == "46", str(runs))

# base colors -> indices 1 and 9
plain, runs = parse_ansi_screen(sgr("31") + "a" + sgr("0") + sgr("91") + "b")
check("parse: base + bright", runs[0][0]["f"] == "1" and runs[0][1]["f"] == "9", str(runs))

# style survives a newline
plain, runs = parse_ansi_screen(sgr("4") + "one\ntwo")
check("parse: style across \\n", runs[1][0].get("a") == "u" and plain == "one\ntwo", str(runs))

# trailing default-styled spaces cut, styled bg-fill spaces kept
plain, runs = parse_ansi_screen(sgr("48;2;0;0;255") + "a   " + sgr("0") + "   ")
check("parse: trailing default cut", plain == "a   " if runs[0][0].get("b") else False, str(runs))

# --- cols truncation ------------------------------------------------------------

lines = ["CHAT-MARK left part one" + " " * 30 + "RIGHT-SIDEBAR",
         "short"]
plain, runs = parse_ansi_screen(sgr("38;5;46") + lines[0] + sgr("0") + "\n" + lines[1])
p2, r2 = truncate_screen(plain, runs, 25)
check("cols: crop plain", p2.split("\n")[0] == lines[0][:25], repr(p2))
check("cols: crop runs text", "".join(r["t"] for r in r2[0]) == p2.split("\n")[0], str(r2))
check("cols: style kept in crop", r2[0][0].get("f") == "46", str(r2))
check("cols: 0 = full width", truncate_screen(plain, runs, 0) == (plain, runs))

# --- trim: predicates -----------------------------------------------------------

check("interior: bare ~", _is_input_interior_line("  ~"))
check("interior: cropped counter", _is_input_interior_line("  ~" + " " * 140 + "66.8K (33%"))
check("interior: full counter", _is_input_interior_line("~   12.3M (7%)"))
check("interior: full row is status", _is_status_line("~    66.8K (33%)  ctrl+p commands    ~"))
check("not interior: ~/.bashrc", not _is_input_interior_line("  ~/.bashrc is here"))
check("badge: gutter badge", _is_badge_line("  ┃  Build · Synth-9 Pro Plan"))
check("badge: stats NOT a badge", not _is_badge_line("     Build · Synth-9 · 1m 4s · 52.5 tok/s"))


def trim(lines):
    mk = [[{"t": ln}] for ln in lines]
    return trim_input_block("\n".join(lines), mk)[0].split("\n")


# --- trim: real idle fixture (cropped to 150 cols as the phone sees it) -----------

idle_150 = [
    "  ┃  последний текст ответа агент",
    "     Build · Synth-9 · 1m 4s · 52.5 tok/s",
    "",
    "  ┃",
    "  ┃",
    "  ┃",
    "  ┃  Build · Synth-9 Pro Plan",
    "  ╹" + "▀" * 147,
    "  ~" + " " * 138 + "66.8K (33%",
]
out = trim(idle_150)
check("idle 150: zone gone", len(out) == 2, str(out))
check("idle 150: ends at stats", out[-1].strip().startswith("Build · Synth-9 · 1m 4s"))

# --- trim: badge + draft + border + status ----------------------------------------

out = trim([
    "  ┃  ⠋ cd ~/some command runs",
    "",
    "  ┃",
    "  ┃  Build · Synth-9 Pro Plan",
    "  ┃  draft typed via pult",
    "  ╹" + "▀" * 40,
    "   ⬝⬝⬝⬝ esc interrupt",
    "",
])
check("zone with draft: cut to tool block", out == ["  ┃  ⠋ cd ~/some command runs"], str(out))

# badge-like CHAT line with real content below -> NOT a zone
out = trim(["keep me", "  Build · summary of plan", "plain chat text no gutter", "  ╹" + "▀" * 12, ""])
check("badge-like chat kept", out[-1] == "plain chat text no gutter", str(out))

# stats line survives when there is no badge
out = trim(["  ┃  ответ", "     Build · Synth-9 · 1m 4s · 52.5 tok/s", "", "  ╹" + "▀" * 20, "  ~"])
check("stats kept without badge", any("tok/s" in ln for ln in out), str(out))

# chat line starting with ~ is content
out = trim(["  ┃  путь:", "~/.config/opencode/opencode.json", "  ┃"])
check("tilde chat kept", any("opencode.json" in ln for ln in out), str(out))

# misaligned runs -> unchanged
plain = "a\n  ┃  Build · X\n  ╹▀▀▀"
runs_bad = [[{"t": "a"}], [{"t": "b"}]]  # 2 runs for 3 lines
p2, _ = trim_input_block(plain, runs_bad)
check("misaligned runs unchanged", p2 == plain)

# padding absorb bounded by max_lines
many = ["content"] + ["  ┃"] * 30 + ["  ┃  Build · Model M", "  ╹▀▀▀▀▀▀▀", ""]
p2, _ = trim_input_block("\n".join(many), [[{"t": ln}] for ln in many])
check("absorb bounded", "Build" not in p2 and len(p2.split("\n")) >= 30 + 1 - 15)

done()
