#!/usr/bin/env python3
"""Widget size check (BUG-005, REQ-026, TC-054): the launcher places the DidITake widget at 4x2 and lets it shrink no
further than 4x2, at the Pixel 5's own width (393dp) and at 360dp.

Why the size can differ: Launcher3, which Pixel Launcher is built on (android14-release, read 2026-11-14:
LauncherAppWidgetProviderInfo.initSpans, DeviceProfile, WidgetSizes), counts a widget's cells for every way the home
screen can stand, upright and turned, and keeps the largest count:

    cells = max(1, ceil((size + widget padding + cell spacing) / (cell + cell spacing)))

for minResizeWidth/Height (the smallest size) and minWidth/Height (the size without targetCell). From Android 12 it
takes targetCellWidth/Height (4x2) only when both lie between those smallest counts and the grid, else the counts of
minWidth/Height. A turned phone's rows are low: when two of them are lower than minResizeHeight, the smallest height
is three rows, 2 is out of range, and the widget is placed at the count of minHeight (4x3 at 360dp in the
accessibility check, BUG-005). Widget padding is 6dp less the cell spacing, a side, when the spacing is under 6dp, else
none. Older Launcher3 (Android 8-11) has no targetCell and counts minWidth/Height and minResizeWidth/Height with the
smaller cells of the two ways, the system's widget padding (8dp a side) and no spacing; this script only reads what
those launchers do. So the widget has two definitions: res/xml-v31 (Android 12 and later) and res/xml (before).

For each width (`wm density reset`, then the density that makes the screen 360dp wide):
  1. Install the debug APK again: the widget of the width before goes with the old install. Then start the launcher
     again: Pixel Launcher on Android 8 kept the removed install's APK path from the old widget and showed the new one
     as 'Problem loading widget' (API 26 at 360dp, run 43608286853: "Package name com.judangbang.pillbadeum not found"
     with the old path; not on API 30, 34 or 36).
  2. Intro > [다음] > [위젯 추가]: the launcher's add sheet shows the widget's size ("4 × 2", Launcher3 widget_dims; it
     is filled in a moment after the sheet shows). Its add button places the widget (TASK-022's request: no drag, also
     on Android 8).
  3. Read what the launcher says (adb root): its grid for the upright screen (DeviceProfile in `dumpsys activity top`,
     Android 12 and later), the widget's cells and smallest cells (its workspace item in
     `dumpsys activity top --all`, else the launcher's database), and the sizes it gave the widget, in dp
     (/data/system/users/0/appwidgets.xml: min_width and max_height are the upright width and height, max_width and
     min_height the turned ones).
  4. Resize with the launcher's frame (touch and hold the widget and let go, then drag a handle; the frame closes with
     BACK and HOME before the cells are read): the bottom handle one row down, which must give one row more (else the
     drags miss the frame and the next check means nothing), then the bottom handle up to the widget's top and the
     right handle (when it has one) to the widget's left: the smallest size the launcher allows. Pixel Launcher on
     Android 8 and 11 draws the frame but leaves it out of the screen dump (run 43608286853), so its handles are then
     taken at the middle of the widget's edges, where the captures show them.
  5. The turned screen's grid: Pixel Launcher's 'Allow home screen rotation' (Launcher3 pref_allowRotation) set in its
     preferences file, the rotation locked at 90 degrees, DeviceProfile read again, back upright. When the launcher
     does not turn, the screen is made wider than tall with `wm size` instead (noted in the report).
From the two grids the script works out, by the rule above, the cells for the values of the definition this phone
reads (they should match what the launcher did), and which minHeight = minResizeHeight values would give 4x2 placed
and 4x2 smallest at this width.

Checks for each width: the add sheet says 4 × 2; the placed widget draws the app's views (not the launcher's error
view); it is 4x2; one row more after the grow; after the shrink it is 4x2.
Writes report.md, widget-size.json, screenshots, screen dumps and the launcher's dumps to --out. Exits 1 when a check
fails. Standard library only. Not product code. Helpers are in backup_restore_check.py, release_check.py,
screens_check.py, screens2_check.py and explore.py.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

from backup_restore_check import (
    PKG,
    UI_XML,
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
from explore import exact, tap_exact
from release_check import go_home, save_ui, screen_size
from screens2_check import widget_host, widget_page, within
from screens_check import find_pin_button, our_widget_ids, physical_density

RES = Path(__file__).resolve().parents[2] / "androidApp/src/main/res"
WIDGET_XML = "diditake_widget_info.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
WANT = (4, 2)  # REQ-026: placed at 4x2 and never smaller than 4x2
APPWIDGETS = "/data/system/users/0/appwidgets.xml"  # AppWidgetServiceImpl's saved state (binary XML from Android 12)
ROTATION_PREF = "pref_allowRotation"  # Launcher3 RotationHelper.ALLOW_ROTATION_PREFERENCE_KEY
LAUNCHER_PREFS = "com.android.launcher3.prefs.xml"  # Launcher3 LauncherFiles.SHARED_PREFERENCES_KEY
MIN_WIDGET_PADDING_DP = 6  # Launcher3 DeviceProfile.MIN_WIDGET_PADDING_DP
SIZE_LABEL = re.compile(r"^\s*(\d+)\s*[×xX]\s*(\d+)\s*$")


# ------------------------------------------------------------------------------------------------
# The widget's own values


def widget_info(api: int) -> Path:
    """The widget definition this phone reads: res/xml-v31 from Android 12, whose launchers take targetCellWidth and
    targetCellHeight, else res/xml (Android 8-11)."""
    v31 = RES / "xml-v31" / WIDGET_XML
    return v31 if api >= 31 and v31.exists() else RES / "xml" / WIDGET_XML


def widget_values(path: Path) -> dict[str, float]:
    """minWidth, minHeight, minResizeWidth, minResizeHeight (dp) and targetCellWidth/Height (0 when absent) of a widget
    definition."""
    root = ET.parse(path).getroot()
    values: dict[str, float] = {}
    for name in ("minWidth", "minHeight", "minResizeWidth", "minResizeHeight", "targetCellWidth", "targetCellHeight"):
        raw = root.get(ANDROID_NS + name, "")
        values[name] = float(raw[:-2]) if raw.endswith("dp") else float(raw or 0)
    return values


# ------------------------------------------------------------------------------------------------
# What the launcher says


def launcher_package() -> str:
    out = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
             check=False)
    names = [line.strip() for line in out.splitlines() if "/" in line]
    return names[-1].split("/")[0] if names else ""


def launcher_dump(r: Report, name: str) -> str:
    """The launcher's own dump (`dumpsys activity top`, with --all for its workspace items), kept next to the report."""
    go_home()
    time.sleep(2)
    out = sh("dumpsys activity top --all", check=False, timeout=120)
    (r.out / f"api{r.api}-{name}-launcher.txt").write_text(out, encoding="utf-8")
    return out


