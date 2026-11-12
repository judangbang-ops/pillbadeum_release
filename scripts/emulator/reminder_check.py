#!/usr/bin/env python3
"""Dose reminder checks on an Android emulator (TASK-024, TASK-025, TASK-027, TASK-040).

Runs against one booted emulator whose image allows `adb root` (google_apis or default) and the debug APK.
The item screen that switches reminders on (TASK-023) is not built yet, so the items are made by the
debug-only receiver `androidApp/src/debug/.../ReminderSeedReceiver.kt`, through the app's own use cases.
What the phone shows is read from `dumpsys alarm`, `dumpsys notification --noredact`, the screen
(uiautomator) and the app database (root).

Flow, on the run's date D in Asia/Seoul (five daily items PillA..PillE, reminders from 08:00, at most 5 a day):
   1. TASK-024 one alarm: with exact alarms refused (Android 12+), then allowed, `dumpsys alarm` holds one
      reminder alarm of the app at 08:00 (RR-54), inexact then exact; the day-boundary alarm is separate.
   2. 08:00 the alarm rings with the screen locked: five item reminders, all VISIBILITY_SECRET with
      [먹었어] [30분 뒤] [오늘 건너뜀], and one silent PUBLIC "복용 시간이에요" (SPEC-013, RR-54).
   3. Lock screen, eight cells (TC-013): names off/on x the device setting "show all content" (1) / "hide
      sensitive content" (0) x locked/unlocked. Names off: no item name or button on the lock screen, even
      expanded. Names on + show all: names and [먹었어], and [먹었어] records PillA without unlocking.
   4. Buttons (TC-011, TC-012): PillB [오늘 건너뜀], PillC [30분 뒤] from the shade; PillD recorded in the app
      (RR-86); PillE swiped away. Each reminder goes, and with the last one "복용 시간이에요" goes (N-13).
   5. 08:30 PillC's snooze rings (RR-51).
   6. A missed alarm (N-12): the app is force-stopped before 09:00, so the 09:00 alarm never comes; opening
      the app at 09:03 shows the 09:00 reminders at once.
   7. Restore (TASK-040, RR-30, RR-33, RR-73): the install marker is deleted, as after a restore (supplement
      R-1, RR-71). One restore notice, no dose reminder and no reminder alarm, even after 10:00 passes;
      [확인했어요] takes the notice off and plans 11:00 (not 10:00).
   8. Reboot (TASK-027, RR-18, RR-55): the clock is put at 10:59 and the phone restarts across 11:00; after
      unlocking, the 11:00 reminders show.
   9. Window end (RR-87): at D+1 04:01 the reminders of D are gone and the next alarm is D+1 08:00.

Names are English because `adb shell` arguments stay ASCII. The real Galaxy check is TASK-052.
Writes report.md, screenshots, screen dumps, dumpsys outputs and logcat to --out. Exits 1 when a check
fails. Standard library only. Not product code.
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
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from backup_restore_check import (
    DATA,
    DB,
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
    tap_text,
    uninstall,
    wait_boot,
)
from explore import ZONE, put_clock, row_button, set_clock, set_zone
from release_check import go_home, save_ui, screen_size

SEED = f"{PKG}/.debug.ReminderSeedReceiver"
ACTION_SEED = f"{PKG}.debug.SEED"
ACTION_NAMES_ON_LOCK = f"{PKG}.debug.NAMES_ON_LOCK"
REMINDER_ALARM = f"{PKG}.action.REMINDER_ALARM"  # ReminderAlarm.ACTION
BOUNDARY_ALARM = f"{PKG}.action.DAY_BOUNDARY"  # DayBoundaryAlarm.ACTION

NAMES = ("PillA", "PillB", "PillC", "PillD", "PillE")
TITLE_END = " 먹어~!"  # ReminderLabels.title
LOCK_TITLE = "복용 시간이에요"  # ReminderLabels.LOCK_TITLE
RESTORED_TITLE = "백업에서 복원했어요"  # ReminderLabels.RESTORED_TITLE
TAKEN, SNOOZE, SKIP = "먹었어", "30분 뒤", "오늘 건너뜀"
ID_DOSE, ID_LOCK, ID_RESTORE = 1, 2, 3  # ReminderNotifications
DOSES, NOTICES = "dose_reminders", "app_notices"  # ReminderChannels
PIN = "1234"


def title(name: str) -> str:
    return f"{name}{TITLE_END}"


# ------------------------------------------------------------------------------------------------
# Debug seeding (androidApp/src/debug)


def seed(names: list[str], start: str, max_per_day: int | None = None) -> list[str]:
    cmd = f"am broadcast -n {SEED} -a {ACTION_SEED} --es names {','.join(names)} --es start {start}"
    if max_per_day is not None:
        cmd += f" --ei max_per_day {max_per_day}"
    out = sh(cmd, timeout=90)
    m = re.search(r'data="seeded ([^"]*)"', out)
    if not m:
        raise RuntimeError(f"seeding failed: {out.strip()}")
    return m.group(1).split(",")


def names_on_lock(show: bool) -> None:
    value = "true" if show else "false"
    out = sh(f"am broadcast -n {SEED} -a {ACTION_NAMES_ON_LOCK} --ez show {value}", timeout=60)
    if f"names_on_lock {value}" not in out:
        raise RuntimeError(f"names on lock not set: {out.strip()}")
    time.sleep(3)  # the reminders are posted again with the new visibility (SPEC-034)


# ------------------------------------------------------------------------------------------------
# dumpsys alarm


ALARM_HEAD = re.compile(r"^\s*(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED|ELAPSED_REALTIME_WAKEUP|ELAPSED_REALTIME) #\d+: Alarm\{")


@dataclass
class PlannedAlarm:
    block: str

    @property
    def window(self) -> str:
        m = re.search(r"\bwindow=(\S+)", self.block)
        return m.group(1) if m else "?"

    @property
    def exact(self) -> bool:
        # TimeUtils.formatDuration prints an exact alarm's window as "0", an inexact one as "+45s0ms" and so on
        return self.window == "0"

    @property
    def when(self) -> str:
        """The trigger time in the phone's zone, as dumpsys prints it (`origWhen=` from Android 12, `when=` before)."""
        m = re.search(r"\b(?:origWhen|when)=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", self.block)
        if m:
            return m.group(1)
        m = re.search(r"\b(?:origWhen|when) (\d{12,})", self.block)
        if m:
            return datetime.fromtimestamp(int(m.group(1)) / 1000, ZONE).strftime("%Y-%m-%d %H:%M:%S")
        return "?"

    def describe(self) -> str:
        reason = re.search(r"exactAllowReason=(\S+)", self.block)
        return f"{self.when}, window={self.window}" + (f", {reason.group(0)}" if reason else "")


