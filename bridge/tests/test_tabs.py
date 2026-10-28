"""Unit tests for parse_tabs + detect_busy — fixtures from REAL captures."""
from _common import check, done
from tmux_ctl import parse_tabs, detect_busy

# --- Real capture: sandbox ocr-tui, single active tab, idle --------------------
TUI_HEADER = [
    {"t": "   ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "Тест", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": " ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "раз", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": "                     ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "+", "f": "#353535", "b": "#040404"},
]
check("single active tab", parse_tabs([TUI_HEADER]) == [{"t": "Тест раз", "a": 1, "u": 0}],
      str(parse_tabs([TUI_HEADER])))
check("idle header: not busy", detect_busy([TUI_HEADER]) is False)

# --- Real capture: live session, inactive (faded) + active with spinner ---------
LIVE = [
    {"t": "   ", "f": "#ffffff", "b": "#040404"},
    {"t": "Генерация", "f": "#353535", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "выкроек", "f": "#353535", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "в", "f": "#353535", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "DXF", "f": "#353535", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "и", "f": "#353535", "b": "#040404"},
    {"t": "з", "f": "#2b2b2b", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#111111"},
    {"t": "⠸", "f": "#674b39", "b": "#0f0f0f"},
    {"t": " ", "f": "#ffffff", "b": "#0d0d0d"},
    {"t": "Анализ", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": " ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "и", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": " ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "улучш", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": "ен", "f": "#626262", "b": "#0d0d0d", "a": "b"},
    {"t": "ия", "f": "#626262", "b": "#0e0e0e", "a": "b"},
    {"t": " ", "f": "#ffffff", "b": "#0e0e0e"},
    {"t": "o", "f": "#626262", "b": "#0f0f0f", "a": "b"},
    {"t": "pen", "f": "#626262", "b": "#101010", "a": "b"},
    {"t": "co", "f": "#626262", "b": "#111111", "a": "b"},
    {"t": "d", "f": "#515151", "b": "#121212", "a": "b"},
    {"t": "e", "f": "#3c3c3c", "b": "#121212", "a": "b"},
    {"t": "-", "f": "#282828", "b": "#121212", "a": "b"},
    {"t": "r", "f": "#131313", "b": "#121212", "a": "b"},
    {"t": " ", "f": "#ffffff", "b": "#080808"},
    {"t": "+", "f": "#626262", "b": "#080808"},
    {"t": " ", "f": "#ffffff", "b": "#080808"},
]
check("live two tabs (fade + spinner + bold)",
      parse_tabs([LIVE]) == [{"t": "Генерация выкроек в DXF из", "a": 0, "u": 0},
                             {"t": "Анализ и улучшения opencode-r", "a": 1, "u": 0}],
      str(parse_tabs([LIVE])))
check("spinner header: busy", detect_busy([LIVE]) is True)

# --- Bullet marks unread --------------------------------------------------------
BULLET = [
    {"t": "  ", "f": "#ffffff", "b": "#040404"},
    {"t": "•", "f": "#9d7cd8", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#040404"},
    {"t": "second", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": "  ", "f": "#ffffff", "b": "#080808"},
    {"t": "+", "f": "#626262", "b": "#080808"},
]
check("bullet -> unread", parse_tabs([BULLET]) == [{"t": "second", "a": 1, "u": 1}],
      str(parse_tabs([BULLET])))

# --- Adjacent inactive tabs (lum-jump heuristic) --------------------------------
ADJ = [
    {"t": "  ", "f": "#ffffff", "b": "#040404"},
    {"t": "first", "f": "#353535", "b": "#040404"},
    {"t": "t", "f": "#080808", "b": "#040404"},
    {"t": " ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "third", "f": "#353535", "b": "#0c0c0c"},
    {"t": " ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "tab", "f": "#2b2b2b", "b": "#0c0c0c"},
    {"t": "  ", "f": "#ffffff", "b": "#080808"},
    {"t": "+", "f": "#626262", "b": "#080808"},
]
check("adjacent inactive split",
      parse_tabs([ADJ]) == [{"t": "firstt", "a": 0, "u": 0}, {"t": "third tab", "a": 0, "u": 0}],
      str(parse_tabs([ADJ])))

# --- Empty / no header / no runs -------------------------------------------------
check("empty runs", parse_tabs([]) == [])
check("blank header", parse_tabs([[{"t": "   ", "f": "#ffffff"}]]) == [])
check("busy: no runs", detect_busy([]) is False)
check("busy: empty header", detect_busy([[{"t": "  "}]]) is False)

# --- '+' inside a title must not be dropped as the button -----------------------
CPP = [
    {"t": "  ", "f": "#ffffff", "b": "#0c0c0c"},
    {"t": "C++ project", "f": "#626262", "b": "#0c0c0c", "a": "b"},
    {"t": "  ", "f": "#ffffff", "b": "#080808"},
    {"t": "+", "f": "#626262", "b": "#080808"},
]
check("'+' inside title kept", parse_tabs([CPP]) == [{"t": "C++ project", "a": 1, "u": 0}],
      str(parse_tabs([CPP])))

done()
