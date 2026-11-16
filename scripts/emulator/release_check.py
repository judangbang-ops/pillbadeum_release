#!/usr/bin/env python3
"""Release-variant check before personal APKs go out (TASK-051, REQ-055, REQ-062).

Takes two release APKs of one commit signed with one test key, the way personal APKs are signed
(scripts/sign-personal-apk.ps1): --apk has version N and --next-apk has N+1. On one booted emulator
(API 31+, an image that allows `adb root`):

  1. Install N. It is the release variant: not debuggable, `run-as` refused, version N.
  2. Add an item in the app, place the home-screen widget, tap the item on the widget:
     one record with source WIDGET.
  3. RR-26: back up with the local test transport (encryption on), uninstall, install N again:
     the widget-tapped record comes back. The backup holds the placed widget too, so the system may start the
     app for its widget right after the restore, and the restore check may have run before the script reads
     the database. First start shows the restore notice. Add a second item and
     place the widget again: it shows the restore line (supplement R-3) until [확인했어요] in the app.
  4. Install N+1 over N: version N+1, the record stays, same device_id and marker, no restore notice.
  5. Tap the second item on the widget the launcher kept: the new build records it.
  6. TASK-046, the reminder buttons in the release build: make three items in the app with [알림 받기] on (first
     reminder 08:00, supplement N-3), move the clock to a minute before the planned exact alarm, and press [먹었어],
     [오늘 건너뜀] and [30분 뒤] in the notification shade: TAKEN and SKIPPED with source NOTIFICATION, one snooze
     30 minutes after the press, the three reminders gone. The permission windows and the exact-alarm screen are not
     seen here (the debug build's screen checks show them): notifications are allowed with `pm grant` and exact alarms
     with `appops`, before the items are made.
  7. For the report, not a check: the APKs' size and dex files, and five cold starts of the app (`am start -W`), so a
     build with code shrinking (R8, `--minify`) can be compared with one without (TASK-046).

The widget is placed the way a person does it: touch and hold the home screen > Widgets > scroll to
필받음 > drag its preview onto the home screen. (Touch and hold on the app icon is not used: on the
API 34 image the icon in the dock is an app suggestion whose menu has no Widgets entry.) If the
launcher cannot be driven, or the placed widget does not show the item, that is a failed check and
the flow records in the app instead, so the backup and update checks still run.

Writes report.md, screenshots and logs to --out. Exits 1 when a check fails.
Standard library only. Not product code. Screen and file helpers are in backup_restore_check.py; step 6 takes the clock
and notification helpers of explore.py and reminder_check.py, imported inside it because both import this file.
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from backup_restore_check import (
    ACTIVITY,
    HOME_MARK,
    UI_XML,
    ITEM_A,
    ITEM_B,
    NOTICE,
    PKG,
    Node,
    Report,
    adb,
    add_item,
    api_level,
    backup_entries,
    backup_now,
    become_root,
    button_for,
    describe,
    dump_ui,
    ensure_transport,
    find,
    find_exact,
    install,
    launch_app,
    only_db_files,
    package_result,
    read_state,
    screen_has,
    set_transport_params,
    setup_transport,
    sh,
    short,
    show_entries,
    tap,
    tap_text,
    uninstall,
    wait_boot,
    wipe_backup,
)

WIDGET_LABEL = "필받음"  # app_name / widget_label in androidApp/src/main/res/values/strings.xml (TASK-045)
# Screens of the full widget picker read from the top before giving up, about half a screen apart (drag_list)
PICKER_PAGES = 16
# The widget's person picker (SCR-002, TASK-012): Labels.PICK_TITLE. It opens on the drop from two people on; with one
# person it closes by itself and the widget shows everyone (REQ-018).
PICK_TITLE = "이 위젯에 누구 약을 보일까요?"
RESTORED_LINE = "복원됨"  # Labels.restoreWidgetLine (supplement R-3)
# BUG-004: the app switches this WorkManager receiver on when it starts (RescheduleReceiverKeeper), so
# placing the first widget no longer causes a PACKAGE_CHANGED that resets it to the loading view.
RESCHEDULE_RECEIVER = "androidx.work.impl.background.systemalarm.RescheduleReceiver"
# Step 6 (TASK-046): items with reminders, named as in reminder_check.py so its notification helpers know them.
REMINDER_ITEMS = ("PillA", "PillB", "PillC")
REMINDER_SWITCH = "알림 받기"  # Labels.REMINDER_SWITCH: a daily item's switch row is read as this alone
REMINDER_START = "첫 알림 시각"  # Labels.REMINDER_START; the time button is read as `첫 알림 시각, 8시`
ITEM_SAVED = "저장했어요"  # Labels.ITEM_SAVED, the quiet-hours notice after [저장] (not expected at 08:00)
NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"


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


def reschedule_receiver_enabled() -> bool:
    """True when `dumpsys package` lists WorkManager's RescheduleReceiver under enabledComponents
    (it is declared disabled; WorkManager switches it on with the first unfinished work)."""
    lines = sh(f"dumpsys package {PKG}", check=False).splitlines()
    for i, line in enumerate(lines):
        if line.strip() != "enabledComponents:":
            continue
        indent = len(line) - len(line.lstrip())
        for item in lines[i + 1:]:
            if not item.strip() or len(item) - len(item.lstrip()) <= indent:
                break
            if item.strip() == RESCHEDULE_RECEIVER:
                return True
    return False


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
    xml = adb("exec-out", "cat", UI_XML, check=False)
    (r.out / f"api{r.api}-{name}.xml").write_text(xml, encoding="utf-8")


def on_screen(node: Node, h: int) -> bool:
    """Fully visible and clear of the gesture bar at the bottom."""
    x1, y1, x2, y2 = node.bounds
    return x2 > x1 and y2 > y1 and y1 >= 0 and y2 <= h - 120


def widget_preview(h: int, below: int = 0) -> Node | None:
    """The app's widget preview in an open widget sheet (Launcher3 ids), or its size label ("4 × 2").
    In the full picker only nodes under the app's row (`below`) count."""
    nodes = [n for n in dump_ui() if n.bounds[1] >= below]
    for name in ("widget_preview_container", "widget_preview", "widget_cell_content"):
        hit = next((n for n in nodes if n.rid.endswith(f"/{name}") and on_screen(n, h)), None)
        if hit is not None:
            return hit
    # The size label sits in the same cell; the label itself takes no touches, so the cell gets the hold.
    return next((n for n in nodes if re.fullmatch(r"\d+\s*[×x]\s*\d+", n.text) and on_screen(n, h)), None)


