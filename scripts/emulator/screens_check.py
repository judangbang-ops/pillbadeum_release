"""R2's screens on one emulator (API 34), each step checked and photographed: the widget's person picker, the people
screen, judgment fixing, past records, the clock warning and the phone's own time zone.

Why: the R2 screen tasks in docs/08-wbs/wbs.md (TASK-012, 013, 015, 016, 018, 019, 021, 022, 043) end with emulator
captures, and nobody on the team has an Android phone yet (2026-11-12), so 동희님 sees the screens through these
pictures. explore.py walks first use; this script checks what each task's end condition names, and keeps the screen
dump (api34-name.xml) next to the picture, so the sentences TalkBack reads can be checked too (content-desc).

Flow (clock set in Asia/Seoul; day 0 is the run's date):
   1. Install and open the app (the intro is passed with [건너뛰기]; step 17 shows it).
   2. The widget placed with one person: no person picker, the widget shows everyone (TASK-012, REQ-018).
   3. Vitamin (나, 매일 아침): no person on the tile or the widget, nor in what TalkBack reads (TASK-015).
   4. 설정 > 대상자 관리: the list, [대상자 추가] with the one-phone note, Mom added, her color changed (TASK-021).
   5. BPpill (Mom, 매일 저녁): the person in front of the names and in TalkBack's sentences; the widget placed with one
      person still shows everyone (TASK-015, TASK-012).
   6. The widget's choice deleted, as after a restore: with two people only "위젯을 다시 설정해 주세요" (TASK-012).
   7. That line opens the person picker (SCR-002); Mom picked: only Mom's item and count (TASK-012).
   8. Mom archived: the question, 보관됨, the widget's archived line (TASK-021, TASK-012).
   9. Text 200% at the Pixel 5's width (393dp): [되살리기] on the screen, clear of the name (TASK-021).
  10. Mom brought back: her widget shows her item again (TASK-021).
  11. Day 0: Vitamin recorded in the app.
  12. Day 1, 09:00: BPpill's [⋯] opens SCR-007 (TASK-018): the buttons with no judgment, [건너뜀], the buttons of a skip.
      The skipped tile tapped on the widget: the skip message and no new record (TASK-013, S-3).
      Vitamin's time picked (08:00): the buttons of a dose.
  13. Day 2, 08:40: 지난 기록 (TASK-019): every state, the person filter, a missed day recorded at 21:00 and a skip
      changed to a dose (SCR-007 of past days, TASK-018).
  14. The clock moved back after a dose (TASK-016, TASK-018, TASK-019, supplement C): the warning band, SCR-007's two
      buttons, the warning still there once the clock is right again (RR-64), the history cell, [그대로 두기].
  15. Phone in Europe/London (TASK-043): times on the phone's clock, dates still in Asia/Seoul. Then the base zone
      changed in 설정 (P-5): a day that ended before it opens as SCR-007#locked, with no button (TASK-018).
  16. Mom archived again, text 200% and the display density set for a 360dp width: [되살리기] under the name (TASK-021).
  17. Installed again: the intro, and its [위젯 추가] asks the launcher to place the widget (TASK-022).

One widget at a time: the Pixel launcher's first page holds one 4x2 widget next to its own icons, so the steps change
the widget's person through its own "다시 설정" line instead of placing a second one. Uninstalling the app (step 17)
takes the widget off the home screen.

Names are typed in English (`adb shell input text` cannot type Korean). The debug APK is used: the database is read as
root, and the debug receiver (androidApp/src/debug) redraws the widgets the way a write does.

Writes report.md, screenshots (api34-NN-name.png), screen dumps and steps.json (each step's result with the numbers of
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
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from backup_restore_check import (
    ACTIVITY,
    DATA,
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
    screen_has,
    sh,
    tap,
    uninstall,
    wait_boot,
)
from explore import (
    BP,
    KEYBOARD_NOTES,
    LOG_PARTS,
    ME,
    MOM,
    VITAMIN,
    ZONE,
    Steps,
    day_label,
    ensure_home,
    exact,
    font_scale,
    message_line,
    night,
    phone_now,
    row_button,
    scroll_down,
    set_clock,
    set_zone,
    tap_desc,
    tap_exact,
    tap_scrolled,
    tile,
    type_into,
)
from release_check import PICK_TITLE, go_home, on_screen, place_widget, save_ui, screen_size
from reminder_check import ACTION_NAMES_ON_LOCK, SEED, query, zone_to

# Texts of the app (shared/src/commonMain/.../status/Labels.kt)
EMPTY_WIDGET = "앱에서 복용 항목을 추가해 주세요"  # Labels.EMPTY_WIDGET
RECONFIGURE = "위젯을 다시 설정해 주세요"  # Labels.RECONFIGURE
RECONFIGURE_ARCHIVED = "대상자가 보관됐어요. 위젯을 다시 설정해 주세요"  # Labels.RECONFIGURE_ARCHIVED
ONE_PHONE = "한 약은 한 휴대폰에서 기록해 주세요"  # the start of Labels.onePhoneFor
CLOCK_BAND = "휴대폰 시계와 맞지 않았어요"  # Labels.clockWarning
CLOCK_SPOKEN = ", 시계 확인 필요"  # the end of Labels.spoken / historySpoken with a clock warning
SKIP_TAP = "건너뜀으로 기록됨"  # Labels.recordMessage for a skipped tile
GREEN = "초록"  # Labels.COLOR_NAMES[2]
SEOUL_NOTE = "날짜는 기준 시간대(Asia/Seoul)로 셈해요."  # HomeScreen, when the phone is in another zone
LOCKED = "설정을 바꾸기 전의 기록은 고칠 수 없어요"  # Labels.LOCKED, the band of SCR-007#locked
LOCKED_NOTE = "하루 시작 시각이나 기준 시간대를 바꾸기 전에 끝난 날이에요."  # Labels.LOCKED_NOTE
ZONE_ASK = "기준 시간대를 바꿀까요?"  # SettingsScreen, the P-5 question before the base zone changes
LONDON = "Europe/London"
SEOUL = "Asia/Seoul"
# What TalkBack reads at the end of a history cell (Labels.historySpoken)
CELL_ENDS = ("먹음", "아직", "건너뜀", "빠짐", CLOCK_SPOKEN)
# Every button SCR-007 can show (JudgeScreen); none is there on a #locked cell
JUDGE_ACTIONS = ("지금 먹었어", "먹은 시각 고르기", "건너뜀", "먹은 시각 고치기", "기록 취소", "먹었어로 바꾸기", "건너뜀 취소",
                 "그대로 두기")

# Glance keeps each widget's state, the person choice among it, in files/datastore/appWidget-<id>.preferences_pb
DATASTORE = f"{DATA}/files/datastore"
CHOICE_KEY = b"person_choice"  # WidgetChoices.KEY; the value is "*" for 전체 (WidgetChoice.EVERYONE) or a person id

# The launcher's sheet for a widget the app asks to place (Launcher3 AddItemActivity): its button to add it
PIN_BUTTONS = ("add to home screen", "add automatically", "add")

SIZE: tuple[int, int] = (0, 0)


# ------------------------------------------------------------------------------------------------
# Screen helpers on top of explore.py


def size() -> tuple[int, int]:
    global SIZE
    if SIZE == (0, 0):
        SIZE = screen_size()
    return SIZE


def desc_is(text: str):
    return lambda n: n.desc == text


def desc_starts(prefix: str):
    return lambda n: n.desc.startswith(prefix)


def text_is(text: str):
    return lambda n: n.text == text


def seek(match, what: str, tries: int = 6) -> Node:
    """The first node `match` accepts that is fully on screen, scrolling the screen down to find it."""
    _, h = size()
    for attempt in range(tries):
        node = next((n for n in dump_ui() if match(n) and on_screen(n, h)), None)
        if node is not None:
            return node
        if attempt < tries - 1:
            scroll_down()
    raise RuntimeError(f"{what} not found after scrolling")


def scroll_top(times: int = 4) -> None:
    w, h = size()
    for _ in range(times):
        sh(f"input swipe {w // 2} {h // 3} {w // 2} {h * 5 // 6} 300")
    time.sleep(1)


def wait_screen(ok, timeout: float = 20) -> list[Node]:
    """Reads the screen until `ok(nodes)` holds or `timeout` passes, and returns the nodes read last."""
    end = time.time() + timeout
    while True:
        nodes = dump_ui()
        if ok(nodes) or time.time() >= end:
            return nodes
        time.sleep(1)


def has_desc(nodes: list[Node], prefix: str) -> bool:
    return any(n.desc.startswith(prefix) for n in nodes)


def has_text(nodes: list[Node], text: str) -> bool:
    return any(n.text == text for n in nodes)


def spoken(nodes: list[Node], name: str) -> list[str]:
    """What TalkBack reads for `name`'s tile (app or widget): descriptions whose first part ends with the name."""
    return [n.desc for n in nodes if ", " in n.desc and n.desc.split(", ")[0].endswith(name)]


