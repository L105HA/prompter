"""Shared bootstrap for bridge tests: import tmux_ctl from the parent dir."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

PASS = 0
FAIL = 0
_FAILED = []


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok  {name}")
    else:
        FAIL += 1
        _FAILED.append(name)
        print(f"FAIL  {name} {extra}")


def done():
    print(f"\n{PASS} passed, {FAIL} failed")
    if _FAILED:
        print("failed:", _FAILED)
    sys.exit(1 if FAIL else 0)
