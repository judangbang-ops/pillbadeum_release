"""TC-097 and TC-106 through the app, Sharesheet and system Files on the disposable regression emulator."""

import re
import sqlite3
import struct
import tempfile
import time
from datetime import datetime
from pathlib import Path

from backup_restore_check import ACTIVITY, DATA, DB, PKG, adb, launch_app, sh, tap
from explore import exact, tap_exact
from feedback_check import build_fixture
from screens_check import seek, size, to_home, wait_screen

FIXTURE = "org.example.task064.fixture"
FIXTURE_LABEL = "Card export fixture"
CACHE = f"{DATA}/cache/card-exports"
SHORT = "가상 엄마"
ONE = "가상 한쪽"
TWO = "가상 두쪽"
LONG = "가상 긴칸"
TALL = "가상 긴카드"
NAMES = (SHORT, ONE, TWO, LONG, TALL)
TOO_TALL = "사진이 너무 길어요. PDF로 보내 주세요"
EDITED = int(datetime.fromisoformat("2026-11-22T09:00:00+09:00").timestamp() * 1000)


def seed_card_fixtures():
    launch_app()
    sh(f"am force-stop {PKG}")
    remote = f"{DATA}/databases/{DB}"
    with tempfile.TemporaryDirectory(prefix="task064-db-") as temp:
        path = Path(temp) / DB
        adb("pull", remote, str(path))
        if sh(f"test -f {remote}-wal && echo yes", check=False).strip() == "yes":
            adb("pull", f"{remote}-wal", f"{path}-wal")
        con = sqlite3.connect(path)
        try:
            con.execute("PRAGMA foreign_keys=ON")
            with con:
                seed_rows(con)
            con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            con.execute("PRAGMA journal_mode=DELETE")
        finally:
            con.close()
        staged = "/data/local/tmp/task064-fake-card.db"
        adb("push", str(path), staged)
        sh(f"cat {staged} > {remote} && rm -f {remote}-shm {remote}-wal {staged}")
    launch_app()


def seed_rows(con):
    metadata = (EDITED, EDITED, 1, "fixture")
    people = {name: f"task064-person-{i}" for i, name in enumerate(NAMES)}
    people["나"] = con.execute("SELECT id FROM person WHERE name = ?", ("나",)).fetchone()[0]
    for name in NAMES:
        con.execute(
            "INSERT INTO person(id,name,color,created_at,updated_at,version,device_id) VALUES (?,?,?,?,?,?,?)",
            (people[name], name, "#392F88", *metadata),
        )
    for person_id in people.values():
        con.execute(
            "INSERT OR REPLACE INTO health_card(person_id,edited_at,created_at,updated_at,version,device_id) VALUES (?,?,?,?,?,?)",
            (person_id, EDITED, *metadata),
        )

    def entry(table, column, name, index, value, extra_column=None, extra_value=None):
        # Table and column names below are fixed test constants. All record values are bound.
        columns = ["id", "person_id", column, "source", "created_at", "updated_at", "version", "device_id"]
        values = [f"task064-{table}-{people[name]}-{index}", people[name], value, "USER", *metadata]
        if extra_column:
            columns.append(extra_column)
            values.append(extra_value)
        con.execute(f"INSERT INTO {table}({','.join(columns)}) VALUES ({','.join('?' for _ in values)})", values)

    entry("allergy", "substance", SHORT, 0, "가상 원인", "reaction", "가상 반응")
    entry("allergy", "substance", "나", 0, "보기 원인")
    entry("condition", "name", SHORT, 0, "가상 질환", "diagnosed_on", "2015")
    entry("procedure_history", "name", SHORT, 0, "가상 시술", "performed_on", "2019")
    con.execute(
        "INSERT INTO intake(id,person_id,name,slot_label,sort_order,created_at,updated_at,version,device_id) VALUES (?,?,?,?,?,?,?,?,?)",
        ("task064-intake", people[SHORT], "가상 항목", "MORNING", 0, *metadata),
    )
    con.execute(
        "INSERT INTO schedule_version(id,intake_id,type,params_json,late_policy,effective_from,created_at,updated_at,version,device_id)"
        " VALUES (?,?,?,?,?,?,?,?,?,?)",
        ("task064-schedule", "task064-intake", "DAILY", "{}", "KEEP", EDITED, *metadata),
    )
    for index, medicine in enumerate(("가상정 10mg", "보기정 500mg")):
        med_id = f"task064-med-{index}"
        con.execute(
            "INSERT INTO medication(id,name,created_at,updated_at,version,device_id) VALUES (?,?,?,?,?,?)",
            (med_id, medicine, *metadata),
        )
        con.execute(
            "INSERT INTO intake_medication(id,intake_id,medication_id,qty_per_take,effective_from,created_at,updated_at,version,device_id)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (f"task064-dose-{index}", "task064-intake", med_id, "1정", EDITED, *metadata),
        )
    for name, lines in ((ONE, 22), (TWO, 23), (LONG, 60)):
        note = "\n".join(f"{i}행" for i in range(1, lines + 1))
        entry("condition", "name", name, 0, "가상 질환", "note", note)
    for index in range(20):
        entry("allergy", "substance", TALL, index, f"가상 알레르기 {index}")
        entry("procedure_history", "name", TALL, index, f"가상 시술 {index}", "place", "가상 의료기관")
        entry("condition", "name", TALL, index, f"가상 질환 {index}", "note", "가상 메모입니다")