def buttons(labels: tuple[str, ...]) -> dict[str, bool]:
    """Which of these button texts are on the screen now."""
    texts = {n.text for n in dump_ui()}
    return {label: label in texts for label in labels}


def home_nodes(ok, timeout: float = 20) -> list[Node]:
    """The phone's home screen (the launcher with our widget), read until `ok(nodes)` holds."""
    go_home()
    return wait_screen(ok, timeout)


def at_home(nodes: list[Node]) -> bool:
    """The app's first screen: [지난 기록] in its top bar and no [뒤로]/[닫기]. HOME_MARK alone is not enough here:
    SCR-008's title is also 지난 기록, and the other screens open from the first one have [뒤로] or [닫기]."""
    return has_text(nodes, HOME_MARK) and not any(n.desc in ("뒤로", "닫기") for n in nodes)


def app_in_front() -> bool:
    lines = sh("dumpsys activity activities", check=False).splitlines()
    return any("ResumedActivity" in line and PKG in line for line in lines)


def to_home() -> None:
    """Back to the app's first screen: the app brought to the front (`am start` resumes the screen it was on), then the
    screens' own [뒤로]/[닫기]. Only when that does not get there is the app stopped and started again."""
    for _ in range(6):
        nodes = dump_ui()
        if at_home(nodes):
            return
        back = next((n for n in nodes if n.desc in ("뒤로", "닫기")), None)
        if back is not None:
            tap(back)
        elif not app_in_front():
            sh(f"am start -n {ACTIVITY}")
            time.sleep(2)
        else:
            break
    ensure_home()


def reopen() -> None:
    """Leaves the app and opens it again: the first screen reads again when it comes back (TASK-017), as after a clock
    or zone change. Not `am force-stop`: a stopped app gets no TIME_SET or TIMEZONE_CHANGED for its widget."""
    to_home()
    go_home()
    launch_app()


def open_people() -> None:
    """First screen > [설정] > 대상자 관리 (SCR-010). Waits for the screen's title: with text 200% [대상자 추가] can sit
    below the fold, and a dump holds only what is on screen. The settings row is one description (`대상자 관리, 2명`)
    with no text of its own, so the title text is only on SCR-010."""
    to_home()
    tap_exact("설정")
    tap(seek(desc_starts("대상자 관리, "), "설정의 '대상자 관리' 줄"))
    nodes = wait_screen(lambda ns: has_text(ns, "대상자 관리"), 15)
    if not has_text(nodes, "대상자 관리"):
        raise RuntimeError("the people screen did not open")


# One of these is on SCR-007 in every state but #locked (TASK-018)
JUDGE_BUTTONS = ("먹은 시각 고르기", "먹은 시각 고치기", "먹었어로 바꾸기", "그대로 두기")


def judge_open(timeout: float = 15) -> bool:
    nodes = wait_screen(lambda ns: any(n.text in JUDGE_BUTTONS for n in ns), timeout)
    return any(n.text in JUDGE_BUTTONS for n in nodes)


def open_judge(name: str) -> None:
    """First screen > `name`'s [⋯] (SCR-007, read `name 판정 고치기`)."""
    to_home()
    tap(seek(desc_is(f"{name} 판정 고치기"), f"[⋯] of {name}"))
    if not judge_open():
        raise RuntimeError(f"SCR-007 of {name} did not open")


def open_history() -> None:
    """First screen > [지난 기록] (SCR-008)."""
    to_home()
    tap_exact(HOME_MARK)
    wait_screen(lambda ns: any(n.desc.endswith(CELL_ENDS) for n in ns), 20)


def last_cell() -> Node | None:
    """The lowest history cell on screen after scrolling to the bottom: the oldest day, the first cell of its line.
    Skipped cells are left out (their state word 건너뜀 is also a button text of SCR-007)."""
    _, h = size()
    for _ in range(4):
        scroll_down()
    cells = [n for n in dump_ui() if n.desc.endswith(CELL_ENDS) and not n.desc.endswith("건너뜀") and on_screen(n, h)]
    return max(cells, key=lambda n: (n.bounds[1], -n.bounds[0]), default=None)


def base_zone() -> str:
    """The base zone the app keeps (setting `base_zone`; Asia/Seoul until it is changed)."""
    rows = query("SELECT value FROM setting WHERE key = 'base_zone'")
    return rows[0][0] if rows else SEOUL


def set_base_zone(zone: str, shot=None) -> None:
    """First screen > [설정] > 기준 시간대 > `zone` > [바꾸기] (SCR-009 and its P-5 question). The list puts the zone in
    use, the phone's and Asia/Seoul first, so `zone` is one of those here."""
    to_home()
    tap_exact("설정")
    tap(seek(desc_starts("기준 시간대, "), "설정의 '기준 시간대' 줄"))
    tap(seek(desc_starts(f"{zone}, "), f"{zone} in the zone list"))
    if exact(ZONE_ASK, 10) is None:
        raise RuntimeError(f"no question before the base zone becomes {zone}")
    if shot:
        shot()
    tap_exact("바꾸기")
    nodes = wait_screen(lambda ns: has_desc(ns, f"기준 시간대, {zone}"), 15)
    if not has_desc(nodes, f"기준 시간대, {zone}"):
        raise RuntimeError(f"the settings row does not say {zone}")


def archived_line(name: str) -> tuple[Node | None, Node | None]:
    """`name`'s line under 보관됨 on SCR-010 and its [되살리기], scrolling until both are on screen."""
    _, h = size()
    line = button = None
    for _ in range(6):
        nodes = dump_ui()
        line = next((n for n in nodes if n.desc.startswith(f"{name}, ") and "지난 기록은 남아요" in n.desc), None)
        button = next((n for n in nodes if n.desc == f"{name} 되살리기"), None)
        if line and button and on_screen(line, h) and on_screen(button, h):
            break
        scroll_down()
    return line, button


