#!/usr/bin/env python3
"""Tester phone auto-update with Obtainium on one emulator (API 34): TASK-055, REQ-096, TC-089.

The testers run a personal APK built from an older main commit and signed with the app-signing key, installed by hand
(versionName 0.1.0, no installer of record). From now on publish-apk.yml puts every main app build, signed with the
same key, on this repository's GitHub releases as v0.1.0.<versionCode>, and Obtainium on the tester's phone installs
it. This script makes that phone on an emulator and lets the real Obtainium do the update from the real release:

  A. The first update, as a tester does it once (docs/07-qa/obtainium-testers.md, steps 1 to 4).
     --old is installed with `adb install` (no installer of record, as a hand install), one item is added in the app,
     then Obtainium is installed with notifications allowed and 'install unknown apps' allowed (`appops`, the switch a
     tester turns on in Android's window). The repository is added in Obtainium (its add screen, opened by the
     `obtainium://add/<url>` link or by [Add App] when the link does not open it), the update is started in Obtainium,
     and Android asks to confirm, since Obtainium did not install the app: [Update] is tapped there.
     Checks: versionCode is the latest release's, versionName 0.1.0.<versionCode>, the installer of record is now
     Obtainium, the item added before is still there.
  B. The next updates, with no hands (REQ-096: "손을 대지 않고"). The app is put back to --old the way phase A leaves
     it, with Obtainium as the installer of record (`pm install -i`), without opening the app. Obtainium is not
     opened either (it is sent to the background and its process ended with `am kill`, never force-stopped, which
     would cancel its jobs): its scheduled background work is run now (`cmd jobscheduler run -f` for each of its
     jobs), as Android would every six hours. Checks: versionCode is the latest release's and no confirmation window of the
     package installer was on screen meanwhile.
     If the background work does not update within the wait, the update is started in Obtainium's screen once, to
     tell an update that needs a confirmation from one whose background run did not come: both are failures.

Writes report.md, screenshots, screen dumps and logcat to --out. Exits 1 when a check fails.
Standard library only. Not product code. adb and screen helpers are in backup_restore_check.py.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import traceback
from pathlib import Path

from backup_restore_check import (
    ITEM_A,
    PKG,
    UI_XML,
    Node,
    Report,
    adb,
    add_item,
    api_level,
    dump_ui,
    find,
    launch_app,
    sh,
    stop_app,
    tap,
    wait_boot,
)

OBTAINIUM = "dev.imranr.obtainium"
# The package installer's confirmation window, by package: the Google one on google_apis images, the AOSP one else
INSTALLER_UI = ("com.google.android.packageinstaller", "com.android.packageinstaller")
# Obtainium's own words (assets/translations/en.json), and Android's on the confirmation window, in English
ADD_WORDS = ("Add",)
ADD_APP_WORDS = ("Add App", "Add app")
UPDATE_WORDS = ("Update", "Install", "Update all", "Install update", "Install updates")
CONFIRM_WORDS = ("Update", "Install", "UPDATE", "INSTALL")
URL_HINTS = ("App source URL", "App Source URL", "URL")
OKAY_WORDS = ("Okay", "OK")


class Steps:
    """Screens and dumps named in order, so a failed run shows how far Obtainium got."""

    def __init__(self, r: Report) -> None:
        self.r = r
        self.n = 0

    def save(self, name: str) -> list[Node]:
        self.n += 1
        nodes = dump_ui(tries=6)
        tag = f"{self.n:02d}-{name}"
        xml = adb("exec-out", "cat", UI_XML, check=False)
        (self.r.out / f"ui-{tag}.xml").write_text(xml, encoding="utf-8")
        self.r.shot(name)
        return nodes


def words(nodes: list[Node]) -> str:
    seen = []
    for n in nodes:
        for s in (n.text, n.desc):
            s = s.strip().replace("\n", " ")
            if s and s not in seen:
                seen.append(s)
    return " / ".join(seen)[:600]


def node_with(nodes: list[Node], labels: tuple[str, ...], pkg_hint: str = "") -> Node | None:
    """A node whose text or description is one of labels (exactly, or the first line of a Flutter description)."""
    for label in labels:
        for n in nodes:
            first = n.desc.split("\n")[0].strip()
            if n.text.strip() == label or first == label:
                return n
    return None


def wait_for(labels: tuple[str, ...], timeout: float = 30) -> Node | None:
    end = time.time() + timeout
    while True:
        node = node_with(dump_ui(tries=6), labels)
        if node is not None or time.time() >= end:
            return node
        time.sleep(1)


def package(pkg: str) -> dict[str, str]:
    out = sh(f"dumpsys package {pkg}", check=False)
    get = lambda key: (m.group(1) if (m := re.search(rf"\b{key}=(\S+)", out)) else "")
    return {"versionCode": get("versionCode"), "versionName": get("versionName"),
            "installer": get("installerPackageName"), "initiator": get("installInitiatingPackageName")}


def focused_package() -> str:
    out = sh("dumpsys window displays", check=False) + sh("dumpsys window windows", check=False)
    m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/\s}]+)", out)
    return m.group(1) if m else ""


def apk_badging(apk: Path) -> tuple[str, str]:
    """versionCode and versionName of an APK, from the zip's AndroidManifest via `aapt2` when on PATH, else ''."""
    for tool in ("aapt2", "aapt"):
        try:
            out = subprocess.run([tool, "dump", "badging", str(apk)], capture_output=True, text=True, timeout=60).stdout
        except (OSError, subprocess.SubprocessError):
            continue
        code = re.search(r"versionCode='(\d+)'", out)
        name = re.search(r"versionName='([^']*)'", out)
        if code:
            return code.group(1), name.group(1) if name else ""
    return "", ""


