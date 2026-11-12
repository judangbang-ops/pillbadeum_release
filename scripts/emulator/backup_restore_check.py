#!/usr/bin/env python3
"""Backup, restore and restore-detection checks on an Android emulator (TASK-028, TASK-029).

Runs against one booted emulator (adb on PATH, a google_apis image that allows `adb root`) and an
installed-from-scratch APK. Drives the app through its own screens (uiautomator dump + input tap),
backs up with the local test transport (`bmgr`), reinstalls, and reads the app's files as root.

Cases (docs/04-spec/record-rules-supplement.md):
  API 31+  RR-26/RR-69 cloud backup with encryption, RR-30 notice, RR-32 record before confirm,
           RR-33 confirm (app part), RR-31 update, RR-71 restore over the installed app,
           RR-34 app data cleared, RR-68 no encryption, device-to-device transfer (B-3)
  API 28-30 the local transport cannot set the client-side encryption flag (AOSP
           LocalTransportParameters before Android 12), so only "nothing is uploaded without it"
  API 26-27 RR-70: the rule file stops the backup (parse failure is logged)

Writes report.md, screenshots and logs to --out. Exits 1 when a check fails.
Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PKG = "com.judangbang.pillbadeum"
ACTIVITY = f"{PKG}/.MainActivity"
DATA = f"/data/data/{PKG}"
DB = "diditake.db"
DB_DOMAIN = "db"  # tar folder of the `database` backup domain
LOCAL_TRANSPORT = "com.android.localtransport/.LocalTransport"  # its name from Android 9 on
transport = LOCAL_TRANSPORT  # the name setup_transport() selected on this device
CONTROL_PKG = "com.android.providers.settings"
CONTROL_OK = "Backup finished with result: Success"
# The app's own `bmgr backupnow` line when its backup ran. "Transport rejected package" is the
# test transport declining a backup whose preflight measured zero bytes: LocalTransport
# .checkFullBackupSize() returns TRANSPORT_PACKAGE_REJECTED only for size <= 0 while backing up
# (AOSP android-11.0.0_r1, packages/LocalTransport), so it means the rules let no file out.
BACKUP_RAN = ("with result: Success", "Transport rejected package")
ITEM_A = "TestA"
ITEM_B = "TestB"
NOTICE = "백업에서 복원했어요"


# ------------------------------------------------------------------------------------------------
# adb


def run(args: list[str], check: bool = True, timeout: int = 120) -> str:
    proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    out = (proc.stdout or "") + (proc.stderr or "")
    if check and proc.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {proc.returncode}\n{out}")
    return out


def adb(*args: str, check: bool = True, timeout: int = 120) -> str:
    return run(["adb", *args], check=check, timeout=timeout)


def sh(cmd: str, check: bool = True, timeout: int = 120) -> str:
    return adb("shell", cmd, check=check, timeout=timeout)


def api_level() -> int:
    return int(sh("getprop ro.build.version.sdk").strip())


def become_root() -> None:
    adb("root", check=False)
    time.sleep(2)
    adb("wait-for-device")
    who = sh("id -u").strip()
    if who != "0":
        raise RuntimeError(f"adb root failed (id -u = {who}); use a google_apis image")


def wait_boot(timeout: int = 300) -> None:
    end = time.time() + timeout
    while time.time() < end:
        if sh("getprop sys.boot_completed", check=False).strip() == "1":
            return
        time.sleep(2)
    raise RuntimeError("emulator did not finish booting")


# ------------------------------------------------------------------------------------------------
# Screen


@dataclass
class Node:
    text: str
    desc: str
    cls: str
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def dump_ui() -> list[Node]:
    for _ in range(5):
        out = sh("uiautomator dump /sdcard/ui.xml", check=False)
        if "dumped" in out:
            xml = adb("exec-out", "cat", "/sdcard/ui.xml")
            nodes = []
            for el in ET.fromstring(xml).iter("node"):
                m = BOUNDS.match(el.get("bounds", ""))
                if not m:
                    continue
                nodes.append(Node(el.get("text", ""), el.get("content-desc", ""), el.get("class", ""),
                                  tuple(int(v) for v in m.groups())))
            return nodes
        time.sleep(1)
    raise RuntimeError(f"uiautomator dump failed: {out}")


def find(text: str, timeout: float = 20) -> Node | None:
    end = time.time() + timeout
    while True:
        for node in dump_ui():
            if text in node.text or text in node.desc:
                return node
        if time.time() >= end:
            return None
        time.sleep(1)


def screen_has(text: str, timeout: float = 0) -> bool:
    return find(text, timeout) is not None


def tap(node: Node) -> None:
    x, y = node.center
    sh(f"input tap {x} {y}")
    time.sleep(1)


def tap_text(text: str, timeout: float = 20) -> None:
    node = find(text, timeout)
    if node is None:
        raise RuntimeError(f"'{text}' not on screen")
    tap(node)


def launch_app() -> None:
    """Starts the app and waits for its home. No `am start -W`: right after `pm clear` the system
    can still be removing the old task and kill the new process, and -W then waits forever."""
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    for _ in range(2):
        sh(f"am start -n {ACTIVITY}")
        if find("항목 추가", 30) is not None:
            return
    raise RuntimeError("app home did not show")


def stop_app() -> None:
    sh(f"am force-stop {PKG}")


def add_item_and_record(name: str) -> None:
    """Home > [항목 추가] > name > [저장] > that row's [먹었어]."""
    tap_text("항목 추가")
    field_node = next((n for n in dump_ui() if n.cls.endswith("EditText")), None)
    if field_node is None:
        raise RuntimeError("name field not found")
    tap(field_node)
    sh(f"input text {name}")
    tap_text("저장")
    row = find(name, 20)
    if row is None:
        raise RuntimeError(f"row {name} did not show")
    # The row's button sits at the right edge on the same line as the name.
    buttons = [n for n in dump_ui() if n.text == "먹었어"]
    if not buttons:
        raise RuntimeError("no [먹었어] button")
    button = min(buttons, key=lambda b: abs(b.center[1] - row.center[1]))
    tap(button)
    time.sleep(2)


