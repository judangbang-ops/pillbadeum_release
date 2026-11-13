"""Walks the app the way a person would, on one emulator (API 34), and takes a screenshot at each step.

Why: nobody on the team has an Android phone for now (2026-11-12, 동희님 uses an iPhone), and 동희님 asked
to try the app on an emulator ("블루스택 이런거에서라도 해봐", 2026-11-12 15:02 KST). The screenshots let a
person see the app and the widget without a phone. This is exploration, not the release gate: a step
fails only when it cannot be done (a button is missing, the widget cannot be placed). What the screens
look like is judged by a person, or by Claude from the screenshots, in docs/07-qa/.

Flow (clock set to the run's date in Asia/Seoul, so the screens show Korean dates and times):
   1. First launch at 08:30: the intro's two pages (SCR-005, TASK-022), [시작하기], then the empty home.
   2. [항목 추가]: the empty form, then a family member "Mom" added with [+ 가족 추가], and the form closed with [×].
   3. Three items: Vitamin (나, 매일 아침), BPpill (Mom, 매일 저녁), Weekly (나, 주 1회 월).
   4. [먹었어] on Vitamin's tile in the app.
   5. The home-screen widget placed the way a person does it (release_check.place_widget), 전체 picked in
      the person picker that opens on the drop with two people (TASK-012),
      BPpill tapped on the widget, then tapped again (already recorded).
   6. [기록 취소] on Vitamin, confirmed in the question, then [먹었어] again.
   7. Editing: Vitamin changed to 주 1회, Weekly archived ([보관] > confirm). Then the system BACK
      (BUG-001): on the form it goes back to the first screen, and on the first screen it leaves the app.
   8. Larger text: font scale 1.3 and 2.0, app and widget. Then dark mode.
   9. Reboot: the widget after the phone restarts.
  10. The next morning 08:30: the widget and the app start a new day.
  11. 02:00 the day after: before the day start (04:00) the day has not changed yet.
  12. The app left open from 03:55 past 04:00 (TASK-017, SCR-004#stale): the first screen turns to the new day
      without being opened again.

Names are typed in English because `adb shell input text` cannot type Korean.
Notifications are not checked: the reminder feature is not built yet (R3).

Writes report.md, screenshots (api34-NN-name.png) and screen dumps to --out.
Exits 1 when a step could not be done. Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
import traceback
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from backup_restore_check import (
    CLOSED_DIALOGS,
    HOME_MARK,
    PKG,
    Node,
    Report,
    api_level,
    become_root,
    dump_ui,
    install,
    launch_app,
    read_state,
    screen_has,
    sh,
    adb,
    button_for,
    tap,
    uninstall,
    wait_boot,
)
from release_check import go_home, place_widget, save_ui, screen_size

ZONE = ZoneInfo("Asia/Seoul")
ME = "나"  # ManagePeople.DEFAULT_PERSON_NAME
MOM = "Mom"
ADD_FAMILY = "+ 가족 추가"  # the person field of the item form with one person (ItemScreen.kt, TASK-015)
VITAMIN = "Vitamin"
BP = "BPpill"
WEEKLY = "Weekly"


# ------------------------------------------------------------------------------------------------
# Screen helpers on top of backup_restore_check


def exact(text: str, timeout: float = 15, below: int = -1) -> Node | None:
    """A node whose text is exactly `text` (find() matches substrings: "월" is also in "11월")."""
    end = time.time() + timeout
    while True:
        hits = [n for n in dump_ui() if n.text == text and n.bounds[1] > below]
        if hits:
            return hits[0]
        if time.time() >= end:
            return None
        time.sleep(1)


def tap_exact(text: str, timeout: float = 15, below: int = -1) -> None:
    node = exact(text, timeout, below)
    if node is None:
        raise RuntimeError(f"'{text}' not on screen")
    tap(node)


def tap_desc(desc: str, timeout: float = 15) -> None:
    """Taps the node read as `desc`: an icon button such as the form's [×] (`닫기`, TASK-016)."""
    end = time.time() + timeout
    while True:
        node = next((n for n in dump_ui() if n.desc == desc), None)
        if node is not None:
            tap(node)
            return
        if time.time() >= end:
            raise RuntimeError(f"no button read as '{desc}'")
        time.sleep(1)


