#!/usr/bin/env python3
"""APK의 권한 목록(aapt2 dump permissions 출력)을 저장소의 기준 목록과 비교함.

쓰는 법: python3 check_permissions.py <aapt2 출력 파일> <기준 목록 파일> [제목에 붙일 이름, 예: 릴리즈 APK]
- 표준 출력에 Markdown을 씀 (워크플로에서 $GITHUB_STEP_SUMMARY로도 보냄)
- android.permission.INTERNET이 있으면 기준 목록과 상관없이 실패함 (REQ-057)
- 기준 목록과 한 줄이라도 다르면(늘거나 줄면) 실패함. 의도한 변경이면 기준 목록을 같은 PR에서 고침
- 패키지 이름은 ${applicationId}로 바꿔 비교함. 패키지 이름(Q9)이 바뀌어도 기준 목록을 그대로 쓰려는 것임
"""

import html
import sys
from pathlib import Path

FORBIDDEN = "android.permission.INTERNET"
PLACEHOLDER = "${applicationId}"


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def read_actual(path: Path) -> tuple[str, set[str]]:
    package = ""
    lines: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("package:"):
            package = line.split(":", 1)[1].strip()
            continue
        lines.append(line)
    if package:
        lines = [line.replace(package, PLACEHOLDER) for line in lines]
    return package, set(lines)


def read_expected(path: Path) -> set[str]:
    lines = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            lines.add(line)
    return lines


def main() -> int:
    if len(sys.argv) not in (3, 4):
        print("usage: check_permissions.py <aapt2-permissions-output> <expected-list> [label]", file=sys.stderr)
        return 2

    package, actual = read_actual(Path(sys.argv[1]))
    expected = read_expected(Path(sys.argv[2]))
    label = f"{esc(sys.argv[3])} " if len(sys.argv) == 4 else ""

    print(f"### {label}권한 목록 (`{esc(package or '패키지 이름 없음')}`)")
    print()
    if not package:
        print("aapt2 출력에 package 줄이 없음. APK나 aapt2 실행을 확인하세요.")
        return 1

    for line in sorted(actual):
        print(f"- <code>{esc(line)}</code>")

    failed = False
    internet = sorted(line for line in actual if f"'{FORBIDDEN}'" in line or line.endswith(f" {FORBIDDEN}"))
    if internet:
        failed = True
        print()
        print(f"**실패: `{FORBIDDEN}`이 있음.** REQ-057은 이 권한을 선언하지 않는 것을 요구함. 새로 넣은 라이브러리를 확인하세요.")

    added = sorted(actual - expected)
    removed = sorted(expected - actual)
    if added or removed:
        failed = True
        print()
        print(f"**실패: 기준 목록(`{esc(sys.argv[2])}`)과 다름.** 의도한 변경이면 기준 목록을 같은 PR에서 고치고 이유를 적으세요.")
        for line in added:
            print(f"- 늘어남: <code>{esc(line)}</code>")
        for line in removed:
            print(f"- 줄어듦: <code>{esc(line)}</code>")

    if not failed:
        print()
        print(f"기준 목록과 같음 ({len(actual)}줄). `{FORBIDDEN}` 없음.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
