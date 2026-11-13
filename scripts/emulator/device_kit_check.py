#!/usr/bin/env python3
"""Runs the Galaxy device kit (scripts/device/device-check.ps1) on an Android emulator without root.

The kit is for the real-phone checks (TASK-032, TASK-052 and the device part of BUG-004, docs/07-qa/device-check.md):
adb is the shell user, as on any phone with USB debugging, and a person places the widget, unlocks the phone and picks
times. Before 동희님's Galaxy is used, this runs the kit's steps on one emulator whose adbd is the shell user (no
`adb root`), so a step that needs root, or that reads a screen differently from how Android and the app draw it, fails
here first. The person's part is played by this driver: it drops the widget the way release_check.py does, sets and
types a test PIN the way reminder_check.py does (the kit never asks for or sees a PIN), expands and taps a lock-screen
reminder, and waits for the clock.

Flow (zone Asia/Seoul; the kit's own `clock` step moves the clock; D is the phone's date at the start):
   1. info; clock D 06:30; install -Fresh; additem PillA..PillE (the first-run intro is skipped with [건너뛰기])
   2. bug004 while the driver drops the widget: RescheduleReceiver on before the drop, the 10-second dump shows the
      widget's items (nodes with the app's package) and no ProgressBar, no package-changed log line
   3. remind PillA..PillE (first reminder 08:00, the app's default), exact allow, alarms: one exact reminder alarm
   4. Lock screen cells (TASK-052, TC-013): appnames hide, content show, clock D 07:56, night start, lock. When the
      08:00 reminders are up: lock-off-show-locked and -expanded (no name, no button, "복용 시간이에요"), unlocked in
      the shade (names); content hide: lock-off-hide-locked; appnames show, content show: lock-on-show-locked (names),
      [먹었어] tapped on the lock screen (one reminder fewer, still locked); content hide: lock-on-hide-locked (no name,
      no button; on Android 16 the folded app-name line of its own bundle is noted, supplement N-13)
   5. night end -Slots 08:00: the 08:00 slot came
   6. RR-13: clock D+1 03:58:30, tap -Text PillA -When 04:00:00 on the widget, snap: the tap time is checked, the band
      ("날짜가 바뀌었어요") or a new-day record is noted
   7. latency: alive -Names PillB,PillC -Count 3 (recorded, recorded, already), dead -Item PillD -Count 2
   8. exact deny, alarms: the reminder alarm is no longer exact
   9. logcat (no crash line of the app), reboot: the driver types the PIN after the boot, and the kit leaves the
      screen, the alarms and the logs. Last here, not before the cells as on the Galaxy: the emulator comes back on
      the host's clock. When adb has not seen the phone again or its boot has not finished in 5 minutes
      (emulator_boot.py), the driver keeps what the runner and the phone say (raw/reboot-hang-*.txt) and turns the
      emulator off and on; the report says so
  10. clock auto, info

The emulator draws the soft keyboard although it has a hardware one (show_ime_with_hard_keyboard 1), as a phone does,
except while the widget is dropped (the launcher's search field would cover the previews). Animations are off, as in
the other emulator checks. When a step fails while a system "isn't responding" dialog is up (the CI emulator stalls
after clock changes), the dialog is closed with [Wait] and a step that is safe to repeat runs once more; the report
says so. When the kit cannot set the clock, that is a failed check and the driver sets it as root, then goes back to
the shell user, so the later steps still run.

Writes report.md, each step's output (steps/NN-<step>.txt), the kit's files (raw/) and screenshots to --out. Exits 1
when a check fails. Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, time as clock_time, timedelta, timezone
from pathlib import Path
from typing import IO

from backup_restore_check import CLOSED_DIALOGS, PKG, Report, adb, api_level, become_root, dump_ui, sh, tap, wait_boot
from emulator_boot import wait_reboot
from explore import put_clock, settle
from release_check import drag, go_home, open_full_widget_picker, save_ui, screen_size, widget_preview
from reminder_check import DOSES, LOCK_TITLE, NAMES, PIN, TAKEN, keyguard_showing, notification_button, posted, set_pin, unlock

KIT = Path(__file__).resolve().parents[2] / "scripts" / "device" / "device-check.ps1"
ALL = ",".join(NAMES)
STALE = "날짜가 바뀌었어요"  # Labels: the band on a widget drawn for a day that has ended (REQ-004, RR-13)
REMINDER = "REMINDER_ALARM"  # the action the kit prints for ReminderAlarm.ACTION (package prefix taken off)


# ------------------------------------------------------------------------------------------------
# The kit


@dataclass
class Done:
    call: str
    code: int
    out: str

    @property
    def ok(self) -> bool:
        return self.code == 0

    def tail(self, lines: int = 2) -> str:
        """The last lines of the output, for the report."""
        kept = [line.strip() for line in self.out.splitlines() if line.strip()]
        text = " / ".join(kept[-lines:]) or "(출력 없음)"
        return (text if len(text) <= 400 else text[:400] + "…") + ("" if self.ok else f" (종료 코드 {self.code})")

    def line(self, prefix: str) -> str:
        """The first output line that starts with `prefix`, or ''."""
        return next((line.strip() for line in self.out.splitlines() if line.strip().startswith(prefix)), "")


class Running:
    def __init__(self, proc: subprocess.Popen, log: Path, handle: IO[str], call: str) -> None:
        self.proc, self.log, self.handle, self.call = proc, log, handle, call

    def poll(self) -> int | None:
        return self.proc.poll()

    def wait(self, timeout: float) -> Done:
        try:
            code = self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            code = self.proc.wait()
            self.handle.write(f"\n[device_kit_check] {timeout:.0f}초 안에 끝나지 않아 멈춤\n")
        self.handle.close()
        out = self.log.read_text(encoding="utf-8", errors="replace")
        print(out, flush=True)
        return Done(self.call, code, out)


class Kit:
    """Runs one step of device-check.ps1 with pwsh, the way 동희님's PC runs it, and keeps its output in
    steps/NN-<step>.txt. The kit's own files go to raw/."""

    def __init__(self, out: Path) -> None:
        self.raw = out / "raw"
        self.logs = out / "steps"
        self.raw.mkdir(parents=True, exist_ok=True)
        self.logs.mkdir(parents=True, exist_ok=True)
        self.count = 0

    def start(self, step: str, *args: str) -> Running:
        self.count += 1
        log = self.logs / f"{self.count:02d}-{step}.txt"
        call = " ".join([step, *args])
        handle = open(log, "w", encoding="utf-8")
        handle.write(f"$ device-check.ps1 -Step {call}\n")
        handle.flush()
        print(f"[kit] -Step {call}", flush=True)
        command = ["pwsh", "-NoProfile", "-NonInteractive", "-File", str(KIT), "-Step", step, *args, "-OutDir", str(self.raw)]
        proc = subprocess.Popen(command, stdout=handle, stderr=subprocess.STDOUT)
        return Running(proc, log, handle, call)

    def text(self, name: str) -> str:
        path = self.raw / name
        return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


