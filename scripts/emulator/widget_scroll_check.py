#!/usr/bin/env python3
"""Widget scroll technical test (TASK-042, REQ-078, screens.md 5장 1번, design review 4.2).

Question: inside a Glance lazy list, does a tap on a button record a dose, and do four buttons fit
side by side at 4x2? The answer decides how TASK-014 handles a widget with more items than fit.

Uses the debug APK only. Its debug-only WidgetHostActivity hosts the debug-only ScrollProbeWidget at an
exact size; the probe draws the product WidgetModel as a `LazyVerticalGrid(GridCells.Fixed(4))` or a
`LazyColumn` with a fixed header, and taps go through the product RecordFromWidget. No launcher is
driven, so the size and the font are exact: 276x220dp (4x2 in portrait in the Android size table)
and 250x110dp (the declared minimum), at font 100% and at the largest font the Settings app offers
(Android 14+: 200%, older: 130%).

Cases (12 daily items Test1..Test12, one person; every case taps a different item, so a record
found after the tap is the tap's own):
  A grid 276x220 font 100%: four buttons in the first row; scroll the grid; tap Test10 -> recorded
  B grid 250x110 font 100%: scroll; tap Test7 -> recorded
  C list 276x220 largest font: scroll; tap Test5 -> recorded
  D list 250x110 largest font: scroll; tap Test4 -> recorded
  E grid 276x220 largest font: screenshot only (does the grid still fit?)
  R rows 276x220 font 100%: a LazyColumn with four buttons a row (the grid's look on a list);
    scroll; tap Test12 -> recorded; then tap it again (C too): the 'already recorded' line appears
    above the list - does the list keep its place?
  P grid 276x220 font 100%: turn a debug component on (PACKAGE_CHANGED for the package, what
    WorkManager's first work does) and watch whether the widget drops to its loading layout and when
    it comes back (an observation: the release check's widget stuck on loading, 2026-11-12)
  L (API 31+) the probe placed on the Pixel Launcher home screen: scroll; tap Test11 -> recorded;
    then P again on the launcher's widget (LP)
  F, FK (API 31+, three times each, after a fresh install every time) the first widget placed on the
    launcher, watched for 60s. The first Glance session makes WorkManager turn its RescheduleReceiver
    on (PACKAGE_CHANGED), and the release check once kept that first widget on its loading layout
    (BUG-004). FK enqueues a 10-year work before placing (debug KeepWork), so the switch happens
    before the widget exists. The probe logs each update and options broadcast it gets and each
    composition; the launcher logs when a reset widget gets views again. Observations, not checks.
"recorded" = an active dose_record row for that item with source WIDGET (DB read as root), and the
button redrawn as done. The report also lists which buttons were fully visible before scrolling and
where the list is after the tap redraw (an observation for TASK-014).

Writes report.md, screenshots, UI dumps and logcat to --out. Exits 1 when a check fails.
Standard library only. Not product code. Screen and file helpers are in backup_restore_check.py.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sqlite3
import sys
import tempfile
import time
from collections.abc import Callable
from pathlib import Path

from backup_restore_check import (
    DATA,
    DB,
    PKG,
    Node,
    Report,
    adb,
    api_level,
    become_root,
    dump_ui,
    find,
    sh,
    tap,
    uninstall,
    wait_boot,
)
from release_check import drag, go_home, on_screen, screen_size

HOST = f"{PKG}/.debug.WidgetHostActivity"
DENSITY = {"d": 0.0}
API = {"n": 0}
ITEM = re.compile(r"(Test\d+), ")  # Labels.widgetDescription: "나 Test6, 미완료"


# ------------------------------------------------------------------------------------------------
# Host and widget


def start_host(layout: str, w: int, h: int, seed: int = 12, bind: bool = True, keep_work: bool = False) -> None:
    sh(f"am force-stop {PKG}", check=False)
    extra = ("" if bind else " --ez bind false") + (" --ez keepwork true" if keep_work else "")
    sh(f"am start -n {HOST} --es layout {layout} --ei w {w} --ei h {h} --ei seed {seed}{extra}")


def wait_status(timeout: float = 40) -> str:
    """The host's status line: probe-ready, probe-bind-dialog or probe-error. Taps the system bind
    dialog when the grant did not take."""
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump_ui()
        status = next((n for n in nodes if n.desc == "probe-status" or n.text.startswith("probe-")), None)
        allow = next((n for n in nodes if n.text.lower() in ("always allow", "항상 허용")), None)
        create = next((n for n in nodes if n.text.upper() in ("CREATE", "만들기")), None)
        if create is not None:
            if allow is not None:
                tap(allow)
            tap(create)
            continue
        if status is not None and status.text.startswith(("probe-ready", "probe-seeded", "probe-error")):
            return status.text
        time.sleep(1)
    return "no status"


def items_on_screen(nodes: list[Node]) -> dict[str, Node]:
    found: dict[str, Node] = {}
    for n in nodes:
        m = ITEM.search(n.desc)
        if m and m.group(1) not in found:
            found[m.group(1)] = n
    return found


def list_view(nodes: list[Node]) -> Node | None:
    """Glance draws LazyVerticalGrid as a GridView and LazyColumn as a ListView."""
    return next((n for n in nodes if n.cls.endswith(("GridView", "ListView"))), None)


# uiautomator reports bounds clipped to what is on screen, so an item cut off by the list edge still
# looks "inside" the list. An item counts as fully visible when its height is the full item height:
# the tallest item seen so far with the same layout and font. Cases run large size first (A before B,
# C before D), so the 250x110 cases are measured against items that were not cut off.
FULL_HEIGHT: dict[str, int] = {}
CURRENT = {"key": ""}


def note_heights(items: dict[str, Node]) -> None:
    key = CURRENT["key"]
    for n in items.values():
        FULL_HEIGHT[key] = max(FULL_HEIGHT.get(key, 0), n.bounds[3] - n.bounds[1])


def fully_inside(item: Node, box: Node, need: float = 1.0) -> bool:
    """True when at least [need] of the item's full height shows inside the list (1.0 = all of it)."""
    x1, y1, x2, y2 = item.bounds
    bx1, by1, bx2, by2 = box.bounds
    inside = x1 >= bx1 and y1 >= by1 and x2 <= bx2 and y2 <= by2 and y2 > y1
    full = FULL_HEIGHT.get(CURRENT["key"], 0)
    # A recorded button is a few px lower or higher than an open one (check mark, "15:32" instead of
    # "기록 없음"; API 34 at 200%: 304 vs 310px), so allow 4% or 8px.
    return inside and (y2 - y1) >= full * need - max(8, full * 0.04)


