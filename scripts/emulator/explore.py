"""Walks the app the way a person would, on one emulator (API 34), and takes a screenshot at each step.

Why: nobody on the team has an Android phone for now (2026-11-12, 동희님 uses an iPhone), and 동희님 asked
to try the app on an emulator ("블루스택 이런거에서라도 해봐", 2026-11-12 15:02 KST). The screenshots let a
person see the app and the widget without a phone. This is exploration, not the release gate: a step
fails only when it cannot be done (a button is missing, the widget cannot be placed). What the screens
look like is judged by a person, or by Claude from the screenshots, in docs/07-qa/.

Flow (clock set to the run's date in Asia/Seoul, so the screens show Korean dates and times):
   1. First launch at 08:30: the empty home.
   2. [항목 추가]: the empty form, then a family member "Mom" added with [+ 사람 추가].
   3. Three items: Vitamin (나, 매일 아침), BPpill (Mom, 매일 저녁), Weekly (나, 주 1회 월).
   4. [먹었어] on Vitamin in the app.
   5. The home-screen widget placed the way a person does it (release_check.place_widget),
      BPpill tapped on the widget, then tapped again (already recorded).
   6. [기록 취소] on Vitamin, then [먹었어] again.
   7. Editing: Vitamin changed to 주 1회, Weekly deleted ([항목 삭제] > confirm).
   8. Larger text: font scale 1.3 and 2.0, app and widget. Then dark mode.
   9. Reboot: the widget after the phone restarts.
  10. The next morning 08:30: the widget and the app start a new day.
  11. 02:00 the day after: before the day start (04:00) the day has not changed yet.

Names are typed in English because `adb shell input text` cannot type Korean.
Notifications are not checked: the reminder feature is not built yet (R3).

Writes report.md, screenshots (api34-NN-name.png) and screen dumps to --out.
Exits 1 when a step could not be done. Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import sys
import time
import traceback
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from backup_restore_check import (
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
    tap,
    uninstall,
    wait_boot,
)
from release_check import go_home, place_widget, save_ui, screen_size

ZONE = ZoneInfo("Asia/Seoul")
ME = "나"  # ManagePeople.DEFAULT_PERSON_NAME
MOM = "Mom"
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


def fields() -> list[Node]:
    return [n for n in dump_ui() if n.cls.endswith("EditText")]


def type_into(index: int, text: str) -> None:
    boxes = fields()
    if len(boxes) <= index:
        raise RuntimeError(f"text field {index + 1} not found ({len(boxes)} on screen)")
    tap(boxes[index])
    sh(f"input text {text}")
    time.sleep(1)
    hide_keyboard()


def hide_keyboard() -> None:
    """The on-screen keyboard covers [저장] (first run, 2026-11-12), so close it after typing."""
    for _ in range(2):
        if "mInputShown=true" not in sh("dumpsys input_method", check=False):
            return
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)


def ensure_home() -> None:
    """The app's home ("오늘 · 이번 주"), starting the app again when another screen is open."""
    if exact("오늘 · 이번 주", 3) is None:
        sh(f"am force-stop {PKG}")
        launch_app()


def settle() -> None:
    """Reads the screen once, which closes a stalled-System-UI dialog before a screenshot."""
    dump_ui()