# ------------------------------------------------------------------------------------------------
# App files (root)


@dataclass
class AppState:
    exists: bool
    active_records: int = 0
    device_ids: list[str] = field(default_factory=list)
    settings: dict[str, str] = field(default_factory=dict)
    marker: str | None = None
    files: str = ""
    readable: bool = True


def read_state() -> AppState:
    files = sh(f"ls -l {DATA}/databases {DATA}/no_backup 2>&1", check=False)
    marker_out = sh(f"cat {DATA}/no_backup/install_marker 2>/dev/null", check=False).strip()
    marker = marker_out or None
    if f"{DB}" not in sh(f"ls {DATA}/databases 2>/dev/null", check=False):
        return AppState(exists=False, marker=marker, files=files)
    tmp = Path(tempfile.mkdtemp())
    try:
        for suffix in ("", "-wal", "-journal"):
            remote = f"{DATA}/databases/{DB}{suffix}"
            if sh(f"ls {remote} 2>/dev/null", check=False).strip():
                adb("pull", remote, str(tmp / f"{DB}{suffix}"))
        con = sqlite3.connect(tmp / DB)
        try:
            records = con.execute("SELECT COUNT(*) FROM dose_record WHERE active = 1").fetchone()[0]
            ids = [r[0] for r in con.execute("SELECT device_id FROM dose_record WHERE active = 1 ORDER BY recorded_at")]
            settings = dict(con.execute("SELECT key, value FROM setting"))
        except sqlite3.DatabaseError:
            # A broken or half-copied database is not "no records": callers see readable = False.
            return AppState(True, -1, [], {}, marker, files, readable=False)
        finally:
            con.close()
        return AppState(True, records, ids, settings, marker, files)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------------------------------------------------------
# Backup transport


def setup_transport() -> None:
    """Turns backup on and selects the test transport by the name `bmgr list transports` shows.
    - API 34: the transport registers a few seconds after backup is turned on; selecting it before
      that is refused ("Unknown transport") and backups go to another transport. So wait for it.
    - Android 8.x: the test transport is the framework's own, named
      `android/com.android.internal.backup.LocalTransport`. Selecting the Android 9+ name there is
      accepted but nothing is bound to it ("Unable to run backup")."""
    global transport
    sh("bmgr enable true")
    sh("setprop log.tag.BackupXmlParserLogging VERBOSE", check=False)
    end = time.time() + 120
    while True:
        listed = sh("bmgr list transports", check=False)
        names = [line.strip().lstrip("*").strip() for line in listed.splitlines() if "LocalTransport" in line]
        if names:
            transport = LOCAL_TRANSPORT if LOCAL_TRANSPORT in names else names[0]
            out = sh(f"bmgr transport {transport}", check=False)
            if "Selected transport" in out:
                return
        if time.time() > end:
            raise RuntimeError(f"could not select a LocalTransport: {listed.strip()}")
        time.sleep(3)


