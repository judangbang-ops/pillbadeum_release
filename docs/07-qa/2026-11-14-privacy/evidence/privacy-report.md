### 개인정보 점검 (TASK-031)

릴리즈 변형의 병합 매니페스트(패키지 `com.judangbang.pillbadeum`), 병합 보고서, `releaseRuntimeClasspath` 의존성, 앱 소스의 로그 호출과 PendingIntent를 봄.

#### 권한과 출처 (REQ-057, 검토 B8)

| 권한 | maxSdk·보호 수준 | 넣은 곳 |
| --- | --- | --- |
| `android.permission.RECEIVE_BOOT_COMPLETED` | - | androidApp/src/main/AndroidManifest.xml, androidx.work:work-runtime:2.7.1 (MERGED) |
| `android.permission.POST_NOTIFICATIONS` | - | androidApp/src/main/AndroidManifest.xml |
| `android.permission.SCHEDULE_EXACT_ALARM` | - | androidApp/src/main/AndroidManifest.xml |
| `android.permission.WAKE_LOCK` | - | androidx.work:work-runtime:2.7.1 |
| `android.permission.ACCESS_NETWORK_STATE` | - | androidx.work:work-runtime:2.7.1 |
| `android.permission.FOREGROUND_SERVICE` | - | androidx.work:work-runtime:2.7.1 |
| `com.judangbang.pillbadeum.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | - | androidx.core:core:1.18.0 |
| `(정의) com.judangbang.pillbadeum.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature | androidx.core:core:1.18.0 |

#### 다른 앱이 부를 수 있는 구성요소 (exported)

| 종류 | 이름 | exported | 보호 권한 | 받는 action | 넣은 곳 |
| --- | --- | --- | --- | --- | --- |
| activity | `com.judangbang.pillbadeum.MainActivity` | true | 없음 | android.intent.action.MAIN | androidApp/src/main/AndroidManifest.xml |
| activity | `com.judangbang.pillbadeum.widget.WidgetPickActivity` | true | 없음 | android.appwidget.action.APPWIDGET_CONFIGURE | androidApp/src/main/AndroidManifest.xml |
| service | `androidx.glance.appwidget.GlanceRemoteViewsService` | true | android.permission.BIND_REMOTEVIEWS | - | androidx.glance:glance-appwidget:1.2.0 |
| service | `androidx.work.impl.background.systemjob.SystemJobService` | true | android.permission.BIND_JOB_SERVICE | - | androidx.work:work-runtime:2.7.1 |
| receiver | `com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver` | true | 없음 | android.appwidget.action.APPWIDGET_UPDATE | androidApp/src/main/AndroidManifest.xml |
| receiver | `androidx.work.impl.diagnostics.DiagnosticsReceiver` | true | android.permission.DUMP | androidx.work.diagnostics.REQUEST_DIAGNOSTICS | androidx.work:work-runtime:2.7.1 |
| receiver | `androidx.profileinstaller.ProfileInstallReceiver` | true | android.permission.DUMP | androidx.profileinstaller.action.BENCHMARK_OPERATION, androidx.profileinstaller.action.INSTALL_PROFILE, androidx.profileinstaller.action.SAVE_PROFILE, androidx.profileinstaller.action.SKIP_FILE | androidx.profileinstaller:profileinstaller:1.4.0 |

#### 앱의 구성요소와 PendingIntent (REQ-081)

| 종류 | 이름 | exported | 받는 것 |
| --- | --- | --- | --- |
| activity | `com.judangbang.pillbadeum.MainActivity` | true | - |
| activity | `com.judangbang.pillbadeum.widget.WidgetPickActivity` | true | - |
| receiver | `com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver` | true | - |
| receiver | `com.judangbang.pillbadeum.widget.WidgetPinnedReceiver` | false | - |
| receiver | `com.judangbang.pillbadeum.boundary.DayBoundaryReceiver` | false | - |
| receiver | `com.judangbang.pillbadeum.reminder.ReminderReceiver` | false | 알림 버튼·밀기·복용 알람 |

앱 소스의 PendingIntent 5개.

