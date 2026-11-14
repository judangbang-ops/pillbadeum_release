# 개인정보 점검 (2026-11-14)

- 대상: TASK-031. 앱 코드는 main `c17a707`(R0~R3와 R2 화면 작업까지 들어간 상태)과 같음
- 방법: CI([`ci.yml`](../../../.github/workflows/ci.yml)의 '개인정보 점검')가 서명하지 않은 릴리즈 APK(`:androidApp:assembleRelease`)를 만든 뒤 아래 넷을 [`check_privacy.py`](../../../.github/scripts/check_privacy.py)로 봄. PR과 main 푸시마다 돎
  - 릴리즈 변형의 병합 매니페스트(`packaged_manifests` 또는 `merged_manifests`의 `AndroidManifest.xml`)
  - 병합 보고서 `manifest-merger-release-report.txt`: 권한·구성요소를 어느 매니페스트가 넣었는지
  - `./gradlew :androidApp:dependencies --configuration releaseRuntimeClasspath`: 릴리즈 APK에 들어가는 라이브러리
  - 앱 소스(`androidApp/src/main`, `shared/src/commonMain`·`androidMain`)의 로그 호출과 PendingIntent
- 실패 조건: `INTERNET` 권한, 분석·크래시·광고·AI SDK, 기준 목록([`expected-exported.txt`](../../../androidApp/expected-exported.txt))과 다른 exported 구성요소, `exported="false"`가 아닌 알림 수신기, `FLAG_IMMUTABLE` 없는 PendingIntent(까닭을 적은 예외 하나), 이름을 담을 수 있는 값을 넣는 로그 호출
- 실행: [CI 실행 43563317312](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43563317312)(`56ebcf6`)에서 통과. 처음 실행 [43560980486](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43560980486)(`99aea67`)은 기준 목록에 Glance의 서비스 하나를 빠뜨려 실패했고, 기준을 고쳐 다시 돌림(3절)
- 근거: 스크립트 출력 그대로는 [evidence/privacy-report.md](evidence/privacy-report.md). 병합 매니페스트·병합 보고서·의존성 목록은 [evidence](evidence/) 폴더(통과한 실행의 아티팩트 `privacy-release`를 그대로 옮김)

## 1. 결과 요약

| # | 확인 항목 | 요구 | 기대 | 결과 | 근거 |
| --- | --- | --- | --- | --- | --- |
| 1 | 릴리즈 병합 매니페스트의 권한 | REQ-057 | `INTERNET` 없음 | 통과: 쓰는 권한 7개에 `INTERNET` 없음 | 2절 |
| 2 | 권한 전체 목록과 출처 | 검토 B8 | 권한마다 넣은 매니페스트 | 남김: 앱이 3개, WorkManager가 4개(하나는 앱과 겹침), androidx.core가 1개(이 앱 전용 서명 권한) | 2절 |
| 3 | 다른 앱이 부를 수 있는 구성요소 | REQ-081 | 기준 목록과 같음, 각각 보호되거나 해가 없음 | 통과: 7개가 기준과 같음. 라이브러리 4개는 시스템 권한으로 막힘, 앱 3개는 첫 화면 열기·이 앱 위젯 설정·위젯 다시 그리기만 함 | 3절 |
| 4 | 알림 버튼 수신기와 PendingIntent | REQ-081 | `ReminderReceiver` `exported="false"`, PendingIntent `FLAG_IMMUTABLE` | 통과: 앱의 수신기 4개 가운데 위젯 공급자만 내보냄. PendingIntent 5개 가운데 4개가 `FLAG_IMMUTABLE`, 1개는 까닭이 있음 | 3절 |
| 5 | 분석·크래시 보고 SDK | REQ-090 | 없음 | 통과: 라이브러리 147개에 없음 | 4절 |
| 6 | AI SDK | REQ-059 | 없음 | 통과: 없음 | 4절 |
| 7 | 광고 SDK | REQ-083 | 없음 | 통과: 없음. 네트워크 클라이언트도 없음 | 4절 |
| 8 | 로그 호출 | REQ-058 | 이름·기록을 넣지 않음 | 통과: 39개 모두 이름 없음 | 5절 |
| 9 | DB 밖에 쓰는 파일 (관찰) | REQ-058 | 6절의 표 | 위젯의 마지막 탭 안내 문구에 항목 이름이 남음. 앱 전용, 백업 안 됨. 고치지 않음 | 6절 |