def control_backup() -> str:
    """Backs up a system package that always has data: shows the transport takes backups at all."""
    return sh(f"bmgr backupnow {CONTROL_PKG}", check=False, timeout=180)


def ensure_transport(r: "Report") -> None:
    """On some images the test transport is bound only at boot, so `bmgr enable true` alone leaves
    every backup refused ("Unable to run backup"; seen on API 27). Reboot once in that case."""
    out = control_backup()
    if CONTROL_OK not in out:
        r.note(f"대조 백업이 안 돼 다시 켬: {last_line(out)}")
        adb("reboot", check=False)
        time.sleep(5)
        adb("wait-for-device", timeout=300)
        wait_boot()
        become_root()
        setup_transport()
        out = control_backup()
    ok = CONTROL_OK in out
    r.check("대조: 시험 전송이 백업을 받음", f"{CONTROL_PKG} 백업 성공", last_line(out), ok)
    if not ok:
        raise RuntimeError("the test transport takes no backup; the cases below would prove nothing")


def last_line(out: str) -> str:
    lines = [line for line in out.strip().splitlines() if line.strip()]
    return lines[-1] if lines else ""


def package_result(out: str) -> str:
    """The `bmgr backupnow` line for the app ("Package ... with result: ...")."""
    return next((line.strip() for line in out.splitlines() if f"Package {PKG} with result" in line), "")


def backup_entries() -> list[str] | None:
    """File names inside the test transport's full backup of the app (a tar stream the transport
    keeps under its `_full` folder). None when the transport holds no full backup of the app."""
    found = sh(f"find /data /cache -path '*/_full/{PKG}' -type f 2>/dev/null", check=False).split()
    if not found:
        return None
    tmp = Path(tempfile.mkdtemp())
    try:
        local = tmp / "backup.tar"
        adb("pull", found[0], str(local))
        names: list[str] = []
        try:
            with tarfile.open(local, mode="r|") as tar:
                names = [member.name for member in tar]
        except tarfile.TarError:
            pass
        if not names:  # fall back to the header names in the raw stream
            raw = local.read_bytes()
            names = sorted({m.decode() for m in re.findall(rb"apps/" + re.escape(PKG.encode()) + rb"/[\x21-\x7e]+", raw)})
        return names
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def db_entries(names: list[str] | None) -> list[str]:
    return [n for n in names or [] if f"/{DB_DOMAIN}/" in n]


def only_db_files(names: list[str] | None) -> bool:
    """B-1: the backup holds the record DB and its companion files, nothing else of the app."""
    allowed = {f"apps/{PKG}/{DB_DOMAIN}/{DB}{suffix}" for suffix in ("", "-wal", "-journal")}
    data = [n for n in names or [] if not n.endswith(("/_manifest", "/_meta"))]
    return bool(data) and all(n in allowed for n in data)


def show_entries(names: list[str] | None) -> str:
    if names is None:
        return "전송에 백업 없음"
    short_names = [n.split(f"apps/{PKG}/", 1)[-1] for n in names]
    return "백업 안: " + (", ".join(short_names) if short_names else "(빈 백업)")


def set_transport_params(params: str) -> None:
    sh(f"settings put secure backup_local_transport_parameters '{params}'")


def wipe_backup() -> None:
    """`bmgr wipe` alone leaves the app's full backup in the test transport: LocalTransport
    .clearBackupData() lists `_full/<package>` as a folder, but a full backup is one tar file there,
    so nothing is deleted (AOSP android-11.0.0_r1) and the next install restores the old backup.
    Delete that file as root too, and check that nothing is left."""
    sh(f"bmgr wipe {transport} {PKG}", check=False)
    for path in sh(f"find /data /cache -path '*/_full/{PKG}' -type f 2>/dev/null", check=False).split():
        sh(f"rm -f {path}")
    if backup_entries() is not None:
        raise RuntimeError("the test transport still holds a backup of the app")


def backup_now() -> str:
    """Leaves the app and ends its process without `am force-stop`: a force-stopped app is in the
    stopped state, and full backup skips stopped apps ("Backup is not allowed", AOSP
    PerformFullTransportBackupTask / BackupEligibilityRules.appIsStopped)."""
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1)
    sh(f"am kill {PKG}", check=False)
    time.sleep(1)
    return sh(f"bmgr backupnow {PKG}", timeout=180)


