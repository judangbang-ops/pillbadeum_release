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
  B. The next updates, with no hands (REQ-096: "손을 대지 않고"). The phone is put in the state a tester's phone is in
     after Obtainium installed one release and before the next one: --b-old (the latest release's commit built with
     versionCode one less, so versionName 0.1.0.<versionCode - 1>) is installed with Obtainium as the installer of
     record (`pm install -i`), and Obtainium's own record of the app (its app_data JSON file) says that version is
     installed and the latest, and that it last looked for updates seven hours ago (it looks again after six, its
     default). Obtainium is stopped before setting up the APK and record, then started normally to load that record.
     Then nobody touches anything: HOME, and Android runs Obtainium's background work when it is due (a
     WorkManager job every 15 minutes, whose run checks the apps due). Nothing is forced: `cmd jobscheduler run -f`
     does not help, since WorkManager does not run periodic work before its time, and `am kill` or `am force-stop`
     would make WorkManager cancel and schedule the job again.
     Checks: within 20 minutes versionCode is the latest release's, no confirmation window of the package installer
     was on screen meanwhile, and Obtainium is still the installer of record. Obtainium's log lines about the run and
     the job list (`dumpsys jobscheduler`) are kept.
     If the background work does not update within the wait, the update is started in Obtainium's screen once, to
     tell an update that needs a confirmation from one whose background run did not come: both are failures.

Writes report.md, screenshots, screen dumps and logcat to --out. Exits 1 when a check fails.
Standard library only. Not product code. adb and screen helpers are in backup_restore_check.py.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import traceback
from collections.abc import Callable
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
    become_root,
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
FIRST_DIALOG_TITLES = ("Welcome", "Note")
PILLBADEUM_WORDS = ("필받음", "Pillbadeum")
# Obtainium's background work: a WorkManager job every 15 minutes (main.dart). Phase B waits for it with a margin
B_WAIT = 20 * 60
# Phase B's log, kept from its start: the emulator's log buffers (2 MB, `logcat -G 2M` after boot) would drop it in 20
# minutes. Obtainium's own lines, WorkManager's, and Android's about jobs and installs
B_LOG_TAGS = ("flutter:I", "WM-WorkerWrapper:D", "WM-SystemJobScheduler:D", "WM-ForceStopRunnable:D", "JobScheduler:V",
              "PackageInstaller:V", "PackageInstallerSession:V", "*:S")
# Obtainium's own log lines about a background run (lib/providers/apps_provider.dart, apps_provider_install.dart)
OBTAINIUM_LOG = re.compile(r"I/flutter.*(BG (update|install) task|BG task|App will not be installed|Installing \"|"
                           r"WorkManager callback)|WM-WorkerWrapper.*Delaying execution")


class Steps:
    """Screens and dumps named in order, so a failed run shows how far Obtainium got."""

    def __init__(self, r: Report) -> None:
        self.r = r
        self.n = 0

    def save(self, name: str, nodes: list[Node] | None = None) -> list[Node]:
        self.n += 1
        if nodes is None:
            nodes = screen_nodes()
        tag = f"{self.n:02d}-{name}"
        xml = adb("exec-out", "cat", UI_XML, check=False)
        (self.r.out / f"ui-{tag}.xml").write_text(xml, encoding="utf-8")
        self.r.shot(name)
        return nodes


def screen_nodes(tries: int = 6) -> list[Node]:
    """The screen's nodes, or none when uiautomator cannot dump it (a window that never drew its first frame)."""
    try:
        return dump_ui(tries=tries)
    except RuntimeError as exc:
        if "not responding" in str(exc):
            raise
        return []


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


def package(pkg: str) -> dict[str, str]:
    out = sh(f"dumpsys package {pkg}", check=False)
    get = lambda key: (m.group(1) if (m := re.search(rf"\b{key}=(\S+)", out)) else "")
    return {"versionCode": get("versionCode"), "versionName": get("versionName"),
            "installer": get("installerPackageName"), "initiator": get("installInitiatingPackageName")}


def focused_package() -> str:
    for section in ("displays", "windows"):
        out = sh(f"dumpsys window {section}", check=False)
        if m := re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/\s}]+)", out):
            return m.group(1)
    return ""


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