def drag_list(w: int, y1: int, y2: int) -> None:
    """Scrolls a list with a slow swipe from y1 to y2 (1.5 s). Let go that slowly, the list flings on only a little past
    where the finger stopped, so no row is skipped between two reads; a quick swipe (0.4 s) flings it on by most of a
    screen. One `input swipe` moves from the first step, so a widget preview under the finger is not held long enough
    to be picked up (separate `input motionevent` calls can be slower than the long-press time)."""
    sh(f"input swipe {w // 2} {y1} {w // 2} {y2} 1500")
    time.sleep(1)


def picker_row(nodes: list[Node], label: str) -> Node | None:
    """The app's row in the widget picker: its name, not the search field holding it, nor a widget's own name under a
    preview (Launcher3 `widget_name`, also in the suggested widgets above the list)."""
    return next((n for n in nodes if n.text == label and not n.cls.endswith("EditText")
                 and not n.rid.endswith("/widget_name")), None)


def open_full_widget_picker(r: Report, tag: str, w: int, h: int, label: str = WIDGET_LABEL) -> int:
    """Home > touch and hold empty space > Widgets > the row of the app named `label`, opened so its previews show.

    A name in Latin letters (`DidITake`, the builds before TASK-045 that upgrade_check.py installs) is typed in the
    picker's search. `adb shell input text` cannot type Korean, so for 필받음 the list is read from the top down, about
    half a screen at a time with a slow swipe (drag_list), as a person scrolls to the app. Apps are listed by name in
    the system language, and Korean comes after Latin letters in English, so the row is near the end. Returns the
    bottom of the row."""
    go_home()
    sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
    tap_text("Widgets", 10)
    header = None
    if label.isascii():
        search = next((n for n in dump_ui() if n.cls.endswith("EditText") and "Search" in (n.text + n.desc)), None)
        if search is not None:
            tap(search)
            sh(f"input text {label}")
            time.sleep(2)
        for _ in range(8):
            header = picker_row(dump_ui(), label)
            if header is not None:
                break
            sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
            time.sleep(1)
    else:
        time.sleep(1)  # the sheet slides up; the first swipe should scroll the list, not the sheet
        seen: list[str] | None = None
        for _ in range(PICKER_PAGES):
            nodes = dump_ui()
            header = picker_row(nodes, label)
            if header is not None:
                break
            texts = [n.text for n in nodes if n.text]
            if texts == seen:
                break  # the list did not move: its end
            seen = texts
            drag_list(w, h * 3 // 4, h // 4)
        if header is not None and header.bounds[1] > h // 2:
            # Up to the top quarter, so the previews open on the screen (at the end of the list it may not move)
            drag_list(w, h * 3 // 4, h * 3 // 4 - (header.bounds[1] - h // 4))
            header = picker_row(dump_ui(), label) or header
    if header is None:
        save_ui(r, f"{tag}-picker-no-app")
        raise RuntimeError(f"widget picker has no '{label}'")
    if widget_preview(h, header.bounds[3]) is None:
        tap(header)
        time.sleep(1)
    header = picker_row(dump_ui(), label) or header
    if widget_preview(h, header.bounds[3]) is None:
        # The previews open under the row, past the bottom edge when the row is low (first run, 2026-11-12).
        sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 2} 1000")
        time.sleep(1)
        header = picker_row(dump_ui(), label)
        if header is None:
            # Without the row the previews of other apps could be taken for ours.
            save_ui(r, f"{tag}-picker-scrolled")
            raise RuntimeError(f"the '{label}' row scrolled out of the picker")
    return header.bounds[3]


def place_widget(r: Report, tag: str, pick: str = "전체", label: str = WIDGET_LABEL) -> bool:
    """Drags the widget preview of the app named `label` onto the middle of the home screen. When the person picker
    opens on the drop (two or more people, TASK-012), takes its picture and taps `pick`: 전체 or a person's name.
    Returns whether the picker opened (with one person it does not, REQ-018)."""
    w, h = screen_size()
    below = open_full_widget_picker(r, tag, w, h, label)
    preview = widget_preview(h, below)
    save_ui(r, f"{tag}-picker")
    r.shot(f"{tag}-picker")
    if preview is None:
        raise RuntimeError("widget preview not on screen")
    r.note(f"위젯 놓기 ({tag}): 끈 곳 {preview.rid or preview.text} {preview.bounds}")
    x, y = preview.center
    drag(x, y, w // 2, h // 2, r, tag)
    picker = find(PICK_TITLE, 6) is not None
    if picker:
        save_ui(r, f"{tag}-pick")
        r.shot(f"{tag}-pick")
        # Each choice is read as `전체, 나·Mom의 약 3개` or `Mom, 약 1개`: match the start of that description.
        choice = next((n for n in dump_ui() if n.desc.startswith(f"{pick}, ")), None)
        if choice is None:
            raise RuntimeError(f"no '{pick}' in the widget's person picker")
        tap(choice)
        r.note(f"위젯 대상자 고르기 ({tag}): {pick}")
        time.sleep(2)
    go_home()
    r.shot(f"{tag}-placed")
    return picker


def try_place_widget(r: Report, tag: str) -> bool:
    """Places the widget; a failure is one failed check, and the flow goes on with in-app records
    so the backup and update checks still run.

    Right after a boot or an install the launcher may not list the app's widget yet, or its long-press
    menu may lack Widgets (PR #16 failed three times this way on 2026-11-12, and a rerun passed). So a
    first failure waits 20 seconds on a fresh home screen and tries once more.
    """
    try:
        try:
            place_widget(r, tag)
        except Exception as first:  # the launcher UI differs between images
            r.note(f"위젯 놓기 ({tag}) 첫 시도 실패, 20초 뒤 한 번 더: {type(first).__name__}: {first}")
            go_home()
            time.sleep(20)
            place_widget(r, f"{tag}-retry")
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
    button = button_for(row, "먹었어") if row is not None else None
    if button is None:
        raise RuntimeError(f"no [먹었어] for {name}")
    tap(button)
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
# Reminders in the release build (step 6, TASK-046)


def seek_node(match, what: str, tries: int = 5) -> Node:
    """The first node `match` accepts that is fully on screen, scrolling the screen down to find it."""
    w, h = screen_size()
    for attempt in range(tries):
        node = next((n for n in dump_ui() if match(n) and on_screen(n, h)), None)
        if node is not None:
            return node
        if attempt < tries - 1:
            sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
            time.sleep(1)
    raise RuntimeError(f"{what} not found after scrolling")


def add_reminder_item(name: str) -> str:
    """App home > [항목 추가] > name > [알림 받기] on > [저장], as a person makes an item with reminders (TASK-023).
    The daily item keeps the form's time of day, whose first reminder is 08:00 (supplement N-3). Notifications are
    already allowed, so the switch asks nothing. Returns how the form reads the first reminder (`첫 알림 시각, 8시`)."""
    from explore import tap_scrolled, type_into  # explore imports this file (module note)

    launch_app()
    tap_scrolled("항목 추가")
    type_into("약 이름", name)
    tap(seek_node(lambda n: n.desc == REMINDER_SWITCH, f"[{REMINDER_SWITCH}] of {name}"))
    start = None
    end = time.time() + 10
    while start is None and time.time() < end:
        start = next((n for n in dump_ui() if n.desc.startswith(f"{REMINDER_START}, ")), None)
        if start is None:
            time.sleep(1)
    if start is None:
        raise RuntimeError(f"no '{REMINDER_START}' after [{REMINDER_SWITCH}] of {name}")
    save = find_exact("저장", 10)
    if save is None:
        raise RuntimeError(f"no [저장] on the form of {name}")
    tap(save)
    if find_exact(ITEM_SAVED, 3) is not None:
        ok = find_exact("확인", 5)
        if ok is not None:
            tap(ok)
    if find_exact(HOME_MARK, 15) is None:
        raise RuntimeError(f"the first screen did not come back after saving {name}")
    return start.desc


def notification_buttons(r: Report) -> None:
    """Step 6 (TASK-046): the reminders and their three buttons in the release build. See the module note."""
    from explore import phone_now, set_clock  # both modules import this file (module note)
    from reminder_check import (
        SKIP,
        SNOOZE,
        TAKEN,
        collapse_shade,
        dose_names,
        doses,
        expand_shade,
        judgments,
        lock_notices,
        posted,
        press_in_shade,
        query,
        show_alarms,
        show_posted,
        snoozes,
        title,
        wait_alarm,
        wait_posted,
    )

    def planned(found) -> list:
        return [a for a in found if not a.windowed]  # without the inexact path's second alarm

    granted = sh(f"pm grant {PKG} {NOTIFICATION_PERMISSION}", check=False).strip()
    sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow", check=False)
    exact_op = sh(f"appops get {PKG} SCHEDULE_EXACT_ALARM", check=False).strip()
    r.note(f"6단계 준비: 알림 권한은 `pm grant`로{f' ({granted})' if granted else ''}, 정확 알람은 `appops`로 허용함 "
           f"('{exact_op}'). 권한 창과 정확 알람 화면은 디버그 빌드의 화면 확인에서 봄")
    starts = [add_reminder_item(name) for name in REMINDER_ITEMS]
    r.shot("reminder-items")
    rules = [row for row in query("SELECT i.name, r.enabled, r.start_time FROM reminder_rule r "
                                  "JOIN intake i ON i.id = r.intake_id ORDER BY i.name") if row[0] in REMINDER_ITEMS]
    found = wait_alarm(lambda a: len(planned(a)) == 1, 30)
    alarm = planned(found)
    form = starts[0] if len(set(starts)) == 1 else ", ".join(starts)
    r.check("알림 켠 항목 셋 (릴리즈, TASK-023·024)",
            f"앱에서 [{REMINDER_SWITCH}]를 켜고 저장: 규칙 셋 다 켜짐·08:00, 다음 칸의 정확 알람 1개",
            f"양식 '{form}'; 규칙 {rules}; 알람 {show_alarms(found)}",
            rules == [(n, 1, "08:00") for n in REMINDER_ITEMS] and len(alarm) == 1 and alarm[0].exact
            and alarm[0].millis is not None)
    if len(alarm) != 1 or alarm[0].millis is None:
        return
    at = alarm[0].millis // 1000
    if at - phone_now() > 90:
        moved = set_clock(datetime.fromtimestamp(at - 60, timezone.utc))
        r.note(f"6단계: 시계를 알람 1분 전으로 옮김 → {moved} (알람 {alarm[0].describe()})")
    items = wait_posted(lambda ps: set(REMINDER_ITEMS) <= set(dose_names(ps)), 240)
    titles = {title(n) for n in REMINDER_ITEMS}
    shown = [p for p in doses(items) if p.title in titles]
    buttons = sorted({" · ".join(p.actions) for p in shown})
    rang = len(shown) == len(REMINDER_ITEMS) and all(set(p.actions) == {TAKEN, SNOOZE, SKIP} for p in shown)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    go_home()
    expand_shade()
    r.shot("reminders-shade")
    save_ui(r, "reminders-shade")
    collapse_shade()
    r.check("알림 울림 (릴리즈, TASK-024·025)", f"세 항목 알림 '<이름> 먹어~!'에 [{TAKEN}]·[{SNOOZE}]·[{SKIP}]",
            f"{show_posted(items)}; 버튼 {buttons}", rang)
    if not rang:
        return

    taken, skipped, snoozed = REMINDER_ITEMS
    press_in_shade(taken, TAKEN)
    press_in_shade(skipped, SKIP)
    before = phone_now() * 1000
    press_in_shade(snoozed, SNOOZE)
    ids = {str(name): str(i) for name, i in query("SELECT name, id FROM intake")}
    state = {k: v for k, v in judgments(ids).items() if k in REMINDER_ITEMS}
    until = snoozes(ids)
    left = posted()
    found = wait_alarm(lambda a: len(planned(a)) == 1, 15)
    alarm = planned(found)
    snooze_at = until.get(snoozed)
    on_time = snooze_at is not None and 30 * 60_000 <= snooze_at - before <= 32 * 60_000
    gone = not set(REMINDER_ITEMS) & set(dose_names(left)) and not lock_notices(left)
    r.check("알림 버튼 (릴리즈, TC-011·TC-012)",
            f"{taken} 복용(TAKEN, NOTIFICATION), {skipped} 건너뜀(SKIPPED, NOTIFICATION), {snoozed} 미룸 1건(누른 때부터 "
            "30분 뒤), 세 알림과 잠금 화면 알림이 사라짐, 다음 알람 = 미룬 시각",
            f"판정 {state}; 미룸 {list(until)} (누른 때부터 "
            f"{'?' if snooze_at is None else round((snooze_at - before) / 60_000, 1)}분); 남은 알림 {show_posted(left)}; "
            f"알람 {show_alarms(found)}",
            state.get(taken) == ("TAKEN", "NOTIFICATION") and state.get(skipped) == ("SKIPPED", "NOTIFICATION")
            and snoozed not in state and list(until) == [snoozed] and on_time and gone
            and len(alarm) == 1 and alarm[0].millis is not None and abs(alarm[0].millis - snooze_at) < 1000)
    r.shot("after-buttons")


# ------------------------------------------------------------------------------------------------
# For the report: size and start time (step 7, TASK-046)


def apk_summary(apk: Path) -> str:
    """The APK's size and its dex files: one file and fewer bytes when code shrinking (R8) is on."""
    with zipfile.ZipFile(apk) as z:
        dex = [i for i in z.infolist() if re.fullmatch(r"classes\d*\.dex", i.filename)]
    return (f"{apk.name} {apk.stat().st_size / 1_000_000:.2f} MB, dex {len(dex)}개 "
            f"(풀면 {sum(i.file_size for i in dex) / 1_000_000:.2f} MB)")


def cold_starts(times: int = 5) -> list[int]:
    """TotalTime in ms of `am start -W` right after `am force-stop`: the app's cold start, to compare builds. Not a
    check: an emulator on a shared runner is noisy. Run last, because a force stop also drops the app's alarms."""
    found: list[int] = []
    for _ in range(times):
        sh(f"am force-stop {PKG}", check=False)
        time.sleep(2)
        out = sh(f"am start -W -n {ACTIVITY}", check=False, timeout=60)
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            found.append(int(m.group(1)))
        time.sleep(3)
    return found


# ------------------------------------------------------------------------------------------------
# Flow


def restore_check_seen(state, old_id: str | None) -> str | None:
    """RR-26, the read right after the reinstall: "before" when the app's restore check has not run yet (old
    device_id, no marker), "after" when it has (new device_id, restored_from = the old one; the marker may still be
    on its way), None for anything else.

    Both happen. The backup also holds the widget placed before it, and right after the restore the system may start
    the app for a broadcast to the widget provider before the script reads (run 43563317318: "Start proc ... for
    broadcast {...DidITakeWidgetReceiver}" 0.07 s after "Restore complete", then "restore detected"). That entry
    opens the core like any other (supplement R-1)."""
    now_id = state.settings.get("device_id")
    if not old_id:
        return None
    if now_id == old_id and state.marker is None:
        return "before"
    if (now_id and now_id != old_id and state.settings.get("restored_from_device_id") == old_id
            and state.marker in (None, now_id)):
        return "after"
    return None


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
    # BUG-004: the receiver is already on before the first widget, so placing it switches nothing.
    keeper = reschedule_receiver_enabled()
    r.check("BUG-004 첫 위젯 전 RescheduleReceiver", "앱을 연 뒤 켜져 있음 (첫 위젯 때 PACKAGE_CHANGED 없음)",
            "켜짐" if keeper else "꺼짐", keeper)
    widget = try_place_widget(r, "n")
    shown, source = record(r, ITEM_A, widget)
    tapped = read_state()
    old_id = tapped.settings.get("device_id")
    r.check("위젯 탭 기록 (릴리즈 N)", "활성 판정 1, source WIDGET, device_id·표식 있음",
            f"위젯에 {ITEM_A} {'보임' if shown else '없음'}; 활성 판정 {tapped.active_records}, source {tapped.sources}, "
            f"device_id {short(old_id)}, 표식 {short(tapped.marker)}",
            shown and tapped.active_records == 1 and tapped.sources == ["WIDGET"]
            and bool(old_id) and tapped.marker == old_id)

    # 3. RR-26: back up -> uninstall -> install -> the record comes back
    wipe_backup()
    set_transport_params("is_encrypted=true")
    out = backup_now()
    entries = backup_entries()
    r.check("B-1 릴리즈 변형 백업에 든 파일 (암호화 있음)", "앱 백업이 성공하고 기록 DB와 짝 파일만",
            f"{package_result(out) or '앱 줄 없음'}; {show_entries(entries)}",
            "with result: Success" in package_result(out) and only_db_files(entries))
    uninstall()
    time.sleep(2)
    install(apk)
    restored = read_state()
    seen = restore_check_seen(restored, old_id)
    r.check("RR-26 백업 → 지우고 다시 설치 (릴리즈 N)",
            f"기록 1건(source {source})이 돌아옴. 복원 확인 전이면 device_id 그대로·표식 없음, 시스템이 위젯 방송으로 "
            "앱을 먼저 깨웠으면 새 device_id·restored_from = 옛 값",
            describe(restored) + f", source {restored.sources}"
            + ("; 앱이 먼저 깨어 복원 확인을 함" if seen == "after" else ""),
            restored.active_records == 1 and restored.sources == [source] and seen is not None)
    launch_app()
    notice = screen_has(NOTICE, 10)
    r.shot("restored-notice")
    after = read_state()
    new_id = after.settings.get("device_id")
    r.check("복원 뒤 첫 실행 (릴리즈 N)", "복원 안내, 새 device_id = 표식, restored_from = 옛 device_id",
            f"안내 {'보임' if notice else '없음'}; {describe(after)}",
            notice and bool(old_id) and after.settings.get("restored_from_device_id") == old_id
            and bool(new_id) and new_id != old_id and after.marker == new_id)

    # Supplement R-3, widget part: uninstalling took the first widget off the home screen. Add the second
    # item and place a widget again while the notice is pending: the widget shows the short restore line
    # until [확인했어요] in the app.
    add_item(ITEM_B)
    widget = try_place_widget(r, "n-again")
    if widget:
        go_home()
        line = find(RESTORED_LINE, 15)
        r.shot("widget-restored")
        r.check("복원 뒤 위젯 (보충 R-3)", f"머리글 아래 '{RESTORED_LINE} · … · 앱에서 확인' 줄",
                line.text if line else "줄 없음", line is not None and "앱에서 확인" in line.text)
    launch_app()
    if notice:
        tap_text("확인했어요")
        time.sleep(2)
    if widget:
        go_home()
        end = time.time() + 15
        while True:
            texts = [n.text for n in dump_ui()]
            on_screen_item = any(ITEM_A in t for t in texts)
            line_left = any(RESTORED_LINE in t for t in texts)
            if (on_screen_item and not line_left) or time.time() >= end:
                break
            time.sleep(1)
        r.check("[확인했어요] 뒤 위젯 (RR-33 위젯 몫)", f"위젯에 {ITEM_A}가 보이고 복원 줄이 사라짐",
                f"위젯 {ITEM_A} {'보임' if on_screen_item else '없음'}, 복원 줄 {'남아 있음' if line_left else '없음'}",
                on_screen_item and not line_left)
    set_transport_params("")

    # 4. N+1 over N
    before_update = read_state()
    adb("install", "-r", str(next_apk), timeout=180)
    info2 = package_info()
    version_next = int(info2["versionCode"]) if info2["versionCode"].isdigit() else -1
    updated = read_state()
    r.check("다음 빌드 N+1을 덮어 설치", "버전 번호가 커짐, 기록·device_id·표식 그대로",
            f"versionCode {version_n} → {version_next}; {describe(updated)}",
            version_next > version_n > 0
            and updated.active_records == before_update.active_records == 1
            and bool(before_update.settings.get("device_id")) and bool(before_update.marker)
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

    # 6. the reminder buttons with the release build N+1 (TASK-046)
    notification_buttons(r)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path, help="release APK, version N")
    parser.add_argument("--next-apk", required=True, type=Path, help="same key, version N+1")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--commit", default="", help="commit the APKs were built from, for the report")
    parser.add_argument("--minify", action="store_true",
                        help="the APKs were built with code shrinking (-Ppillbadeum.minify=true); names the report")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    build = "코드 축소 켬" if args.minify else "코드 축소 끔"
    r = Report(args.out, api, title=f"릴리즈 변형 확인 API {api} ({build})")
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    from explore import set_zone  # explore imports this file (module note)

    # Seoul like the other checks, before the first install, so the app's base zone and the report's times are KST
    zone = set_zone()
    setup_transport()
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 시간대 {zone}, APK {args.apk.name} → "
           f"{args.next_apk.name}" + (f", 커밋 {args.commit}" if args.commit else ""))
    r.note(f"빌드 ({build}): {apk_summary(args.apk)}; {apk_summary(args.next_apk)}")
    try:
        ensure_transport(r)
        run_flow(args.apk, args.next_apk, r)
        starts = cold_starts()
        r.note(f"7단계 앱 콜드 스타트 (`am start -W`의 TotalTime, {len(starts)}회, 판정 아님): {starts} ms"
               + (f", 중앙값 {statistics.median(starts):.0f} ms" if starts else ""))
    except Exception as exc:
        r.check("스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            (args.out / f"api{api}-ui.xml").write_text(adb("exec-out", "cat", UI_XML, check=False), encoding="utf-8")
        except Exception:
            pass
    finally:
        r.write()
        try:
            (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60), encoding="utf-8")
        except Exception as exc:  # the report is already written; a missing logcat must not hide it
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
