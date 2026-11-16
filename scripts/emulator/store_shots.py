"""Store screenshots for Google Play (TASK-037) and the launcher's name and icon (TASK-045), taken from the app with
example data on one emulator: API 34 on a Pixel 2 (1080x1920, 9:16). Run by .github/workflows/store-shots.yml.

Why: the listing needs phone screenshots of the real app, at most twice as long as wide and 1080x1920 or more for
the recommended size (docs/release/store-listing.md 4.1). The QA captures are 1080x2340, 2.17 times as long (4.2), and
nobody on the team has an Android phone yet, so they come from the emulator as the QA captures do.

Day 0 is the last Friday on or before the run's date (Asia/Seoul), so that the weekly item (금요일) is due on day 0.

Flow:
   0. Setup in English, as the other scripts (the launcher steps read its English words): clock on day -31 09:00,
      animations and heads-up popups off, a 24-hour clock, the debug APK installed, notifications allowed, and exact
      alarms allowed (appops), as a person does from the app's [허용하기]: without them the first screen opens with
      "알림이 늦을 수 있어요" (first run, 2026-11-16) and the reminder may ring minutes late.
   1. The app opened (the intro passed with [건너뛰기]) and the example items made by the debug receiver (SEED), in
      this order: 나 비타민D (매일 점심, 알림 12:30); 엄마 (주황) 아침 혈압약 (매일 아침, 08:00), 저녁약 (매일 저녁, 19:00)
      and 골다공증약 (주 1회 금요일, 09:00). Names on the lock screen on (NAMES_ON_LOCK, SPEC-034): with names hidden,
      the default, a separate "복용 시간이에요" stands in for the reminders on the lock screen and shows in the shade too.
   2. The widget dragged from the launcher's widget list (its picture shows 필받음's row with the icon, TASK-045), 전체
      picked, made as wide as the home screen and one row taller, so that its four tiles show two in a row. This
      launcher has five columns, where four cells are 296 dp and the widget puts one tile in a row (WidgetLayout.plan);
      a phone with four columns gives a 4x2 widget that width. It lands on the grid's last row, so it grows upwards.
   3. The phone in Korean (persist.sys.locale and a reboot). The emulator gives the phone the computer's time zone
      (UTC) and clock again when it boots (first run), so the zone is set again and the clock goes to day 0 12:29:30:
      the app takes the clock going back (RR-81) and rings 비타민D's 12:30 reminder at 12:30, or when the alarm
      broadcast comes after 12:30.
   4. A month of history written by the debug receiver (RECORD) as SCR-007 writes a past day (see history()), and the
      database counted against it. Day 0's 아침 혈압약 and 골다공증약 are among it, so that their reminders, which
      the clock change and the alarm brought, go (N-13) and only 비타민D's is left in the shade.
   5. System UI demo mode (clock, full battery and signal, no notification icons); other apps' notifications snoozed.
   6. Store 2: the notification shade with 비타민D's reminder and its three buttons. Its [먹었어] is then tapped.
   7. Store 1: the home screen with the widget, 3 of the 4 taken. Store 3: the app's first screen (SCR-004). Store 4:
      지난 기록 (SCR-008). Store 5: 아침 혈압약's form (SCR-006), scrolled to "누구 약인가요?" so that the reminder
      rows (알림 받기, 첫 알림 시각) show. Store 6: the widget with text at 200%.
   8. The app list with 필받음's icon (TASK-045). Text back at 100%, demo mode off.

Each store picture is saved as store-N-name.png, the screen as captured, with its screen dump. The 24-bit copies in
docs/release/listing/ko-KR/images/phoneScreenshots/ are made from them (the screen is captured with an alpha channel,
which Play does not take). Every step is also checked against the screen dump or the database; a failed step is one
failed row and the run goes on, so one run shows everything that went wrong.
"""

from __future__ import annotations

