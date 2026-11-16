# 개인정보처리방침 근거와 정할 것 (TASK-035)

- 대상: [방침](privacy-policy.md). 초안은 2026-11-15 Claude가 썼고, 2026-11-16 동희님 결정(3장)으로 빈칸을 채워 공개함. 공개할 글은 그 파일에만 두고, 이 문서는 근거를 남기는 작업 기록임(공개 저장소에 있어 읽을 수는 있음)
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
| 3장 | Google이 Google 방침에 따라 다룸, 대개 하루 한 번, 새 백업이 이전 백업을 지움, 57일 | 2.4의 Android 문서와 Google One 도움말 |
| 4장 | 잠금 화면에는 처음에 "복용 시간이에요"만 | 설정 `show_names_on_lock`이 없으면 끔(`ManageSettings.showNamesOnLock`). 끔이면 항목 알림은 `VISIBILITY_SECRET`이고 따로 `VISIBILITY_PUBLIC` "복용 시간이에요" 하나, 켬이면 `VISIBILITY_PRIVATE`(`ReminderNotifications.visibilityFor`). 갤럭시 실기기 여덟 칸은 TASK-052 |
| 5장 | 기록을 취소하거나 고쳐도 원래 기록이 남음 | 보충 I-2: 판정을 지우거나 고쳐 쓰지 않고 무효 처리(`voided_at`) 뒤 새 행 |
| 5장 | 항목 완전 삭제 | REQ-015, SPEC-025: 보관한 항목의 [완전 삭제]가 항목과 판정을 함께 지움, "지난 기록도 함께 지워지고 되돌릴 수 없어요" |
| 5장 | Google 백업을 끄면 그 휴대폰의 백업이 지워짐, 계정 대시보드에서도 지움, 앱에서 지운 것은 다음 백업부터 | 2.4의 Google One 도움말과 "Only the most recent backup is stored" |
| 5장 | 대상자 이름을 완전히 지우는 기능은 없음 | SPEC-026(이름 바꾸기)·SPEC-027(보관)·SPEC-044(되살리기)만 있음 |
| 6장 | 다른 앱이 기록을 보내거나 바꾸지 못함 | REQ-081, 개인정보 점검 3절: 기록을 받는 수신기는 모두 `exported="false"`, PendingIntent는 `FLAG_IMMUTABLE`(까닭 있는 하나 빼고) |
| 6장 | 로그에 이름과 기록 내용을 남기지 않음 | REQ-058, 개인정보 점검 5절: 앱 소스의 로그 호출 39개에 이름 없음. 라이브러리 예외 메시지는 보지 않음 |
| 7장 | 가족·법정대리인의 요청은 휴대폰을 가진 사용자가 처리 | 법 제30조 제1항 제5호(2.3). 가족의 이름과 기록은 그 가족을 챙기는 사용자의 휴대폰에만 있음 |
| 8장 | 신고·상담 기관 셋 | 2.3의 작성지침 예시 |

## 2. 요건과의 대조

읽은 날은 모두 2026-11-15. Google Play 도움말 페이지에는 고친 날이 보이지 않음. 법 조문과 Play 도움말은 조사 에이전트가 읽은 문장을 옮김(Claude가 직접 연 law.go.kr 페이지는 스크립트로 그려져 본문이 없었고, Play 도움말은 자동 접속 확인 화면이 떴음). Android 문서와 Google One 도움말(한국어)은 Claude가 원문에서 다시 확인함.

### 2.1 Google Play 사용자 데이터 정책