def physical_density() -> int:
    """The display's own density (`wm density` prints it with any override)."""
    found = re.search(r"Physical density: (\d+)", sh("wm density"))
    if found is None:
        raise RuntimeError("wm density printed no physical density")
    return int(found.group(1))


def history_descs(rounds: int = 3) -> list[str]:
    """Every cell sentence of the history screen, scrolling down, then back to the top."""
    seen: list[str] = []
    for i in range(rounds):
        for n in dump_ui():
            if n.desc and n.desc not in seen:
                seen.append(n.desc)
        if i < rounds - 1:
            scroll_down()
    scroll_top()
    return seen


def pick_time(hour: int, minute: int, confirm: str) -> None:
    """In SCR-007's time picker: the hour, the minute, then the confirm button (Claude's design, TASK-018)."""
    tap(seek(desc_is(f"{hour}시"), f"hour {hour}"))
    tap(seek(desc_is(f"{minute}분"), f"minute {minute}"))
    tap(seek(text_is(confirm), f"[{confirm}]"))
    time.sleep(2)


# ------------------------------------------------------------------------------------------------
# App data (root)


def doses(name: str) -> list[tuple]:
    """Judgments of item `name`, in the order recorded: (period_key, kind, source, active, taken_at in Seoul time)."""
    rows = query(
        "SELECT d.period_key, d.kind, d.source, d.active, d.taken_at FROM dose_record d "
        f"JOIN intake i ON i.id = d.intake_id WHERE i.name = '{name}' ORDER BY d.recorded_at"
    )
    return [(p, k, s, a, datetime.fromtimestamp(t / 1000, ZONE).strftime("%m-%d %H:%M")) for p, k, s, a, t in rows]


def active(name: str) -> list[tuple]:
    return [row for row in doses(name) if row[3] == 1]


def active_count() -> int:
    return query("SELECT COUNT(*) FROM dose_record WHERE active = 1")[0][0]


def widget_state_files() -> list[str]:
    return [f for f in sh(f"ls {DATASTORE} 2>/dev/null", check=False).split() if f.startswith("appWidget-")]


def stored_choices() -> list[str]:
    """The person choice in each widget's Glance state: "*" (전체), a person id, or "-" when none is stored.
    Read from the preferences protobuf: the key, then the string value (field 5)."""
    found = []
    for name in widget_state_files():
        raw = subprocess_bytes(f"{DATASTORE}/{name}")
        at = raw.find(CHOICE_KEY)
        if at < 0:
            found.append("-")
            continue
        tag = raw.find(b"\x2a", at + len(CHOICE_KEY))
        length = raw[tag + 1] if 0 <= tag < len(raw) - 1 else 0
        found.append(raw[tag + 2:tag + 2 + length].decode("utf-8", "replace") if length else "-")
    return found


def subprocess_bytes(path: str) -> bytes:
    return subprocess.run(["adb", "exec-out", "cat", path], capture_output=True, timeout=60).stdout


def our_widget_ids() -> list[int]:
    """Ids of the DidITake widgets on the home screen, from `dumpsys appwidget`."""
    ids, current = [], None
    for line in sh("dumpsys appwidget", check=False).splitlines():
        m = re.match(r"\s*\[\d+\] id=(\d+)", line)
        if m:
            current = int(m.group(1))
        elif current is not None and "provider=" in line:
            if PKG in line:
                ids.append(current)
            current = None
    return ids


def refresh_widgets() -> None:
    """Every widget drawn again from the database, as after a write: the debug receiver's NAMES_ON_LOCK action runs
    AppContainer.afterWrite. `--include-stopped-packages` reaches the app also after `am force-stop`."""
    out = sh(f"am broadcast --include-stopped-packages -n {SEED} -a {ACTION_NAMES_ON_LOCK} --ez show false", timeout=90)
    if "names_on_lock false" not in out:
        raise RuntimeError(f"widget refresh failed: {out.strip()}")
    time.sleep(3)


def wipe_widget_choices() -> list[str]:
    """Deletes every widget's Glance state with its person choice, as a restore leaves the widgets (SCR-001#reconfigure,
    supplement R-3), and redraws them. The app's process is stopped first: Glance keeps the state in memory."""
    sh(f"am force-stop {PKG}")
    files = widget_state_files()
    sh(f"rm -f {DATASTORE}/appWidget-*.preferences_pb")
    left = widget_state_files()
    if left:
        raise RuntimeError(f"widget state files left: {left}")
    refresh_widgets()
    return files


def find_pin_button(timeout: float = 15) -> Node | None:
    end = time.time() + timeout
    while True:
        for n in dump_ui():
            if n.text.strip().lower() in PIN_BUTTONS or n.rid.endswith("/add_button"):
                return n
        if time.time() >= end:
            return None
        time.sleep(1)


# ------------------------------------------------------------------------------------------------
# Flow


class ShotSteps(Steps):
    """explore.Steps that also keeps which screenshots each step took, written to steps.json for the capture gallery."""

    def __init__(self, r: Report) -> None:
        super().__init__(r)
        self.spans: list[dict] = []

    def __call__(self, title: str, expected: str, fn, look: bool = False) -> bool:
        first = self.r.shots + 1
        ok = super().__call__(title, expected, fn, look)
        case, _, actual, _ = self.r.rows[-1]
        self.spans.append({"title": case, "expected": expected, "actual": actual, "ok": ok,
                           "shots": [f"api{self.r.api}-{n:02d}-" for n in range(first, self.r.shots + 1)]})
        return ok


def add(r: Report, name: str, person: str | None, choice: str, shot: str | None = None) -> str:
    """Home > [항목 추가] > name > person (only shown from two people on, TASK-015) > 매일 + time of day > [저장]."""
    to_home()
    tap_scrolled("항목 추가")
    type_into("약 이름", name)
    if person is not None:
        tap_exact(person)
    tap_exact("매일")
    group = exact("언제 먹나요?")
    tap_exact(choice, below=group.bounds[3] if group else -1)
    if shot:
        r.shot(shot)
    tap_exact("저장")
    if tile(name, 20) is None:
        raise RuntimeError(f"tile {name} did not show after [저장]")
    return f"{name} 저장됨"