| 곳 | 만드는 함수 | 인텐트 | 플래그 | 판단 |
| --- | --- | --- | --- | --- |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/boundary/DayBoundaryAlarm.kt:31` | `getBroadcast` | `intent` | `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:99` | `getBroadcast` | `Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtras(extras` | `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderNotifications.kt:297` | `getBroadcast` | `intent` | `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderNotifications.kt:304` | `getActivity` | `MainActivity.homeIntent(context)` | `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` | 바꿀 수 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPinnedReceiver.kt:59` | `getBroadcast` | `Intent(context, WidgetPinnedReceiver::class.java)` | `PendingIntent.FLAG_UPDATE_CURRENT or mutable` | 바꿀 수 있음: `requestPinAppWidget`의 성공 콜백. 런처가 새 위젯 번호(`EXTRA_APPWIDGET_ID`)를 더해야 해서 바꿀 수 있어야 함. 인텐트는 내보내지 않은 `WidgetPinnedReceiver`를 가리킴 |

#### 의존성 (REQ-059·083·090)

릴리즈 실행 클래스패스의 라이브러리 147개 (목록: `dependencies-releaseRuntimeClasspath.txt`). 분석·크래시 보고·광고·AI SDK: 없음. 네트워크 클라이언트: 없음.

#### 로그 호출 (REQ-058)

`androidApp/src/main`, `shared/src/commonMain`, `shared/src/androidMain`의 로그 호출 39개. 글에 넣는 값이 이름(항목·대상자)이나 기록을 담을 수 있으면 실패로 봄. 예외를 함께 남기는 호출은 '예외' 칸에 표시함.

| 곳 | 호출 | 글 | 넣는 값 | 예외 | 판단 |
| --- | --- | --- | --- | --- | --- |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:49` | `Log.w` | `"own package not found"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:69` | `Log.w` | `"screen lock state not readable"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:79` | `Log.i` | `"no browser for the privacy policy"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:98` | `Log.w` | `"widget pin support not readable"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:115` | `Log.w` | `"widget pin request refused"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:146` | `Log.w` | `"no notification permission window"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:196` | `Log.i` | `"no screen for ${intent.action}"` | `${intent.action}` | 있음 | 이름 없음 (`${intent.action}`: 수·종류·번호) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:199` | `Log.w` | `"screen refused: ${intent.action}"` | `${intent.action}` | 있음 | 이름 없음 (`${intent.action}`: 수·종류·번호) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AndroidPhone.kt:207` | `Log.w` | `"reminder access not readable"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AppContainer.kt:56` | `Log.e` | `"widget refresh after a font size change failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AppContainer.kt:101` | `Log.e` | `"filling the choice of earlier widgets failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AppContainer.kt:121` | `Log.i` | `"restore detected: refreshing widgets"` | - | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/AppContainer.kt:126` | `Log.e` | `"after-open work failed (restored=$restored)"` | `$restored` | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/MainActivity.kt:51` | `Log.e` | `"day boundary alarm not set"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/boundary/DayBoundaryAlarm.kt:39` | `Log.i` | `"next day boundary refresh at $at"` | `$at` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/boundary/DayBoundaryAlarm.kt:64` | `Log.e` | `"boundary refresh failed ($action)"` | `$action` | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:67` | `Log.w` | `"collect failed, ringing again in $delay (retry ${attempt + 1})"` | `$delay`, `${attempt + 1}` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:78` | `Log.i` | `"no reminder alarm needed"` | - | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:86` | `Log.i` | `"exact reminder alarm at $at"` | `$at` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:90` | `Log.w` | `"exact reminder alarm refused, setting an inexact one"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderAlarm.kt:95` | `Log.i` | `"inexact reminder alarm at $at, and one within $WINDOW of it"` | `$at`, `$WINDOW` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/ReminderReceiver.kt:36` | `Log.e` | `"reminder broadcast failed ($action)"` | `$action` | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/Reminders.kt:106` | `Log.i` | `"dose reminders are off: ${notices.size} reminder(s) not shown"` | `${notices.size}` | - | 이름 없음 (`${notices.size}`: 수·종류·번호) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/Reminders.kt:113` | `Log.i` | `"${contents.size} reminder(s) shown"` | `${contents.size}` | - | 이름 없음 (`${contents.size}`: 수·종류·번호) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/Reminders.kt:150` | `Log.w` | `"notification record failed: $result"` | `$result` | - | 이름 없음 (`$result`: 이 분기의 `RecordResult`는 `Locked`(값 없음)나 `Error`(메시지에 항목 번호만, `RecordDose`·`JudgmentSupport`)) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/Reminders.kt:167` | `Log.w` | `"snooze failed: ${result.message}"` | `${result.message}` | - | 이름 없음 (`${result.message}`: `SnoozeResult.Error`의 메시지는 `RecordResult.Error`의 것(항목 번호만). `lookUpWindow`은 Gone·Stale·Error로만 거절해(`JudgmentSupport.kt`) 'unexpected lookup result' 분기에는 지금 오지 않음) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/reminder/Reminders.kt:181` | `Log.e` | `"$name failed"` | `$name` | 있음 | 이름 없음 (`$name`: 단계 이름(`step`의 인자: replan 등). 항목·대상자 이름이 아님) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/DidITakeWidget.kt:159` | `Log.e` | `"widget model load failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/DidITakeWidget.kt:186` | `Log.i` | `"onRestored: old ${oldWidgetIds.joinToString()} -> new ${newWidgetIds.joinToString()}"` | `${oldWidgetIds.joinToString()}`, `${newWidgetIds.joinToString()}` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/DidITakeWidget.kt:209` | `Log.e` | `"record failed: ${result.message}"` | `${result.message}` | - | 이름 없음 (`${result.message}`: `RecordResult.Error`의 메시지(항목 번호만)) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/RescheduleReceiverKeeper.kt:50` | `Log.e` | `"could not enqueue the keep-alive work"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetChoices.kt:68` | `Log.i` | `"widget choices ready: $filled filled with everyone (install state ${state::class.simpleName})"` | `$filled`, `${state::class.simpleName}` | - | 이름 없음 (`${state::class.simpleName}`: 수·종류·번호) |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPickActivity.kt:54` | `Log.w` | `"not one of this app's widgets: $appWidgetId"` | `$appWidgetId` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPickActivity.kt:70` | `Log.e` | `"saving the widget's choice failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPickActivity.kt:78` | `Log.e` | `"drawing the widget after its choice failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPickActivity.kt:90` | `Log.e` | `"reading the people failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPinnedReceiver.kt:30` | `Log.i` | `"widget $appWidgetId placed from the intro: ${choice?.let { it::class.simpleName } ?: "no choice"}"` | `$appWidgetId`, `${choice?.let { it::class.simpleName } ?: "no choice"}` | - | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPinnedReceiver.kt:35` | `Log.e` | `"giving the widget placed from the intro its choice failed"` | - | 있음 | 이름 없음 |
| `androidApp/src/main/kotlin/com/judangbang/pillbadeum/widget/WidgetPinnedReceiver.kt:42` | `Log.e` | `"drawing the widget placed from the intro failed"` | - | 있음 | 이름 없음 |

#### 결과

- 모두 통과
