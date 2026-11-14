#!/usr/bin/env python3
"""개인정보 점검 (TASK-031, REQ-057·058·059·081·083·090): 릴리즈 변형의 병합 매니페스트·권한 출처·의존성 목록과 앱 소스의
로그 호출·PendingIntent.

쓰는 법: python3 check_privacy.py <자료 폴더> [저장소 루트, 기본 .]
자료 폴더는 CI가 서명하지 않은 릴리즈 APK를 만든 뒤 채움 (.github/workflows/ci.yml):
  merged-manifest-release.xml               릴리즈 변형의 병합 매니페스트
  manifest-merger-release-report.txt        병합 보고서: 권한·구성요소를 어느 매니페스트가 넣었는지
  dependencies-releaseRuntimeClasspath.txt  ./gradlew :androidApp:dependencies --configuration releaseRuntimeClasspath
표준 출력에 Markdown을 쓰고(워크플로에서 $GITHUB_STEP_SUMMARY로도 보냄), 같은 내용을 자료 폴더의 privacy-report.md에 씀.

실패(종료 코드 1):
- android.permission.INTERNET이 있음 (REQ-057)
- 분석·크래시 보고·광고·AI SDK로 보이는 의존성이 있음 (REQ-090·059·083, 아래 FORBIDDEN)
- 다른 앱이 부를 수 있는 구성요소(exported)가 androidApp/expected-exported.txt와 다름. 의도한 변경이면 같은 PR에서 그 파일을 고침
- 기록을 받는 수신기(PRIVATE)가 병합 매니페스트에서 exported="false"가 아님 (REQ-081)
- 앱 소스의 PendingIntent가 FLAG_IMMUTABLE 없이 만들어짐. 바꿀 수 있어야 하는 것은 MUTABLE_OK에 까닭과 함께 적고,
  인텐트가 이 앱의 클래스를 가리켜야 함 (REQ-081)
- 앱 소스(androidApp/src/main, shared/src/commonMain·androidMain)의 로그 호출이 이름을 담을 수 있는 값을 글에 넣음
  (REQ-058, 아래 NAME_WORDS). 이름이 아님을 확인한 값은 SAFE에 까닭과 함께 적음
표준 라이브러리만 씀.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

A = "{http://schemas.android.com/apk/res/android}"
INTERNET = "android.permission.INTERNET"
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")
# 기록·건너뛰기·미루기를 받는 수신기: 다른 앱이 보낼 수 없어야 함 (REQ-081, 검토 B12)
PRIVATE = {"receiver ${applicationId}.reminder.ReminderReceiver": "알림 버튼·밀기·복용 알람"}

# group:artifact 앞부분. 분석·크래시 보고·광고(REQ-090)와 AI(REQ-059) SDK
FORBIDDEN = {
    "com.google.firebase": "Firebase (분석·크래시·AI 등)",
    "com.google.android.gms:play-services-analytics": "Google Analytics",
    "com.google.android.gms:play-services-measurement": "Google Analytics for Firebase",
    "com.google.android.gms:play-services-ads": "Google 광고",
    "com.google.ads": "Google 광고",
    "com.crashlytics": "Crashlytics",
    "io.sentry": "Sentry",
    "com.bugsnag": "Bugsnag",
    "com.instabug": "Instabug",
    "com.microsoft.appcenter": "App Center",
    "com.newrelic": "New Relic",
    "com.datadoghq": "Datadog",
    "com.amplitude": "Amplitude",
    "com.mixpanel": "Mixpanel",
    "com.segment": "Segment",
    "com.appsflyer": "AppsFlyer",
    "com.adjust": "Adjust",
    "io.branch": "Branch",
    "com.flurry": "Flurry",
    "ly.count": "Countly",
    "com.facebook": "Facebook SDK",
    "com.applovin": "AppLovin",
    "com.unity3d.ads": "Unity Ads",
    "com.ironsource": "ironSource",
    "com.google.ai.client": "Gemini SDK",
    "com.google.mlkit": "ML Kit",
    "com.google.mediapipe": "MediaPipe",
    "org.tensorflow": "TensorFlow Lite",
    "com.microsoft.onnxruntime": "ONNX Runtime",
    "com.openai": "OpenAI",
    "com.aallam.openai": "OpenAI (Kotlin)",
    "com.anthropic": "Anthropic",
    "dev.langchain4j": "LangChain4j",
}
# 네트워크 클라이언트: 금지는 아니지만 INTERNET 없이 쓸 일이 없어 목록에 따로 보임
NETWORK = ("com.squareup.okhttp3", "com.squareup.retrofit2", "io.ktor:ktor-client", "com.android.volley")

SOURCES = ("androidApp/src/main", "shared/src/commonMain", "shared/src/androidMain")
LOG_CALL = re.compile(r"\b(Log\.(?:v|d|i|w|e|wtf)|println|print|printStackTrace)\s*\(")
# 이 낱말이 글 속 값의 이름에 들어 있으면 이름(항목·대상자)이나 기록을 담을 수 있다고 봄
NAME_WORDS = {"name", "names", "nickname", "displayname", "label", "labels", "title", "person", "people", "intake",
              "intakes", "item", "items", "content", "contents", "text", "body", "message", "record", "records",
              "result", "notice", "notices", "dose", "doses", "board", "cell", "cells"}
# 값의 끝이 이것이면 수·종류·번호만 남음
SAFE_ENDINGS = (".size", ".count()", "::class.simpleName", ".id", ".action")
# (파일 이름, 값) → 이름이 아닌 까닭. 코드가 바뀌어 값이 달라지면 다시 걸림
PENDING = re.compile(r"\bPendingIntent\.(getBroadcast|getActivity|getActivities|getService|getForegroundService)\s*\(")
# 파일 이름 → 바꿀 수 있는 PendingIntent가 필요한 까닭 (REQ-081). 인텐트는 이 앱의 클래스를 가리켜야 함
MUTABLE_OK = {
    "WidgetPinnedReceiver.kt": "`requestPinAppWidget`의 성공 콜백. 런처가 새 위젯 번호(`EXTRA_APPWIDGET_ID`)를 더해야 해서 "
                               "바꿀 수 있어야 함. 인텐트는 내보내지 않은 `WidgetPinnedReceiver`를 가리킴",
}
SAFE = {
    ("Reminders.kt", "$name"): "단계 이름(`step`의 인자: replan 등). 항목·대상자 이름이 아님",
    ("Reminders.kt", "$result"): "이 분기의 `RecordResult`는 `Locked`(값 없음)나 `Error`(메시지에 항목 번호만, "
                                 "`RecordDose`·`JudgmentSupport`)",
    ("Reminders.kt", "${result.message}"): "`SnoozeResult.Error`의 메시지는 `RecordResult.Error`의 것(항목 번호만). "
                                           "`lookUpWindow`은 Gone·Stale·Error로만 거절해(`JudgmentSupport.kt`) "
                                           "'unexpected lookup result' 분기에는 지금 오지 않음",
    ("DidITakeWidget.kt", "${result.message}"): "`RecordResult.Error`의 메시지(항목 번호만)",
}


def esc(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


# ------------------------------------------------------------------------------------------------
# Manifest


def merger_sources(report: Path) -> dict[str, list[str]]:
    """'kind#name' → where it came from: '[group:artifact:version]' or the app's own manifest path.
    Each element starts at a line with no indent ('uses-permission#…', 'application', 'manifest'); the lines under it
    say where it came from ('ADDED from …', 'MERGED from …') or are indented attribute lines."""
    sources: dict[str, list[str]] = {}
    current = None
    record = re.compile(r"^(ADDED|MERGED|INJECTED|IMPLIED|REJECTED|REMOVED) from (\[[^\]]+\]|\S+)")
    for line in report.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.startswith("--"):
            continue
        m = record.match(line)
        if not line[0].isspace() and not m:
            current = line.strip()
            sources.setdefault(current, [])
            continue
        if m and current is not None:
            where = m.group(2)
            if where.startswith("["):
                where = where[1:-1]
            else:
                where = re.sub(r"^.*?/(androidApp|shared)/", r"\1/", where)
                # ':줄:칸-칸' 또는 ':줄:칸-줄:칸'
                where = re.sub(r":\d+:\d+-\d+(:\d+)?$", "", where)
            entry = where if m.group(1) == "ADDED" else f"{where} ({m.group(1)})"
            if entry not in sources[current]:
                sources[current].append(entry)
    return sources


def manifest_facts(manifest: Path, sources: dict[str, list[str]]):
    root = ET.parse(manifest).getroot()
    package = root.get("package", "")
    perms = []
    for el in root.iter("uses-permission"):
        name = el.get(A + "name", "")
        perms.append((name, el.get(A + "maxSdkVersion", ""), sources.get(f"uses-permission#{name}", [])))
    for el in root.iter("permission"):
        name = el.get(A + "name", "")
        perms.append((f"(정의) {name}", el.get(A + "protectionLevel", ""), sources.get(f"permission#{name}", [])))
    exported, own = [], []
    for kind in COMPONENTS:
        for el in root.iter(kind):
            name = el.get(A + "name", "")
            flag = el.get(A + "exported")
            if name.startswith(package + "."):
                own.append((kind, name, flag or "적지 않음"))
            filters = el.findall("intent-filter")
            # targetSdk 31 이상은 intent-filter가 있으면 exported를 꼭 적어야 함. 적지 않은 것은 보이게 남김
            if flag != "true" and not (flag is None and filters):
                continue
            guard = el.get(A + "permission") or el.get(A + "readPermission") or ""
            actions = sorted({a.get(A + "name", "") for f in filters for a in f.iter("action")})
            exported.append((kind, name, flag or "적지 않음", guard, actions, sources.get(f"{kind}#{name}", [])))
    return package, perms, exported, own


# ------------------------------------------------------------------------------------------------
# Dependencies


def dependencies(listing: Path) -> list[str]:
    """Unique group:artifact:version of the resolved graph (after ' -> ' when Gradle picked another version)."""
    found = set()
    for line in listing.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.search(r"[+\\]--- (.+)$", line)
        if not m or m.group(1).startswith("project "):
            continue
        rest = re.sub(r" \([*cn]\)$", "", m.group(1).strip())
        coord, _, picked = rest.partition(" -> ")
        parts = coord.split(":")
        if len(parts) < 2:
            continue
        version = picked.strip() or (parts[2] if len(parts) > 2 else "")
        found.add(f"{parts[0]}:{parts[1]}:{version}".rstrip(":"))
    return sorted(found)


# ------------------------------------------------------------------------------------------------
# Log calls in the app's sources


def skip_string(src: str, i: int) -> int:
    """src[i] is a '"'; returns the index just after the string literal."""
    if src.startswith('"""', i):
        return src.index('"""', i + 3) + 3
    i += 1
    while src[i] != '"':
        if src[i] == "\\":
            i += 2
            continue
        if src.startswith("${", i):
            i = skip_template(src, i + 2)
            continue
        i += 1
    return i + 1


