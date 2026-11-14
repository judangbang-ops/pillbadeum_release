"""R2's remaining screens, part 2: permissions, settings and the widget with six items, each step checked and
photographed on one emulator. Run on API 34, 36, 26 and 27 (.github/workflows/screens2.yml).

Why: the end conditions of TASK-014, 020, 023, 026, 041 and 042 in docs/08-wbs/wbs.md are emulator captures, and
nobody on the team has an Android phone yet (2026-11-12). Part 1 (screens_check.py) covers the other R2 screens;
this script reuses its helpers. Each step reads the screen dump (the sentences TalkBack reads, content-desc) and the
database, and keeps the dump (apiNN-name.xml) next to the picture.

Flow on API 33 and later (34, 36), clock in Asia/Seoul, day 0 the run's date:
   0. Setup: clock on day -7 08:30, heads-up popups off, the debug APK installed.
   1. The intro's [위젯 추가]: the launcher's add sheet places the widget (one person: it shows everyone).
   2. Day -7: Weekly (주 1회, day 0's weekday) recorded in the app, so on day 0 it shows its last dose (TASK-041).
   3. Day 0 08:30: Weekly is pending again with "마지막 복용 MM/DD(요) HH:MM".
   4. TASK-023: Night (매일, 자기 전) with [알림 받기]: the system permission window, [Don't allow] -> the switch stays
      on, "알림 권한이 없어 알림이 오지 않아요" and [설정 열기]. Asked again and refused again; the third time no window
      comes and the notice shows at once (the permission is fixed). [설정 열기] opens the system notification screen.
      [저장] -> "저장했어요" with "방해 금지 시간(22:00~07:00)과 겹쳐 다시 알림이 2번 줄어요" -> [확인] -> first screen.
   5. TASK-026: the first screen's "알림이 꺼져 있어 알려 드릴 수 없어요" and [설정 열기] -> system screen; 설정 >
      알림이 오지 않을 때 with all three rows off, no promise in minutes.
   6. TASK-023: the permission made askable again (pm revoke and clear-permission-flags), Evening (저녁) with
      [알림 받기] -> [Allow]: no notice, saved straight to the first screen (18:00 loses no repeat).
   7. TASK-026: "알림이 늦을 수 있어요" and [허용하기] -> the system exact-alarm screen, switched on there -> the notice
      goes. The battery row's [설정 열기] -> the system optimization list; the app left out of it (deviceidle) -> all
      three rows on.
   8. TASK-020: the backup line without a screen lock and with one (a throwaway PIN, never written anywhere, cleared
      right after); the lock-screen names switch; 다시 알림 간격 120분 -> "방해 금지 시간과 겹쳐 다시 알림이 모두 3번
      줄어요 (알림을 켠 항목 2개)" (Night drops 2, Evening 1).
   9. Iron, Zinc and Omega added by the debug receiver: six items on the 4x2 widget.
  10. TASK-014, TASK-041: the widget at text 100%, 115%, 130% and 200%: tiles per row against WidgetLayout's rule,
      '더 있음' while tiles are hidden, Weekly's last-dose line whole inside the list at 100% and 200%.
  11. TASK-014, TASK-042: back at 100%, all six tiles tapped on the widget, scrolling the list: each records a dose
      from the widget (source WIDGET). Whether the list keeps its place after a tap is noted.
  12. TASK-020 (RR-15): day 1 03:30, before the day start (04:00) the widget still shows day 0 with 6 / 6. The day start
      set to 03:00 in 설정 (system time picker, the P-5 question, [바꾸기]): the widget is drawn for day 1 (1 / 6, the
      weekly dose still counts), the records keep their period keys and none is added.

API 26: steps 0-3, the battery row (notifications and exact alarms need no permission before Android 13 and 12), the
backup line (Android 8.x does not back up, with or without a lock), six items, the widget at 100%, 115% and 130%
(the largest size there) and the six taps (TASK-042 on the oldest Android). Before the taps, what the launcher does
with a drag over the widget's list is noted: a slow straight drag photographed halfway, then a fast one, straight or
wider than tall, which the taps then use (drag_list). API 27: the backup line only (TC-035).

Names are typed in English (`adb shell input text` cannot type Korean). The debug APK is used: the database is read as
root, and the debug receiver (androidApp/src/debug) adds items and redraws the widgets the way a write does.

Writes report.md, screenshots (apiNN-NN-name.png), screen dumps and steps.json (each step's result with the numbers of
the screenshots it took, for the capture gallery) to --out. Exits 1 when a check fails.
Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path

from backup_restore_check import (
    ACTIVITY,
    HOME_MARK,
    PKG,
    Node,
    Report,
    adb,
    api_level,
    become_root,
    dump_ui,
    install,
    launch_app,
    sh,
    tap,
    uninstall,
    wait_boot,
)
from explore import (
    KEYBOARD_NOTES,
    LOG_PARTS,
    WEEKLY,
    ZONE,
    day_label,
    exact,
    font_scale,
    keyboard_height,
    night,
    row_button,
    scroll_down,
    set_clock,
    set_zone,
    tap_exact,
    tap_scrolled,
    tile,
    type_into,
)
from release_check import WIDGET_LABEL, go_home, place_widget, save_ui
from reminder_check import PIN, query, seed, set_pin, setting
from screens_check import (
    EMPTY_WIDGET,
    ShotSteps,
    active,
    active_count,
    at_home,
    desc_is,
    desc_starts,
    find_pin_button,
    has_desc,
    has_text,
    our_widget_ids,
    physical_density,
    refresh_widgets,
    reopen,
    scroll_top,
    seek,
    size,
    spoken,
    stored_choices,
    text_is,
    to_home,
    wait_screen,
)

# Texts of the app (shared/src/commonMain/.../status/Labels.kt)
NIGHT, EVENING = "Night", "Evening"
SEEDED = ("Iron", "Zinc", "Omega")
ITEMS = (WEEKLY, NIGHT, EVENING) + SEEDED  # the widget's order: items are listed in the order they were added
TAP_ORDER = ("Omega", "Zinc", "Iron", EVENING, NIGHT, WEEKLY)  # the last row first: down the list, then back up
WEEKDAYS = "월화수목금토일"
REMINDER_SWITCH = "알림 받기"  # Labels.REMINDER_SWITCH; a daily item's switch row is read as its name alone
ITEM_NOTIFICATIONS_OFF = "알림 권한이 없어 알림이 오지 않아요"  # Labels.ITEM_NOTIFICATIONS_OFF, SCR-006#permission
NOTIFICATIONS_OFF = "알림이 꺼져 있어 알려 드릴 수 없어요"  # Labels.NOTIFICATIONS_OFF, SCR-004#permission
EXACT_ALARMS_OFF = "알림이 늦을 수 있어요"  # Labels.EXACT_ALARMS_OFF
OPEN_SETTINGS, ALLOW = "설정 열기", "허용하기"
ITEM_SAVED = "저장했어요"  # Labels.ITEM_SAVED, the title of SCR-006#quiet
NIGHT_QUIET = "방해 금지 시간(22:00~07:00)과 겹쳐 다시 알림이 2번 줄어요"  # Labels.itemQuiet, 21:00 at the defaults
QUIET_SAVED = "방해 금지 시간과 겹쳐 다시 알림이 모두 3번 줄어요 (알림을 켠 항목 2개)"  # Labels.quietSaved(3, 2)
NOTIF_ROW, EXACT_ROW, BATTERY_ROW = "알림 권한", "정확한 시각 알람", "배터리 사용 제한"
ACCESS_ON, BATTERY_FREE = "켜짐", "제한 없음"
NOTIF_OFF_NOTE = "꺼져 있어 알림이 오지 않아요."
EXACT_OFF_NOTE = "꺼져 있어 알림이 늦을 수 있어요."
BATTERY_NOTE_START = "제한되어 있어 알림이 늦을 수 있어요."
SHOW_NAMES_ROW = "잠금 화면에 약 이름 보이기. 끄면 '복용 시간이에요'만 보여요."  # SwitchRow: name. note
SHOW_NAMES_ON = "이제 잠금 화면 알림에 약 이름이 보여요"  # Labels.SHOW_NAMES_ON
BACKUP_ON = "화면 잠금과 Google 백업이 켜져 있으면 복용 기록이 백업돼요."
BACKUP_NO_LOCK = "화면 잠금이 없어 복용 기록이 백업되지 않아요."
BACKUP_OLD = "이 휴대폰은 Android 버전이 낮아 복용 기록이 백업되지 않아요."
BACKUPS = (BACKUP_ON, BACKUP_NO_LOCK, BACKUP_OLD)
DAY_START_ASK = "하루 시작 시각을 바꿀까요?"
MORE = "더 있음"  # Labels.MORE, the widget's down arrow (SCR-001#overflow)
MINUTE_PROMISE = re.compile(r"\d+\s*분")  # REQ-048: no sentence of the access notices promises minutes

# The system permission window (permission controller): its buttons by id, or by their English text
ALLOW_TEXTS = ("Allow",)
DENY_TEXTS = ("Don't allow", "Don\u2019t allow", "Deny")
NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
EXACT_SWITCH_TEXT = "Allow setting alarms"  # the switch row of 설정 > Alarms & reminders for this app (English image)

# WidgetLayout (shared/src/commonMain/.../status/WidgetLayout.kt), dp: two tiles a row while a tile's text width holds
# LONGEST_PIECE_EM at the name size (16sp), tiles at least TILE_MIN_HEIGHT tall
GAP, TILE_PAD_START, TILE_PAD_END, TILE_MIN_HEIGHT, LONGEST_PIECE_EM = 6, 12, 8, 64, 7.1
# 16sp in dp from Android 14 on, which grows large text less than linearly (FontScaleConverterFactory: 14sp and 18sp at
# each scale, 16sp between them). The widget reads the phone's own value (DidITakeWidget.widgetFonts); this is only to
# tell which tiles-per-row to expect, and a size this close to the rule's limit is reported, not judged.
NONLINEAR_16SP = {"1.15": 18.1, "1.3": 20.2, "2.0": 28.0}

DENSITY = {"d": 2.75}
API = {"n": 0}
LOCK = {"on": False}  # the throwaway screen lock may be set: cleared again at the end


# ------------------------------------------------------------------------------------------------
# Small helpers


def pct(scale: str) -> int:
    return round(float(scale) * 100)


def day_of(d: date) -> str:
    return f"{d.month}/{d.day:02d}({WEEKDAYS[d.weekday()]})"


def plain(text: str) -> str:
    return text.replace("\u00a0", " ")


def dp(px: float) -> float:
    return px / DENSITY["d"]


def notification_permission() -> tuple[bool | None, str]:
    """POST_NOTIFICATIONS of the app from `dumpsys package`: granted, and its flags (USER_SET, USER_FIXED, ...)."""
    for line in sh(f"dumpsys package {PKG}", check=False).splitlines():
        if f"{NOTIFICATION_PERMISSION}:" in line and "granted=" in line:
            flags = re.search(r"flags=\[([^\]]*)\]", line)
            return "granted=true" in line, (flags.group(1).strip() if flags else "")
    return None, ""


def permission_window(timeout: float = 10) -> dict | None:
    """The system permission window's buttons and message, or None when it does not come within `timeout`."""
    end = time.time() + timeout
    while True:
        nodes = dump_ui()
        allow = next((n for n in nodes if n.rid.endswith("/permission_allow_button") or n.text in ALLOW_TEXTS), None)
        deny = next((n for n in nodes if n.rid.endswith("/permission_deny_button") or n.text in DENY_TEXTS), None)
        if allow is not None and deny is not None:
            message = next((n.text for n in nodes if n.rid.endswith("/permission_message")), "")
            return {"allow": allow, "deny": deny, "message": message}
        if time.time() >= end:
            return None
        time.sleep(1)


