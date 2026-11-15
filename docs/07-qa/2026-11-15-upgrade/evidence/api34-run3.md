## 옛 빌드에서 업데이트 확인 API 34

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 0. 준비: 서울 시간대, 오늘 08:30, 옛 빌드 설치 | 시간대·시계가 바뀌고 옛 빌드가 깔림 | 시간대 Asia/Seoul, 시계 2026-11-15 08:30 KST, 옛 빌드 versionCode 111 (8c50d47) | 통과 |
| 1. 옛 빌드: 대상자 둘·항목·위젯·위젯 탭 | 나 Vitamin과 Mom BPpill(옛 항목 화면의 [+ 사람 추가]), 고르기 화면 없이 위젯이 놓이고 기록 1건, 위젯에 저장된 대상자 선택 없음 | 항목 ['나 Vitamin', 'Mom BPpill'], 위젯 id 2, 고르기 화면 없음, 옛 위젯 나 Vitamin, 미완료 / Mom BPpill, 미완료, 위젯에서 탭: 활성 판정 1, source ['WIDGET'], 저장된 선택 ['-'] | 통과 |
| 2. 지금 빌드를 덮어 설치 (adb install -r) | 버전 번호가 커지고 기록과 설치 표식이 그대로. 앱을 열기 전 위젯이 나 Vitamin·Mom BPpill을 보이고 다시 설정을 묻지 않음(RR-74) | versionCode 111 → 241; 활성 판정 1, source ['WIDGET'], 설치 표식 그대로 01a1dc; 앱을 열기 전 위젯(새 빌드가 그림): 나 Vitamin, 복용함, 오늘 08:32 / Mom BPpill, 미완료, 기록 없음, 다시 설정 안내 없음 | 통과 |
| 3. 지금 빌드 첫 실행 | 첫 실행 안내가 한 번 뜸. 업데이트 뒤 앱이 위젯 하나를 '전체'(*)로 한 번 채움(앱을 여는 것보다 먼저일 수 있음), 위젯에 두 사람 항목 | 첫 실행(08:32:42) 안내 뜸([건너뛰기]), 업데이트 뒤 앱 로그 한 번 08:32:33.451 'widget choices ready: 1 filled with everyone (install state Normal)'(첫 실행 전), 저장된 선택 ['*'], 위젯 나 Vitamin, 복용함, 오늘 08:32 / Mom BPpill, 미완료, 기록 없음 | 통과 |
| 4. 업데이트 전에 놓은 위젯에서 Mom BPpill 탭 | 기록 2건(둘 다 WIDGET), 위젯이 'Mom BPpill, 복용함, …'을 보임 | 업데이트 전에 놓은 위젯(id 2): 활성 판정 2, source ['WIDGET', 'WIDGET']; 나 Vitamin, 복용함, 오늘 08:32 / Mom BPpill, 복용함, 오늘 08:33 | 통과 |
| 5. 대조: 선택을 지운 뒤 | 같은 위젯이 '위젯을 다시 설정해 주세요'를 보이고, 앱이 다시 채우지 않음(설치마다 한 번) | 지운 상태 파일 ['appWidget-2.preferences_pb']; 위젯 '위젯을 다시 설정해 주세요', 다시 채운 앱 로그 없음, 저장된 선택 ['-'] | 통과 |

- 위젯 놓기 (old-widget): 끈 곳 com.google.android.apps.nexuslauncher:id/widget_preview_container (59, 930, 1021, 1516)
- 앱을 열기 전(덮어 설치 08:32:32): 위젯 새 빌드가 그림, 새 빌드 첫 프로세스 08:32:32.749 broadcast {boundary.DayBoundaryReceiver}, 채움 로그 08:32:33.451 widget choices ready: 1 filled with everyone (install state Normal)
- 기기: API 34, sdk_gphone64_x86_64, 옛 빌드 old-111.apk (8c50d4735ab3624d1e4689a18d55fe4bdfc86808) → 지금 빌드 new-241.apk (cd33c1be7618c96545d74ac48e227f59cd3ccc4b (PR 머리 47aefbd482aa7fa520cfaf2e491bddc37b0aa070))
