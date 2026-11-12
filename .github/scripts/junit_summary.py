#!/usr/bin/env python3
"""Gradle JUnit XML 결과를 GitHub 실행 요약(Markdown)으로 바꿈.

쓰는 법: python3 junit_summary.py <test-results 폴더>
- 표준 출력에 Markdown을 씀 (워크플로에서 $GITHUB_STEP_SUMMARY로 보냄)
- 결과 파일이 없거나, 테스트가 0개이거나, 모두 건너뛰어 통과한 테스트가 없으면 종료 코드 1.
  테스트 작업이 '통과'했는데 실제로는 아무 테스트도 안 돈 경우를 잡으려는 것임.
"""

import html
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_LISTED_FAILURES = 30


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: junit_summary.py <test-results-dir>", file=sys.stderr)
        return 2

    results_dir = Path(sys.argv[1])
    files = sorted(results_dir.glob("**/TEST-*.xml")) if results_dir.is_dir() else []

    print(f"### 테스트 결과 (`{results_dir}`)")
    print()
    if not files:
        print("결과 파일이 없음. 컴파일이나 설정 단계에서 멈췄을 수 있으니 위 단계 로그를 보세요.")
        return 1

    total = failures = errors = skipped = 0
    seconds = 0.0
    failed_cases: list[str] = []
    per_class: list[tuple[str, int]] = []

    for path in files:
        suite = ET.parse(path).getroot()
        tests = int(suite.get("tests", 0))
        total += tests
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        seconds += float(suite.get("time", 0) or 0)
        name = suite.get("name", path.stem)
        per_class.append((name.rsplit(".", 1)[-1], tests))
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                first_line = (problem.get("message") or "").strip().splitlines()
                reason = first_line[0][:200] if first_line else problem.tag
                # kotlin.test 메시지 'Expected <A>, actual <B>.'의 <A>가 HTML 태그로 지워지지 않게 함
                test_name = html.escape(f"{case.get('classname', '')}.{case.get('name', '')}")
                failed_cases.append(f"<code>{test_name}</code>: {html.escape(reason)}")

    passed = total - failures - errors - skipped
    print("| 전체 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |")
    print("| ---: | ---: | ---: | ---: | ---: | ---: |")
    print(f"| {total} | {passed} | {failures} | {errors} | {skipped} | {seconds:.1f}초 |")
    print()
    print("<details><summary>클래스별 테스트 수 ({}개 파일)</summary>".format(len(files)))
    print()
    for name, count in per_class:
        print(f"- {name}: {count}")
    print()
    print("</details>")

    if failed_cases:
        print()
        print("#### 실패한 테스트")
        for line in failed_cases[:MAX_LISTED_FAILURES]:
            print(f"- {line}")
        if len(failed_cases) > MAX_LISTED_FAILURES:
            print(f"- 외 {len(failed_cases) - MAX_LISTED_FAILURES}개 (테스트 보고서 아티팩트 참고)")

    if total == 0:
        print()
        print("실행된 테스트가 0개임. 테스트 소스가 빠졌는지 확인하세요.")
        return 1
    if passed == 0 and failures == 0 and errors == 0:
        print()
        print("모든 테스트가 건너뜀이라 통과한 테스트가 없음.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
