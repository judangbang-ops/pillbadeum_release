#!/usr/bin/env python3
"""Lock-screen notification experiment (TASK-025 groundwork, 2026-11-12 review B6, REQ-046, REQ-076).

Question: with "이름 표시" off (the default), the lock screen must show only "복용 시간이에요" and no
buttons, whatever the device's lock-screen notification setting is. screens.md SCR-003 plans a
VISIBILITY_PRIVATE notification with a public version, but the official docs do not say whether the
device setting "show all content" overrides that (review B6). This script measures it.

On one booted emulator, with the experiment app (experiments/lockscreen) installed:

  1. Set a PIN (a secure lock screen; redaction only applies there) and lock immediately on sleep.
  2. For each device setting (lock_screen_allow_private_notifications 1 = show all content,
     0 = hide sensitive content) and each posting method of the experiment app:
     sleep -> post the two reminders ("아침약", "저녁약", buttons 먹었어/30분 뒤/오늘 건너뜀) -> wake on
     the lock screen -> read the screen (uiautomator) -> unlock with the PIN -> open the shade -> read.
  3. Write report.md (one row per setting and method), results.json, screenshots and screen dumps.

What is read is text on screen: an item name, the body time (08:02), the button 먹었어, the generic
title 복용 시간이에요. A collapsed notification hides its buttons, so a missing button in the shade
is not a finding by itself; the screenshots show the layout.

This is an experiment, not a pass/fail check of the product: it exits 1 only when the run itself
broke (could not lock, unlock or post). Standard library only. Helpers are in backup_restore_check.py.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

from backup_restore_check import CLOSED_DIALOGS, adb, api_level, dump_ui, sh, tap, wait_boot

EXP_PKG = "com.judangbang.pillbadeum.lockexp"
RECEIVER = f"{EXP_PKG}/.PostReceiver"
PIN = "1234"
NAMES = ("아침약", "저녁약")
BODY = "08:02"
BUTTON = "먹었어"
GENERIC = "복용 시간이에요"

# Device setting "lock_screen_allow_private_notifications": 1 = show all content, 0 = hide sensitive content
DEVICE_SETTINGS = (("내용 표시", "1"), ("내용 숨김", "0"))

# (method, what it is) in the order of the report; the receiver documents each one.
METHODS = (
    ("private_public", "PRIVATE + 공개용 판 (screens.md 계획)"),
    ("private_nopublic", "PRIVATE, 공개용 판 없음 (참고)"),
    ("secret", "SECRET"),
    ("secret_summary", "SECRET + PUBLIC 묶음 요약 '복용 시간이에요'"),
    ("secret_plus_public", "SECRET + 따로 PUBLIC '복용 시간이에요'"),
    ("public_generic", "PUBLIC '복용 시간이에요'만 (참고)"),
    ("public_full", "PUBLIC (이름 표시 켬의 계획)"),
)


class Run:
    def __init__(self, out: Path, api: int) -> None:
        self.out = out
        self.api = api
        self.shots = 0
        self.results: list[dict] = []
        self.notes: list[str] = []
        self.broken: list[str] = []

    def note(self, text: str) -> None:
        self.notes.append(text)
        print(f"[note] {text}", flush=True)

    def save(self, name: str) -> None:
        """Screenshot and the screen dump that dump_ui() left in /sdcard/ui.xml."""
        self.shots += 1
        stem = self.out / f"api{self.api}-{self.shots:02d}-{name}"
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=False, timeout=60).stdout
        stem.with_suffix(".png").write_bytes(png)
        stem.with_suffix(".xml").write_text(adb("exec-out", "cat", "/sdcard/ui.xml", check=False), encoding="utf-8")


# ------------------------------------------------------------------------------------------------
# Device


KEYGUARD_SOURCES = (
    # PhoneWindowManager's KeyguardServiceDelegate block ("KeyguardServiceDelegate" then "showing=true"), all versions
    ("dumpsys window policy", r"KeyguardServiceDelegate\s+showing=(true|false)"),
    # ActivityTaskManager's KeyguardController (Android 10+)
    ("dumpsys activity activities", r"\bmKeyguardShowing=(true|false)"),
    ("dumpsys window", r"\bmShowingLockscreen=(true|false)"),
    ("dumpsys window", r"\bisStatusBarKeyguard=(true|false)"),
)


def keyguard_showing() -> bool | None:
    """Whether the lock screen is up. Field names differ between versions, so several dumps are tried.
    (The first run on 2026-11-12 looked only at the last two and got None on API 34.)"""
    cache: dict[str, str] = {}
    for cmd, pattern in KEYGUARD_SOURCES:
        out = cache.setdefault(cmd, sh(cmd, check=False))
        m = re.search(pattern, out)
        if m:
            return m.group(1) == "true"
    return None


def save_keyguard_dumps(out: Path, api: int) -> None:
    """Raw dumps once, so a version whose fields are not matched can be read afterwards."""
    text = "\n".join(f"===== {cmd}\n{sh(cmd, check=False)}" for cmd in dict.fromkeys(c for c, _ in KEYGUARD_SOURCES))
    (out / f"api{api}-keyguard-dumps.txt").write_text(text, encoding="utf-8")


def set_pin() -> str:
    for cmd in (f"locksettings set-pin {PIN}", f"cmd lock_settings set-pin {PIN}"):
        out = sh(cmd, check=False).strip()
        if PIN in out or "set" in out.lower():
            return f"{cmd} -> {out}"
    raise RuntimeError(f"could not set a PIN: {out}")


def sleep_screen() -> None:
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)


def wake_screen() -> None:
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(3)


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


def post(method: str) -> str:
    out = sh(f"am broadcast -n {RECEIVER} --es method {method}")
    if f"posted {method}" not in out:
        raise RuntimeError(f"posting {method} failed: {out.strip()}")
    m = re.search(r'data="([^"]*)"', out)
    return m.group(1) if m else out.strip()


def read_once() -> dict:
    nodes = dump_ui()
    blob = "\n".join(t for n in nodes for t in (n.text, n.desc) if t)
    return {
        "names": [name for name in NAMES if name in blob],
        "body": BODY in blob,
        "button": BUTTON in blob,
        "generic": GENERIC in blob,
    }


def read_screen(expand: int = 3) -> dict:
    """Reads the screen, then taps up to [expand] notification expand buttons (buttons show only on an
    expanded notification) and reads again. The result is what any of the reads saw."""
    seen = read_once()
    taps = 0
    for _ in range(expand):
        button = next((n for n in dump_ui() if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc), None)
        if button is None:
            break
        tap(button)
        taps += 1
        again = read_once()
        seen = {
            "names": [name for name in NAMES if name in seen["names"] or name in again["names"]],
            "body": seen["body"] or again["body"],
            "button": seen["button"] or again["button"],
            "generic": seen["generic"] or again["generic"],
        }
    seen["expand_taps"] = taps
    return seen


# ------------------------------------------------------------------------------------------------
# Experiment


def one_case(run: Run, setting_label: str, setting_value: str, method: str) -> dict:
    sh(f"settings put secure lock_screen_allow_private_notifications {setting_value}")
    post("cancel")
    sleep_screen()
    posted = post(method)
    wake_screen()
    locked_kg = keyguard_showing()
    locked = read_screen()
    run.save(f"{method}-{setting_value}-locked")
    unlocked_ok = unlock()
    expand_shade()
    shade = read_screen()
    run.save(f"{method}-{setting_value}-shade")
    collapse_shade()
    if locked_kg is not True:
        run.broken.append(f"{setting_label}/{method}: lock screen not detected after wake ({locked_kg})")
    if not unlocked_ok:
        run.broken.append(f"{setting_label}/{method}: could not unlock with the PIN")
    result = {
        "api": run.api,
        "setting": setting_label,
        "setting_value": setting_value,
        "method": method,
        "posted": posted,
        "keyguard_after_wake": locked_kg,
        "unlocked": unlocked_ok,
        "locked": locked,
        "shade": shade,
        # The SCR-003 rule for "이름 표시" off: lock screen has no name and no button but says 복용 시간이에요;
        # the unlocked shade has the names.
        "meets_names_off": (not locked["names"] and not locked["button"] and locked["generic"]
                            and len(shade["names"]) == len(NAMES)),
    }
    print(f"[case] {json.dumps(result, ensure_ascii=False)}", flush=True)
    return result


def yes(v: bool) -> str:
    return "보임" if v else "-"


def write_report(run: Run) -> None:
    label = dict(METHODS)
    lines = [
        f"### 잠금 화면 알림 실험 API {run.api}",
        "",
        "이름 표시 끔의 기준(SCR-003): 잠금 화면에 이름·버튼 없이 '복용 시간이에요', 잠금을 푼 알림창에 이름 둘.",
        "",
        "| 기기 설정 | 방법 | 잠금 화면 이름 | 잠금 화면 본문 | 잠금 화면 버튼 | 잠금 화면 '복용 시간이에요' | 알림창 이름 | 알림창 버튼 | 알림창 '복용 시간이에요' | 끔 기준 충족 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in run.results:
        lk, sd = r["locked"], r["shade"]
        lines.append(
            f"| {r['setting']} | {label[r['method']]} | {', '.join(lk['names']) or '-'} | {yes(lk['body'])} | {yes(lk['button'])} "
            f"| {yes(lk['generic'])} | {', '.join(sd['names']) or '-'} | {yes(sd['button'])} | {yes(sd['generic'])} "
            f"| {'예' if r['meets_names_off'] else '아니오'} |"
        )
    notes = list(run.notes)
    if CLOSED_DIALOGS:
        notes.append(f"[Wait]로 닫은 시스템 ANR 대화상자 {len(CLOSED_DIALOGS)}번: {', '.join(sorted(set(CLOSED_DIALOGS)))}")
    if run.broken:
        notes.append("실행이 깨진 곳: " + "; ".join(run.broken))
    if notes:
        lines += ["", *[f"- {n}" for n in notes]]
    (run.out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (run.out / "results.json").write_text(json.dumps(run.results, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    api = api_level()
    run = Run(args.out, api)
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("settings put system screen_off_timeout 1800000", check=False)
    # Lock as soon as the screen goes off, and show notifications on the lock screen at all.
    sh("settings put secure lock_screen_lock_after_timeout 0", check=False)
    sh("settings put secure lock_screen_show_notifications 1", check=False)
    run.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, {sh('getprop ro.build.fingerprint').strip()}")

    try:
        print(adb("install", "-r", "-g", str(args.apk), timeout=300), flush=True)
        run.note(set_pin())
        save_keyguard_dumps(args.out, api)
        # Unlock once so the first case starts from a normal unlocked phone.
        sleep_screen()
        wake_screen()
        run.note(f"PIN 뒤 잠금 화면: {keyguard_showing()}, 풀기: {unlock()}")
        for setting_label, setting_value in DEVICE_SETTINGS:
            for method, _ in METHODS:
                run.results.append(one_case(run, setting_label, setting_value, method))
    except Exception as exc:  # report what happened, then fail
        run.broken.append(f"script: {type(exc).__name__}: {exc}")
        try:
            run.save("error")
        except Exception:
            pass
    finally:
        write_report(run)
        try:
            (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60), encoding="utf-8")
        except Exception as exc:
            print(f"[note] logcat not saved: {exc}", flush=True)
    return 1 if run.broken else 0


if __name__ == "__main__":
    sys.exit(main())
