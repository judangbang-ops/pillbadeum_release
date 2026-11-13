#!/usr/bin/env python3
"""PR에 이번에 푸시한 커밋들이 지켜보는 파일을 바꿨는지 보고, 에뮬레이터처럼 무거운 작업을 돌릴지 정함.

GitHub의 on.pull_request.paths는 PR 전체가 바꾼 파일을 봄. 그래서 에뮬레이터 스크립트를 한 번이라도 바꾼
PR은 문서만 고친 푸시에도 에뮬레이터 확인을 모두 다시 돌림(2026-11-12 PR #17: 푸시마다 약 100분).
이 문은 이번 푸시가 바꾼 파일(이벤트의 before..after)만 봄.

쓰는 법: python3 push_gate.py <경로>...
- 경로는 git pathspec. 'dir/'는 그 아래 모든 파일. 워크플로의 on.pull_request.paths와 같은 목록을 넘김
- 환경 변수 GATE_ACTION(github.event.action), GATE_BEFORE(github.event.before), GATE_AFTER(github.event.after)
- $GITHUB_OUTPUT에 run=true 또는 run=false를 씀. $GITHUB_STEP_SUMMARY가 있으면 까닭 한 줄을 씀
- 모르면 늘 run=true: synchronize가 아닌 동작(PR 열기·다시 열기), before·after가 없음, git diff 실패(강제 푸시로
  before가 클론에 없을 때 등)
"""

import os
import subprocess
import sys


def decide(action: str, before: str, after: str, paths: list[str]) -> tuple[bool, str]:
    if action != "synchronize":
        return True, f"PR 동작이 '{action or '없음'}'이라 PR 전체 기준으로 돌림"
    if not before or not after or set(before) == {"0"}:
        return True, "이번 푸시의 앞뒤 커밋을 몰라 돌림"
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", before, after, "--", *paths],
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, "stderr", "") or str(error)
        return True, f"git diff 실패라 돌림: {detail.strip()[:200]}"
    changed = [line for line in result.stdout.splitlines() if line]
    if changed:
        shown = ", ".join(changed[:10])
        if len(changed) > 10:
            shown += f" 외 {len(changed) - 10}개"
        return True, f"이번 푸시가 바꿈: {shown}"
    return False, f"이번 푸시({before[:7]}..{after[:7]})는 지켜보는 파일을 바꾸지 않아 건너뜀"


def main(argv: list[str]) -> int:
    if not argv:
        print("쓰는 법: python3 push_gate.py <경로>...", file=sys.stderr)
        return 2
    run, why = decide(
        os.environ.get("GATE_ACTION", ""),
        os.environ.get("GATE_BEFORE", ""),
        os.environ.get("GATE_AFTER", ""),
        argv,
    )
    line = f"run={'true' if run else 'false'}"
    print(f"{line} ({why})")
    for name, text in (("GITHUB_OUTPUT", line), ("GITHUB_STEP_SUMMARY", why)):
        target = os.environ.get(name)
        if target:
            with open(target, "a", encoding="utf-8") as handle:
                handle.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
