### 위젯 스크롤 기술 시험 API 26

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| A 위젯 그려짐 (grid, 276×220dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.0 id 3; 목록 GridView; 보인 항목 12 | 통과 |
| A 가로 4개 | 첫 줄에 Test1~Test4가 나란히 | Test1, Test2, Test3, Test4 | 통과 |
| A 스크롤로 Test10에 닿음 | 목록만 스크롤되어 Test10가 다 보임 | 보임 | 통과 |
| A 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| A 목록 안 버튼 탭 → 기록 | Test10의 활성 판정, source = WIDGET | WIDGET | 통과 |
| A 탭 뒤 다시 그림 | Test10 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test10, 오늘 17:17 기록됨 | 통과 |
| B 위젯 그려짐 (grid, 250×110dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 250x110dp font 1.0 id 4; 목록 GridView; 보인 항목 4 | 통과 |
| B 스크롤로 Test7에 닿음 | 목록만 스크롤되어 Test7가 63% 이상 보임 | 보임 | 통과 |
| B 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| B 목록 안 버튼 탭 → 기록 | Test7의 활성 판정, source = WIDGET | WIDGET | 통과 |
| B 탭 뒤 다시 그림 | Test7 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test7, 오늘 17:18 기록됨 | 통과 |
| C 위젯 그려짐 (list, 276×220dp, 글자 130%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.3 id 6; 목록 ListView; 보인 항목 4 | 통과 |
| C 스크롤로 Test5에 닿음 | 목록만 스크롤되어 Test5가 다 보임 | 보임 | 통과 |
| C 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| C 목록 안 버튼 탭 → 기록 | Test5의 활성 판정, source = WIDGET | WIDGET | 통과 |
| C 탭 뒤 다시 그림 | Test5 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test5, 오늘 17:18 기록됨 | 통과 |
| D 위젯 그려짐 (list, 250×110dp, 글자 130%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 250x110dp font 1.3 id 7; 목록 ListView; 보인 항목 1 | 통과 |
| D 스크롤로 Test4에 닿음 | 목록만 스크롤되어 Test4가 다 보임 | 보임 | 통과 |
| D 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| D 목록 안 버튼 탭 → 기록 | Test4의 활성 판정, source = WIDGET | WIDGET | 통과 |
| D 탭 뒤 다시 그림 | Test4 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test4, 오늘 17:19 기록됨 | 통과 |
| E 위젯 그려짐 (grid, 276×220dp, 글자 130%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.3 id 8; 목록 GridView; 보인 항목 8 | 통과 |
| R 위젯 그려짐 (rows, 276×220dp, 글자 100%) | 호스트 준비, 목록(GridView/ListView)과 항목이 보임 | probe-ready .debug.ScrollProbeWidgetReceiver 276x220dp font 1.0 id 10; 목록 ListView; 보인 항목 12 | 통과 |
| R 가로 4개 | 첫 줄에 Test1~Test4가 나란히 | Test1, Test2, Test3, Test4 | 통과 |
| R 스크롤로 Test12에 닿음 | 목록만 스크롤되어 Test12가 다 보임 | 보임 | 통과 |
| R 머리글 고정 | 스크롤 뒤에도 목록 바로 위 머리글(DidITake)이 보임 | 보임 | 통과 |
| R 목록 안 버튼 탭 → 기록 | Test12의 활성 판정, source = WIDGET | WIDGET | 통과 |
| R 탭 뒤 다시 그림 | Test12 버튼이 완료로 바뀜(TalkBack 문장에 '기록됨', 화면 밖이면 다시 스크롤해 봄) | 나 Test12, 오늘 17:20 기록됨 | 통과 |

- 기기: API 26, Android SDK built for x86_64, 디버그 APK androidApp-debug.apk, 커밋 6b5541384fbad99b17a85630b648a0b2e07e82c6 (PR 머리 36e36e73109b9f2d0052d14ada932f2fecb63c3b). 가장 큰 글자 = 130%
- appwidget grantbind: 출력 없음
- A-grid-276x220-font100: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 403, 754, 856), 높이 453px; 항목 한 칸의 온전한 높이 224px (px = dp × 2.75)
- A-grid-276x220-font100: 탭 뒤 다시 그릴 때 스크롤 위치 맨 위로 돌아감 (스크롤 219px → 0px; Test10 보임, 보이는 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8, Test9, Test10, Test11, Test12)
- A-grid-276x220-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- A-grid-276x220-font100: 탭 뒤 8초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- B-grid-250x110-font100: 스크롤 전 다 보인 항목 없음; 첫 줄 없음; 목록 영역 (93, 403, 683, 554), 높이 151px; 항목 한 칸의 온전한 높이 224px (px = dp × 2.75)
- B-grid-250x110-font100: 배치 관찰 — 목록 높이 55dp가 항목 한 칸 81dp보다 낮아 한 칸이 다 보일 수 없음. 항목 높이의 63% 이상 보이면 닿은 것으로 보고, 보이는 부분을 누름
- B-grid-250x110-font100: 탭 뒤 다시 그릴 때 스크롤 위치 맨 위로 돌아감 (Test7 화면 밖, 보이는 항목 Test1, Test2, Test3, Test4)
- B-grid-250x110-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- B-grid-250x110-font100: Test7가 화면 밖이라 다시 스크롤해 다시 그린 문장을 읽음
- C-list-276x220-font130: 스크롤 전 다 보인 항목 Test1, Test2, Test3; 첫 줄 Test1; 목록 영역 (93, 417, 754, 856), 높이 439px; 항목 한 칸의 온전한 높이 137px (px = dp × 2.75)
- C-list-276x220-font130: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (스크롤 387px → 387px)
- C-list-276x220-font130: 탭 동안 시험 호스트의 onStop/onStart: 없음
- C-list-276x220-font130: 탭 뒤 8초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- C-list-276x220-font130: 기록된 Test5를 다시 누름 → 안내 줄 보임 ('Test5: 이미 기록됨 (17:18). 변경 없음'); 스크롤 387px → 0px, 보이는 항목 Test1, Test2, Test3, Test5의 활성 판정 1건(1건이어야 함)
- D-list-250x110-font130: 스크롤 전 다 보인 항목 Test1; 첫 줄 Test1; 목록 영역 (93, 417, 683, 554), 높이 137px; 항목 한 칸의 온전한 높이 137px (px = dp × 2.75)
- D-list-250x110-font130: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (스크롤 410px → 410px)
- D-list-250x110-font130: 탭 동안 시험 호스트의 onStop/onStart: 없음
- D-list-250x110-font130: 탭 뒤 8초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- E-grid-276x220-font130: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 417, 754, 856), 높이 439px; 항목 한 칸의 온전한 높이 248px (px = dp × 2.75)
- R-rows-276x220-font100: 스크롤 전 다 보인 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8; 첫 줄 Test1, Test2, Test3, Test4; 목록 영역 (93, 403, 754, 856), 높이 453px; 항목 한 칸의 온전한 높이 224px (px = dp × 2.75)
- R-rows-276x220-font100: 탭 뒤 다시 그릴 때 스크롤 위치 그대로 (스크롤 219px → 219px)
- R-rows-276x220-font100: 탭 동안 시험 호스트의 onStop/onStart: 없음
- R-rows-276x220-font100: 탭 뒤 7초 안에 버튼 문장이 '기록됨'으로 바뀐 것을 확인(덤프 간격 포함)
- R-rows-276x220-font100: 기록된 Test12를 다시 누름 → 안내 줄 보임 ('Test12: 이미 기록됨 (17:20). 변경 없음'); 스크롤 219px → 0px, 보이는 항목 Test1, Test2, Test3, Test4, Test5, Test6, Test7, Test8, Test12의 활성 판정 1건(1건이어야 함)
- P-grid-276x220-font100: 시험 구성요소를 켜서 우리 패키지에 PACKAGE_CHANGED를 보냄 → 10초 뒤 위젯이 로딩 화면으로 바뀌고, 13초 뒤 항목이 다시 보임
- P-grid-276x220-font100: 로그 순서 — 08:20:47.249 시험 구성요소 enabled
- L 런처 경우: API 31 미만 이미지의 런처는 위젯 목록 화면이 달라 하지 않음
