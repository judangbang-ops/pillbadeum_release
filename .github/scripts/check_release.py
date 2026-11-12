#!/usr/bin/env python3
"""릴리즈 빌드의 서명·버전 번호·debuggable·정렬을 확인함 (TASK-002, REQ-062, 독립 검토 A2·A3).

쓰는 법:
  python3 check_release.py --build-tools <build-tools 디렉터리> --apk <서명하지 않은 릴리즈 APK>
      --aab <서명한 릴리즈 AAB> --cert-sha256 <AAB 서명 인증서의 기대 SHA-256>
      --version-code <기대 버전 번호> [--key-label <표 제목에 적을 키 이름>]

확인하는 것 (하나라도 어긋나면 종료 코드 1):
- AAB: `jarsigner -verify`가 'jar verified.'를 내고, 서명 인증서가 하나이며 그 SHA-256이 기대값과 같음
- APK: `aapt2 dump badging`의 versionCode가 기대값과 같고 debuggable이 아님
- APK: `zipalign -c -P 16 4`가 통과함(4바이트 정렬, 압축하지 않은 네이티브 라이브러리의 16KB 페이지 정렬).
  이 APK를 동희님 PC에서 앱 서명 키로 서명하므로(scripts/sign-personal-apk.ps1) 서명 전에 정렬이 맞아야 함
표준 출력에 Markdown을 씀 (워크플로에서 $GITHUB_STEP_SUMMARY로도 보냄)
"""

from __future__ import annotations

import argparse
import hashlib
import html
import re
import subprocess
import sys
from pathlib import Path

