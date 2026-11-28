"""The prototype QA's 13 checks of 2026-11-11, asked again of the latest build on one emulator (API 34) (TASK-034).

Why: TASK-034 (docs/08-wbs/wbs.md) repeats the 13 checks of the prototype QA (judangbang-ops/DidITake,
docs/07-qa/2026-11-11-prototype/report.md) on the last build, because R2 and R3 rewrote the widget, the first screen and
the record functions that QA used. Each step keeps the prototype's question and asks it of today's screens and words
(docs/05-ui/screens.md, Labels.kt). Where the design changed since, the step says what is checked instead, and the QA
report beside the run (docs/07-qa/) puts the two side by side. Records are read from the app's database, as the
prototype QA did. The other emulator scripts cover parts of these (explore.py, screens_check.py, screens2_check.py,
widget_size_check.py); this one asks all 13 in one run, and on the widget, where they asked them.

Flow (clock set in Asia/Seoul; day 0 is the run's date; the clock only moves forward):
   1. Install and open: the intro's two pages, [시작하기], the empty first screen with its [항목 추가] note (QA 1).
   2. Morning (매일 아침), Vitamin (매일 저녁) and Weekly (주 1회 월) added: three tiles, read as not taken, and three
      items with those schedules in the database (QA 2).
   3. The widget placed by hand: `필받음 · MM/DD` and `0 / 3`, each tile read as `<name>, 미완료, 기록 없음` (weekly
      `이번 주 미완료`) with `기록 없음` drawn, and no person before the names: with one person the widget shows none
      (REQ-018), where the prototype drew a person badge (QA 3).
   4. The widget's cells as the launcher placed them: 4x2, the first row's tiles whole with their names, and the arrow
      when a row is cut (QA 13; widget_size_check.py measures the sizes in depth).
   5. Morning tapped on the widget: `Morning, 복용함, 오늘 HH:MM` and `HH:MM 먹음`, one active row from WIDGET (QA 4).
   6. Morning tapped again: `Morning: 이미 기록됨 (HH:MM). 변경 없음` in the header's place, the rows unchanged (QA 5).
   7. Vitamin tapped twice at once (two taps sent together): one row (QA 6).
   8. Vitamin's record cancelled in the app ([기록 취소], the question, [기록 취소]): the row voided with USER_CANCEL, and
      the widget shows Vitamin not taken by itself, timed from the tap (QA 7).
   9. Vitamin tapped on the widget again: one voided row and one active row (QA 8).
  10. Clock moved to day 1 05:00: the widget turns to day 1 by itself (TIME_SET): header, `0 / 3`, `마지막 복용 어제`
      (QA 9).
  11. Morning tapped (day 1). Clock moved to day 2 03:54, before the 04:00 day start: still day 1. Then, with no clock
      change, the day-boundary alarm (04:00:05, inexact) turns the widget to day 2: header, `0 / 3`, Morning not taken;
      the app's log shows the next boundary planned after it (QA 10).
  12. Weekly tapped on a Friday; the Saturday still taken; the next Monday 05:00 `이번 주 미완료, 마지막 복용 …금요일`
      (QA 11).
  13. The soft keyboard up over the item form: [저장] whole above it, tapped, and the item saved (QA 12).
  13a–b. Feedback (TC-098): the no-mail sheet and clipboard, then a single draft in the fixture app on two taps.
  13c–d. Health card show (TC-095, TC-109, card_show_check.py): no lock-screen override in the app's source, then
      62 s awake on the show screen with a 30 s timeout, no card over the PIN keyguard, the same summary after
      unlocking, and asleep 34 s after Back.
  14. The app's data cleared (`pm clear`, what [저장공간 지우기] in the settings does) with the widget left on the home
      screen, then a tile it still draws tapped: no judgment row, and the widget drawn again as the empty widget
      (REQ-092, TC-009; not one of the 13, but TASK-034 owns it).
  15. No crash of the app in the whole run (the crash log, QA 1's "크래시 없이").

Names are typed in English (`adb shell input text` cannot type Korean). The debug APK is used: the database is read as
root. The widget is never redrawn by the debug receiver here: what it shows after a write, a clock change or the day
start is what the app drew by itself.

Writes report.md, screenshots (api34-NN-name.png), screen dumps and steps.json (each step's result with the numbers of
the screenshots it took, for the capture gallery) to --out. Exits 1 when a check fails.
Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path

import screens2_check as s2
from backup_restore_check import (
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
    IME_BLOCK,
    KEYBOARD_NOTES,
    LOG_PARTS,
    VITAMIN,
    WEEKLY,
    ZONE,
    exact,
    font_scale,
    message_line,
    night,
    phone_now,
    row_button,
    set_clock,
    set_zone,
    tap_exact,
    tap_scrolled,
    tile,
)
from card_export_check import check_card_export
from card_show_check import check_card_privacy, check_card_show
from feedback_check import check_feedback
from release_check import WIDGET_LABEL, go_home, place_widget, save_ui, screen_size
from reminder_check import BOUNDARY_ALARM, alarms, query
from screens_check import ShotSteps, our_widget_ids, physical_density, to_home
from widget_size_check import launcher_package, read_span

MORNING = "Morning"
TYPED = "Keyboard"  # the item saved with the soft keyboard up (step 13)
NAMES = (MORNING, VITAMIN, WEEKLY)
# The widget helpers of screens2_check find tiles by these names (its ITEMS); this run's items are listed in the order
# they were added, like the widget's rows.
s2.ITEMS = NAMES + (TYPED,)

EMPTY_HOME = "아직 복용 항목이 없어요."  # HomeScreen's empty state (SCR-004#empty), with its full stop
EMPTY_WIDGET = "앱에서 복용 항목을 추가해 주세요"  # Labels.EMPTY_WIDGET, the widget with no items (SCR-001#empty)
NO_RECORD = "기록 없음"  # Labels.NO_RECORD
CANCEL_ASK = "기록을 취소할까요?"  # the question of [기록 취소] (REQ-029)
MORE = s2.MORE  # the widget's down arrow, read as "더 있음" (SCR-001#overflow)
WEEKDAY_NAMES = ("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
WEEKDAYS = "월화수목금토일"
BOUNDARY_LOG = "next day boundary refresh at"  # DayBoundaryAlarm.scheduleNext logs the next boundary it planned


def flat(text: str) -> str:
    """Widget text as read: the widget joins the letters of Korean words with U+2060 (WidgetLayout.keepWords) and puts a
    no-break space before times (Labels.NBSP)."""
    return text.replace("⁠", "").replace(" ", " ")


def hhmm(millis: int) -> str:
    return datetime.fromtimestamp(millis / 1000, ZONE).strftime("%H:%M")


def rows_of(name: str) -> list[dict]:
    """Every judgment row of item `name`, in the order recorded."""
    found = query(
        "SELECT d.period_key, d.kind, d.source, d.active, d.void_reason, d.taken_at, d.id FROM dose_record d "
        f"JOIN intake i ON i.id = d.intake_id WHERE i.name = '{name}' ORDER BY d.recorded_at"
    )
    keys = ("key", "kind", "source", "active", "void", "taken_at", "id")
    return [dict(zip(keys, row)) for row in found]


def show_rows(rows: list[dict]) -> str:
    if not rows:
        return "행 없음"
    parts = []
    for row in rows:
        state = "활성" if row["active"] == 1 else f"무효({row['void']})"
        parts.append(f"{row['key']} {row['kind']} {row['source']} {state} {hhmm(row['taken_at'])}")
    return "; ".join(parts)


def wait_rows(name: str, ok, timeout: float = 20) -> list[dict]:
    end = time.time() + timeout
    while True:
        rows = rows_of(name)
        if ok(rows) or time.time() >= end:
            return rows
        time.sleep(1.5)


def widget_tiles(nodes: list[Node]) -> dict[str, Node]:
    return s2.tiles_in(nodes, s2.list_view(nodes))


def widget_texts(nodes: list[Node]) -> list[str]:
    host = s2.widget_host(nodes)
    return [flat(n.text) for n in nodes if n.text and (host is None or s2.within(n, host))]


def drawn_has(nodes: list[Node], line: str) -> bool:
    return any(line in text for text in widget_texts(nodes))


def widget_message(nodes: list[Node]) -> str:
    """The short message the widget shows in its header's place after a tap, if any."""
    return next((t for t in widget_texts(nodes) if "기록됨" in t or "변경 없음" in t), "(메시지 없음)")