def tap_scrolled(text: str, shot: tuple[Report, str] | None = None) -> None:
    """Taps the node whose text is exactly `text`, scrolling the screen up to find it: [항목 추가] sits at the end of
    the first screen's list, below the fold with large text (TASK-016). `shot` takes a picture before the tap."""
    for _ in range(6):
        node = exact(text, 2)
        if node is not None:
            if shot:
                shot[0].shot(shot[1])
            tap(node)
            return
        scroll_down()
    raise RuntimeError(f"'{text}' not found after scrolling")


def tile(name: str, timeout: float = 15) -> Node | None:
    """The text of `name`'s tile on the app's first screen. TalkBack reads a tile as one sentence that starts with
    the name, or the person and the name: `나 Vitamin, 미완료, …` (TASK-016). Its words are not nodes of their own,
    so the name is matched in that description."""
    end = time.time() + timeout
    while True:
        hits = [n for n in dump_ui() if n.desc.split(", ")[0].endswith(name)]
        if hits:
            return hits[0]
        if time.time() >= end:
            return None
        time.sleep(1)


def type_into(label: str, text: str) -> None:
    """Types into the text field read as `label` (`약 이름`, `가족 이름`).

    The field's description may sit on a node of its own with the field's bounds instead of on the EditText
    (Compose Multiplatform 1.11 on API 34, run 43357220722: the EditText had no text or description and a View
    with the same bounds read '가족 이름'), so a field also matches by the bounds of a node read as `label`."""
    nodes = dump_ui()
    boxes = [n for n in nodes if n.cls.endswith("EditText")]
    labelled = {n.bounds for n in nodes if n.desc == label}
    box = next((n for n in boxes if n.desc == label or n.bounds in labelled), None)
    if box is None:
        raise RuntimeError(
            f"text field '{label}' not found (fields on screen: {[n.desc for n in boxes]}, "
            f"read as '{label}': {sorted(labelled)})"
        )
    tap(box)
    sh(f"input text {text}")
    time.sleep(1)
    hide_keyboard()