SHA256_LINE = re.compile(r"^\s*SHA256:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})\s*$")
PACKAGE_LINE = re.compile(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", re.M)
DEBUGGABLE_LINE = re.compile(r"^application-debuggable\s*$", re.M)

Row = tuple[str, bool, str]


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def colon_hex(value: str) -> str:
    """'7d23…'나 '7D:23:…'을 '7D:23:…' 꼴로 맞춤"""
    digits = re.sub(r"[^0-9A-Fa-f]", "", value).upper()
    return ":".join(digits[i : i + 2] for i in range(0, len(digits), 2))


def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return subprocess.CompletedProcess(cmd, 127, "", f"도구가 없음: {cmd[0]}")


def last_line(proc: subprocess.CompletedProcess[str]) -> str:
    """실패 설명으로 쓸 마지막 줄. 표준 출력을 먼저 보고, JVM이 붙이는 'Picked up …' 줄은 뺌"""
    for stream in (proc.stdout, proc.stderr):
        lines = [line.strip() for line in stream.splitlines() if line.strip() and not line.startswith("Picked up ")]
        if lines:
            return esc(lines[-1])
    return f"종료 코드 {proc.returncode}"


def file_info(path: Path) -> str:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return f"`{esc(str(path))}` ({path.stat().st_size:,}바이트, SHA-256 `{digest}`)"


def check_aab(aab: Path, expected_sha256: str) -> list[Row]:
    rows: list[Row] = []
    verify = run(["jarsigner", "-J-Duser.language=en", "-verify", str(aab)])
    verified = verify.returncode == 0 and re.search(r"^jar verified\.", verify.stdout, re.M) is not None
    rows.append(("AAB 서명 검증 (`jarsigner -verify`)", verified, "jar verified." if verified else last_line(verify)))

    printed = run(["keytool", "-J-Duser.language=en", "-printcert", "-jarfile", str(aab)])
    found = [colon_hex(m.group(1)) for line in printed.stdout.splitlines() if (m := SHA256_LINE.match(line))]
    expected = colon_hex(expected_sha256)
    name = "AAB 서명 인증서 SHA-256"
    if printed.returncode != 0 or not found:
        rows.append((name, False, "인증서를 읽지 못함: " + last_line(printed)))
    elif len(found) != 1:
        rows.append((name, False, f"인증서가 {len(found)}개임 (1개여야 함): " + ", ".join(f"`{fp}`" for fp in found)))
    elif found[0] != expected:
        rows.append((name, False, f"`{found[0]}` (기대값 `{expected}`와 다름)"))
    else:
        rows.append((name, True, f"`{found[0]}` (기대값과 같음)"))
    return rows


def check_apk(build_tools: Path, apk: Path, expected_version_code: int) -> list[Row]:
    rows: list[Row] = []
    badging = run([str(build_tools / "aapt2"), "dump", "badging", str(apk)])
    package = PACKAGE_LINE.search(badging.stdout)
    if badging.returncode != 0 or not package:
        rows.append(("APK 버전 번호 (`versionCode`)", False, "`aapt2 dump badging`을 읽지 못함: " + last_line(badging)))
        rows.append(("APK debuggable 아님", False, "확인하지 못함"))
    else:
        name, code, version_name = package.groups()
        detail = f"{esc(code)} (versionName {esc(version_name)}, 패키지 `{esc(name)}`)"
        if code == str(expected_version_code):
            rows.append(("APK 버전 번호 (`versionCode`)", True, detail + ", 커밋 수와 같음"))
        else:
            rows.append(("APK 버전 번호 (`versionCode`)", False, detail + f", 기대값 {expected_version_code}와 다름"))
        debuggable = DEBUGGABLE_LINE.search(badging.stdout) is not None
        rows.append(("APK debuggable 아님", not debuggable, "debuggable임" if debuggable else "아님"))

    align = run([str(build_tools / "zipalign"), "-c", "-P", "16", "4", str(apk)])
    rows.append(("APK 정렬 (`zipalign -c -P 16 4`)", align.returncode == 0, "맞음" if align.returncode == 0 else last_line(align)))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="릴리즈 APK·AAB를 확인함 (TASK-002)")
    parser.add_argument("--build-tools", required=True, type=Path, help="Android SDK build-tools 디렉터리 (aapt2·zipalign)")
    parser.add_argument("--apk", required=True, type=Path, help="서명하지 않은 릴리즈 APK")
    parser.add_argument("--aab", required=True, type=Path, help="서명한 릴리즈 AAB")
    parser.add_argument("--cert-sha256", required=True, help="AAB 서명 인증서의 기대 SHA-256 (콜론은 있어도 없어도 됨)")
    parser.add_argument("--version-code", required=True, type=int, help="기대 versionCode (빌드한 커밋까지의 커밋 수)")
    parser.add_argument("--key-label", default="", help="표 제목에 적을 서명 키 이름")
    args = parser.parse_args()

    if len(re.sub(r"[^0-9A-Fa-f]", "", args.cert_sha256)) != 64:
        print(f"--cert-sha256이 SHA-256 꼴이 아님: '{args.cert_sha256}'", file=sys.stderr)
        return 2
    for path in (args.apk, args.aab):
        if not path.is_file():
            print(f"### 릴리즈 빌드 확인\n\n**실패:** 파일이 없음: `{esc(str(path))}`")
            return 1

    rows = check_aab(args.aab, args.cert_sha256) + check_apk(args.build_tools, args.apk, args.version_code)

    title = "릴리즈 빌드 확인" + (f" ({esc(args.key_label)})" if args.key_label else "")
    print(f"### {title}")
    print()
    print("| 확인 | 결과 | 값 |")
    print("| --- | --- | --- |")
    for name, ok, detail in rows:
        cell = detail.replace("|", "\\|").replace("\n", " ")
        print(f"| {name} | {'통과' if ok else '**실패**'} | {cell} |")
    print()
    print(f"- AAB(서명함): {file_info(args.aab)}")
    print(f"- APK(서명 안 함): {file_info(args.apk)}")

    failed = [name for name, ok, _ in rows if not ok]
    if failed:
        print()
        print("**실패:** " + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
