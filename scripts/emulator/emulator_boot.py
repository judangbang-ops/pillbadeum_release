#!/usr/bin/env python3
"""Waits for the CI emulator after `adb reboot`, and turns it off and on when the reboot does not come back.

Shared by reminder_check.py (step 8, RR-18) and device_kit_check.py (the kit's `reboot`). On the GitHub runner the
reboot sometimes does not come back: the emulator left adb for good on API 34 and API 30 (reminder check, run
43414847440) and on API 26 (run 43374888132, attempt 2), and its boot never finished on API 36 (device kit, run
43391459701). The same images came back in about 30 seconds in the other runs. Then what the phone and the runner say
is kept in reboot-hang-*.txt, and the emulator is turned off and on with the same AVD, as a person holds the power key:
the data partition stays, the app sees a boot as after any reboot, and the check goes on.

Standard library only. Not product code.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from pathlib import Path

from backup_restore_check import adb, run, wait_boot

# As in .github/actions/android-emulator, which starts the emulator first (AVD "check" in $RUNNER_TEMP/avd)
EMULATOR_FLAGS = ("-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect",
                  "-camera-back", "none", "-camera-front", "none")


def visible() -> bool:
    """adb lists the emulator as a device (not missing, not offline)."""
    return re.search(r"^emulator-\d+\s+device\b", adb("devices", check=False, timeout=30), re.M) is not None


def boot_hang_diagnostics(out: Path) -> None:
    """What the runner and the phone say while the reboot hangs: whether the emulator process is still there, what adb
    lists, and, when adb still reaches the phone, its properties (init.svc.*, sys.boot_completed) and last logs."""
    host = run(["pgrep", "-af", "qemu-system"], check=False, timeout=30).strip() or "qemu-system 프로세스 없음"
    (out / "reboot-hang-host.txt").write_text(f"{host}\n\n{adb('devices', '-l', check=False, timeout=30)}", encoding="utf-8")
    if not visible():
        return
    for name, args, timeout in (
        ("props", ("shell", "getprop"), 60),
        ("crash", ("logcat", "-d", "-b", "crash"), 90),
        ("logcat", ("logcat", "-d", "-v", "time", "-t", "4000"), 120),
    ):
        try:
            text = adb(*args, check=False, timeout=timeout)
        except subprocess.TimeoutExpired:
            text = f"[{timeout}초 안에 답이 없음]"
        (out / f"reboot-hang-{name}.txt").write_text(text, encoding="utf-8")


def power_cycle() -> None:
    """Turns the CI emulator off and on with the same AVD; the data partition stays, as on a phone whose power key
    was held. Only on the CI runner (RUNNER_TEMP), where .github/actions/android-emulator made the AVD."""
    temp = os.environ.get("RUNNER_TEMP")
    if not temp:
        raise RuntimeError("emulator did not come back from the reboot (not on the CI runner: not restarting it)")
    adb("emu", "kill", check=False, timeout=60)
    end = time.time() + 120
    while time.time() < end and "emulator-" in adb("devices", check=False, timeout=30):
        time.sleep(3)
    if "emulator-" in adb("devices", check=False, timeout=30) or run(["pgrep", "-f", "qemu-system"], check=False, timeout=30).strip():
        subprocess.run(["pkill", "-9", "-f", "qemu-system"], check=False)
        time.sleep(5)
    avd = Path(temp) / "avd"
    for lock in (avd / "check.avd").glob("*.lock"):  # left behind when the emulator was killed
        if lock.is_dir():
            shutil.rmtree(lock, ignore_errors=True)
        else:
            lock.unlink(missing_ok=True)
    log = open(Path(temp) / "emulator.log", "ab")
    subprocess.Popen(["emulator", "-avd", "check", *EMULATOR_FLAGS], env={**os.environ, "ANDROID_AVD_HOME": str(avd)},
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    adb("wait-for-device", check=False, timeout=300)


def wait_reboot(out: Path) -> bool:
    """After `adb reboot`: adb sees the phone again within 5 minutes and its boot finishes within 5 more. Otherwise
    keeps reboot-hang-*.txt in `out`, turns the emulator off and on, and waits up to 10 minutes for that boot.
    True when the emulator had to be turned off and on."""
    try:
        adb("wait-for-device", check=False, timeout=300)
        wait_boot(300)
        return False
    except (subprocess.TimeoutExpired, RuntimeError):
        pass
    boot_hang_diagnostics(out)
    power_cycle()
    wait_boot(600)
    return True
