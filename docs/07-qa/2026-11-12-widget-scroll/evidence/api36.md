### 위젯 스크롤 기술 시험 API 36

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| A 위젯 그려짐 (grid, 276×220dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.0 id 2; 목록 GridView; 보인 항목 12 | 통과 |
| A 가로 4개 | 첫 줄에 Test1~Test4가 나란히 | Test1, Test2, Test3, Test4 | 통과 |
| A 스크롤로 Test10에 닿음 | 목록만 스크롤되어 Test10가 다 보임 | 보임 | 통과 |
| A 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| A 목록 안 버튼 탭 → 기록 | Test10의 활성 판정, source = WIDGET | WIDGET | 통과 |
| A 탭 뒤 다시 그림 | Test10 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test10, 오늘 17:18 기록됨 | 통과 |
| B 위젯 그려짐 (grid, 250×110dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 250x110dp font 1.0 id 3; 목록 GridView; 보인 항목 4 | 통과 |
| B 스크롤로 Test7에 닿음 | 목록만 스크롤되어 Test7가 63% 이상 보임 | 보임 | 통과 |
| B 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| B 목록 안 버튼 탭 → 기록 | Test7의 활성 판정, source = WIDGET | WIDGET | 통과 |
| B 탭 뒤 다시 그림 | Test7 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test7, 오늘 17:19 기록됨 | 통과 |
| C 위젯 그려짐 (list, 276×220dp, 글자 200%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 2.0 id 5; 목록 ListView; 보인 항목 2 | 통과 |
| C 스크롤로 Test5에 닿음 | 목록만 스크롤되어 Test5가 다 보임 | 보임 | 통과 |
| C 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| C 목록 안 버튼 탭 → 기록 | Test5의 활성 판정, source = WIDGET | WIDGET | 통과 |
| C 탭 뒤 다시 그림 | Test5 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test5, 오늘 17:20 기록됨 | 통과 |
| D 위젯 그려짐 (list, 250×110dp, 글자 200%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 250x110dp font 2.0 id 6; 목록 ListView; 보인 항목 1 | 통과 |
| D 스크롤로 Test4에 닿음 | 목록만 스크롤되어 Test4가 48% 이상 보임 | 보임 | 통과 |
| D 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| D 목록 안 버튼 탭 → 기록 | Test4의 활성 판정, source = WIDGET | WIDGET | 통과 |
| D 탭 뒤 다시 그림 | Test4 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test4, 오늘 17:21 기록됨 | 통과 |
| E 위젯 그려짐 (grid, 276×220dp, 글자 200%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 2.0 id 7; 목록 GridView; 보인 항목 8 | 통과 |
| R 위젯 그려짐 (rows, 276×220dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.0 id 9; 목록 ListView; 보인 항목 12 | 통과 |
| R 가로 4개 | 첫 줄에 Test1~Test4가 나란히 | Test1, Test2, Test3, Test4 | 통과 |
| R 스크롤로 Test12에 닿음 | 목록만 스크롤되어 Test12가 다 보임 | 보임 | 통과 |
| R 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| R 목록 안 버튼 탭 → 기록 | Test12의 활성 판정, source = WIDGET | WIDGET | 통과 |
| R 탭 뒤 다시 그림 | Test12 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test12, 오늘 17:21 기록됨 | 통과 |
| L 스크롤로 Test11에 닿음 | 목록만 스크롤되어 Test11가 다 보임 | 보임 | 통과 |
| L 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| L 목록 안 버튼 탭 → 기록 | Test11의 활성 판정, source = WIDGET | WIDGET | 통과 |
| L 탭 뒤 다시 그림 | Test11 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test11, 오늘 17:24 기록됨 | 통과 |

- 기기: API 36, sdk_gphone64_x86_64, 디버그 APK androidApp-debug.apk, 커밋 6b5541384fbad99b17a85630b648a0b2e07e82c6 (PR 머리 36e36e73109b9f2d0052d14ada932f2fecb63c3b). 가장 큰 글자 = 200%
- appwidget grantbind: 출력 없음
- A-grid-276x220-font100: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 336, 754, 790), 높이 454px; 항목 한 칸의 온전한 높이 226px (px = dp × 2.75)
- A-grid-276x220-font100: 탭 뒤 다시 그릴 때 스크롤 위치 맨 위로 돌아감 (스크롤 224px → 0px; Test10 보임, 보이는 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8, Test9, Test10, Test11, Test12)
- A-grid-276x220-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- A-grid-276x220-font100: 탭 뒤 13초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- B-grid-250x110-font100: 스크롤 전 다 보인 항목 없음; 첫 줄 없음; 목록 영역 (93, 336, 683, 488), 높이 152px; 항목 한 칸의 온전한 높이 226px (px = dp × 2.75)
- B-grid-250x110-font100: 배치 관찰 — 목록 높이 55dp가 항목 한 칸 82dp보다 낮아 한 칸이 다 보일 수 없음. 항목 높이의 63% 이상 보이면 닿은 것으로 보고, 보이는 부분을 누름
- B-grid-250x110-font100: 탭 뒤 다시 그릴 때 스크롤 위치 맨 위로 돌아감 (Test7 화면 밖, 보이는 항목 Test1, Test2, Test3, Test4)
- B-grid-250x110-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- B-grid-250x110-font100: Test7가 화면 밖이라 다시 스크롤해 다시 그린 문장을 읽음
- C-list-276x220-font200: 스크롤 전 다 보인 항목 Test1, Test2; 첫 줄 Test1; 목록 영역 (93, 381, 754, 790), 높이 409px; 항목 한 칸의 온전한 높이 205px (px = dp × 2.75)
- C-list-276x220-font200: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (스크롤 718px → 718px)
- C-list-276x220-font200: 탭 동안 시험 호스트의 onStop/onStart: 없음
- C-list-276x220-font200: 탭 뒤 9초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- C-list-276x220-font200: 기록된 Test5를 다시 누름 → 안내 줄 보임 ('Test5: 이미 기록됨 (17:20). 변경 없음'); 스크롤 718px → 0px, 보이는 항목 Test1, Test2, Test5의 활성 판정 1건(1건이어야 함)
- D-list-250x110-font200: 스크롤 전 다 보인 항목 없음; 첫 줄 없음; 목록 영역 (93, 381, 683, 488), 높이 107px; 항목 한 칸의 온전한 높이 205px (px = dp × 2.75)
- D-list-250x110-font200: 배치 관찰 — 목록 높이 39dp가 항목 한 칸 75dp보다 낮아 한 칸이 다 보일 수 없음. 항목 높이의 48% 이상 보이면 닿은 것으로 보고, 보이는 부분을 누름
- D-list-250x110-font200: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (보이는 항목 Test4, Test4 y 381 → 381)
- D-list-250x110-font200: 탭 동안 시험 호스트의 onStop/onStart: 없음
- D-list-250x110-font200: 탭 뒤 9초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- E-grid-276x220-font200: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 381, 754, 790), 높이 409px; 항목 한 칸의 온전한 높이 310px (px = dp × 2.75)
- R-rows-276x220-font100: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 336, 754, 790), 높이 454px; 항목 한 칸의 온전한 높이 226px (px = dp × 2.75)
- R-rows-276x220-font100: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (스크롤 224px → 224px)
- R-rows-276x220-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- R-rows-276x220-font100: 탭 뒤 9초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- R-rows-276x220-font100: 기록된 Test12를 다시 누름 → 안내 줄 보임 ('Test12: 이미 기록됨 (17:21). 변경 없음'); 스크롤 224px → 0px, 보이는 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8, Test12의 활성 판정 1건(1건이어야 함)
- P-grid-276x220-font100: 시험 구성요소를 켜서 우리 패키지에 PACKAGE_CHANGED를 보냄 → 20초 동안 위젯이 그대로 보임
- P-grid-276x220-font100: 로그 순서 — 08:22:34.921 시험 구성요소 enabled
- L-grid-launcher-font100: 놓기 전 WorkManager RescheduleReceiver 켜짐
- L-grid-launcher-font100: 로그 순서 — 08:23:36.675 위젯 11 묶임; 08:23:36.694 런처가 위젯 11 뷰를 만듦; 08:23:47.222 Glance 세션 appWidget-10 닫힘
- L-grid-launcher-font100: 놓고 2초 뒤 항목이 보임(위젯 목록 화면 조작까지 42초). 우리 패키지 PACKAGE_CHANGED 0번, 런처가 위젯 뷰를 만든 횟수 1번(놓을 때 1번 포함), 지금 RescheduleReceiver 켜짐
- L-grid-launcher-font100: 런처가 준 목록 영역 327×167dp
- L-grid-launcher-font100: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (91, 856, 989, 1315), 높이 459px; 항목 한 칸의 온전한 높이 226px (px = dp × 2.75)
- L-grid-launcher-font100: 탭 뒤 다시 그릴 때 스크롤 위치 맨 위로 돌아감 (스크롤 217px → 0px; Test11 보임, 보이는 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8, Test9, Test10, Test11, Test12)
- L-grid-launcher-font100: 탭 뒤 10초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- LP-grid-launcher-font100: 시험 구성요소를 켜서 우리 패키지에 PACKAGE_CHANGED를 보냄 → 20초 동안 위젯이 그대로 보임
- LP-grid-launcher-font100: 로그 순서 — 08:24:17.027 시험 구성요소 disabled; 08:24:18.064 런처가 위젯 11 뷰를 만듦; 08:24:27.747 Glance 세션 appWidget-11 닫힘
- [Wait]로 닫은 시스템 ANR 대화상자 1번: System UI isn't responding