## 2. 권한과 출처

| 권한 | 넣은 곳 | 앱에서 쓰는 곳 | 판단 |
| --- | --- | --- | --- |
| `RECEIVE_BOOT_COMPLETED` | 앱, `androidx.work:work-runtime:2.7.1` | 재부팅 뒤 날짜 경계·복용 알람을 다시 걺(`DayBoundaryReceiver`). WorkManager도 예약을 되살릴 때 씀 | 필요 |
| `POST_NOTIFICATIONS` | 앱 | 복용 알림(TASK-025) | 필요 |
| `SCHEDULE_EXACT_ALARM` | 앱 | 정확한 복용 알람. 거절되면 부정확 알람으로 바꿈(`ReminderAlarm`) | 필요 |
| `WAKE_LOCK` | `androidx.work:work-runtime:2.7.1` | WorkManager가 작업하는 동안 기기를 깨워 둠 | 라이브러리 기본 |
| `ACCESS_NETWORK_STATE` | `androidx.work:work-runtime:2.7.1` | 네트워크 조건이 붙은 작업용. 이 앱의 작업에는 네트워크 조건이 없음 | 그대로 둠 (8절) |
| `FOREGROUND_SERVICE` | `androidx.work:work-runtime:2.7.1` | 오래 걸리는 작업을 포그라운드로 돌릴 때 씀. 이 앱은 쓰지 않음 | 그대로 둠 (8절) |
| `com.judangbang.pillbadeum.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (쓰기·정의) | `androidx.core:core:1.18.0` | 코드에서 등록하는 수신기를 이 앱만 보낼 수 있게 막을 때 씀. 보호 수준 `signature`라 다른 앱은 얻지 못함 | 해 없음 |

- 출처는 병합 보고서의 `ADDED from`·`MERGED from` 줄에서 읽음. 처음 실행에서는 스크립트가 `application` 줄의 출처를 앞 권한(`SCHEDULE_EXACT_ALARM`)에 붙여 잘못 보였고, 고쳐서 다시 돌림(판정에는 영향 없음)
- `INTERNET`이 없으므로 이 앱은 기기 밖으로 아무것도 보낼 수 없음. 같은 권한 목록은 디버그·릴리즈 APK 모두 PR마다 기대 목록과도 비교함(`check_permissions.py`, TASK-002)

## 3. 다른 앱이 부를 수 있는 것

### 3.1 exported 구성요소 7개 (기준 목록과 같음)

| 종류 | 이름 | 넣은 곳 | 막는 것 | 다른 앱이 할 수 있는 일 |
| --- | --- | --- | --- | --- |
| activity | `MainActivity` | 앱 | 없음 (런처 아이콘) | 앱의 첫 화면을 엶. 받는 값은 '첫 화면으로' 표시(`EXTRA_HOME`) 하나 |
| activity | `widget.WidgetPickActivity` | 앱 | 없음 (`APPWIDGET_CONFIGURE`) | 위젯 대상자 고르기 화면을 엶. 이 앱의 위젯 번호가 아니면 닫음. 고르는 것은 사람이 함 |
| receiver | `widget.DidITakeWidgetReceiver` | 앱 | 없음 (`APPWIDGET_UPDATE`) | 위젯을 다시 그리게 함. 기록은 이 수신기로 받지 않음(3.2) |
| service | `androidx.glance.appwidget.GlanceRemoteViewsService` | Glance 1.2.0 | `BIND_REMOTEVIEWS` (시스템만) | 없음. 위젯의 목록(LazyColumn) 칸을 런처에 그려 줌 |
| service | `androidx.work.impl.background.systemjob.SystemJobService` | WorkManager 2.7.1 | `BIND_JOB_SERVICE` (시스템만) | 없음 |
| receiver | `androidx.work.impl.diagnostics.DiagnosticsReceiver` | WorkManager 2.7.1 | `DUMP` (adb 셸 등) | 없음 |
| receiver | `androidx.profileinstaller.ProfileInstallReceiver` | profileinstaller 1.4.0 | `DUMP` | 없음 |

- 처음 기준 목록은 디버그 APK와 라이브러리 문서를 보고 예상으로 적어 `GlanceRemoteViewsService`를 빠뜨렸음. 첫 실행이 '늘었음'으로 실패해 기준에 더함. 앞으로 exported 구성요소가 늘거나 줄면 같은 PR에서 기준 파일과 그 까닭을 고쳐야 CI가 통과함

### 3.2 기록을 받는 곳

| 종류 | 이름 | exported | 받는 것 |
| --- | --- | --- | --- |
| receiver | `reminder.ReminderReceiver` | false | 알림의 [먹었어]·[30분 뒤]·[오늘 건너뜀] 버튼, 알림 밀어 지우기, 복용 알람, 정확한 알람 권한 바뀜(시스템) |
| receiver | `boundary.DayBoundaryReceiver` | false | 04:00 날짜 경계 알람, 재부팅·시각·시간대 바뀜·앱 업데이트(시스템) |
| receiver | `widget.WidgetPinnedReceiver` | false | 앱 안내에서 놓은 위젯의 번호 |
| receiver | `widget.DidITakeWidgetReceiver` | true | 시스템의 위젯 갱신 |

- 위젯 칸을 눌러 기록하는 것은 `actionRunCallback<RecordFromWidget>`이고, Glance가 이것을 `ActionCallbackBroadcastReceiver`로 보냄. 칸이 목록(LazyColumn) 안에 있으면 Glance의 트램펄린 액티비티(`ActionTrampolineActivity`·`InvisibleActionTrampolineActivity`)를 거침. 병합 매니페스트에서 이 셋이 모두 `exported="false"`임을 봄. 이 앱은 Glance의 람다 동작(위젯 공급자가 직접 받는 클릭)을 쓰지 않음
- 그래서 다른 앱은 기록·건너뛰기·미루기를 보낼 수 없음(REQ-081). 위젯을 그리는 런처는 따로임: 목록 위젯은 Android 방식대로 런처가 칸마다 누를 때의 내용을 채움(Glance가 만드는 PendingIntent 틀). 그 틀의 플래그는 라이브러리 안이라 보지 않음(7절)

### 3.3 앱 소스의 PendingIntent 5개

| 곳 | 가리키는 것 | 플래그 | 판단 |
| --- | --- | --- | --- |
| `boundary/DayBoundaryAlarm.kt:31` | `DayBoundaryReceiver` | `FLAG_IMMUTABLE`, `FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `reminder/ReminderAlarm.kt:99` | `ReminderReceiver` (복용 알람) | `FLAG_IMMUTABLE`, `FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `reminder/ReminderNotifications.kt:297` | `ReminderReceiver` (알림 버튼) | `FLAG_IMMUTABLE`, `FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `reminder/ReminderNotifications.kt:304` | `MainActivity.homeIntent` (알림 누르기) | `FLAG_IMMUTABLE`, `FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `widget/WidgetPinnedReceiver.kt:59` | `WidgetPinnedReceiver` | `FLAG_UPDATE_CURRENT`, Android 12부터 `FLAG_MUTABLE` | 바꿀 수 있어야 함: `requestPinAppWidget`의 성공 콜백이라 런처가 새 위젯 번호(`EXTRA_APPWIDGET_ID`)를 더함. 인텐트가 이 앱의 내보내지 않은 수신기를 이름으로 가리켜 다른 곳으로 돌릴 수 없음 |

- Glance가 위젯에 만드는 PendingIntent는 라이브러리 안의 것이라 이 검사에 없음. 가는 곳이 내보내지 않은 구성요소임만 3.2에서 봄

## 4. 의존성

릴리즈 실행 클래스패스의 라이브러리 147개(전체 목록: [evidence/dependencies-releaseRuntimeClasspath.txt](evidence/dependencies-releaseRuntimeClasspath.txt)).

| 묶음 | 수 | 무엇 |
| --- | --- | --- |
| `androidx.*` | 115 | Compose 28, Lifecycle 18, SQLite 8, Room 6(앱의 Room 3 넷, WorkManager가 쓰는 Room 2.2.5 둘), Glance 4, WorkManager 2, 그 밖 49 |
| `org.jetbrains.*` | 30 | Compose Multiplatform 12, kotlinx(코루틴·직렬화·날짜 등) 9, JetBrains androidx 7, Kotlin 표준 라이브러리 1, 주석 1 |
| 그 밖 | 2 | `com.google.guava:listenablefuture:1.0`(WorkManager가 쓰는 인터페이스 하나), `org.jspecify:jspecify:1.0.0`(null 표시 주석) |

- 분석·크래시 보고·광고·AI SDK(스크립트의 `FORBIDDEN` 33묶음: Firebase, Google Analytics·광고, Crashlytics, Sentry, Facebook, AppsFlyer, ML Kit, TensorFlow Lite, OpenAI 등): 없음
- 네트워크 클라이언트(OkHttp, Retrofit, Ktor 클라이언트, Volley): 없음

## 5. 로그 호출

앱 소스의 로그 호출 39개(`Log.i`·`Log.w`·`Log.e`. `println`·`printStackTrace`는 없음). 모두 '이름 없음'.

- 고정된 글만 남기는 것 20개
- 값을 넣는 것 19개. 넣는 값은 모두 수·시각·번호·종류·단계 이름임

| 곳 | 넣는 값 | 무엇 |
| --- | --- | --- |
| `AndroidPhone.kt:196`, `:199` | `intent.action` | 열려던 시스템 설정 화면의 action |
| `AppContainer.kt:126` | `restored` | 복원 뒤인지 (참·거짓) |
| `DayBoundaryAlarm.kt:39`, `ReminderAlarm.kt:86`, `:95` | `at`, `WINDOW` | 알람 시각과 허용 폭 |
| `DayBoundaryAlarm.kt:64`, `ReminderReceiver.kt:36` | `action` | 받은 방송의 action |
| `ReminderAlarm.kt:67` | `delay`, `attempt + 1` | 다시 울릴 때까지와 몇 번째인지 |
| `Reminders.kt:106`, `:113` | `notices.size`, `contents.size` | 알림 수 |
| `Reminders.kt:150` | `result` | `RecordResult`의 `Locked`·`Error`. `Error`의 메시지에는 항목 번호만 있음 |
| `Reminders.kt:167`, `DidITakeWidget.kt:209` | `result.message` | 같은 `Error`의 메시지(항목 번호만) |
| `Reminders.kt:181` | `name` | 단계 이름(replan 등). 항목·대상자 이름이 아님 |
| `DidITakeWidget.kt:186` | 위젯 번호 목록 | 복원 전후의 위젯 번호 |
| `WidgetChoices.kt:68` | `filled`, 설치 상태의 종류 | 채운 위젯 수, 클래스 이름 |
| `WidgetPickActivity.kt:54`, `WidgetPinnedReceiver.kt:30` | `appWidgetId`, 선택의 종류 | 위젯 번호, 클래스 이름 |

- 24개는 예외를 함께 남김. 앱 코드가 만드는 예외 메시지 13곳(`check`·`checkNotNull`·`require`·`throw`. `error`는 없음)에 넣는 값은 번호·파일 경로·색 코드·일정 규칙(JSON과 요일 코드)·시각뿐이고 이름은 없음(손으로 봄. 2곳은 값을 넣지 않음). 라이브러리(Room·SQLite 등)가 던지는 예외의 메시지는 보지 않음(7절)
- 이 판단은 값의 이름에 든 낱말(`name`, `label`, `title`, `person`, `item`, `text`, `message`, `record` 등)로 함. 이름이 아님을 확인한 값 4개는 까닭과 함께 스크립트의 `SAFE`에 적음. 코드가 바뀌어 값이 달라지면 다시 실패로 걸림

## 6. DB 밖에 쓰는 파일 (관찰)

REQ-058의 원문은 기획 v3 8장 "저장소에는 실제 복용 기록·가족 건강정보를 올리지 않음"과 README 작업 규칙 "건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음"임. 여기서 '저장소'는 코드 저장소(git)를 뜻함. 앱이 기기에 쓰는 것은 요구 밖이지만, 기록 DB 밖에 무엇을 쓰는지 소스에서 찾아 적음.

| 파일 | 쓰는 곳 | 담는 것 | 이름 | 백업 |
| --- | --- | --- | --- | --- |
| 기록 DB `databases/diditake.db`(와 `-wal`·`-journal`) | Room | 기록·항목·대상자 | 있음 (앱이 하는 일) | 넣음 (REQ-055) |
| 설치 표식 `noBackupFilesDir/install_marker` | `FileInstallMarker` | 이 설치의 `device_id` | 없음 | 넣지 않음 (`noBackupFilesDir`) |
| 위젯 선택 준비 표식 `noBackupFilesDir/widget_choices_ready` | `WidgetChoices.prepare` | 준비했다는 표시 | 없음 | 넣지 않음 (`noBackupFilesDir`) |
| 위젯마다 Glance 상태 `person_choice` | `WidgetChoices` | 대상자 id 또는 '전체' | 없음 | 넣지 않음 |
| 위젯마다 Glance 상태 `refresh_at` | `DidITakeWidget` | 다시 그린 시각 | 없음 | 넣지 않음 |
| 위젯마다 Glance 상태 `message`·`message_at` | `RecordFromWidget` | 마지막 탭의 안내 문구(항목 이름이 들어감)와 시각 | **있음** | 넣지 않음 |

- 마지막 탭의 안내 문구는 10초만 보이지만(`MESSAGE_TTL_MS`) 파일에서는 다음 탭이 덮거나 지울 때까지, 또는 위젯을 치울 때까지 남음. 앱 전용 저장 공간이라 다른 앱은 읽지 못하고, 같은 이름이 기록 DB에 이미 있음. 백업 규칙(`data_extraction_rules.xml`, `backup_rules.xml`)은 기록 DB와 짝 파일만 넣어 이 파일은 백업·기기 간 이전에 가지 않음
- 그래서 고치지 않음. 바꾸려면 10초 뒤 지우는 일을 따로 걸어야 하는데(위젯 세션이 끝난 뒤에도) 얻는 것이 없음

## 7. 확인하지 못한 것

- 실기기에서 `adb logcat`으로 실제로 찍히는 줄: 소스의 로그 호출만 봄. 시스템이 남기는 줄(알림 내용 등)은 앱이 고를 수 없음. 알림과 잠금 화면의 이름 표시는 TC-013(REQ-047)
- 라이브러리가 던지는 예외의 메시지(5절)와 라이브러리 안의 로그
- Glance가 위젯에 만드는 PendingIntent의 플래그(3.2·3.3절)
- 다른 앱 흉내(예: `adb shell am broadcast`로 위젯 공급자에 Glance의 동작을 보내 보기): 하지 않음. 3절의 '다른 앱이 할 수 있는 일'은 병합 매니페스트와 앱·Glance의 동작에서 판단함
- R8(코드 축소)을 켠 빌드: 지금 릴리즈 변형은 축소하지 않음(`isMinifyEnabled = false`, TASK-046에서 정함). 켜도 의존성은 같고 클래스만 줄어듦
- Play Console 데이터 보안 양식과 권한 신고: TASK-036. 개인정보처리방침: TASK-035

## 8. 결정할 것

- WorkManager가 넣는 `ACCESS_NETWORK_STATE`·`FOREGROUND_SERVICE`·`WAKE_LOCK`(검토 B8): 그대로 둠(Claude 판단). 뺄 수는 있지만(`tools:node="remove"`) 위젯과 알람이 쓰는 WorkManager 작업을 실기기에서 다시 봐야 함. `INTERNET`이 없어 보낼 수 없으므로 얻는 것은 Play 권한 목록의 '네트워크 연결 보기' 같은 줄이 빠지는 것뿐임
