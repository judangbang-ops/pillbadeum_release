"""TC-098 on a disposable emulator, invoked by regression_check.py."""

import os
import re
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

from backup_restore_check import PKG, adb, sh, tap
from explore import exact, tap_exact
from screens_check import has_text, seek, to_home, wait_screen

ADDRESS = "elddlwkd@gmail.com"
FEEDBACK = "의견 보내기"
NO_MAIL = "메일 앱을 열 수 없어요. 이 주소로 보내 주세요"
FIXTURE = "org.example.task065.fixture"
COMPONENT = f"{FIXTURE}/.FeedbackReceiver"
TAG = "TASK065Fixture"


def build_fixture(out: Path) -> Path:
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    android_jar = sdk / "platforms/android-36/android.jar"
    tools = sdk / "build-tools/36.0.0"
    source = Path(__file__).with_name("feedback_fixture")
    unsigned, aligned, signed = (out / name for name in ("unsigned.apk", "aligned.apk", "feedback-test.apk"))
    key = out / "fixture.p12"

    def run(*args):
        subprocess.run(args, check=True)

    def tool(name: str, *args):
        if os.name == "nt":
            name += ".bat" if name in {"d8", "apksigner"} else ".exe"
        run(str(tools / name), *args)

    run("javac", "--release", "11", "-encoding", "UTF-8", "-classpath", android_jar,
        "-d", out, source / "FeedbackReceiver.java")
    tool("d8", "--min-api", "26", "--lib", android_jar, "--output", out,
         out / "org/example/task065/fixture/FeedbackReceiver.class")
    tool("aapt2", "link", "-I", android_jar, "--manifest", source / "AndroidManifest.xml", "-o", unsigned)
    with ZipFile(unsigned, "a") as archive:
        archive.write(out / "classes.dex", "classes.dex")
    tool("zipalign", "-f", "4", unsigned, aligned)
    run("keytool", "-genkeypair", "-keyalg", "RSA", "-keystore", key, "-alias", "fixture",
        "-storepass", "android", "-dname", "CN=AndroidTest", "-validity", "30")
    tool("apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--out", signed, aligned)
    return signed


def feedback_row():
    return seek(lambda node: node.text.startswith(FEEDBACK) or node.desc.startswith(FEEDBACK), FEEDBACK)


def check_feedback(report, steps):
    with tempfile.TemporaryDirectory(prefix="task065-") as temp:
        apk = build_fixture(Path(temp))
        handlers = sh(f"cmd package query-activities --components -a android.intent.action.SENDTO -d mailto:{ADDRESS}")
        disabled = []
        try:
            for component in re.findall(r"^[ \t]*([\w.]+/\S+)[ \t]*$", handlers, flags=re.MULTILINE):
                sh(f"pm disable {component}")
                disabled.append(component)
            steps("13a. TC-098 메일 앱 없음과 주소 복사", "하단 창과 주소, 복사한 주소가 클립보드에 있음",
                  lambda: no_mail_and_copy(report, apk))
            steps("13b. TC-098 메일 앱에 초안 열기", "받는 사람·제목·본문 세 값만, 두 번 눌러도 초안 하나",
                  lambda: single_draft(report))
        finally:
            adb("uninstall", FIXTURE, check=False)
            for component in disabled:
                sh(f"pm default-state {component}")


def no_mail_and_copy(report, apk: Path) -> str:
    to_home()
    tap_exact("설정")
    row = feedback_row()
    report.shot("tc098-settings-feedback")
    tap(row)
    if exact(NO_MAIL) is None or exact(ADDRESS) is None:
        raise RuntimeError("No-mail title or contact address was missing")
    report.shot("tc098-settings-no-mail")
    tap_exact("복사")
    adb("install", str(apk))
    sh(f"am start -W -n {COMPONENT} -a android.intent.action.MAIN")
    if exact(f"clipboard: {ADDRESS}") is None:
        raise RuntimeError("The focused test app did not read the contact address from the clipboard")
    sh("input keyevent KEYCODE_BACK")
    tap_exact("닫기")
    return "하단 창에서 주소를 복사했고, 별도 시험 앱이 클립보드에서 같은 주소를 읽음"


def single_draft(report) -> str:
    info = sh(f"dumpsys package {PKG}")
    name = re.search(r"\bversionName=(\S+)", info).group(1)
    code = re.search(r"\bversionCode=(\d+)", info).group(1)
    release = sh("getprop ro.build.version.release").strip()
    api = sh("getprop ro.build.version.sdk").strip()
    maker = sh("getprop ro.product.manufacturer").strip()
    model = sh("getprop ro.product.model").strip()
    expected = (f"mailto:{ADDRESS} [필받음] 의견 앱 버전: {name} ({code}) "
                f"Android 버전: {release} (API {api}) 기기 모델: {maker} {model}")

    def draft_logs():
        return re.findall(r"draft onlyApprovedFields=\w+", adb("logcat", "-d", "-s", f"{TAG}:I"))

    previous_count = len(draft_logs())
    node = feedback_row()
    x, y = node.center
    press = f"input tap {x} {y}"
    sh(f"{press}; {press}")

    def received(nodes):
        return any(" ".join(node.text.split()) == expected for node in nodes)

    if not received(wait_screen(received)):
        raise RuntimeError("Test mail app did not display the exact recipient, subject and three body fields")
    if draft_logs()[previous_count:] != ["draft onlyApprovedFields=true"]:
        raise RuntimeError("Expected one draft with exactly the approved fields and no attachment")
    report.shot("tc098-mail-draft")
    sh("input keyevent KEYCODE_BACK")
    if not has_text(wait_screen(lambda nodes: has_text(nodes, "설정")), "설정"):
        raise RuntimeError("Backing out of the draft did not return to settings")
    return "받는 사람·제목·본문 세 값이 정확하고 첨부 없음. 초안 하나가 열렸고 뒤로 가면 설정이 보임"