def skip_template(src: str, i: int) -> int:
    """src[i] is just after '${'; returns the index just after the matching '}'."""
    depth = 1
    while depth:
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        i += 1
    return i


def call_args(src: str, open_paren: int) -> str:
    i, depth = open_paren + 1, 1
    while depth:
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return src[open_paren + 1:i - 1]


def split_args(args: str) -> list[str]:
    """The call's arguments, split at the commas that are not inside a string, a template or brackets."""
    parts, depth, start, i = [], 0, 0, 0
    while i < len(args):
        c = args[i]
        if c == '"':
            i = skip_string(args, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(args[start:i].strip())
            start = i + 1
        i += 1
    if args[start:].strip():
        parts.append(args[start:].strip())
    return parts


def string_parts(args: str) -> tuple[list[str], list[str]]:
    """The string literals in `args` and the values put into them ('$x' and '${...}')."""
    literals, values = [], []
    i = 0
    while i < len(args):
        if args[i] != '"':
            i += 1
            continue
        end = skip_string(args, i)
        lit = args[i:end]
        literals.append(lit)
        j = 1
        while j < len(lit) - 1:
            if lit[j] == "\\":
                j += 2
                continue
            if lit.startswith("${", j):
                k = skip_template(lit, j + 2)
                values.append(lit[j:k])
                j = k
                continue
            m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)", lit[j:])
            if m:
                values.append(m.group(0))
                j += len(m.group(0))
                continue
            j += 1
        i = end
    return literals, values


