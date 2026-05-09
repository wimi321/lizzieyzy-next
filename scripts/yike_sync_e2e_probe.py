#!/usr/bin/env python
"""
Real-scene Yike sync probe for lizzieyzy-next + readboard.

What it does:
1) Attach to readboard window.
2) Click sync button once (start).
3) Read incremental yike-sync-debug.log lines.
4) Assert key chain markers and print a compact report.

Preconditions:
- readboard and lizzie are already running.
- Yike browser is already on the target page (for example live/new-room).
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import sys
import time
from typing import Iterable, List, Optional, Sequence, Tuple

try:
    import uiautomation as auto
except Exception as exc:  # pragma: no cover - runtime dependency check
    print(f"[ERROR] missing dependency: uiautomation ({exc})")
    print("Install with: C:\\Python314\\python.exe -m pip install --user uiautomation")
    sys.exit(2)


DEFAULT_LOG = r"D:\dev\weiqi\lizzieyzy-next\target\target\yike-sync-debug.log"
DEFAULT_WINDOW_REGEX = r".*readboard.*"
DEFAULT_BUTTON_PATTERNS = ("同步", "Sync", "Keep", "开始")
DEFAULT_YIKE_RADIO_PATTERNS = ("弈客", "Yike")


def _compile_patterns(parts: Sequence[str]) -> List[str]:
    out: List[str] = []
    for part in parts:
        text = (part or "").strip()
        if not text:
            continue
        out.append(text.lower())
    return out


def _match_any(text: str, patterns: Sequence[str]) -> bool:
    lower = (text or "").lower()
    return any(p in lower for p in patterns)


def _find_readboard_window(window_regex: str, timeout_sec: float) -> Optional[auto.Control]:
    win = auto.WindowControl(searchDepth=1, RegexName=window_regex)
    if win.Exists(timeout_sec, 0.2):
        return win
    return None


def _safe_runtime_id(ctrl: auto.Control) -> str:
    try:
        rid = ctrl.GetRuntimeId()
        if not rid:
            return ""
        return ",".join(str(x) for x in rid)
    except Exception:
        return ""


def _iter_buttons(ctrl: auto.Control) -> Iterable[auto.Control]:
    queue: List[auto.Control] = [ctrl]
    seen: set[str] = set()
    visited = 0
    while queue:
        cur = queue.pop(0)
        visited += 1
        if visited > 4000:
            return
        try:
            rid = _safe_runtime_id(cur)
            if rid and rid in seen:
                continue
            if rid:
                seen.add(rid)
            if cur.ControlTypeName == "ButtonControl":
                yield cur
            queue.extend(cur.GetChildren())
        except Exception:
            continue


def _iter_radios(ctrl: auto.Control) -> Iterable[auto.Control]:
    queue: List[auto.Control] = [ctrl]
    seen: set[str] = set()
    visited = 0
    while queue:
        cur = queue.pop(0)
        visited += 1
        if visited > 4000:
            return
        try:
            rid = _safe_runtime_id(cur)
            if rid and rid in seen:
                continue
            if rid:
                seen.add(rid)
            if cur.ControlTypeName == "RadioButtonControl":
                yield cur
            queue.extend(cur.GetChildren())
        except Exception:
            continue


def _find_sync_button(window: auto.Control, patterns: Sequence[str]) -> Optional[auto.Control]:
    max_depth = 4
    queue: List[Tuple[auto.Control, int]] = [(window, 0)]
    while queue:
        cur, depth = queue.pop(0)
        if depth > max_depth:
            continue
        try:
            name = (cur.Name or "").strip()
            if cur.ControlTypeName == "ButtonControl" and _match_any(name, patterns):
                if "停止" not in name:
                    return cur
            if depth < max_depth:
                children = cur.GetChildren()
                for child in children[:120]:
                    queue.append((child, depth + 1))
        except Exception:
            continue
    return None


def _find_radio(window: auto.Control, patterns: Sequence[str]) -> Optional[auto.Control]:
    max_depth = 4
    queue: List[Tuple[auto.Control, int]] = [(window, 0)]
    while queue:
        cur, depth = queue.pop(0)
        if depth > max_depth:
            continue
        try:
            name = (cur.Name or "").strip()
            if cur.ControlTypeName == "RadioButtonControl" and _match_any(name, patterns):
                return cur
            if depth < max_depth:
                children = cur.GetChildren()
                for child in children[:120]:
                    queue.append((child, depth + 1))
        except Exception:
            continue
    return None


def _is_radio_selected(radio: auto.Control) -> bool:
    try:
        return bool(radio.GetSelectionItemPattern().IsSelected)
    except Exception:
        return False


def _ensure_yike_platform(window: auto.Control, patterns: Sequence[re.Pattern[str]]) -> Tuple[bool, str]:
    radio = _find_radio(window, patterns)
    if radio is None:
        return False, "yike radio not found"
    if _is_radio_selected(radio):
        return True, f"already selected: {radio.Name}"
    try:
        radio.GetSelectionItemPattern().Select()
    except Exception:
        _click_button(radio)
    time.sleep(0.2)
    if _is_radio_selected(radio):
        return True, f"selected now: {radio.Name}"
    return False, f"failed to select: {radio.Name}"


def _click_button(button: auto.Control) -> None:
    # Prefer invoke for stability; fallback to click.
    try:
        if button.GetInvokePattern():
            button.GetInvokePattern().Invoke()
            return
    except Exception:
        pass
    button.Click(simulateMove=False)


def _read_appended_lines(log_path: str, start_pos: int, wait_sec: float) -> List[str]:
    deadline = time.time() + wait_sec
    while time.time() < deadline:
        if os.path.exists(log_path) and os.path.getsize(log_path) > start_pos:
            break
        time.sleep(0.2)

    if not os.path.exists(log_path):
        return []
    with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
        f.seek(start_pos)
        return [line.rstrip("\r\n") for line in f.readlines()]


def _contains(lines: Sequence[str], fragment: str) -> bool:
    return any(fragment in line for line in lines)


def _find_first(lines: Sequence[str], fragment: str) -> str:
    for line in lines:
        if fragment in line:
            return line
    return ""


def _extract_route(line: str) -> str:
    m = re.search(r"(https?://\S+)", line or "")
    return m.group(1) if m else ""


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe Yike sync chain by clicking readboard sync button.")
    parser.add_argument("--log", default=DEFAULT_LOG, help="Path to yike-sync-debug.log")
    parser.add_argument("--window-regex", default=DEFAULT_WINDOW_REGEX, help="Regex for readboard window title")
    parser.add_argument(
        "--button-contains",
        default="|".join(DEFAULT_BUTTON_PATTERNS),
        help="Button name fragments split by |, e.g. 同步|Sync|Keep",
    )
    parser.add_argument("--find-timeout-sec", type=float, default=8.0)
    parser.add_argument("--wait-log-sec", type=float, default=6.0)
    parser.add_argument(
        "--ensure-yike",
        action="store_true",
        help="Select Yike platform radio button before clicking sync.",
    )
    parser.add_argument(
        "--yike-radio-contains",
        default="|".join(DEFAULT_YIKE_RADIO_PATTERNS),
        help="Yike radio name fragments split by |, e.g. 弈客|Yike",
    )
    args = parser.parse_args()

    if not os.path.exists(args.log):
        print(f"[ERROR] log file not found: {args.log}")
        return 2

    start_pos = os.path.getsize(args.log)
    ts = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[INFO] start: {ts}")
    print(f"[INFO] log: {args.log}")
    print(f"[INFO] log start offset: {start_pos}")

    window = _find_readboard_window(args.window_regex, args.find_timeout_sec)
    if window is None:
        print(f"[ERROR] readboard window not found by regex: {args.window_regex}")
        return 2

    patterns = _compile_patterns(args.button_contains.split("|"))
    yike_patterns = _compile_patterns(args.yike_radio_contains.split("|"))

    if args.ensure_yike:
        ok, detail = _ensure_yike_platform(window, yike_patterns)
        print(f"[INFO] ensure_yike: {ok} ({detail})")
        if not ok:
            return 2

    button = _find_sync_button(window, patterns)
    if button is None:
        print("[ERROR] sync button not found")
        return 2

    print(f"[INFO] click button: '{button.Name}'")
    _click_button(button)

    lines = _read_appended_lines(args.log, start_pos, args.wait_log_sec)
    print(f"[INFO] appended lines: {len(lines)}")

    has_start_cmd = _contains(lines, "ReadBoard received yikeSyncStart")
    has_start_from_rb = _contains(lines, "BrowserFrame start from readboard")
    has_force_reload = _contains(lines, "BrowserFrame force reload current address for explicit yike sync")

    start_line = _find_first(lines, "BrowserFrame start from readboard")
    route = _extract_route(start_line)

    print("--- Result ---")
    print(f"start_command_seen: {has_start_cmd}")
    print(f"start_from_readboard_seen: {has_start_from_rb}")
    print(f"force_reload_seen: {has_force_reload}")
    print(f"route_from_start_log: {route or '<none>'}")

    # Strong pass criteria: command -> start from readboard -> force reload.
    passed = has_start_cmd and has_start_from_rb and has_force_reload
    print(f"pass: {passed}")

    # Exit code 0 only when the chain is complete.
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