@dataclass
class Snap:
    """A `snap` summary (<name>-summary.txt): what the screen showed."""

    text: str

    def field(self, label: str) -> str | None:
        m = re.search(rf"^{re.escape(label)}: (.*)$", self.text, re.M)
        return m.group(1).strip() if m else None

    def listed(self, label: str) -> list[str]:
        value = self.field(label)
        return [] if value in (None, "없음") else [v.strip() for v in value.split(",") if v.strip()]

    @property
    def locked(self) -> bool:
        return self.field("잠금 화면") == "떠 있음"

    @property
    def names(self) -> list[str]:
        return self.listed("화면 글자의 항목 이름")

    @property
    def desc_only(self) -> list[str]:
        return self.listed("글자에는 없고 설명에만 있는 이름")

    @property
    def buttons(self) -> list[str]:
        return self.listed("알림 버튼 글자")

    @property
    def lock_title(self) -> bool:
        return self.field(f"'{LOCK_TITLE}'") == "보임"

    @property
    def stale(self) -> bool:
        return f"'{STALE}' 띠: 보임" in self.text

    @property
    def count(self) -> int:
        m = re.search(r"이 앱 알림 (\d+)개", self.text)
        return int(m.group(1)) if m else -1

    @property
    def autogroup(self) -> bool:
        return "AUTOGROUP_SUMMARY" in self.text

    def describe(self) -> str:
        if not self.text:
            return "요약 파일 없음"
        return (f"잠금 {'떠 있음' if self.locked else '없음'}, 이름 {', '.join(self.names) or '없음'}, "
                f"설명에만 {', '.join(self.desc_only) or '없음'}, 버튼 {', '.join(self.buttons) or '없음'}, "
                f"'{LOCK_TITLE}' {'보임' if self.lock_title else '없음'}, 이 앱 알림 {self.count}개"
                + (", Android 자동 묶음" if self.autogroup else "") + (f", '{STALE}' 띠" if self.stale else ""))