def judge(file: str, value: str) -> tuple[bool, str]:
    """(may hold a name, why not)."""
    if (file, value) in SAFE:
        return False, SAFE[(file, value)]
    inner = value[2:-1] if value.startswith("${") else value[1:]
    if inner.endswith(SAFE_ENDINGS):
        return False, "수·종류·번호"
    # a class name or a fixed string inside the value is not a name
    code = re.sub(r"::class(\.java)?\.simpleName", "", inner)
    code = re.sub(r'"(?:[^"\\]|\\.)*"', "", code)
    words = {w.lower() for w in re.findall(r"[A-Za-z]+", re.sub(r"([a-z])([A-Z])", r"\1 \2", code))}
    hits = words & NAME_WORDS
    return (True, "") if hits else (False, "")


def log_calls(root: Path):
    rows, bad = [], []
    for base in SOURCES:
        for path in sorted((root / base).rglob("*.kt")):
            src = path.read_text(encoding="utf-8")
            for m in LOG_CALL.finditer(src):
                line = src.count("\n", 0, m.start()) + 1
                call = m.group(1)
                args = split_args(call_args(src, m.end() - 1))
                # Log.x(tag, message[, throwable]); println(message); e.printStackTrace() prints a stack trace only
                if call.startswith("Log."):
                    message, throwable = (args[1] if len(args) > 1 else ""), len(args) > 2
                elif call == "printStackTrace":
                    message, throwable = "", True
                else:
                    message, throwable = (args[0] if args else ""), False
                literals, values = string_parts(message)
                rel = path.relative_to(root).as_posix()
                notes, flagged = [], []
                for v in values:
                    risky, why = judge(path.name, v)
                    if risky:
                        flagged.append(v)
                    elif why:
                        notes.append(f"`{v}`: {why}")
                # the message is not a string literal (e.g. Log.i(TAG, text)): only SAFE lets it pass.
                # A literal joined with values ("a" + x): the values are judged like those inside the literal
                rest = message
                for lit in literals:
                    rest = rest.replace(lit, " ")
                rest = re.sub(r"\s+", " ", rest.replace("+", " ")).strip()
                if message and not literals:
                    if (path.name, message) in SAFE:
                        notes.append(f"`{message}`: {SAFE[(path.name, message)]}")
                    else:
                        flagged.append(message)
                elif re.search(r"[A-Za-z_]", rest):
                    risky, why = judge(path.name, "${" + rest + "}")
                    if risky:
                        flagged.append(rest)
                    elif why:
                        notes.append(f"`{rest}`: {why}")
                rows.append((rel, line, call, message, values, throwable, flagged, notes))
                if flagged:
                    bad.append(f"{rel}:{line} {flagged}")
    return rows, bad