def restore_now() -> str:
    """`bmgr restore <token> <package>`: restores the app from the test transport's restore set.
    Android 10 and later no longer take `bmgr restore <package>` alone."""
    listed = sh("bmgr list sets", check=False)
    tokens = re.findall(r"^\s*([0-9a-f]+) : ", listed, re.M)
    if not tokens:
        raise RuntimeError(f"no restore set: {listed.strip()}")
    token = "1" if "1" in tokens else tokens[0]  # LocalTransport's current set is token 1
    return sh(f"bmgr restore {token} {PKG}", check=False, timeout=180)  # ends "restoreFinished: 0", "done"


def uninstall() -> None:
    sh(f"pm uninstall --user 0 {PKG}", check=False)


def install(apk: Path) -> str:
    return adb("install", "-t", str(apk), timeout=180)


def reinstall(apk: Path) -> str:
    uninstall()
    time.sleep(2)
    return install(apk)


# ------------------------------------------------------------------------------------------------
# Report


class Report:
    def __init__(self, out: Path, api: int) -> None:
        self.out = out
        self.api = api
        self.rows: list[tuple[str, str, str, bool]] = []
        self.notes: list[str] = []
        self.shots = 0

    def check(self, case: str, expected: str, actual: str, ok: bool) -> bool:
        self.rows.append((case, expected, actual, ok))
        print(f"[{'PASS' if ok else 'FAIL'}] {case}: expected {expected}; got {actual}", flush=True)
        return ok

    def note(self, text: str) -> None:
        self.notes.append(text)
        print(f"[note] {text}", flush=True)

    def shot(self, name: str) -> None:
        self.shots += 1
        path = self.out / f"api{self.api}-{self.shots:02d}-{name}.png"
        with open(path, "wb") as fh:
            fh.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)

    @property
    def failed(self) -> bool:
        return any(not ok for *_, ok in self.rows)

    def write(self) -> None:
        lines = [f"### 에뮬레이터 확인 API {self.api}", "", "| 경우 | 기대 | 결과 | 판정 |", "| --- | --- | --- | --- |"]
        for case, expected, actual, ok in self.rows:
            cell = lambda s: s.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {cell(case)} | {cell(expected)} | {cell(actual)} | {'통과' if ok else '**실패**'} |")
        if self.notes:
            lines += ["", *[f"- {n}" for n in self.notes]]
        (self.out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def describe(state: AppState) -> str:
    if not state.exists:
        return f"DB 없음, 표식 {'있음' if state.marker else '없음'}"
    if not state.readable:
        return "DB가 있으나 읽지 못함"
    s = state.settings
    return (f"활성 판정 {state.active_records}, device_id {short(s.get('device_id'))}, "
            f"restored_from {short(s.get('restored_from_device_id'))}, "
            f"pending {s.get('restore_notice_pending', '-')}, 표식 {short(state.marker)}")


def short(value: str | None) -> str:
    return "-" if not value else value[-6:]


# ------------------------------------------------------------------------------------------------
# Cases


def fresh_install_with_record(apk: Path, r: Report, name: str = ITEM_A) -> AppState:
    uninstall()
    install(apk)
    launch_app()
    add_item_and_record(name)
    state = read_state()
    r.check(f"준비: 새 설치에서 {name} 기록", "활성 판정 1, 표식 = device_id",
            describe(state), state.active_records == 1 and state.marker == state.settings.get("device_id"))
    return state


def cases_api31(apk: Path, r: Report) -> None:
    # RR-26 / RR-69: screen lock (encryption) available -> restored. RR-30 notice.
    wipe_backup()
    set_transport_params("is_encrypted=true")
    before = fresh_install_with_record(apk, r)
    old_id = before.settings.get("device_id")
    out = backup_now()
    r.note(f"bmgr backupnow (is_encrypted=true): {package_result(out)} / {last_line(out)}")
    entries = backup_entries()
    r.check("B-1 백업에 든 파일 (암호화 가능)", "기록 DB와 짝 파일만",
            show_entries(entries), only_db_files(entries))
    reinstall(apk)
    restored = read_state()
    r.check("RR-26·RR-69 백업 → 지우고 다시 설치 (암호화 가능)", "기록 1건이 돌아옴, 표식 없음",
            describe(restored), restored.active_records == 1 and restored.marker is None)
    launch_app()
    after = read_state()
    notice = screen_has(NOTICE, 10)
    r.shot("restored-notice")
    r.check("RR-30·R-2 복원 뒤 첫 실행", "안내 보임, 새 device_id, restored_from = 옛 값, pending 1",
            f"안내 {'보임' if notice else '없음'}; {describe(after)}",
            notice and after.settings.get("device_id") not in (None, old_id)
            and after.settings.get("restored_from_device_id") == old_id
            and after.settings.get("restore_notice_pending") == "1"
            and after.marker == after.settings.get("device_id"))
    new_id = after.settings.get("device_id")

    # RR-32: recording before [확인했어요] works, with the new device id; the notice stays.
    add_item_and_record(ITEM_B)
    tapped = read_state()
    still = screen_has(NOTICE, 5)
    r.check("RR-32 확인 전에 기록", "기록됨, 새 행 device_id = 새 값, 안내 남음",
            f"활성 판정 {tapped.active_records}, 새 행 {short(tapped.device_ids[-1] if tapped.device_ids else None)}, 안내 {'남음' if still else '없음'}",
            tapped.active_records == 2 and tapped.device_ids[-1] == new_id and still)

    # RR-33 (app part): [확인했어요] clears the notice.
    tap_text("확인했어요")
    time.sleep(2)
    gone = not screen_has(NOTICE, 0)
    confirmed = read_state()
    r.shot("after-confirm")
    r.check("RR-33 [확인했어요] (알림 몫은 TASK-040)", "안내 사라짐, pending 0",
            f"안내 {'사라짐' if gone else '남음'}, pending {confirmed.settings.get('restore_notice_pending')}",
            gone and confirmed.settings.get("restore_notice_pending") == "0")

    # RR-31: update in place is not a restore.
    adb("install", "-r", "-t", str(apk), timeout=180)
    launch_app()
    updated = read_state()
    r.check("RR-31 같은 폰에서 업데이트", "안내 없음, device_id 그대로",
            f"안내 {'보임' if screen_has(NOTICE, 3) else '없음'}; {describe(updated)}",
            not screen_has(NOTICE, 0) and updated.settings.get("device_id") == new_id and updated.marker == new_id)

    # RR-71: restore the backup over the installed app (marker stays, ids differ).
    stop_app()
    out = restore_now()
    r.note(f"bmgr restore: {' / '.join(line.strip() for line in out.splitlines() if line.strip())}")
    before_start = read_state()
    r.note(f"RR-71 복원 직후 실행 전: {describe(before_start)} "
           "(표식이 없으면 시스템이 복원 전에 앱 데이터를 지운 것: R-1 표의 '표식 없음' 줄로 감)")
    launch_app()
    over = read_state()
    notice = screen_has(NOTICE, 10)
    r.check("RR-71 깔린 앱 위에 복원", "복원으로 처리, 안내 보임, restored_from = 백업의 device_id",
            f"안내 {'보임' if notice else '없음'}; {describe(over)}",
            notice and over.settings.get("restored_from_device_id") == old_id
            and over.settings.get("device_id") not in (None, old_id, new_id))

    # RR-34: clearing app data is a new install.
    sh(f"pm clear {PKG}")
    time.sleep(3)
    launch_app()
    cleared = read_state()
    r.check("RR-34 앱 데이터 삭제 뒤 실행", "새 설치, 안내 없음, 기록 0",
            f"안내 {'보임' if screen_has(NOTICE, 3) else '없음'}; {describe(cleared)}",
            not screen_has(NOTICE, 0) and cleared.active_records == 0
            and cleared.settings.get("restored_from_device_id") is None)

    # RR-68: no encryption capability (no screen lock) -> nothing comes back.
    wipe_backup()
    set_transport_params("is_encrypted=false")
    fresh_install_with_record(apk, r)
    out = backup_now()
    r.note(f"bmgr backupnow (is_encrypted=false): {last_line(out)}")
    entries = backup_entries()
    reinstall(apk)
    nolock = read_state()
    r.check("RR-68 암호화 없음(화면 잠금 없음)", "백업에 DB 없음, 다시 설치하면 DB 없음",
            f"{package_result(out) or last_line(out)}; {show_entries(entries)}; {describe(nolock)}",
            bool(package_result(out)) and not db_entries(entries) and not nolock.exists)
    launch_app()
    r.check("RR-68 그 뒤 첫 실행", "새 설치로 처리, 안내 없음",
            f"안내 {'보임' if screen_has(NOTICE, 3) else '없음'}", not screen_has(NOTICE, 0))

    # B-3: device-to-device transfer does not need a screen lock.
    wipe_backup()
    set_transport_params("is_device_transfer=true,is_encrypted=false")
    d2d_before = fresh_install_with_record(apk, r)
    out = backup_now()
    r.note(f"bmgr backupnow (is_device_transfer=true): {package_result(out)} / {last_line(out)}")
    entries = backup_entries()
    r.check("B-1 기기 간 이전에 든 파일", "기록 DB와 짝 파일만", show_entries(entries), only_db_files(entries))
    reinstall(apk)
    d2d = read_state()
    launch_app()
    r.check("B-3 기기 간 이전 (암호화 없음)", "기록 1건이 돌아오고 복원 안내",
            f"안내 {'보임' if screen_has(NOTICE, 10) else '없음'}; {describe(d2d)}",
            d2d.active_records == 1 and screen_has(NOTICE, 0)
            and d2d_before.settings.get("device_id") is not None)
    set_transport_params("")


def cases_no_backup(apk: Path, r: Report, case: str, expected_note: str, needs_parse_failure: bool) -> None:
    """Android 8.x (parse failure) and 9-11 (the test transport has no encryption flag): the backup
    runs for the app but holds no DB, and a reinstall brings no DB back."""
    wipe_backup()
    set_transport_params("")
    sh("logcat -c", check=False)
    fresh_install_with_record(apk, r)
    out = backup_now()
    result = package_result(out)
    log = adb("logcat", "-d", check=False, timeout=60)
    lines = [line for line in log.splitlines()
             if re.search(r"XmlPullParser|Aborting|BackupXmlParserLogging|FullBackup", line)]
    (r.out / f"api{r.api}-backup-rules.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    parse_failed = any("XmlPullParserException" in line or "Aborting full backup" in line for line in lines)
    entries = backup_entries()
    reinstall(apk)
    state = read_state()
    evidence = (f"{result or last_line(out)}; 규칙 파일 파싱 실패 기록 {'있음' if parse_failed else '없음'}; "
                f"{show_entries(entries)}; 다시 설치 뒤 {describe(state)}")
    ok = any(text in result for text in BACKUP_RAN) and not db_entries(entries) and not state.exists
    if needs_parse_failure:
        ok = ok and parse_failed
    r.check(case, expected_note, evidence, ok)
    launch_app()
    r.check(f"{case} 뒤 첫 실행", "새 설치로 처리, 안내 없음",
            f"안내 {'보임' if screen_has(NOTICE, 3) else '없음'}", not screen_has(NOTICE, 0))
    set_transport_params("")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    adb("wait-for-device")
    wait_boot()
    become_root()
    api = api_level()
    r = Report(args.out, api)
    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {setting} 0")
    sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
    sh("svc power stayon true", check=False)
    sh("settings put system screen_off_timeout 1800000", check=False)
    setup_transport()
    r.note(f"기기: API {api}, {sh('getprop ro.product.model').strip()}, 전송 {transport}")

    try:
        ensure_transport(r)
        if api >= 31:
            cases_api31(args.apk, r)
        elif api >= 28:
            cases_no_backup(args.apk, r, "Android 9~11, 시험 전송(암호화 플래그 없음)",
                            "앱 백업은 돌지만 DB가 들지 않음(0바이트면 시험 전송이 거절), 다시 설치하면 DB 없음 (이 전송은 clientSideEncryption을 켤 수 없음)",
                            needs_parse_failure=False)
        else:
            cases_no_backup(args.apk, r, "RR-70 Android 8.x",
                            "규칙 파일 파싱 실패로 앱 전체 백업이 빈 채로 끝남(0바이트면 시험 전송이 거절), 백업에 DB 없음, 다시 설치하면 DB 없음",
                            needs_parse_failure=True)
    except Exception as exc:  # report what happened, then fail
        r.check("스크립트", "끝까지 돎", f"{type(exc).__name__}: {exc}", False)
        try:
            r.shot("error")
            (args.out / f"api{api}-ui.xml").write_text(adb("exec-out", "cat", "/sdcard/ui.xml", check=False), encoding="utf-8")
        except Exception:
            pass
    finally:
        (args.out / f"api{api}-logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=60), encoding="utf-8")
        r.write()
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