def scroll_down() -> None:
    w, h = screen_size()
    sh(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 400")
    time.sleep(1)


def row_button(name: str, label: str) -> Node:
    """The [먹었어] or [기록 취소] button on the same line as `name` on the app home."""
    row = exact(name, 15)
    if row is None:
        raise RuntimeError(f"row {name} not on the home")
    buttons = [n for n in dump_ui() if n.text == label]
    if not buttons:
        raise RuntimeError(f"no [{label}] on the home")
    button = min(buttons, key=lambda b: abs(b.center[1] - row.center[1]))
    if abs(button.center[1] - row.center[1]) > 200:
        raise RuntimeError(f"[{label}] is not on the {name} line")
    return button


def message_line() -> str:
    """The short message the app or widget shows after a tap, if one of the known ones is on screen."""
    for n in dump_ui():
        if any(key in n.text for key in ("기록했어요", "이미 기록됨", "취소했어요", "삭제했어요", "기록됨", "변경 없음")):
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
        sh("setprop persist.sys.timezone Asia/Seoul", check=False)
    return sh("getprop persist.sys.timezone").strip()


def set_clock(at: datetime) -> str:
    """Turns off network time and sets the clock. `cmd alarm set-time` sends TIME_SET like a person
    changing the time in Settings; `date` is the fallback."""
    sh("settings put global auto_time 0", check=False)
    sh("settings put global auto_time_zone 0", check=False)
    target = int(at.timestamp())
    sh(f"cmd alarm set-time {target * 1000}", check=False)
    now = int(sh("date +%s").strip() or 0)
    if abs(now - target) > 120:
        utc = at.astimezone(timezone.utc)
        sh(f"date -u {utc.strftime('%m%d%H%M%Y.%S')}", check=False)
        now = int(sh("date +%s").strip() or 0)
    if abs(now - target) > 120:
        raise RuntimeError(f"clock not set: wanted {target}, phone says {now}")
    time.sleep(3)
    return sh("date '+%Y-%m-%d %H:%M %Z'").strip()


def font_scale(scale: str) -> None:
    sh(f"settings put system font_scale {scale}")
    time.sleep(3)


def night(on: bool) -> None:
    sh(f"cmd uimode night {'yes' if on else 'no'}")
    time.sleep(3)


def reboot() -> None:
    adb("reboot", check=False)
    time.sleep(10)
    adb("wait-for-device", timeout=300)
    wait_boot(600)
    become_root()
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    time.sleep(10)


# ------------------------------------------------------------------------------------------------
# Flow


class Steps:
    """Runs each step; a step that raises is one failed check, and the walk goes on."""

    def __init__(self, r: Report) -> None:
        self.r = r

    def __call__(self, title: str, expected: str, fn) -> bool:
        try:
            actual = fn() or "됨"
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
    """Home > [항목 추가] > name > person chip > 매일 + time of day, or 주 1회 + weekday > [저장]."""
    ensure_home()
    tap_exact("항목 추가")
    type_into(0, name)
    tap_exact(person)
    tap_exact("주 1회" if weekly else "매일")
    # The weekday and time-of-day chips sit under the "주기" label; "월" alone is also in other texts.
    cycle = exact("주기")
    tap_exact(choice, below=cycle.bounds[3] if cycle else -1)
    if shot:
        r.shot(shot)
    tap_exact("저장")
    if exact(name, 20) is None:
        raise RuntimeError(f"row {name} did not show after [저장]")
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

    # 1. first launch
    def first_launch() -> str:
        launch_app()
        time.sleep(2)
        r.shot("first-launch")
        empty = screen_has("아직 복용 항목이 없어요", 5)
        if not empty:
            raise RuntimeError("empty-home text not shown")
        return "빈 홈과 안내 문구"

    steps("1. 처음 실행", "빈 홈, [항목 추가] 안내", first_launch)

    # 2. the add form, and adding a family member
    def add_form() -> str:
        tap_exact("항목 추가")
        time.sleep(1)
        r.shot("add-form-empty")
        chips = [n.text for n in dump_ui() if n.text in (ME, "+ 사람 추가")]
        tap_exact("+ 사람 추가")
        type_into(2, MOM)
        r.shot("add-person-open")
        tap_exact("추가")
        if exact(MOM, 10) is None:
            raise RuntimeError(f"chip {MOM} did not show")
        r.shot("add-person-done")
        tap_exact("닫기")
        exact("항목 추가", 10)
        return f"폼의 대상자 칩 {chips}, {MOM} 칩 생김"

    steps("2. 항목 추가 화면과 대상자 추가", "빈 폼, 나 칩, 사람 추가 후 새 칩", add_form)

    # 3. three items
    steps("3-1. 매일 아침 항목 (나)", "목록에 생김", lambda: add_item(r, VITAMIN, ME, False, "아침", "form-vitamin"))
    steps("3-2. 매일 저녁 항목 (Mom)", "목록에 생김", lambda: add_item(r, BP, MOM, False, "저녁"))
    steps("3-3. 주 1회 월요일 항목 (나)", "목록에 생김", lambda: add_item(r, WEEKLY, ME, True, "월", "form-weekly"))

    def home_three() -> str:
        ensure_home()
        time.sleep(1)
        r.shot("home-three-items")
        return "항목 3개"

    steps("3-4. 항목 3개인 홈", "오늘·이번 주 구역", home_three)

    # 4. record in the app
    def record_app() -> str:
        ensure_home()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(1)
        msg = message_line()
        r.shot("app-recorded")
        return f"메시지 '{msg}', {records()}"

    steps("4. 앱에서 [먹었어]", "기록 1건, 버튼이 [기록 취소]로 바뀜", record_app)

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
        return f"{records()}"

    steps("5-4. 위젯 기록 뒤 앱", "BPpill이 완료로 보임", app_after_widget)

    # 6. void and record again
    def void() -> str:
        ensure_home()
        tap(row_button(VITAMIN, "기록 취소"))
        time.sleep(1)
        msg = message_line()
        r.shot("app-voided")
        return f"메시지 '{msg}', {records()}"

    def again() -> str:
        ensure_home()
        tap(row_button(VITAMIN, "먹었어"))
        time.sleep(1)
        msg = message_line()
        r.shot("app-recorded-again")
        return f"메시지 '{msg}', {records()}"

    steps("6-1. [기록 취소]", "확인 없이 바로 취소되는지 보기, 활성 판정이 줄어듦", void)
    steps("6-2. 다시 [먹었어]", "다시 기록됨", again)

    # 7. edit and delete
    def edit() -> str:
        ensure_home()
        tap(exact(VITAMIN, 15))
        time.sleep(1)
        r.shot("edit-form")
        tap_exact("주 1회")
        r.shot("edit-weekly")
        tap_exact("저장")
        exact("항목 추가", 10)
        time.sleep(1)
        r.shot("home-after-edit")
        return f"{records()}"

    def delete() -> str:
        ensure_home()
        tap(exact(WEEKLY, 15))
        time.sleep(1)
        scroll_down()
        tap_exact("항목 삭제")
        time.sleep(1)
        r.shot("delete-confirm")
        tap_exact("삭제")
        time.sleep(2)
        msg = message_line()
        r.shot("home-after-delete")
        gone = exact(WEEKLY, 3) is None
        return f"메시지 '{msg}', 목록에서 {'사라짐' if gone else '남아 있음'}"

    steps("7-1. 항목 수정 (매일 → 주 1회)", "안내 문구, 저장 후 홈", edit)
    steps("7-2. 항목 삭제", "확인 대화상자, 목록에서 사라짐", delete)

    # 8. larger text and dark mode
    def bigger(scale: str) -> str:
        font_scale(scale)
        launch_app()
        time.sleep(2)
        r.shot(f"app-font-{scale}")
        tap_exact("항목 추가")
        time.sleep(1)
        r.shot(f"form-font-{scale}")
        tap_exact("닫기")
        if placed["ok"]:
            go_home()
            time.sleep(3)
            r.shot(f"widget-font-{scale}")
        return f"글자 크기 {scale}"

    steps("8-1. 글자 크기 1.3", "잘리거나 겹치지 않음", lambda: bigger("1.3"))
    steps("8-2. 글자 크기 2.0", "잘리거나 겹치지 않음", lambda: bigger("2.0"))

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

    steps("8-3. 어두운 화면", "글자가 보임", dark)

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

    steps("9. 폰 다시 켜기", "위젯이 다시 그려짐", restart)

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

    steps("10. 다음 날 08:30", "위젯·앱이 새 날로 바뀜(매일 항목이 안 먹음으로)", next_morning)

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

    steps("11. 그다음 날 02:00", "하루 시작 04:00 전이라 앞날로 셈", before_day_start)


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
        r.write()
        log = adb("logcat", "-d", "-v", "time", check=False)
        (args.out / f"api{api}-logcat.txt").write_text(log, encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