def resumed_package() -> str:
    for line in sh("dumpsys activity activities", check=False).splitlines():
        if "ResumedActivity" in line:
            found = re.search(r" u\d+ ([\w.]+)/", line)
            if found:
                return found.group(1)
    return ""


def system_screen(timeout: float = 12) -> tuple[str, list[str]]:
    """The package in front once it is not this app (a system settings screen), and the first texts on it."""
    end = time.time() + timeout
    pkg = ""
    while time.time() < end:
        pkg = resumed_package()
        if pkg and pkg != PKG:
            break
        time.sleep(1)
    time.sleep(1)
    texts = [n.text for n in dump_ui() if n.text.strip()][:6]
    return pkg, texts


def back_to_app(ok, timeout: float = 15) -> list[Node]:
    """System BACK from a system screen, then the app's screen read until `ok(nodes)` holds."""
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    return wait_screen(ok, timeout)


def keyboard_up() -> bool:
    """An on-screen keyboard is up: the window dump says so, or the item form's [저장] footer, which the form keeps above
    the keyboard, sits in the upper 70% of the screen."""
    if keyboard_height() > 0:
        return True
    save = next((n for n in dump_ui() if n.text == "저장"), None)
    return save is not None and save.bounds[3] < size()[1] * 0.7


def type_name(name: str) -> None:
    """Types the item's name into the form and closes an on-screen keyboard that came up. On API 36 one comes up although
    the emulator has a hardware keyboard and show_ime_with_hard_keyboard is 0, and it covered 주 1회's weekdays and the
    times of day (screens2 run 43539426392). BACK is pressed only while the keyboard is up, which takes it, and the form
    must still be there after (BACK on the form itself would leave it, BUG-001)."""
    type_into("약 이름", name)
    for _ in range(3):
        time.sleep(1.5)
        if not keyboard_up():
            return
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
        if not any(n.text == "약 이름" for n in dump_ui()):
            raise RuntimeError("BACK left the item form instead of closing the on-screen keyboard")
        KEYBOARD_NOTES.append(f"API {API['n']}: 이름을 넣은 뒤 뜬 화면 키보드를 BACK으로 닫음")
    if keyboard_up():
        raise RuntimeError("the on-screen keyboard stayed up over the item form")


def item_form(name: str, slot: str) -> None:
    """First screen > [항목 추가] > name > 매일 + time of day (screens_check.add without [저장]); [알림 받기] is left to
    the step. One person: the form asks for no person (TASK-015)."""
    to_home()
    tap_scrolled("항목 추가")
    type_name(name)
    tap_exact("매일")
    group = exact("언제 먹나요?")
    tap_exact(slot, below=group.bounds[3] if group else -1)


def reminder_switch() -> Node:
    """The item form's [알림 받기] row, from the top of the form down (the form scrolls; a dump holds what shows)."""
    scroll_top(2)
    return seek(desc_is(REMINDER_SWITCH), f"[{REMINDER_SWITCH}]")


def band_text(nodes: list[Node], text: str) -> bool:
    return any(text in n.text for n in nodes)


def find(match, tries: int = 3) -> Node | None:
    """seek() without the error: the first node `match` accepts fully on screen, scrolling down, or None."""
    try:
        return seek(match, "", tries)
    except RuntimeError:
        return None


def wait_find(match, timeout: float = 12) -> Node | None:
    """find() again until the node shows or `timeout` passes. Under the switch the form's notice can sit below the fold."""
    end = time.time() + timeout
    while True:
        node = find(match)
        if node is not None or time.time() >= end:
            return node
        time.sleep(1)


def has_notice(text: str):
    return lambda n: text in n.text


def form_reminder() -> tuple[str, Node | None, bool]:
    """The item form's reminder part read from the top down (ItemScreen.ReminderPart): the first-reminder time as TalkBack
    reads it, the notice that notifications are off (left on screen for the picture) and whether its [설정 열기] shows."""
    scroll_top(2)
    time_row = find(desc_starts("첫 알림 시각, "))
    notice = find(has_notice(ITEM_NOTIFICATIONS_OFF))
    button = notice is not None and find(text_is(OPEN_SETTINGS), 2) is not None
    return (time_row.desc if time_row else ""), notice, button


def home_notice(text: str, timeout: float = 12) -> list[Node]:
    """The app's first screen from its top (SCR-004#permission sits above the items), read until `text`'s notice shows."""
    to_home()
    scroll_top(2)
    return wait_screen(lambda ns: band_text(ns, text), timeout)


def open_settings() -> None:
    """First screen > [설정] (SCR-009), read until its first row is there."""
    to_home()
    tap_exact("설정")
    nodes = wait_screen(lambda ns: has_desc(ns, "하루 시작 시각, "), 15)
    if not has_desc(nodes, "하루 시작 시각, "):
        raise RuntimeError("the settings screen did not open")


def access_rows() -> dict[str, str]:
    """The three rows of '알림이 오지 않을 때' as TalkBack reads them, scrolling until all three were seen."""
    found: dict[str, str] = {}
    seek(desc_starts(f"{NOTIF_ROW}, "), f"the '{NOTIF_ROW}' row")
    for attempt in range(4):
        for n in dump_ui():
            for name in (NOTIF_ROW, EXACT_ROW, BATTERY_ROW):
                if n.desc.startswith(f"{name}, ") and name not in found:
                    found[name] = n.desc
        if len(found) == 3:
            break
        scroll_down()
    return found


def access_buttons() -> list[str]:
    return [n.desc for n in dump_ui() if n.desc in (f"{NOTIF_ROW} {OPEN_SETTINGS}", f"{EXACT_ROW} {ALLOW}",
                                                    f"{BATTERY_ROW} {OPEN_SETTINGS}")]


def backup_line(want: str | None = None, timeout: float = 12) -> str:
    """The backup notice of SCR-009, scrolled to; with `want`, read again until it says that (the screen reads the phone
    again when the app comes back to the front)."""
    node = seek(lambda n: n.text in BACKUPS, "the backup notice")
    end = time.time() + timeout
    while want is not None and node.text != want and time.time() < end:
        time.sleep(1)
        node = next((n for n in dump_ui() if n.text in BACKUPS), node)
    return node.text


