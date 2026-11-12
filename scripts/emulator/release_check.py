#!/usr/bin/env python3
"""Release-variant check before personal APKs go out (TASK-051, REQ-055, REQ-062).

Takes two release APKs of one commit signed with one test key, the way personal APKs are signed
(scripts/sign-personal-apk.ps1): --apk has version N and --next-apk has N+1. On one booted emulator
(API 31+, an image that allows `adb root`):

  1. Install N. It is the release variant: not debuggable, `run-as` refused, version N.
  2. Add an item in the app, place the home-screen widget, tap the item on the widget:
     one record with source WIDGET.
  3. RR-26: back up with the local test transport (encryption on), uninstall, install N again:
     the widget-tapped record comes back. First start shows the restore notice; [확인했어요].
  4. Add a second item, place the widget, install N+1 over N: version N+1, the record stays,
     same device_id and marker, no restore notice.
  5. Tap the second item on the widget the launcher kept: the new build records it.

The widget is placed the way a person does it: touch and hold the app icon > Widgets > drag the
preview onto the home screen, or, when the icon has no Widgets entry, the home screen's full widget
picker. If neither can be driven, or the placed widget does not show the item, that is a failed
check and the flow records in the app instead, so the backup and update checks still run.

Writes report.md, screenshots and logs to --out. Exits 1 when a check fails.
Standard library only. Not product code. Screen and file helpers are in backup_restore_check.py.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

from backup_restore_check import (
    ITEM_A,
    ITEM_B,
    NOTICE,
    PKG,
    Node,
    Report,
    adb,
    add_item,
    api_level,
    backup_now,
    become_root,
    describe,
    dump_ui,
    ensure_transport,
    find,
    install,
    launch_app,
    read_state,
    screen_has,
    set_transport_params,
    setup_transport,
    sh,
    short,
    tap,
    tap_text,
    uninstall,
    wait_boot,
    wipe_backup,
)

WIDGET_LABEL = "DidITake"  # app_name / widget_label in androidApp/src/main/res/values/strings.xml


# ------------------------------------------------------------------------------------------------
# Package


def package_info() -> dict[str, str]:
    out = sh(f"dumpsys package {PKG}")
    version = re.search(r"versionCode=(\d+)", out)
    flags = re.search(r"\bflags=\[([^\]]*)\]", out)
    run_as = sh(f"run-as {PKG} id", check=False).strip()
    return {
        "versionCode": version.group(1) if version else "?",
        "flags": flags.group(1).strip() if flags else "?",
        "run-as": run_as.splitlines()[-1] if run_as else "",
    }


# ------------------------------------------------------------------------------------------------
# Home screen and widget


def screen_size() -> tuple[int, int]:
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    w, h = m[-1]
    return int(w), int(h)


def go_home() -> None:
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def drag(x1: int, y1: int, x2: int, y2: int, r: Report | None = None, tag: str = "") -> None:
    """Touch and hold, then move and drop: what a person does to place a widget.
    With a report, takes a screenshot while still holding, to show whether the drag started."""
    sh(f"input motionevent DOWN {x1} {y1}")
    time.sleep(1.5)
    steps = 12
    for i in range(1, steps + 1):
        x = x1 + (x2 - x1) * i // steps
        y = y1 + (y2 - y1) * i // steps
        sh(f"input motionevent MOVE {x} {y}")
        time.sleep(0.05)
    time.sleep(1)
    if r is not None:
        r.shot(f"{tag}-dragging")
    sh(f"input motionevent UP {x2} {y2}")
    time.sleep(2)


def save_ui(r: Report, name: str) -> None:
    """Keeps the last screen dump next to the screenshots, for when the launcher steps go wrong."""
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
    (r.out / f"api{r.api}-{name}.xml").write_text(xml, encoding="utf-8")


def on_screen(node: Node, h: int) -> bool:
    """Fully visible and clear of the gesture bar at the bottom."""
    x1, y1, x2, y2 = node.bounds
    return x2 > x1 and y2 > y1 and y1 >= 0 and y2 <= h - 120


def widget_preview(h: int, below: int = 0) -> Node | None:
    """The DidITake widget's preview in an open widget sheet (Launcher3 ids), or its size label ("4 × 2").
    In the full picker only nodes under the DidITake row (`below`) count."""
    nodes = [n for n in dump_ui() if n.bounds[1] >= below]
    for name in ("widget_preview_container", "widget_preview", "widget_cell_content"):
        hit = next((n for n in nodes if n.rid.endswith(f"/{name}") and on_screen(n, h)), None)
        if hit is not None:
            return hit
    # The size label sits in the same cell; the label itself takes no touches, so the cell gets the hold.
    return next((n for n in nodes if re.fullmatch(r"\d+\s*[×x]\s*\d+", n.text) and on_screen(n, h)), None)


def open_app_widget_sheet(r: Report, tag: str) -> bool:
    """Home > touch and hold the app icon > Widgets: a sheet with this app's widgets only."""
    go_home()
    icon = next((n for n in dump_ui() if WIDGET_LABEL in (n.text, n.desc) and not n.cls.endswith("EditText")), None)
    if icon is None:
        save_ui(r, f"{tag}-home")
        r.note(f"위젯 놓기 ({tag}): 홈 화면에 앱 아이콘이 없음")
        return False
    x, y = icon.center
    sh(f"input swipe {x} {y} {x} {y} 1200")
    time.sleep(1)
    nodes = dump_ui()
    entry = next((n for n in nodes if "Widgets" in (n.text, n.desc)), None)
    if entry is None:
        save_ui(r, f"{tag}-icon-menu")
        r.shot(f"{tag}-icon-menu")
        r.note(f"위젯 놓기 ({tag}): 아이콘({icon.cls} {icon.bounds}) 메뉴에 Widgets 없음")
        go_home()
        return False
    tap(entry)
    time.sleep(2)
    return True


