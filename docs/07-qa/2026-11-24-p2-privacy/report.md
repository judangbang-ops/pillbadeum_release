# TASK-066 2차 개인정보 점검

- 기준은 `release/p2-r1`의 `309189c`다. 요구는 REQ-057·081·106, 명세는 SPEC-046·054, 시험은 TC-108이다. [설계 변경 R 15·16](../../10-records/changes/2026-11-22-phase2-release1.md), [시험 계획](../../12-qa/test-plan.md).
- CI는 릴리즈 APK의 권한과 병합 매니페스트를 기준 목록과 비교한다. 별도 가상 자료가 들어간 디버그 APK를 API 34 회귀 워크플로에서 실행해 위젯·알림, 최근 앱, 실패 경로를 확인한다.

## 검사

| 조건 | 검사 | 근거 |
| --- | --- | --- |
| 카드·약 줄, 클립보드 값, 내보내기 파일 로그 | `check_privacy.py`, `test_check_privacy.py` | 가상 로그 호출 14개 검출, 앱 소스 로그 호출 40개에서 검출 0건. 예외 출력과 동적 로그 태그도 검사 |
| 위젯·알림의 카드·약 줄 조회와 표시 | `renderer_card_reads`, `check_privacy_render` | 위젯·알림 및 공통 데이터 경로에서 카드·약 줄 조회 참조를 검사. 에뮬레이터에서 일반 복용 항목 이름이 표시되는 위젯과 알림을 확인하고 가상 카드·약 줄 값을 검색 |
| 앱 권한과 exported 구성요소 | `check_permissions.py`, `check_privacy.py` | 디버그·릴리즈 APK 권한과 릴리즈 병합 매니페스트를 기준과 비교. 카드 FileProvider의 `exported=false` 확인 |
| R 15 SQLite 보안 삭제 | `check_secure_delete` | 앱의 Room 쓰기 연결에서 `PRAGMA main.secure_delete`와 `sqlite_version()` 조회 |
| R 16 홈과 최근 앱 | `check_card_recents` | 기록 화면과 SCR-011~015에서 홈 → 최근 앱으로 전환하고, 새 스냅샷의 `mIsRealSnapshot`과 캡처를 확인 |
| 저장·내보내기 실패 및 복사·공유 로그 | `check_card_logs` | 임시 SQLite 트리거로 저장 실패, 내보내기 경로에 일반 파일을 놓아 쓰기 실패를 유도. 그 사이 응급 대비 문장을 복사하고 공유 창까지 열어 `logcat`에서 시험 값과 파일 이름·경로·URI 검색 |

## 정적 검사 관찰

- `test_check_privacy.py`의 Python 시험 5건이 통과했다. 회귀 시험은 가상 로그와 렌더 경로의 조회를 검출하고, 허용된 호출과 현재 앱 소스를 통과시켰다.
- 같은 가상 파일을 임시 복제본의 앱 소스 경로에 놓아 확인했다. 기준 커밋의 검사기는 가상 호출 14개 중 2개, 확장한 검사기는 14개를 검출했다. 확장한 검사기 전체 실행은 가상 파일이 있을 때 종료 코드 1, 파일을 제거했을 때 0을 반환했다. 병합 매니페스트와 의존성은 기준에 합친 내보내기 PR의 릴리즈 빌드 자료를 사용했다. [기준 빌드 45376516034](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45376516034).
- `1f32411`의 CI에서 JVM 395건, 앱 화면 211건, Python 5건이 통과했다. 디버그·릴리즈 APK 권한, 릴리즈 병합 매니페스트의 exported 7개가 각각 기준과 같았다. 릴리즈의 카드 FileProvider는 `exported=false`였다. [CI 45389845701](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45389845701).

## API 34 에뮬레이터 관찰

- `1f32411`의 API 34 회귀는 29건이 통과했다. TC-108의 네 검사와 기존 REQ-092 위젯 데이터 삭제 회귀를 포함한다. 앱 Room 쓰기 연결에서 `PRAGMA main.secure_delete`는 `1`, `sqlite_version()`은 `3.50.1`이었다. 앱은 `BundledSQLiteDriver`를 사용한다. [회귀 실행 45389845602](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45389845602).
- 위젯·알림에는 가상 복용 항목 이름이 표시됐고 카드·약 줄 시험 값은 각각 0건이었다. 홈 화면에서도 카드·약 줄 값이 없었다. 최근 앱에서 기록 화면은 `mIsRealSnapshot=true`, SCR-011~015는 모두 `false`였다. 캡처 여섯 장을 대조해 기록 화면은 실제 미리보기, 카드 화면 다섯 개는 모두 빈 미리보기로 보이는 것을 확인했다. [캡처와 회귀 보고서](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45389845602/artifacts/14919654578).
- 저장 실패와 내보내기 쓰기 실패 메시지가 표시됐다. 응급 대비 문장 복사와 내보내기 공유 창이 열리는 경로를 포함한 `logcat` 구간에서 시험 값과 내보내기 파일 이름·경로·URI는 0건이었다.
- `0412738` 실행에서는 TC-108이 남긴 위젯의 스크롤 상태로 기존 REQ-092 시험이 항목을 찾지 못했다. TC-108을 기존 위젯·내보내기 회귀 뒤로 옮기고 최근 앱 전환이 끝난 뒤 캡처하도록 조정했다. [첫 회귀 실행](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45384277789).

`1f32411`의 PR 검사 22개 가운데 20개가 통과했고, 스크린샷과 업데이트 에뮬레이터 두 작업은 변경 경로 게이트로 건너뛰었다. 두 작업은 앞선 코드 실행에서 통과했다. [스토어 스크린샷](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45384277834), [API 34 업데이트](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45384277907).

## 관찰 범위

- Android 12 이하에서는 최근 앱 미리보기만 따로 끌 수 없어 카드가 보일 수 있다. [Android API](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)).
- `secure_delete` 조회는 해당 쓰기 연결의 설정을 확인한다. SQLite WAL, 저장 장치, 백업, 사용자에게 공유된 사본의 삭제는 확인 범위 밖이다. [SQLite PRAGMA](https://www.sqlite.org/pragma.html#pragma_secure_delete).
- 로컬 에뮬레이터·실기기, 다른 Android 버전과 제조사 런처는 이번 점검의 실행 범위에 포함하지 않는다.
