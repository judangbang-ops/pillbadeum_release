# TASK-066 2차 개인정보 점검

- 기준은 `release/p2-r1`의 `309189c`다. 요구는 REQ-057·081·106, 명세는 SPEC-046·054, 시험은 TC-108이다. [설계 변경 R 15·16](../../10-records/changes/2026-11-22-phase2-release1.md), [시험 계획](../../12-qa/test-plan.md).
- CI는 릴리즈 APK의 권한과 병합 매니페스트를 기준 목록과 비교한다. 별도 가상 자료가 들어간 디버그 APK를 API 34 회귀 워크플로에서 실행해 위젯·알림, 최근 앱, 실패 경로를 확인한다.

## 검사

| 조건 | 검사 | 근거 |
| --- | --- | --- |
| 카드·약 줄, 클립보드 값, 내보내기 파일 로그 | `check_privacy.py`, `test_check_privacy.py` | 가상 로그 호출 14개 검출, 앱 소스 검출 0건. 예외 출력과 동적 로그 태그도 검사 |
| 위젯·알림의 카드·약 줄 조회와 표시 | `renderer_card_reads`, `check_privacy_render` | 위젯·알림 및 공통 데이터 경로에서 카드·약 줄 조회 참조를 검사. 에뮬레이터에서 일반 복용 항목 이름이 표시되는 위젯과 알림을 확인하고 가상 카드·약 줄 값을 검색 |
| 앱 권한과 exported 구성요소 | `check_permissions.py`, `check_privacy.py` | 디버그·릴리즈 APK 권한과 릴리즈 병합 매니페스트를 기준과 비교. 카드 FileProvider의 `exported=false` 확인 |
| R 15 SQLite 보안 삭제 | `check_secure_delete` | 앱의 Room 쓰기 연결에서 `PRAGMA main.secure_delete`와 `sqlite_version()` 조회 |
| R 16 홈과 최근 앱 | `check_card_recents` | 기록 화면과 SCR-011~015에서 홈 → 최근 앱으로 전환하고, 새 스냅샷의 `mIsRealSnapshot`과 캡처를 확인 |
| 저장·내보내기 실패 및 복사·공유 로그 | `check_card_logs` | 임시 SQLite 트리거로 저장 실패, 내보내기 경로에 일반 파일을 놓아 쓰기 실패를 유도. 그 사이 응급 대비 문장을 복사하고 공유 창까지 열어 `logcat`에서 시험 값과 파일 이름·경로·URI 검색 |

## 관찰 범위

- Android 12 이하에서는 최근 앱 미리보기만 따로 끌 수 없어 카드가 보일 수 있다. [Android API](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)).
- `secure_delete` 조회는 해당 쓰기 연결의 설정을 확인한다. SQLite WAL, 저장 장치, 백업, 사용자에게 공유된 사본의 삭제는 확인 범위 밖이다. [SQLite PRAGMA](https://www.sqlite.org/pragma.html#pragma_secure_delete).
- 로컬 에뮬레이터·실기기, 다른 Android 버전과 제조사 런처는 이번 점검의 실행 범위에 포함하지 않는다.
