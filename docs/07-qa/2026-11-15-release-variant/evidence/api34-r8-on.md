# 릴리즈 변형 확인 결과 (API 34, 코드 축소 켬)

[실행 43685794993](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43685794993)(수동 실행, `minify` 켬)의 `release-check-api34` 아티팩트 `report.md`를 그대로 옮김. 머리글만 한 단계 낮춤.

## 릴리즈 변형 확인 API 34 (코드 축소 켬)

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 대조: 시험 전송이 백업을 받음 | com.android.providers.settings 백업 성공 | Backup finished with result: Success | 통과 |
| 릴리즈 변형 N 설치 | debuggable 아님, run-as 거부, 백업 허용 | versionCode 235, flags [HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP], run-as: run-as: package not debuggable: com.judangbang.pillbadeum | 통과 |
| BUG-004 첫 위젯 전 RescheduleReceiver | 앱을 연 뒤 켜져 있음 (첫 위젯 때 PACKAGE_CHANGED 없음) | 켜짐 | 통과 |
| 위젯 탭 기록 (릴리즈 N) | 활성 판정 1, source WIDGET, device_id·표식 있음 | 위젯에 TestA 보임; 활성 판정 1, source ['WIDGET'], device_id 92dbed, 표식 92dbed | 통과 |
| B-1 릴리즈 변형 백업에 든 파일 (암호화 있음) | 앱 백업이 성공하고 기록 DB와 짝 파일만 | Package com.judangbang.pillbadeum with result: Success; 백업 안: _manifest, _meta, db/diditake.db-wal, db/diditake.db-wal, db/diditake.db | 통과 |
| RR-26 백업 → 지우고 다시 설치 (릴리즈 N) | 기록 1건(source WIDGET)이 돌아옴. 복원 확인 전이면 device_id 그대로·표식 없음, 시스템이 위젯 방송으로 앱을 먼저 깨웠으면 새 device_id·restored_from = 옛 값 | 활성 판정 1, device_id 92dbed, restored_from -, pending -, 표식 -, source ['WIDGET'] | 통과 |
| 복원 뒤 첫 실행 (릴리즈 N) | 복원 안내, 새 device_id = 표식, restored_from = 옛 device_id | 안내 보임; 활성 판정 1, device_id 8cea1c, restored_from 92dbed, pending 1, 표식 8cea1c | 통과 |
| 복원 뒤 위젯 (보충 R-3) | 머리글 아래 '복원됨 · … · 앱에서 확인' 줄 | 복원됨 · 마지막 기록 11/15 15:40 · 앱에서 확인 | 통과 |
| [확인했어요] 뒤 위젯 (RR-33 위젯 몫) | 위젯에 TestA가 보이고 복원 줄이 사라짐 | 위젯 TestA 보임, 복원 줄 없음 | 통과 |
| 다음 빌드 N+1을 덮어 설치 | 버전 번호가 커짐, 기록·device_id·표식 그대로 | versionCode 235 → 236; 활성 판정 1, device_id 8cea1c, restored_from 92dbed, pending 0, 표식 8cea1c | 통과 |
| 덮어 설치 뒤 위젯 탭 (N+1) | 기록 2건, 둘 다 source WIDGET, 새 기록의 device_id = 이 폰 값, 옛 기록은 옛 값 | 위젯에 TestB 보임; 활성 판정 2, source ['WIDGET', 'WIDGET'], device_id ['92dbed', '8cea1c'], 이 폰 8cea1c | 통과 |
| 덮어 설치 뒤 앱 | 복원 안내 없음 | 안내 없음 | 통과 |
| 알림 켠 항목 셋 (릴리즈, TASK-023·024) | 앱에서 [알림 받기]를 켜고 저장: 규칙 셋 다 켜짐·08:00, 다음 칸의 정확 알람 1개 | 양식 '첫 알림 시각, 8시'; 규칙 [('PillA', 1, '08:00'), ('PillB', 1, '08:00'), ('PillC', 1, '08:00')]; 알람 1개: 2026-11-16 08:00:00, window=0, exactAllowReason=permission | 통과 |
| 알림 울림 (릴리즈, TASK-024·025) | 세 항목 알림 '<이름> 먹어~!'에 [먹었어]·[30분 뒤]·[오늘 건너뜀] | 4개: PillA 먹어~! [dose_reminders, SECRET, 버튼 3]; PillB 먹어~! [dose_reminders, SECRET, 버튼 3]; PillC 먹어~! [dose_reminders, SECRET, 버튼 3]; 복용 시간이에요 [app_notices, PUBLIC, 버튼 0]; 버튼 ['먹었어 · 30분 뒤 · 오늘 건너뜀'] | 통과 |
| 알림 버튼 (릴리즈, TC-011·TC-012) | PillA 복용(TAKEN, NOTIFICATION), PillB 건너뜀(SKIPPED, NOTIFICATION), PillC 미룸 1건(누른 때부터 30분 뒤), 세 알림과 잠금 화면 알림이 사라짐, 다음 알람 = 미룬 시각 | 판정 {'PillA': ('TAKEN', 'NOTIFICATION'), 'PillB': ('SKIPPED', 'NOTIFICATION')}; 미룸 ['PillC'] (누른 때부터 30.1분); 남은 알림 0개; 알람 1개: 2026-11-16 08:30:37, window=0, exactAllowReason=permission | 통과 |

- 기기: API 34, sdk_gphone64_x86_64, 시간대 Asia/Seoul, APK release-235.apk → release-236.apk, 커밋 93751eea8228c4292c71115fda312bd1fd66e3b9
- 빌드 (코드 축소 켬): release-235.apk 7.32 MB, dex 1개 (풀면 3.22 MB); release-236.apk 7.32 MB, dex 1개 (풀면 3.22 MB)
- 위젯 놓기 (n): 끈 곳 com.google.android.apps.nexuslauncher:id/widget_preview_container (59, 930, 1021, 1516)
- 위젯 놓기 (n-again): 끈 곳 com.google.android.apps.nexuslauncher:id/widget_preview_container (59, 930, 1021, 1516)
- 6단계 준비: 알림 권한은 `pm grant`로, 정확 알람은 `appops`로 허용함 ('SCHEDULE_EXACT_ALARM: allow'). 권한 창과 정확 알람 화면은 디버그 빌드의 화면 확인에서 봄
- 6단계: 시계를 알람 1분 전으로 옮김 → 2026-11-16 07:59 KST (알람 2026-11-16 08:00:00, window=0, exactAllowReason=permission)
- 7단계 앱 콜드 스타트 (`am start -W`의 TotalTime, 5회, 판정 아님): [1115, 722, 928, 1054, 782] ms, 중앙값 928 ms