def open_full_widget_picker(r: Report, tag: str, w: int, h: int) -> int:
    """Home > touch and hold empty space > Widgets > search DidITake, so its preview shows near the top."""
    go_home()
    sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
    tap_text("Widgets", 10)
    search = find("Search", 5)
    if search is not None:
        tap(search)
        sh(f"input text {WIDGET_LABEL}")
        time.sleep(2)
    header = None
    for _ in range(8):
        header = next((n for n in dump_ui() if n.text == WIDGET_LABEL and not n.cls.endswith("EditText")), None)
        if header is not None:
            break
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
        time.sleep(1)
    if header is None:
        save_ui(r, f"{tag}-picker-no-app")
        raise RuntimeError(f"widget picker has no '{WIDGET_LABEL}'")
    if widget_preview(h, header.bounds[3]) is None:
        tap(header)
        time.sleep(1)
    header = next((n for n in dump_ui() if n.text == WIDGET_LABEL and not n.cls.endswith("EditText")), header)
    if widget_preview(h, header.bounds[3]) is None:
        # The previews open under the row, past the bottom edge when the row is low (first run, 2026-11-12).
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 2} 1000")
        time.sleep(1)
        header = next((n for n in dump_ui() if n.text == WIDGET_LABEL and not n.cls.endswith("EditText")), None)
    return header.bounds[3] if header is not None else 0


