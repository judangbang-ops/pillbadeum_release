#!/usr/bin/env python3
"""Update from an older build on one emulator (API 34): the widget placed before the update keeps showing everyone
(TASK-012, supplement R-3, RR-74), and what the old build recorded stays.

Why: the person picker came with TASK-012 (PR #19). A widget placed by a build before it has no person choice, and
from two people on a widget without one asks to be set up again (SCR-001#reconfigure), as after a restore. So the
first process of the build with the picker fills every placed widget without a choice with 전체, once per install
(WidgetChoices.prepare, run by AppContainer.core() before anything draws, from whichever entry comes first: the app,
the widget, an alarm or a system broadcast). The app tests check this on Robolectric (WidgetPickTest.rr74_*); this
script checks it with a real launcher, a real widget placed by the old APK and `adb install -r`, as a person updating
would.

Takes two debug APKs signed with one key: --old, built from a commit before the picker (by default 8c50d47, main of
2026-11-13 before PR #19), and --new, built from this commit with a bigger version number. --old-label is the old
build's app name, by which its widget is found in the launcher: DidITake before TASK-045 (2026-11-16), 필받음 after.
Flow (Asia/Seoul):

  0. Seoul time zone, today 08:30, the old build installed.
  1. The old build, with two people as a family using it would have: Vitamin (나), then BPpill for Mom, who is added on
     the item form ([+ 사람 추가]: that build has no 대상자 관리). The widget placed (that build has no person picker)
     shows both, and Vitamin tapped on it gives one record with source WIDGET. The widget's Glance state has no person
     choice. If the old widget stays on its loading view (BUG-004, fixed after that build) Vitamin is recorded in the
     old app instead and that is noted.
  2. The new build installed over it (`adb install -r`): a bigger version number, the record and the install marker
     stay. Before anyone opens the app, the widget, drawn again by the new build, reads 나 Vitamin and Mom BPpill and
     does not ask to be set up again (RR-74). With two people a widget drawn before the fill would ask, so this also
     shows the fill comes first. The report notes which build drew the widget (the new header reads "<app> · M/D"),
     why the new build's first process started and when the fill was logged: in the first two runs a broadcast after
     the update (MY_PACKAGE_REPLACED to DayBoundaryReceiver in run 43689285870, a widget update to
     DidITakeWidgetReceiver in run 43691218110) started it and the fill came within a second, before the app was opened.
  3. The new build opened: the intro shows once (the old build's data has no intro_seen_at, SPEC-002) and is passed with
     [건너뛰기]. Since the update the app has logged exactly once that it filled one widget with 전체 (when, against the
     first start, goes into the report), the widget's state holds "*" (WidgetChoice.EVERYONE), and the widget still
     reads both people's items.
  4. BPpill tapped on the widget placed before the update: a second record with source WIDGET, and the widget reads Mom
     BPpill as taken. Without a choice, from two people on, the widget would record nothing (SPEC-009).
  5. Control: the widget's state deleted, as a restore leaves it, and the widget drawn again: with two people it asks to
     be set up again, and the app does not fill it in a second time (once per install). This shows that steps 2 to 4
     pass because of the filled-in choice.

The new build's debug receiver redraws the widgets (step 5) and the app data is read as root, so both APKs are debug
builds. Names are typed in English (`adb shell input text` cannot type Korean).

Writes report.md, screenshots (api34-NN-name.png), screen dumps and the logcat to --out. Exits 1 when a check fails.
Standard library only. Not product code. The helpers come from the other emulator scripts.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import traceback
from datetime import datetime
from pathlib import Path

from backup_restore_check import (
    ACTIVITY,
    DATA,
    DB,
    HOME_MARK,
    INTRO_MARK,
    PKG,
    Report,
    adb,
    add_item,
    api_level,
    become_root,
    button_for,
    dump_ui,
    find,
    find_exact,
    install,
    read_state,
    sh,
    tap,
    tap_text,
    uninstall,
    wait_boot,
)
from explore import (
    BP,
    LOG_PARTS,
    ME,
    MOM,
    VITAMIN,
    ZONE,
    Steps,
    font_scale,
    night,
    set_clock,
    set_zone,
    tap_exact,
)
from release_check import go_home, package_info, place_widget, save_ui, tap_widget_item
from screens_check import (
    RECONFIGURE,
    has_desc,
    has_text,
    home_nodes,
    our_widget_ids,
    spoken,
    stored_choices,
    wipe_widget_choices,
)

OLD_HOME_MARK = "항목 추가"  # the old build's first screen has [항목 추가]; it has no 지난 기록 in its top bar
FILLED_LOG = "widget choices ready:"  # WidgetChoices.prepare logs this once per install (tag WidgetChoices)


def launch_old() -> None:
    """Starts the old build and waits for its first screen (that build has no intro)."""
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    for _ in range(2):
        sh(f"am start -n {ACTIVITY}")
        if find_exact(OLD_HOME_MARK, 30) is not None:
            return
    raise RuntimeError("the old build's first screen did not show")


def record_in_old_app(name: str) -> None:
    """The old build's first screen > that row's [먹었어]."""
    launch_old()
    row = find(name, 20)
    button = button_for(row, "먹었어") if row is not None else None
    if button is None:
        raise RuntimeError(f"no [먹었어] for {name} in the old build")
    tap(button)
    time.sleep(2)


def clear_log() -> None:
    """Keeps the logcat so far for the report, then clears it, so later reads see only what came after."""
    LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
    adb("logcat", "-c", check=False)


def filled_log() -> list[tuple[str, str]]:
    """What WidgetChoices.prepare logged since the last clear_log(), as (time, message).
    A line reads "11-15 08:31:54.541 I/WidgetChoices( 7053): widget choices ready: …"."""
    out = adb("logcat", "-d", "-v", "time", "-s", "WidgetChoices", check=False)
    found = []
    for line in out.splitlines():
        if FILLED_LOG in line:
            parts = line.split()
            found.append((parts[1] if len(parts) > 1 else "?", line.split(": ", 1)[-1].strip()))
    return found


def filled_lines() -> list[str]:
    return [message for _, message in filled_log()]


def process_start() -> str:
    """Why the app's first process since the last clear_log() was started, from ActivityManager's
    "Start proc 7053:com.judangbang.pillbadeum/u0a192 for broadcast {…/….boundary.DayBoundaryReceiver}"."""
    out = adb("logcat", "-d", "-v", "time", "-s", "ActivityManager", check=False)
    for line in out.splitlines():
        if "Start proc" in line and f":{PKG}/" in line and " for " in line:
            return f"{line.split()[1]} {line.split(' for ', 1)[1].strip().replace(f'{PKG}/{PKG}.', '')}"
    return "기록 없음"


def widget_spoken(nodes) -> list[str]:
    return spoken(nodes, VITAMIN) + spoken(nodes, BP)


def both_people(nodes) -> bool:
    """The widget reads 나 Vitamin and Mom BPpill: from two people on both builds put the person before the name."""
    return has_desc(nodes, f"{ME} {VITAMIN}, ") and has_desc(nodes, f"{MOM} {BP}, ")


# The new widget's header reads "<app> · 11/15" ("필받음 · 11/15" since TASK-045); the old one has "11/15 (일)"
NEW_HEADER = re.compile(r"^.+ · \d{1,2}/\d{1,2}$")


def new_layout(nodes) -> bool:
    return any(NEW_HEADER.match(n.text) for n in nodes)


def edit_texts() -> list:
    return [n for n in dump_ui() if n.cls.endswith("EditText")]


def add_for_new_person_in_old_app(item: str, person: str) -> None:
    """The old build's item form (8c50d47, App.kt EditScreen): [항목 추가] > the item's name > [+ 사람 추가] > the
    person's name > [추가] > the person's chip > [저장]. That build adds people only here (it has no 대상자 관리), and a
    person added here is not chosen for the item until the chip is tapped. The form's title is also 항목 추가, so the
    first screen is told from the form by having no text field."""
    launch_old()
    tap_exact(OLD_HOME_MARK)
    fields = edit_texts()
    if not fields:
        raise RuntimeError("no name field on the old item form")
    tap(fields[0])
    sh(f"input text {item}")
    tap_exact("+ 사람 추가")
    end = time.time() + 15
    while len(fields := edit_texts()) < 3:  # item name, widget alias, then the new person's name
        if time.time() >= end:
            raise RuntimeError(f"the person's name field did not open: {len(fields)} fields")
        time.sleep(1)
    tap(fields[2])
    sh(f"input text {person}")
    tap_exact("추가")
    end = time.time() + 15
    while len(edit_texts()) > 2:  # the panel closes once the person is added
        if time.time() >= end:
            raise RuntimeError("the add-person panel did not close")
        time.sleep(1)
    tap_exact(person)
    tap_exact("저장")
    end = time.time() + 20
    while True:
        nodes = dump_ui()
        if not any(n.cls.endswith("EditText") for n in nodes) and any(item in n.text for n in nodes):
            return
        if time.time() >= end:
            raise RuntimeError(f"{item} not on the old first screen after [저장]")
        time.sleep(1)


def owners() -> list[str]:
    """'person item' for each item in the app's database, oldest first (the old build has the same two tables)."""
    tmp = Path(tempfile.mkdtemp())
    try:
        for suffix in ("", "-wal", "-journal"):
            remote = f"{DATA}/databases/{DB}{suffix}"
            if sh(f"ls {remote} 2>/dev/null", check=False).strip():
                adb("pull", remote, str(tmp / f"{DB}{suffix}"))
        con = sqlite3.connect(tmp / DB)
        try:
            rows = con.execute("SELECT p.name, i.name FROM intake i JOIN person p ON p.id = i.person_id "
                               "ORDER BY i.created_at").fetchall()
        finally:
            con.close()
        return [f"{p} {i}" for p, i in rows]
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run(r: Report, steps: Steps, old_apk: Path, new_apk: Path, old_commit: str, old_label: str) -> None:
    today = datetime.now(ZONE).date()
    morning = datetime(today.year, today.month, today.day, 8, 30, tzinfo=ZONE)
    seen: dict[str, str] = {}

    # 0. phone setup, the old build
    def setup() -> str:
        become_root()
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {setting} 0")
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("svc power stayon true", check=False)
        sh("settings put system screen_off_timeout 1800000", check=False)
        zone = set_zone()
        clock = set_clock(morning)
        font_scale("1.0")
        night(False)
        uninstall()
        install(old_apk)
        seen["old_version"] = package_info()["versionCode"]
        return f"시간대 {zone}, 시계 {clock}, 옛 빌드 versionCode {seen['old_version']} ({old_commit[:7]})"

    if not steps("0. 준비: 서울 시간대, 오늘 08:30, 옛 빌드 설치", "시간대·시계가 바뀌고 옛 빌드가 깔림", setup):
        return

    # 1. the old build with two people: items, the widget, a tap on the widget
    def old_build() -> str:
        launch_old()
        add_item(VITAMIN, r)  # 나, the only person so far
        add_for_new_person_in_old_app(BP, MOM)
        r.shot("old-app")
        items = owners()
        if items != [f"{ME} {VITAMIN}", f"{MOM} {BP}"]:
            raise RuntimeError(f"items in the old build: {items}")
        try:
            picker = place_widget(r, "old-widget", label=old_label)
        except RuntimeError as exc:
            r.note(f"옛 빌드 위젯 놓기 첫 시도 실패, 20초 뒤 한 번 더: {exc}")
            go_home()
            time.sleep(20)
            picker = place_widget(r, "old-widget-retry", label=old_label)
        ids = our_widget_ids()
        if picker:
            raise RuntimeError("a person picker opened in the old build")
        if len(ids) != 1:
            raise RuntimeError(f"widgets of the app on the home screen: {ids}")
        seen["widget_id"] = str(ids[0])
        nodes = home_nodes(both_people, 20)
        drawn = widget_spoken(nodes)
        if both_people(nodes):
            tap_widget_item(VITAMIN)
            how = "위젯에서 탭"
        else:
            # BUG-004 (the first widget stuck on its loading view) was fixed after this build (TASK-054)
            r.note(f"옛 빌드 위젯이 두 사람 항목을 보이지 않아({drawn or '로딩 표시, BUG-004'}) 옛 앱에서 기록함")
            record_in_old_app(VITAMIN)
            how = "옛 앱에서 기록(위젯에 두 항목이 안 보임)"
        go_home()
        r.shot("old-widget-after-tap")
        dump_ui()  # save_ui keeps the last dump: read the screen again so the dump matches the screenshot
        save_ui(r, "old-widget-after-tap")
        state = read_state()
        choices = stored_choices()
        seen["marker"] = state.marker or ""
        seen["sources"] = ",".join(state.sources)
        if state.active_records != 1:
            raise RuntimeError(f"active records {state.active_records}, expected 1")
        if any(c != "-" for c in choices):
            raise RuntimeError(f"the old build stored a person choice: {choices}")
        return (f"항목 {items}, 위젯 id {ids[0]}, 고르기 화면 없음, 옛 위젯 {' / '.join(drawn) or '항목 안 보임'}, "
                f"{how}: 활성 판정 {state.active_records}, source {state.sources}, "
                f"저장된 선택 {choices or '없음(상태 파일 없음)'}")

    if not steps("1. 옛 빌드: 대상자 둘·항목·위젯·위젯 탭",
                 "나 Vitamin과 Mom BPpill(옛 항목 화면의 [+ 사람 추가]), 고르기 화면 없이 위젯이 놓이고 기록 1건, "
                 "위젯에 저장된 대상자 선택 없음", old_build):
        return

    # 2. the new build over the old one
    def update() -> str:
        clear_log()
        out = adb("install", "-r", "-t", str(new_apk), timeout=180)
        if "Success" not in out:
            raise RuntimeError(f"install -r: {out.strip()}")
        seen["installed_at"] = sh("date +%H:%M:%S", check=False).strip()
        time.sleep(5)
        info = package_info()
        seen["new_version"] = info["versionCode"]
        state = read_state()
        # Before anyone opens the app. The new build draws the widget again after the update; a widget drawn without
        # a choice would ask to be set up again (two people), so wait for the new layout and then judge it
        nodes = home_nodes(lambda ns: new_layout(ns) and (both_people(ns) or has_text(ns, RECONFIGURE)), 20)
        r.shot("widget-after-update")
        save_ui(r, "widget-after-update")
        drawn = widget_spoken(nodes)
        by = "새 빌드가 그림" if new_layout(nodes) else "옛 빌드 그림 그대로(새 빌드가 아직 그리지 않음)"
        before = filled_log()
        r.note(f"앱을 열기 전(덮어 설치 {seen['installed_at']}): 위젯 {by}, 새 빌드 첫 프로세스 {process_start()}, 채움 로그 "
               f"{' / '.join(f'{t} {m}' for t, m in before) or '아직 없음'}")
        if not int(info["versionCode"]) > int(seen["old_version"]):
            raise RuntimeError(f"versionCode {seen['old_version']} -> {info['versionCode']}")
        if state.active_records != 1 or ",".join(state.sources) != seen["sources"]:
            raise RuntimeError(f"records after the update {state.active_records}, sources {state.sources}")
        if (state.marker or "") != seen["marker"] or not seen["marker"]:
            raise RuntimeError(f"install marker {short(seen['marker'])} -> {short(state.marker)}")
        if has_text(nodes, RECONFIGURE) or not both_people(nodes):
            raise RuntimeError(f"widget before the app was opened ({by}): {drawn}, "
                               f"reconfigure line {has_text(nodes, RECONFIGURE)}")
        return (f"versionCode {seen['old_version']} → {info['versionCode']}; 활성 판정 {state.active_records}, "
                f"source {state.sources}, 설치 표식 그대로 {short(state.marker)}; 앱을 열기 전 위젯({by}): "
                f"{' / '.join(drawn)}, 다시 설정 안내 없음")

    if not steps("2. 지금 빌드를 덮어 설치 (adb install -r)",
                 "버전 번호가 커지고 기록과 설치 표식이 그대로. 앱을 열기 전 위젯이 나 Vitamin·Mom BPpill을 보이고 "
                 "다시 설정을 묻지 않음(RR-74)", update):
        return

    # 3. the new build's first start: the intro once, the widget filled with 전체
    def first_start() -> str:
        sh("input keyevent KEYCODE_WAKEUP", check=False)
        sh("wm dismiss-keyguard", check=False)
        opened = sh("date +%H:%M:%S", check=False).strip()
        sh(f"am start -n {ACTIVITY}")
        intro = find_exact(INTRO_MARK, 30) is not None
        if intro:
            r.shot("intro-after-update")
            tap_text(INTRO_MARK)
        if find_exact(HOME_MARK, 30) is None:
            raise RuntimeError("the new build's first screen did not show")
        r.shot("new-app")
        time.sleep(3)
        log = filled_log()
        lines = [message for _, message in log]
        choices = stored_choices()
        nodes = home_nodes(both_people)
        widget = widget_spoken(nodes)
        r.shot("widget-filled")
        save_ui(r, "widget-filled")
        if not intro:
            raise RuntimeError("no intro on the first start after the update (intro_seen_at is absent)")
        if len(lines) != 1 or not lines[0].startswith(f"{FILLED_LOG} 1 filled with everyone"):
            raise RuntimeError(f"WidgetChoices log {lines}")
        if choices != ["*"]:
            raise RuntimeError(f"stored choices {choices}, expected ['*'] (전체)")
        if not both_people(nodes) or has_text(nodes, RECONFIGURE):
            raise RuntimeError(f"widget {widget}, reconfigure line {has_text(nodes, RECONFIGURE)}")
        # "08:31:54.541" against "08:32:02": the same day, so the strings compare in time order
        when = "첫 실행 전" if log[0][0] < opened else "첫 실행 뒤"
        return (f"첫 실행({opened}) 안내 뜸([건너뛰기]), 업데이트 뒤 앱 로그 한 번 {log[0][0]} '{lines[0]}'({when}), "
                f"저장된 선택 {choices}, 위젯 {' / '.join(widget)}")

    steps("3. 지금 빌드 첫 실행",
          "첫 실행 안내가 한 번 뜸. 업데이트 뒤 앱이 위젯 하나를 '전체'(*)로 한 번 채움(앱을 여는 것보다 먼저일 수 있음), "
          "위젯에 두 사람 항목", first_start)

    # 4. the widget placed before the update records for Mom (without a choice it would record nothing, SPEC-009)
    def tap_mom() -> str:
        if not tap_widget_item(BP):
            raise RuntimeError(f"{BP} not on the widget")
        nodes = home_nodes(lambda ns: has_desc(ns, f"{MOM} {BP}, 복용함"), 15)
        r.shot("widget-two-people")
        save_ui(r, "widget-two-people")
        widget = widget_spoken(nodes)
        state = read_state()
        if state.active_records != 2 or set(state.sources) != {"WIDGET"}:
            raise RuntimeError(f"active records {state.active_records}, sources {state.sources}")
        if not has_desc(nodes, f"{MOM} {BP}, 복용함") or has_text(nodes, RECONFIGURE):
            raise RuntimeError(f"widget {widget}, reconfigure line {has_text(nodes, RECONFIGURE)}")
        if our_widget_ids() != [int(seen["widget_id"])]:
            raise RuntimeError(f"widgets now {our_widget_ids()}, placed before the update {seen['widget_id']}")
        return (f"업데이트 전에 놓은 위젯(id {seen['widget_id']}): 활성 판정 {state.active_records}, "
                f"source {state.sources}; {' / '.join(widget)}")

    steps("4. 업데이트 전에 놓은 위젯에서 Mom BPpill 탭",
          "기록 2건(둘 다 WIDGET), 위젯이 'Mom BPpill, 복용함, …'을 보임", tap_mom)

    # 5. control: without the filled-in choice the same widget asks to be set up again, and nothing fills it again
    def control() -> str:
        clear_log()
        files = wipe_widget_choices()
        nodes = home_nodes(lambda ns: has_text(ns, RECONFIGURE))
        r.shot("widget-wiped")
        save_ui(r, "widget-wiped")
        lines = filled_lines()
        choices = stored_choices()
        if not has_text(nodes, RECONFIGURE):
            raise RuntimeError(f"no '{RECONFIGURE}' after the state was deleted: {widget_spoken(nodes)}")
        if lines:
            raise RuntimeError(f"WidgetChoices filled again: {lines}")
        if any(c != "-" for c in choices):
            raise RuntimeError(f"a choice came back: {choices}")
        return f"지운 상태 파일 {files}; 위젯 '{RECONFIGURE}', 다시 채운 앱 로그 없음, 저장된 선택 {choices or '없음'}"

    steps("5. 대조: 선택을 지운 뒤", f"같은 위젯이 '{RECONFIGURE}'를 보이고, 앱이 다시 채우지 않음(설치마다 한 번)", control)