PX_DP = re.compile(r"^\s*([^:]+):\s*(-?[\d.]+)px \((-?[\d.]+)dp\)\s*$")
GRID_KEYS = {
    "getCellSize().x": "cell_x", "getCellSize().y": "cell_y",
    "cellLayoutBorderSpacePx Horizontal": "space_x", "cellLayoutBorderSpacePx Vertical": "space_y",
    "widthPx": "width", "heightPx": "height",
    "cellLayoutPaddingPx.left": "pad_left", "cellLayoutPaddingPx.top": "pad_top",
    "hotseatBarSizePx": "hotseat",
}


def device_profile(dump: str) -> dict | None:
    """The first DeviceProfile block of a launcher dump (Launcher3 DeviceProfile.dump, Android 12 and later): sizes in
    px as the launcher keeps them, the density, rows, columns and whether the screen is turned."""
    lines = dump.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == "DeviceProfile:"), None)
    if start is None:
        return None
    grid: dict = {}
    for line in lines[start + 1:start + 120]:
        text = line.strip()
        m = PX_DP.match(line)
        if m and m.group(1).strip() in GRID_KEYS:
            grid[GRID_KEYS[m.group(1).strip()]] = float(m.group(2))
            continue
        if text.startswith("1 dp = "):
            grid["density"] = float(text.split("=")[1].split()[0])
        elif text.startswith("isLandscape:"):
            grid["landscape"] = text.split(":", 1)[1].strip() == "true"
        elif text.startswith("isGestureMode:"):
            grid["gesture"] = text.split(":", 1)[1].strip() == "true"
        elif text.startswith("inv.numRows:"):
            grid["rows"] = int(text.split(":", 1)[1])
        elif text.startswith("inv.numColumns:"):
            grid["cols"] = int(text.split(":", 1)[1])
    needed = ("density", "rows", "cols", "cell_x", "cell_y", "space_x", "space_y")
    return grid if all(k in grid for k in needed) else None


