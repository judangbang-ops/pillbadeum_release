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

If the launcher's widget picker cannot be driven, that is one failed check and the flow records in
the app instead, so the backup and update checks still run.

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


def drag(x1: int, y1: int, x2: int, y2: int) -> None:
    """Touch and hold, then move and drop: what a person does to place a widget."""
    sh(f"input motionevent DOWN {x1} {y1}")
    time.sleep(1.5)
    steps = 12
    for i in range(1, steps + 1):
        x = x1 + (x2 - x1) * i // steps
        y = y1 + (y2 - y1) * i // steps
        sh(f"input motionevent MOVE {x} {y}")
        time.sleep(0.05)
    time.sleep(1)
    sh(f"input motionevent UP {x2} {y2}")
    time.sleep(2)


def place_widget(r: Report, tag: str) -> None:
    """Home > touch and hold > Widgets > DidITake > drag the preview onto the home screen."""
    go_home()
    w, h = screen_size()
    sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
    tap_text("Widgets", 10)
    header = None
    for _ in range(8):
        header = next((n for n in dump_ui() if n.text == WIDGET_LABEL), None)
        if header is not None:
            break
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
        time.sleep(1)
    if header is None:
        raise RuntimeError(f"widget picker has no '{WIDGET_LABEL}'")
    tap(header)
    time.sleep(1)
    r.shot(f"{tag}-picker")
    # The expanded app shows the preview with its size ("4 × 2"); drag from just above that label.
    size = next((n for n in dump_ui() if re.fullmatch(r"\d+\s*[×x]\s*\d+", n.text)), None)
    if size is None:
        raise RuntimeError("widget preview (size label) not found")
    x, y = size.center
    drag(x, y - 150, w // 2, h // 2)
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


def record(r: Report, name: str, widget: bool) -> bool:
    """Records `name` from the widget when it is placed, else from the app. True when the widget
    showed the item and was tapped."""
    if widget:
        shown = tap_widget_item(name)
        r.shot(f"widget-after-tap-{name}")
        return shown
    record_in_app(name)
    return False


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
    shown = record(r, ITEM_A, widget)
    source = "WIDGET" if widget else "APP"
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
    shown = record(r, ITEM_B, widget)
    final = read_state()
    this_phone = updated.settings.get("device_id")
    r.check("덮어 설치 뒤 위젯 탭 (N+1)", "기록 2건, 둘 다 source WIDGET, 새 기록의 device_id = 이 폰 값, 옛 기록은 옛 값",
            f"위젯에 {ITEM_B} {'보임' if shown else '없음'}; 활성 판정 {final.active_records}, "
            f"source {final.sources}, device_id {[short(d) for d in final.device_ids]}, 이 폰 {short(this_phone)}",
            shown and final.active_records == 2 and final.sources == ["WIDGET", "WIDGET"]
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