import argparse
import random
import subprocess
import sys
import time
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from backup_restore_check import (
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
from explore import LOG_PARTS, Steps, exact, font_scale, night, phone_now, reboot, set_clock, set_zone, tile
from release_check import go_home, place_widget, save_ui
from reminder_check import (
    ACTION_SEED,
    REMINDER_ALARM,
    REMINDER_RECEIVER,
    TAKEN,
    TITLE_END,
    collapse_shade,
    debug,
    expand_shade,
    names_on_lock,
    query,
    wait_posted,
)
from screens_check import CELL_ENDS, open_history, to_home, wait_screen
from screens2_check import widget_host, widget_page
from widget_size_check import resize

ZONE = ZoneInfo("Asia/Seoul")
SIZE = (1080, 1920)  # the Pixel 2 the workflow makes
MOM = "엄마"  # the second person; the first is 나 (ManagePeople.DEFAULT_PERSON_NAME)
MOM_COLOR = 1  # PersonColors.PALETTE[1], 주황
VITAMIN, BP, EVENING, BONE = "비타민D", "아침 혈압약", "저녁약", "골다공증약"
ITEMS = (VITAMIN, BP, EVENING, BONE)
WEEKDAY = "FRIDAY"  # 골다공증약's day; day 0 is one
DAYS = 31  # days of history before day 0
BATCH = 24  # RECORD entries per broadcast
ACTION_RECORD = f"{PKG}.debug.RECORD"
DEMO = "com.android.systemui.demo"
SHADE_IDS = (":id/expand_button",)
EDGE = 8  # px: where the widget's left handle is dragged, the home screen's left edge
TOUCH_SLOP = 21  # px: 8 dp at 420 dpi, the part of a drag a list takes before it scrolls


def day_zero(today: date) -> date:
    """The last Friday on or before `today`."""
    return today - timedelta(days=(today.weekday() - 4) % 7)


def history(day0: date) -> list[str]:
    """RECORD entries (`<name>|<yyyy-MM-ddTHH:mm>|<kind>`), oldest first, the same in every run (random.Random(37)):

    - 아침 혈압약 07:40~08:25 (day -31 09:10, once the app was set up); 건너뜀 on day -11, nothing on day -20
    - 비타민D 12:10~13:20; nothing on days -4, -17 and -23
    - 저녁약 18:50~19:50; 건너뜀 on days -2 and -15
    - 골다공증약, whose week runs from Friday to Thursday: day -31 (a Tuesday, the first week) 10:00, the Fridays -28,
      -21 and -7 at 09:00~09:40, and for the week of day -14 the Saturday after, 10:30
    - day 0: 아침 혈압약 08:05 and 골다공증약 09:12. 비타민D comes from its reminder's [먹었어]; 저녁약 is still to
      come

    A day with nothing shows as 빠짐 in 지난 기록; a 건너뜀 of a past day is stored at the day's start (P-3)."""
    rnd = random.Random(37)

    def between(d: date, start: str, end: str) -> str:
        h1, m1 = map(int, start.split(":"))
        h2, m2 = map(int, end.split(":"))
        minute = rnd.randint(h1 * 60 + m1, h2 * 60 + m2)
        return f"{d.isoformat()}T{minute // 60:02d}:{minute % 60:02d}"

    out = []
    for k in range(DAYS, 0, -1):
        d = day0 - timedelta(days=k)
        if k == 11:
            out.append(f"{BP}|{d.isoformat()}T08:00|SKIPPED")
        elif k == DAYS:
            out.append(f"{BP}|{d.isoformat()}T09:10|TAKEN")
        elif k != 20:
            out.append(f"{BP}|{between(d, '07:40', '08:25')}|TAKEN")
        if k not in (4, 17, 23):
            out.append(f"{VITAMIN}|{between(d, '12:10', '13:20')}|TAKEN")
        if k in (2, 15):
            out.append(f"{EVENING}|{d.isoformat()}T19:00|SKIPPED")
        else:
            out.append(f"{EVENING}|{between(d, '18:50', '19:50')}|TAKEN")
    for k, at in ((31, "10:00"), (28, None), (21, None), (13, "10:30"), (7, None)):
        d = day0 - timedelta(days=k)
        out.append(f"{BONE}|{d.isoformat()}T{at}|TAKEN" if at else f"{BONE}|{between(d, '09:00', '09:40')}|TAKEN")
    out.append(f"{BP}|{day0.isoformat()}T08:05|TAKEN")
    out.append(f"{BONE}|{day0.isoformat()}T09:12|TAKEN")
    return out


def tally(entries: list[str]) -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = {}
    for entry in entries:
        name, _, kind = entry.split("|")
        out[(name, kind)] = out.get((name, kind), 0) + 1
    return out


def stored() -> dict[tuple[str, str], int]:
    """Active judgments in the database by item name and kind."""
    rows = query("SELECT i.name, d.kind, COUNT(*) FROM dose_record d JOIN intake i ON i.id = d.intake_id "
                 "WHERE d.active = 1 GROUP BY i.name, d.kind")
    return {(str(name), str(kind)): int(count) for name, kind, count in rows}


def describe_tally(counts: dict[tuple[str, str], int]) -> str:
    return ", ".join(f"{name} {kind} {count}" for (name, kind), count in sorted(counts.items()))


def seed_item(name: str, start: str, slot: str | None = None, weekly: str | None = None,
              person: str | None = None) -> str:
    """One item with its reminder on, made by the debug receiver as the item screen makes it."""
    extras = f"--es names '{name}' --es start {start}"
    if slot:
        extras += f" --es slot {slot}"
    if weekly:
        extras += f" --es weekly {weekly}"
    if person:
        extras += f" --es person '{person}' --ei color {MOM_COLOR}"
    return debug(ACTION_SEED, extras, "seeded")


def record(entries: list[str]) -> int:
    for i in range(0, len(entries), BATCH):
        part = entries[i:i + BATCH]
        debug(ACTION_RECORD, f"--es records '{';'.join(part)}'", f"recorded {len(part)}")
    return len(entries)


def demo(command: str, extras: str = "") -> None:
    sh(f"am broadcast -a {DEMO} -e command {command} {extras}".strip(), check=False)


def demo_on() -> None:
    """System UI demo mode: what the listing rules ask of the status bar (store-listing.md 4.3)."""
    sh("settings put global sysui_demo_allowed 1", check=False)
    demo("enter")
    demo("battery", "-e level 100 -e plugged false -e powersave false")
    demo("network", "-e wifi show -e level 4 -e fully true")
    demo("network", "-e mobile show -e datatype none -e level 4 -e fully true")
    demo("notifications", "-e visible false")
    demo("status", "-e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e mute hide "
         "-e speakerphone hide -e tty hide -e eri hide -e zen hide")
    demo_clock()


def demo_clock() -> str:
    """The demo clock at the phone's time, so that the bar agrees with the times on the screen."""
    hhmm = sh("date +%H%M").strip()
    demo("clock", f"-e hhmm {hhmm}")
    time.sleep(1)
    return hhmm


def snooze_others() -> list[str]:
    """Snoozes the other apps' notifications for an hour, so that the shade shows only the app's reminder."""
    snoozed = []
    for line in sh("cmd notification list", check=False).splitlines():
        key = line.strip()
        parts = key.split("|")
        if len(parts) < 3 or parts[1] == PKG:
            continue
        sh(f"cmd notification snooze --for 3600000 '{key}'", check=False)
        snoozed.append(parts[1])
    return snoozed


def reminder_button(name: str, label: str, tries: int = 4) -> Node | None:
    """The `label` button of `name`'s reminder in the open shade, the reminder expanded when its buttons are hidden.
    Never taps the reminder itself: that opens the app. reminder_check.notification_button tells the expander from
    the collapser by English words; here the language is Korean, so it takes the expander next to the title."""
    head_text = f"{name}{TITLE_END}"
    for _ in range(tries):
        nodes = dump_ui()
        head = next((n for n in nodes if head_text in n.text), None)
        if head is None:
            return None
        buttons = [n for n in nodes if n.text == label and n.bounds[1] > head.bounds[1]]
        if buttons:
            return min(buttons, key=lambda n: n.bounds[1])
        expanders = [n for n in nodes if n.rid.endswith(SHADE_IDS)]
        if not expanders:
            return None
        tap(min(expanders, key=lambda n: abs(n.center[1] - head.center[1])))
        time.sleep(1)
    return None


def in_widget(nodes: list[Node]) -> list[str]:
    """The example items whose tiles show on the widget (a tile reads `엄마 아침 혈압약, …`)."""
    host = widget_host(nodes)
    if host is None:
        return []
    x1, y1, x2, y2 = host.bounds
    inside = [n for n in nodes if x1 <= n.center[0] <= x2 and y1 <= n.center[1] <= y2]
    return [name for name in ITEMS if any(n.desc.split(", ")[0].endswith(name) for n in inside)]


def run(r: Report, steps: Steps, apk: Path) -> None:
    day0 = day_zero(datetime.now(ZONE).date())
    entries = history(day0)
    r.note(f"0일: {day0.isoformat()} ({'월화수목금토일'[day0.weekday()]}요일), 기록 {len(entries)}건")

    def at(day: int, hour: int, minute: int = 0, second: int = 0) -> datetime:
        d = day0 + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, second, tzinfo=ZONE)

    def store(n: int, name: str) -> Path:
        """One store picture as captured, with its dump; the report keeps the order."""
        path = r.out / f"store-{n}-{name}.png"
        demo_clock()
        path.write_bytes(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
        save_ui(r, f"store-{n}-{name}")
        r.note(f"스토어 {n}: {path.name}")
        return path

    # 0. Setup
    def setup() -> str:
        become_root()
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {setting} 0")
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("settings put global heads_up_notifications_enabled 0", check=False)
        sh("settings put system time_12_24 24", check=False)
        sh("svc power stayon true", check=False)
        sh("settings put system screen_off_timeout 1800000", check=False)
        size = sh("wm size").strip().splitlines()[-1]
        zone = set_zone()
        clock = set_clock(at(-DAYS, 9))
        font_scale("1.0")
        night(False)
        uninstall()
        install(apk)
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
        sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow", check=False)
        sh("cmd appops write-settings", check=False)  # kept through the reboot of step 3
        exact = sh(f"appops get {PKG} SCHEDULE_EXACT_ALARM", check=False).strip()
        if f"{SIZE[0]}x{SIZE[1]}" not in size:
            raise RuntimeError(f"the screen is not {SIZE[0]}x{SIZE[1]}: {size}")
        return f"{size}, 시간대 {zone}, 시계 {clock}, 정확 알람 {exact}"

    if not steps("0. 준비 (영어, -31일 09:00)", f"화면 {SIZE[0]}x{SIZE[1]}, 앱 설치, 알림·정확 알람 허용", setup):
        return

    # 1. The items
    def items() -> str:
        launch_app()
        ids = [
            seed_item(VITAMIN, "12:30", slot="NOON"),
            seed_item(BP, "08:00", slot="MORNING", person=MOM),
            seed_item(EVENING, "19:00", slot="EVENING", person=MOM),
            seed_item(BONE, "09:00", weekly=WEEKDAY, person=MOM),
        ]
        names_on_lock(True)
        go_home()
        launch_app()
        nodes = wait_screen(lambda ns: all(any(n.desc.split(", ")[0].endswith(i) for n in ns) for i in ITEMS), 20)
        shown = [i for i in ITEMS if any(n.desc.split(", ")[0].endswith(i) for n in nodes)]
        if len(shown) != len(ITEMS):
            raise RuntimeError(f"the first screen shows {shown}")
        return f"{len(ids)}개 만듦, 첫 화면에 {', '.join(shown)}"

    if not steps("1. 예시 항목 (디버그 수신기 SEED)", "나 1개·엄마 3개, 첫 화면에 넷 모두, 잠금 화면 이름 표시", items):
        return

    # 2. The widget
    def widget() -> str:
        picker = place_widget(r, "widget", pick="전체")
        wide = resize(r, "widget-wide", "left", lambda h, p: (EDGE, p[1]))
        tall = resize(r, "widget-tall", "top", lambda h, p: (p[0], p[1] - (h.bounds[3] - h.bounds[1]) // 2 - 20))
        nodes = widget_page(lambda ns: len(in_widget(ns)) == len(ITEMS), 30)
        shown = in_widget(nodes)
        host = widget_host(nodes)
        r.shot("widget-grown")
        save_ui(r, "widget-grown")
        if len(shown) != len(ITEMS):
            raise RuntimeError(f"the widget shows {shown} ({wide}; {tall})")
        if host is None or host.bounds[2] - host.bounds[0] < SIZE[0] * 4 // 5:
            raise RuntimeError(f"the widget is not as wide as the home screen: {host.bounds if host else None}")
        return f"대상자 고르기 {'열림' if picker else '안 열림'}, {wide}, {tall}, 위젯 {host.bounds}, 칸 {', '.join(shown)}"

    steps("2. 위젯 놓기, 화면 폭만큼·한 줄 더 (TASK-045 위젯 목록)", "전체를 고른 위젯에 칸 넷, 위젯 폭이 화면의 4/5 이상",
          widget)

    # 3. Korean, day 0
    def korean() -> str:
        sh("setprop persist.sys.locale ko-KR")
        reboot()
        locale = sh("getprop persist.sys.locale").strip()
        booted = sh("date '+%Y-%m-%d %H:%M %Z'").strip()
        zone = set_zone()
        clock = set_clock(at(0, 12, 29, 30))
        due = int(at(0, 12, 30, 5).timestamp())
        deadline = time.time() + 120
        while phone_now() < due and time.time() < deadline:
            time.sleep(2)
        # The alarm rings at 12:30 by itself; this one covers an alarm the clock change left armed for later. A second
        # collect right after the first leaves its reminders up (BUG-007, RR-94).
        sh(f"am broadcast -n {REMINDER_RECEIVER} -a {REMINDER_ALARM}", check=False)
        if locale != "ko-KR":
            raise RuntimeError(f"locale {locale}")
        if zone != "Asia/Seoul":
            raise RuntimeError(f"time zone {zone}")
        return f"언어 {locale}, 다시 켠 뒤 {booted}, 시간대 {zone}, 시계 {clock}"

    if not steps("3. 한국어로 다시 켜기, 0일 12:29:30", "ko-KR, Asia/Seoul, 0일 12:29", korean):
        return

    # 4. The history
    def write_history() -> str:
        count = record(entries)
        want, have = tally(entries), stored()
        if have != want:
            raise RuntimeError(f"the database has {describe_tally(have)}; wanted {describe_tally(want)}")
        return f"{count}건 씀: {describe_tally(have)}"

    steps("4. 지난 한 달 기록 (디버그 수신기 RECORD)", "DB의 판정 수가 쓴 것과 같음", write_history)

    # 5. The status bar and the shade
    def bar() -> str:
        demo_on()
        snoozed = snooze_others()
        return f"데모 모드, 다른 앱 알림 {len(snoozed)}개 미룸" + (f" ({', '.join(sorted(set(snoozed)))})" if snoozed else "")

    steps("5. 상태 표시줄 정리 (데모 모드)", "시계·배터리·신호 고정, 다른 알림 없음", bar)

    # 6. Store 2: the reminder
    def reminder() -> str:
        want = f"{VITAMIN}{TITLE_END}"
        shown = wait_posted(lambda p: any(x.title == want for x in p), 90)
        if not any(x.title == want for x in shown):
            sh(f"am broadcast -n {REMINDER_RECEIVER} -a {REMINDER_ALARM}", check=False)
            shown = wait_posted(lambda p: any(x.title == want for x in p), 60)
        if not any(x.title == want for x in shown):
            raise RuntimeError(f"no '{want}' posted: {[x.title for x in shown]}")
        others = [x.title for x in shown if x.title != want]
        expand_shade()
        button = reminder_button(VITAMIN, TAKEN)
        if button is None:
            collapse_shade()
            expand_shade()
            button = reminder_button(VITAMIN, TAKEN)
        if button is None:
            save_ui(r, "shade")
            raise RuntimeError(f"no [{TAKEN}] under '{want}' in the shade")
        store(2, "reminder")
        tap(button)
        time.sleep(3)
        collapse_shade()
        taken = stored().get((VITAMIN, "TAKEN"), 0)
        if taken != tally(entries).get((VITAMIN, "TAKEN"), 0) + 1:
            raise RuntimeError(f"{VITAMIN} TAKEN {taken} after [{TAKEN}]")
        return (f"'{want}'와 [{TAKEN}]을 찍고 누름, {VITAMIN} 먹음 {taken}건; 이 앱의 다른 알림 "
                + (", ".join(others) if others else "없음"))

    steps("6. 스토어 2: 펼친 복용 알림", f"{VITAMIN} 알림의 버튼 셋, [{TAKEN}]으로 기록됨", reminder)

    # 7. Store 1, 3, 4, 5
    def home_widget() -> str:
        nodes = widget_page(lambda ns: len(in_widget(ns)) == len(ITEMS), 30)
        shown = in_widget(nodes)
        if len(shown) != len(ITEMS):
            raise RuntimeError(f"the widget shows {shown}")
        host = widget_host(nodes)
        texts = [n.text for n in nodes if n.text and host is not None and host.bounds[1] <= n.center[1] <= host.bounds[3]]
        store(1, "widget")
        return f"칸 {', '.join(shown)}; 위젯 글 {' / '.join(texts)}"

    steps("7. 스토어 1: 홈 화면 위젯", "칸 넷, 셋 먹음", home_widget)

    def first_screen() -> str:
        go_home()
        launch_app()
        to_home()
        nodes = wait_screen(lambda ns: all(any(n.desc.split(", ")[0].endswith(i) for n in ns) for i in ITEMS), 20)
        missing = [i for i in ITEMS if not any(n.desc.split(", ")[0].endswith(i) for n in nodes)]
        if missing:
            raise RuntimeError(f"the first screen lacks {missing}")
        store(3, "today")
        return "항목 넷"

    steps("8. 스토어 3: 앱 첫 화면 (SCR-004)", "오늘과 이번 주, 항목 넷", first_screen)

    def past() -> str:
        open_history()
        nodes = wait_screen(lambda ns: sum(1 for n in ns if n.desc.endswith(CELL_ENDS)) >= 8, 20)
        cells = [n for n in nodes if n.desc.endswith(CELL_ENDS)]
        if len(cells) < 8:
            raise RuntimeError(f"{len(cells)} cells in 지난 기록")
        store(4, "history")
        return f"칸 {len(cells)}개"

    steps("9. 스토어 4: 지난 기록 (SCR-008)", "날짜 줄에 칸", past)

    def form() -> str:
        to_home()
        node = tile(BP)
        if node is None:
            raise RuntimeError(f"no tile of {BP}")
        tap(node)
        nodes = wait_screen(lambda ns: any(n.text == "항목 수정" for n in ns), 15)
        if not any(n.text == "항목 수정" for n in nodes):
            raise RuntimeError("the edit form did not open")
        if not any(BP in n.text for n in nodes if "EditText" in n.cls):
            raise RuntimeError(f"the form's name is not {BP}")
        # Up to "누구 약인가요?", so that the reminder rows come into view; a slow drag, so that the list does not fling
        who = next((n for n in nodes if n.text == "누구 약인가요?"), None)
        scroller = next((n for n in nodes if n.cls.endswith("ScrollView")), None)
        if who is not None and scroller is not None:
            lift = who.bounds[1] - scroller.bounds[1] - 24 + TOUCH_SLOP
            x = SIZE[0] // 2
            sh(f"input swipe {x} {scroller.bounds[3] - 40} {x} {scroller.bounds[3] - 40 - lift} 2000")
            time.sleep(2)
            nodes = dump_ui()
        rows = [t for t in ("누구 약인가요?", "얼마나 자주?", "언제 먹나요?", "알림 받기", "첫 알림 시각")
                if any(n.text == t for n in nodes)]
        if "알림 받기" not in rows:
            raise RuntimeError(f"the reminder rows are not in view: {rows}")
        store(5, "form")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        to_home()
        return f"{BP} 수정 화면, 보이는 줄 {', '.join(rows)}"

    steps("10. 스토어 5: 항목 수정 (SCR-006)", f"{BP}의 대상자·주기·시간대와 알림 받기·첫 알림 시각", form)

    # 8. Store 6 and the app list
    def big_text() -> str:
        go_home()
        font_scale("2.0")
        time.sleep(5)
        nodes = widget_page(lambda ns: bool(in_widget(ns)), 30)
        shown = in_widget(nodes)
        if not shown:
            raise RuntimeError("no tile on the widget at text 200%")
        store(6, "widget-200")
        return f"보이는 칸 {', '.join(shown)}"

    steps("11. 스토어 6: 글자 200%의 위젯", "큰 글씨의 칸", big_text)
    font_scale("1.0")

    def app_list() -> str:
        go_home()
        w, h = SIZE
        sh(f"input swipe {w // 2} {h * 4 // 5} {w // 2} {h // 5} 400")
        time.sleep(2)
        label = None
        for _ in range(4):
            label = exact("필받음", 3)
            if label is not None:
                break
            sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 1500")
            time.sleep(1)
        r.shot("app-list")
        save_ui(r, "app-list")
        go_home()
        if label is None:
            raise RuntimeError("필받음 not in the app list")
        return f"앱 목록에 필받음 {label.bounds}"

    steps("12. 앱 목록의 이름과 아이콘 (TASK-045)", "필받음과 아이콘", app_list)
    demo("exit")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    api = api_level()
    r = Report(args.out, api, f"스토어 스크린샷 API {api}")
    steps = Steps(r)
    try:
        run(r, steps, args.apk)
    except Exception as exc:
        traceback.print_exc()
        r.check("스토어 스크린샷 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        r.write()
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        (args.out / f"api{api}-logcat.txt").write_text("\n".join(LOG_PARTS), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