def needed_fraction(box: Node) -> float:
    """1.0 when one item fits in the list. When the list is lower than one item (a layout finding
    for TASK-014, not a scrolling failure), most of what can show: the list height less a margin."""
    full = FULL_HEIGHT.get(CURRENT["key"], 0)
    height = box.bounds[3] - box.bounds[1]
    if full <= 0 or height >= full:
        return 1.0
    return max(0.2, (height - 8) / full)


def wait_items(timeout: float = 30) -> list[Node]:
    end = time.time() + timeout
    nodes: list[Node] = []
    while time.time() < end:
        nodes = dump_ui()
        if items_on_screen(nodes) and list_view(nodes) is not None:
            return nodes
        time.sleep(1)
    return nodes


def scroll_to(name: str, need: float = 1.0, tries: int = 14) -> Node | None:
    """Drags inside the list until [need] of [name] is visible. Items are numbered top to bottom, so
    the direction comes from the numbers on screen. Each try is printed as a [debug] line."""
    d = DENSITY["d"] or 2.75
    # The list starts moving only after the touch slop (8dp), so every drag adds it.
    slop = round(8 * d)
    full = FULL_HEIGHT.get(CURRENT["key"], 0)
    for attempt in range(tries + 1):
        nodes = dump_ui()
        box = list_view(nodes)
        items = items_on_screen(nodes)
        item = items.get(name)
        if box is None:
            return None
        if item is not None and fully_inside(item, box, need):
            return item
        if attempt == tries:
            break  # the last drag is checked above, then give up
        x = (box.bounds[0] + box.bounds[2]) // 2
        top, bottom = box.bounds[1], box.bounds[3]
        if item is not None and item.bounds[3] > item.bounds[1] and full:
            # Partly visible: move its middle to the middle of the list. The earlier rule (align the
            # cut edge) moved a cut-off-at-the-top row to the bottom edge and back again, 14 tries
            # without a stop (list at 200%, two rows of 205px in 409px, API 34 and 36, 2026-11-12).
            true_top = item.bounds[3] - full if item.bounds[1] <= top + 2 else item.bounds[1]
            delta = top + ((bottom - top) - full) // 2 - true_top  # > 0: the content moves down
            down = delta < 0
            span = max(abs(delta), 6) + slop
            how = f"{name} {item.bounds[1]}-{item.bounds[3]} (온전하면 {true_top}-{true_top + full})"
        else:
            # A step is 40% of the list or 70dp, whichever is more: in a 39dp list 40% minus the slop
            # moved about 8dp a drag (API 34 at 200%, 2026-11-12).
            span = max((bottom - top) * 2 // 5, round(70 * d)) + slop
            shown = [int(k[4:]) for k in items]
            down = not shown or int(name[4:]) > min(shown)
            how = f"{name} 안 보임, 보이는 항목 {', '.join(sorted(items, key=lambda k: int(k[4:]))) or '없음'}"
        print(f"[debug] scroll try {attempt + 1}: {how}; 목록 {top}-{bottom}; "
              f"{'아래로' if down else '위로'} {span}px", flush=True)
        # The finger may leave the list; the list keeps the gesture.
        y1, y2 = (bottom - 6, bottom - 6 - span) if down else (top + 6, top + 6 + span)
        drag_list(x, y1, y2)
        time.sleep(1.5)
    return None


def scroll_offset(items: dict[str, Node], box: Node, layout: str) -> int | None:
    """How far the list is scrolled from the top, in px, counted in rows of the full item height (a
    grid row holds 4 items). A few px off per row: done rows differ a little in height."""
    full = FULL_HEIGHT.get(CURRENT["key"], 0)
    if not items or not full:
        return None
    per_row = 1 if layout == "list" else 4
    top, bottom = box.bounds[1], box.bounds[3]
    # An item whose top shows gives its true top; one cut off only at the top gives it from its
    # bottom. One cut off at both edges (taller than the list) gives neither.
    estimates = []
    for name, n in items.items():
        if n.bounds[1] > top + 2:
            true_top = n.bounds[1]
        elif n.bounds[3] < bottom - 2:
            true_top = n.bounds[3] - full
        else:
            continue
        estimates.append((int(name[4:]) - 1) // per_row * full - (true_top - top))
    return min(estimates) if estimates else None


def drag_list(x: int, y1: int, y2: int) -> None:
    """Moves a finger from y1 to y2 and lifts it without a fling, so the list moves by the distance
    less the touch slop. API 29+: `input motionevent` - past the slop at once (a slow start was a long
    press for the Pixel Launcher, which then picked the widget up, 2026-11-12), then still for 0.4s
    before lifting. Older images have no motionevent: a swipe slow enough not to fling (25dp/s; a 234px
    swipe in 0.8s overshot a 205px row)."""
    d = DENSITY["d"] or 2.75
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
        duration = max(800, int(abs(y2 - y1) / (25 * d) * 1000))
        sh(f"input swipe {x} {y1} {x} {y2} {duration}")


def first_row(items: dict[str, Node], box: Node) -> list[str]:
    """Names fully visible in the top row of the list, left to right."""
    inside = [(n.bounds[1], n.bounds[0], name) for name, n in items.items() if fully_inside(n, box)]
    if not inside:
        return []
    top = min(y for y, _, _ in inside)
    return [name for y, _, name in sorted(inside, key=lambda t: t[1]) if abs(y - top) <= 12]


# ------------------------------------------------------------------------------------------------
# Database


def widget_records() -> dict[str, str]:
    """Active judgments by item name -> source, read from the app database as root."""
    rows = query_db("SELECT i.name, d.source FROM dose_record d JOIN intake i ON i.id = d.intake_id WHERE d.active = 1")
    return {str(name): str(source) for name, source in rows}


def active_count(name: str) -> int:
    rows = query_db("SELECT COUNT(*) FROM dose_record d JOIN intake i ON i.id = d.intake_id "
                    "WHERE d.active = 1 AND i.name = ?", (name,))
    return int(rows[0][0]) if rows else 0


def query_db(sql: str, params: tuple = ()) -> list[tuple]:
    """Runs a query on a copy of the app database pulled as root ([] when there is no database yet)."""
    tmp = Path(tempfile.mkdtemp())
    try:
        for suffix in ("", "-wal", "-journal"):
            remote = f"{DATA}/databases/{DB}{suffix}"
            if sh(f"ls {remote} 2>/dev/null", check=False).strip():
                adb("pull", remote, str(tmp / f"{DB}{suffix}"))
        if not (tmp / DB).exists():
            return []
        con = sqlite3.connect(tmp / DB)
        try:
            return con.execute(sql, params).fetchall()
        finally:
            con.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def wait_record(name: str, timeout: float = 20) -> str | None:
    end = time.time() + timeout
    while time.time() < end:
        source = widget_records().get(name)
        if source is not None:
            return source
        time.sleep(1.5)
    return None


# ------------------------------------------------------------------------------------------------
# Cases


def set_font(scale: float) -> None:
    sh(f"settings put system font_scale {scale}")
    time.sleep(1)


def save_ui(r: Report, name: str) -> None:
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
    (r.out / f"api{r.api}-{name}.xml").write_text(xml, encoding="utf-8")


def run_case(r: Report, case: str, layout: str, w: int, h: int, font: float, target: str | None,
             again: bool = False) -> None:
    tag = f"{case}-{layout}-{w}x{h}-font{int(font * 100)}"
    CURRENT["key"] = f"{layout}-{font}"
    set_font(font)
    start_host(layout, w, h)
    status = wait_status()
    nodes = wait_items(45)
    if list_view(nodes) is None or not items_on_screen(nodes):
        # First start on a slow emulator (API 34, 2026-11-12): the widget was not drawn in time once.
        r.note(f"{tag}: 위젯이 45초 안에 그려지지 않아 호스트를 한 번 다시 띄움")
        r.shot(f"{tag}-not-drawn")
        start_host(layout, w, h)
        status = wait_status()
        nodes = wait_items(45)
    r.shot(f"{tag}-before")
    save_ui(r, f"{tag}-before")
    box = list_view(nodes)
    items = items_on_screen(nodes)
    note_heights(items)
    ok_ready = status.startswith("probe-ready") and box is not None and bool(items)
    if not r.check(f"{case} 위젯 그려짐 ({layout}, {w}×{h}dp, 글자 {int(font * 100)}%)",
                   "호스트 준비, 목록(GridView/ListView)과 항목이 보임",
                   f"{status}; 목록 {box.cls.rsplit('.', 1)[-1] if box else '없음'}; 보인 항목 {len(items)}", ok_ready):
        return
    def restart() -> list[Node]:
        start_host(layout, w, h)
        wait_status()
        return wait_items(45)

    exercise(r, case, layout, tag, target, nodes, host=True, restart=restart, again=again)


def header_above(box: Node) -> Node | None:
    """The widget's own header (DidITake) just above its list, not an app label elsewhere on screen."""
    x1, y1, x2, _ = box.bounds
    return next((n for n in dump_ui() if n.text == "DidITake" and n.bounds[3] <= y1 + 4
                 and n.bounds[1] >= y1 - 400 and n.bounds[0] < x2 and n.bounds[2] > x1), None)


def host_events() -> list[str]:
    """WidgetHostActivity logs onStart/onStop (it starts and stops listening there). The log is not
    cleared, so the full logcat stays in the artifacts; callers compare counts before and after."""
    out = adb("logcat", "-d", "-s", "WidgetHostActivity:I", check=False, timeout=30)
    return re.findall(r"host (onStop|onStart)", out)


def exercise(r: Report, case: str, layout: str, tag: str, target: str | None, nodes: list[Node], host: bool,
             restart: Callable[[], list[Node]] | None = None, again: bool = False) -> None:
    """Scroll to [target], tap it, check the record and the redraw. Shared by the debug host and the
    launcher case. [restart] shows the widget again in the debug host."""
    box = list_view(nodes)
    items = items_on_screen(nodes)
    visible = sorted((name for name, n in items.items() if fully_inside(n, box)), key=lambda s: int(s[4:]))
    row = first_row(items, box)
    r.note(f"{tag}: 스크롤 전 다 보인 항목 {', '.join(visible) or '없음'}; 첫 줄 {', '.join(row) or '없음'}; "
           f"목록 영역 {box.bounds}, 높이 {box.bounds[3] - box.bounds[1]}px; 항목 한 칸의 온전한 높이 "
           f"{FULL_HEIGHT.get(CURRENT['key'], 0)}px (px = dp × {DENSITY['d']})")
    if layout in ("grid", "rows") and case in ("A", "R"):
        r.check(f"{case} 가로 4개", "첫 줄에 Test1~Test4가 나란히", ", ".join(row) or "없음",
                row == ["Test1", "Test2", "Test3", "Test4"])
    if target is None:
        return
    need = needed_fraction(box)
    if need < 1.0:
        d = DENSITY["d"] or 1
        r.note(f"{tag}: 배치 관찰 — 목록 높이 {round((box.bounds[3] - box.bounds[1]) / d)}dp가 항목 한 칸 "
               f"{round(FULL_HEIGHT.get(CURRENT['key'], 0) / d)}dp보다 낮아 한 칸이 다 보일 수 없음. "
               f"항목 높이의 {int(need * 100)}% 이상 보이면 닿은 것으로 보고, 보이는 부분을 누름")
    want = "다 보임" if need >= 1.0 else f"{int(need * 100)}% 이상 보임"
    item = scroll_to(target, need)
    if item is None and restart is not None and not has_widget_list(dump_ui()):
        # Drawn, then back on its loading layout before the first drag (API 34 and 36, 2026-11-12,
        # see case P): an observation; show it again once and scroll then.
        r.shot(f"{tag}-lost")
        r.note(f"{tag}: 스크롤 전에 위젯이 로딩 화면으로 돌아감(관찰) — 호스트를 한 번 다시 띄움")
        if has_widget_list(restart()):
            item = scroll_to(target, need)
    r.shot(f"{tag}-scrolled")
    save_ui(r, f"{tag}-scrolled")
    if not r.check(f"{case} 스크롤로 {target}에 닿음", f"목록만 스크롤되어 {target}가 {want}",
                   "보임" if item is not None else "닿지 못함", item is not None):
        return
    tapped_nodes = dump_ui()
    box = list_view(tapped_nodes) or box
    header_after = header_above(box)
    r.check(f"{case} 머리글 고정", "스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임",
            "보임" if header_after is not None else "안 보임", header_after is not None)
    before_y = item.bounds[1]
    before_offset = scroll_offset(items_on_screen(tapped_nodes), box, layout)
    if "기록됨" in item.desc:
        r.note(f"{tag}: 주의 — {target}가 탭 전에 이미 기록됨 ({item.desc})")
    events_before = len(host_events()) if host else 0
    tap(item)
    tapped_at = time.time()
    source = wait_record(target)
    r.check(f"{case} 목록 안 버튼 탭 → 기록", f"{target}의 활성 판정, source = WIDGET",
            source or "판정 없음", source == "WIDGET")
    time.sleep(3)
    # Observation for TASK-014 (not pass/fail): does the list keep its scroll position when the
    # widget redraws after the tap, or jump back to the top? Wait for the redraw first (the button's
    # sentence says 기록됨): at 3s it had not come yet in some runs and the jump came later.
    def redrawn_or_gone(nodes: list[Node]) -> bool:
        shown_item = items_on_screen(nodes).get(target)
        return shown_item is None or "기록됨" in shown_item.desc

    after_nodes = dump_ui()
    end = time.time() + 10
    while not redrawn_or_gone(after_nodes) and time.time() < end:
        time.sleep(1)
        after_nodes = dump_ui()
    time.sleep(1)
    after_nodes = dump_ui()
    r.shot(f"{tag}-after-tap")
    save_ui(r, f"{tag}-after-tap")
    after = items_on_screen(after_nodes)
    after_box = list_view(after_nodes) or box
    after_offset = scroll_offset(after, after_box, layout)
    node = after.get(target)
    shown = ", ".join(sorted(after, key=lambda s: int(s[4:]))) or "없음"
    if before_offset is not None and after_offset is not None:
        where = f"스크롤 {before_offset}px → {after_offset}px"
        if before_offset > 16 and after_offset <= 16:
            kept = f"맨 위로 돌아감 ({where}; {target} {'보임' if node else '화면 밖'}, 보이는 항목 {shown})"
        elif abs(after_offset - before_offset) <= 16:
            kept = f"그대로 ({where})"
        else:
            kept = f"바뀜 ({where}; 보이는 항목 {shown})"
    else:
        # An item taller than the list (250x110) shows neither edge: compare what shows instead.
        before_names = set(items_on_screen(tapped_nodes))
        same_y = node is not None and abs(node.bounds[1] - before_y) <= 4
        if set(after) == before_names and same_y:
            kept = f"그대로 (보이는 항목 {shown}, {target} y {before_y} → {node.bounds[1]})"
        elif "Test1" in after and "Test1" not in before_names:
            kept = f"맨 위로 돌아감 ({target} {'보임' if node else '화면 밖'}, 보이는 항목 {shown})"
        else:
            kept = f"바뀜 (보이는 항목 {', '.join(sorted(before_names, key=lambda k: int(k[4:])))} → {shown})"
    r.note(f"{tag}: 탭 뒤 다시 그릴 때 스크롤 위치 {kept}")
    if host:
        # Glance runs the tap through a trampoline activity in its own task. If the host activity
        # stops (and stops listening) meanwhile, a reset can come from the debug host, not the list.
        during = host_events()[events_before:]
        r.note(f"{tag}: 탭 동안 시험 호스트의 onStop/onStart: {' → '.join(during) or '없음'}")
    # The redraw check reads the button's TalkBack sentence. It looks where the button is now and
    # scrolls only when it is off screen; half of it showing is enough to read it.
    on_screen = target in after
    node = after.get(target) or scroll_to(target, min(need, 0.5))
    redrawn = node if node is not None and "기록됨" in node.desc else None
    end = time.time() + 15
    while node is not None and redrawn is None and time.time() < end:
        time.sleep(1)
        again = items_on_screen(dump_ui()).get(target) or scroll_to(target, min(need, 0.5))
        node = again or node
        redrawn = again if again is not None and "기록됨" in again.desc else None
    if redrawn is not None and on_screen:
        r.note(f"{tag}: 탭 뒤 {time.time() - tapped_at:.0f}초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)")
    elif redrawn is not None:
        r.note(f"{tag}: {target}가 화면 밖이라 다시 스크롤해 다시 그린 문장을 읽음")
    r.check(f"{case} 탭 뒤 다시 그림", f"{target} 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄)",
            redrawn.desc if redrawn else (node.desc if node else "찾지 못함"), redrawn is not None)
    if again and redrawn is not None:
        tap_again(r, tag, layout, target, need)


def tap_again(r: Report, tag: str, layout: str, target: str, need: float) -> None:
    """Observation: a second tap on a recorded button shows the 'already recorded' line above the
    list (RecordFromWidget). Does that line, which changes the widget's layout, move the list?"""
    item = items_on_screen(dump_ui()).get(target) or scroll_to(target, need)
    if item is None:
        r.note(f"{tag}: 다시 누르기 — {target}를 찾지 못해 하지 않음")
        return
    nodes = dump_ui()
    box = list_view(nodes)
    before = scroll_offset(items_on_screen(nodes), box, layout) if box else None
    tap(item)
    message = None
    end = time.time() + 15
    while time.time() < end and message is None:
        time.sleep(1)
        nodes = dump_ui()
        message = next((n for n in nodes if "이미 기록됨" in n.text), None)
    time.sleep(1)
    nodes = dump_ui()
    r.shot(f"{tag}-tap-again")
    save_ui(r, f"{tag}-tap-again")
    box = list_view(nodes)
    after = scroll_offset(items_on_screen(nodes), box, layout) if box else None
    shown = ", ".join(sorted(items_on_screen(nodes), key=lambda k: int(k[4:]))) or "없음"
    line = f"안내 줄 보임 ('{message.text}')" if message else "15초 안에 안내 줄 안 보임"
    r.note(f"{tag}: 기록된 {target}를 다시 누름 → {line}; 스크롤 {before}px → {after}px, 보이는 항목 {shown}, "
           f"{target}의 활성 판정 {active_count(target)}건(1건이어야 함)")


PROBE_LABEL = "Scroll probe (debug)"  # android:label of ScrollProbeWidgetReceiver


def widget_reload_counts() -> tuple[int, int]:
    """(PACKAGE_CHANGED for our package, launcher widget views re-created) so far in the logcat.
    The first is logged by the phone app's CarrierSvcBindHelper on these images; both only count."""
    out = adb("logcat", "-d", check=False, timeout=90)
    changed = len(re.findall(rf"onPackageModified: {re.escape(PKG)}\s*$", out, re.M))
    created = len(re.findall(r"LauncherAppWidgetHostView: App widget created with id: \d+", out))
    return changed, created


def reschedule_receiver_state() -> str:
    """WorkManager's RescheduleReceiver in `dumpsys package`: listed under enabled or disabled
    components (set at runtime), or in neither (the manifest default)."""
    out = sh(f"dumpsys package {PKG}", check=False)
    section = ""
    for line in out.splitlines():
        stripped = line.strip()
        if stripped.startswith("enabledComponents:"):
            section = "켜짐"
        elif stripped.startswith("disabledComponents:"):
            section = "꺼짐"
        elif stripped.endswith(":") and not stripped.startswith("androidx"):
            section = ""
        if "RescheduleReceiver" in stripped and section:
            return section
    return "매니페스트 기본값"


def place_probe_in_launcher(r: Report, tag: str) -> str:
    """Home > touch and hold > Widgets > search DidITake > drag the probe's preview (the one above the
    'Scroll probe (debug)' name) to the middle of the home screen. Returns '' or why it did not work."""
    w, h = screen_size()
    go_home()
    sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
    if find("Widgets", 10) is None:
        return "홈 길게 누르기 메뉴에 Widgets 없음"
    tap(find("Widgets", 2))
    time.sleep(1)
    search = next((n for n in dump_ui() if n.cls.endswith("EditText")), None)
    if search is not None:
        tap(search)
        sh("input text DidITake")
        time.sleep(2)
    for _ in range(8):
        nodes = dump_ui()
        header = next((n for n in nodes if n.text == "DidITake" and n.rid.endswith("/app_title")), None)
        name = next((n for n in nodes if n.text == PROBE_LABEL), None)
        if name is None and header is not None and not any(n.rid.endswith("/widget_preview_container") for n in nodes):
            tap(header)  # the app row is folded: open it
            time.sleep(1)
            continue
        if name is not None and on_screen(name, h):
            previews = [n for n in nodes if n.rid.endswith("/widget_preview_container")
                        and n.bounds[3] <= name.bounds[1] + 4 and n.bounds[0] < name.bounds[2] and n.bounds[2] > name.bounds[0]]
            preview = max(previews, key=lambda n: n.bounds[3], default=None)
            if preview is not None and on_screen(preview, h):
                save_ui(r, f"{tag}-picker")
                r.shot(f"{tag}-picker")
                x, y = preview.center
                drag(x, y, w // 2, h // 2, r, tag)
                go_home()
                return ""
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 2} 1000")
        time.sleep(1)
    save_ui(r, f"{tag}-picker-not-found")
    return f"위젯 목록에서 '{PROBE_LABEL}' 미리보기를 찾지 못함"


TIMELINE = [
    (re.compile(rf"onPackageModified: {re.escape(PKG)}\s*$"), "PACKAGE_CHANGED(우리 패키지)"),
    (re.compile(r"Bound widget (\d+) to provider .*ScrollProbeWidgetReceiver"), "위젯 {0} 묶임"),
    # Launcher3 logs "created" in setAppWidget (a new view, or a reset after a provider change) and
    # "loaded" on the first non-null RemoteViews after that.
    (re.compile(r"LauncherAppWidgetHostView: App widget created with id: (\d+)"), "런처가 위젯 {0} 뷰를 만듦(초기화)"),
    (re.compile(r"LauncherAppWidgetHostView: App widget with id: (\d+) loaded"), "런처의 위젯 {0}이 화면을 받음"),
    (re.compile(r"ScrollProbeWidget: provideGlance"), "Glance 세션 시작"),
    (re.compile(r"ScrollProbeWidget: onUpdate (.*)$"), "앱이 APPWIDGET_UPDATE 받음({0})"),
    (re.compile(r"ScrollProbeWidget: onAppWidgetOptionsChanged (\d+)"), "앱이 크기 변경 받음({0})"),
    (re.compile(r"ScrollProbeWidget: composed"), "Glance가 구성함"),
    (re.compile(r"WidgetHostActivity: keep-work enqueued"), "KeepWork 넣음"),
    (re.compile(r"Closing session (appWidget-\d+)"), "Glance 세션 {0} 닫힘"),
    (re.compile(r"ComponentToggleReceiver: toggled ToggleTarget to (\w+)"), "시험 구성요소 {0}"),
    (re.compile(r"SessionWorker attempted restart but Session is not available for (appWidget-\d+)"),
     "Glance 세션 {0} 다시 시작 못 함"),
]


def device_time() -> str:
    return sh("date +'%m-%d %H:%M:%S.000'", check=False).strip()


def launcher_timeline(since: str) -> list[str]:
    """What happened to our widgets since [since] (device time), from the logcat, in order."""
    out = adb("logcat", "-d", "-T", since, check=False, timeout=90) if since else ""
    events = []
    for line in out.splitlines():
        for pattern, label in TIMELINE:
            m = pattern.search(line)
            if m:
                events.append(f"{line[6:18]} {label.format(*m.groups())}")
                break
    return events


def has_widget_list(nodes: list[Node]) -> bool:
    return list_view(nodes) is not None and bool(items_on_screen(nodes))


def run_reset_case(r: Report, case: str) -> None:
    """PACKAGE_CHANGED for our package while a probe is drawn in the debug host (see the module doc).
    An observation for TASK-014 and the release check, not a pass/fail of the scroll test."""
    tag = f"{case}-grid-276x220-font100"
    CURRENT["key"] = "grid-1.0"
    set_font(1.0)
    start_host("grid", 276, 220)
    wait_status()
    nodes = wait_items(45)
    if not has_widget_list(nodes):
        r.note(f"{tag}: 위젯이 45초 안에 그려지지 않아 하지 못함")
        return
    time.sleep(3)
    watch_after_toggle(r, tag)


def watch_after_toggle(r: Report, tag: str) -> None:
    """Turns the debug component on or off and watches the widget on screen for 75s at most."""
    since = device_time()
    started = time.time()
    sh(f"am broadcast -n {PKG}/.debug.ComponentToggleReceiver", check=False)
    lost_at = back_at = None
    while time.time() - started < 75:
        shown = has_widget_list(dump_ui())
        if not shown and lost_at is None:
            lost_at = time.time() - started
            r.shot(f"{tag}-loading")
        elif shown and lost_at is not None:
            back_at = time.time() - started
            break
        elif shown and time.time() - started > 20:
            break  # no drop in 20s
        time.sleep(1)
    events = launcher_timeline(since)
    if lost_at is None:
        seen = "20초 동안 위젯이 그대로 보임"
    elif back_at is None:
        seen = f"{lost_at:.0f}초 뒤 위젯이 로딩 화면으로 바뀌고, 75초 안에 돌아오지 않음"
    else:
        seen = f"{lost_at:.0f}초 뒤 위젯이 로딩 화면으로 바뀌고, {back_at:.0f}초 뒤 항목이 다시 보임"
    r.note(f"{tag}: 시험 구성요소를 켜서 우리 패키지에 PACKAGE_CHANGED를 보냄 → {seen}")
    r.note(f"{tag}: 로그 순서 — {'; '.join(events[:14]) or '찾은 줄 없음'}")
    r.shot(f"{tag}-after")


def run_launcher_case(r: Report, case: str, target: str) -> None:
    """The same flow on a real home screen (Pixel Launcher, API 31+), at the size the launcher gives a
    4x2 widget. The host only sets the probe layout and binds nothing, so no other probe session runs
    while the launcher places its widget. The timeline from the logcat shows what happened to it."""
    tag = f"{case}-grid-launcher-font100"
    CURRENT["key"] = "grid-1.0"
    set_font(1.0)
    start_host("grid", 250, 110, bind=False)  # seeds 12 items and sets the probe layout to grid
    status = wait_status()
    if not status.startswith("probe-seeded"):
        r.note(f"{tag}: 런처 경우를 하지 못함 — 호스트가 항목만 만들지 못함 ({status})")
        return
    since = device_time()
    changed0, created0 = widget_reload_counts()
    r.note(f"{tag}: 놓기 전 WorkManager RescheduleReceiver {reschedule_receiver_state()}")
    placed_at = time.time()
    why = place_probe_in_launcher(r, tag)
    if "Widgets" in why:
        # The launcher's touch-and-hold menu was not ready once (right after boot): one more try.
        r.note(f"{tag}: {why} — 20초 뒤 한 번 더")
        time.sleep(20)
        why = place_probe_in_launcher(r, tag)
    if why:
        # Launcher steps differ between images: an observation, not a failure of the scroll test.
        r.note(f"{tag}: 런처 경우를 하지 못함 — {why}")
        return
    dropped_at = time.time()
    nodes = wait_items(120)
    shown_at = time.time()
    r.shot(f"{tag}-before")
    save_ui(r, f"{tag}-before")
    box = list_view(nodes)
    items = items_on_screen(nodes)
    changed1, created1 = widget_reload_counts()
    reloads = (f"우리 패키지 PACKAGE_CHANGED {changed1 - changed0}번, 런처가 위젯 뷰를 만든 횟수 "
               f"{created1 - created0}번(놓을 때 1번 포함), 지금 RescheduleReceiver {reschedule_receiver_state()}")
    events = launcher_timeline(since)
    r.note(f"{tag}: 로그 순서 — {'; '.join(events[:14]) or '찾은 줄 없음'}")
    if box is None or not items:
        r.note(f"{tag}: 런처에 놓은 시험 위젯이 놓고 120초 안에 그려지지 않음 (Glance 로딩 화면, 관찰). {reloads}")
        return
    r.note(f"{tag}: 놓고 {shown_at - dropped_at:.0f}초 뒤 항목이 보임(위젯 목록 화면 조작까지 "
           f"{shown_at - placed_at:.0f}초). {reloads}")
    note_heights(items)
    d = DENSITY["d"] or 1
    r.note(f"{tag}: 런처가 준 목록 영역 {round((box.bounds[2] - box.bounds[0]) / d)}×"
           f"{round((box.bounds[3] - box.bounds[1]) / d)}dp")
    exercise(r, case, "grid", tag, target, nodes, host=False)
    # Case P on the launcher's widget: a launcher answers a provider change itself (it may build the
    # widget's view again), unlike the debug host.
    go_home()
    time.sleep(2)
    if has_widget_list(dump_ui()):
        watch_after_toggle(r, f"{case}P-grid-launcher-font100")
    else:
        r.note(f"{case}P-grid-launcher-font100: 홈 화면에서 위젯 목록이 안 보여 하지 않음")


def run_first_place_trials(r: Report, apk: Path, trials: int = 3) -> None:
    """Cases F and FK (see the module doc), alternating, each after a fresh install."""
    results: dict[str, list[str]] = {"F": [], "FK": []}
    for i in range(1, trials + 1):
        for case in ("F", "FK"):
            results[case].append(first_place(r, apk, f"{case}{i}", keep_work=case == "FK"))
    for case, outcomes in results.items():
        r.note(f"{case} 요약: " + ", ".join(outcomes))


def first_place(r: Report, apk: Path, tag: str, keep_work: bool) -> str:
    """One trial: install afresh, seed (and KeepWork for FK), place the probe, watch it for 60s."""
    uninstall()
    adb("install", "-t", str(apk), timeout=180)
    set_font(1.0)
    since = device_time()
    start_host("grid", 250, 110, bind=False, keep_work=keep_work)
    status = wait_status()
    if not status.startswith("probe-seeded"):
        r.note(f"{tag}: 하지 못함 — 호스트가 항목만 만들지 못함 ({status})")
        return "못 함"
    time.sleep(5)  # FK: the PACKAGE_CHANGED of KeepWork comes about 1s after it
    before = reschedule_receiver_state()
    why = place_probe_in_launcher(r, tag)
    if why:
        r.note(f"{tag}: {why} — 15초 뒤 한 번 더")
        time.sleep(15)
        why = place_probe_in_launcher(r, tag)
    if why:
        r.note(f"{tag}: 하지 못함 — {why}")
        return "못 함"
    dropped = time.time()
    first_shown = lost = back = None
    while time.time() - dropped < 60:
        shown = has_widget_list(dump_ui())
        t = time.time() - dropped
        if shown and first_shown is None:
            first_shown = t
        elif not shown and first_shown is not None and lost is None:
            lost = t
            r.shot(f"{tag}-loading")
        elif shown and lost is not None and back is None:
            back = t
        time.sleep(1)
    shown_now = has_widget_list(dump_ui())
    r.shot(f"{tag}-60s")
    if first_shown is None:
        seen = "60초 동안 한 번도 항목이 보이지 않음(로딩 화면)"
        outcome = "멈춤"
    elif lost is not None and back is None:
        seen = f"{first_shown:.0f}초에 항목이 보였다가 {lost:.0f}초에 로딩 화면으로 바뀌고 60초까지 돌아오지 않음"
        outcome = "멈춤"
    elif lost is not None:
        seen = f"{first_shown:.0f}초에 보임, {lost:.0f}초에 로딩 화면, {back:.0f}초에 다시 보임"
        outcome = f"돌아옴({back - lost:.0f}초)"
    else:
        seen = f"{first_shown:.0f}초에 보이고 60초까지 그대로"
        outcome = "정상"
    if not shown_now and outcome != "멈춤":
        seen += ", 60초에는 안 보임"
        outcome = "멈춤"
    events = launcher_timeline(since)
    r.note(f"{tag}: 놓기 전 RescheduleReceiver {before} → 놓은 뒤 {seen}. 지금 RescheduleReceiver "
           f"{reschedule_receiver_state()}")
    r.note(f"{tag}: 로그 순서 — {'; '.join(events[:30]) or '찾은 줄 없음'}")
    return outcome


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path, help="debug APK")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--commit", default="")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    API["n"] = api
    r = Report(args.out, api, title=f"위젯 스크롤 기술 시험 API {api}")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    largest = 2.0 if api >= 34 else 1.3
    m = re.search(r"(\d+)\s*$", sh("wm density").strip())
    DENSITY["d"] = int(m.group(1)) / 160 if m else 0.0
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 디버그 APK {args.apk.name}"
           + (f", 커밋 {args.commit}" if args.commit else "") + f". 가장 큰 글자 = {int(largest * 100)}%")
    try:
        uninstall()
        adb("install", "-t", str(args.apk), timeout=180)
        grant = sh(f"appwidget grantbind --package {PKG}", check=False).strip()
        r.note(f"appwidget grantbind: {grant or '출력 없음'}")
        # 12 items: at 276x220 two grid rows (8 items) show, so Test10 needs a real scroll and the
        # scroll position after the tap redraw means something (2026-11-12 run: 6 items all fit).
        # Every case taps another item (C tapped B's Test7 once and found B's record).
        run_case(r, "A", "grid", 276, 220, 1.0, "Test10")
        run_case(r, "B", "grid", 250, 110, 1.0, "Test7")
        run_case(r, "C", "list", 276, 220, largest, "Test5", again=True)
        run_case(r, "D", "list", 250, 110, largest, "Test4")
        run_case(r, "E", "grid", 276, 220, largest, None)
        # The grid's look on a LazyColumn (rows of four): does it keep its place after a tap like
        # the list, unlike the grid (runs 6 and 7, 2026-11-12)?
        run_case(r, "R", "rows", 276, 220, 1.0, "Test12", again=True)
        run_reset_case(r, "P")
        # The launcher last: right after boot the Pixel Launcher's menu was not ready (API 34 and 36,
        # 2026-11-12), and its widget stays on the home screen.
        if api >= 31:
            run_launcher_case(r, "L", "Test11")
            # BUG-004 (release check, 2026-11-12): the first widget after a fresh install stayed on
            # its loading layout once. Each trial reinstalls, so this goes last.
            run_first_place_trials(r, args.apk)
        else:
            r.note("L 런처 경우: API 31 미만 이미지의 런처는 위젯 목록 화면이 달라 하지 않음")
    except Exception as exc:
        r.check("스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            save_ui(r, "error")
        except Exception:
            pass
    finally:
        sh("settings put system font_scale 1.0", check=False)
        r.write()
        try:
            (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60), encoding="utf-8")
        except Exception as exc:
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