def widget_item(dump: str, wid: int | None) -> dict | None:
    """Our widget's workspace item: `span(4,2) minSpan(4,2)` (Launcher3 from Android 12) or `spanX=4 spanY=2
    minSpanX=.. minSpanY=..` (older). Matched by appWidgetId, else by our package in the line."""
    for line in dump.splitlines():
        if "appWidgetId" not in line:
            continue
        m = re.search(r"appWidgetId=(-?\d+)", line)
        if m is None or (wid is not None and int(m.group(1)) != wid) or (wid is None and PKG not in line):
            continue
        item: dict = {"line": line.strip()}
        new = re.search(r"\bspan\((\d+),\s*(\d+)\)", line)
        old = re.search(r"\bspanX=(\d+)\s+spanY=(\d+)", line)
        if new or old:
            item["span"] = tuple(int(v) for v in (new or old).groups())
        new_min = re.search(r"\bminSpan\((\d+),\s*(\d+)\)", line)
        old_min = re.search(r"\bminSpanX=(\d+)\s+minSpanY=(\d+)", line)
        if new_min or old_min:
            item["min"] = tuple(int(v) for v in (new_min or old_min).groups())
        return item
    return None


def launcher_db_span(launcher: str, wid: int | None) -> tuple[int, int] | None:
    """The widget's cells from the launcher's database (table favorites, every Launcher3 since Android 4)."""
    if not launcher or wid is None:
        return None
    folder = f"/data/data/{launcher}/databases"
    names = [n for n in sh(f"ls {folder} 2>/dev/null", check=False).split() if n.endswith(".db")]
    tmp = Path(tempfile.mkdtemp())
    try:
        for name in names:
            for suffix in ("", "-wal", "-journal"):
                if sh(f"ls {folder}/{name}{suffix} 2>/dev/null", check=False).strip():
                    adb("pull", f"{folder}/{name}{suffix}", str(tmp / f"{name}{suffix}"), check=False)
            try:
                con = sqlite3.connect(tmp / name)
                try:
                    row = con.execute("SELECT spanX, spanY FROM favorites WHERE appWidgetId = ?", (wid,)).fetchone()
                finally:
                    con.close()
            except sqlite3.DatabaseError:
                continue
            if row:
                return int(row[0]), int(row[1])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return None


def widget_options(api: int, wid: int | None, tries: int = 6) -> dict[str, int] | None:
    """The sizes the launcher gave the widget, in dp, from the system's saved widget state. The system saves it a
    moment after the launcher reports a size, so it is read a few times until our widget is there."""
    if wid is None:
        return None
    for _ in range(tries):
        text = ""
        if api >= 31:
            # Android 12 saves it as binary XML; abx2xml (on the phone from Android 12) writes it as text
            tmp = "/data/local/tmp/appwidgets.xml"
            sh(f"rm -f {tmp}; abx2xml {APPWIDGETS} {tmp}", check=False)
            text = adb("exec-out", "cat", tmp, check=False)
        if "<gs" not in text:
            text = adb("exec-out", "cat", APPWIDGETS, check=False)
        try:
            root = ET.fromstring(text)
        except ET.ParseError:
            time.sleep(2)
            continue
        ours = {int(p.get("tag", "-1"), 16) for p in root.iter("p") if p.get("pkg") == PKG}
        for g in root.iter("g"):
            if int(g.get("id", "-1"), 16) == wid and int(g.get("p", "-1"), 16) in ours:
                sizes = {k: int(g.get(k, "0"), 16) for k in ("min_width", "min_height", "max_width", "max_height")}
                if any(sizes.values()):
                    return sizes
        time.sleep(2)
    return None


def describe_options(o: dict[str, int] | None) -> str:
    if not o:
        return "읽지 못함"
    return f"세로 화면 {o['min_width']}×{o['max_height']}dp, 가로 화면 {o['max_width']}×{o['min_height']}dp"


# ------------------------------------------------------------------------------------------------
# The Launcher3 rule


def jround(value: float) -> int:
    """Java Math.round for the launcher's own numbers (half up)."""
    return math.floor(value + 0.5)


def widget_padding(grid: dict) -> tuple[int, int]:
    """Launcher3 DeviceProfile.widgetPadding, both sides together (x, y), in px."""
    least = jround(MIN_WIDGET_PADDING_DP * grid["density"])
    if grid["space_x"] < least or grid["space_y"] < least:
        return 2 * jround(max(0.0, least - grid["space_x"])), 2 * jround(max(0.0, least - grid["space_y"]))
    return 0, 0