IME_BLOCK = re.compile(r"Window\{[^}]*InputMethod\}:(.*?)(?=\n\s*Window #|\Z)", re.S)
FRAME = re.compile(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
KEYBOARD_NOTES: list[str] = []


def keyboard_height() -> int:
    """Height in pixels of the on-screen keyboard window while it is visible, else 0."""
    block = IME_BLOCK.search(sh("dumpsys window windows", check=False))
    if not block or "isVisible=true" not in block.group(1):
        return 0
    frame = FRAME.search(block.group(1))
    return max(0, int(frame.group(4)) - int(frame.group(2))) if frame else 0


def hide_keyboard() -> None:
    """Never presses BACK.

    Until TASK-016 the app had no back handler, so a BACK that the keyboard did not take closed the
    whole app (fourth run, 2026-11-12: after typing a name the form vanished to the launcher). Now
    BACK on a form goes to the first screen without saving (BUG-001), which would still lose the
    form. The emulator runs with a hardware keyboard and show_ime_with_hard_keyboard 0, so no soft
    keyboard is drawn. If one is drawn anyway, this only notes it for the report; a covered button
    then fails the step.
    """
    h = keyboard_height()
    if h > 0:
        KEYBOARD_NOTES.append(f"화면 키보드가 {h}px 높이로 떠 있었음")


def ensure_home() -> None:
    """The app's first screen ([지난 기록] in its top bar), starting the app again when another screen is open."""
    if exact(HOME_MARK, 3) is None:
        sh(f"am force-stop {PKG}")
        launch_app()


def scroll_down() -> None:
    w, h = screen_size()
    sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
    time.sleep(1)


def row_button(name: str, label: str) -> Node:
    """The [먹었어] or [기록 취소] button in `name`'s tile on the app's first screen."""
    row = tile(name)
    if row is None:
        raise RuntimeError(f"tile {name} not on the home")
    button = button_for(row, label)
    if button is None:
        raise RuntimeError(f"no [{label}] in the {name} tile")
    return button


def message_line() -> str:
    """The short message the app or widget shows after a tap, if one of the known ones is on screen."""
    for n in dump_ui():
        if any(key in n.text for key in ("기록했어요", "이미 기록됨", "취소했어요", "보관했어요", "기록됨", "변경 없음")):
            return n.text
    return "(메시지 없음)"


def records() -> str:
    state = read_state()
    return f"활성 판정 {state.active_records}건, source {state.sources}"


# ------------------------------------------------------------------------------------------------
# Clock and system settings (adb root on the google_apis image)


def set_zone() -> str:
    sh("cmd alarm set-timezone Asia/Seoul", check=False)
    if sh("getprop persist.sys.timezone").strip() != "Asia/Seoul":
        # Android 8 has no `cmd alarm set-timezone`. IAlarmManager.setTimeZone (transaction 3) tells the system server
        # and sends TIMEZONE_CHANGED, which setprop alone does not: on API 26 the alarm dump and the lock-screen clock
        # stayed in UTC (reminder check, run 43360309166).
        sh("service call alarm 3 s16 Asia/Seoul", check=False)
    if sh("getprop persist.sys.timezone").strip() != "Asia/Seoul":
        sh("setprop persist.sys.timezone Asia/Seoul", check=False)
    return sh("getprop persist.sys.timezone").strip()


def put_clock(target: int) -> None:
    """Sets the phone clock to `target` (Unix seconds), or raises."""
    sh(f"cmd alarm set-time {target * 1000}", check=False)
    now = int(sh("date +%s").strip() or 0)
    if abs(now - target) > 120:
        utc = datetime.fromtimestamp(target, timezone.utc)
        sh(f"date -u {utc.strftime('%m%d%H%M%Y.%S')}", check=False)
        now = int(sh("date +%s").strip() or 0)
    if abs(now - target) > 120:
        raise RuntimeError(f"clock not set: wanted {target}, phone says {now}")


def set_clock(at: datetime) -> str:
    """Turns off network time and sets the clock. `cmd alarm set-time` sends TIME_SET like a person
    changing the time in Settings; `date` is the fallback."""
    sh("settings put global auto_time 0", check=False)
    sh("settings put global auto_time_zone 0", check=False)
    # Run 7 (2026-11-12) set 08:30, and a minute later the phone was back on the real time: the
    # setting alone did not stop automatic time on API 34, so turn the time detector off as well.
    sh("cmd time_detector set_auto_detection_enabled false", check=False)
    target = int(at.timestamp())
    put_clock(target)
    started = time.time()
    settle()
    expected = target + int(time.time() - started)
    now = int(sh("date +%s").strip() or 0)
    if abs(now - expected) > 120:
        # Seen once on run 7: the time went back while System UI was stalling. Set it once more.
        put_clock(expected)
        settle()
        expected = target + int(time.time() - started)
        now = int(sh("date +%s").strip() or 0)
        if abs(now - expected) > 120:
            raise RuntimeError(f"clock went back after it was set: wanted about {expected}, phone says {now}")
    return sh("date '+%Y-%m-%d %H:%M %Z'").strip()


def settle(quiet: float = 30, longest: float = 180) -> None:
    """Waits until no system ANR dialog has shown for `quiet` seconds (at most `longest`).

    A clock change sends TIME_SET to every app, and on the CI emulator system_server and Google
    Play services then stall for about a minute (fifth run, 2026-11-12: 42 "isn't responding"
    dialogs, the first right after setting 08:30, and one ate the tap on [추가]). dump_ui closes
    each dialog with [Wait]; this keeps reading the screen until the dialogs stop coming.
    """
    start = time.time()
    seen = len(CLOSED_DIALOGS)
    last_dialog = start
    while time.time() - start < longest:
        dump_ui()
        if len(CLOSED_DIALOGS) != seen:
            seen, last_dialog = len(CLOSED_DIALOGS), time.time()
        if time.time() - last_dialog >= quiet:
            return
        time.sleep(3)


def phone_now() -> int:
    """The phone clock in Unix seconds (it was set apart from the computer's clock)."""
    return int(sh("date +%s").strip() or 0)


def day_label(d) -> str:
    """The first screen's date line for the logical date `d`: `11월 12일 목요일` (Labels.longDate)."""
    return f"{d.month}월 {d.day}일 {'월화수목금토일'[d.weekday()]}요일"


def font_scale(scale: str) -> None:
    sh(f"settings put system font_scale {scale}")
    time.sleep(3)


def night(on: bool) -> None:
    sh(f"cmd uimode night {'yes' if on else 'no'}")
    time.sleep(3)


LOG_PARTS: list[str] = []


def reboot() -> None:
    # The log buffer does not survive a reboot (fourth run, 2026-11-12: the morning steps were lost)
    LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
    adb("reboot", check=False)
    time.sleep(10)
    adb("wait-for-device", timeout=300)
    wait_boot(600)
    become_root()
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    time.sleep(10)
    # Run 7: System UI kept stalling for over five minutes after the reboot, so wait longer here
    settle(quiet=30, longest=420)


# ------------------------------------------------------------------------------------------------
# Flow


class Steps:
    """Runs each step; a step that raises is one failed check, and the walk goes on."""

    def __init__(self, r: Report) -> None:
        self.r = r

    def __call__(self, title: str, expected: str, fn, look: bool = False) -> bool:
        """look=True: the step only takes screenshots, so a pass means it ran, not that the screen is right."""
        try:
            actual = fn() or "됨"
            if look:
                actual += " (캡처만, 화면은 사람이 봄)"
            self.r.check(title, expected, actual, True)
            return True
        except Exception as exc:  # exploration: record and go on
            traceback.print_exc()
            self.r.check(title, expected, f"{type(exc).__name__}: {exc}", False)
            tag = f"fail-{len(self.r.rows):02d}"
            try:
                self.r.shot(tag)
                save_ui(self.r, tag)
                # Back to a known place so one failed step does not break the next ones
                hide_keyboard()
                sh(f"am force-stop {PKG}")
            except Exception:
                pass
            return False


def add_item(r: Report, name: str, person: str, weekly: bool, choice: str, shot: str | None = None) -> str:
    """Home > [항목 추가] > name > person > 매일 + time of day, or 주 1회 + weekday > [저장]."""
    ensure_home()
    tap_scrolled("항목 추가")
    type_into("약 이름", name)
    tap_exact(person)
    tap_exact("주 1회" if weekly else "매일")
    # The weekday and time-of-day cells sit under their question; "월" alone is also in other texts.
    group = exact("무슨 요일에?" if weekly else "언제 먹나요?")
    tap_exact(choice, below=group.bounds[3] if group else -1)
    if shot:
        r.shot(shot)
    tap_exact("저장")
    if tile(name, 20) is None:
        raise RuntimeError(f"tile {name} did not show after [저장]")
    return f"{name} 저장됨"


def run(r: Report, steps: Steps) -> None:
    base = datetime.now(ZONE).date()

    def at(day: int, hour: int, minute: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=ZONE)

    # 0. phone setup
    def setup() -> str:
        become_root()
        zone = set_zone()
        clock = set_clock(at(0, 8, 30))
        font_scale("1.0")
        night(False)
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        return f"시간대 {zone}, 시계 {clock}"

    steps("준비: 서울 시간대, 오늘 08:30", "시간대·시계가 바뀜", setup)

    # 1. first launch: the intro (SCR-005, TASK-022), then the empty home
    def first_launch() -> str:
        launch_app(stop_at_intro=True)
        time.sleep(2)
        r.shot("intro-1")
        for text in ("기록 도구예요", "한 약은 한 휴대폰에서 기록해 주세요"):
            if not screen_has(text, 5):
                raise RuntimeError(f"intro page 1 lacks '{text}'")
        tap_exact("다음")
        time.sleep(1)
        r.shot("intro-2")
        if not screen_has("홈 화면에 위젯을 놓아 주세요", 5):
            raise RuntimeError("intro page 2 not shown")
        # The launcher's request is not used here: step 5 places the widget by hand, as page 2 explains.
        pin = "있음" if exact("위젯 추가", 3) is not None else "없음(손으로 놓는 법만)"
        tap_exact("시작하기")
        if exact(HOME_MARK, 15) is None:
            raise RuntimeError("[시작하기] did not open the first screen")
        time.sleep(1)
        r.shot("first-launch")
        empty = screen_has("아직 복용 항목이 없어요", 5)
        if not empty:
            raise RuntimeError("empty-home text not shown")
        return f"안내 두 쪽([위젯 추가] {pin}), [시작하기] 뒤 빈 홈과 안내 문구"

    steps("1. 처음 실행", "첫 실행 안내 두 쪽, [시작하기] 뒤 빈 홈과 [항목 추가] 안내", first_launch)

    # 2. the add form, and adding a family member
    def add_form() -> str:
        tap_exact("항목 추가")
        time.sleep(1)
        r.shot("add-form-empty")
        # One person: the person field is folded and only [+ 가족 추가] shows (TASK-015, REQ-018).
        chips = [n.text for n in dump_ui() if n.text in (ME, ADD_FAMILY)]
        tap_exact(ADD_FAMILY)
        type_into("가족 이름", MOM)
        r.shot("add-person-open")
        tap_exact("추가")
        if exact(MOM, 10) is None:
            raise RuntimeError(f"person {MOM} did not show")
        r.shot("add-person-done")
        tap_desc("닫기")
        if exact(HOME_MARK, 10) is None:
            raise RuntimeError("[×] did not go back to the first screen")
        return f"폼의 대상자 칸 {chips}, {MOM} 생김, [×]로 첫 화면"

    steps("2. 항목 추가 화면과 대상자 추가", "빈 폼(대상자 칸 접힘, [+ 가족 추가]), 가족 추가 뒤 나·Mom, [×]로 닫힘", add_form)

    # 3. three items
    steps("3-1. 매일 아침 항목 (나)", "목록에 생김", lambda: add_item(r, VITAMIN, ME, False, "아침", "form-vitamin"))
    steps("3-2. 매일 저녁 항목 (Mom)", "목록에 생김", lambda: add_item(r, BP, MOM, False, "저녁"))
    steps("3-3. 주 1회 월요일 항목 (나)", "목록에 생김", lambda: add_item(r, WEEKLY, ME, True, "월", "form-weekly"))

    def home_three() -> str:
        ensure_home()
        time.sleep(1)
        r.shot("home-three-items")
        shown = [n for n in (VITAMIN, BP, WEEKLY) if tile(n, 3) is not None]
        if len(shown) != 3:
            raise RuntimeError(f"home shows {shown}")
        return "항목 3개"

    steps("3-4. 항목 3개인 홈", "타일 3개와 이번 주 약통", home_three)

    # 4. record in the app
    def record_app() -> str:
        ensure_home()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(1)
        msg = message_line()
        r.shot("app-recorded")
        return f"메시지 '{msg}', {records()}"

    steps("4. 앱에서 [먹었어]", "기록 1건, 타일이 채워지고 버튼이 [기록 취소]로 바뀜", record_app)

    # 5. widget
    placed = {"ok": False}

    def widget() -> str:
        try:
            place_widget(r, "widget")  # raises when the launcher cannot be driven
        except RuntimeError as exc:
            # First run: the home menu did not open once. One more try from a fresh home screen.
            r.note(f"위젯 놓기 첫 시도 실패, 한 번 더: {exc}")
            go_home()
            time.sleep(3)
            place_widget(r, "widget-retry")
        placed["ok"] = True
        time.sleep(3)
        r.shot("widget-home")
        return "홈 화면에 위젯"

    steps("5-1. 위젯 놓기", "홈 화면에 위젯, 항목 3개", widget)

    def widget_tap() -> str:
        go_home()
        node = exact(BP, 15)
        if node is None:
            raise RuntimeError(f"{BP} not on the widget")
        tap(node)
        time.sleep(3)
        msg = message_line()
        r.shot("widget-tap")
        return f"메시지 '{msg}', {records()}"

    def widget_again() -> str:
        node = exact(BP, 15)
        if node is None:
            raise RuntimeError(f"{BP} not on the widget")
        tap(node)
        time.sleep(3)
        msg = message_line()
        r.shot("widget-tap-again")
        return f"메시지 '{msg}', {records()}"

    if placed["ok"]:
        steps("5-2. 위젯에서 BPpill 탭", "기록 2건(WIDGET 1), 짧은 메시지", widget_tap)
        steps("5-3. 같은 항목 다시 탭", "기록 그대로 2건, '이미 기록됨' 메시지", widget_again)

    def app_after_widget() -> str:
        launch_app()
        time.sleep(2)
        r.shot("app-after-widget")
        state = records()
        if "WIDGET" not in state:
            raise RuntimeError(f"no widget record: {state}")
        return state

    steps("5-4. 위젯 기록 뒤 앱", "BPpill이 완료로 보임", app_after_widget)

    # 6. void and record again
    def void() -> str:
        # The app asks first (REQ-029). The sixth run expected no question and failed at 6-2.
        ensure_home()
        before = read_state().active_records
        tap(row_button(VITAMIN, "기록 취소"))
        title = exact("기록을 취소할까요?", 10)
        if title is None:
            raise RuntimeError("no '기록을 취소할까요?' question after [기록 취소]")
        r.shot("app-void-confirm")
        tap_exact("기록 취소", 10, below=title.bounds[1])
        time.sleep(1)
        msg = message_line()
        r.shot("app-voided")
        after = read_state().active_records
        if after != before - 1:
            raise RuntimeError(f"active records {before} -> {after}, expected one fewer")
        return f"확인 대화상자 뒤 취소, 메시지 '{msg}', {records()}"

    def again() -> str:
        ensure_home()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(1)
        msg = message_line()
        r.shot("app-recorded-again")
        return f"메시지 '{msg}', {records()}"

    steps("6-1. [기록 취소]", "'기록을 취소할까요?' 확인 뒤 활성 판정이 하나 줄어듦", void)
    steps("6-2. 다시 [먹었어]", "다시 기록됨", again)

    # 7. edit and delete
    def edit() -> str:
        ensure_home()
        node = tile(VITAMIN)
        if node is None:
            raise RuntimeError(f"tile {VITAMIN} not on the home")
        tap(node)
        time.sleep(1)
        r.shot("edit-form")
        tap_exact("주 1회")
        r.shot("edit-weekly")
        tap_exact("저장")
        exact(HOME_MARK, 10)
        time.sleep(1)
        r.shot("home-after-edit")
        return f"{records()}"

    def delete() -> str:
        ensure_home()
        node = tile(WEEKLY)
        if node is None:
            raise RuntimeError(f"tile {WEEKLY} not on the home")
        tap(node)
        time.sleep(1)
        tap_scrolled("보관")  # at the end of the edit form
        time.sleep(1)
        r.shot("delete-confirm")
        # The question is "Weekly을(를) 보관할까요?"; its [보관] is below the title (REQ-014, SCR-006)
        title = exact(f"{WEEKLY}을(를) 보관할까요?", 10)
        if title is None:
            raise RuntimeError("no archive question after [보관]")
        tap_exact("보관", 10, below=title.bounds[1])
        time.sleep(2)
        msg = message_line()
        r.shot("home-after-delete")
        gone = tile(WEEKLY, 3) is None
        return f"메시지 '{msg}', 목록에서 {'사라짐' if gone else '남아 있음'}"

    def back() -> str:
        # BUG-001 (TASK-016): BACK on a form goes to the first screen, and BACK there leaves the app.
        ensure_home()
        tap_scrolled("항목 추가")
        if exact("약 이름", 10) is None:
            raise RuntimeError("the item form did not open")
        sh("input keyevent KEYCODE_BACK")
        if exact(HOME_MARK, 5) is None and keyboard_height() > 0:
            sh("input keyevent KEYCODE_BACK")  # the first BACK only closed a keyboard
        if exact(HOME_MARK, 10) is None:
            raise RuntimeError("BACK on the form did not go to the first screen")
        r.shot("back-to-home")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(2)
        resumed = [line.strip() for line in sh("dumpsys activity activities", check=False).splitlines()
                   if "ResumedActivity" in line]
        r.shot("back-left-app")
        if any(PKG in line for line in resumed):
            raise RuntimeError(f"BACK on the first screen did not leave the app: {resumed[:2]}")
        launch_app()
        return "폼 → 첫 화면, 첫 화면 → 앱을 나감"

    steps("7-1. 항목 수정 (매일 → 주 1회)", "안내 문구, 저장 후 홈", edit)
    steps("7-2. 항목 보관", "확인 대화상자, 목록에서 사라짐", delete)
    steps("7-3. 시스템 뒤로 가기 (BUG-001)", "폼에서 첫 화면으로, 첫 화면에서 앱을 나감", back)

    # 8. larger text and dark mode
    def bigger(scale: str) -> str:
        font_scale(scale)
        launch_app()
        time.sleep(2)
        r.shot(f"app-font-{scale}")
        # With large text the tiles go one a row (TASK-016) and [항목 추가] moves below the fold.
        tap_scrolled("항목 추가", shot=(r, f"app-font-{scale}-end"))
        time.sleep(1)
        r.shot(f"form-font-{scale}")
        tap_desc("닫기")
        if placed["ok"]:
            go_home()
            time.sleep(3)
            r.shot(f"widget-font-{scale}")
        return f"글자 크기 {scale}"

    steps("8-1. 글자 크기 1.3", "잘리거나 겹치지 않음", lambda: bigger("1.3"), look=True)
    steps("8-2. 글자 크기 2.0", "잘리거나 겹치지 않음", lambda: bigger("2.0"), look=True)

    def dark() -> str:
        font_scale("1.0")
        night(True)
        launch_app()
        time.sleep(2)
        r.shot("app-dark")
        if placed["ok"]:
            go_home()
            time.sleep(3)
            r.shot("widget-dark")
        night(False)
        return "어두운 화면"

    steps("8-3. 어두운 화면", "글자가 보임", dark, look=True)

    # 9. reboot
    def restart() -> str:
        reboot()
        set_zone()
        clock = set_clock(at(0, 20, 0))
        go_home()
        time.sleep(5)
        settle()
        r.shot("widget-after-reboot")
        return f"다시 켠 뒤 시계 {clock}"

    steps("9. 폰 다시 켜기", "위젯이 다시 그려짐", restart, look=True)

    # 10. the next morning
    def next_morning() -> str:
        clock = set_clock(at(1, 8, 30))
        go_home()
        time.sleep(8)
        settle()
        r.shot("widget-next-morning")
        launch_app()
        time.sleep(2)
        r.shot("app-next-morning")
        return f"시계 {clock}"

    steps("10. 다음 날 08:30", "위젯·앱이 새 날로 바뀜(매일 항목이 안 먹음으로)", next_morning, look=True)

    # 11. before the day start
    def before_day_start() -> str:
        clock = set_clock(at(2, 2, 0))
        go_home()
        time.sleep(8)
        settle()
        r.shot("widget-0200")
        launch_app()
        time.sleep(2)
        r.shot("app-0200")
        return f"시계 {clock}"

    steps("11. 그다음 날 02:00", "하루 시작 04:00 전이라 앞날로 셈", before_day_start, look=True)

    # 12. the day start with the app left open (TASK-017)
    def across_day_start() -> str:
        clock = set_clock(at(2, 3, 55))
        start = phone_now()
        boundary = int(at(2, 4, 0).timestamp())
        if start > boundary - 60:
            raise RuntimeError(f"the clock settled too late to watch 04:00 pass ({clock})")
        before, after = day_label(at(1, 12).date()), day_label(at(2, 12).date())
        launch_app()
        if exact(before, 10) is None:
            raise RuntimeError(f"before 04:00 the first screen does not show {before}")
        r.shot("app-0355")
        # The app is not touched from here on: only the screen is read until the new day shows.
        while phone_now() < boundary + 90:
            if exact(after, 0) is not None:
                break
            time.sleep(5)
        seen = phone_now()
        r.shot("app-0400")
        if exact(after, 0) is None:
            raise RuntimeError(f"{after} not shown by {datetime.fromtimestamp(seen, ZONE):%H:%M:%S}")
        if seen < boundary:
            raise RuntimeError(f"{after} shown before 04:00")
        return f"{before} → {after} ({datetime.fromtimestamp(seen, ZONE):%H:%M:%S}에 봄, 시계 {clock})"

    steps("12. 앱을 켜 둔 채 04:00 넘기기", "다시 열지 않아도 첫 화면이 새 날짜로 바뀜(SCR-004#stale)", across_day_start)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    api = api_level()
    r = Report(args.out, api, f"에뮬레이터 둘러보기 API {api}")
    try:
        uninstall()
        install(args.apk)
        run(r, Steps(r))
    except Exception as exc:
        traceback.print_exc()
        r.check("둘러보기 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        for note in sorted(set(KEYBOARD_NOTES)):
            r.note(note)
        r.write()
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        log = "\n--------- reboot ---------\n".join(LOG_PARTS)
        (args.out / f"api{api}-logcat.txt").write_text(log, encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