# ------------------------------------------------------------------------------------------------
# Phone


def unroot() -> str:
    """Restarts adbd as the shell user and returns `id -u`."""
    adb("unroot", check=False)
    who = ""
    end = time.time() + 90
    while time.time() < end:
        time.sleep(2)
        adb("wait-for-device", check=False, timeout=60)
        who = sh("id -u", check=False).strip()
        if who and who != "0":
            return who
    raise RuntimeError(f"adb unroot failed (id -u = {who!r})")


def phone_now() -> datetime:
    """The phone's local wall clock."""
    return datetime.strptime(sh("date '+%Y-%m-%d %H:%M:%S'").strip(), "%Y-%m-%d %H:%M:%S")


def phone_offset() -> timedelta:
    z = sh("date +%z").strip()  # +0900
    sign = -1 if z.startswith("-") else 1
    return sign * timedelta(hours=int(z[1:3]), minutes=int(z[3:5]))


def epoch(at: datetime) -> int:
    """Unix seconds of the phone-local wall time `at`."""
    return int((at - phone_offset()).replace(tzinfo=timezone.utc).timestamp())


def keyboard(shown: bool) -> None:
    """The soft keyboard with the emulator's hardware keyboard: drawn (as on a phone) or not."""
    sh(f"settings put secure show_ime_with_hard_keyboard {1 if shown else 0}", check=False)


def widget_nodes(xml_path: Path) -> list[tuple[str, str]]:
    """(text, content-desc) of the nodes a dump reports with the app's package: what the app's widget draws."""
    if not xml_path.exists():
        return []
    try:
        root = ET.fromstring(xml_path.read_text(encoding="utf-8", errors="replace"))
    except ET.ParseError:
        return []
    return [(el.get("text", "").replace("⁠", ""), el.get("content-desc", "").replace("⁠", ""))
            for el in root.iter("node") if el.get("package") == PKG]


def doses_up() -> int:
    """How many of the app's dose reminders are posted."""
    return sum(1 for p in posted() if p.channel == DOSES)


# ------------------------------------------------------------------------------------------------
# Flow