def run(r: Report, steps: Steps, apk: Path) -> None:
    base = datetime.now(ZONE).date()

    def at(day: int, hour: int, minute: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=ZONE)

    def hm(seconds: int, zone: ZoneInfo = ZONE) -> str:
        return datetime.fromtimestamp(seconds, zone).strftime("%H:%M")

    # 0. phone setup
    def setup() -> str:
        become_root()
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {setting} 0")
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("svc power stayon true", check=False)
        sh("settings put system screen_off_timeout 1800000", check=False)
        zone = set_zone()
        clock = set_clock(at(0, 8, 30))
        font_scale("1.0")
        night(False)
        uninstall()
        install(apk)
        return f"시간대 {zone}, 시계 {clock}"

    if not steps("0. 준비: 서울 시간대, 오늘 08:30, 새로 설치", "시간대·시계가 바뀌고 앱이 깔림", setup):
        return

    # 1. the app, empty
    def first_open() -> str:
        launch_app()
        if not screen_has("아직 복용 항목이 없어요", 10):
            raise RuntimeError("the empty first screen did not show")
        r.shot("home-empty")
        return "첫 실행 안내를 [건너뛰기]로 넘기고 빈 첫 화면"

    steps("1. 앱 열기", "빈 첫 화면", first_open)

    # 2. the widget with one person (TASK-012, REQ-018)
    def widget_one_person() -> str:
        try:
            picker = place_widget(r, "widget-one")
        except RuntimeError as exc:
            r.note(f"위젯 놓기 첫 시도 실패, 20초 뒤 한 번 더: {exc}")
            go_home()
            time.sleep(20)
            picker = place_widget(r, "widget-one-retry")
        nodes = home_nodes(lambda ns: has_text(ns, EMPTY_WIDGET))
        r.shot("widget-one-person")
        save_ui(r, "widget-one-person")
        choices = stored_choices()
        if picker:
            raise RuntimeError("the person picker opened with one person")
        if not has_text(nodes, EMPTY_WIDGET):
            raise RuntimeError(f"the widget does not show '{EMPTY_WIDGET}'")
        if choices != ["*"]:
            raise RuntimeError(f"stored choices {choices}, expected ['*'] (전체)")
        return f"고르기 화면 없음, 위젯에 '{EMPTY_WIDGET}', 저장된 선택 {choices}"

    steps("2. 대상자 한 명일 때 위젯 놓기 (TASK-012)", "대상자 고르기 화면이 뜨지 않고 '전체'(*)로 저장, 위젯은 빈 안내",
          widget_one_person)

    # 3. one person: no person on tiles, the widget or in TalkBack's sentences (TASK-015)
    def one_person() -> str:
        add(r, VITAMIN, None, "아침", "form-one-person")
        to_home()
        time.sleep(1)
        app = spoken(dump_ui(), VITAMIN)
        r.shot("home-one-person")
        save_ui(r, "home-one-person")
        widget_nodes = home_nodes(lambda ns: bool(spoken(ns, VITAMIN)))
        widget = spoken(widget_nodes, VITAMIN)
        r.shot("widget-one-person-item")
        save_ui(r, "widget-one-person-item")
        bad = [s for s in app + widget if not s.startswith(f"{VITAMIN}, ")]
        if not app or not widget or bad:
            raise RuntimeError(f"app {app}, widget {widget}")
        if has_text(widget_nodes, ME):
            raise RuntimeError(f"the widget shows '{ME}' with one person")
        return f"앱: {app[0]} / 위젯: {widget[0]}"

    steps("3. 대상자 한 명: 이름 앞에 대상자 없음 (TASK-015)",
          f"앱 칸과 위젯 칸이 '{VITAMIN}, …'로 읽히고 '{ME}'가 보이지 않음", one_person)

    def one_person_history() -> str:
        open_history()
        nodes = dump_ui()
        cells = [n.desc for n in nodes if n.desc.startswith((f"{VITAMIN}, ", f"{ME} {VITAMIN}, "))]
        picker = [n.text for n in nodes if n.text in ("전체", ME)]
        r.shot("history-one-person")
        save_ui(r, "history-one-person")
        tap_desc("뒤로")
        if picker or cells != [f"{VITAMIN}, 아직"]:
            raise RuntimeError(f"picker texts {picker}, cells {cells}")
        return f"대상자 고르기 없음, 칸 {cells}"

    steps("3-2. 대상자 한 명의 지난 기록 (TASK-015)", f"대상자 고르기(전체·{ME}) 없음, 칸 '{VITAMIN}, 아직'",
          one_person_history)

    # 4. people: list, add with the one-phone note, edit the color (TASK-021)
    colors: dict[str, str] = {}

    def people_add() -> str:
        open_people()
        nodes = dump_ui()
        rows = [n.desc for n in nodes if n.desc.startswith(f"{ME}, ")]
        r.shot("people-one")
        save_ui(r, "people-one")
        tap_exact("대상자 추가")
        if exact("이름", 10) is None:
            raise RuntimeError("the person form did not open")
        empty_note = next((n.text for n in dump_ui() if n.text.startswith(ONE_PHONE)), "")
        r.shot("person-add-empty")
        type_into("이름", MOM)
        note = next((n.text for n in dump_ui() if n.text.startswith(ONE_PHONE)), "")
        r.shot("person-add-typed")
        save_ui(r, "person-add-typed")
        tap(seek(text_is("저장"), "[저장]"))
        listed = wait_screen(lambda ns: has_desc(ns, f"{MOM}, "), 15)
        r.shot("people-two")
        if not has_desc(listed, f"{MOM}, 약 0개"):
            raise RuntimeError(f"{MOM} not listed: {[n.desc for n in listed if n.desc]}")
        if f"{MOM} 약을 {MOM} 휴대폰에서도" not in note or "같은 약을 두 휴대폰에서" not in empty_note:
            raise RuntimeError(f"one-phone note: before '{empty_note}', with the name '{note}'")
        return f"목록 {rows}, 안내 '{note}', {MOM} 생김"

    steps("4-1. 대상자 추가 (TASK-021)", "목록, 한 휴대폰 안내(입력 중인 이름이 들어감), 저장 뒤 목록에 Mom", people_add)

    def people_edit() -> str:
        before = dict(query("SELECT name, color FROM person"))
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM}'s line"))
        if exact("대상자 수정", 10) is None:
            raise RuntimeError("the edit form did not open")
        r.shot("person-edit")
        tap(seek(desc_is(GREEN), f"color {GREEN}"))
        r.shot("person-edit-green")
        try:
            seek(text_is("보관"), "[보관]")
            archive_shown = True
        except RuntimeError:
            archive_shown = False
        tap(seek(text_is("저장"), "[저장]"))
        wait_screen(lambda ns: has_text(ns, "대상자 추가"), 15)
        after = dict(query("SELECT name, color FROM person"))
        r.shot("people-edited")
        colors.update(after)
        if before.get(MOM) == after.get(MOM):
            raise RuntimeError(f"{MOM}'s color stayed {after.get(MOM)}")
        if not archive_shown:
            raise RuntimeError("no [보관] on the edit form with two people")
        return f"{MOM} 색 {before.get(MOM)} → {after.get(MOM)}, 수정 화면에 [보관]"

    steps("4-2. 대상자 수정: 색 바꾸기 (TASK-021)", f"수정 화면에 [보관], {GREEN}으로 저장", people_edit)

    # 5. two people: the person in front of the names; the widget placed with one person still shows everyone
    def two_people() -> str:
        add(r, BP, MOM, "저녁", "form-two-people")
        to_home()
        time.sleep(1)
        nodes = dump_ui()
        app = spoken(nodes, VITAMIN) + spoken(nodes, BP)
        r.shot("home-two-people")
        save_ui(r, "home-two-people")
        widget_nodes = home_nodes(lambda ns: has_desc(ns, f"{ME} {VITAMIN}, ") and has_desc(ns, f"{MOM} {BP}, "))
        widget = spoken(widget_nodes, VITAMIN) + spoken(widget_nodes, BP)
        r.shot("widget-two-people")
        save_ui(r, "widget-two-people")
        want = (f"{ME} {VITAMIN}, ", f"{MOM} {BP}, ")
        ok_app = all(any(s.startswith(w) for s in app) for w in want)
        ok_widget = all(any(s.startswith(w) for s in widget) for w in want)
        if not (ok_app and ok_widget) or has_text(widget_nodes, RECONFIGURE):
            raise RuntimeError(f"app {app}, widget {widget}")
        return f"앱: {' / '.join(app)}; 위젯: {' / '.join(widget)}"

    steps("5. 대상자 두 명: 이름 앞에 대상자 (TASK-015, TASK-012)",
          "앱·위젯이 '나 Vitamin, …', 'Mom BPpill, …'로 읽힘, 한 명일 때 놓은 위젯은 다시 설정을 묻지 않음", two_people)

    # 6. the widget's choice deleted, as after a restore (TASK-012, SCR-001#reconfigure)
    def wiped() -> str:
        files = wipe_widget_choices()
        nodes = home_nodes(lambda ns: has_text(ns, RECONFIGURE))
        r.shot("widget-reconfigure")
        save_ui(r, "widget-reconfigure")
        tiles = [n.text for n in nodes if n.text in (VITAMIN, BP)]
        if not has_text(nodes, RECONFIGURE) or tiles:
            raise RuntimeError(f"line {'shown' if has_text(nodes, RECONFIGURE) else 'missing'}, tiles {tiles}")
        return f"지운 상태 파일 {files}, 위젯에 '{RECONFIGURE}'만"

    steps("6. 위젯 선택을 지움 (복원 흉내, TASK-012)", f"대상자 둘 → 칸 없이 '{RECONFIGURE}'만", wiped)

    # 7. the line opens SCR-002; Mom picked
    def pick_mom() -> str:
        go_home()
        line = exact(RECONFIGURE, 10)
        if line is None:
            raise RuntimeError(f"'{RECONFIGURE}' not on the home screen")
        tap(line)
        if not screen_has(PICK_TITLE, 15):
            raise RuntimeError("the person picker did not open")
        options = [n.desc for n in dump_ui() if n.desc.startswith(("전체, ", f"{ME}, ", f"{MOM}, "))]
        r.shot("pick-from-widget")
        save_ui(r, "pick-from-widget")
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM} in the picker"))
        nodes = home_nodes(lambda ns: has_text(ns, BP))
        count = next((n.text for n in nodes if re.fullmatch(r"\d+ / \d+", n.text)), "")
        r.shot("widget-mom")
        save_ui(r, "widget-mom")
        if not has_text(nodes, BP) or has_text(nodes, VITAMIN) or count != "0 / 1":
            raise RuntimeError(f"widget texts {[n.text for n in nodes if n.text]}")
        return f"고르기 화면 {options}, Mom 위젯: {BP}만, 수 '{count}'"

    steps("7. 다시 설정 줄 → 대상자 고르기 → Mom (TASK-012)", "고르기 화면(전체·나·Mom), Mom 위젯에 BPpill만과 '0 / 1'",
          pick_mom)

    # 8. Mom archived (TASK-021, TASK-012)
    def archive_mom() -> str:
        open_people()
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM}'s line"))
        tap(seek(text_is("보관"), "[보관]"))
        title = exact(f"{MOM}을(를) 보관할까요?", 10)
        if title is None:
            raise RuntimeError("no archive question")
        question = [n.text for n in dump_ui() if n.text and n.bounds[1] >= title.bounds[1]][:3]
        r.shot("person-archive-ask")
        tap_exact("보관", 10, below=title.bounds[1])
        nodes = wait_screen(lambda ns: has_text(ns, "보관됨"), 15)
        msg = message_line()
        restore = has_desc(nodes, f"{MOM} 되살리기")
        r.shot("people-archived")
        save_ui(r, "people-archived")
        to_home()
        app_nodes = dump_ui()
        app = spoken(app_nodes, VITAMIN)
        r.shot("home-mom-archived")
        widget = home_nodes(lambda ns: has_text(ns, RECONFIGURE_ARCHIVED))
        r.shot("widget-archived")
        if not restore or not has_text(widget, RECONFIGURE_ARCHIVED) or spoken(app_nodes, BP):
            raise RuntimeError(f"[되살리기] {restore}, widget {[n.text for n in widget if n.text]}, app {app}")
        return f"질문 {question}, 메시지 '{msg}', 보관됨에 [되살리기], 위젯 '{RECONFIGURE_ARCHIVED}', 앱 {app}"

    steps("8. Mom 보관 (TASK-021, TASK-012)",
          f"묻고 보관, '보관됨'과 [되살리기], 위젯은 '{RECONFIGURE_ARCHIVED}', 앱 칸에서 BPpill이 빠짐", archive_mom)

    # 9. text 200% at this phone's width: [되살리기] on the screen, beside or under the name (TASK-021, ArchivedLine).
    # It goes under the name only when it would take more than 45% of the room beside the badge; the Pixel 5 is 393dp
    # wide, where it still fits beside. The end condition's 360dp is step 16, at the end (wm density re-lays the launcher).
    def archived_200() -> str:
        font_scale("2.0")
        try:
            reopen()
            open_people()
            _, h = size()
            name, button = archived_line(MOM)
            r.shot("people-archived-200")
            save_ui(r, "people-archived-200")
            if not (name and button):
                raise RuntimeError(f"name {name}, button {button}")
            below = button.bounds[1] >= name.bounds[3] - 2
            beside = button.bounds[0] >= name.bounds[2] - 2
            if not (below or beside) or not on_screen(button, h):
                raise RuntimeError(f"name {name.bounds}, [되살리기] {button.bounds}, screen height {h}")
            dp = round(size()[0] * 160 / physical_density())
            return f"폭 {dp}dp: 이름 {name.bounds} {'아래' if below else '옆'} [되살리기] {button.bounds}"
        finally:
            font_scale("1.0")

    steps("9. 글자 200%의 보관됨, 이 휴대폰 폭 (TASK-021)", "[되살리기]가 화면 안에 있고 이름과 겹치지 않음", archived_200)

    # 10. Mom brought back
    def restore_mom() -> str:
        reopen()
        open_people()
        tap(seek(desc_is(f"{MOM} 되살리기"), "[되살리기]"))
        # Archived, the line reads `Mom, 약 1개 · 지난 기록은 남아요`; back among the active people `Mom, 약 1개`
        nodes = wait_screen(lambda ns: any(n.desc == f"{MOM}, 약 1개" for n in ns), 15)
        back = any(n.desc == f"{MOM}, 약 1개" for n in nodes)
        msg = next((n.text for n in nodes if "되살렸어요" in n.text), "(메시지 없음)")
        r.shot("people-restored")
        to_home()
        widget = home_nodes(lambda ns: has_text(ns, BP))
        r.shot("widget-mom-restored")
        if not back or not has_text(widget, BP) or has_text(widget, RECONFIGURE_ARCHIVED):
            raise RuntimeError(f"people {[n.desc for n in nodes if n.desc]}, widget {[n.text for n in widget if n.text]}")
        return f"메시지 '{msg}', 목록에 {MOM}, 위젯에 다시 {BP}"

    steps("10. Mom 되살리기 (TASK-021)", "묻지 않고 되살림, Mom 위젯에 BPpill이 다시 보임", restore_mom)

    # 11. day 0: Vitamin recorded in the app
    def day0() -> str:
        to_home()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(2)
        rows = active(VITAMIN)
        r.shot("home-day0")
        if not rows or rows[-1][1] != "TAKEN":
            raise RuntimeError(f"{VITAMIN}: {rows}")
        return f"{VITAMIN} {rows[-1]}"

    steps("11. 0일: 앱에서 Vitamin [먹었어]", "먹음 1건", day0)

    # 12. day 1, 09:00: SCR-007 of the current window (TASK-018), the skipped tile on the widget (TASK-013)
    def day1_skip() -> str:
        clock = set_clock(at(1, 9, 0))
        reopen()
        open_judge(BP)
        none = buttons(("지금 먹었어", "먹은 시각 고르기", "건너뜀", "먹은 시각 고치기", "기록 취소"))
        r.shot("judge-none")
        save_ui(r, "judge-none")
        tap_exact("건너뜀")
        if exact("먹었어로 바꾸기", 10) is None:
            raise RuntimeError("no [먹었어로 바꾸기] after [건너뜀]")
        skipped = buttons(("먹었어로 바꾸기", "건너뜀 취소", "먹은 시각 고르기"))
        r.shot("judge-skipped")
        tap_desc("뒤로")
        home = wait_screen(at_home, 10)
        tile_said = spoken(home, BP)
        r.shot("home-skipped")
        save_ui(r, "home-skipped")
        rows = active(BP)
        if not any(s.endswith(", 건너뜀") for s in tile_said):
            raise RuntimeError(f"the first screen reads {BP} as {tile_said}")
        want_none = {"지금 먹었어": True, "먹은 시각 고르기": True, "건너뜀": True, "먹은 시각 고치기": False, "기록 취소": False}
        want_skip = {"먹었어로 바꾸기": True, "건너뜀 취소": True, "먹은 시각 고르기": False}
        if none != want_none or skipped != want_skip or not rows or rows[-1][1] != "SKIPPED":
            raise RuntimeError(f"none {none}, skipped {skipped}, {BP} {rows}")
        return f"시계 {clock}; 판정 없음 {none}; 건너뜀 뒤 {skipped}; {BP} {rows[-1]}; 첫 화면 칸 {tile_said}"

    steps("12-1. 1일 09:00 BPpill [⋯]: 판정 없음 → 건너뜀 (TASK-018)",
          "[지금 먹었어]·[먹은 시각 고르기]·[건너뜀], 건너뜀 뒤 [먹었어로 바꾸기]·[건너뜀 취소], 첫 화면 칸은 '…, 건너뜀'",
          day1_skip)

    def widget_skip_tap() -> str:
        before = active_count()
        nodes = home_nodes(lambda ns: any(s.endswith(", 건너뜀") for s in spoken(ns, BP)))
        said = spoken(nodes, BP)
        r.shot("widget-skipped")
        target = next((n for n in nodes if n.desc.startswith(f"{MOM} {BP}, ")), None) or exact(BP, 5)
        if target is None:
            raise RuntimeError(f"{BP} not on the widget")
        tap(target)
        wait_screen(lambda ns: any(SKIP_TAP in n.text for n in ns), 8)
        msg = message_line()
        r.shot("widget-skipped-tap")
        save_ui(r, "widget-skipped-tap")
        after = active_count()
        rows = active(BP)
        if SKIP_TAP not in msg or after != before or rows[-1][1] != "SKIPPED":
            raise RuntimeError(f"message '{msg}', active {before} → {after}, {BP} {rows}")
        return f"위젯 칸 {said}; 탭 메시지 '{msg}'; 활성 판정 {before} → {after}"

    steps("12-2. 위젯의 건너뜀 칸 탭 (TASK-013)", f"'{BP}: {SKIP_TAP} (…). 바꾸려면 앱에서', 판정 그대로", widget_skip_tap)

    def day1_pick() -> str:
        open_judge(VITAMIN)
        tap_exact("먹은 시각 고르기")
        tap(seek(desc_is("8시"), "hour 8"))
        tap(seek(desc_is("0분"), "minute 0"))
        r.shot("judge-pick")
        save_ui(r, "judge-pick")
        tap(seek(text_is("이 시각으로 기록"), "[이 시각으로 기록]"))
        if exact("먹은 시각 고치기", 10) is None:
            raise RuntimeError("no [먹은 시각 고치기] after recording")
        scroll_top()
        taken = buttons(("먹은 시각 고치기", "기록 취소", "건너뜀"))
        r.shot("judge-taken")
        tap_desc("뒤로")
        rows = active(VITAMIN)
        if taken != {"먹은 시각 고치기": True, "기록 취소": True, "건너뜀": False} or not rows or not rows[-1][4].endswith("08:00"):
            raise RuntimeError(f"taken {taken}, {VITAMIN} {rows}")
        return f"먹음 뒤 {taken}; {VITAMIN} {rows[-1]}"

    steps("12-3. Vitamin 먹은 시각 고르기 08:00 (TASK-018)", "시각 고르기 → 08:00 먹음, [먹은 시각 고치기]·[기록 취소]",
          day1_pick)

    # 13. day 2, 08:40: past records (TASK-019) and SCR-007 of past days (TASK-018)
    def history() -> str:
        clock = set_clock(at(2, 8, 40))
        reopen()
        open_history()
        r.shot("history-all")
        save_ui(r, "history-all")
        cells = history_descs()
        want = [f"{ME} {VITAMIN}, 아직", f"{MOM} {BP}, 아직", f"{ME} {VITAMIN}, 08:00 먹음", f"{MOM} {BP}, 건너뜀",
                f"{MOM} {BP}, 빠짐"]
        missing = [w for w in want if w not in cells]
        day0_dose = [c for c in cells if re.fullmatch(rf"{ME} {VITAMIN}, \d\d:\d\d 먹음", c) and "08:00" not in c]
        if missing or not day0_dose:
            raise RuntimeError(f"missing {missing}, day-0 dose {day0_dose}; cells {[c for c in cells if ', ' in c]}")
        return f"시계 {clock}; 칸 {want + day0_dose}"

    steps("13-1. 2일 08:40 지난 기록 (TASK-019)", "먹음·건너뜀·빠짐·아직 칸, 대상자 이름이 앞에", history)

    def history_mom() -> str:
        scroll_top()
        tap_exact(MOM)
        time.sleep(2)
        cells = history_descs()
        r.shot("history-mom")
        mixed = [c for c in cells if VITAMIN in c]
        mine = [c for c in cells if c.startswith(f"{BP}, ")]
        tap_exact("전체")
        time.sleep(1)
        if mixed or not mine:
            raise RuntimeError(f"Mom filter shows {[c for c in cells if ', ' in c]}")
        return f"Mom만: {mine}"

    steps("13-2. 지난 기록 대상자 고르기 Mom (TASK-019)", "BPpill 칸만(이름 앞 대상자 없음)", history_mom)

    def past_missed() -> str:
        tap(seek(desc_is(f"{MOM} {BP}, 빠짐"), "the missed cell"))
        if exact("먹은 시각 고르기", 10) is None:
            raise RuntimeError("SCR-007 of the missed day did not open")
        titles = sorted((n for n in dump_ui() if "고치기" in n.text and "기록" in n.text), key=lambda n: n.bounds[1])
        title = titles[0].text if titles else ""
        past = buttons(("지금 먹었어", "먹은 시각 고르기", "건너뜀"))
        r.shot("judge-past-none")
        save_ui(r, "judge-past-none")
        tap_exact("먹은 시각 고르기")
        tap(seek(desc_is("21시"), "hour 21"))
        tap(seek(desc_is("0분"), "minute 0"))
        r.shot("judge-past-pick")
        tap(seek(text_is("이 시각으로 기록"), "[이 시각으로 기록]"))
        if exact("먹은 시각 고치기", 10) is None:
            raise RuntimeError("no [먹은 시각 고치기] after recording the past day")
        scroll_top()
        r.shot("judge-past-taken")
        tap_desc("뒤로")
        cell = wait_screen(lambda ns: has_desc(ns, f"{MOM} {BP}, 21:00 먹음"), 10)
        rows = [row for row in active(BP) if row[0] == base.isoformat()]
        r.shot("history-after-missed")
        if past != {"지금 먹었어": False, "먹은 시각 고르기": True, "건너뜀": True} or not rows \
                or rows[-1][1] != "TAKEN" or not rows[-1][4].endswith("21:00") or not has_desc(cell, f"{MOM} {BP}, 21:00 먹음"):
            raise RuntimeError(f"title '{title}', buttons {past}, {BP} day 0 {rows}")
        return f"제목 '{title}', 버튼 {past}, 0일 {BP} {rows[-1]}"

    steps("13-3. 빠짐 칸 → 지난 창 먹은 시각 21:00 (TASK-018, TASK-019)",
          "'… 기록 고치기', [지금 먹었어] 없음, 21:00 먹음으로 칸이 바뀜", past_missed)

    def past_skipped() -> str:
        tap(seek(desc_is(f"{MOM} {BP}, 건너뜀"), "the skipped cell"))
        if exact("먹었어로 바꾸기", 10) is None:
            raise RuntimeError("SCR-007 of the skipped day did not open")
        r.shot("judge-past-skipped")
        tap_exact("먹었어로 바꾸기")
        pick_time(21, 0, "이 시각으로 바꾸기")
        if exact("먹은 시각 고치기", 10) is None:
            raise RuntimeError("no [먹은 시각 고치기] after the change")
        scroll_top()
        r.shot("judge-skip-to-taken")
        tap_desc("뒤로")
        time.sleep(2)
        r.shot("history-after-fixes")
        save_ui(r, "history-after-fixes")
        day1 = (base + timedelta(days=1)).isoformat()
        rows = [row for row in doses(BP) if row[0] == day1]
        on = [row for row in rows if row[3] == 1]
        if len(on) != 1 or on[0][1] != "TAKEN" or not on[0][4].endswith("21:00") \
                or not any(row[1] == "SKIPPED" and row[3] is None for row in rows):
            raise RuntimeError(f"{BP} day 1 {rows}")
        return f"1일 {BP}: {rows}"

    steps("13-4. 건너뜀 칸 → 먹었어로 바꾸기 21:00 (TASK-018)", "건너뜀이 무효로 남고 21:00 먹음 하나가 활성", past_skipped)

    # 14. the clock moved back after a dose (supplement C, RR-64)
    flagged = {"at": 0}

    def clock_back() -> str:
        reopen()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(2)
        recorded = phone_now()
        flagged["at"] = recorded
        clock = set_clock(datetime.fromtimestamp(recorded - 40 * 60, ZONE))
        reopen()
        band = find_text_start(CLOCK_BAND)
        pair = buttons(("그대로 두기", "기록 취소"))
        said = [s for s in spoken(dump_ui(), VITAMIN) if s.endswith(CLOCK_SPOKEN)]
        r.shot("home-clock-warning")
        save_ui(r, "home-clock-warning")
        if not band or pair != {"그대로 두기": True, "기록 취소": True} or not said:
            raise RuntimeError(f"band '{band}', buttons {pair}, spoken {spoken(dump_ui(), VITAMIN)}")
        return f"{hm(recorded)}에 기록 뒤 시계 {clock}; 띠 '{band}'; {pair}; 칸 '{said[0]}'"

    steps("14-1. 기록 뒤 시계를 40분 되돌림 (TASK-016 SCR-004#future)",
          "맨 위 띠와 [그대로 두기]·[기록 취소], 칸 문장 끝에 '시계 확인 필요'", clock_back)

    def judge_clock() -> str:
        open_judge(VITAMIN)
        pair = buttons(("그대로 두기", "기록 취소", "먹은 시각 고치기"))
        r.shot("judge-clock")
        save_ui(r, "judge-clock")
        tap_desc("뒤로")
        if not (pair["그대로 두기"] and pair["기록 취소"]):
            raise RuntimeError(f"buttons {pair}")
        return f"버튼 {pair}"

    steps("14-2. 시계 경고의 판정 고치기 (TASK-018 SCR-007#future)", "[그대로 두기]·[기록 취소]", judge_clock)

    def clock_right_again() -> str:
        if not flagged["at"]:
            # Without 14-1's dose the clock would be set from 0 (1970) and every record would look ahead of it
            raise RuntimeError("14-1 recorded no dose, so the clock is left as it is")
        clock = set_clock(datetime.fromtimestamp(flagged["at"] + 30 * 60, ZONE))
        reopen()
        band = find_text_start(CLOCK_BAND)
        r.shot("home-clock-warning-kept")
        open_history()
        cells = [c for c in history_descs() if c.startswith(f"{ME} {VITAMIN}, ") and c.endswith(CLOCK_SPOKEN)]
        r.shot("history-clock")
        save_ui(r, "history-clock")
        tap_desc("뒤로")
        if not band or not cells:
            raise RuntimeError(f"band '{band}', history cells {cells}")
        return f"시계 {clock}; 띠가 남음; 지난 기록 칸 '{cells[0]}'"

    steps("14-3. 시계가 다시 맞아도 경고가 남음 (RR-64, TASK-019)", "띠가 남고 지난 기록 칸에 '시계 확인 필요'", clock_right_again)

    def keep() -> str:
        to_home()
        tap_exact("그대로 두기")
        time.sleep(3)
        gone = not screen_has(CLOCK_BAND, 0)
        resolved = query("SELECT COUNT(*) FROM clock_flag WHERE resolved_at IS NOT NULL")[0][0]
        rows = active(VITAMIN)
        r.shot("home-clock-kept")
        if not gone or resolved < 1 or rows[-1][1] != "TAKEN":
            raise RuntimeError(f"band {'gone' if gone else 'still there'}, resolved {resolved}, {VITAMIN} {rows}")
        return f"띠 사라짐, 해결된 경고 {resolved}개, 판정 그대로 {rows[-1]}"

    steps("14-4. [그대로 두기] (RR-66)", "띠가 사라지고 판정은 그대로", keep)

    # 15. the phone in another zone (TASK-043, REQ-082)
    def last_dose(name: str) -> int:
        """Epoch seconds of `name`'s latest active dose."""
        rows = query("SELECT MAX(d.taken_at) FROM dose_record d JOIN intake i ON i.id = d.intake_id "
                     f"WHERE i.name = '{name}' AND d.active = 1")
        return (rows[0][0] or 0) // 1000

    def london() -> str:
        dose = flagged["at"] or last_dose(VITAMIN)  # 14-1's dose; an earlier one when 14-1 failed
        zone = zone_to(LONDON)
        try:
            reopen()
            nodes = dump_ui()
            date = day_label(at(2, 12).date())
            dated = any(n.text.startswith(date) or date in n.text for n in nodes)
            uk = hm(dose, ZoneInfo(LONDON))
            said = [s for s in spoken(nodes, VITAMIN) if uk in s]
            r.shot("home-london")
            save_ui(r, "home-london")
            try:
                seek(text_is(SEOUL_NOTE), "the zone note")  # under the list, with the medical note
                note = True
                r.shot("home-london-notes")
            except RuntimeError:
                note = False
            scroll_top()
            open_history()
            cells = [c for c in history_descs() if c.startswith(f"{ME} {VITAMIN}, ") and uk in c]
            r.shot("history-london")
            tap_desc("뒤로")
            refresh_widgets()
            go_home()
            time.sleep(2)
            r.shot("widget-london")
            if not note or not dated or not said or not cells:
                raise RuntimeError(f"note {note}, date '{date}' {dated}, tile {spoken(nodes, VITAMIN)}, history {cells}")
            return f"시간대 {zone}; '{SEOUL_NOTE}'; 날짜 '{date}' 그대로; 칸 '{said[0]}'; 지난 기록 '{cells[0]}'"
        finally:
            zone_to("Asia/Seoul")

    steps("15. 휴대폰 시간대 Europe/London (TASK-043)",
          "기록 시각은 휴대폰 시계(런던), 날짜·칸은 기준 시간대(서울), 첫 화면에 기준 시간대 안내", london)

    # 15-2. a day that ended before the base zone changed (SCR-007#locked, P-5, RR-44). With the phone in London the
    # zone list has Europe/London at the top; the base zone goes back to Asia/Seoul after it (another P-5 change).
    def locked() -> str:
        before = base_zone()
        zone_to(LONDON)
        try:
            reopen()
            set_base_zone(LONDON, shot=lambda: r.shot("settings-zone-ask"))
            r.shot("settings-zone-london")
            stored = base_zone()
            open_history()
            cell = last_cell()
            if cell is None:
                raise RuntimeError("no history cell to open")
            said = cell.desc
            tap(cell)
            nodes = wait_screen(lambda ns: any(LOCKED in n.text for n in ns), 15)
            band = next((n.text for n in nodes if LOCKED in n.text), "")
            note = any(LOCKED_NOTE in n.text for n in nodes)
            shown = sorted({n.text for n in nodes if n.text in JUDGE_ACTIONS})
            r.shot("judge-locked")
            save_ui(r, "judge-locked")
            tap_desc("뒤로")
            if stored != LONDON or not band or shown:
                raise RuntimeError(f"base zone {before} → {stored}, cell '{said}', band '{band}', buttons {shown}")
            return (f"기준 시간대 {before} → {stored}; 맨 아래 칸 '{said}'; 띠 '{band}'; "
                    f"안내 줄 {'있음' if note else '없음'}; 버튼 없음")
        finally:
            try:
                if base_zone() != SEOUL:
                    set_base_zone(SEOUL)
            except Exception as exc:  # the next steps read dates in Seoul; say so instead of hiding the step's own error
                r.note(f"기준 시간대를 {SEOUL}로 되돌리지 못함: {type(exc).__name__}: {exc}")
            zone_to(SEOUL)

    steps("15-2. 기준 시간대를 바꾸기 전에 끝난 날 (TASK-018 SCR-007#locked)",
          f"설정에서 기준 시간대를 {LONDON}로 바꾼 뒤 지난 기록 맨 아래 칸: '{LOCKED}' 띠, 버튼 없음", locked)

    # 16. TASK-021's end condition at 360dp: text 200% puts [되살리기] under the name. Mom is archived again and the
    # display density set for a 360dp width; last before the reinstall, since wm density also re-lays the launcher.
    def archived_360() -> str:
        w, h = size()
        dpi = round(w * 160 / 360)
        open_people()
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM}'s line"))
        tap(seek(text_is("보관"), "[보관]"))
        title = exact(f"{MOM}을(를) 보관할까요?", 10)
        if title is None:
            raise RuntimeError("no archive question")
        tap_exact("보관", 10, below=title.bounds[1])
        if not has_text(wait_screen(lambda ns: has_text(ns, "보관됨"), 15), "보관됨"):
            raise RuntimeError(f"{MOM} was not archived")
        try:
            font_scale("2.0")
            sh(f"wm density {dpi}")
            reopen()
            open_people()
            name, button = archived_line(MOM)
            r.shot("people-archived-200-360dp")
            save_ui(r, "people-archived-200-360dp")
            if not (name and button):
                raise RuntimeError(f"name {name}, button {button}")
            below = button.bounds[1] >= name.bounds[3] - 2
            if not below or not on_screen(button, h):
                raise RuntimeError(f"density {dpi}: name {name.bounds}, [되살리기] {button.bounds}, screen height {h}")
            return f"wm density {dpi} (폭 {w * 160 // dpi}dp): 이름 {name.bounds} 아래 [되살리기] {button.bounds}"
        finally:
            sh("wm density reset", check=False)
            font_scale("1.0")

    steps("16. 폭 360dp·글자 200%의 보관됨 (TASK-021)", "[되살리기]가 이름 아래 줄에 있고 화면 안", archived_360)

    # 17. installed again: the intro and its [위젯 추가] (TASK-022)
    def intro_pin() -> str:
        uninstall()
        time.sleep(3)
        left = our_widget_ids()
        install(apk)
        launch_app(stop_at_intro=True)
        time.sleep(2)
        r.shot("intro-1")
        tap_exact("다음")
        if exact("위젯 추가", 10) is None:
            r.shot("intro-2-no-pin")
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
        r.shot("pin-done")
        tap_exact("시작하기", 15)
        if exact(HOME_MARK, 15) is None:
            raise RuntimeError("[시작하기] did not open the first screen")
        nodes = home_nodes(lambda ns: has_text(ns, EMPTY_WIDGET))
        r.shot("widget-pinned")
        save_ui(r, "widget-pinned")
        ids = our_widget_ids()
        choices = stored_choices()
        if left or len(ids) != 1 or not has_text(nodes, EMPTY_WIDGET) or choices != ["*"]:
            raise RuntimeError(f"widgets before {left}, after {ids}, texts {[n.text for n in nodes if n.text]}, choices {choices}")
        return f"지운 뒤 위젯 {left}, [위젯 추가] → '{button.text or button.rid}' → 위젯 {ids}, 선택 {choices}"

    steps("17. 첫 실행 안내의 [위젯 추가] (TASK-022)", "런처의 추가 창 → 홈 화면에 위젯, 대상자 한 명이라 '전체'(*)로 저장",
          intro_pin)


def find_text_start(prefix: str) -> str:
    """The first text on screen that contains `prefix` (the clock band is one text with the time inside)."""
    return next((n.text for n in dump_ui() if prefix in n.text), "")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()  # also counts this boot, so become_root waits for the emulator's own commands after it
    api = api_level()
    r = Report(args.out, api, f"에뮬레이터 화면 확인 API {api}")
    steps = ShotSteps(r)
    try:
        run(r, steps, args.apk)
    except Exception as exc:
        traceback.print_exc()
        r.check("화면 확인 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        for note in sorted(set(KEYBOARD_NOTES)):
            r.note(note)
        r.write()
        (args.out / "steps.json").write_text(json.dumps(steps.spans, ensure_ascii=False, indent=1), encoding="utf-8")
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        (args.out / f"api{api}-logcat.txt").write_text("\n".join(LOG_PARTS), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