def place_widget(r: Report, tag: str) -> None:
    """Drags the DidITake widget preview onto the middle of the home screen."""
    w, h = screen_size()
    below = 0
    if not open_app_widget_sheet(r, tag):
        r.note(f"위젯 놓기 ({tag}): 전체 위젯 목록에서 찾음")
        below = open_full_widget_picker(r, tag, w, h)
    preview = widget_preview(h, below)
    save_ui(r, f"{tag}-picker")
    r.shot(f"{tag}-picker")
    if preview is None:
        raise RuntimeError("widget preview not on screen")
    r.note(f"위젯 놓기 ({tag}): 끈 곳 {preview.rid or preview.text} {preview.bounds}")
    x, y = preview.center
    drag(x, y, w // 2, h // 2, r, tag)
    go_home()
    r.shot(f"{tag}-placed")


def try_place_widget(r: Report, tag: str) -> bool:
    """Places the widget; a failure is one failed check, and the flow goes on with in-app records
    so the backup and update checks still run."""
    try:
        place_widget(r, tag)
        return True
    except Exception as exc:  # the launcher UI differs between images
        r.check(f"위젯 놓기 ({tag})", f"홈 화면에 {WIDGET_LABEL} 위젯", f"{type(exc).__name__}: {exc}", False)
        r.shot(f"{tag}-widget-failed")
        save_ui(r, f"{tag}-widget-failed")
        go_home()
        return False


def record_in_app(name: str) -> None:
    """App home > that row's [먹었어] (fallback when the widget could not be placed)."""
    launch_app()
    row = find(name, 20)
    buttons = [n for n in dump_ui() if n.text == "먹었어"]
    if row is None or not buttons:
        raise RuntimeError(f"no [먹었어] for {name}")
    tap(min(buttons, key=lambda b: abs(b.center[1] - row.center[1])))
    time.sleep(2)


def record(r: Report, name: str, widget: bool) -> tuple[bool, str]:
    """Records `name` from the widget when it is placed and shows the item, else in the app.
    Returns (the widget showed the item and was tapped, the source the new record should have)."""
    if widget:
        shown = tap_widget_item(name)
        r.shot(f"widget-after-tap-{name}")
        if shown:
            return True, "WIDGET"
        r.note(f"홈 화면 위젯에 {name}이 보이지 않아 앱에서 기록함")
    record_in_app(name)
    return False, "APP"


def tap_widget_item(name: str) -> bool:
    """Taps the item on the home-screen widget. True when the launcher shows it."""
    go_home()
    node = find(name, 15)
    if node is None:
        return False
    tap(node)
    time.sleep(4)
    return True


# ------------------------------------------------------------------------------------------------
# Flow


def run_flow(apk: Path, next_apk: Path, r: Report) -> None:
    # 1. release variant, version N
    uninstall()
    install(apk)
    info = package_info()
    version_n = int(info["versionCode"]) if info["versionCode"].isdigit() else -1
    r.check("릴리즈 변형 N 설치", "debuggable 아님, run-as 거부, 백업 허용",
            f"versionCode {info['versionCode']}, flags [{info['flags']}], run-as: {info['run-as']}",
            "DEBUGGABLE" not in info["flags"] and "ALLOW_BACKUP" in info["flags"]
            and "not debuggable" in info["run-as"])

    # 2. widget tap records
    launch_app()
    add_item(ITEM_A)
    widget = try_place_widget(r, "n")
    shown, source = record(r, ITEM_A, widget)
    tapped = read_state()
    r.check("위젯 탭 기록 (릴리즈 N)", "활성 판정 1, source WIDGET",
            f"위젯에 {ITEM_A} {'보임' if shown else '없음'}; 활성 판정 {tapped.active_records}, source {tapped.sources}",
            shown and tapped.active_records == 1 and tapped.sources == ["WIDGET"])
    old_id = tapped.settings.get("device_id")

    # 3. RR-26: back up -> uninstall -> install -> the record comes back
    wipe_backup()
    set_transport_params("is_encrypted=true")
    out = backup_now()
    r.note(f"bmgr backupnow (is_encrypted=true): {out.strip().splitlines()[-1] if out.strip() else ''}")
    uninstall()
    time.sleep(2)
    install(apk)
    restored = read_state()
    r.check("RR-26 백업 → 지우고 다시 설치 (릴리즈 N)", f"기록 1건(source {source})이 돌아옴",
            describe(restored) + f", source {restored.sources}",
            restored.active_records == 1 and restored.sources == [source])
    launch_app()
    notice = screen_has(NOTICE, 10)
    r.shot("restored-notice")
    after = read_state()
    r.check("복원 뒤 첫 실행 (릴리즈 N)", "복원 안내, restored_from = 옛 device_id",
            f"안내 {'보임' if notice else '없음'}; {describe(after)}",
            notice and after.settings.get("restored_from_device_id") == old_id)
    tap_text("확인했어요")
    time.sleep(2)
    set_transport_params("")

    # 4. N+1 over N
    add_item(ITEM_B)
    # Uninstalling in step 3 took the first widget off the home screen: place one again.
    widget = try_place_widget(r, "n-again")
    before_update = read_state()
    adb("install", "-r", str(next_apk), timeout=180)
    info2 = package_info()
    version_next = int(info2["versionCode"]) if info2["versionCode"].isdigit() else -1
    updated = read_state()
    r.check("다음 빌드 N+1을 덮어 설치", "버전 번호가 커짐, 기록·device_id·표식 그대로",
            f"versionCode {version_n} → {version_next}; {describe(updated)}",
            version_next > version_n > 0
            and updated.active_records == before_update.active_records == 1
            and updated.settings.get("device_id") == before_update.settings.get("device_id")
            and updated.marker == before_update.marker)

    # 5. the kept widget still records with the new build
    shown, _ = record(r, ITEM_B, widget)
    final = read_state()
    this_phone = updated.settings.get("device_id")
    r.check("덮어 설치 뒤 위젯 탭 (N+1)", "기록 2건, 둘 다 source WIDGET, 새 기록의 device_id = 이 폰 값, 옛 기록은 옛 값",
            f"위젯에 {ITEM_B} {'보임' if shown else '없음'}; 활성 판정 {final.active_records}, "
            f"source {final.sources}, device_id {[short(d) for d in final.device_ids]}, 이 폰 {short(this_phone)}",
            shown and final.active_records == 2 and final.sources == [source, "WIDGET"]
            and final.device_ids[-1:] == [this_phone] and final.device_ids[:1] == [old_id])
    launch_app()
    r.check("덮어 설치 뒤 앱", "복원 안내 없음", f"안내 {'보임' if screen_has(NOTICE, 3) else '없음'}",
            not screen_has(NOTICE, 0))
    r.shot("app-after-update")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path, help="release APK, version N")
    parser.add_argument("--next-apk", required=True, type=Path, help="same key, version N+1")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    r = Report(args.out, api, title=f"릴리즈 변형 확인 API {api}")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    setup_transport()
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, APK {args.apk.name} → {args.next_apk.name}")
    try:
        ensure_transport(r)
        run_flow(args.apk, args.next_apk, r)
    except Exception as exc:
        r.check("스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            (args.out / f"api{api}-ui.xml").write_text(adb("exec-out", "cat", "/sdcard/ui.xml", check=False), encoding="utf-8")
        except Exception:
            pass
    finally:
        (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60), encoding="utf-8")
        r.write()
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
