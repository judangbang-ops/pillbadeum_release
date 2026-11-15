#!/usr/bin/env python3
"""스토어 등록정보 글과 앱 문구를 검사함 (TASK-037, TC-061·TC-076, REQ-048·REQ-064·REQ-087).

쓰는 법: python3 .github/scripts/check_wording.py [저장소 루트]
- 등록정보 글: docs/release/listing/<언어>/의 title.txt·short_description.txt·full_description.txt.
  Play Console에 그대로 붙여 넣는 글이고, 파일 끝 줄바꿈 하나는 세지 않음
  - 길이: 앱 이름 30자, 간단한 설명 80자, 자세한 설명 4,000자 (Play 도움말 answer/9859152)
  - 자세한 설명에 건강 콘텐츠 정책(answer/9878878)의 고지 둘 (REQ-064, TC-076). 한 줄에 '의료기기가 아니'와
    진단·처치·치료·예방이 다 있고, 한 줄에 '의학적'과 '의료 전문가'(또는 '의료인')와 '상의'(또는 '상담')가 있어야 함
  - 메타데이터 정책(answer/9898842)과 그래픽 도움말(answer/9866151)에서 옮긴 금지: 이모지·이모티콘, 같은 기호의 반복,
    강조용 대문자 낱말, 순위·성과, 가격·프로모션, Play 프로그램 문구
  - 간단한 설명은 더해서: 줄바꿈 없음, 한글·영문·숫자·공백과 . , ( ) ' - 밖의 문자 없음, 설치를 재촉하는 문구 없음
  - `{…}` 자리 표시는 실패로 치지 않고 '남은 자리'로 알림
- 앱 문구: 제품 코드(shared의 commonMain·androidMain, androidApp의 main)의 Kotlin 문자열 가운데 한글이 든 것과
  res/values*/strings.xml. 주석은 보지 않음
- 모든 글(등록정보와 앱 문구)에 의학 판단 문구가 없어야 함 (REQ-087, TC-061): 복용량·용량, mg 같은 양, 두 배,
  한꺼번에, 빠뜨린 약을 어떻게 먹으라는 말, 상호작용·병용, 효과·효능·부작용.
  알림이 몇 분 안에 온다는 약속도 없어야 함 (REQ-048)
- 사람이 봐야 하는 것(사용자 후기 인용, 사실과 다른 기능 설명)은 이 검사가 잡지 못함
- 표준 출력에 Markdown을 씀. 파이썬 표준 라이브러리만 씀. 문제가 하나라도 있으면 1로 끝남
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LISTING = Path("docs/release/listing")
FIELDS = (
    ("title.txt", "앱 이름", 30),
    ("short_description.txt", "간단한 설명", 80),
    ("full_description.txt", "자세한 설명", 4000),
)
PRODUCT = ("shared/src/commonMain", "shared/src/androidMain", "androidApp/src/main")
HANGUL = re.compile(r"[가-힣]")
PLACEHOLDER = re.compile(r"\{[^{}\n]+\}")

# REQ-087: 복용량 권고, 빠뜨렸을 때의 처치, 상호작용 판정을 하지 않음. 효과를 내세우는 말도 같은 줄기로 막음
MEDICAL = (
    (re.compile(r"복용량|투여량|용량"), "복용량"),
    (re.compile(r"\d+(?:\.\d+)?\s*(?:mg|㎎|밀리그램|mcg|㎍|ml|mL|㎖|IU)(?![A-Za-z])"), "약의 양"),
    (re.compile(r"(?:두|2)\s?배"), "두 배"),
    (re.compile(r"한꺼번에"), "한꺼번에"),
    (
        re.compile(r"(?:빠뜨|잊|놓[치친쳤]|거르|거른|걸렀)[^.\n]{0,15}(?:드세요|드시|드셔|먹으세요|먹어도|먹으면|복용하세요|복용해도)"),
        "빠뜨린 약의 처치",
    ),
    (re.compile(r"상호\s?작용|병용|(?:같이|함께)\s?(?:드시|먹으|복용하)면\s?안"), "상호작용"),
    (re.compile(r"효과|효능|부작용"), "효과·부작용"),
)
# REQ-048: 알림 지연을 몇 분 안으로 약속하지 않음. '30분 뒤'처럼 사람이 고르는 미루기는 걸리지 않음
DELAY = (re.compile(r"\d+\s?분\s?(?:안에|안으로|이내|내에|내로|만에)"), "알림 시간 약속")

# Play 메타데이터 정책(answer/9898842)과 그래픽 도움말(answer/9866151)
STORE = (
    (
        re.compile(r"1\s?위|넘버\s?원|No\.?\s?1(?!\d)|#\s?1(?!\d)|최고|베스트|\bbest\b|인기|올해의|수상|\d+\s?만\s?(?:명|다운로드)", re.I),
        "순위·성과",
    ),
    (re.compile(r"무료|공짜|할인|특가|이벤트|쿠폰|캐시\s?백|\d+\s?%|\bfree\b|\boff\b", re.I), "가격·프로모션"),
    (re.compile(r"에디터|editor'?s choice|신규 출시|\bnew\b", re.I), "Play 프로그램"),
)
CALL_TO_ACTION = re.compile(
    r"지금\s?(?:다운로드|설치|받)|다운로드\s?하세요|설치\s?하세요|받아\s?보세요|\b(?:download|install|try|play) now\b", re.I
)
EMOJI = re.compile(
    "[\U0001F000-\U0001FAFF☀-➿⬀-⯿⌀-⏿️‍〰〽㊗㊙]"
    r"|[:;]-?[()DPp](?![A-Za-z])|\^\^|ㅎㅎ|ㅋㅋ|ㅠㅠ|ㅜㅜ|T_T"
)
REPEATED = re.compile(r"([^\w\s])\1")
CAPS = re.compile(r"\b[A-Z]{2,}\b")
CAPS_OK = {"PIN"}
SHORT_ALLOWED = re.compile(r"[가-힣A-Za-z0-9 .,()'\-]")
DISCLAIMER_DEVICE = ("진단", "처치", "치료", "예방")


def body(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    return text[:-1] if text.endswith("\n") else text


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def kotlin_strings(text: str) -> list[tuple[int, str]]:
    """Korean string literals of one Kotlin file with their line, comments left out."""
    blank = lambda m: re.sub(r"[^\n]", " ", m.group(0))  # noqa: E731 - keeps offsets and line numbers
    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    text = re.sub(r"(?m)^\s*//[^\n]*", blank, text)
    found = []
    for m in re.finditer(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', text, re.S):
        literal = m.group(1) if m.group(1) is not None else m.group(2)
        if HANGUL.search(literal):
            found.append((line_of(text, m.start()), literal))
    return found


def xml_strings(path: Path) -> list[tuple[int, str]]:
    found = []
    raw = path.read_text(encoding="utf-8")
    for node in ET.fromstring(raw).iter():
        if node.tag in ("string", "item") and node.text and HANGUL.search(node.text):
            name = node.get("name", "")
            at = raw.find(f'name="{name}"') if name else -1
            found.append((line_of(raw, at) if at >= 0 else 0, node.text))
    return found


def medical(text: str) -> list[str]:
    hits = [f"{label} `{m.group(0)}`" for pattern, label in MEDICAL for m in pattern.finditer(text)]
    hits += [f"{DELAY[1]} `{m.group(0)}`" for m in DELAY[0].finditer(text)]
    return hits


def check_field(name: str, text: str) -> list[str]:
    hits = [f"{label} `{m.group(0)}`" for pattern, label in STORE for m in pattern.finditer(text)]
    hits += [f"이모지·이모티콘 `{m.group(0)}`" for m in EMOJI.finditer(text)]
    hits += [f"같은 기호 반복 `{m.group(0)}`" for m in REPEATED.finditer(text)]
    hits += [f"대문자 낱말 `{m.group(0)}`" for m in CAPS.finditer(text) if m.group(0) not in CAPS_OK]
    if name == "short_description.txt":
        if "\n" in text:
            hits.append("줄바꿈")
        others = sorted({c for c in PLACEHOLDER.sub("", text) if c != "\n" and not SHORT_ALLOWED.fullmatch(c)})
        hits += [f"쓸 수 없는 문자 `{c}` (U+{ord(c):04X})" for c in others]
        hits += [f"설치 재촉 `{m.group(0)}`" for m in CALL_TO_ACTION.finditer(text)]
    if name == "full_description.txt":
        lines = text.split("\n")
        device = any(
            ("의료기기가 아니" in line or "의료 기기가 아니" in line) and all(word in line for word in DISCLAIMER_DEVICE)
            for line in lines
        )
        advice = any(
            "의학적" in line and ("의료 전문가" in line or "의료인" in line) and ("상의" in line or "상담" in line)
            for line in lines
        )
        if not device:
            hits.append("고지 없음: '의료기기가 아니며 … 진단·처치·치료·예방하지 않음'이 한 줄에 없음 (REQ-064)")
        if not advice:
            hits.append("고지 없음: '의학적 … 의료 전문가(의료인)와 상의'가 한 줄에 없음 (REQ-064)")
    return hits


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    problems: list[str] = []
    print("### 스토어 등록정보와 앱 문구 (TC-061·TC-076, REQ-048·REQ-064·REQ-087)")
    print()

    languages = sorted(p for p in (root / LISTING).iterdir() if p.is_dir()) if (root / LISTING).is_dir() else []
    if not languages:
        problems.append(f"`{LISTING}/<언어>/`가 없음")
    for folder in languages:
        rel = folder.relative_to(root)
        print(f"#### `{rel}`")
        print()
        print("| 칸 | 글자 수 / 한도 | 남은 자리 | 결과 |")
        print("| --- | --- | --- | --- |")
        for name, label, limit in FIELDS:
            path = folder / name
            if not path.is_file():
                problems.append(f"`{rel}/{name}` 없음")
                print(f"| {label} | 없음 | | 실패 |")
                continue
            text = body(path)
            hits = check_field(name, text) + medical(text)
            if len(text) > limit:
                hits.insert(0, f"{len(text)}자로 한도 {limit}자를 넘음")
            places = ", ".join(sorted({f"`{m.group(0)}`" for m in PLACEHOLDER.finditer(text)})) or "없음"
            problems += [f"`{rel}/{name}`: {hit}" for hit in hits]
            print(f"| {label} | {len(text)} / {limit} | {places} | {'통과' if not hits else '실패'} |")
        print()

    checked = 0
    files = 0
    for base in PRODUCT:
        for path in sorted((root / base).rglob("*")):
            if path.suffix == ".kt":
                literals = kotlin_strings(path.read_text(encoding="utf-8"))
            elif path.suffix == ".xml" and path.name == "strings.xml" and path.parent.name.startswith("values"):
                literals = xml_strings(path)
            else:
                continue
            if not literals:
                continue
            files += 1
            checked += len(literals)
            rel = path.relative_to(root)
            for line, literal in literals:
                problems += [f"`{rel}:{line}`: {hit}" for hit in medical(literal)]
    print(f"- 앱 문구: 한글이 든 문자열 {checked}개 ({files}개 파일)")
    print("- 사람이 볼 것: 사용자 후기 인용이 없는지, 기능 설명이 앱과 맞는지 (이 검사로는 못 봄)")
    print()
    for item in problems:
        print(f"- 문제: {item}")
    print("문제 없음" if not problems else f"문제 {len(problems)}개")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
