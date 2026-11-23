import argparse
import hashlib
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.judangbang.pillbadeum"
ACTIVITY = APP + "/.debug.TechActivity"
RECIPIENT = "org.example.task056.recipient"
EXPORT = "cache/task056-export"


def run(*args, check=True, binary=False):
    return subprocess.run(args, check=check, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=not binary, timeout=70).stdout


def adb(*args, **kwargs):
    return run("adb", *args, **kwargs)


def shell(*args, **kwargs):
    return adb("shell", *args, **kwargs).strip()


class Probe:
    def __init__(self, api, apk, out):
        self.api, self.apk, self.out = api, apk, out
        self.out.mkdir(parents=True, exist_ok=True)
        self.failures = []

    def log(self, line):
        print(line, flush=True)
        with (self.out / "observations.txt").open("a", encoding="utf-8") as stream:
            print(line, file=stream)

    def expect(self, condition, detail):
        self.log(f"{'PASS' if condition else 'FAIL'} {detail}")
        if not condition:
            self.failures.append(detail)

    def screen(self, name):
        data = adb("exec-out", "screencap", "-p", binary=True)
        if not data.startswith(b"\x89PNG"):
            raise RuntimeError("screencap did not return PNG")
        (self.out / f"{name}.png").write_bytes(data)

    def tree(self):
        dump = shell("uiautomator", "dump", "/sdcard/task056.xml", check=False)
        if "dumped" not in dump:
            return []
        xml = adb("exec-out", "cat", "/sdcard/task056.xml")
        return list(ET.fromstring(xml).iter("node"))

    def labels(self, nodes):
        labels = list(dict.fromkeys(node.get("text") for node in nodes if node.get("text")))
        return [label if len(label) < 140 else f"[{len(label)} characters]" for label in labels]

    def tap_node(self, node, long=False):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        x, y = str((x1+x2)//2), str((y1+y2)//2)
        if long:
            shell("input", "swipe", x, y, x, y, "800")
        else:
            shell("input", "tap", x, y)

    def find(self, labels, swipes=0):
        for attempt in range(swipes + 1):
            nodes = self.tree()
            for node in nodes:
                if node.get("text", "").casefold() in labels or node.get("content-desc", "").casefold() in labels:
                    return node
            if attempt < swipes:
                self.swipe(up=True)
        return None

    def tap(self, *labels, swipes=0):
        node = self.find({label.casefold() for label in labels}, swipes=swipes)
        if node is None:
            raise RuntimeError(f"missing label {labels}: {self.labels(self.tree())}")
        self.tap_node(node)

    def swipe(self, up):
        bounds = shell("wm", "size").splitlines()[-1]
        width, height = (int(n) for n in re.findall(r"\d+", bounds))
        top, bottom = str(height // 4), str(height * 3 // 4)
        a, b = (bottom, top) if up else (top, bottom)
        shell("input", "swipe", str(width // 2), a, str(width // 2), b, "400")

    def start(self, mode, cold=False):
        if cold:
            shell("am", "force-stop", APP)
        text = shell("am", "start", "-W", "-n", ACTIVITY, "--es", "mode", mode)
        if "Error" in text:
            raise RuntimeError(text)

    def tagged(self):
        return shell("logcat", "-d", "-s", "TASK056:I", "*:S")

    def wait_log(self, fragment, start=0):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            logs = self.tagged()[start:]
            if fragment in logs:
                return True
            time.sleep(.4)
        return False

    def clipboard(self):
        self.start("copy", cold=True)
        self.screen("clipboard-before")
        self.tap("민감한 내용 복사")
        time.sleep(.55)
        self.screen("clipboard-sensitive")
        sensitive = str(self.api >= 33).lower()
        self.expect(self.wait_log(f"CLIP match=true sensitive={sensitive}"), "(3) clipboard round trip")
        if self.api >= 33:
            time.sleep(6)
        guidance = ("이제 긴급 정보에 붙여 넣어 주세요" if self.api >= 33 else
                    "복사했어요. 긴급 정보에 붙여 넣어 주세요\n이 휴대폰에서는 복사한 글이 클립보드 미리보기에 보일 수 있어요")
        self.expect(self.find({guidance}) is not None, "(3) app guidance visible")
        if self.api >= 33:
            self.tap("민감 표시 없이 복사")
            time.sleep(.55)
            self.screen("clipboard-public-control")

    def export(self):
        samples = [f"{font}-{short}.{suffix}" for font in ("gothic", "system")
                   for short, suffix in (("one", "pdf"), ("two", "pdf"), ("one", "png"), ("long", "png"))]
        for scale in ("1.0", "2.0"):
            shell("settings", "put", "system", "font_scale", scale)
            time.sleep(1.5)
            start = len(self.tagged())
            self.start("export", cold=True)
            self.expect(self.wait_log(f"EXPORT complete fontScale={scale}", start), f"(1) font scale={scale}")
            folder = self.out / f"font-{scale}"
            folder.mkdir()
            for filename in samples:
                data = adb("exec-out", "run-as", APP, "cat", f"{EXPORT}/{filename}", binary=True)
                (folder / filename).write_bytes(data)
        shell("settings", "put", "system", "font_scale", "1.0")
        for font in ("gothic", "system"):
            for item, pages in (("one", 1), ("two", 2)):
                name = f"{font}-{item}"
                for scale in ("1.0", "2.0"):
                    path = self.out / f"font-{scale}" / f"{name}.pdf"
                    info = run("pdfinfo", str(path))
                    self.expect(bool(re.search(rf"^Pages:\s+{pages}$", info, re.M)
                                     and re.search(r"^Page size:\s+595 x 842 pts", info, re.M)),
                                f"(1) {name} {scale}: {pages} A4 page(s)")
                    run("pdftoppm", "-scale-to", "960", "-png", str(path), str(path.with_suffix("")))
                    extracted = run("pdftotext", "-layout", str(path), "-").split("\f")[:pages]
                    self.log(f"(1) {name} {scale} text extraction: "
                             f"name on {sum('가상 엄마' in page for page in extracted)}/{pages}, "
                             f"number on {sum(f'{i}/{pages}쪽' in page for i, page in enumerate(extracted, 1))}/{pages}")
                for page in range(1, pages + 1):
                    self.compare(f"{name}-{page}.png")
            for item in ("one", "long"):
                filename = f"{font}-{item}.png"
                png = (self.out / "font-1.0" / filename).read_bytes()
                w, h = struct.unpack(">II", png[16:24])
                self.expect(png.startswith(b"\x89PNG") and w == 1190 and
                            (h == 1684 if item == "one" else h > 1684), f"(1) {filename} {w}x{h}")
                self.compare(filename)

    def compare(self, filename):
        digest = [(hashlib.sha256((self.out / f"font-{scale}" / filename).read_bytes()).hexdigest())
                  for scale in ("1.0", "2.0")]
        self.expect(digest[0] == digest[1], f"(1) {filename} same rendering 100%/200% SHA-256={digest[0]}")

    def share(self):
        for extension in ("pdf", "png"):
            self.start("export", cold=True)
            self.tap(f"Android 공유 창 {extension.upper()}")
            time.sleep(1)
            self.screen(f"chooser-{extension}")
            self.tap("TASK056 Receiver", swipes=2)
            self.expect(self.wait_log(f"RECIPIENT share {extension} read=true writeDenied=true content=true filename=true"),
                        f"(2) {extension} recipient has read-only content URI")
            self.screen(f"recipient-{extension}")
        listing = shell("run-as", APP, "ls", "-1", EXPORT)
        self.expect("pillbadeum-card-20261123.pdf" in listing and "pillbadeum-card-20261123.png" in listing,
                    "(2) both shared files exist before process restart")
        start = len(self.tagged())
        self.start("home", cold=True)
        self.expect(self.wait_log("COLD_START removed=10", start), "(2) removed 10 files on cold start")
        self.expect(shell("run-as", APP, "ls", "-1", EXPORT) == "", "(2) export cache empty after cold start")

    def mail(self):
        candidates = shell("cmd", "package", "query-activities", "--components", "-a", "android.intent.action.SENDTO",
                           "-d", "mailto:test@example.invalid", check=False)
        for line in candidates.splitlines():
            match = re.fullmatch(r"([\w.]+)/[^\s]+", line.strip())
            if match:
                package = match.group(1)
                self.log(f"(7) disabling preinstalled mail handler on CI emulator: {package}")
                shell("pm", "disable-user", "--user", "0", package)
        self.start("mail", cold=True)
        self.tap("메일 앱 열기")
        self.expect(self.wait_log("MAIL no handler"), "(7) no mail handler fallback")
        self.screen("mail-no-handler")
        start = len(self.tagged())
        self.tap("주소 복사")
        self.expect(self.wait_log("CLIP match=true", start), "(7) fallback address copy")
        adb("install", str(self.apk / "recipient.apk"))
        start = len(self.tagged())
        self.start("mail", cold=True)
        self.tap("메일 앱 열기")
        self.expect(self.wait_log("RECIPIENT mail to=true subject=true body=true", start),
                    "(7) no queries; SENDTO launches receiver")
        self.log("(7) no <queries>: " + next((line.strip() for line in self.tagged()[start:].splitlines()
                                              if "MAIL resolved=" in line), "no resolve log"))
        self.screen("mail-receiver")

    def mail_queries(self):
        adb("uninstall", APP)
        adb("install", str(self.apk / "probe-queries.apk"))
        start = len(self.tagged())
        self.start("mail", cold=True)
        self.tap("메일 앱 열기")
        self.expect(self.wait_log("RECIPIENT mail to=true subject=true body=true", start), "(7) with queries; SENDTO receiver")
        self.expect(self.wait_log("MAIL resolved=ComponentInfo{" + RECIPIENT, start),
                    "(7) with queries; resolveActivity sees the receiver")

    def emergency(self):
        self.log("(5) installed emergency packages: " + ", ".join(
            line for line in shell("pm", "list", "packages").splitlines()
            if "emergency" in line or "safetyhub" in line))
        for label in ("긴급 설정", "의료 정보 편집", "의료 정보 항목 편집", "설정"):
            self.start("settings", cold=True)
            self.tap(label)
            self.log("(5) " + next((line.split("SETTINGS ", 1)[1] for line in reversed(self.tagged().splitlines())
                                    if "SETTINGS " in line), "no settings result"))
        section = self.find({"safety & emergency", "safety and emergency", "emergency information"}, swipes=4)
        if section is None:
            self.log(f"(5) Settings route unavailable in this image: {self.labels(self.tree())}")
            self.screen("emergency-settings")
            return
        self.log(f"(5) Settings > {section.get('text')}")
        self.tap_node(section)
        self.screen("emergency-settings")
        self.log(f"(5) section labels: {self.labels(self.tree())}")
        item = self.find({"medical information", "medical info", "emergency information", "open emergency information",
                          "open personal safety"})
        if item is not None:
            self.log(f"(5) section > {item.get('text')}")
            self.tap_node(item)
        self.screen("emergency-information")
        self.log(f"(5) information labels: {self.labels(self.tree())}")
        field = self.find({"allergies", "medications", "medical conditions", "add information"}, swipes=2)
        if field is not None and field.get("text").casefold() == "add information":
            self.tap_node(field)
            field = self.find({"allergies", "medications", "medical conditions"}, swipes=1)
        if field is None:
            self.log("(5) no editable medical field; length not measured")
            return
        self.log(f"(5) field: {field.get('text')}")
        self.tap_node(field)
        node = next((n for n in self.tree() if n.get("class") == "android.widget.EditText"), None)
        if node is None:
            self.log("(5) field did not expose an EditText")
            return
        self.screen("emergency-edit")
        for length in (197, 1024):
            self.tap_node(node)
            shell("input", "keyevent", "KEYCODE_MOVE_END")
            shell("input", "keyevent", *["KEYCODE_DEL"] * min(197, length)) if length != 197 else None
            shell("input", "text", "A" * (length - 1) + "Z")
            node = next((n for n in self.tree() if n.get("class") == "android.widget.EditText"), None)
            if node is None:
                self.log(f"(5) {length} ASCII characters, field could not be read after input")
                break
            entered = node.get("text", "")
            self.log(f"(5) typed {length}, read {len(entered)}, final sentinel Z={entered.endswith('Z')}")
            self.screen(f"emergency-length-{length}")
        shell("input", "keyevent", "KEYCODE_BACK")

    def show(self):
        shell("svc", "power", "stayon", "false")
        shell("settings", "put", "system", "screen_off_timeout", "30000")
        shell("settings", "put", "secure", "lock_screen_lock_after_timeout", "0")
        pin = "1357"
        response = shell("locksettings", "set-pin", pin)
        self.log("(4) test PIN configured=" + str("set to" in response.lower()))
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.start("home", cold=True)
        self.tap("보여 주기")
        self.screen("show-card")
        time.sleep(62)
        self.expect(self.power("Awake"), "(4) awake after 62 seconds")
        self.screen("show-card-62s")
        shell("input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(2.5)
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(1)
        locked = shell("dumpsys", "window", "policy")
        self.screen("show-locked")
        self.expect(bool(re.search(r"\b(?:showing|mShowingLockscreen)=true", locked)), "(4) keyguard is showing")
        self.expect(self.find({"가상 엄마", "닫기"}) is None, "(4) card node absent on keyguard")
        self.swipe(up=True)
        time.sleep(.8)
        shell("input", "text", pin)
        shell("input", "keyevent", "KEYCODE_ENTER")
        if self.find({"닫기"}) is None:
            shell("locksettings", "clear", "--old", pin, check=False)
            shell("wm", "dismiss-keyguard", check=False)
            self.failures.append("(4) PIN unlock did not restore the card")
            self.log("FAIL (4) PIN unlock did not restore the card")
            self.start("home", cold=True)
            self.tap("보여 주기")
        shell("input", "keyevent", "KEYCODE_BACK")
        self.expect(self.find({"보여 주기"}) is not None, "(4) back returns to card entry")
        self.screen("show-after-back")
        time.sleep(34)
        self.expect(self.power("Asleep"), "(4) timeout restored after back")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("locksettings", "clear", "--old", pin, check=False)
        shell("wm", "dismiss-keyguard", check=False)

    def power(self, expected):
        lines = [line.strip() for line in shell("dumpsys", "power").splitlines()
                 if "mWakefulness=" in line or "Display Power: state=" in line or "mStayOn=" in line]
        self.log("(4) " + " / ".join(lines))
        return f"mWakefulness={expected}" in lines

    def execute(self):
        self.log(f"API={shell('getprop', 'ro.build.version.sdk')} device={shell('getprop', 'ro.product.model')} "
                 f"locale={shell('getprop', 'persist.sys.locale')} fingerprint={shell('getprop', 'ro.build.fingerprint')}")
        self.expect(shell("getprop", "ro.build.version.sdk") == str(self.api), "requested API")
        self.log("(6) documentary evidence only")
        shell("settings", "put", "system", "screen_off_timeout", "1800000")
        shell("wm", "dismiss-keyguard", check=False)
        adb("install", str(self.apk / "probe-no-queries.apk"))
        for variant in ("no-queries", "queries"):
            path = self.apk / f"permissions-{variant}.txt"
            if path.exists():
                self.log(f"(8) permissions {variant} " + path.read_text().strip().replace("\n", " | "))
        for action in (self.clipboard, self.mail, self.export, self.share, self.emergency, self.mail_queries, self.show):
            try:
                action()
            except (RuntimeError, subprocess.SubprocessError, ValueError) as err:
                self.log(f"FAIL {action.__name__}: {err}")
                self.failures.append(action.__name__)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    probe = Probe(args.api, args.apk, args.out)
    try:
        probe.execute()
    finally:
        (args.out / "task056-logcat.txt").write_text(probe.tagged() + "\n", encoding="utf-8")
    return 1 if probe.failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