def bring_back_app() -> None:
    """Leaves the app for the home screen and brings it back to the front, on the screen it was on (onResume)."""
    go_home()
    sh(f"am start -n {ACTIVITY}")
    time.sleep(3)


def lock_on() -> None:
    """Sets the throwaway screen lock of the reminder check (reminder_check.PIN). Neither the PIN nor what the command
    printed is kept or written anywhere."""
    LOCK["on"] = True  # cleared at the end even when setting it half failed
    try:
        set_pin()
    except Exception:
        raise RuntimeError("시험 화면 잠금을 걸지 못함") from None


def lock_off() -> bool:
    """Clears that lock. True when the phone said it cleared it (the output itself is not kept)."""
    try:
        out = sh(f"locksettings clear --old {PIN}", check=False)
    except Exception:
        return False
    cleared = "clear" in out.lower()
    if cleared:
        LOCK["on"] = False
    return cleared


def widget_dose(name: str, key: str) -> str | None:
    """The source of `name`'s active dose in the window `key`, or None."""
    rows = query("SELECT d.source FROM dose_record d JOIN intake i ON i.id = d.intake_id "
                 f"WHERE i.name = '{name}' AND d.active = 1 AND d.kind = 'TAKEN' AND d.period_key = '{key}'")
    return str(rows[0][0]) if rows else None


def wait_dose(name: str, key: str, timeout: float = 20) -> str | None:
    end = time.time() + timeout
    while True:
        source = widget_dose(name, key)
        if source is not None or time.time() >= end:
            return source
        time.sleep(1.5)


def active_keys() -> list[tuple]:
    return query("SELECT i.name, d.period_key, d.kind, d.source FROM dose_record d JOIN intake i ON i.id = d.intake_id "
                 "WHERE d.active = 1 ORDER BY i.name, d.period_key")


# ------------------------------------------------------------------------------------------------
# The widget on the home screen


def widget_host(nodes: list[Node]) -> Node | None:
    """Our widget on the launcher page shown: the host read as the app's name (Pixel Launcher, API 34 and 36; on API 36
    the list's rows are hosts too, unnamed), else the largest unnamed host that holds the widget's header, empty text or
    a tile (a launcher that does not name the host)."""
    hosts = [n for n in nodes if "AppWidgetHostView" in n.cls]
    named = next((n for n in hosts if n.desc == WIDGET_LABEL), None)
    if named is not None:
        return named

    def ours(n: Node) -> bool:
        return n.text.startswith(f"{WIDGET_LABEL} · ") or n.text == EMPTY_WIDGET or n.desc.split(", ")[0] in ITEMS

    held = [h for h in hosts if h.desc == "" and any(ours(n) and within(n, h) for n in nodes)]
    return max(held, key=lambda h: (h.bounds[2] - h.bounds[0]) * (h.bounds[3] - h.bounds[1]), default=None)


LAUNCHER_PAGES = 4


def widget_page(ok, timeout: float = 30) -> list[Node]:
    """The launcher page with our widget, read until `ok(nodes)` holds. The widget the intro asks for went to the
    launcher's second page on API 34 and 36, and HOME pressed while the launcher is in front, or after a text size
    change has made it again, shows its first page (screens2 run 43539426392: the widget steps found no widget). So when
    the page HOME shows has no widget, HOME is pressed again for the first page and the pages are swiped to the left one
    at a time, in the empty middle of the screen. Never to the right of the first page: Pixel Launcher keeps a feed
    there."""
    go_home()
    nodes = dump_ui()
    if widget_host(nodes) is None:
        go_home()
        w, h = size()
        y = h * 9 // 20
        for _ in range(LAUNCHER_PAGES):
            sh(f"input swipe {w * 5 // 6} {y} {w // 6} {y} 300")
            time.sleep(2)
            nodes = dump_ui()
            if widget_host(nodes) is not None:
                break
        else:
            raise RuntimeError(f"our widget is on none of the launcher's first {LAUNCHER_PAGES + 1} pages")
    return nodes if ok(nodes) else wait_screen(ok, timeout)


def within(inner: Node, outer: Node, slack: int = 1) -> bool:
    x1, y1, x2, y2 = inner.bounds
    ox1, oy1, ox2, oy2 = outer.bounds
    return x1 >= ox1 - slack and y1 >= oy1 - slack and x2 <= ox2 + slack and y2 <= oy2 + slack


def list_view(nodes: list[Node]) -> Node | None:
    """The widget's list: Glance draws its LazyColumn of rows as a ListView (PR #16)."""
    host = widget_host(nodes)
    lists = [n for n in nodes if n.cls.endswith("ListView")]
    if host is not None:
        lists = [n for n in lists if within(n, host)] or lists
    return lists[0] if lists else None


def tiles_in(nodes: list[Node], box: Node | None) -> dict[str, Node]:
    """The tiles drawn in the list, by item name. A tile is read as one sentence that starts with its name (one person:
    no person before it). Bounds are what shows: uiautomator clips them to the list."""
    found: dict[str, Node] = {}
    if box is None:
        return found
    for n in nodes:
        head = n.desc.split(", ")[0]
        if head in ITEMS and head not in found and n.desc.startswith(f"{head}, ") and within(n, box):
            found[head] = n
    return found


def widget_ready(nodes: list[Node]) -> bool:
    return bool(tiles_in(nodes, list_view(nodes)))


def settled_widget(timeout: float = 15) -> list[Node]:
    """The home screen read until the widget's list and tiles sit where they sat one read before: a redraw for a new text
    size has finished."""
    nodes = widget_page(widget_ready, 30)
    end = time.time() + timeout
    last = None
    while True:
        box = list_view(nodes)
        now = (box.bounds if box else None, sorted((k, n.bounds) for k, n in tiles_in(nodes, box).items()))
        if now == last or time.time() >= end:
            return nodes
        last = now
        time.sleep(1.5)
        nodes = dump_ui()


def whole(n: Node) -> bool:
    """Every tile is at least 64dp tall, so a shorter one is cut by the list's edge."""
    return n.bounds[3] - n.bounds[1] >= TILE_MIN_HEIGHT * DENSITY["d"] - 4


def columns_seen(tiles: dict[str, Node]) -> int:
    lefts: list[int] = []
    for n in tiles.values():
        if not any(abs(n.bounds[0] - x) <= 6 for x in lefts):
            lefts.append(n.bounds[0])
    return len(lefts)


def body_range(scale: str) -> tuple[float, float]:
    """16sp in dp at `scale`: linear before Android 14, else between linear and NONLINEAR_16SP."""
    linear = 16 * float(scale)
    other = NONLINEAR_16SP.get(scale, linear) if API["n"] >= 34 else linear
    return min(linear, other), max(linear, other)


def expected_columns(inner_dp: float, lo: float, hi: float) -> tuple[int | None, float]:
    """WidgetLayout.plan's tiles per row for a list `inner_dp` wide, and the largest 16sp size that keeps two. None when
    the size range reaches within 0.3dp of that limit."""
    text = (inner_dp - GAP) / 2 - TILE_PAD_START - TILE_PAD_END
    limit = text / LONGEST_PIECE_EM
    if hi <= limit - 0.3:
        return 2, limit
    if lo >= limit + 0.3:
        return 1, limit
    return None, limit


# How drag_list moves a finger on API 28 and older, which have no `input motionevent`: "fast" (straight, within
# FAST_DRAG_MS) or "diagonal" (as fast, wider than tall). probe_old_drag picks it on the launcher in step 11.
OLD_DRAG = {"how": "fast"}
FAST_DRAG_MS = 250
SLOW_DRAG_DP_S = 25


def drag_list(x: int, y1: int, y2: int) -> None:
    """Moves a finger from y1 to y2 in the list and lifts it (PR #16, widget_scroll_check.drag_list). From API 29
    `input motionevent`: past the touch slop at once (a slow start is a long press that picks the widget up), still for
    0.4s before lifting, so the list does not fling. Older images have no motionevent, and the slow swipe PR #16 used
    there (25dp/s, in a test host) never moved the list on the API 26 launcher: 30 drags in screens2 run 43542096180,
    the launcher taking the touch about 0.34s after it went down (logcat: its hotseat's layers were built then). So the
    drag ends within FAST_DRAG_MS, before a launcher long press, and the list flings a little further (a few dp at this
    speed); probe_old_drag says whether it goes straight or wider than tall."""
    d = DENSITY["d"]
    if API["n"] >= 29:
        sign = -1 if y2 < y1 else 1
        slop = round(8 * d)
        sh(f"input motionevent DOWN {x} {y1}")
        y = y1 + sign * min(slop + 12, abs(y2 - y1))
        sh(f"input motionevent MOVE {x} {y}")
        step = round(30 * d)
        while (y2 - y) * sign > 0:
            y += sign * min(step, abs(y2 - y))
            sh(f"input motionevent MOVE {x} {y}")
        time.sleep(0.4)
        sh(f"input motionevent UP {x} {y2}")
    else:
        # Wider than tall: the launcher's swipe up to the app drawer wants a path steeper than 45°, while the list
        # starts once the finger is 8dp up or down, before the workspace's page swipe (its slop grows with the angle).
        dx = round(abs(y2 - y1) * 1.1) if OLD_DRAG["how"] == "diagonal" else 0
        sh(f"input swipe {x + dx // 2} {y1} {x - dx // 2} {y2} {FAST_DRAG_MS}")
    time.sleep(1.5)