def alarm_dump() -> str:
    return sh("dumpsys alarm", timeout=90)


def alarms(action: str, out: str | None = None) -> list[PlannedAlarm]:
    """The app's pending alarms whose intent action is `action` (tag `*walarm*:<action>` or `*alarm*:<action>`)."""
    lines = (out if out is not None else alarm_dump()).splitlines()
    found: list[PlannedAlarm] = []
    i = 0
    while i < len(lines):
        if not ALARM_HEAD.match(lines[i]):
            i += 1
            continue
        indent = len(lines[i]) - len(lines[i].lstrip())
        j = i + 1
        while j < len(lines) and lines[j].strip() and len(lines[j]) - len(lines[j].lstrip()) > indent:
            j += 1
        block = "\n".join(lines[i:j])
        if PKG in block and f"*:{action}" in block:
            found.append(PlannedAlarm(block))
        i = j
    return found


def wait_alarm(predicate, timeout: float = 30) -> list[PlannedAlarm]:
    end = time.time() + timeout
    while True:
        found = alarms(REMINDER_ALARM)
        if predicate(found) or time.time() >= end:
            return found
        time.sleep(3)


def show_alarms(found: list[PlannedAlarm]) -> str:
    return f"{len(found)}개" + (": " + "; ".join(a.describe() for a in found) if found else "")


# ------------------------------------------------------------------------------------------------
# dumpsys notification


@dataclass
class Posted:
    id: int
    tag: str
    channel: str
    vis: str
    public: bool
    title: str
    text: str
    actions: list[str] = field(default_factory=list)


def notification_dump() -> str:
    return sh("dumpsys notification --noredact", timeout=90)