def allow_obtainium() -> None:
    """What a tester allows once: notifications (Obtainium's first start asks) and 'install unknown apps' (Android
    asks at the first update)."""
    sh(f"pm grant {OBTAINIUM} android.permission.POST_NOTIFICATIONS", check=False)
    sh(f"appops set {OBTAINIUM} REQUEST_INSTALL_PACKAGES allow")


def start_obtainium(steps: Steps, r: Report) -> None:
    """Obtainium on screen past its splash, started from the launcher as a tester does.

    On the CI emulator the first start stayed on the splash for good (run 45018288077, 2026-11-22): Android
    re-created the activity two seconds in, while the input devices were reconfigured after boot, two replies on
    Flutter's platform channel were lost ('FlutterJNI was detached from native C++'), and the first Flutter frame never
    came. A tester's phone is not just booted. Here Obtainium is stopped and started again, up to three times, and each
    try that stays on the splash is kept as a screen and noted."""
    for attempt in range(1, 4):
        sh(f"monkey -p {OBTAINIUM} -c android.intent.category.LAUNCHER 1", check=False)
        end = time.time() + 45
        while time.time() < end:
            time.sleep(3)
            if focused_package() == OBTAINIUM and words(dump_ui(tries=6)):
                if attempt > 1:
                    r.note(f"Obtainium이 {attempt}번째 시작에서 첫 화면을 그림(앞의 시작은 시작 화면에 멈춤)")
                return
        steps.save(f"obtainium-splash-{attempt}")
        sh(f"am force-stop {OBTAINIUM}", check=False)
        time.sleep(2)
    raise RuntimeError("Obtainium stayed on its splash screen in 3 starts")


def close_first_dialogs(steps: Steps, r: Report) -> None:
    """Obtainium 1.7.4 opens with dialogs that a tester closes with [Okay]: 'Welcome' (a link to its README), then
    'Note' (Google's developer verification, keepandroidopen.org), seen in run 45019114890. Each is kept as a screen
    and its title noted, for the testers' guide."""
    for _ in range(5):
        nodes = dump_ui(tries=6)
        okay = node_with(nodes, OKAY_WORDS)
        if okay is None or node_with(nodes, ("Dismiss",)) is None:
            return
        title = next((n.desc.split("\n")[0].strip() for n in nodes if n.desc.strip()), "")
        steps.save(f"obtainium-dialog-{title.lower() or 'untitled'}")
        r.note(f"Obtainium 첫 시작 대화상자 '{title}'를 [Okay]로 닫음")
        tap(okay)
        time.sleep(2)
    raise RuntimeError(f"Obtainium dialogs did not close: {words(dump_ui())}")


def open_add_screen(steps: Steps, r: Report, repo_url: str) -> str:
    """Obtainium's add screen with repo_url in its URL field. Returns how it was opened."""
    start_obtainium(steps, r)
    close_first_dialogs(steps, r)
    steps.save("obtainium-first")
    sh(f"am start -a android.intent.action.VIEW -d 'obtainium://add/{repo_url}' {OBTAINIUM}", check=False)
    time.sleep(6)
    nodes = steps.save("obtainium-link")
    if any(repo_url in n.text or repo_url in n.desc for n in nodes) and node_with(nodes, ADD_WORDS):
        return "링크 obtainium://add/"
    # Not by the link: [Add App] at the bottom of Obtainium's first screen, type the URL
    sh("input keyevent KEYCODE_BACK", check=False)
    time.sleep(2)
    if focused_package() != OBTAINIUM:
        start_obtainium(steps, r)
    close_first_dialogs(steps, r)
    nav = wait_for(ADD_APP_WORDS, 20)
    if nav is None:
        steps.save("no-add-app")
        raise RuntimeError(f"Obtainium [Add App] not on screen: {words(dump_ui())}")
    tap(nav)
    nodes = steps.save("add-app")
    field = next((n for n in nodes if n.cls.endswith("EditText")), None)
    if field is None:
        raise RuntimeError(f"no URL field on Obtainium's add screen: {words(nodes)}")
    tap(field)
    sh(f"input text '{repo_url}'")
    time.sleep(1)
    sh("input keyevent KEYCODE_BACK", check=False)  # closes the keyboard; the add screen stays
    time.sleep(1)
    return "[Add App]에 URL 입력"