def list_place(nodes: list[Node]) -> dict[str, tuple[int, int]]:
    """Where each tile of the list shows, top and bottom."""
    return {k: (n.bounds[1], n.bounds[3]) for k, n in tiles_in(nodes, list_view(nodes)).items()}


def list_moved(before: dict[str, tuple[int, int]], after: dict[str, tuple[int, int]]) -> bool:
    return set(before) != set(after) or any(abs(a - b) > 8 for k in before for a, b in zip(before[k], after[k]))


def probe_old_drag(r: Report) -> str:
    """API 28 and older, once before the six taps (TASK-042 on Android 8): what this launcher does with a drag over
    the widget's list, and which drag_list then uses. The slow straight drag of run 43542096180 again, photographed
    halfway (a long press picks the widget up, the app drawer follows the finger, or the list moves); then a fast
    straight drag, and if the list stays where it was, a fast one wider than tall. Raises when none moves it."""
    d = DENSITY["d"]
    seen: list[str] = []

    def drag_and_look(label: str, drag) -> bool:
        nodes = widget_page(widget_ready, 30)
        box = list_view(nodes)
        before = list_place(nodes)
        drag(box)
        nodes = dump_ui()
        if list_view(nodes) is None:  # the app drawer or another page in front: back to the widget
            seen.append(f"{label}: 위젯 목록이 사라짐(런처가 다른 화면을 엶)")
            nodes = widget_page(widget_ready, 30)
            return False
        moved = list_moved(before, list_place(nodes))
        seen.append(f"{label}: {'목록이 움직임' if moved else '목록 그대로'}")
        return moved

    def slow(box: Node) -> None:
        x = (box.bounds[0] + box.bounds[2]) // 2
        y1 = box.bounds[3] - 6
        span = max((box.bounds[3] - box.bounds[1]) * 2 // 5, round(70 * d)) + round(8 * d)
        duration = int(span / (SLOW_DRAG_DP_S * d) * 1000)
        drag = subprocess.Popen(["adb", "shell", f"input swipe {x} {y1} {x} {y1 - span} {duration}"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(min(1.5, duration / 2000))
        r.shot("widget-slow-drag")  # the launcher halfway through the drag; a dump would wait for it to end
        drag.wait(timeout=60)
        time.sleep(2)

    def fast(box: Node) -> None:
        move_list(box, content_up=True, px=max((box.bounds[3] - box.bounds[1]) * 2 // 5, round(70 * d)))

    drag_and_look(f"느린 곧은 드래그({SLOW_DRAG_DP_S}dp/s, 반쯤에서 캡처)", slow)
    OLD_DRAG["how"] = "fast"
    if not drag_and_look(f"빠른 곧은 드래그({FAST_DRAG_MS / 1000:g}초)", fast):
        OLD_DRAG["how"] = "diagonal"
        if not drag_and_look(f"빠르고 가로가 더 긴 드래그({FAST_DRAG_MS / 1000:g}초)", fast):
            raise RuntimeError(f"no drag moved the widget's list on this launcher: {'; '.join(seen)}")
    r.note(f"API {API['n']} 런처에서 위젯 목록 끌기(관찰, TASK-042): {'; '.join(seen)}. 여섯 칸 탭은 마지막 방법으로 스크롤")
    return "; ".join(seen)


def move_list(box: Node, content_up: bool, px: int) -> None:
    """Moves the list's content up (to show what is below) or down by about `px`, plus the touch slop."""
    d = DENSITY["d"]
    x = (box.bounds[0] + box.bounds[2]) // 2
    top, bottom = box.bounds[1], box.bounds[3]
    span = max(px, round(6 * d)) + round(8 * d)
    if content_up:
        drag_list(x, bottom - 6, bottom - 6 - span)
    else:
        drag_list(x, top + 6, top + 6 + span)


def reach_tile(name: str, tries: int = 10) -> tuple[Node | None, Node | None]:
    """Scrolls the widget's list until at least 48dp of `name`'s tile shows (or all of it, when the list is lower), and
    returns the tile and the list."""
    d = DENSITY["d"]
    box = None
    for attempt in range(tries + 1):
        nodes = dump_ui()
        box = list_view(nodes)
        if box is None:
            return None, None
        tiles = tiles_in(nodes, box)
        node = tiles.get(name)
        need = min(48 * d, box.bounds[3] - box.bounds[1] - 8)
        if node is not None and node.bounds[3] - node.bounds[1] >= need:
            return node, box
        if attempt == tries:
            break
        top, bottom = box.bounds[1], box.bounds[3]
        if node is not None:
            cut_at_bottom = node.bounds[3] >= bottom - 2
            hidden = TILE_MIN_HEIGHT * d - (node.bounds[3] - node.bounds[1])
            how = f"{name} 일부 {node.bounds[1]}-{node.bounds[3]}"
            move_list(box, content_up=cut_at_bottom, px=round(max(hidden, 24 * d)))
        else:
            shown = [ITEMS.index(k) for k in tiles]
            further_down = not shown or ITEMS.index(name) > max(shown)
            how = f"{name} 안 보임, 보이는 칸 {', '.join(tiles) or '없음'}"
            move_list(box, content_up=further_down, px=max((bottom - top) * 2 // 5, round(70 * d)))
        print(f"[debug] widget scroll {attempt + 1}: {how}; 목록 {top}-{bottom}", flush=True)
    return None, box


def weekly_line_inside(expected: str, tries: int = 8) -> tuple[Node | None, Node | None, str]:
    """Scrolls the list until Weekly's last-dose line (TextView `expected`) lies whole inside the list: not touching its
    top or bottom edge, where uiautomator would have clipped it. Returns the line, the list and what was done."""
    d = DENSITY["d"]
    want = plain(expected)
    done: list[str] = []
    box = None
    for attempt in range(tries + 1):
        nodes = dump_ui()
        box = list_view(nodes)
        if box is None:
            return None, None, "목록 없음"
        top, bottom = box.bounds[1], box.bounds[3]
        line = next((n for n in nodes if plain(n.text) == want and within(n, box)), None)
        if line is not None and line.bounds[1] > top + 1 and line.bounds[3] < bottom - 1:
            return line, box, ", ".join(done) or "스크롤 없음"
        if attempt == tries:
            break
        if line is None:
            # Weekly is the first row: back towards the top of the list
            move_list(box, content_up=False, px=round(70 * d))
            done.append("위로")
        elif line.bounds[1] > top + 1:
            # Cut at the bottom: its top shows, so bring that top 4dp under the list's top edge
            move_list(box, content_up=True, px=max(line.bounds[1] - top - round(4 * d), round(6 * d)))
            done.append("아래로")
        else:
            move_list(box, content_up=False, px=round(30 * d))
            done.append("위로 조금")
    return None, box, ", ".join(done)


def widget_header(nodes: list[Node]) -> tuple[str, str]:
    """The header's date ('DidITake · 11/14') and count ('0 / 6') on the home screen."""
    head = next((n.text for n in nodes if n.text.startswith(f"{WIDGET_LABEL} · ")), "")
    count = next((n.text for n in nodes if re.fullmatch(r"\d+ / \d+", n.text)), "")
    return head, count


# ------------------------------------------------------------------------------------------------
# The platform time picker (AndroidPhone.pickTime: TimePickerDialog, 24-hour)


def pick_platform_time(hour: int, minute: int) -> str:
    """Sets the open TimePickerDialog: in its keyboard mode when it has one, else on the clock face (hours and minutes
    are read as their numbers), then [OK]. Returns how."""
    def has_fields(ns: list[Node]) -> bool:
        return any(n.rid.endswith("/input_hour") for n in ns)

    nodes = wait_screen(lambda ns: any(n.rid.endswith(("/toggle_mode", "/input_hour")) or n.text == "OK" for n in ns), 10)
    toggle = next((n for n in nodes if n.rid.endswith("/toggle_mode")), None)
    if toggle is not None and not has_fields(nodes):
        tap(toggle)
        nodes = wait_screen(has_fields, 6)
    if has_fields(nodes):
        for key, value in (("hour", hour), ("minute", minute)):
            field = next((n for n in dump_ui() if n.rid.endswith(f"/input_{key}")), None)
            if field is None:
                raise RuntimeError(f"no {key} field in the time picker")
            tap(field)
            sh("input keyevent KEYCODE_MOVE_END")
            for _ in range(3):
                sh("input keyevent KEYCODE_DEL")
            sh(f"input text {value:02d}")
            time.sleep(1)
        how = "시각 고르기의 키보드 칸"
    else:
        for value in (str(hour), str(minute)):
            target = next((n for n in dump_ui() if n.desc == value or (n.text == value and not n.cls.endswith("EditText"))),
                          None)
            if target is None:
                raise RuntimeError(f"'{value}' not on the clock face")
            tap(target)
            time.sleep(1)
        how = "시각 고르기의 시계 판"
    ok = next((n for n in dump_ui() if n.rid.endswith("/button1") or n.text.upper() == "OK"), None)
    if ok is None:
        raise RuntimeError("no [OK] in the time picker")
    tap(ok)
    return how


# ------------------------------------------------------------------------------------------------
# Flow


def run(r: Report, steps: ShotSteps, apk: Path, api: int) -> None:
    base = datetime.now(ZONE).date()
    full = api >= 33  # the permission window (Android 13) and exact alarms that a fresh install does not get (14)
    backup_only = api == 27
    scales = ("1.0", "1.15", "1.3", "2.0") if api >= 34 else ("1.0", "1.15", "1.3")
    largest = scales[-1]
    key0 = base.isoformat()  # period key of day 0's windows: the dailies' and Weekly's (anchored on day 0's weekday)

    def at(day: int, hour: int, minute: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=ZONE)

    facts: dict[str, str] = {}

    # 0. phone setup
    def setup() -> str:
        become_root()
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {name} 0")
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("svc power stayon true", check=False)
        sh("settings put system screen_off_timeout 1800000", check=False)
        # A reminder that comes due across a clock jump would drop down over the top of the screen
        sh("settings put global heads_up_notifications_enabled 0", check=False)
        DENSITY["d"] = physical_density() / 160
        zone = set_zone()
        clock = set_clock(at(0 if backup_only else -7, 8, 30))
        font_scale("1.0")
        night(False)
        uninstall()
        install(apk)
        return f"API {api}, 밀도 {DENSITY['d']}, 시간대 {zone}, 시계 {clock}"

    when = "오늘" if backup_only else "7일 전"
    if not steps(f"0. 준비: 서울 시간대, {when} 08:30, 새로 설치", "시간대·시계가 바뀌고 앱이 깔림", setup):
        return

    if backup_only:
        def open_app() -> str:
            launch_app()
            return "첫 실행 안내를 [건너뛰기]로 넘김"

        steps("1. 앱 열기", "첫 화면", open_app)
        backup_step(r, steps, api, "2")
        return

    # 1. the widget through the intro's [위젯 추가] (TASK-022's request): no dragging, also on Android 8
    placed = {"ok": False}

    def intro_widget() -> str:
        how = "첫 실행 안내의 [위젯 추가] → 런처의 추가 창"
        try:
            launch_app(stop_at_intro=True)
            time.sleep(2)
            tap_exact("다음")
            if exact("위젯 추가", 10) is None:
                raise RuntimeError("no [위젯 추가] on the intro's second page")
            r.shot("intro-2")
            tap_exact("위젯 추가")
            button = find_pin_button()
            r.shot("pin-sheet")
            save_ui(r, "pin-sheet")
            if button is None:
                raise RuntimeError("the launcher's add sheet did not show")
            tap(button)
            time.sleep(4)
            tap_exact("시작하기", 15)
        except RuntimeError as exc:
            if api < 29:
                # No dragging without `input motionevent`: past the intro for the next steps, and the failure reported
                try:
                    go_home()
                    launch_app()
                except Exception:
                    pass
                raise
            # Android 10 and later: the widget dragged from the launcher's list as a person does (release_check)
            r.note(f"첫 실행 안내로 위젯을 놓지 못해 런처 목록에서 끌어 놓음: {exc}")
            go_home()
            launch_app()
            place_widget(r, "widget-drag")
            how = "런처의 위젯 목록에서 끌어 놓음"
        nodes = widget_page(lambda ns: has_text(ns, EMPTY_WIDGET), 30)
        r.shot("widget-empty")
        save_ui(r, "widget-empty")
        ids = our_widget_ids()
        choices = stored_choices()
        if len(ids) != 1 or not has_text(nodes, EMPTY_WIDGET):
            raise RuntimeError(f"widgets {ids}, texts {[n.text for n in nodes if n.text][:12]}")
        placed["ok"] = True
        host = widget_host(nodes)
        facts["host"] = f"{host.bounds} ({dp(host.bounds[2] - host.bounds[0]):.0f}×{dp(host.bounds[3] - host.bounds[1]):.0f}dp)" \
            if host else "?"
        return f"{how}; 위젯 {ids}, 저장된 선택 {choices}, 크기 {facts['host']}"

    steps("1. 위젯 놓기 (첫 실행 안내의 [위젯 추가])", "홈 화면에 위젯 하나, 빈 안내", intro_widget)

    # 2. day -7: Weekly recorded, so that day 0 has a last dose before its window
    weekly: dict[str, str] = {}

    def weekly_dose() -> str:
        to_home()
        tap_scrolled("항목 추가")
        type_name(WEEKLY)
        tap_exact("주 1회")
        group = exact("무슨 요일에?")
        tap_exact(WEEKDAYS[base.weekday()], below=group.bounds[3] if group else -1)
        r.shot("form-weekly")
        tap_exact("저장")
        if tile(WEEKLY, 20) is None:
            raise RuntimeError(f"tile {WEEKLY} did not show after [저장]")
        tap(row_button(WEEKLY, "먹었어"))
        time.sleep(2)
        rows = active(WEEKLY)
        r.shot("home-weekly-taken")
        if not rows or rows[-1][1] != "TAKEN":
            raise RuntimeError(f"{WEEKLY}: {rows}")
        taken = rows[-1][4]  # 'MM-DD HH:MM' in Seoul
        weekly["time"] = taken[-5:]
        dose_day = base - timedelta(days=7)
        weekly["line"] = f"마지막 복용 {day_of(dose_day)}\u00a0{weekly['time']}"
        weekly["spoken"] = f"마지막 복용 {dose_day.month}월 {dose_day.day}일 {WEEKDAYS[dose_day.weekday()]}요일 {weekly['time']}"
        return f"{WEEKLY} 주 1회 {WEEKDAYS[base.weekday()]}, 먹음 {rows[-1]}"

    steps("2. 7일 전: Weekly 추가와 [먹었어]", "주 1회 항목 저장, 먹음 1건", weekly_dose)

    # 3. day 0: Weekly's window starts again and shows its last dose
    def day0() -> str:
        clock = set_clock(at(0, 8, 30))
        reopen()
        nodes = wait_screen(lambda ns: bool(spoken(ns, WEEKLY)), 15)
        said = spoken(nodes, WEEKLY)
        r.shot("home-day0")
        save_ui(r, "home-day0")
        want = f"{WEEKLY}, 이번 주 미완료, {weekly.get('spoken', '?')}"
        if not said or any(s != want for s in said):
            raise RuntimeError(f"first screen reads {said}, expected '{want}'")
        return f"시계 {clock}; 첫 화면 '{said[0]}'"

    steps("3. 0일 08:30: Weekly 마지막 복용 (TASK-041)", "첫 화면 칸이 '… 이번 주 미완료, 마지막 복용 (7일 전) …'으로 읽힘",
          day0)

    if full:
        permission_steps(r, steps)
    else:
        def settings_access_old() -> str:
            open_settings()
            rows = access_rows()
            r.shot("settings-access")
            save_ui(r, "settings-access")
            want_on = {NOTIF_ROW: f"{NOTIF_ROW}, {ACCESS_ON}", EXACT_ROW: f"{EXACT_ROW}, {ACCESS_ON}"}
            battery = rows.get(BATTERY_ROW, "")
            if any(rows.get(k) != v for k, v in want_on.items()) or not battery.startswith(f"{BATTERY_ROW}, {BATTERY_NOTE_START}"):
                raise RuntimeError(f"rows {rows}")
            tap(seek(desc_is(f"{BATTERY_ROW} {OPEN_SETTINGS}"), "the battery row's button"))
            pkg, texts = system_screen()
            r.shot("system-battery")
            save_ui(r, "system-battery")
            back_to_app(lambda ns: has_desc(ns, f"{BATTERY_ROW}, "))
            promises = [t for t in rows.values() if MINUTE_PROMISE.search(t)]
            if pkg in ("", PKG) or promises:
                raise RuntimeError(f"system screen {pkg} {texts}, minutes {promises}")
            return f"{' / '.join(rows.values())}; [설정 열기] → {pkg} {texts[:3]}"

        steps("4. 설정 > 알림이 오지 않을 때 (TASK-026, Android 8)",
              "알림·정확 알람은 켜짐(권한이 없는 버전), 배터리는 제한 문장과 [설정 열기] → 시스템 최적화 목록", settings_access_old)

    backup_step(r, steps, api, "8-1")

    if full:
        def names_switch() -> str:
            # Stored as "true"/"false" (ManageSettings.setShowNamesOnLock); no row yet means off
            open_settings()
            before = setting("show_names_on_lock")
            tap(seek(desc_is(SHOW_NAMES_ROW), "the lock-screen names row"))
            nodes = wait_screen(lambda ns: has_text(ns, SHOW_NAMES_ON), 10)
            on = setting("show_names_on_lock")
            r.shot("settings-names-on")
            save_ui(r, "settings-names-on")
            tap(seek(desc_is(SHOW_NAMES_ROW), "the lock-screen names row"))
            off_nodes = wait_screen(lambda ns: not has_text(ns, SHOW_NAMES_ON), 10)
            off = setting("show_names_on_lock")
            r.shot("settings-names-off")
            if before == "true" or on != "true" or off != "false" or not has_text(nodes, SHOW_NAMES_ON) \
                    or has_text(off_nodes, SHOW_NAMES_ON):
                raise RuntimeError(f"setting {before} → {on} → {off}, band shown {has_text(nodes, SHOW_NAMES_ON)}, "
                                   f"band after off {has_text(off_nodes, SHOW_NAMES_ON)}")
            return f"켬: '{SHOW_NAMES_ON}' 띠, 저장값 {before} → {on}; 다시 끔: 띠 사라짐, 저장값 {off}"

        steps("8-2. 잠금 화면에 약 이름 보이기 (TASK-020)", f"켜면 '{SHOW_NAMES_ON}', 끄면 띠가 사라짐", names_switch)

        def interval_quiet() -> str:
            open_settings()
            tap(seek(desc_starts("다시 알림 간격, "), "the interval row"))
            tap_exact("120분", 10)
            r.shot("settings-interval-120")
            tap_exact("저장")
            nodes = wait_screen(lambda ns: has_text(ns, QUIET_SAVED), 10)
            scroll_top()
            r.shot("settings-quiet-notice")
            save_ui(r, "settings-quiet-notice")
            stored = setting("reminder_interval_minutes")
            band = next((n.text for n in nodes if n.text.startswith("방해 금지 시간과")), "")
            if band != QUIET_SAVED or stored != "120":
                raise RuntimeError(f"band '{band}', stored interval {stored}")
            return f"'{band}', 저장값 {stored}분"

        steps("8-3. 다시 알림 간격 120분 → 방해 금지 안내 (TASK-020, 검토 C6)", f"'{QUIET_SAVED}'", interval_quiet)

    # 9. six items on the widget
    def six_items() -> str:
        names = list(SEEDED) if full else [NIGHT, EVENING, *SEEDED]
        ids = seed(names, "20:00")
        reopen()
        refresh_widgets()
        nodes = widget_page(lambda ns: len(tiles_in(ns, list_view(ns))) >= 2, 30)
        listed = query("SELECT name FROM intake WHERE archived_at IS NULL ORDER BY sort_order, created_at, id")
        r.shot("widget-six")
        save_ui(r, "widget-six")
        order = [row[0] for row in listed]
        if order != list(ITEMS) or len(ids) != len(names) or not widget_ready(nodes):
            raise RuntimeError(f"items {order}, seeded {ids}")
        return f"디버그 수신기로 {', '.join(names)} 추가; 항목 순서 {', '.join(order)}"

    steps("9. 항목 여섯 (위젯 4×2)", f"항목 {', '.join(ITEMS)}", six_items)

    # 10. the widget at each text size (TASK-014's thresholds, TASK-041's line at 100% and the largest size)
    def widget_font(scale: str) -> str:
        if not placed["ok"]:
            raise RuntimeError("no widget on the home screen (step 1)")
        font_scale(scale)
        refresh_widgets()
        nodes = settled_widget()
        box = list_view(nodes)
        if box is None:
            raise RuntimeError("no widget list on the home screen")
        tiles = tiles_in(nodes, box)
        r.shot(f"widget-font-{pct(scale)}")
        save_ui(r, f"widget-font-{pct(scale)}")
        inner = dp(box.bounds[2] - box.bounds[0])
        lo, hi = body_range(scale)
        want, limit = expected_columns(inner, lo, hi)
        seen = columns_seen(tiles)
        shown = [k for k, n in tiles.items() if whole(n)]
        hidden = len(ITEMS) - len(shown)
        arrow = any(n.desc == MORE for n in nodes)
        head, count = widget_header(nodes)
        problems = []
        if want is not None and seen != want:
            problems.append(f"한 줄에 {seen}개 (WidgetLayout으로는 {want}개)")
        if hidden > 0 and not arrow:
            problems.append(f"가려진 칸 {hidden}개인데 '{MORE}' 없음")
        result = (f"목록 {box.bounds} 폭 {inner:.0f}dp, 한 줄에 {seen}개 (16sp ≈ {lo:.1f}~{hi:.1f}dp, 둘이 되는 한계 "
                  f"{limit:.1f}dp → {want or '문턱 근처라 판정 안 함'}), 다 보이는 칸 {', '.join(shown) or '없음'}, "
                  f"'{MORE}' {'보임' if arrow else '없음'}, 머리글 '{head}' '{count}'")
        if scale in ("1.0", largest):
            line, box2, how = weekly_line_inside(weekly.get("line", "?"))
            r.shot(f"widget-font-{pct(scale)}-weekly")
            save_ui(r, f"widget-font-{pct(scale)}-weekly")
            if line is None:
                problems.append(f"{WEEKLY}의 '{plain(weekly.get('line', '?'))}'가 목록 안에 다 보이지 않음 ({how})")
            else:
                result += (f"; {WEEKLY} 줄 '{plain(line.text)}' {line.bounds} 높이 {dp(line.bounds[3] - line.bounds[1]):.0f}dp, "
                           f"목록 {box2.bounds} 안 ({how})")
        if problems:
            raise RuntimeError("; ".join(problems) + f" — {result}")
        return result

    for i, scale in enumerate(scales, start=1):
        tasks = "TASK-014, TASK-041" if scale in ("1.0", largest) else "TASK-014"
        want = f"한 줄 칸 수가 WidgetLayout과 같고, 가려진 칸이 있으면 '{MORE}'"
        if scale in ("1.0", largest):
            want += f", {WEEKLY}의 마지막 복용 줄이 목록 안에 다 보임"
        steps(f"10-{i}. 위젯 글자 {pct(scale)}% ({tasks})", want, lambda s=scale: widget_font(s))

    # 11. all six tapped on the widget, scrolling its list (TASK-014, TASK-042)
    def six_taps() -> str:
        if not placed["ok"]:
            raise RuntimeError("no widget on the home screen (step 1)")
        font_scale("1.0")
        refresh_widgets()
        widget_page(widget_ready, 30)
        if API["n"] < 29:
            probe_old_drag(r)
        done, problems, kept = [], [], []
        for name in TAP_ORDER:
            node, box = reach_tile(name)
            if node is None or box is None:
                problems.append(f"{name}에 닿지 못함")
                continue
            before = sorted(k for k, n in tiles_in(dump_ui(), box).items() if whole(n))
            top = max(node.bounds[1], box.bounds[1])
            bottom = min(node.bounds[3], box.bounds[3])
            sh(f"input tap {(node.bounds[0] + node.bounds[2]) // 2} {(top + bottom) // 2}")
            source = wait_dose(name, key0)
            if source != "WIDGET":
                problems.append(f"{name} 판정 {source}")
            done.append(f"{name} {source}")
            time.sleep(2)
            after_nodes = dump_ui()
            after = sorted(k for k, n in tiles_in(after_nodes, list_view(after_nodes)).items() if whole(n))
            kept.append(f"{name}: {'그대로' if after == before else f'{before} → {after}'}")
        # The last tap's message holds the header's place for 10 seconds (WidgetState.MESSAGE_TTL_MS)
        nodes = widget_page(lambda ns: widget_header(ns)[1] == f"{len(ITEMS)} / {len(ITEMS)}", 25)
        head, count = widget_header(nodes)
        r.shot("widget-all-taken")
        save_ui(r, "widget-all-taken")
        r.note(f"위젯 탭 뒤 목록 위치(관찰, 다 보이는 칸 비교): {'; '.join(kept)}")
        if problems or count != f"{len(ITEMS)} / {len(ITEMS)}":
            raise RuntimeError(f"{'; '.join(problems)}; header '{head}' '{count}'")
        return f"{', '.join(done)}; 머리글 '{head}' '{count}'"

    steps("11. 위젯에서 여섯 칸 모두 누르기 (TASK-014, TASK-042)", "스크롤해 닿은 칸마다 위젯 기록(source WIDGET), 머리글 6 / 6",
          six_taps)

    if full:
        day_start_step(r, steps, base, at, key0)


def permission_steps(r: Report, steps: ShotSteps) -> None:
    """Steps 4 to 7: the notification permission refused and allowed, the notices and their system screens."""

    def item_deny() -> str:
        item_form(NIGHT, "자기 전")
        before = notification_permission()
        tap(reminder_switch())
        window = permission_window(10)
        if window is None:
            raise RuntimeError(f"no permission window after [{REMINDER_SWITCH}] (permission {before})")
        r.shot("permission-ask-1")
        save_ui(r, "permission-ask-1")
        tap(window["deny"])
        wait_find(has_notice(ITEM_NOTIFICATIONS_OFF), 15)
        start, notice, button = form_reminder()
        r.shot("form-denied")
        save_ui(r, "form-denied")
        after = notification_permission()
        if notice is None or not button or start != "첫 알림 시각, 21시" or after[0] is not False:
            raise RuntimeError(f"notice {notice is not None}, button {button}, time '{start}', permission {after}")
        return (f"권한 창 '{window['message']}' → [{window['deny'].text}]; 스위치 켠 채 '{ITEM_NOTIFICATIONS_OFF}'와 "
                f"[{OPEN_SETTINGS}], '{start}'; 권한 {before} → {after}")

    steps("4-1. Night [알림 받기] → 권한 창 → 허용 안 함 (TASK-023, TC-028)",
          f"시스템 권한 창, 거부 뒤 스위치는 켠 채 '{ITEM_NOTIFICATIONS_OFF}'와 [{OPEN_SETTINGS}], 첫 알림 21시", item_deny)

    def item_deny_again() -> str:
        tap(reminder_switch())  # off
        time.sleep(1)
        tap(reminder_switch())  # on: asked again
        second = permission_window(10)
        if second is None:
            raise RuntimeError("no second permission window")
        r.shot("permission-ask-2")
        tap(second["deny"])
        wait_find(has_notice(ITEM_NOTIFICATIONS_OFF), 15)
        fixed = notification_permission()
        tap(reminder_switch())
        time.sleep(1)
        tap(reminder_switch())
        third = permission_window(5)
        if third is not None:
            r.shot("permission-ask-3")
            tap(third["deny"])
        notice = wait_find(has_notice(ITEM_NOTIFICATIONS_OFF), 10)
        r.shot("form-denied-fixed")
        save_ui(r, "form-denied-fixed")
        if third is not None or notice is None or "USER_FIXED" not in fixed[1]:
            raise RuntimeError(f"third window {third is not None}, notice {notice is not None}, permission {fixed}")
        return f"두 번째 거부 뒤 권한 {fixed}; 세 번째 켜기에는 창 없이 바로 안내"

    steps("4-2. 두 번 거부한 뒤 다시 켜기 (TASK-023, TC-050)", "두 번째 창도 거부하면 USER_FIXED, 그다음에는 창 없이 바로 안내",
          item_deny_again)

    def item_settings() -> str:
        button = find(text_is(OPEN_SETTINGS))
        if button is None:
            raise RuntimeError(f"no [{OPEN_SETTINGS}] under the notice")
        tap(button)
        pkg, texts = system_screen()
        r.shot("system-from-form")
        save_ui(r, "system-from-form")
        back_to_app(lambda ns: has_text(ns, "저장"))  # the form's [저장] stays at its foot
        notice = wait_find(has_notice(ITEM_NOTIFICATIONS_OFF), 10)
        r.shot("form-after-system")
        if pkg in ("", PKG) or notice is None:
            raise RuntimeError(f"front package '{pkg}', texts {texts}; back on the form with the notice {notice is not None}")
        return f"[{OPEN_SETTINGS}] → {pkg} {texts[:3]}; 뒤로 → 입력하던 항목 화면, 안내 그대로"

    steps("4-3. 항목 화면 [설정 열기] → 시스템 화면 (TASK-023, TASK-026)", "앱 알림 설정 화면이 열리고, 뒤로 가면 입력하던 항목 화면",
          item_settings)

    def item_save_quiet() -> str:
        tap_exact("저장")
        nodes = wait_screen(lambda ns: has_text(ns, ITEM_SAVED), 15)
        body = next((n.text for n in nodes if n.text.startswith("방해 금지 시간")), "")
        r.shot("saved-quiet")
        save_ui(r, "saved-quiet")
        tap_exact("확인")
        home = wait_screen(at_home, 15)
        rule = query("SELECT r.enabled, r.start_time FROM reminder_rule r JOIN intake i ON i.id = r.intake_id "
                     f"WHERE i.name = '{NIGHT}'")
        if body != NIGHT_QUIET or not at_home(home) or rule != [(1, "21:00")]:
            raise RuntimeError(f"notice '{body}', home {at_home(home)}, rule {rule}")
        return f"'{ITEM_SAVED}' 창 '{body}' → [확인] → 첫 화면; 알림 규칙 {rule[0]}"

    steps("4-4. Night [저장] → 방해 금지 안내 (TASK-023, RR-49, TC-031)",
          f"'{ITEM_SAVED}' 창에 '{NIGHT_QUIET}', [확인] → 첫 화면, 알림 켠 채 21:00 저장", item_save_quiet)

    def home_off() -> str:
        nodes = home_notice(NOTIFICATIONS_OFF)
        r.shot("home-notifications-off")
        save_ui(r, "home-notifications-off")
        band = next((n.text for n in nodes if NOTIFICATIONS_OFF in n.text), "")
        if not band or MINUTE_PROMISE.search(band):
            raise RuntimeError(f"notice '{band}' on the first screen")
        tap_exact(OPEN_SETTINGS)
        pkg, texts = system_screen()
        r.shot("system-from-home")
        back_to_app(at_home)
        if pkg in ("", PKG):
            raise RuntimeError(f"[{OPEN_SETTINGS}] opened no system screen (front '{pkg}')")
        return f"첫 화면 '{band}' → [{OPEN_SETTINGS}] → {pkg} {texts[:3]}; 분 약속 없음"

    steps("5-1. 첫 화면의 알림 꺼짐 안내 (TASK-026, TC-015, TC-028)", f"'{NOTIFICATIONS_OFF}'와 [{OPEN_SETTINGS}] → 시스템 화면",
          home_off)

    def settings_off() -> str:
        open_settings()
        rows = access_rows()
        r.shot("settings-access-off")
        save_ui(r, "settings-access-off")
        labels = access_buttons()
        want = {NOTIF_ROW: f"{NOTIF_ROW}, {NOTIF_OFF_NOTE}", EXACT_ROW: f"{EXACT_ROW}, {EXACT_OFF_NOTE}"}
        battery = rows.get(BATTERY_ROW, "")
        promises = [t for t in rows.values() if MINUTE_PROMISE.search(t)]
        if any(rows.get(k) != v for k, v in want.items()) or not battery.startswith(f"{BATTERY_ROW}, {BATTERY_NOTE_START}") \
                or promises:
            raise RuntimeError(f"rows {rows}, minutes {promises}")
        return f"{' / '.join(rows.values())}; 버튼 {labels}; 분 약속 없음"

    steps("5-2. 설정 > 알림이 오지 않을 때, 셋 다 꺼짐 (TASK-026, TC-086)",
          "알림·정확 알람·배터리 줄이 꺼짐 문장과 버튼, 분 단위 약속 없음", settings_off)

    def item_allow() -> str:
        # Asked again as on a fresh install: the two refusals made it fixed (USER_FIXED)
        sh(f"pm revoke {PKG} {NOTIFICATION_PERMISSION}", check=False)
        sh(f"pm clear-permission-flags {PKG} {NOTIFICATION_PERMISSION} user-set user-fixed", check=False)
        reset = notification_permission()
        launch_app()
        item_form(EVENING, "저녁")
        tap(reminder_switch())
        window = permission_window(10)
        if window is None:
            raise RuntimeError(f"no permission window after the reset (permission {reset})")
        r.shot("permission-ask-allow")
        tap(window["allow"])
        time.sleep(2)
        start, notice, _ = form_reminder()
        r.shot("form-allowed")
        save_ui(r, "form-allowed")
        tap_exact("저장")
        home = wait_screen(at_home, 15)
        granted = notification_permission()
        if notice is not None or not at_home(home) or has_text(home, ITEM_SAVED) or not granted[0] \
                or start != "첫 알림 시각, 18시":
            raise RuntimeError(f"notice {notice is not None}, home {at_home(home)}, quiet window {has_text(home, ITEM_SAVED)}, "
                               f"permission {granted}, time '{start}'")
        return f"다시 물을 수 있게 함 {reset}; [{window['allow'].text}] → 안내 없음, '{start}', [저장] → 곧장 첫 화면; 권한 {granted}"

    steps("6. Evening [알림 받기] → 권한 창 → 허용 (TASK-023, TC-028)", "안내 없이 켜짐, 첫 알림 18시, 저장하면 곧장 첫 화면",
          item_allow)

    def exact_alarms() -> str:
        nodes = home_notice(EXACT_ALARMS_OFF)
        r.shot("home-exact-off")
        save_ui(r, "home-exact-off")
        band = next((n.text for n in nodes if EXACT_ALARMS_OFF in n.text), "")
        if not band or MINUTE_PROMISE.search(band) or band_text(nodes, NOTIFICATIONS_OFF):
            raise RuntimeError(f"first screen: {[n.text for n in nodes if n.text][:10]}")
        tap_exact(ALLOW)
        pkg, texts = system_screen()
        r.shot("system-exact-alarms")
        save_ui(r, "system-exact-alarms")
        # A Switch on older settings screens; the Compose settings of API 34 draw the row as a View read by its text
        # (screens2 run 43539426392 found no Switch there and allowed with appops)
        switch = next((n for n in dump_ui() if n.cls.endswith("Switch") or n.text.startswith(EXACT_SWITCH_TEXT)), None)
        how = "시스템 화면의 스위치"
        if switch is not None:
            tap(switch)
            time.sleep(2)
        state = sh(f"appops get {PKG} SCHEDULE_EXACT_ALARM", check=False)
        if "allow" not in state.lower():
            sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
            how = "appops (스위치로 켜지지 않음)"
            r.note(f"정확 알람: 시스템 화면의 스위치로 켜지지 않아 appops로 허용함 (그 화면: {texts[:3]})")
        r.shot("system-exact-alarms-on")
        home = back_to_app(lambda ns: at_home(ns) and not band_text(ns, EXACT_ALARMS_OFF), 15)
        r.shot("home-access-ok")
        if pkg in ("", PKG) or band_text(home, EXACT_ALARMS_OFF) or band_text(home, NOTIFICATIONS_OFF):
            raise RuntimeError(f"system screen '{pkg}', notice left {band_text(home, EXACT_ALARMS_OFF)}")
        return f"'{band}' → [{ALLOW}] → {pkg} {texts[:3]}; {how}으로 허용 → 안내 사라짐; 분 약속 없음"

    steps("7-1. 정확 알람 안내 → [허용하기] → 허용 (TASK-026, TC-029)", f"'{EXACT_ALARMS_OFF}'와 [{ALLOW}] → 이 앱의 허용 화면, "
          "허용하고 돌아오면 안내 없음", exact_alarms)

    def battery() -> str:
        open_settings()
        tap(seek(desc_is(f"{BATTERY_ROW} {OPEN_SETTINGS}"), "the battery row's button"))
        pkg, texts = system_screen()
        r.shot("system-battery")
        save_ui(r, "system-battery")
        sh(f"dumpsys deviceidle whitelist +{PKG}", check=False)
        back_to_app(lambda ns: has_desc(ns, f"{BATTERY_ROW}, {BATTERY_FREE}"))
        rows = access_rows()
        r.shot("settings-access-on")
        save_ui(r, "settings-access-on")
        want = {NOTIF_ROW: f"{NOTIF_ROW}, {ACCESS_ON}", EXACT_ROW: f"{EXACT_ROW}, {ACCESS_ON}",
                BATTERY_ROW: f"{BATTERY_ROW}, {BATTERY_FREE}"}
        if pkg in ("", PKG) or rows != want:
            raise RuntimeError(f"system screen '{pkg}' {texts}, rows {rows}")
        return f"[{OPEN_SETTINGS}] → {pkg} {texts[:3]}; 최적화에서 뺀 뒤 {' / '.join(rows.values())}"

    steps("7-2. 배터리 [설정 열기] → 최적화 목록, 셋 다 켜짐 (TASK-026, TC-030)",
          "시스템 배터리 최적화 목록이 열리고, 앱을 빼고 돌아오면 세 줄 모두 켜짐·제한 없음", battery)


def backup_step(r: Report, steps: ShotSteps, api: int, number: str) -> None:
    """TASK-020, TC-035: the backup line without and with a screen lock (SPEC-035)."""
    no_lock = BACKUP_OLD if api < 28 else BACKUP_NO_LOCK
    with_lock = BACKUP_OLD if api < 28 else BACKUP_ON

    def backup() -> str:
        open_settings()
        first = backup_line(no_lock)
        r.shot("settings-backup-no-lock")
        save_ui(r, "settings-backup-no-lock")
        try:
            lock_on()
            bring_back_app()
            locked = backup_line(with_lock)
            r.shot("settings-backup-lock")
            save_ui(r, "settings-backup-lock")
        finally:
            cleared = lock_off()
        bring_back_app()
        last = backup_line(no_lock)
        if (first, locked, last) != (no_lock, with_lock, no_lock) or not cleared:
            raise RuntimeError(f"lock off '{first}', on '{locked}', off again '{last}', lock cleared {cleared}")
        return f"잠금 없음 '{first}' / 잠금 있음 '{locked}' / 잠금을 지운 뒤 '{last}'"

    steps(f"{number}. 백업 안내, 화면 잠금 없음·있음 (TASK-020, TC-035)", f"잠금 없음 '{no_lock}', 잠금 있음 '{with_lock}'",
          backup)


def day_start_step(r: Report, steps: ShotSteps, base: date, at, key0: str) -> None:
    """Step 12, TASK-020 (RR-15, TC-033): the day start moved from 04:00 to 03:00 at 03:30, before the old start."""
    day1 = base + timedelta(days=1)
    header0 = f"{WIDGET_LABEL} · {base.month}/{base.day:02d}"
    header1 = f"{WIDGET_LABEL} · {day1.month}/{day1.day:02d}"

    def day_start() -> str:
        keys_before = active_keys()
        count_before = active_count()
        clock = set_clock(at(1, 3, 30))
        reopen()
        refresh_widgets()
        nodes = widget_page(lambda ns: widget_header(ns)[0] == header0, 20)
        before = widget_header(nodes)
        r.shot("widget-before-day-start")
        save_ui(r, "widget-before-day-start")
        open_settings()
        tap(seek(desc_is("하루 시작 시각, 4시"), "the day start row"))
        how = pick_platform_time(3, 0)
        if exact(DAY_START_ASK, 10) is None:
            raise RuntimeError("no P-5 question after picking 03:00")
        body = next((n.text for n in dump_ui() if n.text.startswith("03:00부터 하루가 시작돼요.")), "")
        r.shot("day-start-ask")
        save_ui(r, "day-start-ask")
        tap_exact("바꾸기")
        row = wait_screen(lambda ns: has_desc(ns, "하루 시작 시각, 3시"), 15)
        r.shot("settings-day-start")
        to_home()
        app_date = has_text(dump_ui(), day_label(day1))
        r.shot("home-day-start")
        after_nodes = widget_page(lambda ns: widget_header(ns)[0] == header1, 30)
        after = widget_header(after_nodes)
        r.shot("widget-after-day-start")
        save_ui(r, "widget-after-day-start")
        stored = setting("day_start")
        keys_after = active_keys()
        count_after = active_count()
        problems = []
        if before != (header0, f"{len(ITEMS)} / {len(ITEMS)}"):
            problems.append(f"before {before}")
        if after != (header1, f"1 / {len(ITEMS)}"):
            problems.append(f"after {after}")
        if not has_desc(row, "하루 시작 시각, 3시") or stored != "03:00" or not body or not app_date:
            problems.append(f"row {has_desc(row, '하루 시작 시각, 3시')}, stored {stored}, question '{body}', app date {app_date}")
        if keys_after != keys_before or count_after != count_before:
            problems.append(f"records {count_before} → {count_after}, keys changed {keys_after != keys_before}")
        if problems:
            raise RuntimeError("; ".join(problems))
        taken0 = sum(1 for row_ in keys_after if row_[1] == key0)
        return (f"시계 {clock}; 바꾸기 전 위젯 {before}; {how}로 03:00 → '{DAY_START_ASK}' '{body[:20]}…' → [바꾸기]; "
                f"위젯 {after}; 저장값 {stored}; 활성 판정 {count_before} → {count_after}, 기간키 그대로({key0} {taken0}건)")

    steps("12. 03:30에 하루 시작 04:00 → 03:00 (TASK-020, RR-15, TC-033)",
          f"바꾸기 전 위젯 '{header0}' 6 / 6, P-5 질문 뒤 위젯 '{header1}' 1 / 6, 판정 수·기간키 그대로", day_start)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()  # also counts this boot, so become_root waits for the emulator's own commands after it
    api = api_level()
    API["n"] = api
    r = Report(args.out, api, f"에뮬레이터 화면 확인 2부 API {api}")
    steps = ShotSteps(r)
    try:
        run(r, steps, args.apk, api)
    except Exception as exc:
        traceback.print_exc()
        r.check("화면 확인 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        if LOCK["on"]:
            r.note("끝에서 시험 화면 잠금을 지움" if lock_off() else "시험 화면 잠금을 지우지 못함")
        for note in sorted(set(KEYBOARD_NOTES)):
            r.note(note)
        r.write()
        (args.out / "steps.json").write_text(json.dumps(steps.spans, ensure_ascii=False, indent=1), encoding="utf-8")
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        (args.out / f"api{api}-logcat.txt").write_text("\n".join(LOG_PARTS), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