def short(value: str | None) -> str:
    return value[:6] if value else "-"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--old", type=Path, required=True, help="debug APK of the old build")
    parser.add_argument("--new", type=Path, required=True, help="debug APK of this commit, a bigger version number")
    parser.add_argument("--old-commit", default="", help="the commit the old APK was built from, for the report")
    parser.add_argument("--old-label", default="DidITake",
                        help="the old build's app name, as the launcher's widget list shows it (필받음 from TASK-045 on)")
    parser.add_argument("--new-commit", default="", help="the commit the new APK was built from, for the report")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()  # also counts this boot, so become_root waits for the emulator's own commands after it
    api = api_level()
    r = Report(args.out, api, f"옛 빌드에서 업데이트 확인 API {api}")
    steps = Steps(r)
    try:
        run(r, steps, args.old, args.new, args.old_commit, args.old_label)
    except Exception as exc:
        traceback.print_exc()
        r.check("업데이트 확인 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        model = sh("getprop ro.product.model", check=False).strip()
        r.note(f"기기: API {api}, {model}, 옛 빌드 {args.old.name} ({args.old_commit or '?'}) → 지금 빌드 {args.new.name} "
               f"({args.new_commit or '?'})")
        r.write()
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        (args.out / f"api{api}-logcat.txt").write_text("\n".join(LOG_PARTS), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