def add_repo(steps: Steps, r: Report, repo_url: str) -> str:
    how = open_add_screen(steps, r, repo_url)
    add = wait_for(ADD_WORDS, 15)
    if add is None:
        steps.save("no-add")
        raise RuntimeError(f"Obtainium [Add] not on screen: {words(dump_ui())}")
    tap(add)
    # Adding reads the release list from GitHub. A dialog may follow (e.g. options for the source): its OK-like
    # button is tapped, and what it said is kept in the screen dump.
    end = time.time() + 60
    while time.time() < end:
        time.sleep(3)
        nodes = dump_ui(tries=6)
        if node_with(nodes, UPDATE_WORDS) or any("pillbadeum" in (n.text + n.desc).lower() for n in nodes):
            break
        ok = node_with(nodes, ("OK", "Okay", "Continue", "Yes"))
        if ok is not None:
            steps.save("add-dialog")
            tap(ok)
    steps.save("added")
    return how


def start_update_in_obtainium(steps: Steps) -> None:
    """The app's [Update]/[Install] in Obtainium: on its app page, or on the list."""
    for _ in range(3):
        nodes = dump_ui(tries=6)
        button = node_with(nodes, UPDATE_WORDS)
        if button is not None:
            steps.save("update-button")
            tap(button)
            return
        # The list: open the app's row, whose page has the button
        row = next((n for n in nodes if "pillbadeum" in (n.text + n.desc).lower() or "필받음" in (n.text + n.desc)), None)
        if row is not None:
            tap(row)
            time.sleep(3)
            continue
        # Pull down to check for updates
        sh("input swipe 540 700 540 1600 400", check=False)
        time.sleep(5)
    steps.save("no-update-button")
    raise RuntimeError(f"no [Update]/[Install] for the app in Obtainium: {words(dump_ui())}")


def confirm_if_asked(steps: Steps, timeout: float = 90) -> bool:
    """Waits for the package installer's window and taps its [Update]. True when it was on screen."""
    end = time.time() + timeout
    while time.time() < end:
        if focused_package() in INSTALLER_UI:
            nodes = steps.save("confirm")
            button = next((n for n in nodes if n.rid == "android:id/button1"), None) or node_with(nodes, CONFIRM_WORDS)
            if button is None:
                raise RuntimeError(f"package installer window without [Update]: {words(nodes)}")
            tap(button)
            return True
        time.sleep(1)
    return False


def wait_version(code: str, timeout: float = 120, watch_installer: list[str] | None = None) -> dict[str, str]:
    """Waits until the app's versionCode is code. With watch_installer, records whenever the package installer's
    window has the focus meanwhile."""
    end = time.time() + timeout
    info = package(PKG)
    while time.time() < end:
        info = package(PKG)
        if info["versionCode"] == code:
            return info
        if watch_installer is not None:
            focus = focused_package()
            if focus in INSTALLER_UI and focus not in watch_installer:
                watch_installer.append(focus)
        time.sleep(2)
    return info


def obtainium_jobs() -> list[str]:
    out = sh("dumpsys jobscheduler", check=False)
    ids = []
    for m in re.finditer(r"JOB #u\d+a?\d*/(-?\d+): \S+ " + re.escape(OBTAINIUM) + r"/", out):
        if m.group(1) not in ids:
            ids.append(m.group(1))
    return ids