출처: [User Data](https://support.google.com/googleplay/android-developer/answer/10144311)

| 요건 (원문) | 방침 |
| --- | --- |
| "All apps must post a privacy policy link in the designated field within Play Console, and a privacy policy link or text within the app itself." | Play Console 칸은 TASK-036. 앱 안은 설정(SCR-009)의 링크(공개 뒤 `PRIVACY_POLICY_URL`) |
| "Developer information and a privacy point of contact or a mechanism to submit inquiries" | 머리말과 8장(`{개발자 이름}`, `{연락 이메일}`) |
| "Disclosing the types of personal and sensitive user data your app accesses, collects, uses, and shares; and any parties with which any personal or sensitive user data is shared" | 1장(저장하고 쓰는 것), 2장(받는 것·나누는 것 없음), 3장(Google 백업) |
| "Secure data handling procedures for personal and sensitive user data" | 6장 |
| "The developer's data retention and deletion policy" | 5장 |
| "Clear labeling as a privacy policy (for example, listed as "privacy policy" in title)" | 제목 '개인정보처리방침' |
| "The entity (for example, developer, company) named in the app's Google Play store listing must appear in the privacy policy or the app must be named in the privacy policy." | 머리말에 개발자와 앱 이름 둘 다 |
| "Apps that do not access any personal and sensitive user data must still submit a privacy policy." | 이 앱은 기기 안에서 건강에 관한 정보를 다루므로 어느 쪽이든 필요 |
| "...available on an active, publicly accessible and non-geofenced URL (no PDFs) and is non-editable." | 3.1의 공개 위치. 가·나 모두 맞음 |

### 2.2 Google Play 건강 앱 정책

출처: [Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511), [Health apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291)

- "Health and medical apps must complete the Health apps declaration form in Play Console and post a privacy policy detailing the handling of personal and sensitive data." → 이 방침과 TASK-036의 신고
- 면책 문구 "not a medical device and does not diagnose, treat, cure, or prevent any medical condition"과 "consult a healthcare professional"은 앱 설명에 넣는 것(REQ-064, TASK-037)이라 방침에는 넣지 않음. 앱 안 문구는 REQ-036(첫 화면·설정)
- 신고의 분류는 "Medication and Treatment Management – Apps for managing medication schedules, pharmacy services, and ensuring adherence to treatment plans. May include pill identifiers, medication reminders, and prescription management." (TASK-036에서 고름)

### 2.3 개인정보 보호법 제30조 제1항과 시행령 제31조 제1항

출처: law.go.kr 개인정보 보호법(시행 2026. 9. 11., 법률 제21445호) 제30조, 같은 법 시행령(시행 2026. 11. 10., 대통령령 제36804호) 제31조, 개인정보보호위원회 「개인정보 처리방침 작성지침」(2026.4. 개정, 2026-04-23 게시)

| 항목 | 방침 |
| --- | --- |
| 1. 처리 목적 | 1장 첫 문단 |
| 2. 처리 및 보유 기간 | 5장(개발자 쪽 없음, 휴대폰은 지울 때까지, 백업은 3장) |
| 3. 제3자 제공 (해당 시) | 2장: 없음 |
| 3의2. 파기 절차·방법 | 5장의 지우는 방법. 개발자가 파기할 정보는 없음 |
| 3의3. 민감정보의 공개 가능성과 비공개 선택 (해당 시) | 정보를 공개하는 기능이 없음. 휴대폰 화면에 보이는 것(잠금 화면 알림은 끔이 기본, 홈 화면 위젯)은 4장 |
| 4. 처리 위탁 (해당 시) | 2장: 없음 |
| 4의2. 가명정보 (해당 시) | 2장: 없음 |
| 5. 정보주체·법정대리인의 권리와 행사 방법 | 7장 |
| 6. 개인정보 보호책임자의 성명과 연락처 | 8장(`{개발자 이름}`, `{연락 이메일}`). 조문은 "전화번호 등 연락처"라 적음(3장 3번) |
| 7. 자동 수집 장치 (해당 시) | 2장: 쓰지 않음 |
| 시행령 1. 처리하는 항목 | 1장 표 |
| 시행령 2. 국외 이전 | 2장: 없음. Google 백업은 사용자가 켠 Google 기능이고 Google 방침을 따름(3장) |
| 시행령 3. 안전성 확보 조치 | 6장 |
| 시행령 4. 국외에서 직접 수집할 때의 국가명 | 해당 없음 |
| 시행령 제31조 제2항: 인터넷 홈페이지에 지속적으로 게재 | 3.1의 공개 위치 |

- 작성지침의 권장: 단말기 안에서만 처리하면 "해당 개인정보가 외부 서버로 전송되지 않고 단말기 내에서 처리된다는 사실을 공개하는 것을 권장함" → 요약과 2장. "해당 사항이 없는 경우에는 "해당 개인정보를 처리하지 않는다"라는 사실을 개인정보 처리방침에 작성하는 것도 가능" → 2장의 '없음'
- 권익침해 구제방법은 조문 항목이 아니고 작성지침의 권장 항목임. 작성지침(2026.4.) 예시대로 개인정보 분쟁조정위원회(1833-6972)·개인정보침해 신고센터(118)·경찰청(182) 셋을 적음. 번호는 조사 에이전트가 각 기관 누리집 아래쪽에서도 확인함. 예전 방침에 흔한 대검찰청은 넣지 않음: 2026-10-02 공소청법 시행으로 검찰청법이 폐지됐고(조사 에이전트가 law.go.kr에서 읽음), 작성지침 예시에도 없음. 작성지침이 "명칭 또는 연락처가 변경된 경우에는 최신 정보로 현행화"하라고 하므로 공개하는 날 세 곳을 다시 봄
- 개발자가 정보를 받지 않을 때도 '개인정보처리자'(제2조제5호)인지는 읽은 자료에 없음. 방침은 Play 정책 때문에 어차피 필요하므로 법의 항목을 모두 다루게 씀

### 2.4 Google 백업 (3장·5장의 근거)

- Android [자동 백업 문서](https://developer.android.com/identity/data/autobackup)(고친 날 2026-10-26): "The backup is end-to-end encrypted on devices running Android 9 or higher using the device's PIN, pattern, or password." "Backup data is stored in a private folder in the user's Google Drive account, limited to 25 MB per app." "Only the most recent backup is stored. When a backup is made, any previous backup is deleted." 백업 조건 "At least 24 hours have elapsed since the last backup. The device is idle. The device is connected to a Wi-Fi network (if the device user hasn't opted in to mobile-data backups)." 기기 간 이전 "because D2D transfers aren't sent to the server, they continue to operate even on devices that don't support encryption."
- Android [백업 개요](https://developer.android.com/identity/data/backup)(고친 날 2026-08-26): "Google treats this data as personal information in accordance with Google's Privacy Policy." "Backups happen automatically, roughly once a day."
- [Google One 도움말 '기기 백업하기'](https://support.google.com/googleone/answer/9149304?hl=ko): "기기에서 백업을 사용 중지하면 백업이 삭제됩니다. 이전에 백업한 사진과 동영상은 Google 포토에 계속 보관됩니다. Google 계정 대시보드를 사용하여 백업을 삭제할 수도 있습니다. 57일 동안 기기를 사용하지 않으면 백업한 데이터(사진 또는 동영상은 제외)도 삭제됩니다."
- 출처끼리 어긋나는 곳: 개발자 문서는 앱 백업이 드라이브 용량에 들지 않는다고 적고, Google One 저장용량 도움말은 "All data managed through the Android backup settings"가 든다고 적음(조사 에이전트). 방침에는 용량을 적지 않음

### 2.5 데이터 보안 양식 (TASK-036으로 넘김)

- [Data safety 도움말](https://support.google.com/googleplay/android-developer/answer/10787469)에 Android 자동 백업은 나오지 않음. 가까운 문장: "User data accessed by your app that is only processed locally on the user's device and not sent off device does not need to be disclosed." "User data that is sent off device, but that is unreadable by you or anyone other than the sender and recipient as a result of end-to-end encryption does not need to be disclosed." FAQ "If the user chooses to upload their data directly to their own external drive or cloud storage account (such as Google Drive…) and this upload is governed by the … provider's terms of service and privacy policy, and your app never collects or accesses the data in question, then your app does not need to declare the collection of this data."
- 이 앱의 클라우드 백업은 종단 간 암호화될 때만 하므로 두 번째 문장에 해당한다고 봄(Claude 판단. Google이 이 경우를 직접 적지는 않음). 양식 답 초안은 TASK-036

## 3. 정할 것 (동희님)

**정함** (2026-11-16): 다섯 모두 정해져 방침에 넣음.

- 질문은 2026-11-15 출시 준비 묶음 보고의 '정해 주실 것' 1·3번. 2026-11-16 10:05 KST에 동희님이 '필받음 재구축 인계' 채팅에서 "추천대로 모두 승인"하셨고, 10:09 KST에 앱 이름·개발자 이름·이메일을 주심
- 1 앱 이름: 필받음
- 2 개발자 이름: 고동희 (Play에 보일 이름으로 주심)
- 3 연락처: 이메일 elddlwkd@gmail.com. 추천대로 앱 연락용 주소를 따로 쓰기로 했고, 이 주소를 주심. 전화번호는 넣지 않음(Claude. 조문은 "전화번호 등 연락처"라 이메일도 연락처이고, 보고에서 전화번호를 묻지 않았음. 넣으시려면 8장에 더함)
- 4 공개 위치: 3.1의 가(이 공개 저장소의 파일). 방침 2장의 게시 서비스는 GitHub, 9장의 이전 판은 그 파일의 커밋 목록
- 5 문안 승인과 시행일: 문안 승인. 시행일은 2026년 11월 16일, main에 합쳐 공개한 날(Claude). 아래 표의 추천은 'Play Console에 넣는 날'이었으나, 저장소 파일은 합치는 날 바로 공개되고, 앱 설정(SCR-009)도 Play보다 먼저(시험용 갤럭시, TASK-047) 이 주소를 엶. 시행일을 Play Console에 넣는 날로 바꾸라고 하시면 그날 고침

아래는 물을 때의 표임.

| # | 정할 것 | 왜 필요한가 | Claude 추천 |
| --- | --- | --- | --- |
| 1 | 앱 이름 | 방침 제목과 본문의 `{앱 이름}`. TASK-045와 같은 결정 | TASK-045에서 정하는 이름을 그대로 씀 |
| 2 | 방침에 적을 개발자 이름 | Play 정책은 방침에 개발자 정보를 요구함(2장). Play 등록정보에 보이는 개발자 이름과 같아야 헷갈리지 않음 | Play 개발자 계정(결정 Q11, TASK-048)의 이름과 같게 |
| 3 | 연락처 | 개인정보 보호책임자 연락처. 방침과 함께 공개됨. 법 조문(제30조 제1항 제6호)은 "전화번호 등 연락처"라 적음 | 이메일: Play 등록정보의 개발자 이메일과 같은 주소(개인 메일을 공개하고 싶지 않으면 이 앱용 주소를 따로 만듦). 전화번호는 넣을지 정해 주셔야 함 |
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
- 개발자가 정보를 받지 않을 때도 개인정보처리자인지, 잠금 화면에 약 이름을 보이는 것이 민감정보의 '공개 가능성'(제30조 제1항 제3호의3)에 해당하는지: 읽은 자료에 없음(2.3)
- 식품의약품안전처가 복약 알림 앱을 의료기기로 보는지: 1차 출처를 찾지 못함. Play 건강 앱 신고의 'Medical Device Apps' 여부는 TASK-036에서 다시 봄
- Google Play 도움말 문장은 조사 에이전트가 읽은 것이고 Claude가 원문을 다시 열지 못함(자동 접속 확인 화면). 공개 전 Play Console에서 방침을 넣을 때 도움말을 다시 읽음

## 5. 공개한 뒤 할 일

- 끝남: 방침 파일의 `{ }` 칸을 채우고 맨 위 초안 상자를 지우고 시행일을 적음(2026-11-16 결정 반영 PR)
- 끝남: `shared/src/commonMain/kotlin/com/judangbang/pillbadeum/ui/SettingsScreen.kt`의 `PRIVACY_POLICY_URL`에 주소를 넣고, 앱 시험 `SettingsScreenTest`의 '준비 중' 줄을 링크 줄로 고침(TC-036, PR #42). jvm `PrivacyPolicyUrlTest`가 이 파일이 그 주소에 있는지 봄. **이 파일을 옮기거나 이름을 바꾸지 않음**: 앱에 들어간 주소는 업데이트 없이 못 바꿈
- Play Console '앱 콘텐츠'의 개인정보처리방침 칸(TASK-036, TC-075)
- 끝남: wbs TASK-035 **끝남**과 진행 기록(PR #42)