def start_obtainium(steps: Steps, r: Report, ready: Callable[[list[Node]], bool] | None = None) -> None:
    """Obtainium on screen past its splash, started from the launcher as a tester does.

    On the CI emulator the first start stayed on the splash for good (run 45018288077, 2026-11-22): Android
    re-created the activity two seconds in, while the input devices were reconfigured after boot, two replies on
    Flutter's platform channel were lost ('FlutterJNI was detached from native C++'), and the first Flutter frame never
    came. A tester's phone is not just booted. Here Obtainium is stopped and started again, up to three times, and each
    try that stays on the splash is kept as a screen and noted."""
    for attempt in range(1, 4):
        sh(f"monkey -p {OBTAINIUM} -c android.intent.category.LAUNCHER 1", check=False)
        end = time.monotonic() + 45
        while time.monotonic() < end:
            time.sleep(3)
            if focused_package() != OBTAINIUM:
                continue
            nodes = screen_nodes(tries=1)
            rendered = ready(nodes) if ready is not None else bool(words(nodes))
            if rendered:
                if attempt > 1:
                    r.note(f"Obtainium이 {attempt}번째 시작에서 첫 화면을 그림(앞의 시작은 시작 화면에 멈춤)")
                return
        steps.save(f"obtainium-splash-{attempt}")
        sh(f"am force-stop {OBTAINIUM}", check=False)
        time.sleep(2)
    raise RuntimeError("Obtainium stayed on its splash screen in 3 starts")


def close_first_dialogs(steps: Steps, r: Report, ready: Callable[[list[Node]], bool] = bool,
                        timeout: float = 20) -> list[Node]:
    """Wait for a usable screen and dismiss first-run dialogs that arrive after it first renders."""
    end = time.monotonic() + timeout
    ready_since = None
    tapped = 0
    while True:
        nodes = screen_nodes(tries=1)
        # The known titles identify Welcome and Note even when the Flutter barrier is absent in the dump.
        title = next((label for label in FIRST_DIALOG_TITLES if node_with(nodes, (label,))), None)
        okay = node_with(nodes, OKAY_WORDS)
        now = time.monotonic()
        if okay is not None and (title or node_with(nodes, ("Dismiss",))):
            if tapped == 5:
                raise RuntimeError(f"Obtainium dialogs did not close: {words(nodes)}")
            steps.save(f"obtainium-dialog-{(title or 'untitled').lower()}", nodes)
            tap(okay)
            r.note(f"Obtainium 첫 시작 대화상자 '{title or '제목 없음'}'를 [Okay]로 닫음")
            tapped += 1
            ready_since = None
            time.sleep(2)
            # Give the next screen its own wait time after capturing and closing this dialog.
            end = time.monotonic() + timeout
            continue
        if ready(nodes):
            if ready_since is None:
                ready_since = now
            if now - ready_since >= 4:
                return nodes
        else:
            ready_since = None
        if now >= end:
            return []
        time.sleep(1)


def add_screen_ready(nodes: list[Node], repo_url: str) -> bool:
    return node_with(nodes, ADD_WORDS) is not None and any(
        n.cls.endswith("EditText") and repo_url in n.text for n in nodes
    )


def open_add_screen(steps: Steps, r: Report, repo_url: str) -> str:
    """Obtainium's add screen with repo_url in its URL field. Returns how it was opened."""
    start_obtainium(steps, r)
    nodes = close_first_dialogs(steps, r)
    if not nodes:
        raise RuntimeError("Obtainium first screen did not become ready")
    steps.save("obtainium-first", nodes)
    sh(f"am start -a android.intent.action.VIEW -d 'obtainium://add/{repo_url}' {OBTAINIUM}", check=False)

    nodes = close_first_dialogs(steps, r, lambda screen: add_screen_ready(screen, repo_url), timeout=15)
    steps.save("obtainium-link", nodes or None)
    if nodes:
        return "링크 obtainium://add/"
    # Not by the link: [Add App] at the bottom of Obtainium's first screen, type the URL
    sh("input keyevent KEYCODE_BACK", check=False)
    time.sleep(2)
    if focused_package() != OBTAINIUM:
        start_obtainium(steps, r)

    def add_nav(nodes: list[Node]) -> Node | None:
        button = node_with(nodes, ADD_APP_WORDS)
        if button is not None and button.cls.endswith("Button"):
            return button
        return node_with(nodes, ADD_WORDS) if node_with(nodes, ("Apps",)) else None

    nodes = close_first_dialogs(steps, r, lambda screen: add_nav(screen) is not None)
    nav = add_nav(nodes)
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
    nodes = close_first_dialogs(steps, r, lambda screen: add_screen_ready(screen, repo_url), timeout=15)
    add = node_with(nodes, ADD_WORDS)
    if add is None:
        steps.save("no-add")
        raise RuntimeError(f"Obtainium [Add] not on screen: {words(dump_ui())}")
    tap(add)
    # Adding reads the release list from GitHub. A dialog may follow (e.g. options for the source): its OK-like
    # button is tapped, and what it said is kept in the screen dump.
    # On a cold emulator the repository fetch and its detail screen can take more than a minute.
    end = time.monotonic() + 180
    while time.monotonic() < end:
        time.sleep(3)
        nodes = dump_ui(tries=6)
        if node_with(nodes, UPDATE_WORDS) or node_with(nodes, PILLBADEUM_WORDS + (PKG,)):
            steps.save("added", nodes)
            return how
        ok = node_with(nodes, ("OK", "Okay", "Continue", "Yes"))
        if ok is not None:
            steps.save("add-dialog", nodes)
            tap(ok)
    steps.save("add-not-complete", nodes)
    raise RuntimeError(f"Obtainium did not finish adding the repository: {words(nodes)}")


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


