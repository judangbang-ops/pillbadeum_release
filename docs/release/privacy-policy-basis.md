# 개인정보처리방침 근거와 정할 것 (TASK-035)

- 대상: [방침 초안](privacy-policy.md) (2026-11-15, Claude). 공개할 글은 그 파일에만 두고, 이 문서는 공개하지 않는 작업 기록임
- 이 문서에 둔 것: 방침 문장마다의 근거(1장), Play 정책·개인정보 보호법 요건과의 대조(2장), 동희님이 정할 것(3장), 확인하지 못한 것(4장), 공개한 뒤 할 일(5장)
- 앱 코드는 main `3f1cd39`(2026-11-15 PR #35 머지)과 같음. 앱이 하는 일은 코드·매니페스트·QA 보고서에서 옮겼고, 요건은 2장에 적은 1차 출처를 읽은 날짜와 함께 적음

## 1. 문장마다의 근거

| 방침 | 문장 | 근거 |
| --- | --- | --- |
| 요약, 2장 | 인터넷 권한이 없어 기기 밖으로 보낼 수 없음 | REQ-057. CI가 PR마다 디버그·릴리즈 APK의 권한을 [`expected-permissions.txt`](../../androidApp/expected-permissions.txt)와 비교하고, `INTERNET`은 그 파일과 상관없이 늘 실패로 봄(`check_permissions.py`). [개인정보 점검](../07-qa/2026-11-14-privacy/report.md) 2절 |
| 요약, 2장 | 광고·사용 분석·오류 보고·AI 도구 없음 | REQ-083·REQ-090·REQ-059. 개인정보 점검 4절: 릴리즈 라이브러리 147개에 분석·크래시·광고·AI SDK와 네트워크 클라이언트가 없음. CI의 '개인정보 점검'이 PR마다 다시 봄 |
| 요약 | 회원 가입·로그인 없음 | REQ-057(서버·계정 없음) |
| 1장 표 | 저장하는 정보 | [`db/schema.sql`](../../db/schema.sql)의 표: 대상자 `person`(이름·색), 항목 `intake`(이름·위젯 이름 `widget_alias`·시간대 이름 `slot_label`·순서), 일정 `schedule_version`, 기록 `dose_record`(종류·`taken_at`·`recorded_at`·`source`·`zone_id`·`voided_at`), 시계 표식 `clock_flag`, 알림 `reminder_rule`·`reminder_snooze`, 설정 `setting`(하루 시작·기준 시간대·알림 간격·하루 최대·방해 금지·잠금 화면 이름·첫 실행 안내·복원 안내). `medication`·`intake_medication`·`dose_version` 표는 스키마에 있으나 1차 앱에 쓰는 코드가 없음 |
| 1장 표 | 설치 구별 번호 | 행마다의 `device_id`와 설정 `device_id`·`restored_from_device_id`. 앱이 처음 실행될 때 UUIDv7(만든 시각과 임의 값)로 만들고, 백업에 들어가지 않는 설치 표식 파일과 DB의 값을 견줘 복원을 알아봄([보충 R-1·R-2](../04-spec/record-rules-supplement.md), `restore/InstallCheck.kt`). 기기 식별자(IMEI·Android ID)나 광고 ID를 읽는 코드와 권한이 없음 |
| 1장 표 | 위젯 상태 | 개인정보 점검 6절: 위젯마다 Glance 상태에 대상자 선택, 다시 그린 시각, 마지막 탭의 안내 문구(항목 이름이 들어감)와 시각. 안내 문구는 '이미 기록됨'·'건너뜀으로 기록됨'·오류일 때만 생김(`Labels.recordMessage`) |
| 1장 | 연락처·위치·사진·카메라·마이크·다른 앱 정보에 접근하지 않음 | 병합 매니페스트의 권한 7개에 해당 권한이 없음(개인정보 점검 2절) |
| 1장 | 권한 설명 | 개인정보 점검 2절의 권한 표: 앱이 넣은 3개(`RECEIVE_BOOT_COMPLETED`·`POST_NOTIFICATIONS`·`SCHEDULE_EXACT_ALARM`), WorkManager 2.7.1이 넣은 `WAKE_LOCK`·`ACCESS_NETWORK_STATE`·`FOREGROUND_SERVICE`(와 겹치는 `RECEIVE_BOOT_COMPLETED`), androidx.core의 이 앱 전용 서명 권한 1개. 이 앱의 WorkManager 작업에는 네트워크 조건이 없음 |
| 2장 | 제공·위탁·국외 이전 없음 | 개발자가 받는 정보가 없음(인터넷 권한 없음). 백업은 사용자가 켠 Android 기능이고 개발자가 맡긴 처리가 아님(3장) |
| 3장 | 기록 DB만 백업, 위젯 상태는 넣지 않음 | REQ-055, [보충 B-1](../04-spec/record-rules-supplement.md#1-백업에-넣는-파일-a1). [`data_extraction_rules.xml`](../../androidApp/src/main/res/xml/data_extraction_rules.xml)·`backup_rules.xml`이 `diditake.db`·`-wal`·`-journal`만 넣음. 설치 표식은 `noBackupFilesDir`라 빠짐 |
| 3장 | 화면 잠금이 있는 Android 9 이상에서만 Google 계정으로 백업, 8.x는 백업하지 않음 | 결정 Q16, 보충 B-3: Android 12 이상 `disableIfNoEncryptionCapabilities="true"`, 9~11 `requireFlags="clientSideEncryption"`, 8.x는 규칙 파일을 읽지 못해 백업을 건너뜀(에뮬레이터 API 26·27, [백업·복원 QA](../07-qa/2026-11-12-backup-restore/report.md)). 설정 화면 문구는 REQ-054 |
| 3장 | 기기 간 이전은 화면 잠금과 상관없이 옮겨짐 | 보충 B-3: `device-transfer`(12 이상)와 `requireFlags="deviceToDeviceTransfer"` 줄(9~11). 9~11의 실제 이전과 Smart Switch는 확인 전(4장) |
| 3장 | 앱을 지워도 백업이 있으면 다시 설치할 때 되살아날 수 있음 | 검토 B9. RR-26: 에뮬레이터 API 34의 시험용 전송으로 백업 → 지우고 다시 설치 → 기록 1건이 돌아옴([백업·복원 QA](../07-qa/2026-11-12-backup-restore/report.md) 3번, [릴리즈 변형 확인](../07-qa/2026-11-15-release-variant/report.md) 6번). 실제 Google 계정 백업은 TASK-032 |
| 4장 | 잠금 화면에는 처음에 "복용 시간이에요"만 | 설정 `show_names_on_lock`이 없으면 끔(`ManageSettings.showNamesOnLock`). 끔이면 항목 알림은 `VISIBILITY_SECRET`이고 따로 `VISIBILITY_PUBLIC` "복용 시간이에요" 하나, 켬이면 `VISIBILITY_PRIVATE`(`ReminderNotifications.visibilityFor`). 갤럭시 실기기 여덟 칸은 TASK-052 |
| 5장 | 기록을 취소하거나 고쳐도 원래 기록이 남음 | 보충 I-2: 판정을 지우거나 고쳐 쓰지 않고 무효 처리(`voided_at`) 뒤 새 행 |
| 5장 | 항목 완전 삭제 | REQ-015, SPEC-025: 보관한 항목의 [완전 삭제]가 항목과 판정을 함께 지움, "지난 기록도 함께 지워지고 되돌릴 수 없어요" |
| 5장 | 대상자 이름을 완전히 지우는 기능은 없음 | SPEC-026(이름 바꾸기)·SPEC-027(보관)·SPEC-044(되살리기)만 있음 |
| 6장 | 다른 앱이 기록을 보내거나 바꾸지 못함 | REQ-081, 개인정보 점검 3절: 기록을 받는 수신기는 모두 `exported="false"`, PendingIntent는 `FLAG_IMMUTABLE`(까닭 있는 하나 빼고) |
| 6장 | 로그에 이름과 기록 내용을 남기지 않음 | REQ-058, 개인정보 점검 5절: 앱 소스의 로그 호출 39개에 이름 없음. 라이브러리 예외 메시지는 보지 않음 |

## 2. 요건과의 대조

조사 결과를 받으면 채움.

## 3. 정할 것 (동희님)

| # | 정할 것 | 왜 필요한가 | Claude 추천 |
| --- | --- | --- | --- |
| 1 | 앱 이름 | 방침 제목과 본문의 `{앱 이름}`. TASK-045와 같은 결정 | TASK-045에서 정하는 이름을 그대로 씀 |
| 2 | 방침에 적을 개발자 이름 | Play 정책은 방침에 개발자 정보를 요구함(2장). Play 등록정보에 보이는 개발자 이름과 같아야 헷갈리지 않음 | Play 개발자 계정(결정 Q11, TASK-048)의 이름과 같게 |
| 3 | 연락 이메일 | 개인정보 보호책임자 연락처. 방침과 함께 공개됨 | Play 등록정보의 개발자 이메일과 같은 주소. 개인 메일을 공개하고 싶지 않으면 이 앱용 주소를 따로 만듦 |
| 4 | 공개 위치 | Play Console과 앱 설정(SCR-009)에 넣을 주소. 앱에 들어간 주소는 앱 업데이트 없이 바꾸지 못함 | 3.1 |
| 5 | 공개 승인과 시행일 | 공개는 동희님 확인 뒤(`ask_before`: publishing) | 문안 확인 뒤, Play Console에 넣는 날을 시행일로 |

### 3.1 공개 위치

| 안 | 주소 예 | 좋은 점 | 나쁜 점 |
| --- | --- | --- | --- |
| 가. 이 공개 저장소의 파일 | `https://github.com/judangbang-ops/pillbadeum_release/blob/main/docs/release/privacy-policy.md` | 따로 설정이 없음. 로그인 없이 읽힘. 바뀐 이력이 커밋으로 남아 9장의 '이전 판'이 그 파일의 커밋 목록이 됨. git으로 관리하라는 지시(2026-11-15 15:31 KST)와 맞음 | GitHub 화면(영어 메뉴, 저장소 파일 목록)이 함께 보여 사용자에게 낯섦. 저장소를 비공개로 돌리면 주소가 끊김 |
| 나. GitHub Pages | `https://judangbang-ops.github.io/pillbadeum_release/…` | 방침 글만 보이는 페이지. 원본은 그대로 git | 저장소 관리자가 설정에서 Pages를 한 번 켜야 함(Claude가 켤 수 없음). 배포 워크플로가 하나 늚. 저장소를 비공개로 돌리면 함께 꺼짐 |
| 다. Notion 웹 게시 | Notion 공개 페이지 | 동희님이 바로 고치기 쉬움 | git 원본과 두 곳을 맞춰야 함. 기록을 git으로 모은 지시와 어긋남 |

- **추천: 가.** 1차 비공개 테스트에는 따로 설정 없이 바로 쓸 수 있고, 이력이 git에 남음. 나중에 나(Pages)로 옮겨도 가의 주소는 계속 읽힘
- 반론: 일반 사용자가 보기에는 나가 깔끔함. 정식 출시 전에는 나로 옮기는 것을 다시 봄
- 어느 쪽이든 저장소를 비공개로 돌리기 전에 방침을 다른 공개 위치로 옮겨야 함

## 4. 확인하지 못한 것

- 갤럭시 Smart Switch로 옮길 때 이 앱의 정보가 따라가는지(검토 B10, TASK-032 RR-29). 방침은 "제조사 이전 앱은 그 앱의 방식을 따름"으로만 적음
- Android 9~11 기기 간 이전에서 두 번째 규칙 줄이 쓰이는지(보충 B-3, TASK-032)
- 잠금 화면 알림이 기기 설정('내용 표시'·'내용 숨김')과 앱 설정에 따라 실제로 어떻게 보이는지(TASK-052 여덟 칸). 방침은 "기기 설정에 따라 보일 수 있음"으로 적음
- 라이브러리가 남기는 로그와 예외 메시지(개인정보 점검 7절)

## 5. 공개한 뒤 할 일

- 방침 파일의 `{ }` 칸을 채우고 맨 위 초안 상자를 지움. 시행일을 적음
- 앱: `shared/src/commonMain/kotlin/com/judangbang/pillbadeum/ui/SettingsScreen.kt`의 `PRIVACY_POLICY_URL`에 주소를 넣고, 앱 시험 `SettingsScreenTest`의 '준비 중' 줄을 링크 줄로 고침(TC-036)
- Play Console '앱 콘텐츠'의 개인정보처리방침 칸(TASK-036, TC-075)
- wbs TASK-035 **끝남**, 진행 기록