def open_export(name):
    # A failed assertion in the previous case can leave a system or receiver activity on top.
    for _ in range(3):
        focus = foreground_activity()
        if not any(activity in focus for activity in ("ChooserActivity", ".documentsui", FIXTURE)):
            break
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.5)
    to_home()
    tap_exact("건강 카드")
    label = f"{name}의 건강 카드"
    tap(seek(lambda item: item.text == label or item.desc == label, label))
    tap_exact("내보내기")
    if exact("보내기") is None:
        raise RuntimeError("Export preview did not open")


def files_in_cache():
    found = sh(f"find {CACHE} -type f 2>/dev/null", check=False)
    return {path for path in found.splitlines() if path.startswith(f"{CACHE}/")}


def foreground_activity():
    return sh(
        "dumpsys window | grep mCurrentFocus; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'",
        check=False,
    )


def tap_share_target():
    width, height = size()
    for attempt in range(3):
        if (target := exact(FIXTURE_LABEL, timeout=2)) is not None:
            tap(target)
            return
        if attempt < 2:
            # Android 14 initially hides the full list of apps below the Sharesheet fold.
            sh(f"input swipe {width // 2} {height * 3 // 4} {width // 2} {height // 4} 400")
    raise RuntimeError("The receiving app was not visible after expanding the system Sharesheet")


def send_file(extension="pdf"):
    before = files_in_cache()
    tap_exact("보내기")
    until = time.monotonic() + 15
    while "ChooserActivity" not in foreground_activity():
        if time.monotonic() >= until:
            raise RuntimeError("The system Sharesheet did not open")
        time.sleep(0.25)
    added = files_in_cache() - before
    if len(added) != 1:
        raise RuntimeError(f"Expected a single new file but got {len(added)}")
    path = added.pop()
    if not re.fullmatch(rf"{re.escape(CACHE)}/[0-9a-f-]{{36}}/pillbadeum-card-\d{{8}}\.{extension}", path):
        raise RuntimeError("The share file path is missing a UUID folder or uses a filename other than the date format")
    return path


def cancel_share():
    sh("input keyevent KEYCODE_BACK")
    if exact("보내기") is None or exact("PDF") is None:
        raise RuntimeError("Closing the Sharesheet did not return to the existing PDF preview")


def files_save_button(report):
    # DocumentsUI's fixed bottom button uses this ID in the Android compatibility tests.
    def is_save(node):
        return node.rid == "android:id/button1" or (
            node.cls.endswith("Button") and node.text.casefold() in ("save", "저장")
        )

    nodes = wait_screen(lambda items: any(is_save(item) for item in items), timeout=20)
    report.shot("tc097-system-files-save")
    button = next((node for node in nodes if is_save(node)), None)
    if button is None:
        labels = [(node.rid, node.text) for node in nodes if node.rid or node.text]
        raise RuntimeError(f"System Files did not expose its Save button: {labels[:24]}")
    return button