def cells(size_dp: float, cell: float, space: float, pad: int, density: float) -> int:
    """LauncherAppWidgetProviderInfo.getSpan with the size turned into px as the system does
    (TypedValue.complexToDimensionPixelSize)."""
    size = jround(size_dp * density)
    return max(1, math.ceil((size + pad + space) / (cell + space)))


def launcher3_spans(values: dict[str, float], grids: list[dict], api: int) -> dict:
    """LauncherAppWidgetProviderInfo.initSpans (android14-release) over the given grids (upright and turned)."""
    rows, cols = grids[0]["rows"], grids[0]["cols"]
    min_x = min_y = span_x = span_y = 0
    for g in grids:
        pad_x, pad_y = widget_padding(g)
        d = g["density"]
        min_x = max(min_x, cells(values["minResizeWidth"], g["cell_x"], g["space_x"], pad_x, d))
        min_y = max(min_y, cells(values["minResizeHeight"], g["cell_y"], g["space_y"], pad_y, d))
        span_x = max(span_x, cells(values["minWidth"], g["cell_x"], g["space_x"], pad_x, d))
        span_y = max(span_y, cells(values["minHeight"], g["cell_y"], g["space_y"], pad_y, d))
    if api >= 31:
        max_x, max_y = max(cols, min_x), max(rows, min_y)
        tx, ty = int(values["targetCellWidth"]), int(values["targetCellHeight"])
        if min_x <= tx <= max_x and min_y <= ty <= max_y:
            span_x, span_y = tx, ty
    return {"span": (min(span_x, cols), min(span_y, rows)), "min": (min(span_x, min_x), min(span_y, min_y))}


def row_heights(grid: dict, n: int = 3) -> list[float]:
    """The height in dp a widget gets for 1..n rows of this grid (WidgetSizes.getWidgetSizePx)."""
    _, pad_y = widget_padding(grid)
    return [round((k * grid["cell_y"] + (k - 1) * grid["space_y"] - pad_y) / grid["density"], 1)
            for k in range(1, n + 1)]


def good_heights(values: dict[str, float], grids: list[dict], api: int) -> list[tuple[int, int]]:
    """The minHeight = minResizeHeight values (whole dp) that give 4x2 placed and 4x2 smallest, as ranges."""
    good = []
    for h in range(30, 241):
        trial = dict(values, minHeight=h, minResizeHeight=h)
        got = launcher3_spans(trial, grids, api)
        if got["span"] == WANT and got["min"] == WANT:
            good.append(h)
    ranges: list[tuple[int, int]] = []
    for h in good:
        if ranges and h == ranges[-1][1] + 1:
            ranges[-1] = (ranges[-1][0], h)
        else:
            ranges.append((h, h))
    return ranges


# ------------------------------------------------------------------------------------------------
# Steps


def place_by_intro(r: Report, tag: str) -> str:
    """Intro > [다음] > [위젯 추가] > the launcher's add sheet: reads the size it shows, then adds the widget.
    Returns the size label ('4 × 2'), or '' when the sheet has none."""
    launch_app(stop_at_intro=True)
    time.sleep(2)
    tap_exact("다음")
    if exact("위젯 추가", 10) is None:
        raise RuntimeError("no [위젯 추가] on the intro's second page")
    tap_exact("위젯 추가")
    button = find_pin_button()
    if button is None:
        save_ui(r, f"{tag}-sheet")
        r.shot(f"{tag}-sheet")
        raise RuntimeError("the launcher's add sheet did not show")
    # The sheet fills in the size a moment after its buttons show: on API 36 the first dump had an empty widget_dims
    # (run 43608286853), so it is read a few times
    label = ""
    for _ in range(8):
        nodes = dump_ui()
        dims = next((n for n in nodes if n.rid.endswith("/widget_dims") and n.text.strip()), None)
        if dims is None:
            dims = next((n for n in nodes if SIZE_LABEL.match(n.text)), None)
        if dims is not None:
            label = dims.text.strip()
            break
        time.sleep(1)
    save_ui(r, f"{tag}-sheet")
    r.shot(f"{tag}-sheet")
    tap(button)
    time.sleep(4)
    if exact("시작하기", 15) is not None:
        tap_exact("시작하기")
    go_home()
    return label


def label_cells(label: str) -> tuple[int, int] | None:
    m = SIZE_LABEL.match(label or "")
    return (int(m.group(1)), int(m.group(2))) if m else None


def drawn(nodes: list[Node], host: Node) -> bool:
    """Whether the placed widget shows the app's own views (ids of this package inside the host). The launcher's error
    view ('Problem loading widget', API 26 at 360dp in run 43608286853) has none."""
    return any(n.rid.startswith(f"{PKG}:id/") and within(n, host) for n in nodes)


