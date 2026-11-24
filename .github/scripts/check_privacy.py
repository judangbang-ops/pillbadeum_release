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
- 앱 소스(androidApp/src/main, shared/src/commonMain·androidMain)의 로그 호출이 이름, 기록, 건강 카드·약 줄,
  클립보드 값이나 내보내기 파일을 담을 수 있음 (REQ-058·106)
- 위젯·알림 데이터 경로에 카드·약 줄을 읽는 참조가 있음 (REQ-106), 카드 FileProvider가 비공개가 아님
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
LOG_LEVEL = r"v|d|i|w|e|wtf|log|println|tag"
PRINT_CALL = r"println|print|printStackTrace"
# TC-108: Android renderers and the common code that supplies their data. Keep this list in step with the call paths.
RENDER_SOURCES = (
    "androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget",
    "androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder",
    "shared/src/commonMain/kotlin/com/judangbang/pillbadeum/status/StatusBoard.kt",
    "shared/src/commonMain/kotlin/com/judangbang/pillbadeum/usecase/Reminders.kt",
)
CARD_READ = re.compile(
    r"\b(?:loadHealthCard|buildEmergencyText|HealthCard(?:Entity|Summary|Dao)|LoadHealthCard|"
    r"(?:healthCard|condition|procedureHistory|allergy|implantedDevice|careContact|intakeMedication|medication)Dao|"
    r"(?:Intake)?Medication(?:Entity|Dao|Fields|Draft)|CardEntryFields|medications|qtyPerTake|"
    r"(?:FROM|JOIN)\s+[`\"]?(?:health_card|condition|procedure_history|allergy|implanted_device|care_contact|"
    r"intake_medication|medication))\b",
    re.IGNORECASE,
)
PENDING = re.compile(r"\bPendingIntent\.(getBroadcast|getActivity|getActivities|getService|getForegroundService)\s*\(")
# 파일 이름 → 바꿀 수 있는 PendingIntent가 필요한 까닭 (REQ-081). 인텐트는 이 앱의 클래스를 가리켜야 함
MUTABLE_OK = {
    "WidgetPinnedReceiver.kt": "`requestPinAppWidget`의 성공 콜백. 런처가 새 위젯 번호(`EXTRA_APPWIDGET_ID`)를 더해야 해서 "
                               "바꿀 수 있어야 함. 인텐트는 내보내지 않은 `WidgetPinnedReceiver`를 가리킴",
}
# (파일 이름, 보간식) → 검토한 값과 근거. 새 동적 값은 명시적으로 검토해야 함.
SAFE = {
    ("WidgetPinnedReceiver.kt", "$appWidgetId"): "런처가 넣은 위젯 번호",
    ("WidgetPinnedReceiver.kt", '${choice?.let { it::class.simpleName } ?: "no choice"}'): "위젯 선택 타입",
    ("DidITakeWidget.kt", "${oldWidgetIds.joinToString()}"): "이전 위젯 번호 배열",
    ("DidITakeWidget.kt", "${newWidgetIds.joinToString()}"): "복원한 위젯 번호 배열",
    ("WidgetPickActivity.kt", "$appWidgetId"): "런처가 넣은 위젯 번호",
    ("WidgetChoices.kt", "$filled"): "초기화한 위젯 수",
    ("WidgetChoices.kt", "${state::class.simpleName}"): "설치 상태 타입",
    ("DayBoundaryAlarm.kt", "$at"): "날짜 경계 알람 시각",
    ("DayBoundaryAlarm.kt", "$action"): "방송 동작 식별자",
    ("AppContainer.kt", "$restored"): "복원 여부",
    ("AndroidPhone.kt", "${intent.action}"): "Android 동작 식별자",
    ("Reminders.kt", "${notices.size}"): "숨긴 알림 수",
    ("Reminders.kt", "${contents.size}"): "게시한 알림 수",
    ("Reminders.kt", "${(wanted - listed).size}"): "시스템에 게시되지 않은 알림 수",
    ("Reminders.kt", "$LIST_WAIT"): "게시 여부 확인 대기 시간",
    ("ReminderAlarm.kt", "$delay"): "알람 재시도 대기 시간",
    ("ReminderAlarm.kt", "${attempt + 1}"): "알람 재시도 횟수",
    ("ReminderAlarm.kt", "$at"): "알람 시각",
    ("ReminderAlarm.kt", "$WINDOW"): "정확하지 않은 알람의 시간 창",
    ("ReminderReceiver.kt", "$action"): "방송 동작 식별자",
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
    quote = '"""' if src.startswith('"""', i) else '"'
    i += len(quote)
    while not src.startswith(quote, i):
        if quote == '"' and src[i] == "\\":
            i += 2
            continue
        if src.startswith("${", i):
            i = skip_template(src, i + 2)
            continue
        i += 1
    i += len(quote)
    if quote == '"""':
        while i < len(src) and src[i] == '"':
            i += 1
    return i


def skip_comment_or_char(src: str, i: int) -> int:
    if src.startswith("//", i):
        end = src.find("\n", i)
        return len(src) if end == -1 else end
    if src.startswith("/*", i):
        depth, j = 1, i + 2
        while depth:
            if src.startswith("/*", j):
                depth += 1
                j += 2
            elif src.startswith("*/", j):
                depth -= 1
                j += 2
            else:
                j += 1
        return j
    if src[i] == "'":
        char = re.match(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'", src[i:])
        if char:
            return i + char.end()
    return i


def skip_template(src: str, i: int) -> int:
    """src[i] is just after '${'; returns the index just after the matching '}'."""
    depth = 1
    while depth:
        end = skip_comment_or_char(src, i)
        if end != i:
            i = end
            continue
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


def mask_kotlin(src: str, strings: bool = False) -> str:
    """Blank comments and character literals, optionally also strings, preserving offsets."""
    chars = list(src)
    i = 0
    while i < len(src):
        if src[i] == '"':
            end = skip_string(src, i)
            if not strings:
                i = end
                continue
        else:
            end = skip_comment_or_char(src, i)
        if end == i:
            i += 1
            continue
        chars[i:end] = ["\n" if c == "\n" else " " for c in src[i:end]]
        i = end
    return "".join(chars)


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
        width = 3 if lit.startswith('"""') else 1
        j = width
        while j < len(lit) - width:
            if width == 1 and lit[j] == "\\":
                j += 2
                continue
            if lit.startswith("${", j):
                k = skip_template(lit, j + 2)
                values.append(lit[j:k])
                j = k
                continue
            m = re.match(r"\$(?:[^\W\d]\w*|`[^`\n]+`)", lit[j:])
            if m:
                values.append(m.group(0))
                j += len(m.group(0))
                continue
            j += 1
        i = end
    return literals, values


def log_arguments(src: str):
    """Direct Kotlin/Android logger calls, including import aliases and Timber tag chains."""
    src = mask_kotlin(src)
    code = mask_kotlin(src, strings=True)
    symbols = {"Log": "Log", "Timber": "Timber", **{name: name for name in PRINT_CALL.split("|")}}
    imports = re.findall(
        rf"(?m)^\s*import\s+(?:android\.util\.|timber\.log\.|kotlin\.io\.)"
        rf"((?:Log|Timber)(?:\.(?:Companion|Forest))?(?:\.(?:{LOG_LEVEL}))?|{PRINT_CALL})"
        r"(?:[ \t]+as[ \t]+(\w+))?[ \t]*$", code
    )
    for original, alias in imports:
        original = original.replace(".Forest", "").replace(".Companion", "")
        symbols[alias or original.rsplit(".", 1)[-1]] = original
    names = "|".join(map(re.escape, symbols))
    calls = re.compile(rf"\b({names})(?:\s*\.\s*(?:Forest|Companion))?(?:\s*\.\s*({LOG_LEVEL}))?\s*\(")
    literal_tag = re.findall(r'(?m)\bconst val TAG\s*(?::\s*String\s*)?=\s*("[^"\n$]*")\s*$', src)
    fixed_tag = len(literal_tag) == 1 and len(re.findall(r"\b(?:val|var)\s+TAG\b", code)) == 1
    fixed_tag = fixed_tag and not re.search(r"[(,]\s*TAG\s*:", code)
    for match in calls.finditer(code):
        if re.search(r"\bfun\s*$", code[code.rfind("\n", 0, match.start()) + 1:match.start()]):
            continue
        original, method = symbols[match[1]], match[2]
        if original in {"Log", "Timber"} and not method:
            continue
        call = f"{original}.{method}" if method else original
        open_paren = match.end() - 1
        raw_args = call_args(src, open_paren)
        args = [re.sub(r"^\w+\s*=(?!=)\s*", "", arg) for arg in split_args(raw_args)]
        line = code.count("\n", 0, match.start()) + 1
        yield line, call, args, fixed_tag
        if call == "Timber.tag":
            end = open_paren + len(raw_args) + 2
            chained = re.match(rf"\s*\.\s*({LOG_LEVEL})\s*\(", code[end:])
            if chained:
                args = call_args(src, end + chained.end() - 1)
                yield line, f"Timber.{chained[1]}", split_args(args), fixed_tag


def checked_log_value(file: str, arg: str, notes: list[str]) -> bool:
    literals, values = string_parts(arg)
    rest = arg
    for literal in literals:
        rest = rest.replace(literal, " ", 1)
    if not literals or rest.strip(" +\t\n"):
        return False
    safe = True
    for value in values:
        why = SAFE.get((file, value))
        if not why and re.fullmatch(r"\$\{\w+\.javaClass\.simpleName\}", value):
            why = "예외 타입만 기록"
        if why:
            notes.append(f"`{value}`: {why}")
        else:
            safe = False
    return safe


def log_calls(root: Path):
    rows, bad = [], []
    for base in SOURCES:
        for path in sorted((root / base).rglob("*.kt")):
            rel = path.relative_to(root).as_posix()
            for line, call, args, fixed_tag in log_arguments(path.read_text(encoding="utf-8")):
                notes, flagged = [], []
                if call == "Log.println" or call == "Timber.log":
                    # Priority is an integer, not a printed value.
                    args = args[1:]
                if call.startswith("Log.") and args and args[0] == "TAG" and fixed_tag:
                    args = args[1:]
                throwable = call == "printStackTrace" or (call.startswith("Log.") and len(args) > 2)
                for arg in args:
                    if arg != "null" and not checked_log_value(path.name, arg, notes):
                        flagged.append("검토하지 않은 동적 인자")
                if call == "printStackTrace":
                    flagged.append("예외 출력")
                message = ", ".join(args)
                values = string_parts(message)[1]
                rows.append((rel, line, call, message, values, throwable, flagged, notes))
                if flagged:
                    bad.append(f"{rel}:{line} ({call})")
    return rows, bad


def renderer_card_reads(root: Path) -> tuple[int, list[str]]:
    paths = []
    for scope in RENDER_SOURCES:
        path = root / scope
        paths.extend(sorted(path.rglob("*.kt")) if path.is_dir() else [path])
    bad = []
    for path in paths:
        src = path.read_text(encoding="utf-8")
        for match in CARD_READ.finditer(src):
            line = src.count("\n", 0, match.start()) + 1
            bad.append(f"{path.relative_to(root).as_posix()}:{line} ({match.group()})")
    return len(paths), bad


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

    out.append("### 개인정보 점검 (TASK-031·066)\n")
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
    card_provider = [flag for kind, name, flag in own
                     if kind == "provider" and name == f"{package}.export.CardExportProvider"]
    if card_provider != ["false"]:
        problems.append("카드 FileProvider의 exported=false를 확인하지 못함")
    else:
        out.append("\n카드 FileProvider: `exported=false`.")
    if any(name.endswith(".debug.ReminderSeedReceiver") for _, name, _ in own):
        problems.append("릴리즈 매니페스트에 디버그 시험 수신기가 있음")
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
    out.append("#### 로그 호출 (REQ-058·106)\n")
    out.append(f"{', '.join(f'`{s}`' for s in SOURCES)}의 로그 호출 {len(rows)}개. 로그 인자에 이름, 기록, 카드·약 줄, "
               "클립보드 값이나 내보내기 파일을 담을 수 있으면 실패로 판정함.\n")
    out.append("| 곳 | 호출 | 글 | 넣는 값 | 예외 | 판단 |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for rel, line, call, text, values, throwable, flagged, notes in rows:
        verdict = "**개인정보 가능성**" if flagged else "점검 통과"
        if notes:
            verdict += " (" + "; ".join(notes) + ")"
        message = "-" if flagged else f"`{esc(text)[:120]}`"
        value_list = "-" if flagged else esc(', '.join(f'`{v}`' for v in values)) or '-'
        out.append(f"| `{rel}:{line}` | `{call}` | {message} | {value_list} | "
                   f"{'있음' if throwable else '-'} | {esc(verdict)} |")
    for b in bad:
        problems.append(f"로그에 개인정보가 들어갈 수 있음: {b}")
    out.append("")

    count, bad = renderer_card_reads(root)
    out.append("#### 위젯·알림의 카드·약 줄 읽기 (REQ-106)\n")
    out.append(f"Android 위젯·알림 소스와 공통 데이터 경로 {count}개 파일에서 카드·약 줄 DAO, 타입, 조회 참조를 검사함.")
    problems.extend(f"위젯·알림 경로에 카드·약 줄 조회 가능성: {finding}" for finding in bad)
    out.append("")

    out.append("#### 결과\n")
    out.append("\n".join(f"- 실패: {p}" for p in problems) if problems else "- 모두 통과")
    text = "\n".join(out) + "\n"
    print(text)
    (data / "privacy-report.md").write_text(text, encoding="utf-8")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