class Flow:
    def __init__(self, r: Report, kit: Kit, api: int) -> None:
        self.r, self.kit, self.api = r, kit, api
        self.fails = 0

    def step(self, step: str, *args: str, timeout: float = 600, again: bool = True) -> Done:
        """Runs a kit step. On a failure: a screenshot and a dump for the report and, when a system "isn't responding"
        dialog was up and the step is safe to repeat (`again`), the dialog is closed and the step runs once more."""
        done = self.kit.start(step, *args).wait(timeout)
        if done.ok:
            return done
        self.fails += 1
        closed = self.close_system_dialogs()
        try:
            self.r.shot(f"fail-{self.fails:02d}-{step}")
            save_ui(self.r, f"fail-{self.fails:02d}-{step}")
        except Exception as exc:  # the failure itself is what the report needs
            print(f"[note] no screenshot after the failure: {exc}", flush=True)
        if closed and again:
            self.r.note(f"`{done.call}` 실패 때 시스템 '응답 없음' 창이 떠 있어 [Wait]로 닫고 한 번 더 돌림")
            done = self.kit.start(step, *args).wait(timeout)
        return done

    def close_system_dialogs(self) -> bool:
        before = len(CLOSED_DIALOGS)
        try:
            dump_ui(tries=4)
        except RuntimeError:  # the app itself not responding: the failed step shows it
            return False
        return len(CLOSED_DIALOGS) > before

    def check(self, case: str, expected: str, actual: str, ok: bool) -> bool:
        return self.r.check(case, expected, actual, ok)

    def snap(self, name: str, names: str = ALL) -> Snap:
        done = self.step("snap", "-Name", name, "-Names", names)
        text = self.kit.text(f"{name}-summary.txt") if done.ok else ""
        return Snap(text)

    def clock(self, at: datetime, why: str, longest: float = 120) -> None:
        """The kit's `clock`; when it cannot set the time, a failed check, and the driver sets it as root."""
        stamp = at.strftime("%Y-%m-%d %H:%M:%S")
        done = self.step("clock", "-At", stamp, again=False)
        shown = re.search(r"휴대폰 시각: (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})", done.out)
        ok = done.ok and shown is not None and abs((datetime.strptime(shown.group(1), "%Y-%m-%d %H:%M:%S") - at).total_seconds()) <= 15
        self.check(f"clock -At '{stamp}' ({why})", "루트 없이 휴대폰 시계를 맞춤 (cmd alarm set-time)", done.tail(1), ok)
        if not ok:
            target = epoch(at)
            become_root()
            try:
                put_clock(target)
            finally:
                who = unroot()
            self.r.note(f"기기 스크립트가 시계를 맞추지 못해 이 확인이 루트로 {stamp}에 맞추고 셸 사용자(id {who})로 돌아감")
        settle(quiet=20, longest=longest)

    def unlock(self, why: str) -> None:
        """What the person does: types the PIN on the lock screen."""
        sh("input keyevent KEYCODE_WAKEUP", check=False)
        if keyguard_showing() is not False and not unlock():
            raise RuntimeError(f"could not unlock with the test PIN ({why})")

    def lock_cell(self, name: str, names_on: bool, content_shown: bool) -> Snap:
        """`lock` then `snap` of one locked cell, checked as in TC-013 and docs/07-qa/device-check.md 2.1."""
        locked = self.step("lock")
        self.check(f"lock ({name})", "화면을 껐다 켜면 잠금 화면이 뜸", locked.tail(1), locked.ok)
        seen = self.snap(name)
        if not names_on:
            ok = seen.locked and not seen.names and not seen.desc_only and not seen.buttons and seen.lock_title
            expected = f"잠금 화면에 이름·버튼이 없고(설명에도 없음) '{LOCK_TITLE}'가 보임"
        elif content_shown:
            ok = seen.locked and len(seen.names) >= 1
            expected = "잠금 화면에 항목 이름이 보임"
        else:
            ok = seen.locked and not seen.names and not seen.buttons and seen.lock_title
            expected = f"잠금 화면에 이름·버튼이 없고 '{LOCK_TITLE}'가 보임"
            if not ok and seen.locked and not seen.names and not seen.buttons and self.api >= 36 and seen.autogroup:
                ok = True
                self.r.note(f"{name}: Android 16이 알림을 자기 묶음(AUTOGROUP_SUMMARY)으로 바꿔 '{LOCK_TITLE}' 대신 접힌 "
                            "앱 이름 줄. 이름·버튼은 없음 (보충 N-13 한계, 갤럭시에서 다시 봄)")
            if seen.desc_only:
                self.r.note(f"{name}: 화면 글자에는 이름이 없고 설명에만 {', '.join(seen.desc_only)} (알림 아이콘 설명, 플랫폼 한계)")
        self.check(f"snap {name}", expected, seen.describe(), ok)
        return seen


