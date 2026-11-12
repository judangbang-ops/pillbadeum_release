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

Cases (6 daily items Test1..Test6, one person, so every tap must find a different pending item):
  A grid 276x220 font 100%: four buttons in the first row; scroll the grid; tap Test6 -> recorded
  B grid 250x110 font 100%: scroll; tap Test5 -> recorded
  C list 276x220 largest font: scroll; tap Test4 -> recorded
  D list 250x110 largest font: scroll; tap Test3 -> recorded
  E grid 276x220 largest font: screenshot only (does the grid still fit?)
"recorded" = an active dose_record row for that item with source WIDGET (DB read as root), and the
button redrawn as done. The report also lists which buttons were fully visible before scrolling.

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
    sh,
    tap,
    uninstall,
    wait_boot,
)

HOST = f"{PKG}/.debug.WidgetHostActivity"
ITEM = re.compile(r"(Test\d+), ")  # Labels.widgetDescription: "나 Test6, 미완료"


# ------------------------------------------------------------------------------------------------
# Host and widget


def start_host(layout: str, w: int, h: int, seed: int = 6) -> None:
    sh(f"am force-stop {PKG}", check=False)
    sh(f"am start -n {HOST} --es layout {layout} --ei w {w} --ei h {h} --ei seed {seed}")


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
        if status is not None and status.text.startswith(("probe-ready", "probe-error")):
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


def fully_inside(item: Node, box: Node) -> bool:
    x1, y1, x2, y2 = item.bounds
    bx1, by1, bx2, by2 = box.bounds
    return x1 >= bx1 and y1 >= by1 and x2 <= bx2 and y2 <= by2 and y2 > y1


def wait_items(timeout: float = 30) -> list[Node]:
    end = time.time() + timeout
    nodes: list[Node] = []
    while time.time() < end:
        nodes = dump_ui()
        if items_on_screen(nodes) and list_view(nodes) is not None:
            return nodes
        time.sleep(1)
    return nodes


def scroll_to(name: str, tries: int = 6) -> Node | None:
    """Swipes up inside the list until [name] is fully visible."""
    for _ in range(tries):
        nodes = dump_ui()
        box = list_view(nodes)
        item = items_on_screen(nodes).get(name)
        if box is not None and item is not None and fully_inside(item, box):
            return item
        if box is None:
            return None
        x = (box.bounds[0] + box.bounds[2]) // 2
        top, bottom = box.bounds[1], box.bounds[3]
        span = max(10, (bottom - top) * 3 // 4)
        sh(f"input swipe {x} {bottom - 4} {x} {bottom - 4 - span} 400")
        time.sleep(1.5)
    return None


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
    tmp = Path(tempfile.mkdtemp())
    try:
        for suffix in ("", "-wal", "-journal"):
            remote = f"{DATA}/databases/{DB}{suffix}"
            if sh(f"ls {remote} 2>/dev/null", check=False).strip():
                adb("pull", remote, str(tmp / f"{DB}{suffix}"))
        if not (tmp / DB).exists():
            return {}
        con = sqlite3.connect(tmp / DB)
        try:
            rows = con.execute(
                "SELECT i.name, d.source FROM dose_record d JOIN intake i ON i.id = d.intake_id WHERE d.active = 1"
            ).fetchall()
        finally:
            con.close()
        return {str(name): str(source) for name, source in rows}
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


def run_case(r: Report, case: str, layout: str, w: int, h: int, font: float, target: str | None) -> None:
    tag = f"{case}-{layout}-{w}x{h}-font{int(font * 100)}"
    set_font(font)
    start_host(layout, w, h)
    status = wait_status()
    nodes = wait_items()
    r.shot(f"{tag}-before")
    save_ui(r, f"{tag}-before")
    box = list_view(nodes)
    items = items_on_screen(nodes)
    ok_ready = status.startswith("probe-ready") and box is not None and bool(items)
    if not r.check(f"{case} 위젯 그려짐 ({layout}, {w}×{h}dp, 글자 {int(font * 100)}%)",
                   "호스트 준비, 목록(GridView/ListView)과 항목이 보임",
                   f"{status}; 목록 {box.cls.rsplit('.', 1)[-1] if box else '없음'}; 보인 항목 {len(items)}", ok_ready):
        return
    visible = sorted((name for name, n in items.items() if fully_inside(n, box)), key=lambda s: int(s[4:]))
    row = first_row(items, box)
    r.note(f"{tag}: 스크롤 전 다 보인 항목 {', '.join(visible) or '없음'}; 첫 줄 {', '.join(row) or '없음'}; "
           f"목록 영역 {box.bounds}")
    if layout == "grid" and case == "A":
        r.check(f"{case} 가로 4개", "첫 줄에 Test1~Test4가 나란히", ", ".join(row) or "없음",
                row == ["Test1", "Test2", "Test3", "Test4"])
    if target is None:
        return
    item = scroll_to(target)
    r.shot(f"{tag}-scrolled")
    save_ui(r, f"{tag}-scrolled")
    if not r.check(f"{case} 스크롤로 {target}에 닿음", f"목록만 스크롤되어 {target}가 다 보임",
                   "보임" if item is not None else "닿지 못함", item is not None):
        return
    header_after = next((n for n in dump_ui() if n.text == "DidITake"), None)
    r.check(f"{case} 머리글 고정", "스크롤 뒤에도 머리글(DidITake)이 보임",
            "보임" if header_after is not None else "안 보임", header_after is not None)
    tap(item)
    source = wait_record(target)
    r.check(f"{case} 목록 안 버튼 탭 → 기록", f"{target}의 활성 판정, source = WIDGET",
            source or "판정 없음", source == "WIDGET")
    redrawn = None
    end = time.time() + 15
    while time.time() < end and redrawn is None:
        node = items_on_screen(dump_ui()).get(target)
        if node is not None and "기록됨" in node.desc:
            redrawn = node
        else:
            time.sleep(1)
    r.shot(f"{tag}-after-tap")
    r.check(f"{case} 탭 뒤 다시 그림", f"{target} 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨')",
            redrawn.desc if redrawn else "바뀌지 않음", redrawn is not None)


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
    r = Report(args.out, api, title=f"위젯 스크롤 기술 시험 API {api}")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    largest = 2.0 if api >= 34 else 1.3
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 디버그 APK {args.apk.name}"
           + (f", 커밋 {args.commit}" if args.commit else "") + f". 가장 큰 글자 = {int(largest * 100)}%")
    try:
        uninstall()
        adb("install", "-t", str(args.apk), timeout=180)
        grant = sh(f"appwidget grantbind --package {PKG}", check=False).strip()
        r.note(f"appwidget grantbind: {grant or '출력 없음'}")
        run_case(r, "A", "grid", 276, 220, 1.0, "Test6")
        run_case(r, "B", "grid", 250, 110, 1.0, "Test5")
        run_case(r, "C", "list", 276, 220, largest, "Test4")
        run_case(r, "D", "list", 250, 110, largest, "Test3")
        run_case(r, "E", "grid", 276, 220, largest, None)
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