def wait_version(code: str, timeout: float = 120, watch_installer: list[str] | None = None,
                 every: float = 2) -> dict[str, str]:
    """Waits until the app's versionCode is code. With watch_installer, records whenever the package installer's
    window has the focus meanwhile."""
    end = time.monotonic() + timeout
    while True:
        info = package(PKG)
        if watch_installer is not None:
            focus = focused_package()
            if focus in INSTALLER_UI and focus not in watch_installer:
                watch_installer.append(focus)
        remaining = end - time.monotonic()
        if info["versionCode"] == code or remaining <= 0:
            return info
        time.sleep(min(every, remaining))


def obtainium_jobs(r: Report, name: str) -> list[str]:
    """Obtainium's jobs in `dumpsys jobscheduler` ('JOB #u0a193/0: 4efec10 dev.imranr.obtainium/…'); the whole
    dump is kept as jobs-<name>.txt."""
    out = sh("dumpsys jobscheduler", check=False)
    (r.out / f"jobs-{name}.txt").write_text(out, encoding="utf-8")
    ids = []
    for m in re.finditer(r"JOB\s+\S*?/(-?\d+): \S+ " + re.escape(OBTAINIUM) + r"/", out):
        if m.group(1) not in ids:
            ids.append(m.group(1))
    return ids


def record_path() -> str:
    """Obtainium's record of the app: app_data/<package>.json in its external files folder (or its own data folder
    when that is missing; lib/providers/apps_provider_lifecycle.dart getAppsDir). Needs root."""
    out = sh(f"find /sdcard/Android/data/{OBTAINIUM}/files /data/data/{OBTAINIUM} -name {PKG}.json", check=False)
    paths = [p.strip() for p in out.splitlines() if p.strip().endswith(f"/app_data/{PKG}.json")]
    if not paths:
        raise RuntimeError(f"Obtainium's record of {PKG} not found: {out.strip()[:300]}")
    return paths[0]


def verify_obtainium_record(path: str, expected: dict[str, str | int]) -> None:
    saved = json.loads(sh(f"cat {path}"))
    actual = {key: saved.get(key) for key in expected}
    if actual != expected:
        raise RuntimeError(f"Obtainium's B setup record changed: expected {expected}, found {actual}")


def set_obtainium_record(r: Report, path: str, version: str, hours_ago: int = 7) -> dict[str, str | int]:
    """Obtainium's record of the app as on a phone where Obtainium installed `version` itself (it then keeps the
    release tag as both the installed and the latest version) and last looked for updates `hours_ago` hours back.
    Only these three fields change (lib/models/app.dart: lastUpdateCheck is in microseconds)."""
    rec = json.loads(sh(f"cat {path}"))
    now = int(sh("date +%s").strip())
    expected = {"installedVersion": version, "latestVersion": version,
                "lastUpdateCheck": (now - hours_ago * 3600) * 1_000_000}
    was = ", ".join(f"{key} {rec.get(key)}" for key in expected)
    rec.update(expected)
    local = r.out / "obtainium-record-b.json"
    local.write_text(json.dumps(rec, ensure_ascii=False), encoding="utf-8")
    adb("push", str(local), "/data/local/tmp/obtainium-record.json")
    sh(f"cat /data/local/tmp/obtainium-record.json > {path}")
    verify_obtainium_record(path, expected)
    r.note(f"Obtainium 기록을 설치·최신 {version}, 마지막 확인 {hours_ago}시간 전으로 둠(바꾸기 전: {was})")
    return expected


def obtainium_log(path: Path) -> list[str]:
    """Obtainium's log lines about its background runs, from the log kept in path."""
    text = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
    return [line.strip()[:220] for line in text.splitlines() if OBTAINIUM_LOG.search(line)]


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