def posted(out: str | None = None) -> list[Posted]:
    """The app's notifications on screen, one per (tag, id)."""
    text = out if out is not None else notification_dump()
    seen: dict[tuple[str, int], Posted] = {}
    for part in text.split("NotificationRecord(")[1:]:
        head = part.split("\n", 1)[0]
        pkg = re.search(r"\bpkg=(\S+)", head)
        if not pkg or pkg.group(1) != PKG:
            continue
        nid = re.search(r"\bid=(-?\d+)", head)
        tag = re.search(r"\btag=(\S+)", head)
        channel = re.search(r"\bchannel=(\S+)", head)
        vis = re.search(r"\bvis=(\w+)", head)
        # The record's own lines are indented under it; the next section of the dump starts further left.
        lines = part.split("\n")
        end = next((i for i, line in enumerate(lines[1:], 1) if line.strip() and len(line) - len(line.lstrip()) <= 4), len(lines))
        body = "\n".join(lines[:end])
        t = re.search(r"^\s*android\.title=\w+ \((.*)\)\s*$", body, re.M)
        x = re.search(r"^\s*android\.text=\w+ \((.*)\)\s*$", body, re.M)
        actions = re.findall(r'^\s*\[\d+\] "(.*)" ->', body, re.M)
        item = Posted(
            id=int(nid.group(1)) if nid else -1,
            tag=tag.group(1) if tag else "null",
            channel=channel.group(1) if channel else "?",
            vis=vis.group(1) if vis else "?",
            public="publicVersion=" in head,
            title=t.group(1) if t else "",
            text=x.group(1) if x else "",
            actions=actions,
        )
        seen[(item.tag, item.id)] = item
    return list(seen.values())


def doses(items: list[Posted]) -> list[Posted]:
    return [p for p in items if p.id == ID_DOSE]


def dose_names(items: list[Posted]) -> list[str]:
    return sorted(n for n in NAMES if any(p.title == title(n) for p in doses(items)))


def lock_notices(items: list[Posted]) -> list[Posted]:
    return [p for p in items if p.id == ID_LOCK]


def wait_posted(predicate, timeout: float) -> list[Posted]:
    end = time.time() + timeout
    while True:
        items = posted()
        if predicate(items) or time.time() >= end:
            return items
        time.sleep(5)


def channel_sound(channel_id: str, out: str) -> str:
    m = re.search(r"NotificationChannel\{mId='" + re.escape(channel_id) + r"'.*?mSound=([^,}]*)", out)
    return m.group(1).strip() if m else "읽지 못함"


def show_posted(items: list[Posted]) -> str:
    parts = []
    for p in items:
        label = p.title or p.text or "(제목 없음)"
        parts.append(f"{label} [{p.channel}, {p.vis}{', 공개용 판' if p.public else ''}, 버튼 {len(p.actions)}]")
    return f"{len(items)}개" + (": " + "; ".join(parts) if parts else "")


# ------------------------------------------------------------------------------------------------
# App database (root)


def query(sql: str) -> list[tuple]:
    tmp = Path(tempfile.mkdtemp())
    try:
        for suffix in ("", "-wal", "-journal"):
            remote = f"{DATA}/databases/{DB}{suffix}"
            if sh(f"ls {remote} 2>/dev/null", check=False).strip():
                adb("pull", remote, str(tmp / f"{DB}{suffix}"))
        con = sqlite3.connect(tmp / DB)
        try:
            return con.execute(sql).fetchall()
        finally:
            con.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def judgments(ids: dict[str, str]) -> dict[str, tuple[str, str]]:
    """Active judgments by item name: (kind, source)."""
    names = {v: k for k, v in ids.items()}
    rows = query("SELECT intake_id, kind, source FROM dose_record WHERE active = 1")
    return {names.get(r[0], r[0]): (str(r[1]), str(r[2])) for r in rows}


def snoozes(ids: dict[str, str]) -> list[str]:
    names = {v: k for k, v in ids.items()}
    return sorted(names.get(r[0], r[0]) for r in query("SELECT intake_id FROM reminder_snooze"))


def setting(key: str) -> str | None:
    rows = query(f"SELECT value FROM setting WHERE key = '{key}'")
    return str(rows[0][0]) if rows else None


# ------------------------------------------------------------------------------------------------
# Lock screen and shade (from the PR #15 experiment, scripts/emulator/lockscreen_experiment.py there)


KEYGUARD_SOURCES = (
    ("dumpsys window policy", r"KeyguardServiceDelegate\s+showing=(true|false)"),
    ("dumpsys activity activities", r"\bmKeyguardShowing=(true|false)"),
    ("dumpsys window", r"\bmShowingLockscreen=(true|false)"),
    ("dumpsys window", r"\bisStatusBarKeyguard=(true|false)"),
)