def pull_pdf(report, remote, case):
    local = report.out / f"tc097-{case}.pdf"
    adb("pull", remote, str(local))
    return local.read_bytes()


def assert_pdf(pdf, pages):
    page_count = len(re.findall(rb"/Type\s*/Page\b", pdf))
    if page_count != pages or not pdf.startswith(b"%PDF"):
        raise RuntimeError(f"Expected {pages} PDF pages, found {page_count}")
    objects = dict(re.findall(rb"(?ms)^(\d+) 0 obj\b(.*?)\nendobj", pdf))
    descriptors = {
        number for number, body in objects.items() if re.search(rb"/FontName\s*/\S*(?:NotoSansCJK|NotoSansKR)", body)
    }
    # Skia can embed Type 3 glyph programs in CharProcs instead of a whole font stream in the descriptor.
    embedded = any(re.search(rb"/FontFile[23]?\b", objects[number]) for number in descriptors) or any(
        b"/Subtype /Type3" in body and b"/CharProcs" in body
        and any(b"/FontDescriptor " + number + b" 0 R" in body for number in descriptors)
        for body in objects.values()
    )
    if not embedded:
        raise RuntimeError("The generated PDF has no embedded Hangul font")
    if len(re.findall(rb"/MediaBox\s*\[0 0 595 842\]", pdf)) != pages:
        raise RuntimeError("The generated PDF pages are not A4 portrait")