def desc_of(nodes: list[Node], name: str) -> str:
    node = widget_tiles(nodes).get(name)
    return node.desc if node is not None else ""


def header_is(head: str, count: str):
    return lambda ns: s2.widget_header(ns) == (head, count)


def header(d: date) -> str:
    return f"{WIDGET_LABEL} · {d.month}/{d.day:02d}"


def tile_reads(name: str, start: str, timeout: float = 30) -> list[Node]:
    """The launcher page read until `name`'s tile is read as starting with `start` (scrolling the list to it)."""
    nodes = s2.widget_page(s2.widget_ready, 30)
    end = time.time() + timeout
    while True:
        if desc_of(nodes, name).startswith(start):
            return nodes
        if name not in widget_tiles(nodes):
            s2.reach_tile(name)
        if time.time() >= end:
            return nodes
        time.sleep(1.5)
        nodes = dump_ui()


def tap_tile(name: str, twice: bool = False) -> Node:
    """Taps `name`'s tile on the widget, in the part of it the list shows; `twice` sends two taps together."""
    s2.widget_page(s2.widget_ready, 30)
    node, box = s2.reach_tile(name)
    if node is None or box is None:
        raise RuntimeError(f"{name} not reached on the widget")
    top, bottom = max(node.bounds[1], box.bounds[1]), min(node.bounds[3], box.bounds[3])
    x, y = (node.bounds[0] + node.bounds[2]) // 2, (top + bottom) // 2
    if twice:
        # Two `input tap` processes started together: their touches land a few milliseconds apart
        sh(f"input tap {x} {y} & input tap {x} {y} & wait")
    else:
        sh(f"input tap {x} {y}")
    return node


