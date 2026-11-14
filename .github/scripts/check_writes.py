#!/usr/bin/env python3
"""판정 쓰기가 usecase의 공통 기록 함수만 거치는지 코드에서 봄 (REQ-003, TASK-034).

쓰는 법: python3 .github/scripts/check_writes.py [저장소 루트]
- shared/src/commonMain/.../data/Daos.kt에서 판정 표(dose_record)와 시계 표시(clock_flag)를 쓰는 DAO 메서드를 읽음:
  DoseRecordDao·ClockFlagDao·IntakePurgeDao 안에서 @Insert·@Update·@Delete·@Upsert이거나 @Query의 SQL이
  INSERT·UPDATE·DELETE로 시작하는 것. 새 쓰기 메서드를 더하면 이 검사도 따라옴
- 제품 코드(shared의 commonMain·androidMain·jvmMain, androidApp의 main·debug)에서 그 메서드를 usecase/ 밖에서
  부르면 실패함. 그 DAO를 가리키는 파일(doseRecordDao()·clockFlagDao()·intakePurgeDao()나 형식 이름이 있는 파일)의
  `.메서드(` 호출을 셈. 시험 코드는 보지 않음
- Daos.kt 밖의 Kotlin 글에 dose_record·clock_flag를 쓰는 INSERT·UPDATE·DELETE가 있으면 실패함(마이그레이션 포함:
  지금 마이그레이션은 판정을 건드리지 않음)
- 표준 출력에 Markdown을 씀. 파이썬 표준 라이브러리만 씀
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

DAOS = Path("shared/src/commonMain/kotlin/com/judangbang/pillbadeum/data/Daos.kt")
GUARDED = ("DoseRecordDao", "ClockFlagDao", "IntakePurgeDao")
GETTERS = ("doseRecordDao", "clockFlagDao", "intakePurgeDao")
PRODUCT = (
    "shared/src/commonMain",
    "shared/src/androidMain",
    "shared/src/jvmMain",
    "androidApp/src/main",
    "androidApp/src/debug",
)
ANNOTATED = re.compile(r"@(Insert|Update|Delete|Upsert)\b[^\n]*\n\s*(?:suspend\s+)?fun\s+(\w+)")
QUERY = re.compile(r'@Query\(\s*(?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")\s*,?\s*\)\s*(?:suspend\s+)?fun\s+(\w+)', re.S)
RAW_WRITE = re.compile(r"\b(?:INSERT\s+(?:OR\s+\w+\s+)?INTO|UPDATE|DELETE\s+FROM)\s+`?(dose_record|clock_flag)\b", re.I)


def interface_blocks(text: str) -> dict[str, str]:
    """Body of each `interface XDao {` in the file, by name (braces counted; the SQL has none)."""
    blocks = {}
    for m in re.finditer(r"interface\s+(\w+)\s*\{", text):
        depth, i = 1, m.end()
        while depth and i < len(text):
            depth += {"{": 1, "}": -1}.get(text[i], 0)
            i += 1
        blocks[m.group(1)] = text[m.end():i - 1]
    return blocks


def write_methods(daos: str) -> dict[str, list[str]]:
    """Methods that write dose_record or clock_flag: name -> the DAOs that have it. @Insert·@Update·@Delete·@Upsert of
    DoseRecordDao and ClockFlagDao (their entities are those tables), and @Query whose SQL writes one of the two tables
    (IntakePurgeDao's deletes of reminders, schedules and the intake itself are not judgment writes)."""
    found: dict[str, list[str]] = {}
    for dao, body in interface_blocks(daos).items():
        if dao not in GUARDED:
            continue
        names = [m.group(2) for m in ANNOTATED.finditer(body)] if dao != "IntakePurgeDao" else []
        for m in QUERY.finditer(body):
            sql = (m.group(1) if m.group(1) is not None else m.group(2)).strip()
            if RAW_WRITE.match(sql):
                names.append(m.group(3))
        for name in names:
            found.setdefault(name, []).append(dao)
    return found


def product_files(root: Path) -> list[Path]:
    files = []
    for top in PRODUCT:
        if (root / top).is_dir():
            files += sorted((root / top).rglob("*.kt"))
    return files


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    daos_path = root / DAOS
    methods = write_methods(daos_path.read_text(encoding="utf-8"))
    call = re.compile(r"\.(" + "|".join(map(re.escape, methods)) + r")\(") if methods else None
    inside: list[str] = []
    outside: list[str] = []
    raw: list[str] = []
    for path in product_files(root):
        if path == daos_path:
            continue
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        for n, line in enumerate(lines, 1):
            if RAW_WRITE.search(line):
                raw.append(f"`{rel}:{n}` {line.strip()[:100]}")
        if call is None or not any(name in text for name in GUARDED + GETTERS):
            continue
        in_usecase = "/usecase/" in rel
        for n, line in enumerate(lines, 1):
            for m in call.finditer(line):
                if in_usecase:
                    inside.append(f"`{path.name}:{n}` {m.group(1)}")
                else:
                    outside.append(f"`{rel}:{n}` {m.group(1)}")
    problems = len(outside) + len(raw) + (0 if methods else 1)
    print("### 판정 쓰기 경로 (REQ-003)")
    print()
    listed = ", ".join(f"{'·'.join(daos)}.{name}" for name, daos in methods.items()) or "없음 (Daos.kt를 읽지 못함)"
    print(f"- `Daos.kt`의 판정·시계 표시 쓰기 메서드 {len(methods)}개: {listed}")
    print(f"- usecase 안에서 부르는 곳 {len(inside)}곳: {', '.join(inside) or '없음'}")
    print(f"- usecase 밖에서 부르는 곳: {', '.join(outside) or '없음'}")
    print(f"- `Daos.kt` 밖의 `dose_record`·`clock_flag` 쓰기 SQL: {', '.join(raw) or '없음'}")
    print()
    print("문제 없음" if problems == 0 else f"문제 {problems}개")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
