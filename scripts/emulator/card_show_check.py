"""TC-095 and TC-109 on the API 34 disposable emulator in regression_check.py."""

import re
import time
from datetime import datetime
from pathlib import Path

from backup_restore_check import ACTIVITY, DATA, PKG, adb, dump_ui, launch_app, sh, tap
from card_export_check import cancel_share, send_file
from explore import ZONE, exact, phone_now, set_clock, tap_desc, tap_exact
from release_check import save_ui
from reminder_check import PIN, debug, doses, keyguard_showing, set_pin, unlock, wait_posted
from screens2_check import list_view, widget_page
from screens_check import seek, to_home
from widget_size_check import launcher_package

PERSON = "가상 엄마"
NOTICE = "사용자가 적은 내용이며 의료기관의 기록이 아니에요. 적지 않은 종류는 보이지 않아요(없다는 뜻이 아니에요)"
MEDICATION = "가상정 10mg 1정 · 매일 아침"
ALLERGY = "가상 알레르기 (반응: 가상 반응)"
CARD_SHOW = "보여 주기"
PRIVACY_PERSON = "가상 보호대상"
PRIVACY_ITEM = "PrivacyItem"
PRIVATE_VALUES = ("가상점검정 10mg", "27정", "가상점검알레르기", "가상점검반응", "가상점검질환",
                  "가상점검시술", "가상점검기기", "가상점검병원", "가상점검의사", "가상점검보호자", "010-0000-0708")
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


def check_card_privacy(report, steps):
    steps("TC-108 SQLite 보안 삭제", "앱 Room의 SQLite 쓰기 연결에서 secure_delete=1", lambda: check_secure_delete(report))
    steps("TC-108 위젯·알림", "가상 항목을 렌더하고 카드와 약 줄은 표시하지 않음", lambda: check_privacy_render(report))
    steps("TC-108 최근 앱", "기록 화면은 실제 스냅샷, SCR-011~015는 테마 스냅샷", lambda: check_card_recents(report))
    steps("TC-108 실패 경로 로그", "저장·내보내기 실패를 유도하고 복사·공유를 거쳐 logcat을 확인함",
          lambda: check_card_logs(report))


def privacy_debug(action, expected, extra=""):
    return debug(f"{PKG}.debug.{action}", extra, expected)


def check_secure_delete(report):
    result = privacy_debug("CARD_SECURE_DELETE", "secure_delete=")
    report.note(f"TC-108 앱 Room 쓰기 연결 `PRAGMA main.secure_delete`: `{result}`")
    if not result.startswith("secure_delete=1 "):
        raise RuntimeError(f"Room writer returned {result}")
    return result


def assert_no_private_values(text, surface, values=PRIVATE_VALUES):
    text = text.replace("\u2060", "")
    hits = [i + 1 for i, value in enumerate(values) if value in text]
    if hits:
        raise RuntimeError(f"{surface} contains private fixture values at indexes {hits}")


def check_privacy_render(report):
    today = datetime.fromtimestamp(phone_now(), ZONE)
    set_clock(today.replace(hour=7, minute=57, second=0, microsecond=0))
    privacy_debug("SEED_CARD_PRIVACY", "privacy card seeded")
    sh("input keyevent KEYCODE_HOME")
    nodes = widget_page(lambda nodes: list_view(nodes) is not None, 30)
    for _ in range(5):
        assert_no_private_values("\n".join(f"{n.text}\n{n.desc}" for n in nodes), "widget")
        if any(PRIVACY_ITEM in n.text or PRIVACY_ITEM in n.desc for n in nodes):
            break
        widget_list = list_view(nodes)
        if widget_list is None:
            raise RuntimeError("the widget list is missing")
        x, _ = widget_list.center
        _, top, _, bottom = widget_list.bounds
        sh(f"input swipe {x} {bottom - 12} {x} {top + 12} 400")
        nodes = dump_ui()
    else:
        raise RuntimeError("the synthetic item is missing from the widget")
    save_ui(report, "tc108-widget")

    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    set_clock(today.replace(hour=8, minute=1, second=0, microsecond=0))
    sh(f"am broadcast -n {PKG}/.reminder.ReminderReceiver -a {PKG}.action.REMINDER_ALARM")
    posted = wait_posted(lambda items: any(PRIVACY_ITEM in n.title for n in doses(items)), 30)
    if not any(PRIVACY_ITEM in n.title for n in doses(posted)):
        raise RuntimeError("the synthetic item reminder was not posted")
    assert_no_private_values("\n".join(f"{n.title}\n{n.text}\n{n.actions}" for n in posted), "notifications")
    report.note("TC-108 위젯과 알림에 시험 항목 이름이 표시됨. 카드·약 줄 시험 값이 각각 0건")
    return "홈 위젯 텍스트·접근성 설명과 게시한 알림 제목·본문·동작에서 카드·약 줄 없음"


def open_privacy_card():
    to_home()
    tap_exact("건강 카드")
    label = f"{PRIVACY_PERSON}의 건강 카드"
    tap(seek(lambda n: n.desc == label, "synthetic person's card"))
    if exact(CARD_SHOW) is None:
        raise RuntimeError("the synthetic card did not open")