def flow(f: Flow, apk: Path) -> None:
    r, kit, api = f.r, f.kit, f.api

    # 1. info, clock, install, items
    done = f.step("info")
    f.check("info", f"기기·앱·설정 정보가 info.txt에 남음 (API {api})", done.line("ro.build.version.sdk") or done.tail(1),
            done.ok and f"ro.build.version.sdk: {api}" in kit.text("info.txt"))
    day = phone_now().date()
    f.clock(datetime.combine(day, clock_time(6, 30)), "준비: 첫 알림 08:00 전")
    done = f.step("install", "-Apk", str(apk), "-Fresh", again=False)
    f.check("install -Fresh", "앱을 지우고 새로 깖", done.line("깔림:") or done.tail(), done.ok and "깔림: versionCode" in done.out)
    if not done.ok:
        raise RuntimeError("the APK did not install; the other steps need the app")
    done = f.step("additem", "-Names", ALL, again=False)
    f.check("additem PillA..PillE", "첫 실행 안내를 넘기고 다섯 항목을 만듦", done.tail(1),
            done.ok and "만든 항목 5개" in done.out)
    if not done.ok:
        raise RuntimeError("the items were not made; the other steps need them")

    # 2. BUG-004: the first widget, dropped while the kit watches
    keyboard(False)
    go_home()
    watching = kit.start("bug004")
    time.sleep(8)  # the kit lists the widgets before it asks for one
    w, h = screen_size()
    for attempt in (1, 2):
        try:
            below = open_full_widget_picker(r, f"bug004-{attempt}", w, h)
            preview = widget_preview(h, below)
            if preview is None:
                raise RuntimeError("widget preview not on screen")
            x, y = preview.center
            drag(x, y, w // 2, h // 2)
            break
        except Exception as exc:  # the launcher differs between images; the kit waits three minutes
            r.note(f"위젯 놓기 {attempt}번째 실패: {type(exc).__name__}: {exc}")
            save_ui(r, f"bug004-place-{attempt}")
            go_home()
            time.sleep(15)
    done = watching.wait(300)
    keyboard(True)
    report = kit.text("bug004.txt")
    shown = [text for text, desc in widget_nodes(kit.raw / "bug004-10s.xml") for name in NAMES if name in text or name in desc]
    f.check("bug004 (BUG-004 첫 위젯)",
            "놓기 전 RescheduleReceiver 켜짐 True, 10초 덤프에 위젯 항목(앱 패키지 노드)이 있고 도는 원 없음, 패키지 바뀜 0줄",
            f"{' / '.join(line for line in report.splitlines()[1:5]) or done.tail()}; 10초 덤프의 위젯 항목 {len(shown)}개",
            done.ok and "RescheduleReceiver 켜짐(놓기 전): True" in report and "10초: 도는 원(ProgressBar) 없음" in report
            and "패키지 바뀜 줄: 0개" in report and len(shown) >= 1)
    go_home()
    r.shot("widget")

    # 3. Reminders on, exact alarms allowed
    done = f.step("remind", "-Names", ALL)
    lines = [line for line in kit.text("remind.txt").splitlines() if line.strip()]
    f.check("remind PillA..PillE", "다섯 항목 [알림 받기] 켬, 첫 알림 시각 08:00", " / ".join(lines) or done.tail(),
            done.ok and len(lines) == 5 and all("08:00" in line and ("켬" in line or "이미 켜져" in line) for line in lines))
    done = f.step("exact", "-Mode", "allow")
    f.check("exact -Mode allow", "정확 알람 허용 (appops)", done.line("정확 알람:") or done.tail(1),
            done.ok and "allow" in done.line("정확 알람:"))
    done = f.step("alarms", "-Name", "alarms-allow")
    reminders = [line for line in done.out.splitlines() if REMINDER in line]
    f.check("alarms (허용)", "이 앱 알림 알람이 08:00 정확 알람 하나 (보충 N-12)", " / ".join(line.strip() for line in reminders) or done.tail(),
            done.ok and len(reminders) == 1 and "08:00:00, 정확" in reminders[0])

    # 4. Lock screen cells
    done = f.step("appnames", "-Mode", "hide")
    f.check("appnames -Mode hide", "앱 설정 [잠금 화면에 약 이름 보이기] 끔 (처음 값이라 이미 그 상태)", done.tail(1),
            done.ok and "끔" in done.out and "이미 그 상태였음" in done.out)
    done = f.step("content", "-Mode", "show")
    f.check("content -Mode show", "기기 잠금 화면 알림 '내용 표시' (1)", done.tail(1), done.ok and "알림 내용: 1" in done.out)
    set_pin()
    r.note("시험 PIN을 이 확인이 정함 (locksettings set-pin, reminder_check.py와 같은 값). 기기 스크립트는 PIN을 모름")
    f.clock(datetime.combine(day, clock_time(7, 56)), "08:00 알림 전")
    done = f.step("night", "-Phase", "start")
    f.check("night -Phase start", "시작 상태와 08:00 알림 알람이 night-start.txt에 남음",
            next((line for line in done.out.splitlines() if REMINDER in line), done.tail(1)),
            done.ok and any(REMINDER in line and "08:00:00" in line for line in kit.text("night-start.txt").splitlines()))
    locked = f.step("lock")
    f.check("lock", "화면을 껐다 켜면 잠금 화면이 뜸", locked.tail(1), locked.ok)
    end = time.time() + 420
    while time.time() < end and (phone_now() < datetime.combine(day, clock_time(8, 0, 20)) or doses_up() < 5):
        time.sleep(5)
    r.note(f"08:00 알림: 휴대폰 {phone_now():%H:%M:%S}, 이 앱 복용 알림 {doses_up()}개")
    f.lock_cell("lock-off-show-locked", names_on=False, content_shown=True)
    expander = next((n for n in dump_ui() if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
    if expander is not None:
        tap(expander)  # what the person does: opens the lock-screen notification
    seen = f.snap("lock-off-show-locked-expanded")
    f.check("snap lock-off-show-locked-expanded", f"펼쳐도 이름·버튼이 없고 '{LOCK_TITLE}'",
            seen.describe() + ("" if expander is not None else " (펼칠 단추 없음)"),
            seen.locked and not seen.names and not seen.desc_only and not seen.buttons)
    f.unlock("lock-off-show-unlocked")
    opened = f.step("shade", "-Mode", "open")
    seen = f.snap("lock-off-show-unlocked")
    closed = f.step("shade", "-Mode", "close")
    f.check("shade open, snap lock-off-show-unlocked, shade close", "잠금을 푼 알림창에 이름이 보임",
            seen.describe(), opened.ok and closed.ok and not seen.locked and len(seen.names) >= 1)
    done = f.step("content", "-Mode", "hide")
    f.check("content -Mode hide", "기기 잠금 화면 알림 '내용 숨김' (0)", done.tail(1), done.ok and "알림 내용: 0" in done.out)
    f.lock_cell("lock-off-hide-locked", names_on=False, content_shown=False)
    f.unlock("appnames show")
    done = f.step("appnames", "-Mode", "show")
    f.check("appnames -Mode show", "앱 설정 [잠금 화면에 약 이름 보이기] 켬", done.tail(1), done.ok and "켬" in done.out and "바꿈" in done.out)
    done = f.step("content", "-Mode", "show")
    f.check("content -Mode show (이름 켬)", "'내용 표시' (1)", done.tail(1), done.ok and "알림 내용: 1" in done.out)
    before = f.lock_cell("lock-on-show-locked", names_on=True, content_shown=True)
    # What the person does: [먹었어] on a reminder of the lock screen (REQ-076: recorded without unlocking)
    button, name = None, None
    for candidate in before.names or list(NAMES):
        button = notification_button(candidate, TAKEN)
        if button is not None:
            name = candidate
            break
    if button is None:
        f.check("잠금 화면 [먹었어]", "잠금을 풀지 않고 기록", f"이름 {before.names}, [먹었어]를 찾지 못함", False)
    else:
        tap(button)
        time.sleep(4)
        after = f.snap("lock-on-show-locked-recorded")
        f.check("잠금 화면 [먹었어] 뒤 snap", f"{name} 알림 하나가 사라지고 잠금은 그대로",
                f"{name}: 알림 {before.count}개 → {after.count}개; {after.describe()}",
                after.locked and before.count > 0 and after.count == before.count - 1)
    done = f.step("content", "-Mode", "hide")
    f.check("content -Mode hide (이름 켬)", "'내용 숨김' (0)", done.tail(1), done.ok and "알림 내용: 0" in done.out)
    f.lock_cell("lock-on-hide-locked", names_on=True, content_shown=False)
    f.unlock("night end")
    f.step("content", "-Mode", "show")

    # 5. The 08:00 slot of the short "night"
    done = f.step("night", "-Phase", "end", "-Slots", "08:00")
    f.check("night -Phase end -Slots 08:00", "계획 칸 08:00에 알림이 옴 (온 칸 1/1)", done.line("온 칸") or done.tail(1),
            done.ok and "온 칸 1/1" in kit.text("night.md"))

    # 6. RR-13: the widget tapped right after the day boundary
    go_home()
    f.clock(datetime.combine(day + timedelta(days=1), clock_time(3, 58, 30)), "RR-13 경계 전", longest=45)
    go_home()
    done = f.step("tap", "-Text", "PillA", "-When", "04:00:00", again=False)
    at = re.search(r"누른 뒤 휴대폰 시각 (\d{2}:\d{2}:\d{2})", done.out)
    # `input tap` is a Java program that takes about a second to start on the CI emulator: the tap lands a little after
    # 04:00:00, and the time printed after it a little later still
    f.check("tap -Text PillA -When 04:00:00 (RR-13)", "휴대폰 시계가 04:00:00이 되면 위젯의 PillA를 누름 (누른 뒤 시각 5초 안)",
            done.tail(1), done.ok and at is not None and "04:00:00" <= at.group(1) <= "04:00:05")
    seen = f.snap("rr13-after", "PillA")
    bands = [text for text, _ in widget_nodes(kit.raw / "rr13-after.xml") if re.fullmatch(r"\s*\d+\s*/\s*\d+\s*", text)]
    r.note(f"RR-13 탭 직후: {'날짜가 바뀌었어요 띠' if seen.stale else '띠 없음'}, 위젯 머리글 {bands or '못 읽음'} "
           "(합격은 띠 또는 새 날 기록, 옛 날 기록 없음: 기록 날짜는 갤럭시에서 앱 [지난 기록]으로 봄)")
    f.check("snap rr13-after", "탭 직후 화면이 남음", seen.describe(), bool(seen.text))

    # 7. Widget latency (REQ-070): alive, then dead
    done = f.step("latency", "-Names", "PillB,PillC", "-Count", "3", timeout=900, again=False)
    rows = [row.split(",") for row in kit.text("latency.csv").splitlines()[1:] if row.strip()]
    alive = [row for row in rows if len(row) >= 9 and row[2] == "alive"]
    f.check("latency -Names PillB,PillC -Count 3", "세 번 모두 바뀜을 잼: 기록, 기록, 이미 기록됨",
            "; ".join(f"{row[1]} {row[6]} {row[8] or '-'}초" for row in alive) or done.tail(),
            done.ok and [row[6] for row in alive] == ["recorded", "recorded", "already"] and all(row[8] for row in alive))
    done = f.step("latency", "-Item", "PillD", "-Process", "dead", "-Count", "2", timeout=900, again=False)
    rows = [row.split(",") for row in kit.text("latency.csv").splitlines()[1:] if row.strip()]
    dead = [row for row in rows if len(row) >= 9 and row[2] == "dead"]
    f.check("latency -Item PillD -Process dead -Count 2", "프로세스를 내리고 두 번 잼: 기록, 이미 기록됨",
            "; ".join(f"{row[1]} pid {row[3] or '없음'} {row[6]} {row[8] or '-'}초" for row in dead) or done.tail(),
            done.ok and [row[6] for row in dead] == ["recorded", "already"] and all(row[8] for row in dead)
            and not any(row[3] for row in dead))

    # 8. Exact alarms refused
    done = f.step("exact", "-Mode", "deny")
    f.check("exact -Mode deny", "정확 알람 거부, 앱을 한 번 열어 다시 계산", done.line("정확 알람:") or done.tail(1),
            done.ok and "deny" in done.line("정확 알람:"))
    done = f.step("alarms", "-Name", "alarms-deny")
    reminders = [line for line in done.out.splitlines() if REMINDER in line]
    f.check("alarms (거부)", "알림 알람이 비정확 (정확 알람 없음)", " / ".join(line.strip() for line in reminders) or done.tail(),
            done.ok and len(reminders) >= 1 and not any(", 정확 (" in line for line in reminders))

    # 9. Logs, then the reboot (RR-18): the driver types the PIN when the lock screen is back
    done = f.step("logcat", "-Name", "before-reboot")
    crash = re.search(r"앱이 죽은 기록\(crash\)의 이 앱 줄 (\d+)개", done.out)
    f.check("logcat (재부팅 전)", "앱이 죽은 기록 0줄", done.line("앱 로그") or done.tail(1), done.ok and crash is not None and crash.group(1) == "0")
    rebooting = kit.start("reboot")
    time.sleep(20)
    # When the reboot does not come back (emulator_boot.py), the emulator is turned off and on, as a person holds the
    # power key. The kit waits up to 10 minutes for the boot.
    if wait_reboot(kit.raw):
        r.note("재부팅: 5분 안에 휴대폰이 돌아오지 않아(adb에서 사라짐 또는 부팅이 끝나지 않음) 에뮬레이터를 껐다 켬. "
               "그때 상태는 raw/reboot-hang-*.txt")
    unlocked = False
    end = time.time() + 270
    while time.time() < end and rebooting.poll() is None:
        if keyguard_showing() is True:
            unlocked = unlock()
            if unlocked:
                break
        time.sleep(3)
    done = rebooting.wait(600)
    alarms = [line for line in kit.text("reboot-alarms.txt").splitlines() if REMINDER in line or "DAY_BOUNDARY" in line]
    f.check("reboot (RR-18)", "잠금을 풀면 홈 화면·알람·로그가 남고, 경계·알림 알람이 다시 있음",
            f"PIN 입력 {'됨' if unlocked else '못 함'}; {done.line('잠금 풀림') or done.tail(1)}; 알람 {len(alarms)}줄",
            done.ok and unlocked and any("DAY_BOUNDARY" in line for line in alarms) and any(REMINDER in line for line in alarms))
    # The kit's reboot leaves the logs of before the reboot first, then those of after: the last count is after
    crash = re.findall(r"앱이 죽은 기록\(crash\)의 이 앱 줄 (\d+)개", done.out)
    f.check("logcat (재부팅 뒤)", "앱이 죽은 기록 0줄", f"재부팅 앞·뒤 {', '.join(crash) or '못 읽음'}줄",
            len(crash) == 2 and crash[-1] == "0")

    # 10. Back to automatic time
    done = f.step("clock", "-Mode", "auto")
    f.check("clock -Mode auto", "자동 시간 켬", done.tail(1), done.ok and "자동 시간 켬" in done.out)
    cleared = sh(f"locksettings clear --old {PIN}", check=False)
    r.note(f"시험 PIN 지움: {'됨' if 'clear' in cleared.lower() else '안 됨'}")
    done = f.step("info", "-Name", "info-end")
    f.check("info -Name info-end", "끝난 뒤 정보가 남음", done.line("settings global auto_time") or done.tail(1), done.ok)


def prepare(r: Report, api: int) -> None:
    who = sh("id -u").strip()
    if who == "0":
        who = unroot()
    r.check("준비: adb가 셸 사용자", "id -u 2000 (루트가 아님, 갤럭시와 같음)", f"id -u {who}", who == "2000")
    if who == "0":
        raise RuntimeError("adbd runs as root; the check would not show what needs root")
    for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {name} 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    # Lock as soon as the screen goes off and show notifications on the lock screen (device-check.md 1장 3·4)
    sh("settings put secure lock_screen_lock_after_timeout 0", check=False)
    sh("settings put secure lock_screen_show_notifications 1", check=False)
    sh("cmd alarm set-timezone Asia/Seoul", check=False)
    keyboard(True)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    go_home()
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 시간대 {sh('getprop persist.sys.timezone').strip()}, "
           f"PowerShell {subprocess.run(['pwsh', '-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], capture_output=True, text=True).stdout.strip()}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    api = api_level()
    r = Report(args.out, api, f"기기 스크립트 확인 API {api}")
    kit = Kit(args.out)
    f = Flow(r, kit, api)
    try:
        prepare(r, api)
        flow(f, args.apk.resolve())
    except Exception as exc:  # a broken step the rest depends on: report what happened, then fail
        traceback.print_exc()
        r.check("확인 스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            save_ui(r, "error")
        except Exception:
            pass
    finally:
        r.write()
        try:
            (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", "-v", "time", check=False, timeout=60), encoding="utf-8")
        except Exception as exc:  # the report is already written
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