def keyguard_showing() -> bool | None:
    cache: dict[str, str] = {}
    for cmd, pattern in KEYGUARD_SOURCES:
        out = cache.setdefault(cmd, sh(cmd, check=False))
        m = re.search(pattern, out)
        if m:
            return m.group(1) == "true"
    return None


def set_pin() -> str:
    out = ""
    for cmd in (f"locksettings set-pin {PIN}", f"cmd lock_settings set-pin {PIN}"):
        out = sh(cmd, check=False).strip()
        if PIN in out or "set" in out.lower():
            return out
    raise RuntimeError(f"could not set a PIN: {out}")


def sleep_screen() -> None:
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)


def wake_screen() -> None:
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(3)


def lock() -> None:
    sleep_screen()
    wake_screen()


def unlock() -> bool:
    for _ in range(3):
        sh("wm dismiss-keyguard", check=False)
        time.sleep(2)
        sh(f"input text {PIN}")
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(3)
        if keyguard_showing() is False:
            return True
        sh("input keyevent KEYCODE_MENU", check=False)
        time.sleep(1)
    return keyguard_showing() is False


def ensure_unlocked() -> None:
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    if keyguard_showing() is not False and not unlock():
        raise RuntimeError("could not unlock with the PIN")


def expand_shade() -> None:
    out = sh("cmd statusbar expand-notifications", check=False)
    if "Unknown" in out or "Error" in out or "not found" in out:
        sh("service call statusbar 1", check=False)
    time.sleep(3)


def collapse_shade() -> None:
    out = sh("cmd statusbar collapse", check=False)
    if "Unknown" in out or "Error" in out or "not found" in out:
        sh("service call statusbar 2", check=False)
    time.sleep(1)


@dataclass
class Seen:
    names: list[str]
    button: bool
    generic: bool

    def describe(self) -> str:
        return (f"이름 {', '.join(self.names) or '없음'}, [먹었어] {'보임' if self.button else '없음'}, "
                f"'{LOCK_TITLE}' {'보임' if self.generic else '없음'}")


def read_once() -> Seen:
    blob = "\n".join(t for n in dump_ui() for t in (n.text, n.desc) if t)
    return Seen([n for n in NAMES if n in blob], TAKEN in blob, LOCK_TITLE in blob)