def check_card_export(report, steps):
    with tempfile.TemporaryDirectory(prefix="task064-share-") as temp:
        apk = build_fixture(Path(temp), "card_export_fixture", f"{FIXTURE}.SaveCardFile")
        adb("install", str(apk))
        try:
            seed_card_fixtures()
            first = {}

            def save_to_files():
                open_export(SHORT)
                send_file()
                cancel_share()
                report.shot("tc106-export-share-cancelled")
                path = send_file()
                generated = pull_pdf(report, path, "first-person")
                assert_pdf(generated, 1)
                first.update(path=path, pdf=generated)
                tap_share_target()
                if exact("PDF read=true writeDenied=true attempts=1") is None:
                    raise RuntimeError("The separate receiving app did not read the PDF with a read-only grant")
                tap_exact("Save in Files")
                tap(files_save_button(report))
                if exact("PDF saved in Files", timeout=30) is None:
                    raise RuntimeError("The receiver did not write the PDF using system Files")
                name = Path(path).name
                downloaded = pull_pdf(report, f"/sdcard/Download/{name}", "saved-in-files")
                if downloaded != generated:
                    raise RuntimeError("The system Files copy differs from the original export")
                sh("input keyevent KEYCODE_BACK")
                return "공유 취소 뒤 미리보기 유지. 별도 앱에 읽기만 허용됐고 시스템 파일 앱에서 한 쪽 PDF 저장"

            steps("14a. TC-097·106 공유 창 취소와 파일 앱 저장", "기존 미리보기 유지, 한 쪽 PDF와 한글 글꼴", save_to_files)

            def separate_paths():
                open_export("나")
                second = send_file()
                cancel_share()
                if second == first["path"] or Path(second).name != Path(first["path"]).name:
                    raise RuntimeError("Same-day exports did not have equal displayed names and distinct paths")
                other = pull_pdf(report, second, "second-person")
                assert_pdf(other, 1)
                if pull_pdf(report, first["path"], "first-person-after-second") != first["pdf"] or other == first["pdf"]:
                    raise RuntimeError("The first file was replaced or the two PDFs have identical contents")
                return "같은 날짜의 두 PDF 파일명은 같고 경로와 내용은 다름. 처음 파일의 바이트 그대로"

            steps("14b. TC-097 같은 날 서로 다른 대상자", "URI에 대응하는 첫 파일의 경로와 내용 유지", separate_paths)

            def pagination():
                problems = []
                for name, pages, case in ((ONE, 1, "boundary-one"), (TWO, 2, "boundary-two"), (LONG, 3, "single-long-entry")):
                    open_export(name)
                    if pages > 1 and exact(f"카드가 길어 {pages}쪽이에요. 쪽마다 이름과 쪽 번호가 들어가요", timeout=2) is None:
                        problems.append(f"{case}: expected a {pages}-page preview")
                    path = send_file()
                    try:
                        assert_pdf(pull_pdf(report, path, case), pages)
                    except RuntimeError as failure:
                        problems.append(f"{case}: {failure}")
                    cancel_share()
                    report.shot(f"tc097-{case}")
                if problems:
                    raise RuntimeError("; ".join(problems))
                return "한 줄 차이인 고정 데이터의 PDF 1쪽·2쪽, 긴 칸 하나의 PDF 3쪽"

            steps("14c. TC-097 페이지 경계와 긴 칸", "한 줄 차이로 1쪽·2쪽, 한 칸이 길면 이어짐", pagination)

            def png_limit():
                open_export(SHORT)
                tap_exact("사진(PNG)")
                normal = send_file("png")
                image = report.out / "tc097-short.png"
                adb("pull", normal, str(image))
                head = image.read_bytes()[:24]
                if not head.startswith(b"\x89PNG\r\n\x1a\n") or struct.unpack(">II", head[16:24]) != (1190, 1684):
                    raise RuntimeError("The one-page PNG was not 1190 x 1684")
                cancel_share()
                open_export(TALL)
                before = files_in_cache()
                tap_exact("사진(PNG)")
                if exact(TOO_TALL) is None:
                    raise RuntimeError("The 60-entry PNG height notice is missing")
                tap_exact("보내기")
                if files_in_cache() != before or exact(TOO_TALL) is None:
                    raise RuntimeError("The oversized PNG created a file or left the preview")
                report.shot("tc097-png-height-limit")
                tap_exact("PDF")
                if exact(TOO_TALL, timeout=2) is not None:
                    raise RuntimeError("Selecting PDF left the PNG height notice visible")
                return "짧은 카드 PNG는 1190 × 1684px. 칸 60개면 파일을 만들지 않고 PDF를 고르면 높이 안내가 사라짐"

            steps("14d. TC-097 PNG 높이 한도", "최대 높이를 넘기면 파일을 만들지 않음", png_limit)

            def cold_start():
                open_export(SHORT)
                path = send_file()
                tap_share_target()
                if exact("PDF read=true writeDenied=true attempts=1") is None:
                    raise RuntimeError("The separate receiver did not read the PDF before the process stopped")
                pid = sh(f"pidof {PKG}").strip()
                if not pid.isdecimal():
                    raise RuntimeError("Expected a single app process on the disposable emulator")
                sh(f"kill -9 {pid}")
                if sh(f"pidof {PKG}", check=False).strip() != "":
                    raise RuntimeError("The app process did not stop before the provider-only read")
                tap_exact("Read shared PDF")
                if exact("PDF read=true writeDenied=true attempts=2") is None or path not in files_in_cache():
                    raise RuntimeError("Opening the file provider alone removed the previous export")
                provider_pid = sh(f"pidof {PKG}").strip()
                if not provider_pid.isdecimal() or provider_pid == pid:
                    raise RuntimeError("The file read did not start a new provider process")
                report.shot("tc097-provider-cold-read")
                # NEW_TASK | CLEAR_TASK discards the remaining receiver task so Android creates MainActivity.
                # It does not force-stop the process that the provider just started.
                sh(f"am start -W -f 0x10008000 -n {ACTIVITY}")
                if exact("지난 기록", timeout=20) is None:
                    raise RuntimeError("The app did not reopen after the provider-only read")
                until = time.monotonic() + 5
                while files_in_cache() and time.monotonic() < until:
                    time.sleep(0.15)
                if files_in_cache():
                    raise RuntimeError("Opening the first app screen did not clear the export cache")
                return "별도 앱의 파일 읽기로 프로세스가 다시 시작되어도 파일 유지. 앱 화면을 열면 캐시가 빔"

            steps("14e. TC-097 프로세스 재시작과 캐시", "파일 공급자로 깨울 때 보존하고 첫 앱 화면에서 정리", cold_start)
        finally:
            adb("uninstall", FIXTURE, check=False)