def pending_intents(root: Path):
    rows, bad = [], []
    for base in SOURCES:
        for path in sorted((root / base).rglob("*.kt")):
            src = path.read_text(encoding="utf-8")
            for m in PENDING.finditer(src):
                line = src.count("\n", 0, m.start()) + 1
                args = split_args(call_args(src, m.end() - 1))
                intent = args[2] if len(args) > 2 else ""
                flags = args[3] if len(args) > 3 else ""
                rel = path.relative_to(root).as_posix()
                if "FLAG_IMMUTABLE" in flags:
                    verdict = "바꿀 수 없음"
                elif path.name in MUTABLE_OK and "::class.java" in intent:
                    verdict = "바꿀 수 있음: " + MUTABLE_OK[path.name]
                else:
                    verdict = "**FLAG_IMMUTABLE 없음**"
                    bad.append(f"{rel}:{line}")
                rows.append((rel, line, m.group(1), intent, flags, verdict))
    return rows, bad


# ------------------------------------------------------------------------------------------------


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    data = Path(sys.argv[1])
    root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(".")
    out: list[str] = []
    problems: list[str] = []

    sources = merger_sources(data / "manifest-merger-release-report.txt")
    package, perms, exported, own = manifest_facts(data / "merged-manifest-release.xml", sources)

    out.append("### 개인정보 점검 (TASK-031)\n")
    out.append(f"릴리즈 변형의 병합 매니페스트(패키지 `{package}`), 병합 보고서, `releaseRuntimeClasspath` 의존성, "
               f"앱 소스의 로그 호출과 PendingIntent를 봄.\n")

    out.append("#### 권한과 출처 (REQ-057, 검토 B8)\n")
    out.append("| 권한 | maxSdk·보호 수준 | 넣은 곳 |")
    out.append("| --- | --- | --- |")
    for name, extra, where in perms:
        out.append(f"| `{esc(name)}` | {esc(extra) or '-'} | {esc(', '.join(where)) or '(병합 보고서에 없음)'} |")
    if any(name == INTERNET for name, _, _ in perms):
        problems.append(f"{INTERNET}이 병합 매니페스트에 있음 (REQ-057)")
    out.append("")

    out.append("#### 다른 앱이 부를 수 있는 구성요소 (exported)\n")
    out.append("| 종류 | 이름 | exported | 보호 권한 | 받는 action | 넣은 곳 |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for kind, name, flag, guard, actions, where in exported:
        out.append(f"| {kind} | `{esc(name)}` | {flag} | {esc(guard) or '없음'} | "
                   f"{esc(', '.join(actions)) or '-'} | {esc(', '.join(where)) or '-'} |")
    expected_file = root / "androidApp/expected-exported.txt"
    actual = {f"{kind} {name.replace(package, '${applicationId}')} {guard or '-'}" for kind, name, _, guard, _, _ in exported}
    if expected_file.exists():
        expected = {line.strip() for line in expected_file.read_text(encoding="utf-8").splitlines()
                    if line.strip() and not line.startswith("#")}
        if actual != expected:
            for line in sorted(actual - expected):
                problems.append(f"exported 구성요소가 늘었음: {line}")
            for line in sorted(expected - actual):
                problems.append(f"exported 구성요소가 줄었음: {line}")
    else:
        problems.append(f"{expected_file.relative_to(root)}이 없음. 지금 목록:\n" + "\n".join(sorted(actual)))
    out.append("")

    out.append("#### 앱의 구성요소와 PendingIntent (REQ-081)\n")
    out.append("| 종류 | 이름 | exported | 받는 것 |")
    out.append("| --- | --- | --- | --- |")
    flags_of = {f"{kind} {name.replace(package, '${applicationId}')}": flag for kind, name, flag in own}
    for kind, name, flag in own:
        key = f"{kind} {name.replace(package, '${applicationId}')}"
        out.append(f"| {kind} | `{esc(name)}` | {flag} | {PRIVATE.get(key, '-')} |")
    for key, what in PRIVATE.items():
        if flags_of.get(key) != "false":
            problems.append(f"{key}({what})가 exported=\"false\"가 아님: {flags_of.get(key, '매니페스트에 없음')} (REQ-081)")
    out.append("")
    prow, pbad = pending_intents(root)
    out.append(f"앱 소스의 PendingIntent {len(prow)}개.\n")
    out.append("| 곳 | 만드는 함수 | 인텐트 | 플래그 | 판단 |")
    out.append("| --- | --- | --- | --- | --- |")
    for rel, line, fn, intent, flags, verdict in prow:
        out.append(f"| `{rel}:{line}` | `{fn}` | `{esc(intent)[:80]}` | `{esc(flags)[:80]}` | {esc(verdict)} |")
    for b in pbad:
        problems.append(f"FLAG_IMMUTABLE 없는 PendingIntent: {b} (REQ-081)")
    if not prow:
        problems.append("앱 소스에서 PendingIntent를 찾지 못함")
    out.append("")

    deps = dependencies(data / "dependencies-releaseRuntimeClasspath.txt")
    forbidden = [(d, why) for d in deps for prefix, why in FORBIDDEN.items() if d.startswith(prefix)]
    network = [d for d in deps if d.startswith(NETWORK)]
    out.append("#### 의존성 (REQ-059·083·090)\n")
    out.append(f"릴리즈 실행 클래스패스의 라이브러리 {len(deps)}개 (목록: `dependencies-releaseRuntimeClasspath.txt`). "
               f"분석·크래시 보고·광고·AI SDK: {', '.join(f'`{d}` ({w})' for d, w in forbidden) or '없음'}. "
               f"네트워크 클라이언트: {', '.join(f'`{d}`' for d in network) or '없음'}.\n")
    for d, why in forbidden:
        problems.append(f"금지한 종류의 의존성: {d} ({why})")
    if not deps:
        problems.append("의존성 목록을 읽지 못함")

    rows, bad = log_calls(root)
    out.append("#### 로그 호출 (REQ-058)\n")
    out.append(f"{', '.join(f'`{s}`' for s in SOURCES)}의 로그 호출 {len(rows)}개. 글에 넣는 값이 이름(항목·대상자)이나 "
               "기록을 담을 수 있으면 실패로 봄. 예외를 함께 남기는 호출은 '예외' 칸에 표시함.\n")
    out.append("| 곳 | 호출 | 글 | 넣는 값 | 예외 | 판단 |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for rel, line, call, text, values, throwable, flagged, notes in rows:
        verdict = ("**이름일 수 있음**: " + ", ".join(f"`{f}`" for f in flagged)) if flagged else "이름 없음"
        if notes:
            verdict += " (" + "; ".join(notes) + ")"
        out.append(f"| `{rel}:{line}` | `{call}` | `{esc(text)[:120]}` | "
                   f"{esc(', '.join(f'`{v}`' for v in values)) or '-'} | {'있음' if throwable else '-'} | {esc(verdict)} |")
    for b in bad:
        problems.append(f"로그에 이름이 들어갈 수 있음: {b}")
    out.append("")

    out.append("#### 결과\n")
    out.append("\n".join(f"- 실패: {p}" for p in problems) if problems else "- 모두 통과")
    text = "\n".join(out) + "\n"
    print(text)
    (data / "privacy-report.md").write_text(text, encoding="utf-8")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