def read_screen(expand: int = 4) -> Seen:
    """What the screen shows, also after tapping up to `expand` expand buttons (buttons show only on an
    expanded notification): the union of the reads."""
    seen = read_once()
    for _ in range(expand):
        button = next((n for n in dump_ui() if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
        if button is None:
            break
        tap(button)
        again = read_once()
        seen = Seen([n for n in NAMES if n in seen.names or n in again.names],
                    seen.button or again.button, seen.generic or again.generic)
    return seen


def notification_button(name: str, label: str, tries: int = 4) -> Node | None:
    """The `label` button of `name`'s reminder on the screen (lock screen or open shade), expanding as needed.
    Never taps the reminder itself: that opens the app."""
    head_text = title(name)
    for _ in range(tries):
        nodes = dump_ui()
        head = next((n for n in nodes if head_text in n.text), None)
        if head is None:
            expander = next((n for n in nodes if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
            if expander is None:
                return None
            tap(expander)
            continue
        below = [n.bounds[1] for n in nodes if TITLE_END in n.text and n.bounds[1] > head.bounds[1] and n is not head]
        limit = min(below, default=10 ** 9)
        buttons = [n for n in nodes if n.text == label and head.bounds[1] < n.bounds[1] < limit]
        if buttons:
            return min(buttons, key=lambda n: n.bounds[1])
        near = [n for n in nodes if n.rid.endswith(":id/expand_button") and abs(n.center[1] - head.center[1]) < 160]
        if not near:
            return None
        tap(near[0])
    return None


def press_in_shade(name: str, label: str) -> None:
    expand_shade()
    button = notification_button(name, label)
    if button is None:
        raise RuntimeError(f"[{label}] of {name} not found in the shade")
    tap(button)
    time.sleep(3)
    collapse_shade()


def swipe_away(name: str) -> None:
    expand_shade()
    head = next((n for n in dump_ui() if title(name) in n.text), None)
    if head is None:
        expander = next((n for n in dump_ui() if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
        if expander is not None:
            tap(expander)
        head = next((n for n in dump_ui() if title(name) in n.text), None)
    if head is None:
        raise RuntimeError(f"{name}'s reminder not found in the shade")
    w, _ = screen_size()
    y = head.center[1]
    sh(f"input swipe {w // 5} {y} {w - 5} {y} 300")
    time.sleep(3)
    collapse_shade()


# ------------------------------------------------------------------------------------------------
# Run


class Run:
    def __init__(self, r: Report, apk: Path) -> None:
        self.r = r
        self.apk = apk
        self.ids: dict[str, str] = {}
        self.logs: list[str] = []  # logcat before each reboot (the buffer does not survive one)

    def check(self, case: str, expected: str, actual: str, ok: bool) -> bool:
        return self.r.check(case, expected, actual, ok)

    def save(self, name: str, text: str) -> None:
        (self.r.out / f"api{self.r.api}-{name}.txt").write_text(text, encoding="utf-8")

    def step(self, title_: str, fn) -> bool:
        """Runs one step; an exception is one failed row, and the run goes on to the next step."""
        try:
            fn()
            return True
        except Exception as exc:  # record and go on
            traceback.print_exc()
            self.r.check(title_, "단계가 끝까지 돎", f"{type(exc).__name__}: {exc}", False)
            tag = f"fail-{len(self.r.rows):02d}"
            try:
                self.r.shot(tag)
                save_ui(self.r, tag)
                collapse_shade()
            except Exception:
                pass
            return False


def flow(run: Run, api: int) -> None:
    r = run.r
    base = datetime.now(ZONE).date()

    def at(day: int, hour: int, minute: int = 0, second: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, second, tzinfo=ZONE)

    exact_gate = api >= 31  # SCHEDULE_EXACT_ALARM exists from Android 12

    # 0. Phone and app
    zone = set_zone()
    clock = set_clock(at(0, 7, 40))
    uninstall()
    install(run.apk)
    if api >= 33:
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    if exact_gate:
        sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM deny")
    launch_app()
    go_home()
    r.note(f"준비: 시간대 {zone}, 시계 {clock}, 정확 알람 {'거부로 시작' if exact_gate else '권한 없음(Android 11 이하는 늘 정확)'}")

    # 1. TASK-024: one reminder alarm, inexact then exact
    ids = seed(list(NAMES), "08:00", max_per_day=5)
    run.ids = dict(zip(NAMES, ids))
    first = wait_alarm(lambda a: len(a) >= 1)
    dump = alarm_dump()
    run.save("alarm-after-seed", dump)
    boundary = alarms(BOUNDARY_ALARM, dump)
    want_exact = not exact_gate
    run.check("TASK-024 항목 다섯 개, 같은 08:00 (RR-54)",
              f"알림 알람 1개, 08:00, {'정확' if want_exact else '비정확(정확 알람 거부)'}; 하루 경계 알람은 따로 1개",
              f"알림 알람 {show_alarms(first)}; 경계 알람 {len(boundary)}개",
              len(first) == 1 and first[0].when.endswith("08:00:00") and first[0].exact == want_exact and len(boundary) == 1)

    if exact_gate:
        sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
        granted = wait_alarm(lambda a: len(a) == 1 and a[0].exact, timeout=45)
        run.save("alarm-after-allow", alarm_dump())
        run.check("TASK-024 정확 알람을 허용한 뒤",
                  "권한 바뀜 방송으로 다시 계산: 알림 알람 1개, 08:00, 정확",
                  f"{show_alarms(granted)} ({sh(f'appops get {PKG} SCHEDULE_EXACT_ALARM', check=False).strip()})",
                  len(granted) == 1 and granted[0].exact and granted[0].when.endswith("08:00:00"))

    # 2. 08:00 rings on the lock screen
    r.note(f"PIN: {set_pin()}")
    sh("settings put secure lock_screen_allow_private_notifications 1", check=False)
    run.step("08:00 알림 준비", lambda: r.note(f"시계 {set_clock(at(0, 7, 56))}"))
    lock()
    rung = wait_posted(lambda p: len(doses(p)) >= 5, timeout=480)
    dump = notification_dump()
    run.save("notifications-0800", dump)
    items = posted(dump)
    five = doses(items)
    lock_notice = lock_notices(items)
    run.check("RR-54 08:00 알람이 울림 (잠금 화면 켠 채)",
              f"항목 알림 5개 모두 SECRET, 버튼 [{TAKEN}] [{SNOOZE}] [{SKIP}]; '{LOCK_TITLE}' 1개 PUBLIC",
              show_posted(items),
              len(five) == 5 and all(p.vis == "SECRET" and p.actions == [TAKEN, SNOOZE, SKIP] and p.channel == DOSES for p in five)
              and dose_names(rung) == sorted(NAMES)
              and len(lock_notice) == 1 and lock_notice[0].vis == "PUBLIC" and lock_notice[0].title == LOCK_TITLE)
    sound = channel_sound(NOTICES, dump)
    run.check("RR-54 '복용 시간이에요' 때문에 소리가 더 나지 않음",
              f"안내가 소리 없는 채널 {NOTICES}에 있음 (채널 mSound=null)",
              f"채널 {lock_notice[0].channel if lock_notice else '-'}, mSound={sound}",
              bool(lock_notice) and lock_notice[0].channel == NOTICES and sound == "null")
    after = alarms(REMINDER_ALARM)
    run.check("TASK-024 울린 뒤 다음 알람", "알림 알람 1개, 09:00", show_alarms(after),
              len(after) == 1 and after[0].when.endswith("09:00:00"))

    # 3. Lock screen, eight cells
    def cell(names_on: bool, device: str, tap_taken: bool = False) -> None:
        label = f"이름 표시 {'켬' if names_on else '끔'}, 기기 '{'내용 표시' if device == '1' else '내용 숨김'}'"
        sh(f"settings put secure lock_screen_allow_private_notifications {device}")
        lock()
        showing = keyguard_showing()
        locked = read_screen()
        tag = f"lock-{'on' if names_on else 'off'}-{device}"
        r.shot(tag)
        save_ui(r, tag)
        if names_on and device == "1":
            ok = len(locked.names) >= 1 and locked.button
            expected = "이름과 [먹었어]가 보임"
        else:
            ok = not locked.names and not locked.button and locked.generic
            expected = f"이름·버튼 없이 '{LOCK_TITLE}'만 (펼쳐도)"
        run.check(f"TC-013 잠금 화면: {label}", expected, f"잠금 {showing}; {locked.describe()}", ok and showing is True)
        if tap_taken:
            button = notification_button("PillA", TAKEN)
            if button is None:
                run.check("TC-013 잠금 화면 [먹었어]", "잠금을 풀지 않고 PillA 복용 기록", "PillA의 [먹었어]를 찾지 못함", False)
            else:
                tap(button)
                time.sleep(4)
                still = keyguard_showing()
                done = judgments(run.ids).get("PillA")
                gone = "PillA" not in dose_names(posted())
                r.shot("lock-taken")
                run.check("TC-013 잠금 화면 [먹었어]", "잠금을 풀지 않고 PillA 복용(TAKEN, NOTIFICATION), PillA 알림 사라짐",
                          f"잠금 {still}, 판정 {done}, 알림 {'사라짐' if gone else '남음'}",
                          still is True and done == ("TAKEN", "NOTIFICATION") and gone)
        unlocked = unlock()
        expand_shade()
        shade = read_screen()
        r.shot(f"shade-{'on' if names_on else 'off'}-{device}")
        collapse_shade()
        run.check(f"TC-013 잠금 해제: {label}", "알림창에 항목 이름이 보임",
                  f"풀림 {unlocked}; {shade.describe()}", unlocked and len(shade.names) >= 1)

    run.step("잠금 화면: 이름 표시 끔, 내용 표시", lambda: cell(False, "1"))
    run.step("잠금 화면: 이름 표시 끔, 내용 숨김", lambda: cell(False, "0"))

    def names_on() -> None:
        names_on_lock(True)
        items_ = posted()
        run.check("SPEC-034 이름 표시 켬", "떠 있는 항목 알림이 PRIVATE + 공개용 판으로 다시 뜸, '복용 시간이에요' 없어짐",
                  show_posted(items_),
                  len(doses(items_)) == 5 and all(p.vis == "PRIVATE" and p.public for p in doses(items_))
                  and not lock_notices(items_))
        cell(True, "0")
        cell(True, "1", tap_taken=True)

    run.step("잠금 화면: 이름 표시 켬", names_on)

    def names_off() -> None:
        names_on_lock(False)
        items_ = posted()
        run.check("SPEC-034 이름 표시 끔으로 되돌림", "항목 알림 SECRET, '복용 시간이에요' 다시 1개",
                  show_posted(items_),
                  all(p.vis == "SECRET" for p in doses(items_)) and len(lock_notices(items_)) == 1)

    run.step("이름 표시 끔으로 되돌림", names_off)

    # 4. Buttons, the app and a swipe; the lock-screen notice goes with the last reminder (N-13)
    def buttons() -> None:
        ensure_unlocked()
        press_in_shade("PillB", SKIP)
        press_in_shade("PillC", SNOOZE)
        state = judgments(run.ids)
        left = dose_names(posted())
        alarm = alarms(REMINDER_ALARM)
        run.check("TC-011·TC-012 알림 버튼",
                  "PillB 건너뜀(SKIPPED, NOTIFICATION), PillC 미룸 1건, 둘의 알림 사라짐, 다음 알람 08:30",
                  f"PillB {state.get('PillB')}, 미룸 {snoozes(run.ids)}, 남은 알림 {left}, 알람 {show_alarms(alarm)}",
                  state.get("PillB") == ("SKIPPED", "NOTIFICATION") and snoozes(run.ids) == ["PillC"]
                  and "PillB" not in left and "PillC" not in left
                  and len(alarm) == 1 and alarm[0].when.endswith("08:30:00"))

        launch_app()
        tap(row_button("PillD", TAKEN))
        time.sleep(4)
        r.shot("app-pilld")
        go_home()
        state = judgments(run.ids)
        left = dose_names(posted())
        run.check("RR-86 앱에서 기록", "PillD 복용(APP), PillD 알림 사라짐",
                  f"PillD {state.get('PillD')}, 남은 알림 {left}",
                  state.get("PillD") == ("TAKEN", "APP") and "PillD" not in left)

        swipe_away("PillE")
        items_ = posted()
        run.check("N-13 마지막 항목 알림을 밀어서 지움", f"PillE 알림 사라짐, 항목 알림 0개, '{LOCK_TITLE}'도 사라짐, PillE는 판정 없음",
                  f"{show_posted(items_)}; PillE 판정 {judgments(run.ids).get('PillE')}",
                  not doses(items_) and not lock_notices(items_) and "PillE" not in judgments(run.ids))

    run.step("버튼·앱·밀어서 지우기", buttons)

    # 5. The snooze rings at 08:30
    def snooze_rings() -> None:
        r.note(f"시계 {set_clock(at(0, 8, 29))}")
        items_ = wait_posted(lambda p: "PillC" in dose_names(p), timeout=300)
        r.shot("snooze-0830")
        run.check("RR-51 [30분 뒤]가 08:30에 울림", "PillC 알림 하나 다시 뜸, 다음 알람 09:00",
                  f"{show_posted(items_)}; 알람 {show_alarms(alarms(REMINDER_ALARM))}",
                  dose_names(items_) == ["PillC"])

    run.step("08:30 미룬 알림", snooze_rings)

    # 6. A missed alarm comes at once when the app opens (N-12)
    def missed() -> None:
        sh(f"am force-stop {PKG}")  # cancels the app's alarms and notifications, and stops broadcasts to it
        gone = alarms(REMINDER_ALARM)
        r.note(f"강제 종료 뒤 알림 알람 {show_alarms(gone)}; 시계 {set_clock(at(0, 9, 3))}")
        quiet = dose_names(posted())
        launch_app()
        items_ = wait_posted(lambda p: dose_names(p) == ["PillC", "PillE"], timeout=90)
        r.shot("missed-0900")
        go_home()
        run.check("N-12 놓친 09:00 알람", "09:03 앱을 열면 PillC·PillE 알림이 바로 뜸 (그 전에는 없음)",
                  f"열기 전 {quiet}, 연 뒤 {show_posted(items_)}",
                  not gone and not quiet and dose_names(items_) == ["PillC", "PillE"])

    run.step("놓친 알람", missed)

    # 7. Restore (R-1: the database is there but the install marker is not)
    def restore() -> None:
        sh(f"rm -f {DATA}/no_backup/install_marker")
        sh(f"am force-stop {PKG}")
        launch_app()
        items_ = wait_posted(lambda p: any(x.id == ID_RESTORE for x in p), timeout=30)
        r.shot("restored")
        notice = [x for x in items_ if x.id == ID_RESTORE]
        alarm = alarms(REMINDER_ALARM)
        pending = setting("restore_notice_pending")
        run.check("RR-30 복원 감지 뒤 (알림 몫)",
                  f"'{RESTORED_TITLE}' 알림 1개, 복용 알림 0개, 알림 알람 없음, pending 1",
                  f"{show_posted(items_)}; 알람 {show_alarms(alarm)}; pending {pending}",
                  len(notice) == 1 and notice[0].title == RESTORED_TITLE and not doses(items_) and not alarm
                  and pending == "1")

        r.note(f"시계 {set_clock(at(0, 10, 5))}")
        time.sleep(15)
        items_ = posted()
        alarm = alarms(REMINDER_ALARM)
        run.check("RR-30 확인 전에 10:00이 지나도", "복용 알림 없음, 복원 안내는 남음, 알림 알람 없음",
                  f"{show_posted(items_)}; 알람 {show_alarms(alarm)}",
                  not doses(items_) and any(x.id == ID_RESTORE for x in items_) and not alarm)

        launch_app()
        tap_text("확인했어요")
        time.sleep(5)
        r.shot("restore-confirmed")
        go_home()
        items_ = posted()
        alarm = alarms(REMINDER_ALARM)
        pending = setting("restore_notice_pending")
        run.check("RR-33·RR-73 [확인했어요]", "복원 안내 사라짐, 바로 뜨는 알림 없음, 다음 알람 11:00 (10:00이 아님), pending 0",
                  f"{show_posted(items_)}; 알람 {show_alarms(alarm)}; pending {pending}",
                  not items_ and len(alarm) == 1 and alarm[0].when.endswith("11:00:00") and pending == "0")

    run.step("복원 안내", restore)

    # 8. Reboot across 11:00
    def reboot() -> None:
        put_clock(int(at(0, 10, 59).timestamp()))  # no settling: the phone must go down before 11:00
        run.logs.append(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
        adb("reboot", check=False)
        time.sleep(10)
        adb("wait-for-device", timeout=300)
        wait_boot(600)
        become_root()
        wake_screen()
        booted = sh("date '+%H:%M:%S'").strip()
        unlocked = unlock()  # BOOT_COMPLETED reaches the app only after the first unlock
        items_ = wait_posted(lambda p: dose_names(p) == ["PillC", "PillE"], timeout=300)
        r.shot("after-reboot")
        run.check("RR-18·RR-55 재부팅이 11:00을 넘김",
                  "잠금을 풀면 PillC·PillE의 11:00 알림이 뜸, 다음 알람 12:00",
                  f"부팅 끝 {booted}, 풀림 {unlocked}; {show_posted(items_)}; 알람 {show_alarms(alarms(REMINDER_ALARM))}",
                  booted >= "11:00:00" and unlocked and dose_names(items_) == ["PillC", "PillE"])

    run.step("재부팅", reboot)

    # 9. The window ends at the next day start (04:00)
    def window_end() -> None:
        ensure_unlocked()
        r.note(f"시계 {set_clock(at(1, 4, 1))}")
        items_ = wait_posted(lambda p: not doses(p), timeout=60)
        alarm = alarms(REMINDER_ALARM)
        run.check("RR-87 창 끝(다음 날 04:00)", "어제 알림과 '복용 시간이에요'가 사라짐, 다음 알람 다음 날 08:00",
                  f"{show_posted(items_)}; 알람 {show_alarms(alarm)}",
                  not doses(items_) and not lock_notices(items_) and len(alarm) == 1
                  and alarm[0].when == at(1, 8).strftime("%Y-%m-%d %H:%M:%S"))

    run.step("창 끝", window_end)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    r = Report(args.out, api, f"알림 확인 API {api}")
    for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {name} 0")
    sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    # Lock as soon as the screen goes off, and show notifications on the lock screen at all (PR #15 experiment).
    sh("settings put secure lock_screen_lock_after_timeout 0", check=False)
    sh("settings put secure lock_screen_show_notifications 1", check=False)
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, {sh('getprop ro.build.fingerprint').strip()}")

    run = Run(r, args.apk)
    try:
        flow(run, api)
    except Exception as exc:  # a broken setup step: report what happened, then fail
        traceback.print_exc()
        r.check("스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            save_ui(r, "error")
        except Exception:
            pass
    finally:
        r.write()
        try:
            run.logs.append(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
            log = "\n--------- reboot ---------\n".join(run.logs)
            (args.out / f"api{api}-logcat.txt").write_text(log, encoding="utf-8")
        except Exception as exc:  # the report is already written; a missing logcat must not hide it
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
