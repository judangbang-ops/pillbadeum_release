#!/usr/bin/env python3
"""Dose reminder checks on an Android emulator (TASK-024, TASK-025, TASK-027, TASK-040).

Runs against one booted emulator whose image allows `adb root` (google_apis or default) and the debug APK.
The items are made by the debug-only receiver `androidApp/src/debug/.../ReminderSeedReceiver.kt`, through the
app's own use cases (the item screen's reminder field, TASK-023, is checked by the app test ItemReminderTest).
What the phone shows is read from `dumpsys alarm`, `dumpsys notification --noredact`, the screen
(uiautomator) and the app database (root). Alarm times are read from the epoch in `dumpsys alarm`, which is
the same on every image (Android 8 prints `when=` in system_server's own time zone).

Flow, on the run's date D in Asia/Seoul (five daily items PillA..PillE, reminders from 08:00, at most 5 a day):
   1. TASK-024 one alarm: with exact alarms refused (Android 12+), then allowed, `dumpsys alarm` holds one
      reminder alarm of the app at 08:00 (RR-54), inexact then exact; the day-boundary alarm is separate. The
      inexact one has a windowed second alarm at the same time (ReminderAlarm.WINDOW, 10 minutes), told apart by
      its flags; allowing exact alarms takes it off.
   2. 08:00 the alarm rings with the screen locked: five item reminders, all VISIBILITY_SECRET with
      [먹었어] [30분 뒤] [오늘 건너뜀], and one silent PUBLIC "복용 시간이에요" (SPEC-013, RR-54). They carry
      group keys without a summary, so Android does not bundle them under the app name (PR #20); Android 16
      sometimes still does (run 43370487070, not in run 43365673905), which the report notes.
   3. Lock screen, eight cells (TC-013): names off/on x the device setting "show all content" (1) / "hide
      sensitive content" (0) x locked/unlocked. Names off: no item name or button on the lock screen, even
      expanded, not even in a notification icon's description. Names on + show all: names and [먹었어], and
      [먹었어] of the first name shown records it without unlocking. Names on + hide: no name on the screen; on
      API 30 System UI still puts the private title into the description of an overflow icon (ICON_DESC), which
      is noted as a platform limit. When Android 16 has moved the reminders into its own bundle
      (AUTOGROUP_SUMMARY), the lock screen shows one folded app-name line instead of "복용 시간이에요": no
      name and no button is what counts there, and the report notes it (a limit). On Android 8 and 11 a tap
      does not expand a lock-screen notification (PR #15, section 3): only the names are checked there, a drag
      is tried for [먹었어], and when it fails that is a note, not a failure.
   4. Buttons (TC-011, TC-012): of the four other items, in name order, one [오늘 건너뜀] and one [30분 뒤]
      from the shade, one recorded in the app (RR-86) and one swiped away. When the lock screen recorded none,
      PillA is recorded from the shade first. Each reminder goes, and with the last one "복용 시간이에요"
      goes (N-13). The snooze rings 30 minutes after the press (`reminder_snooze.fire_at`), and that is the next
      alarm. A swipe that System UI does not take as a dismissal is tried again (swipe_away), and the report says
      which try worked; when none does, the shade's "Clear all" takes it off (the same delete intent) and the
      report says so.
   5. 30 minutes after the press the snoozed item rings (RR-51), and the next alarm is 09:00.
   6. A missed alarm (N-12): the app is force-stopped before 09:00, so the 09:00 alarm never comes; opening
      the app at 09:03 shows the 09:00 reminders of the snoozed and the swiped item at once.
   7. Restore (TASK-040, RR-30, RR-33, RR-73): the install marker is deleted, as after a restore (supplement
      R-1, RR-71). One restore notice, no dose reminder and no reminder alarm, even after 10:00 passes;
      [확인했어요] takes the notice off and plans 11:00 (not 10:00).
   8. Reboot (TASK-027, RR-18, RR-55): at 10:59 the app ops are written out (`adb reboot` skips the shutdown
      that saves them) and the phone restarts. The emulator comes back on the host's clock, on some images in
      UTC, so the zone and 11:01 are set again before unlocking; the app is not direct-boot aware, so the
      unlock's BOOT_COMPLETED is its first run. After unlocking, the 11:00 reminders show and the next alarm
      is 12:00.
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
from explore import ZONE, phone_now, put_clock, row_button, set_clock, set_zone
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
    def flags(self) -> int:
        m = re.search(r"\bflags=0x([0-9a-f]+)", self.block)
        return int(m.group(1), 16) if m else 0

    @property
    def windowed(self) -> bool:
        """The second alarm of the inexact path (`setWindow`, ReminderAlarm.WINDOW = 10 minutes): no allow-while-idle
        flag. The planned alarm carries FLAG_ALLOW_WHILE_IDLE (0x4, exact), FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED (0x8) or,
        inexact from Android 12, FLAG_ALLOW_WHILE_IDLE_COMPAT (0x20). Run 43365673905, API 34 with exact alarms refused:
        flags=0x20 window=+14m26s677ms and flags=0x0 window=+10m0s0ms, both at 08:00."""
        return not self.flags & 0x2C

    @property
    def millis(self) -> int | None:
        """The trigger time in epoch milliseconds, from the block head (`Alarm{… when 1794524400000 …}`, `origWhen`
        from Android 12)."""
        m = re.search(r"\b(?:origWhen|when) (\d{12,})", self.block)
        return int(m.group(1)) if m else None

    @property
    def when(self) -> str:
        """The trigger time in Asia/Seoul, from [millis]. The formatted `when=` is the system server's own zone: on API 26
        it stayed UTC after the zone was set (run 43360309166), and after a reboot the emulator may start in UTC."""
        if self.millis is not None:
            return datetime.fromtimestamp(self.millis / 1000, ZONE).strftime("%Y-%m-%d %H:%M:%S")
        m = re.search(r"\b(?:origWhen|when)=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", self.block)
        if m:
            return m.group(1)
        return "?"

    def describe(self) -> str:
        reason = re.search(r"exactAllowReason=(\S+)", self.block)
        return (f"{self.when}, window={self.window}" + (", 창 알람" if self.windowed else "")
                + (f", {reason.group(0)}" if reason else ""))


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
    # The summary Android posts in the app's name when it bundles the app's notifications itself (AUTOGROUP_SUMMARY).
    # Android 16 also bundles groups that have no summary, as the reminders' groups (run 43370487070, API 36).
    autogroup: bool = False


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
        channel = re.search(r"\bchannel=([^\s:]+)", head)  # API 26 prints "channel=dose_reminders: Notification(…"
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
            autogroup=bool(re.search(r"^\s*flags=\S*\bAUTOGROUP_SUMMARY\b", body, re.M)),
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
        label = "Android 자동 묶음 요약" if p.autogroup else p.title or p.text or "(제목 없음)"
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


def snoozes(ids: dict[str, str]) -> dict[str, int]:
    """Pending snoozes by item name: the time it rings again, in epoch milliseconds (`reminder_snooze.fire_at`)."""
    names = {v: k for k, v in ids.items()}
    return {names.get(r[0], r[0]): int(r[1]) for r in query("SELECT intake_id, fire_at FROM reminder_snooze")}


def clock_text(millis: int | None) -> str:
    return "-" if millis is None else datetime.fromtimestamp(millis / 1000, ZONE).strftime("%H:%M:%S")


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


# System UI describes a notification icon (status bar, the lock screen's shelf) as "<app> notification: <title>"
# (StatusBarIconView.contentDescForNotification). On API 30 that title is the private one even while the lock screen
# hides the content: with names shown and the device set to hide sensitive content, the shelf icons of the overflow
# read "DidITake notification: PillA 먹어~!" to accessibility, and the screen shows no name (run 43365673905).
ICON_DESC = re.compile(r"^.+ notification: ")


@dataclass
class Seen:
    names: list[str]
    button: bool
    generic: bool
    icon_names: list[str] = field(default_factory=list)  # names only in a notification icon's description

    def describe(self) -> str:
        icons = f", 알림 아이콘 설명에만 이름 {', '.join(self.icon_names)}" if self.icon_names else ""
        return (f"이름 {', '.join(self.names) or '없음'}, [먹었어] {'보임' if self.button else '없음'}, "
                f"'{LOCK_TITLE}' {'보임' if self.generic else '없음'}{icons}")


def read_once() -> Seen:
    nodes = dump_ui()
    blob = "\n".join(t for n in nodes for t in (n.text, "" if ICON_DESC.match(n.desc) else n.desc) if t)
    icons = "\n".join(n.desc for n in nodes if ICON_DESC.match(n.desc))
    return Seen([n for n in NAMES if n in blob], TAKEN in blob, LOCK_TITLE in blob,
                [n for n in NAMES if n in icons and n not in blob])


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
        names = [n for n in NAMES if n in seen.names or n in again.names]
        seen = Seen(names, seen.button or again.button, seen.generic or again.generic,
                    [n for n in NAMES if (n in seen.icon_names or n in again.icon_names) and n not in names])
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
            # A bundle (Android groups four or more of an app's notifications that carry no group key): its chevron is
            # on the bundle header above the collapsed line (run 43360309166, API 34).
            near = sorted((n for n in nodes if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc
                           and n.bounds[1] < head.bounds[1]), key=lambda n: -n.bounds[1])
        if not near:
            return None
        tap(near[0])
    return None


def drag_down(name: str) -> bool:
    """Drags `name`'s reminder down on the lock screen. On API 26 and 30 the expand chevron does nothing there (PR #15
    experiment, 3장); dragging a notification down opens it in the shade while the phone stays locked."""
    head = next((n for n in dump_ui() if title(name) in n.text), None)
    if head is None:
        return False
    _, h = screen_size()
    x, y = head.center
    sh(f"input swipe {x} {y} {x} {min(h - 40, y + 700)} 600")
    time.sleep(2)
    return True


def press_in_shade(name: str, label: str) -> None:
    expand_shade()
    button = notification_button(name, label)
    if button is None:
        raise RuntimeError(f"[{label}] of {name} not found in the shade")
    tap(button)
    time.sleep(3)
    collapse_shade()


def shade_title(name: str) -> Node | None:
    """The title of `name`'s reminder in the open shade, expanding a bundle once if it is not shown."""
    head = next((n for n in dump_ui() if title(name) in n.text), None)
    if head is None:
        expander = next((n for n in dump_ui() if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
        if expander is not None:
            tap(expander)
        head = next((n for n in dump_ui() if title(name) in n.text), None)
    return head


# How each try swipes: (start x, end x) as parts of the screen width, and the duration in ms.
SWIPES = ((0.2, 0.995, 300), (0.2, 0.995, 1000), (0.8, 0.005, 1000))


def swipe_away(r: Report, name: str, api: int) -> tuple[int, str]:
    """Takes `name`'s reminder off in the shade as a person does, and returns how: (the swipe that took it off, "swipe"),
    (the tries, "clear-all") when the shade's "Clear all" did it after every swipe failed, or (the tries, "") when
    nothing did.

    On the CI emulator a swipe did not always reach System UI as a dismissal: in run 43365673905 (API 30 and 34) the
    swipe went across the reminder's title in the open shade and the app never heard of it (no log after it, while on
    API 36 the dismissal's re-plan was logged at once). Each try opens the shade again, reads where the title is now,
    keeps a screenshot, and swipes slower or the other way; the report says which try worked. From API 29 a last try
    moves the finger in steps (`input motionevent`), so the move does not hang on how fast System UI takes events.

    In run 43370487070 no try worked on API 26 (three) or API 30 (four), although the same swipe had worked on API 26 in
    the run before: the touches were injected (logcat) and the reminder did not move. What the app does after a
    dismissal does not depend on the gesture: a swipe and "Clear all" both send its delete intent. So after the swipes
    the shade's "Clear all" is pressed, and the report says so."""
    w, _ = screen_size()
    tries = [*SWIPES, None] if api >= 29 else list(SWIPES)
    for attempt, swipe in enumerate(tries, 1):
        expand_shade()
        r.shot(f"swipe-{name}-{attempt}")
        head = shade_title(name)
        if head is None:
            save_ui(r, f"swipe-{name}-{attempt}")
            collapse_shade()
            raise RuntimeError(f"{name}'s reminder not found in the shade (try {attempt})")
        y = head.center[1]
        if swipe is None:
            xs = [int(w * f) for f in (0.2, 0.35, 0.5, 0.65, 0.8, 0.95)]
            moves = "; ".join(f"input motionevent MOVE {x} {y}" for x in xs[1:])
            sh(f"input motionevent DOWN {xs[0]} {y}; {moves}; input motionevent UP {xs[-1]} {y}")
        else:
            start, end, duration = swipe
            sh(f"input swipe {int(w * start)} {y} {int(w * end)} {y} {duration}")
        time.sleep(3)
        if name not in dose_names(wait_posted(lambda p: name not in dose_names(p), timeout=10)):
            collapse_shade()
            return attempt, "swipe"
        dump_ui()
        save_ui(r, f"swipe-{name}-{attempt}-after")
        collapse_shade()
    expand_shade()
    r.shot(f"swipe-{name}-clear-all")
    button = next((n for n in dump_ui() if n.text.strip().lower() == "clear all" or n.desc.lower().startswith("clear all")), None)
    if button is None:
        save_ui(r, f"swipe-{name}-clear-all")
        collapse_shade()
        return len(tries), ""
    tap(button)
    time.sleep(3)
    gone = name not in dose_names(wait_posted(lambda p: name not in dose_names(p), timeout=10))
    collapse_shade()  # "Clear all" closes the shade on most images; on the others this does
    return len(tries), "clear-all" if gone else ""


# ------------------------------------------------------------------------------------------------
# Run


class Run:
    def __init__(self, r: Report, apk: Path) -> None:
        self.r = r
        self.apk = apk
        self.ids: dict[str, str] = {}
        self.logs: list[str] = []  # logcat before each reboot (the buffer does not survive one)
        # Which item the lock screen records depends on which reminders Android shows there; the other four are then
        # skipped, snoozed, recorded in the app and swiped away, in NAMES order (step 4).
        self.lock_taken: str | None = None
        self.snoozed = "PillC"
        self.swiped = "PillE"
        self.snooze_at: int | None = None  # reminder_snooze.fire_at of the snoozed item, epoch ms (step 4)

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
    want_exact = not exact_gate
    first = wait_alarm(lambda a: len(a) >= (1 if want_exact else 2))
    dump = alarm_dump()
    run.save("alarm-after-seed", dump)
    boundary = alarms(BOUNDARY_ALARM, dump)
    planned = [a for a in first if not a.windowed]
    second = [a for a in first if a.windowed]
    if want_exact:
        expected = "알림 알람 1개, 08:00, 정확; 하루 경계 알람은 따로 1개"
        ok = len(first) == 1 and len(planned) == 1 and planned[0].exact and planned[0].when.endswith("08:00:00")
    else:
        # ReminderAlarm: the inexact alarm may ring up to an hour late, so a windowed one (10 minutes) goes with it.
        expected = ("알림 알람 1개, 08:00, 비정확(정확 알람 거부) + 같은 08:00의 10분 창 알람 1개(ReminderAlarm.WINDOW); "
                    "하루 경계 알람은 따로 1개")
        ok = (len(planned) == 1 and not planned[0].exact and planned[0].when.endswith("08:00:00")
              and len(second) == 1 and second[0].window == "+10m0s0ms" and second[0].when.endswith("08:00:00"))
    run.check("TASK-024 항목 다섯 개, 같은 08:00 (RR-54)", expected,
              f"알림 알람 {show_alarms(first)}; 경계 알람 {len(boundary)}개", ok and len(boundary) == 1)

    if exact_gate:
        sh(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
        granted = wait_alarm(lambda a: len(a) == 1 and a[0].exact, timeout=45)
        run.save("alarm-after-allow", alarm_dump())
        run.check("TASK-024 정확 알람을 허용한 뒤",
                  "권한 바뀜 방송으로 다시 계산: 알림 알람 1개, 08:00, 정확 (창 알람은 지워짐)",
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
    if any(p.autogroup for p in items):
        r.note("RR-54: Android가 앱 알림을 모두 자기 묶음으로 바꿈(AUTOGROUP_SUMMARY). 항목 알림의 묶음에 요약이 없어서 "
               "Android 16이 다시 묶은 경우(보충 N-13 '구현'의 한계). 잠금 화면 칸의 판정은 3단계")
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
        tag = f"lock-{'on' if names_on else 'off'}-{device}"
        r.shot(tag)  # before any tap: a tap on a hidden-content reminder brings up the PIN pad
        locked = read_screen()
        save_ui(r, tag)
        if names_on and device == "1" and api >= 31:
            ok = len(locked.names) >= 1 and locked.button
            expected = "이름과 [먹었어]가 보임"
        elif names_on and device == "1":
            ok = len(locked.names) >= 1
            expected = "이름이 보임 ([먹었어]는 보지 않음: API 26·30 잠금 화면에서는 펼치기 탭이 먹지 않음, PR #15 실험 3장)"
        elif names_on:
            # Names shown, device hides content: the screen shows the public versions. A name only in the description of
            # an overflow icon is System UI's (API 30, see ICON_DESC): noted, not counted.
            ok = not locked.names and not locked.button and locked.generic
            expected = f"이름·버튼 없이 '{LOCK_TITLE}'만 (펼쳐도)"
            # Android 16 may move the reminders into its own bundle (AUTOGROUP_SUMMARY, see Posted.autogroup); its summary
            # has no public version, so the lock screen shows one folded line with the app name and no text, and tapping
            # its chevron asks for the PIN (run 43370487070, API 36; not in run 43365673905). No name and no button is
            # what counts there; the missing '복용 시간이에요' is a limit (supplement N-13 '구현'), checked on a Galaxy
            # in TASK-052.
            bundled = api >= 36 and any(p.autogroup for p in posted())
            if bundled:
                expected += "; Android가 자기 묶음으로 바꾸면 이름·버튼 없이 앱 이름 줄만"
            if bundled and not ok and not locked.names and not locked.button:
                ok = True
                r.note(f"TC-013 {label}: Android 16이 알림을 자기 묶음(AUTOGROUP_SUMMARY)으로 바꿔 잠금 화면에는 "
                       f"'{LOCK_TITLE}' 없이 앱 이름 줄 하나만 접혀 보임(펼치려면 PIN). 이름·버튼은 없음. "
                       f"보충 N-13 '구현'의 한계, Galaxy에서 다시 봄(TASK-052). 캡처 {tag}")
            if locked.icon_names:
                r.note(f"TC-013 {label}: 화면에는 이름이 없고, 잠금 화면 알림 아이콘의 접근성 설명에만 "
                       f"{', '.join(locked.icon_names)}이 있음(API {api} System UI가 숨긴 알림의 제목을 아이콘 설명에 씀, "
                       "앱이 막을 수 없는 플랫폼 한계)")
        else:
            # Names hidden: the reminders are SECRET and not on the lock screen at all, so not even an icon may name one.
            ok = not locked.names and not locked.icon_names and not locked.button and locked.generic
            expected = f"이름·버튼 없이 '{LOCK_TITLE}'만 (펼쳐도, 알림 아이콘 설명에도 이름 없음)"
        run.check(f"TC-013 잠금 화면: {label}", expected, f"잠금 {showing}; {locked.describe()}", ok and showing is True)
        if tap_taken:
            lock()
            shown = read_once().names
            name, button = None, None
            for candidate in shown:
                button = notification_button(candidate, TAKEN)
                if button is None and api < 31 and drag_down(candidate):
                    button = notification_button(candidate, TAKEN)
                if button is not None:
                    name = candidate
                    break
            if button is None and api < 31:
                r.note(f"TC-013 잠금 화면 [먹었어]: 잠금 화면의 {shown or '알림'}을 펼치지 못해 누르지 못함(API 26·30, PR #15 실험 3장과 같음). "
                       "다음 단계는 알림창에서 하나를 기록하고 이어 감")
            elif button is None:
                run.check("TC-013 잠금 화면 [먹었어]", "잠금을 풀지 않고 복용 기록", f"잠금 화면의 이름 {shown}, [먹었어]를 찾지 못함", False)
            else:
                tap(button)
                time.sleep(4)
                still = keyguard_showing()
                done = judgments(run.ids).get(name)
                gone = name not in dose_names(posted())
                r.shot("lock-taken")
                run.lock_taken = name
                run.check("TC-013 잠금 화면 [먹었어]", "잠금을 풀지 않고 보이는 항목 하나 복용(TAKEN, NOTIFICATION), 그 알림 사라짐",
                          f"{name}: 잠금 {still}, 판정 {done}, 알림 {'사라짐' if gone else '남음'}",
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
        taken = run.lock_taken
        skipped, snoozed, in_app, swiped = (n for n in NAMES if n != (taken or NAMES[0]))
        run.snoozed, run.swiped = snoozed, swiped
        if taken is None:
            # The lock screen recorded none (API 26·30, see step 3): one from the shade, so the rest of the flow holds.
            taken = NAMES[0]
            press_in_shade(taken, TAKEN)
            r.note(f"{taken}: 알림창의 [{TAKEN}]로 기록함, 판정 {judgments(run.ids).get(taken)}")
        press_in_shade(skipped, SKIP)
        before = phone_now() * 1000
        press_in_shade(snoozed, SNOOZE)
        state = judgments(run.ids)
        left = dose_names(posted())
        alarm = alarms(REMINDER_ALARM)
        snoozed_until = snoozes(run.ids)
        run.snooze_at = snoozed_until.get(snoozed)
        # [30분 뒤] rings 30 minutes after the press (usecase/Reminders.kt: fireAt = now + PlanReminders.SNOOZE), not at
        # the slot + 30: the press comes some minutes after 08:00 here (run 43365673905: 08:04:03 -> 08:34:03).
        on_time = run.snooze_at is not None and 30 * 60_000 <= run.snooze_at - before <= 32 * 60_000
        until = ", ".join(f"{k} {clock_text(v)}" for k, v in snoozed_until.items()) or "없음"
        run.check("TC-011·TC-012 알림 버튼",
                  f"{skipped} 건너뜀(SKIPPED, NOTIFICATION), {snoozed} 미룸 1건(누른 때부터 30분 뒤), 둘의 알림 사라짐, "
                  "다음 알람 = 미룬 시각",
                  f"{skipped} {state.get(skipped)}, 미룸 {until} (누르기 직전 시계 {clock_text(before)}), "
                  f"남은 알림 {left}, 알람 {show_alarms(alarm)}",
                  state.get(skipped) == ("SKIPPED", "NOTIFICATION") and list(snoozed_until) == [snoozed] and on_time
                  and skipped not in left and snoozed not in left
                  and len(alarm) == 1 and alarm[0].millis is not None and abs(alarm[0].millis - run.snooze_at) < 1000)

        launch_app()
        tap(row_button(in_app, TAKEN))
        time.sleep(4)
        r.shot("app-taken")
        go_home()
        state = judgments(run.ids)
        left = dose_names(posted())
        run.check("RR-86 앱에서 기록", f"{in_app} 복용(APP), {in_app} 알림 사라짐",
                  f"{in_app} {state.get(in_app)}, 남은 알림 {left}",
                  state.get(in_app) == ("TAKEN", "APP") and in_app not in left)

        tried, how = swipe_away(r, swiped, api)
        items_ = wait_posted(lambda p: not doses(p) and not lock_notices(p), timeout=15)
        if how == "clear-all":
            time.sleep(5)  # the app re-plans after the delete intent: it must not put '복용 시간이에요' back
            items_ = posted()
        result = {"swipe": f"{tried}번째 밀기에 사라짐",
                  "clear-all": f"밀기 {tried}번이 먹지 않아 알림창의 '모두 지우기'로 지움"}.get(how, "밀어도, '모두 지우기'로도 남음")
        run.check("N-13 마지막 항목 알림을 밀어서 지움",
                  f"{swiped} 알림 사라짐, 항목 알림 0개, '{LOCK_TITLE}'도 사라짐, {swiped}는 판정 없음 (밀기가 먹지 않으면 '모두 지우기')",
                  f"{result}; {show_posted(items_)}; {swiped} 판정 {judgments(run.ids).get(swiped)}",
                  bool(how) and not doses(items_) and not lock_notices(items_) and swiped not in judgments(run.ids))
        if how == "swipe" and tried > 1:
            r.note(f"N-13 밀어서 지우기: 앞의 {tried - 1}번은 System UI가 지우기로 받지 않았음(앱에 아무것도 닿지 않음), "
                   f"{tried}번째({'손가락을 나눠 움직임' if tried > len(SWIPES) else '더 느리게 또는 반대로'})에 지워짐. 캡처 swipe-{swiped}-*")
        elif how == "clear-all":
            r.note(f"N-13 밀어서 지우기: 밀기 {tried}번을 System UI가 지우기로 받지 않아(앱에 아무것도 닿지 않음, 캡처 swipe-{swiped}-*) "
                   f"알림창의 '모두 지우기'로 지움. 앱에는 밀기와 같은 삭제 인텐트가 감({swiped} 판정 없음). '모두 지우기'는 "
                   f"'{LOCK_TITLE}'도 함께 지우므로, 마지막 알림이 없어지면 앱이 안내를 내리는지는 여기서 보지 못하고 앱 시험 "
                   "ReminderFlowTest(swiping_reminders_away_keeps_their_slots_and_the_last_takes_the_lock_notice_off)가 봄. "
                   "여기서는 그 뒤에 앱이 안내를 다시 띄우지 않는지만 봄")

    run.step("버튼·앱·밀어서 지우기", buttons)

    # 5. The snooze rings 30 minutes after the press
    def snooze_rings() -> None:
        if run.snooze_at is None:
            raise RuntimeError("no snooze time: step 4 did not snooze")
        due = datetime.fromtimestamp(run.snooze_at / 1000, ZONE)
        # Item reminders still up from step 4 (none when it passed) stay; only the snoozed item may come back.
        before = dose_names(posted())
        r.note(f"시계 {set_clock(due - timedelta(minutes=1))}")
        items_ = wait_posted(lambda p: run.snoozed in dose_names(p), timeout=300)
        alarm = alarms(REMINDER_ALARM)
        r.shot("snooze-rings")
        run.check(f"RR-51 [30분 뒤]가 {due:%H:%M:%S}에 울림",
                  f"{run.snoozed} 알림 하나 다시 뜸, 다음 알람 09:00" + (f" (4단계에서 남은 {before}는 그대로)" if before else ""),
                  f"{show_posted(items_)}; 알람 {show_alarms(alarm)}",
                  dose_names(items_) == sorted({*before, run.snoozed}) and len(alarm) == 1
                  and alarm[0].when.endswith("09:00:00"))

    run.step("미룬 알림", snooze_rings)

    # 6. A missed alarm comes at once when the app opens (N-12)
    def missed() -> None:
        sh(f"am force-stop {PKG}")  # cancels the app's alarms and notifications, and stops broadcasts to it
        gone = alarms(REMINDER_ALARM)
        r.note(f"강제 종료 뒤 알림 알람 {show_alarms(gone)}; 시계 {set_clock(at(0, 9, 3))}")
        quiet = dose_names(posted())
        launch_app()
        due = sorted([run.snoozed, run.swiped])
        items_ = wait_posted(lambda p: dose_names(p) == due, timeout=90)
        r.shot("missed-0900")
        go_home()
        run.check("N-12 놓친 09:00 알람", f"09:03 앱을 열면 {'·'.join(due)} 알림이 바로 뜸 (그 전에는 없음)",
                  f"열기 전 {quiet}, 연 뒤 {show_posted(items_)}",
                  not gone and not quiet and dose_names(items_) == due)

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
        put_clock(int(at(0, 10, 59).timestamp()))
        # `adb reboot` skips the framework shutdown that writes app ops to disk: without this the exact-alarm allow of
        # step 1 was gone after the reboot on API 34 (an hour-wide alarm, run 43360309166).
        sh("cmd appops write-settings", check=False)
        run.logs.append(adb("logcat", "-d", "-v", "time", check=False, timeout=60))
        adb("reboot", check=False)
        time.sleep(10)
        adb("wait-for-device", timeout=300)
        wait_boot(600)
        become_root()
        # The emulator comes back on the host's clock (API 26·34·36) and, on some images, in UTC (API 34·36); API 30 kept
        # 10:59 and finished booting before 11:00 (run 43360309166). The app is not direct-boot aware, so nothing reaches
        # it before the first unlock: set the zone and 11:01 now, and the unlock's BOOT_COMPLETED is the first run.
        zone = set_zone()
        clock = set_clock(at(0, 11, 1))
        wake_screen()
        unlocked = unlock()
        due = sorted([run.snoozed, run.swiped])
        items_ = wait_posted(lambda p: dose_names(p) == due, timeout=300)
        alarm = wait_alarm(lambda a: len(a) == 1 and a[0].when.endswith("12:00:00"), timeout=60)
        r.shot("after-reboot")
        run.check("RR-18·RR-55 재부팅이 11:00을 넘김",
                  f"잠금을 풀면 {'·'.join(due)}의 11:00 알림이 뜸, 다음 알람 12:00",
                  f"부팅 뒤 {zone} {clock}, 풀림 {unlocked}; {show_posted(items_)}; 알람 {show_alarms(alarm)}",
                  unlocked and dose_names(items_) == due and len(alarm) == 1 and alarm[0].when.endswith("12:00:00"))

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