def by_rid(nodes: list[Node], name: str) -> Node | None:
    return next((n for n in nodes if n.rid.endswith(f"/{name}") and n.bounds[2] > n.bounds[0]), None)


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 1500) -> None:
    sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")
    time.sleep(2)


HANDLE_INSET = 14  # px from the widget's edge to a handle's middle in the API 26 and 30 captures (run 43608286853)
SIDES = {"bottom": "아래", "right": "오른쪽"}


def frame_handles(nodes: list[Node], host: Node) -> tuple[dict[str, tuple[int, int]], bool]:
    """The middles of the resize frame's handles, and whether the frame was in the screen dump. The frame (Launcher3
    AppWidgetResizeFrame) hides the handles of a way the widget cannot be resized (its smallest cells fill the grid),
    so a side can be missing. Without the frame in the dump (Pixel Launcher on Android 8 and 11), the middles of the
    widget's edges, HANDLE_INSET inside, where the captures show the four handles."""
    if by_rid(nodes, "widget_resize_frame") is not None:
        found = {}
        for side in SIDES:
            handle = by_rid(nodes, f"widget_resize_{side}_handle")
            if handle is not None:
                found[side] = handle.center
        return found, True
    x1, y1, x2, y2 = host.bounds
    return {"bottom": ((x1 + x2) // 2, y2 - HANDLE_INSET), "right": (x2 - HANDLE_INSET, (y1 + y2) // 2)}, False


def resize(r: Report, tag: str, side: str, target) -> str:
    """Touch and hold our widget and let go: the launcher's resize frame. Drag its `side` handle to
    target(host, handle middle) and let go: the launcher gives the widget the cells the handle ended on, never fewer
    than its smallest cells. The frame is closed with BACK and HOME (Android 8-11 Launcher3 closes it on HOME).
    Returns what was done, for the report."""
    host = widget_host(widget_page(lambda ns: widget_host(ns) is not None, 30))
    if host is None:
        raise RuntimeError("our widget is not on the home screen before the resize")
    x, y = host.center
    swipe(x, y, x, y, 2000)
    nodes = dump_ui()
    save_ui(r, f"{tag}-frame")
    r.shot(f"{tag}-frame")
    handles, in_dump = frame_handles(nodes, host)
    if side in handles:
        hx, hy = handles[side]
        tx, ty = target(host, (hx, hy))
        swipe(hx, hy, tx, ty)
        r.shot(f"{tag}-done")
        done = f"{SIDES[side]} 손잡이를 끎" + ("" if in_dump else "(틀이 화면 덤프에 없어 위젯 가장자리에서 잡음)")
    else:
        done = f"{SIDES[side]} 손잡이 없음"
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)
    go_home()
    return done


def read_span(r: Report, name: str, launcher: str, wid: int | None) -> tuple[tuple[int, int] | None, dict, str]:
    """The widget's cells, its workspace item and the launcher dump they came from."""
    dump = launcher_dump(r, name)
    item = widget_item(dump, wid) or {}
    return item.get("span") or launcher_db_span(launcher, wid), item, dump


def cells_text(span: tuple[int, int] | None) -> str:
    return f"{span[0]}×{span[1]}" if span else "읽지 못함"


def allow_rotation(launcher: str) -> str:
    """Turns on Launcher3's 'Allow home screen rotation' in the launcher's preferences file (adb root). Pixel Launcher
    keeps the home screen upright on a phone otherwise (RotationHelper asks for SCREEN_ORIENTATION_NOSENSOR, which the
    rotation lock does not turn). The launcher is stopped after the file is written: it keeps its preferences in memory
    and reads the file only when it starts again."""
    written = []
    entry = f'<boolean name="{ROTATION_PREF}" value="true" />'
    for base in (f"/data/data/{launcher}", f"/data/user_de/0/{launcher}"):
        folder = f"{base}/shared_prefs"
        if not sh(f"ls -d {folder} 2>/dev/null", check=False).strip():
            continue
        path = f"{folder}/{LAUNCHER_PREFS}"
        exists = bool(sh(f"ls {path} 2>/dev/null", check=False).strip())
        old = sh(f"cat {path}", check=False) if exists else ""
        old = re.sub(rf'\s*<boolean name="{ROTATION_PREF}" value="\w+"\s*/>', "", old)
        if "</map>" in old:
            body = old.replace("</map>", f"    {entry}\n</map>")
        else:
            body = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    {entry}\n</map>\n"
        local = Path(tempfile.mkdtemp()) / LAUNCHER_PREFS
        local.write_text(body, encoding="utf-8")
        adb("push", str(local), "/data/local/tmp/launcher-prefs.xml")
        shutil.rmtree(local.parent, ignore_errors=True)
        owner = sh(f"stat -c %u:%g {folder}", check=False).strip()
        # `cat >` into the existing file keeps its owner, mode and SELinux label; a new file gets them set
        sh(f"cat /data/local/tmp/launcher-prefs.xml > {path}", check=False)
        if not exists and owner:
            sh(f"chown {owner} {path}; chmod 660 {path}", check=False)
        written.append(path)
    sh(f"am force-stop {launcher}", check=False)
    time.sleep(3)
    return ", ".join(written) or "설정 파일 폴더 없음"


def turned_grid(r: Report, tag: str, launcher: str) -> tuple[dict | None, str]:
    """The launcher's grid for the turned screen: the home screen turned with the rotation locked at 90 degrees, else
    the screen made wider than tall (`wm size`), which leaves the system bars where the upright screen has them."""
    written = allow_rotation(launcher)
    sh("settings put system accelerometer_rotation 0", check=False)
    sh("settings put system user_rotation 1", check=False)
    time.sleep(4)
    grid = device_profile(launcher_dump(r, f"{tag}-turned"))
    r.shot(f"{tag}-turned")
    sh("settings put system user_rotation 0", check=False)
    time.sleep(4)
    go_home()
    if grid is not None and grid.get("landscape"):
        return grid, f"홈 화면을 돌려 읽음(런처 설정 {written})"
    w, h = screen_size()
    sh(f"wm size {h}x{w}", check=False)
    time.sleep(5)
    grid = device_profile(launcher_dump(r, f"{tag}-wide"))
    r.shot(f"{tag}-wide")
    sh("wm size reset", check=False)
    time.sleep(5)
    go_home()
    if grid is not None and grid.get("landscape"):
        return grid, "런처가 돌지 않아 화면 크기를 가로가 길게 바꿔 읽음(wm size, 시스템 막대 위치는 세로 화면 그대로)"
    return None, "가로 화면의 칸을 읽지 못함"


def grid_text(g: dict | None) -> str:
    if not g:
        return "읽지 못함"
    d = g["density"]
    pad_x, pad_y = widget_padding(g)
    rows = ", ".join(f"{k + 1}줄 {v}" for k, v in enumerate(row_heights(g)))
    return (f"{g['cols']}×{g['rows']} 칸, 칸 {g['cell_x'] / d:.1f}×{g['cell_y'] / d:.1f}dp, "
            f"칸 사이 {g['space_x'] / d:.1f}×{g['space_y'] / d:.1f}dp, 위젯 여백 {pad_x / d:.1f}×{pad_y / d:.1f}dp, "
            f"위젯 높이 {rows}dp")


def measure(r: Report, apk: Path, api: int, width_dp: int | None, values: dict[str, float], launcher: str) -> dict:
    tag = "own" if width_dp is None else f"w{width_dp}"
    name = "기본 폭" if width_dp is None else f"폭 {width_dp}dp"
    w_px, _ = screen_size()
    if width_dp is None:
        sh("wm density reset")
        dpi = physical_density()
    else:
        dpi = w_px * 160 // width_dp
        sh(f"wm density {dpi}")
    density = dpi / 160
    time.sleep(5)
    go_home()
    res: dict = {"width": tag, "dpi": dpi, "width_dp": round(w_px / density, 1)}
    r.note(f"{name}: wm density {dpi}, 폭 {res['width_dp']}dp")

    # 1-2. fresh install, the launcher started again (docstring, step 1), the add sheet's size, the widget added
    uninstall()
    time.sleep(2)
    install(apk)
    if launcher:
        sh(f"am force-stop {launcher}", check=False)
        time.sleep(3)
        go_home()
    label = place_by_intro(r, tag)
    res["label"] = label
    r.check(f"{name}: 추가 창의 위젯 크기", "4 × 2", label or "읽지 못함", label_cells(label) == WANT)

    # 3. what the launcher made of it: the app's views come a moment after the host
    nodes = widget_page(lambda ns: (h := widget_host(ns)) is not None and drawn(ns, h), 30)
    host = widget_host(nodes)
    r.shot(f"{tag}-placed")
    save_ui(r, f"{tag}-placed")
    if host is None:
        raise RuntimeError("our widget is not on the home screen")
    shown = drawn(nodes, host)
    r.check(f"{name}: 놓인 위젯이 앱 화면을 그림", "앱의 뷰", "앱의 뷰" if shown else "앱의 뷰 없음(런처의 오류 화면 등)",
            shown)
    ids = our_widget_ids()
    wid = max(ids) if ids else None
    span, item, up_dump = read_span(r, f"{tag}-placed", launcher, wid)
    grid_up = device_profile(up_dump)
    host_dp = ((host.bounds[2] - host.bounds[0]) / density, (host.bounds[3] - host.bounds[1]) / density)
    options = widget_options(api, wid)
    res.update(widget_id=wid, item=item.get("line", ""), span=span, min_span=item.get("min"), options=options,
               host_dp=[round(v, 1) for v in host_dp], grid_upright=grid_up)
    r.check(f"{name}: 놓인 위젯의 칸", "4×2", cells_text(span), span == WANT)
    r.note(f"{name}: 놓인 위젯 {host.bounds} = {host_dp[0]:.0f}×{host_dp[1]:.0f}dp, 런처가 위젯에 준 크기 "
           f"{describe_options(options)}, 가장 작은 칸 {item.get('min', '모름')}")
    if api < 31 and options and span:
        # Launcher3 before Android 12 gives the widget span x cell of each way the screen stands, less the system's
        # widget padding (8dp a side), and counts cells with the smaller cell of the two ways (no spacing)
        cell = min((options["min_width"] + 16) / span[0], (options["max_width"] + 16) / span[0])
        row_h = min((options["max_height"] + 16) / span[1], (options["min_height"] + 16) / span[1])
        ranges = {"width": (round(3 * cell - 16, 1), round(4 * cell - 16, 1)),
                  "height": (round(row_h - 16, 1), round(2 * row_h - 16, 1))}
        res.update(old_cell=round(cell, 1), old_row=round(row_h, 1), old_ranges=ranges)
        r.note(f"{name}: 위젯이 받은 크기로 센 옛 런처의 칸(작은 쪽, 시스템 위젯 여백 8dp씩): 칸 너비 {cell:.1f}dp, "
               f"줄 높이 {row_h:.1f}dp. minWidth·minResizeWidth가 {ranges['width'][0]:.1f}dp 넘고 "
               f"{ranges['width'][1]:.1f}dp 이하, minHeight·minResizeHeight가 {ranges['height'][0]:.1f}dp 넘고 "
               f"{ranges['height'][1]:.1f}dp 이하면 4×2로 놓이고 4×2보다 작아지지 않음")

    # 4. resize with the frame. Each drag finds the widget again (the dumps press HOME, which shows the first page, and
    # the launcher may have put the widget on the second, screens2_check.widget_page). One row down first: a row is
    # the grid's cell and spacing (Android 12 and later), else the widget's height over its rows.
    if span is None:
        raise RuntimeError("the placed widget's cells could not be read")
    row = (grid_up["cell_y"] + grid_up["space_y"]) if grid_up else (host.bounds[3] - host.bounds[1]) / span[1]
    how_grow = resize(r, f"{tag}-grow", "bottom", lambda h, p: (p[0], p[1] + round(row)))
    grown, _, _ = read_span(r, f"{tag}-grown", launcher, wid)
    want = (span[0], span[1] + 1)
    r.check(f"{name}: 크기 조절 틀로 한 줄 늘린 칸", cells_text(want), f"{cells_text(grown)} ({how_grow})",
            grown == want)
    how_v = resize(r, f"{tag}-shrink-v", "bottom", lambda h, p: (p[0], h.bounds[1] + 10))
    how_h = resize(r, f"{tag}-shrink-h", "right", lambda h, p: (h.bounds[0] + 10, p[1]))
    how = f"{how_v}, {how_h}"
    nodes = widget_page(lambda ns: widget_host(ns) is not None, 30)
    host_after = widget_host(nodes)
    r.shot(f"{tag}-shrunk")
    save_ui(r, f"{tag}-shrunk")
    span_after, item_after, _ = read_span(r, f"{tag}-shrunk", launcher, wid)
    options_after = widget_options(api, wid)
    res.update(grow=how_grow, grown=grown, shrink=how, span_after=span_after, options_after=options_after,
               host_after_dp=[round((host_after.bounds[2] - host_after.bounds[0]) / density, 1),
                              round((host_after.bounds[3] - host_after.bounds[1]) / density, 1)] if host_after else None)
    r.check(f"{name}: 가장 작게 줄인 뒤의 칸", "4×2 (4×1·3×2로 줄지 않음)", f"{cells_text(span_after)} ({how})",
            span_after == WANT)
    r.note(f"{name}: 줄인 뒤 런처가 위젯에 준 크기 {describe_options(options_after)}")

    # 5. the turned screen's grid, and the rule worked out over both
    grid_turned, turned_how = turned_grid(r, tag, launcher) if grid_up is not None else (None, "세로 화면의 칸도 없음")
    res.update(grid_turned=grid_turned, turned_how=turned_how)
    r.note(f"{name}: 런처의 세로 화면 {grid_text(grid_up)}")
    r.note(f"{name}: 런처의 가로 화면 {grid_text(grid_turned)} ({turned_how})")
    if grid_up is not None and grid_turned is not None:
        model = launcher3_spans(values, [grid_up, grid_turned], api)
        heights = good_heights(values, [grid_up, grid_turned], api)
        res.update(model=model, good_heights=heights)
        ranges = ", ".join(f"{a}~{b}dp" if a != b else f"{a}dp" for a, b in heights) or "없음"
        r.note(f"{name}: Launcher3 규칙으로 계산한 칸: 놓일 때 {model['span']}, 가장 작게 {model['min']} "
               f"(런처가 한 것: {span}, 가장 작게 {item.get('min', '모름')}). minHeight = minResizeHeight를 "
               f"{ranges}로 두면 4×2로 놓이고 4×2보다 작아지지 않음")
    return res


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--widths", default="own,360", help="'own' (the phone's density) and widths in dp")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    r = Report(args.out, api, f"위젯 크기 확인 API {api}")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
    sh("settings put global heads_up_notifications_enabled 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    info = widget_info(api)
    values = widget_values(info)
    launcher = launcher_package()
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 런처 {launcher or '모름'}, "
           f"화면 {screen_size()[0]}×{screen_size()[1]}px, 기본 밀도 {physical_density()}")
    r.note(f"위젯 값({info.relative_to(RES.parent.parent.parent.parent)}): "
           + ", ".join(f"{k} {v:g}" for k, v in values.items()))

    results = []
    try:
        for width in args.widths.split(","):
            width_dp = None if width.strip() == "own" else int(width)
            try:
                results.append(measure(r, args.apk, api, width_dp, values, launcher))
            except Exception as exc:  # report it and go on with the next width
                traceback.print_exc()
                r.check(f"{'기본 폭' if width_dp is None else f'폭 {width_dp}dp'}: 단계", "끝까지 돎",
                        f"{type(exc).__name__}: {exc}", False)
                try:
                    r.shot(f"error-{width.strip()}")
                    (args.out / f"api{api}-error-{width.strip()}.xml").write_text(
                        adb("exec-out", "cat", UI_XML, check=False), encoding="utf-8")
                except Exception:
                    pass
                sh("settings put system user_rotation 0", check=False)
                sh("wm size reset", check=False)
    finally:
        sh("wm density reset", check=False)
        spans = [set(h for a, b in res["good_heights"] for h in range(a, b + 1))
                 for res in results if "good_heights" in res]
        if len(spans) > 1:
            both = sorted(set.intersection(*spans))
            r.note("모든 폭에서 4×2로 놓이고 4×2보다 작아지지 않는 minHeight = minResizeHeight: "
                   + (f"{both[0]}~{both[-1]}dp" if both and both[-1] - both[0] + 1 == len(both)
                      else (", ".join(map(str, both)) + "dp" if both else "없음")))
        old = [res["old_ranges"] for res in results if "old_ranges" in res]
        if len(old) > 1:
            w = (max(o["width"][0] for o in old), min(o["width"][1] for o in old))
            h = (max(o["height"][0] for o in old), min(o["height"][1] for o in old))
            r.note(f"모든 폭에서 옛 런처가 4×2로 놓고 4×2보다 줄이지 않는 값: minWidth·minResizeWidth {w[0]:.1f}dp 초과 "
                   f"{w[1]:.1f}dp 이하, minHeight·minResizeHeight {h[0]:.1f}dp 초과 {h[1]:.1f}dp 이하")
        (args.out / "widget-size.json").write_text(
            json.dumps({"api": api, "launcher": launcher, "definition": str(info.relative_to(RES)), "values": values,
                        "widths": results}, ensure_ascii=False, indent=2, default=list), encoding="utf-8")
        r.write()
        try:
            (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60),
                                                           encoding="utf-8")
        except Exception as exc:  # the report is already written; a missing logcat must not hide it
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