def open_condition_editor():
    tap(seek(lambda n: n.desc == "질환, 가상점검질환", "synthetic condition row"))
    if exact("저장") is None:
        raise RuntimeError("the condition editor did not open")


def app_snapshots():
    dump = sh("dumpsys window -a")
    candidates = re.findall(r"(?m)^\s*topApp=[^\n]*" + re.escape(PKG) + r"[^\n]*\n\s*snapshot=(.*)$", dump)
    snapshots = {}
    for snapshot in candidates:
        snap_id = re.search(r"\bmId=(\d+)", snapshot)
        real = re.search(r"\bmIsRealSnapshot=(true|false)", snapshot)
        if snap_id and real:
            snapshots[snap_id[1]] = real[1]
    return snapshots


def snapshot_in_recents(report, launcher, label, expected_real):
    previous = app_snapshots()
    sh("input keyevent KEYCODE_HOME")
    nodes = dump_ui()
    focused = sh("dumpsys window | grep mCurrentFocus")
    if not nodes or launcher not in focused:
        raise RuntimeError(f"{label}: Home did not replace the app window")
    assert_no_private_values("\n".join(f"{n.text}\n{n.desc}" for n in nodes), f"Home ({label})")
    sh("input keyevent KEYCODE_APP_SWITCH")
    end = time.monotonic() + 15
    while time.monotonic() < end:
        for snap_id, real in app_snapshots().items():
            if snap_id in previous:
                continue
            report.note(f"TC-108 {label} 최신 스냅샷 `mId={snap_id}`, `mIsRealSnapshot={real}`")
            report.shot(f"tc108-recents-{label}")
            if (real == "true") != expected_real:
                raise RuntimeError(f"unexpected recents snapshot for {label}: {real}")
            sh(f"am start -n {ACTIVITY}")
            return
        time.sleep(0.5)
    raise RuntimeError(f"no fresh app snapshot for {label}")


def check_card_recents(report):
    launcher = launcher_package()
    to_home()
    snapshot_in_recents(report, launcher, "record-control", expected_real=True)
    routes = (("011", None, CARD_SHOW), ("012", open_condition_editor, "저장"),
              ("013", lambda: tap_exact(CARD_SHOW), NOTICE), ("014", lambda: tap_exact("응급 대비 문장"), "모두 복사"),
              ("015", lambda: tap_exact("내보내기"), "보내기"))
    for label, open_route, expected in routes:
        open_privacy_card()
        if open_route is not None:
            open_route()
        if exact(expected) is None:
            raise RuntimeError(f"missing UI on SCR-{label}")
        snapshot_in_recents(report, launcher, label, expected_real=False)
    return "홈에서 카드 시험 값 0건. 기록 화면은 실제 스냅샷, SCR-011~015 다섯 화면 모두 테마 스냅샷"


def check_card_logs(report):
    since = f"{phone_now()}.000"
    open_privacy_card()
    open_condition_editor()
    privacy_debug("CARD_SAVE_FAULT", "save fault enabled=true", "--ez enabled true")
    try:
        tap_exact("저장")
        if exact("저장하지 못했어요. 다시 눌러 주세요") is None:
            raise RuntimeError("the forced SQLite write failure did not reach the editor")
        report.shot("tc108-save-failure")
    finally:
        privacy_debug("CARD_SAVE_FAULT", "save fault enabled=false")

    open_privacy_card()
    tap_exact("응급 대비 문장")
    tap_exact("모두 복사")
    if exact("이제 긴급 정보에 붙여 넣어 주세요.") is None:
        raise RuntimeError("the clipboard success indication is missing")
    open_privacy_card()
    tap_exact("내보내기")
    export_cache = f"{DATA}/cache/card-exports"
    sh(f"run-as {PKG} touch {export_cache}")
    try:
        tap_exact("보내기")
        if exact("파일을 만들지 못했어요. 휴대폰 저장 공간을 확인해 주세요") is None:
            raise RuntimeError("the blocked export cache did not cause a write failure")
        report.shot("tc108-export-failure")
    finally:
        sh(f"run-as {PKG} rm -f {export_cache}")
    send_file()
    cancel_share()
    logs = adb("logcat", "-b", "all", "-d", "-T", since, "-v", "brief")
    if not logs.strip():
        raise RuntimeError("logcat has no lines in the exercised interval")
    assert_no_private_values(logs, "logcat", (*PRIVATE_VALUES, PRIVACY_PERSON))
    if re.search(r"card-exports[/\\]|content://[^\s]+\.card-export/|pillbadeum-card-\d{8}\.(pdf|png)", logs):
        raise RuntimeError("logcat contains an export file path, URI or name")
    return "저장 실패, 복사 성공, 내보내기 쓰기 실패·공유 창 성공 구간에서 시험 값·파일 경로·URI·이름 0건"


def check_lock_screen_overrides():
    root = Path(__file__).resolve().parents[2]
    # The emulator runs the debug APK, so its source set counts too.
    for scope in ("androidApp/src/main", "androidApp/src/debug", "shared/src/androidMain", "shared/src/commonMain"):
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
        # Cleared in finally even when set_pin raises after the PIN was set (clearing an unset PIN does nothing).
        pin_set = True
        set_pin()
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