def phase_b(r: Report, steps: Steps, b_old: Path, code: str) -> None:
    r.note("B. 그 뒤의 업데이트(손대지 않음)")
    b_code = str(int(code) - 1)
    become_root()
    # Stop Obtainium before changing the installed APK or its record, so its old in-memory state cannot overwrite it.
    sh(f"am force-stop {OBTAINIUM}")
    adb("uninstall", PKG, check=False)
    adb("install", "-i", OBTAINIUM, str(b_old), timeout=300)
    info = package(PKG)
    if not r.check("B-1 Obtainium이 한 번 업데이트한 뒤와 같게",
                   f"versionCode {b_code}, versionName 0.1.0.{b_code}, 설치한 앱 {OBTAINIUM}",
                   f"versionCode {info['versionCode']}, versionName {info['versionName']}, 설치한 앱 '{info['installer']}'",
                   info["versionCode"] == b_code and info["versionName"] == f"0.1.0.{b_code}"
                   and info["installer"] == OBTAINIUM):
        return
    path = record_path()
    expected = set_obtainium_record(r, path, f"v0.1.0.{b_code}")
    start_obtainium(steps, r, lambda nodes: node_with(nodes, PILLBADEUM_WORDS) is not None)
    verify_obtainium_record(path, expected)
    # From HOME onward nobody touches the phone or forces any scheduled work.
    log_path = r.out / f"api{api_level()}-logcat-b.txt"
    with log_path.open("w", encoding="utf-8") as log_file:
        log = subprocess.Popen(["adb", "logcat", "-v", "time", "-T", "1", *B_LOG_TAGS], stdout=log_file,
                               stderr=subprocess.STDOUT, text=True)
        try:
            sh("input keyevent KEYCODE_HOME")
            jobs = obtainium_jobs(r, "b-start")
            r.note(f"Obtainium 예약 작업(JobScheduler) 시작 때: {', '.join(jobs) or '없음'}")
            shown: list[str] = []
            started = time.monotonic()
            # An installer confirmation stays open without a tap, so frequent dumps are unnecessary here.
            info = wait_version(code, B_WAIT, shown, every=30)
            waited = int(time.monotonic() - started)
            background = info["versionCode"] == code
            if background:
                time.sleep(10)  # Obtainium's lines after the install
            jobs = obtainium_jobs(r, "b-end")
        finally:
            log.terminate()
            try:
                log.wait(timeout=30)
            except subprocess.TimeoutExpired:
                log.kill()
                log.wait()
    r.note(f"Obtainium 예약 작업(JobScheduler) {waited // 60}분 {waited % 60}초 뒤: {', '.join(jobs) or '없음'}")
    try:
        after = json.loads(sh(f"cat {path}"))
        r.note("Obtainium 기록 기다린 뒤: " + ", ".join(f"{key} {after.get(key)}" for key in expected))
    except (RuntimeError, ValueError) as exc:
        r.note(f"Obtainium 기록을 다시 읽지 못함: {exc}")
    for line in obtainium_log(log_path)[:25]:
        r.note(f"Obtainium 기록: `{line}`")
    if background:
        steps.save("after-b")
    else:
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
    r.check(f"B-2 예약 작업만으로 업데이트({B_WAIT // 60}분 안)", f"versionCode {code}", f"versionCode "
            f"{info['versionCode']}" + (f", {waited // 60}분 {waited % 60}초 뒤" if background else " (예약 작업으로는 안 됨)"),
            background)
    r.check("B-3 Android 확인 창", "안 뜸", ", ".join(shown) or "안 뜸", not shown)
    r.check("B-4 업데이트 뒤 설치한 앱", OBTAINIUM, info["installer"] or "(없음)", info["installer"] == OBTAINIUM)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--old", type=Path, required=True, help="the testers' APK: older main build, app-signing key")
    parser.add_argument("--b-old", type=Path, help="phase B's start: the latest release's commit, versionCode one less")
    parser.add_argument("--obtainium", type=Path, required=True, help="Obtainium APK")
    parser.add_argument("--repo-url", required=True, help="https://github.com/<owner>/<repo> with the releases")
    parser.add_argument("--code", required=True, help="versionCode of the latest release")
    parser.add_argument("--phases", choices=("A", "AB"), default="AB", help="A only or A followed by B (default)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.phases == "AB" and args.b_old is None:
        parser.error("--phases AB requires --b-old")
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    api = api_level()
    r = Report(args.out, api, f"Obtainium 업데이트 확인 API {api}")
    steps = Steps(r)
    old_code, old_name = apk_badging(args.old)
    try:
        sh("settings put global package_verifier_enable 0", check=False)
        phase_a(r, steps, args.old, args.obtainium, args.repo_url, args.code)
        if args.phases == "AB":
            phase_b(r, steps, args.b_old, args.code)
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
               f"{old_name or '?'}), B 시작 APK {args.b_old.name if args.b_old else '-'}, 최신 릴리즈 versionCode "
               f"{args.code}, Obtainium {ob['versionName'] or '?'}")
        r.write()
        (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", "-v", "time", check=False), encoding="utf-8")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
