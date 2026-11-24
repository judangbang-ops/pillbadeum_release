"""TC-095 and TC-109 on the API 34 disposable emulator in regression_check.py."""

import re
import time
from pathlib import Path

from backup_restore_check import PKG, dump_ui, launch_app, sh
from explore import tap_desc, tap_exact
from release_check import save_ui
from reminder_check import PIN, debug, keyguard_showing, set_pin, unlock
from screens_check import to_home

PERSON = "가상 엄마"
NOTICE = "사용자가 적은 내용이며 의료기관의 기록이 아니에요. 적지 않은 종류는 보이지 않아요(없다는 뜻이 아니에요)"
MEDICATION = "가상정 10mg 1정 · 매일 아침"
ALLERGY = "가상 알레르기 (반응: 가상 반응)"
CARD_SHOW = "보여 주기"
LOCK_SCREEN_OVERRIDES = re.compile(
    r"\b(?:setShowWhenLocked|setTurnScreenOn|requestDismissKeyguard|"
    r"FLAG_SHOW_WHEN_LOCKED|FLAG_DISMISS_KEYGUARD|FLAG_TURN_SCREEN_ON)\b"
    r"|android:(?:showWhenLocked|inheritShowWhenLocked|turnScreenOn)\s*=\s*\"true\""
)


def check_card_show(report, steps):
    steps("TC-109 잠금 화면 코드 검사", "잠금 화면 위에 표시하거나 잠금을 푸는 호출과 속성 없음",
          check_lock_screen_overrides)
    steps("TC-095·109 보여 주기 화면과 기기 잠금", "62초 켜짐, 잠금 화면에 카드 없음, 잠금 해제와 뒤로 가기 뒤 30초 설정 복구",
          lambda: check_screen_and_lock(report))


def check_lock_screen_overrides():
    root = Path(__file__).resolve().parents[2]
    for scope in ("androidApp/src/main", "shared/src/androidMain", "shared/src/commonMain"):
        for path in (root / scope).rglob("*"):
            if path.suffix not in {".kt", ".java", ".xml"}:
                continue
            if LOCK_SCREEN_OVERRIDES.search(path.read_text(encoding="utf-8")):
                raise RuntimeError(f"lock screen override in {path.relative_to(root)}")
    return "앱 소스와 매니페스트에서 잠금 화면 표시 예외 없음"


def check_screen_and_lock(report):
    to_home()
    debug(f"{PKG}.debug.SEED_CARD_SHOW", "", "card seeded")
    # The fixture writes through the app's use cases. Relaunch so the record screen reads the new person.
    sh(f"am force-stop {PKG}")
    launch_app()
    tap_exact("건강 카드")
    tap_desc(f"{PERSON}의 건강 카드")
    tap_exact(CARD_SHOW)

    settings = [("global", "stay_on_while_plugged_in", "0"),
                ("system", "screen_off_timeout", "30000"),
                ("secure", "lock_screen_lock_after_timeout", "0")]
    original = [(group, key, sh(f"settings get {group} {key}").strip()) for group, key, _ in settings]
    pin_set = False
    try:
        for group, key, value in settings:
            sh(f"settings put {group} {key} {value}")
            if sh(f"settings get {group} {key}").strip() != value:
                raise RuntimeError(f"test setting not applied: {key}")
        set_pin()
        pin_set = True
        report.note("TC-095 화면 꺼짐 30000ms, 충전 중 화면 켜 두기 끔, 잠금 지연 0ms")
        assert_card_contents()
        report.shot("tc095-card-show")
        time.sleep(62)
        assert_power(report, "62초 뒤", "Awake")
        assert_card_contents()
        report.shot("tc095-card-show-62s")
        save_ui(report, "tc095-card-show-62s")

        sh("input keyevent KEYCODE_POWER")
        time.sleep(3)
        assert_power(report, "전원 버튼 1회", "Asleep")
        sh("input keyevent KEYCODE_POWER")
        time.sleep(3)
        assert_power(report, "전원 버튼 2회", "Awake")
        report.shot("tc109-locked")
        if keyguard_showing() is not True:
            raise RuntimeError("keyguard did not appear after the second power press")
        nodes = dump_ui()
        if not nodes or any(n.text in {PERSON, NOTICE, MEDICATION, ALLERGY} or n.desc == "닫기" for n in nodes):
            raise RuntimeError("the keyguard dump is empty or includes card content")
        save_ui(report, "tc109-locked")
        report.note("TC-109 실제 잠금 상태 확인. 화면 덤프에 카드 대상자, 고지, 약, 알레르기, 닫기 없음")

        if not unlock():
            raise RuntimeError("emulator did not unlock with the test PIN")
        assert_card_contents()
        report.shot("tc095-unlocked")
        sh("input keyevent KEYCODE_BACK")
        back_at = time.monotonic()
        if not any(n.text == CARD_SHOW for n in dump_ui()):
            raise RuntimeError("system Back did not return to the health card")
        report.shot("tc095-after-back")
        time.sleep(max(0, 34 - (time.monotonic() - back_at)))
        assert_power(report, "뒤로 가기 34초 뒤", "Asleep")
        return "62초 뒤 켜짐, 전원 버튼으로 잠금, 잠금 해제 뒤 같은 요약, 뒤로 가기 34초 뒤 꺼짐"
    finally:
        sh("input keyevent KEYCODE_WAKEUP", check=False)
        if pin_set:
            sh(f"locksettings clear --old {PIN}", check=False)
        sh("wm dismiss-keyguard", check=False)
        for group, key, value in original:
            if value == "null":
                sh(f"settings delete {group} {key}", check=False)
            else:
                sh(f"settings put {group} {key} {value}", check=False)


def assert_card_contents():
    nodes = dump_ui()
    ordered = (PERSON, NOTICE, "지금 먹는 약", MEDICATION, "알레르기", ALLERGY)
    visible = {node.text: node.bounds[1] for node in nodes}
    if not all(text in visible for text in ordered):
        raise RuntimeError("the show screen is missing a fixture row")
    date_top = next((y for text, y in visible.items() if text.startswith("마지막으로 고친 날 ")), None)
    tops = [visible[text] for text in ordered]
    if tops != sorted(set(tops)) or date_top is None or date_top <= tops[-1]:
        raise RuntimeError("the title, notice, categories or date are out of order")
    if set(visible).intersection({"질환", "수술·시술", "이식형 의료기기", "의료기관·담당 의사", "보호자 연락처"}):
        raise RuntimeError("an empty card category was rendered")


def assert_power(report, when, expected):
    state = re.search(r"(?m)^\s*mWakefulness=(\w+)", sh("dumpsys power"))
    actual = state.group(1) if state else "missing"
    report.note(f"TC-095 {when} mWakefulness={actual}")
    if actual != expected:
        raise RuntimeError(f"{when}: expected mWakefulness={expected}, found {actual}")