# Since Android 11 a window's frame is printed as `Frames: parent=… display=… frame=[l,t][r,b]`; before, `mFrame=…`
# (explore.FRAME reads only the old form, so on API 34 it never finds the keyboard: regression run 43627835213).
WINDOW_FRAME = re.compile(r"\b(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
# The area the keyboard takes from the apps, in `dumpsys window`: `InsetsSource id=… type=ime frame=[…] visible=true`
IME_INSETS = re.compile(r"\btype=ime\b.*?\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\].*?\bvisible=true")
IMS_SHOWN = re.compile(r"\b(mInputShown|mIsInputViewShown|mWindowVisible|mDecorViewVisible)=true")


def keyboard_state() -> tuple[list[str], int | None, str]:
    """What says the on-screen keyboard is up, the top in pixels of the area it covers when a dump gives one, and the
    dump lines read (kept as evidence). Three dumps, as their wording differs across versions: the keyboard's window in
    `dumpsys window windows` (`isVisible=true` and its frame), the keyboard's insets in `dumpsys window` (the area the
    app makes room for with imePadding, preferred for the top) and `dumpsys input_method`."""
    signals: list[str] = []
    kept: list[str] = []
    window_top = insets_top = None
    block = IME_BLOCK.search(sh("dumpsys window windows", check=False))
    if block:
        text = block.group(1)
        kept += [line.strip() for line in text.splitlines()
                 if re.search(r"isVisible=|isOnScreen=|frame=|mViewVisibility=", line, re.I)]
        if re.search(r"\bisVisible=true", text):
            signals.append("키보드 창 isVisible=true")
            frame = WINDOW_FRAME.search(text)
            if frame and int(frame.group(4)) > int(frame.group(2)):
                window_top = int(frame.group(2))
    for line in sh("dumpsys window", check=False).splitlines():
        m = IME_INSETS.search(line)
        if m and int(m.group(4)) > int(m.group(2)):
            kept.append(line.strip())
            signals.append("키보드 인셋 visible=true")
            insets_top = int(m.group(2))
            break
    for line in sh("dumpsys input_method", check=False).splitlines():
        m = IMS_SHOWN.search(line)
        if m:
            kept.append(line.strip())
            signals.append(f"input_method {m.group(1)}=true")
    top = insets_top if insets_top is not None else window_top
    return sorted(set(signals)), top, "\n".join(kept)


def add_item(r: Report, name: str, weekly: bool, choice: str, shot: str | None = None) -> None:
    """First screen > [항목 추가] > name > 매일 + time of day, or 주 1회 + weekday > [저장]. One person: the form shows no
    person field (TASK-015)."""
    to_home()
    tap_scrolled("항목 추가")
    s2.type_name(name)
    tap_exact("주 1회" if weekly else "매일")
    group = exact("무슨 요일에?" if weekly else "언제 먹나요?")
    tap_exact(choice, below=group.bounds[3] if group else -1)
    if shot:
        r.shot(shot)
    tap_exact("저장")
    if tile(name, 20) is None:
        raise RuntimeError(f"tile {name} did not show after [저장]")


def run(r: Report, steps: ShotSteps) -> None:
    base = datetime.now(ZONE).date()

    def at(day: int, hour: int, minute: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=ZONE)

    day0, day1, day2 = base, base + timedelta(days=1), base + timedelta(days=2)
    friday = base + timedelta(days=3)
    while friday.weekday() != 4:
        friday += timedelta(days=1)
    saturday, monday = friday + timedelta(days=1), friday + timedelta(days=3)
    week_key = (friday - timedelta(days=friday.weekday())).isoformat()  # Weekly (주 1회 월): its window starts on Monday
    ok = {"widget": False}
    taken: dict[str, str] = {}  # HH:MM of the doses the later steps read back

    def setup() -> str:
        become_root()
        s2.DENSITY["d"] = physical_density() / 160
        s2.API["n"] = r.api
        zone = set_zone()
        clock = set_clock(at(0, 8, 30))
        font_scale("1.0")
        night(False)
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("logcat -b crash -c", check=False)
        return f"시간대 {zone}, 시계 {clock}, 밀도 {s2.DENSITY['d']:.2f}"

    steps("준비: 서울 시간대, 오늘 08:30", "시간대·시계가 바뀜", setup)

    # 1. First launch (QA 1)
    def first_launch() -> str:
        launch_app(stop_at_intro=True)
        time.sleep(2)
        r.shot("intro-1")
        for text in ("기록 도구예요", "한 약은 한 휴대폰에서 기록해 주세요"):
            if not screen_has(text, 5):
                raise RuntimeError(f"intro page 1 lacks '{text}'")
        tap_exact("다음")
        if not screen_has("홈 화면에 위젯을 놓아 주세요", 5):
            raise RuntimeError("intro page 2 not shown")
        r.shot("intro-2")
        tap_exact("시작하기")
        if exact(HOME_MARK, 15) is None:
            raise RuntimeError("[시작하기] did not open the first screen")
        time.sleep(1)
        r.shot("empty-home")
        texts = [n.text for n in dump_ui()]
        if EMPTY_HOME not in texts or "항목 추가" not in texts:
            raise RuntimeError(f"empty first screen not shown: {texts}")
        return f"안내 두 쪽, [시작하기] 뒤 '{EMPTY_HOME}'와 [항목 추가]"

    steps("1. 앱 첫 실행 (QA 1)", f"첫 실행 안내 두 쪽, 그다음 빈 첫 화면 '{EMPTY_HOME}'와 [항목 추가]", first_launch)

    # 2. Three items (QA 2)
    def three_items() -> str:
        add_item(r, MORNING, False, "아침", "form-morning")
        add_item(r, VITAMIN, False, "저녁")
        add_item(r, WEEKLY, True, "월", "form-weekly")
        to_home()
        time.sleep(1)
        r.shot("home-three-items")
        read = {}
        for name in NAMES:
            node = tile(name, 5)
            read[name] = node.desc if node is not None else ""
        found = query(
            "SELECT i.name, s.type, i.slot_label, s.params_json FROM intake i JOIN schedule_version s ON s.intake_id = i.id "
            "WHERE i.archived_at IS NULL AND s.effective_to IS NULL ORDER BY i.sort_order"
        )
        stored = [(str(n), str(t), str(slot)) for n, t, slot, _ in found]
        want = [(MORNING, "DAILY", "MORNING"), (VITAMIN, "DAILY", "EVENING"), (WEEKLY, "WEEKLY", "None")]
        problems = []
        if stored != want:
            problems.append(f"DB {stored}")
        for name in NAMES:
            state = "이번 주 미완료" if name == WEEKLY else "미완료"
            if not read[name].startswith(f"{name}, {state}"):
                problems.append(f"{name} read '{read[name]}'")
        weekly_params = next((str(p) for n, _, _, p in found if n == WEEKLY), "")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"타일 셋 {list(read.values())}; DB {stored}, Weekly params {weekly_params}"

    steps("2. 항목 등록: 매일 둘, 주 1회 월요일 하나 (QA 2)",
          "첫 화면에 칸 셋(미완료로 읽힘), DB에 Morning DAILY 아침, Vitamin DAILY 저녁, Weekly WEEKLY", three_items)

    # 3. The widget placed (QA 3)
    def widget() -> str:
        try:
            picker = place_widget(r, "widget")
        except RuntimeError as exc:
            r.note(f"위젯 놓기 첫 시도 실패, 20초 뒤 한 번 더: {exc}")
            go_home()
            time.sleep(20)
            picker = place_widget(r, "widget-retry")
        ok["widget"] = True
        nodes = s2.widget_page(header_is(header(day0), "0 / 3"), 30)
        r.shot("widget-placed")
        save_ui(r, "widget-placed")
        problems = []
        if picker:
            problems.append("대상자 고르기가 열림(대상자 한 명이면 열리지 않아야 함)")
        head = s2.widget_header(nodes)
        if head != (header(day0), "0 / 3"):
            problems.append(f"header {head}")
        reads = {}
        for name in NAMES:
            state = "이번 주 미완료" if name == WEEKLY else "미완료"
            got = desc_of(nodes, name) or desc_of(tile_reads(name, f"{name}, {state}", 10), name)
            reads[name] = got
            if got != f"{name}, {state}, {NO_RECORD}":
                problems.append(f"{name} read '{got}'")
        nodes = s2.widget_page(s2.widget_ready, 30)
        if not drawn_has(nodes, NO_RECORD):
            problems.append(f"'{NO_RECORD}' not drawn: {widget_texts(nodes)}")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"머리글 {head}; {'; '.join(reads.values())}; '{NO_RECORD}' 그려짐, 이름 앞 대상자 없음"

    steps("3. 위젯 놓기 (QA 3)",
          f"'{header(day0)}'와 '0 / 3', 칸마다 '<이름>, 미완료, {NO_RECORD}'(주 1회는 '이번 주 미완료')와 '{NO_RECORD}' 글, "
          "대상자 한 명이라 이름 앞 대상자 없음(REQ-018, 프로토타입의 대상자 배지 대신)", widget)

    # 4. Widget size (QA 13)
    def widget_size() -> str:
        if not ok["widget"]:
            raise RuntimeError("no widget on the home screen (step 3)")
        ids = our_widget_ids()
        span, item, _ = read_span(r, "widget-span", launcher_package(), ids[0] if ids else None)
        nodes = s2.settled_widget()
        tiles = widget_tiles(nodes)
        box = s2.list_view(nodes)
        problems = []
        if span != (4, 2):
            problems.append(f"span {span} ({item.get('line', '')})")
        top = min((n.bounds[1] for n in tiles.values()), default=0)
        first_row = [n for n in tiles.values() if box is not None and n.bounds[1] - top <= 8 * s2.DENSITY["d"]]
        for n in first_row:
            name = n.desc.split(", ")[0]
            label = next((x for x in nodes if flat(x.text) == name and s2.within(x, n)), None)
            if not s2.whole(n) or label is None:
                problems.append(f"{name} 칸 {n.bounds}, 이름 글 {'있음' if label else '없음'}")
        cut = [n.desc.split(", ")[0] for n in tiles.values() if not s2.whole(n)]
        arrow = any(n.desc == MORE for n in nodes)
        if len(first_row) < 2:
            problems.append(f"첫 줄 칸 {len(first_row)}개")
        if (cut or len(tiles) < len(NAMES)) and not arrow:
            problems.append(f"가려진 칸 {cut}, 보이는 칸 {list(tiles)}인데 '{MORE}' 화살표 없음")
        r.shot("widget-size")
        if problems:
            raise RuntimeError("; ".join(problems))
        names = [n.desc.split(", ")[0] for n in first_row]
        return (f"런처 칸 {span[0]}×{span[1]}; 첫 줄 {names} 칸과 이름이 다 보임; "
                f"가려진 칸 {cut or '없음'}, 화살표 {'있음' if arrow else '없음'}")

    steps("4. 위젯 기본 크기 (QA 13)",
          f"런처가 놓은 칸 4×2, 첫 줄 칸과 이름이 다 보이고 가려진 칸이 있으면 '{MORE}' 화살표", widget_size)

    # 5. Widget tap (QA 4)
    def widget_tap() -> str:
        if not ok["widget"]:
            raise RuntimeError("no widget on the home screen (step 3)")
        tap_tile(MORNING)
        rows = wait_rows(MORNING, lambda rs: any(x["active"] == 1 for x in rs))
        if len(rows) != 1 or rows[0]["active"] != 1 or rows[0]["source"] != "WIDGET" or rows[0]["kind"] != "TAKEN" \
                or rows[0]["key"] != day0.isoformat():
            raise RuntimeError(f"rows {show_rows(rows)}")
        taken[MORNING] = hhmm(rows[0]["taken_at"])
        want = f"{MORNING}, 복용함, 오늘 {taken[MORNING]}"
        nodes = tile_reads(MORNING, want, 20)
        r.shot("widget-tap")
        nodes = s2.widget_page(header_is(header(day0), "1 / 3"), 25)  # after the 10-second message
        got, head = desc_of(nodes, MORNING), s2.widget_header(nodes)
        problems = []
        if got != want:
            problems.append(f"read '{got}'")
        if head != (header(day0), "1 / 3"):
            problems.append(f"header {head}")
        if not drawn_has(nodes, f"{taken[MORNING]} 먹음"):
            problems.append(f"'{taken[MORNING]} 먹음' not drawn: {widget_texts(nodes)}")
        r.shot("widget-tap-after")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"DB {show_rows(rows)}; '{got}', '{taken[MORNING]} 먹음', 머리글 {head}"

    steps("5. 위젯 탭 (QA 4)", f"DB 활성 1행(WIDGET, {day0.isoformat()}), 칸이 '{MORNING}, 복용함, 오늘 HH:MM'과 'HH:MM 먹음', "
          "머리글 1 / 3", widget_tap)

    # 6. The same tile again (QA 5)
    def tap_again() -> str:
        before = rows_of(MORNING)
        tap_tile(MORNING)
        want = f"{MORNING}: 이미 기록됨 ({taken.get(MORNING, '?')}). 변경 없음"
        end, seen = time.time() + 12, []
        while time.time() < end and not any(want in t for t in seen):
            seen = widget_texts(dump_ui())
            time.sleep(0.5)
        r.shot("widget-tap-again")
        time.sleep(3)
        after = rows_of(MORNING)
        problems = []
        if not any(want in t for t in seen):
            problems.append(f"'{want}' not shown: {seen}")
        if [x["id"] for x in after] != [x["id"] for x in before] or after != before:
            problems.append(f"rows {show_rows(before)} -> {show_rows(after)}")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"'{want}'; DB 그대로 {show_rows(after)}"

    steps("6. 같은 칸 다시 탭 (QA 5)",
          f"머리글 자리에 '{MORNING}: 이미 기록됨 (HH:MM). 변경 없음', DB 변화 없음", tap_again)

    # 7. Two taps at once on another tile (QA 6)
    def double_tap() -> str:
        tap_tile(VITAMIN, twice=True)
        time.sleep(6)
        rows = wait_rows(VITAMIN, lambda rs: bool(rs))
        time.sleep(3)
        rows = rows_of(VITAMIN)
        r.shot("widget-double-tap")
        if len(rows) != 1 or rows[0]["active"] != 1 or rows[0]["source"] != "WIDGET":
            raise RuntimeError(f"rows {show_rows(rows)}")
        taken[VITAMIN] = hhmm(rows[0]["taken_at"])
        return f"DB {show_rows(rows)}, 위젯 메시지 '{widget_message(dump_ui())}'"

    steps("7. 다른 칸 동시에 두 번 탭 (QA 6)", f"{VITAMIN}의 DB 1행(활성, WIDGET)", double_tap)

    # 8. Cancel in the app (QA 7)
    def cancel() -> str:
        launch_app()
        to_home()
        tap(row_button(VITAMIN, "기록 취소"))
        title = exact(CANCEL_ASK, 10)
        if title is None:
            raise RuntimeError(f"no '{CANCEL_ASK}' after [기록 취소]")
        r.shot("app-cancel-ask")
        tap_exact("기록 취소", 10, below=title.bounds[1])
        tapped = time.time()
        rows = wait_rows(VITAMIN, lambda rs: bool(rs) and all(x["active"] != 1 for x in rs))
        msg = message_line()
        r.shot("app-cancelled")
        want = f"{VITAMIN}, 미완료, {NO_RECORD}"
        go_home()
        nodes = tile_reads(VITAMIN, want, 30)
        seconds = time.time() - tapped
        got = desc_of(nodes, VITAMIN)
        r.shot("widget-after-cancel")
        problems = []
        if len(rows) != 1 or rows[0]["active"] == 1 or rows[0]["void"] != "USER_CANCEL":
            problems.append(f"rows {show_rows(rows)}")
        if got != want:
            problems.append(f"widget read '{got}'")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"질문 '{CANCEL_ASK}' 뒤 메시지 '{msg}'; DB {show_rows(rows)}; 위젯 '{got}'(취소 탭부터 {seconds:.0f}초 안, 홈 화면 읽기 포함)"

    steps("8. 앱에서 기록 취소 (QA 7)",
          f"'{CANCEL_ASK}' 뒤 무효(USER_CANCEL), 위젯을 다시 그리라고 하지 않아도 '{VITAMIN}, 미완료, {NO_RECORD}'", cancel)

    # 9. Recorded again on the widget (QA 8)
    def again() -> str:
        tap_tile(VITAMIN)
        rows = wait_rows(VITAMIN, lambda rs: any(x["active"] == 1 for x in rs))
        r.shot("widget-again")
        voided = [x for x in rows if x["active"] != 1]
        live = [x for x in rows if x["active"] == 1]
        if len(rows) != 2 or len(voided) != 1 or voided[0]["void"] != "USER_CANCEL" or len(live) != 1 \
                or live[0]["source"] != "WIDGET":
            raise RuntimeError(f"rows {show_rows(rows)}")
        taken[VITAMIN] = hhmm(live[0]["taken_at"])
        want = f"{VITAMIN}, 복용함, 오늘 {taken[VITAMIN]}"
        got = desc_of(tile_reads(VITAMIN, want, 20), VITAMIN)
        if got != want:
            raise RuntimeError(f"widget read '{got}'")
        return f"DB {show_rows(rows)}; 위젯 '{got}'"

    steps("9. 취소 뒤 위젯으로 다시 기록 (QA 8)", "DB 무효 1행 + 활성 1행(WIDGET), 위젯은 복용함", again)

    # 10. Clock change to the next day (QA 9)
    def next_day() -> str:
        clock = set_clock(at(1, 5, 0))
        nodes = s2.widget_page(header_is(header(day1), "0 / 3"), 60)
        r.shot("widget-next-day")
        save_ui(r, "widget-next-day")
        head = s2.widget_header(nodes)
        problems = [] if head == (header(day1), "0 / 3") else [f"header {head}"]
        reads = []
        for name in (MORNING, VITAMIN):
            want = f"{name}, 미완료, 마지막 복용 어제 {taken.get(name, '?')}"
            got = desc_of(tile_reads(name, want, 15), name)
            reads.append(got)
            if got != want:
                problems.append(f"{name} read '{got}'")
        nodes = dump_ui()
        if not drawn_has(nodes, f"마지막 복용 어제 {taken.get(MORNING, '?')}"):
            problems.append(f"'마지막 복용 어제 {taken.get(MORNING, '?')}' not drawn: {widget_texts(nodes)}")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"시계 {clock}; 머리글 {head}; {'; '.join(reads)}"

    steps("10. 시계를 다음 날 05:00으로 (QA 9)",
          f"앱을 열지 않아도 위젯이 '{header(day1)}' '0 / 3', '마지막 복용 어제 HH:MM'", next_day)

    # 11. The day-boundary alarm (QA 10)
    def boundary() -> str:
        tap_tile(MORNING)
        rows = wait_rows(MORNING, lambda rs: any(x["active"] == 1 and x["key"] == day1.isoformat() for x in rs))
        dose = next((x for x in rows if x["active"] == 1 and x["key"] == day1.isoformat()), None)
        if dose is None:
            raise RuntimeError(f"no day-1 dose: {show_rows(rows)}")
        taken["day1"] = hhmm(dose["taken_at"])
        clock = set_clock(at(2, 3, 54))
        start = int(at(2, 4, 0).timestamp())
        if phone_now() >= start - 30:
            raise RuntimeError(f"the clock was set to 03:54 but it is already {clock} after the system settled")
        found = alarms(BOUNDARY_ALARM)
        planned = [a.millis for a in found if a.millis]
        windows = [a.window for a in found]
        boundary_ms = (start + 5) * 1000
        before_nodes = s2.widget_page(header_is(header(day1), "1 / 3"), 40)
        before = (s2.widget_header(before_nodes), desc_of(before_nodes, MORNING))
        read_at = phone_now()
        r.shot("widget-before-boundary")
        # Wait for 04:00:05 on the phone clock, then read the widget's page without pressing anything
        while phone_now() < start + 5:
            time.sleep(5)
        deadline = start + 6 * 60
        nodes, turned = dump_ui(), None
        while phone_now() < deadline:
            nodes = dump_ui()
            if s2.widget_header(nodes)[0] == header(day2):
                turned = phone_now()
                break
            time.sleep(5)
        time.sleep(12)  # the header settles; a tile read waits for nothing else
        nodes = s2.widget_page(s2.widget_ready, 30)
        r.shot("widget-after-boundary")
        save_ui(r, "widget-after-boundary")
        after = (s2.widget_header(nodes), desc_of(nodes, MORNING))
        log = adb("logcat", "-d", "-v", "time", "-s", "DayBoundaryAlarm:I", check=False)
        next_plan = (datetime(day2.year, day2.month, day2.day, 19, 0, 5)).strftime("%Y-%m-%dT%H:%M:%S")  # day 3 04:00:05 KST in UTC
        replanned = [line.strip() for line in log.splitlines() if BOUNDARY_LOG in line and next_plan in line]
        problems = []
        if before != ((header(day1), "1 / 3"), f"{MORNING}, 복용함, 어제 {taken['day1']}") or read_at >= start:
            problems.append(f"before {before} (read at {datetime.fromtimestamp(read_at, ZONE):%H:%M:%S})")
        if boundary_ms not in planned:
            problems.append(f"no day-boundary alarm at {datetime.fromtimestamp(boundary_ms / 1000, ZONE):%m-%d %H:%M:%S}: "
                            f"{[datetime.fromtimestamp(m / 1000, ZONE).strftime('%m-%d %H:%M:%S') for m in planned]}")
        want_after = ((header(day2), "0 / 3"), f"{MORNING}, 미완료, 마지막 복용 어제 {taken['day1']}")
        if after != want_after:
            problems.append(f"after {after}")
        if not replanned:
            problems.append(f"no '{BOUNDARY_LOG} {next_plan}' in the app's log after 04:00:05")
        turned_at = datetime.fromtimestamp(turned, ZONE).strftime("%H:%M:%S") if turned else "바뀌지 않음"
        if problems:
            raise RuntimeError("; ".join(problems) + f" (위젯이 바뀐 때 {turned_at})")
        return (f"시계 {clock}; 04:00 전 위젯 {before}; 예약된 경계 알람 04:00:05(창 {windows}); 위젯이 {turned_at}에 {after}; "
                f"로그 '{replanned[0][-60:]}'")

    steps("11. 하루 시작 04:00 경계 알람 (QA 10)",
          f"{day1} 기록 뒤 03:54에는 '{header(day1)}' 1 / 3, 04:00:05 경계 알람(정확하지 않은 알람) 뒤 시계를 건드리지 않아도 "
          f"'{header(day2)}' 0 / 3과 Morning 미완료, 앱 로그에 다음 경계 예약", boundary)

    # 12. Weekly window (QA 11)
    def weekly() -> str:
        clock = set_clock(datetime(friday.year, friday.month, friday.day, 10, 0, tzinfo=ZONE))
        tap_tile(WEEKLY)
        rows = wait_rows(WEEKLY, lambda rs: any(x["active"] == 1 for x in rs))
        live = [x for x in rows if x["active"] == 1]
        if len(live) != 1 or live[0]["key"] != week_key or live[0]["source"] != "WIDGET":
            raise RuntimeError(f"rows {show_rows(rows)} (window key {week_key} expected)")
        at_w = hhmm(live[0]["taken_at"])
        fri = desc_of(tile_reads(WEEKLY, f"{WEEKLY}, 복용함, 오늘 금요일 {at_w}", 20), WEEKLY)
        r.shot("widget-weekly-friday")
        set_clock(datetime(saturday.year, saturday.month, saturday.day, 10, 0, tzinfo=ZONE))
        sat_want = f"{WEEKLY}, 복용함, 어제 금요일 {at_w}"
        sat_nodes = s2.widget_page(lambda ns: s2.widget_header(ns)[0] == header(saturday), 60)
        sat = desc_of(tile_reads(WEEKLY, sat_want, 20), WEEKLY)
        sat_head = s2.widget_header(dump_ui())
        r.shot("widget-weekly-saturday")
        set_clock(datetime(monday.year, monday.month, monday.day, 5, 0, tzinfo=ZONE))
        mon_want = f"{WEEKLY}, 이번 주 미완료, 마지막 복용 {friday.month}월 {friday.day}일 금요일 {at_w}"
        s2.widget_page(lambda ns: s2.widget_header(ns)[0] == header(monday), 60)
        mon_nodes = tile_reads(WEEKLY, mon_want, 20)
        mon = desc_of(mon_nodes, WEEKLY)
        line = f"마지막 복용 {friday.month}/{friday.day:02d}(금) {at_w}"
        r.shot("widget-weekly-monday")
        save_ui(r, "widget-weekly-monday")
        problems = []
        if fri != f"{WEEKLY}, 복용함, 오늘 금요일 {at_w}":
            problems.append(f"Friday '{fri}'")
        if sat != sat_want or sat_head != (header(saturday), "1 / 3") or not sat_nodes:
            problems.append(f"Saturday '{sat}' {sat_head}")
        if mon != mon_want:
            problems.append(f"Monday '{mon}'")
        if not drawn_has(mon_nodes, line):
            problems.append(f"'{line}' not drawn: {widget_texts(mon_nodes)}")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"금 {clock}: DB {show_rows(live)}, '{fri}'; 토 '{sat}' {sat_head}; 다음 월 05:00 '{mon}', '{line}'"

    steps("12. 주 1회(월) 금요일 기록 → 토요일 → 다음 월요일 (QA 11)",
          f"금요일 기록({week_key} 창, WIDGET), 토요일 '복용함, 어제 금요일 …'과 1 / 3, 다음 월요일 '이번 주 미완료, 마지막 복용 "
          f"{friday.month}월 {friday.day}일 금요일 …'과 '마지막 복용 {friday.month}/{friday.day:02d}(금) …'", weekly)

    # 13. Saving with the soft keyboard up (QA 12)
    def keyboard() -> str:
        sh("settings put secure show_ime_with_hard_keyboard 1", check=False)
        try:
            to_home()
            tap_scrolled("항목 추가")
            tap_exact("매일")
            group = exact("언제 먹나요?")
            tap_exact("점심", below=group.bounds[3] if group else -1)
            field = next((n for n in dump_ui() if n.cls.endswith("EditText")), None)
            if field is None:
                raise RuntimeError("no name field on the item form")
            tap(field)
            sh(f"input text {TYPED}")
            signals, top, kept = [], None, ""
            for _ in range(10):
                time.sleep(1)
                signals, top, kept = keyboard_state()
                if signals and top is not None:
                    break
            nodes = dump_ui()
            save = next((n for n in nodes if n.text == "저장"), None)
            r.shot("form-keyboard")
            save_ui(r, "form-keyboard")
            (r.out / f"api{r.api}-keyboard-dumpsys.txt").write_text(kept + "\n", encoding="utf-8")
            if save is None:
                raise RuntimeError(f"[저장] not on screen with the keyboard up ({signals}, top {top})")
            _, h = screen_size()
            # The form keeps its footer above the keyboard, so [저장] leaves the bottom of the screen while the keyboard
            # is up (screens2_check.keyboard_up): the sign used when no dump says where the keyboard is
            lifted = save.bounds[3] < h * 0.7
            if not signals and not lifted:
                raise RuntimeError(f"the soft keyboard did not come up: no dump says so and [저장] is at {save.bounds}")
            if top is not None and 0 < top < h:
                above, how = save.bounds[3] <= top + 1, f"키보드 위쪽 {top}px"
            else:
                above, how = lifted, f"키보드 위치를 덤프에서 읽지 못해 화면 높이 {h}px의 70% 위로 봄"
            up = ", ".join(signals) or "덤프 신호 없음, [저장]이 화면 위쪽 70% 안"
            tap(save)
            saved = tile(TYPED, 20) is not None
            r.shot("form-keyboard-saved")
            stored = query(f"SELECT COUNT(*) FROM intake WHERE name = '{TYPED}' AND archived_at IS NULL")[0][0]
            if not above or not saved or stored != 1:
                raise RuntimeError(f"[저장] {save.bounds}, {how} ({up}); tile {saved}; DB {stored}")
            return f"화면 키보드가 뜬 채({up}; {how}) 그 위 [저장] {save.bounds}를 눌러 '{TYPED}' 저장(DB 1행)"
        finally:
            sh("settings put secure show_ime_with_hard_keyboard 0", check=False)

    steps("13. 키보드가 열린 채 저장 (QA 12)", "화면 키보드가 떠 있을 때 [저장]이 키보드 위에 다 보이고, 누르면 저장됨", keyboard)
    check_feedback(r, steps)
    check_card_show(r, steps)

    # 14. App data cleared, then a tile left on the widget tapped (REQ-092)
    def data_cleared() -> str:
        if not ok["widget"]:
            raise RuntimeError("no widget on the home screen (step 3)")
        out = sh(f"pm clear {PKG}", check=False).strip()
        time.sleep(3)
        nodes = s2.widget_page(lambda ns: bool(widget_tiles(ns)) or any(flat(n.text) == EMPTY_WIDGET for n in ns), 30)
        left = list(widget_tiles(nodes))
        before = s2.widget_header(nodes)
        r.shot("widget-data-cleared")
        save_ui(r, "widget-data-cleared")
        if not left:
            # Nothing left to tap: say what the widget showed instead (the check below still asks for no rows)
            empty = any(flat(n.text) == EMPTY_WIDGET for n in nodes)
            if not empty:
                raise RuntimeError(f"pm clear '{out}': no tile and no '{EMPTY_WIDGET}' on the widget: {widget_texts(nodes)}")
            return f"pm clear '{out}' 뒤 탭하기 전에 이미 빈 위젯 '{EMPTY_WIDGET}' {before}: 누를 버튼이 남지 않음"
        tapped = MORNING if MORNING in left else left[0]
        tap_tile(tapped)
        # The tap starts the app in a new process over an empty database; the widget is drawn again from it
        end = time.time() + 40
        while True:
            nodes = dump_ui()
            empty = any(flat(n.text) == EMPTY_WIDGET for n in nodes)
            if (empty and not widget_tiles(nodes)) or time.time() >= end:
                break
            time.sleep(2)
        message = widget_message(nodes)
        after = s2.widget_header(nodes)
        r.shot("widget-data-cleared-tap")
        save_ui(r, "widget-data-cleared-tap")
        try:
            rows, items = query("SELECT (SELECT COUNT(*) FROM dose_record), (SELECT COUNT(*) FROM intake)")[0]
        except sqlite3.Error as exc:  # no database at all: the tap never reached the app
            rows, items = None, f"DB 없음({exc})"
        problems = []
        if rows != 0 or items != 0:
            problems.append(f"DB dose_record {rows}, intake {items}")
        if not empty or widget_tiles(nodes):
            problems.append(f"widget not empty: tiles {list(widget_tiles(nodes))}, {widget_texts(nodes)}")
        if problems:
            raise RuntimeError(f"pm clear '{out}', tiles left {left}, tapped {tapped}: " + "; ".join(problems))
        return (f"pm clear '{out}' 뒤 위젯에 남은 칸 {left}(머리글 {before}); {tapped} 탭 → DB 판정 {rows}행·항목 {items}개, "
                f"위젯 '{EMPTY_WIDGET}' {after}, 위젯 메시지 '{message}'")

    steps("14. 앱 데이터를 지운 뒤 남은 위젯 버튼 (REQ-092)",
          f"`pm clear` 뒤 위젯에 남은 칸을 누르면 판정 행 없음, 위젯이 칸 없이 '{EMPTY_WIDGET}'로 다시 그려짐", data_cleared)

    check_card_export(r, steps)
    # TC-108 scrolls the launcher widget and adds a person. Run it after regressions that rely on the earlier fixtures.
    check_card_privacy(r, steps)

    # 15. No crash (QA 1)
    def no_crash() -> str:
        crash = adb("logcat", "-d", "-b", "crash", check=False)
        ours = [line for line in crash.splitlines() if PKG in line]
        if ours:
            raise RuntimeError(f"crash log: {ours[:5]}")
        return "크래시 기록에 이 앱 없음"

    steps("15. 실행 내내 크래시 없음 (QA 1)", "logcat crash 버퍼에 이 앱이 없음", no_crash)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()  # also counts this boot, so become_root waits for the emulator's own commands after it
    api = api_level()
    r = Report(args.out, api, f"에뮬레이터 회귀 확인 API {api} (TASK-034, 2026-11-11 프로토타입 13항목)")
    steps = ShotSteps(r)
    try:
        uninstall()
        install(args.apk)
        run(r, steps)
    except Exception as exc:
        traceback.print_exc()
        r.check("회귀 확인 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
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