def phase_a(r: Report, steps: Steps, old: Path, obtainium: Path, repo_url: str, code: str) -> None:
    r.note("A. 첫 업데이트(테스터가 한 번 하는 것)")
    adb("install", str(old), timeout=300)
    info = package(PKG)
    r.check("A-1 옛 APK를 손으로 깐 상태", "versionName 0.1.0, 설치한 앱 없음",
            f"versionCode {info['versionCode']}, versionName {info['versionName']}, 설치한 앱 '{info['installer']}'",
            info["versionName"] == "0.1.0" and info["installer"] in ("", "null"))
    launch_app()
    add_item(ITEM_A, r)
    stop_app()
    adb("install", str(obtainium), timeout=300)
    allow_obtainium()
    how = add_repo(steps, r, repo_url)
    r.note(f"Obtainium에 저장소를 더한 방법: {how}")
    start_update_in_obtainium(steps)
    asked = confirm_if_asked(steps)
    info = wait_version(code, 180)
    steps.save("after-a")
    r.check("A-2 Android 확인 창(손으로 깐 앱의 첫 업데이트)", "뜸 → [Update]", "뜸" if asked else "안 뜸", asked)
    r.check("A-3 업데이트 뒤 버전", f"versionCode {code}, versionName 0.1.0.{code}",
            f"versionCode {info['versionCode']}, versionName {info['versionName']}",
            info["versionCode"] == code and info["versionName"] == f"0.1.0.{code}")
    r.check("A-4 설치한 앱", OBTAINIUM, info["installer"] or "(없음)", info["installer"] == OBTAINIUM)
    launch_app()
    kept = find(ITEM_A, 15) is not None
    steps.save("app-after-a")
    r.check("A-5 업데이트 전 기록", f"{ITEM_A}이 그대로 있음", "있음" if kept else "없음", kept)
    stop_app()


def phase_b(r: Report, steps: Steps, old: Path, code: str) -> None:
    r.note("B. 그 뒤의 업데이트(손대지 않음)")
    adb("uninstall", PKG, check=False)
    adb("install", "-i", OBTAINIUM, str(old), timeout=300)
    info = package(PKG)
    old_code = info["versionCode"]
    r.check("B-1 A가 끝난 상태와 같게", f"옛 APK, 설치한 앱 {OBTAINIUM}",
            f"versionCode {old_code}, 설치한 앱 '{info['installer']}'", info["installer"] == OBTAINIUM and old_code != code)
    # Obtainium goes to the background as on a phone left alone: HOME, then its process is ended the way Android ends
    # a background app (`am kill`). Not `am force-stop`: that cancels every scheduled job of the package
    # (JobSchedulerService on ACTION_PACKAGE_RESTARTED), which no tester does.
    sh("input keyevent KEYCODE_HOME", check=False)
    time.sleep(2)
    sh(f"am kill {OBTAINIUM}", check=False)
    time.sleep(2)
    jobs = obtainium_jobs()
    r.note(f"Obtainium 예약 작업(JobScheduler): {', '.join(jobs) or '없음'}")
    shown: list[str] = []
    for job in jobs:
        sh(f"cmd jobscheduler run -f {OBTAINIUM} {job}", check=False)
    info = wait_version(code, 240, shown)
    background = info["versionCode"] == code
    if not background:
        steps.save("b-no-background")
        r.note("예약 작업으로 업데이트되지 않아 Obtainium 화면에서 한 번 업데이트를 누름(원인을 가리려고)")
        try:
            start_obtainium(steps, r)
            start_update_in_obtainium(steps)
            if confirm_if_asked(steps, 60):
                shown.append("[Update]를 누른 뒤 확인 창")
            info = wait_version(code, 180, shown)
        except RuntimeError as exc:
            r.note(f"화면에서 업데이트 못 함: {exc}")
    steps.save("after-b")
    r.check("B-2 예약 작업만으로 업데이트", f"versionCode {code}", f"versionCode {info['versionCode']}"
            + ("" if background else " (예약 작업으로는 안 됨)"), background)
    r.check("B-3 Android 확인 창", "안 뜸", ", ".join(shown) or "안 뜸", not shown)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--old", type=Path, required=True, help="the testers' APK: older main build, app-signing key")
    parser.add_argument("--obtainium", type=Path, required=True, help="Obtainium APK")
    parser.add_argument("--repo-url", required=True, help="https://github.com/<owner>/<repo> with the releases")
    parser.add_argument("--code", required=True, help="versionCode of the latest release")
    parser.add_argument("--phases", default="AB", help="A, B or AB (default)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    api = api_level()
    r = Report(args.out, api, f"Obtainium 업데이트 확인 API {api}")
    steps = Steps(r)
    old_code, old_name = apk_badging(args.old)
    try:
        sh("settings put global package_verifier_enable 0", check=False)
        if "A" in args.phases:
            phase_a(r, steps, args.old, args.obtainium, args.repo_url, args.code)
        if "B" in args.phases:
            phase_b(r, steps, args.old, args.code)
    except Exception as exc:
        traceback.print_exc()
        try:
            steps.save("stopped")
        except Exception:
            pass
        r.check("확인 진행", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        ob = package(OBTAINIUM)
        model = sh("getprop ro.product.model", check=False).strip()
        r.note(f"기기: API {api}, {model}. 옛 APK {args.old.name} (versionCode {old_code or '?'}, versionName "
               f"{old_name or '?'}), 최신 릴리즈 versionCode {args.code}, Obtainium {ob['versionName'] or '?'}")
        r.write()
        (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", "-v", "time", check=False), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
