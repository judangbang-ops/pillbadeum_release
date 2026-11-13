#!/usr/bin/env python3
"""PR에 푸시한 커밋들이 지켜보는 파일을 바꿨는지 보고, 에뮬레이터처럼 무거운 작업을 돌릴지 정함.

GitHub의 on.pull_request.paths는 PR 전체가 바꾼 파일을 봄. 그래서 에뮬레이터 스크립트를 한 번이라도 바꾼
PR은 문서만 고친 푸시에도 에뮬레이터 확인을 모두 다시 돌림(2026-11-12 PR #17: 푸시마다 약 100분).
이 문은 지난 확인 뒤로 바뀐 파일만 봄.

비교 기준은 이 PR 브랜치에서 그 확인 작업(이름이 GATE_JOB으로 시작하는 작업)이 실제로 돌아 모두 성공한 마지막
실행의 커밋임. 이번 푸시의 앞 커밋과 비교하면, 앞 푸시에서 확인이 실패했는데 다음 푸시가 지켜보는 파일을
바꾸지 않았을 때 건너뛰어 PR에는 '건너뜀'만 남고 실패가 가려짐(PR #20 독립 검토). 건너뛴 실행·취소된 실행·실패한
실행, 수동 실행(API를 골라 돌릴 수 있음)은 기준이 되지 않음. 그 사이 main이 바뀐 것은 보지 않음(main은 머지 때
따로 확인함).

쓰는 법: python3 push_gate.py <경로>...
- 경로는 git pathspec. 'dir/'는 그 아래 모든 파일. 워크플로의 on.pull_request.paths와 같은 목록을 넘김
- 환경 변수: GATE_ACTION(github.event.action), GATE_AFTER(github.event.after), GATE_JOB(확인 작업 이름의 앞부분,
  예: '알림 (API'), GH_TOKEN(actions: read 권한). GITHUB_REPOSITORY·GITHUB_WORKFLOW_REF·GITHUB_HEAD_REF·
  GITHUB_API_URL은 Actions가 줌
- $GITHUB_OUTPUT에 run=true 또는 run=false를 씀. $GITHUB_STEP_SUMMARY가 있으면 까닭 한 줄을 씀
- 모르면 늘 run=true: synchronize가 아닌 동작(PR 열기·다시 열기·라벨), 커밋·작업 이름을 모름, 성공한 실행이
  없음, API를 읽지 못함, git diff 실패(강제 푸시로 기준 커밋이 클론에 없을 때 등)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

RUNS_LOOKED_AT = 30


def api(path: str) -> dict:
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    request = urllib.request.Request(
        base + path,
        headers={"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"},
    )
    token = os.environ.get("GH_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def workflow_file(workflow_ref: str) -> str:
    """'owner/repo/.github/workflows/x.yml@refs/pull/20/merge' -> 'x.yml'."""
    return workflow_ref.split("@", 1)[0].rsplit("/", 1)[-1]


def last_passed(repo: str, workflow: str, branch: str, job: str, get=api) -> tuple[str | None, str]:
    """The head commit of the newest finished PR run of `workflow` on `branch` whose jobs named `job`... all ran and
    passed, and which run that was. (None, why) when there is none among the last RUNS_LOOKED_AT runs."""
    query = urllib.parse.urlencode(
        {"branch": branch, "event": "pull_request", "status": "completed", "per_page": RUNS_LOOKED_AT}
    )
    runs = get(f"/repos/{repo}/actions/workflows/{urllib.parse.quote(workflow)}/runs?{query}")["workflow_runs"]
    for run in runs:  # newest first
        jobs = get(f"/repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100")["jobs"]  # its latest attempt
        checks = [j for j in jobs if j["name"].startswith(job)]
        if checks and all(j["conclusion"] == "success" for j in checks):
            return run["head_sha"], f"실행 {run['id']}"
    return None, f"최근 실행 {len(runs)}개 가운데 '{job}' 작업이 모두 성공한 실행이 없음"


def changed_since(base: str, after: str, paths: list[str]) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", base, after, "--", *paths],
        capture_output=True,
        text=True,
        check=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def decide(env: dict[str, str], paths: list[str], get=api, diff=changed_since) -> tuple[bool, str]:
    action = env.get("GATE_ACTION", "")
    after = env.get("GATE_AFTER", "")
    job = env.get("GATE_JOB", "")
    workflow = workflow_file(env.get("GITHUB_WORKFLOW_REF", ""))
    if action != "synchronize":
        return True, f"PR 동작이 '{action or '없음'}'이라 PR 전체 기준으로 돌림"
    if not after or set(after) == {"0"}:
        return True, "이번 푸시의 커밋을 몰라 돌림"
    if not job or not workflow:
        return True, "확인 작업이나 워크플로 이름을 몰라 돌림"
    try:
        base, where = last_passed(env.get("GITHUB_REPOSITORY", ""), workflow, env.get("GITHUB_HEAD_REF", ""), job, get)
    except Exception as error:  # the API only saves time: when it fails, run
        return True, f"지난 실행을 읽지 못해 돌림: {str(error)[:200]}"
    if base is None:
        return True, f"{where}이라 돌림"
    label = f"마지막으로 성공한 확인({where}, {base[:7]})"
    try:
        changed = diff(base, after, paths)
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, "stderr", "") or str(error)
        return True, f"git diff 실패라 돌림: {detail.strip()[:200]}"
    if changed:
        shown = ", ".join(changed[:10])
        if len(changed) > 10:
            shown += f" 외 {len(changed) - 10}개"
        return True, f"{label} 뒤로 바뀜: {shown}"
    return False, f"{label} 뒤로 지켜보는 파일이 바뀌지 않아 건너뜀({after[:7]})"


def main(argv: list[str]) -> int:
    if not argv:
        print("쓰는 법: python3 push_gate.py <경로>...", file=sys.stderr)
        return 2
    run, why = decide(dict(os.environ), argv)
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
