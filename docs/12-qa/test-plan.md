# 12 QA 시험 계획 (1차)

- 상태: 초안 (2026-11-12, Claude). CORTHEX 12를 이 저장소에서 새로 씀. 출시 판단(가기·안 가기)은 동희님이 함
- 입력: [03 흐름](../03-user-flow/README.md) FL-001~010, [04 명세](../04-functional-spec/spec.md)의 합격 기준 SPEC-001~043, [08 화면 상태](../05-ui/screens.md), [02 요구](../01-planning/requirements.md)의 비기능 요구
- 이전 QA 기록: [백업·복원 확인](../07-qa/2026-11-12-backup-restore/report.md), [릴리즈 변형 확인](../07-qa/2026-11-12-release-variant/report.md). 이 계획의 '지금' 열이 그 결과를 가리킴
- 시험 사례는 흐름과 합격 기준에서 씀. 코드에서 거꾸로 쓰지 않음

## 0. 읽는 법

- **TC**: `TC-001`부터. TC-001~043은 같은 번호의 SPEC 한 줄씩, TC-044~053은 흐름 FL-001~010, TC-054~077은 비기능 요구, TC-078~087은 화면 하나의 모든 상태
- **방법**
  - `jvm`: `./gradlew :shared:jvmTest`의 시험. CI가 PR마다 돌림
  - 둘러보기: [`scripts/emulator/explore.py`](../../scripts/emulator/explore.py)가 Actions 에뮬레이터(API 34)에서 앱을 사람처럼 쓰고 단계마다 캡처함. 이 앱은 브라우저 화면이 없어 CORTHEX의 브라우저 대리인(ego lite·Aside) 대신 이것이 흐름을 걷는 몫을 함
  - 에뮬레이터: 그 밖의 에뮬레이터 스크립트(`backup_restore_check.py`, `release_check.py`)
  - 실기기: 갤럭시 실기기. 동희님 폰은 아이폰이라 안드로이드 폰을 정해야 함(TASK-047·032·052)
  - 검사: CI의 권한 검사, 문서 검사, 코드 검사
- **지금**: 2026-11-12에 확인한 결과와 그 커밋. '앱에 없음'은 그 화면이나 기능이 아직 없다는 뜻이고, 괄호의 작업이 만듦
- API가 없어(06 뺌) 응답 코드별 사례는 없음. 대신 공통 함수의 결과(`RecordResult`의 Recorded·Already·Stale·Gone·Error 등)가 화면 상태로 가는지를 TC-078~087이 봄

## 1. 명세의 합격 기준

| TC | 확인할 것 | 근거 | 방법 | 지금 |
| --- | --- | --- | --- | --- |
| TC-001 | 첫 실행 준비: 주어진: 새 설치 → 하면: 앱을 엶 → 그러면: 대상자는 '나' 1명, 안내 없음 (RR-34) | SPEC-001, REQ-016, FL-001 | jvm: `ManageIntakesTest.ensureDefault_creates_me_once`, `InstallCheckTest.newInstall_*`·`rr34_*` | jvm 통과 (CI, `45faa8f`) |
| TC-002 | 첫 실행 안내: 주어진: 첫 실행 → 하면: 안내를 넘김 → 그러면: 다음 실행에는 안내가 없고, 지원하는 런처에서 [위젯 추가]가 고정 요청을 띄움 | SPEC-002, REQ-080, REQ-036, REQ-074, FL-001 | 에뮬레이터 캡처(화면 작업 PR) | 앱에 없음 (TASK-022) |
| TC-003 | 복용 항목 추가: 주어진: 이름 빈칸 → 하면: [저장] → 그러면: 저장 안 됨, "이름을 입력해 주세요", 다른 입력 유지. 주어진: 오후 3시 등록 → 하면: 오늘 08:00 복용 입력 → 그러면: 저장됨 (RR-41) | SPEC-003, REQ-012, REQ-013, FL-001 | jvm: `ManageIntakesTest.create_*`. 둘러보기 3-1~3-3 | jvm 통과 (CI, `45faa8f`). 둘러보기 6회차(`45faa8f`, 실행 43178960350) 3-1~3-3 통과 |
| TC-004 | 가족 대상자 추가: 주어진: 항목 추가 화면 → 하면: [+ 사람 추가]에 'Mom' → 그러면: 'Mom' 칩이 생기고 고를 수 있음 | SPEC-004, REQ-085, REQ-074, FL-001, FL-006 | jvm: `ManageIntakesTest.addPerson_rejects_blank_name_and_bad_color`. 둘러보기 2 | jvm 통과 (CI, `45faa8f`). 대상자 관리 화면은 없음 (TASK-021) |
| TC-005 | 위젯 놓기와 대상자 고르기: 주어진: 대상자 둘 → 하면: 위젯을 놓고 'Mom' → 그러면: 그 위젯은 Mom의 항목과 완료 수만 보임 | SPEC-005, REQ-025, REQ-018, REQ-080, FL-001, FL-009 | 에뮬레이터 캡처(화면 작업 PR) | 앱에 없음 (TASK-012) |
| TC-006 | 위젯 그리기: 주어진: 항목 3개, 1개 복용·1개 건너뜀 → 하면: 위젯을 봄 → 그러면: 머리글 `1 / 3 완료 · 건너뜀 1` (RR-35, RR-36, RR-37, RR-59, RR-61, RR-63) | SPEC-006, REQ-019, REQ-020, REQ-021, REQ-027, REQ-075, REQ-078, FL-002, FL-010 | jvm: `StatusBoardTest`. 둘러보기 5-1, 8-1·8-2 캡처 | jvm 통과 (CI, `45faa8f`). 건너뜀·넘침·주 1회 크게는 앱에 없음 (TASK-013·014·041) |
| TC-007 | 위젯 버튼으로 복용 기록: 주어진: 매일 약 미완료 → 하면: 08:40 위젯 탭 → 그러면: 복용 1건, 09:00·10:00 알림 없음, 떠 있던 알림 사라짐 (RR-3, RR-6, RR-47, RR-86) | SPEC-007, REQ-022, REQ-001, REQ-003, REQ-007, REQ-043, FL-002 | jvm: `RecordRulesCounterexampleTest.c01`·`c02`·`c03`. 둘러보기 5-2 | jvm 통과 (CI, `45faa8f`) |
| TC-008 | 판정이 있는 위젯 버튼: 주어진: 08:02 복용 → 하면: 다시 탭 → 그러면: 판정 그대로 1건, 메시지 (RR-1, RR-7) | SPEC-008, REQ-023, REQ-024, FL-002 | jvm: `RecordRulesCounterexampleTest.c07_skipThenWidgetTap_noChange`, `StatusBoardTest.record_messages_match_record_rules_table`. 둘러보기 5-3 | jvm 통과 (CI, `45faa8f`) |
| TC-009 | 기록하지 않는 위젯 버튼: 주어진: 03:59에 그린 위젯 → 하면: 04:01에 탭 → 그러면: 기록 0건, 새 날짜로 다시 그림, 메시지 (RR-13). 주어진: 앱 데이터 지움 → 하면: 남은 버튼 탭 → 그러면: 기록 0건, 항목 없는 위젯 | SPEC-009, REQ-004, REQ-005, REQ-092, REQ-079, FL-002 | jvm: `RecordRulesCounterexampleTest.c09`·`c13`·`c14`. 앱 데이터를 지운 뒤 남은 버튼(REQ-092)은 에뮬레이터 | jvm 통과 (CI, `45faa8f`). REQ-092는 아직 안 봄 |
| TC-010 | 반복 알림 띄우기: 주어진: 시작 08:00, 간격 60, 최대 3 → 하면: 판정 없이 둠 → 그러면: 08:00·09:00·10:00 (RR-46, RR-49, RR-50, RR-53, RR-54, RR-55, RR-79, RR-82, RR-83, RR-87) | SPEC-010, REQ-041, REQ-042, FL-003 | jvm: `PlanRemindersTest.rr46_*`, `ReminderSlotsTest`. 알림 표시는 에뮬레이터 | jvm 통과 (CI, `45faa8f`). 알림 표시는 앱에 없음 (TASK-024·025) |
| TC-011 | 알림에서 먹었어·오늘 건너뜀: 주어진: 08:00 알림 → 하면: [먹었어] → 그러면: 복용 1건, 알림 사라짐, 위젯 복용 (RR-21, RR-23) | SPEC-011, REQ-045, REQ-004, REQ-001, REQ-003, REQ-043, FL-003 | jvm: `RecordRulesCounterexampleTest.c23_notificationStaleKey_returnsStale`. 버튼은 에뮬레이터 | jvm 통과 (CI, `45faa8f`). 알림 버튼은 앱에 없음 (TASK-025) |
| TC-012 | 알림 30분 뒤: 주어진: 08:00 알림 → 하면: [30분 뒤] → 그러면: 08:30 울림, 09:00 칸은 그대로 (RR-51, RR-52, RR-84, RR-85) | SPEC-012, REQ-088, FL-003 | jvm: `PlanRemindersTest.rr51_*`·`rr52_*` | jvm 통과 (CI, `45faa8f`). 알림 버튼은 앱에 없음 (TASK-025) |
| TC-013 | 잠금 화면 알림: 주어진: 이름 표시 끔 → 하면: 잠금 화면에서 알림을 봄 → 그러면: "복용 시간이에요"만, 버튼 없음. 주어진: 켬 → 그러면: 이름과 [먹었어]가 보이고 잠금 해제 없이 기록 | SPEC-013, REQ-046, REQ-076, FL-003, FL-008 | 실기기(갤럭시): TASK-052 | 앱에 없음 (TASK-025) |
| TC-014 | 판정이 바뀐 뒤 알림 정리: 주어진: 08:40 기록 → 하면: 09:30 취소 → 그러면: 10:00 울림, 09:30에는 안 울림 (RR-22, RR-48, RR-80) | SPEC-014, REQ-043, REQ-044, FL-002, FL-003, FL-004 | jvm: `PlanRemindersTest.rr47_rr21_*`·`rr48_rr22_*` | jvm 통과 (CI, `45faa8f`). 떠 있는 알림 지우기는 앱에 없음 (TASK-025) |
| TC-015 | 앱 첫 화면: 주어진: 알림 켠 항목, 시스템 설정에서 앱 알림 끔 → 하면: 앱을 엶 → 그러면: 안내와 [설정 열기]가 보임 | SPEC-015, REQ-028, REQ-035, REQ-036, REQ-075, REQ-089, FL-004, FL-007 | jvm: `StatusBoardTest`. 둘러보기 1, 3-4 | jvm 통과 (CI, `45faa8f`) |
| TC-016 | 기록 취소: 주어진: 오늘 복용 → 하면: [기록 취소] → [확인] → 그러면: 활성 0건, 무효 행 1건, 미완료 (RR-6) | SPEC-016, REQ-029, REQ-006, FL-004 | jvm: `RecordRulesCounterexampleTest.c06_voidThenRerecord_keepsHistory`, `DoseRecordDaoTest.voidActive_*`. 둘러보기 6-1 | jvm 통과 (CI, `45faa8f`) |
| TC-017 | 시각 골라 기록·시각 고치기: 주어진: 어제 칸 빠짐 → 하면: 어제 21:00 고름 → 그러면: 어제 창에 복용 1건, 오늘은 그대로 (RR-39, RR-40, RR-42, RR-43, RR-44) | SPEC-017, REQ-030, REQ-031, REQ-032, FL-004, FL-005, SCR-007#locked | jvm: `RecordInWindowTest.rr39_*`~`rr44_*` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-018) |
| TC-018 | 앱에서 건너뜀: 주어진: 어제 칸 빠짐 → 하면: [건너뜀] → 그러면: 어제 기간키에 건너뜀 1건, 오늘 칸과 알림 그대로 (RR-38) | SPEC-018, REQ-033, FL-004 | jvm: `RecordInWindowTest.rr38_*` | jvm 통과 (CI, `45faa8f`). 버튼은 없음 (TASK-016·018) |
| TC-019 | 건너뜀을 복용으로: 주어진: 오늘 건너뜀 → 하면: [먹었어로 바꾸기] → 그러면: 건너뜀 무효 1건, 복용 활성 1건 (RR-7) | SPEC-019, REQ-034, FL-004 | jvm: `RecordRulesCounterexampleTest.c07_skipToTaken_viaChangeJudgment`, `RecordInWindowTest.a_skip_turns_into_a_dose_*` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-018) |
| TC-020 | 시계가 틀렸던 기록 경고: 주어진: 시계가 금요일로 틀린 채 탭 → 하면: 수요일로 되돌림 → 그러면: 앱의 금요일 기록에 경고 (RR-19, RR-64, RR-65, RR-66, RR-67, RR-88, RR-89) | SPEC-020, REQ-009, FL-004, FL-010 | jvm: `ClockFlagsTest` | jvm 통과 (CI, `45faa8f`). 첫 화면 경고는 없음 (TASK-016) |
| TC-021 | 시스템 뒤로 가기: 주어진: 항목 추가 화면에 이름 입력 → 하면: 뒤로 가기 → 그러면: 앱 첫 화면, 항목 없음, 앱은 열려 있음 | SPEC-021, REQ-094, FL-001 | 에뮬레이터: 둘러보기(항목 추가 화면에서 뒤로 가기) | **실패**: 앱이 닫힘 (BUG-001) |
| TC-022 | 지난 기록: 주어진: 이번 달 중간에 매일 → 주 1회(월) → 하면: 지난 기록 → 그러면: 바꾼 뒤 화~일은 빠짐이 아님 (RR-45, RR-77, RR-78) | SPEC-022, REQ-037, REQ-038, REQ-039, REQ-040, FL-005, SCR-008#empty | jvm: `LoadHistoryTest` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-019) |
| TC-023 | 항목 고치기와 주기 바꾸기: 주어진: 월요일 08:00 복용한 매일 약 → 하면: 같은 날 주 1회(월)로 → 그러면: 이번 주 창은 완료 (RR-10, RR-16) | SPEC-023, REQ-012, REQ-010, FL-006 | jvm: `ManageIntakesTest.edit_*`. 둘러보기 7-1 | jvm 통과 (CI, `45faa8f`) |
| TC-024 | 항목 보관: 주어진: 항목 보관 → 하면: 남은 위젯 버튼 탭 → 그러면: 기록 0건, 지난 기록은 남음 (RR-9, RR-14, RR-57) | SPEC-024, REQ-014, FL-006 | jvm: `ManageIntakesTest.archive_hides_item_but_keeps_records_and_rejects_widget_taps`. 둘러보기 7-2 | jvm 통과 (CI, `45faa8f`) |
| TC-025 | 항목 완전 삭제: 주어진: 항목과 판정 3건 → 하면: [완전 삭제] → [확인] → 그러면: 항목 0, 그 판정 0 | SPEC-025, REQ-015, FL-006 | jvm: `DeleteArchivedIntakeTest` | jvm 통과 (CI, `45faa8f`). 버튼은 없음 (TASK-020) |
| TC-026 | 대상자 이름·색 고치기: 주어진: 'Mom' → 하면: '엄마'로 → 그러면: 위젯과 앱의 배지가 '엄마' | SPEC-026, REQ-086, FL-006 | jvm: `ManagePeopleTest.update_edits_an_active_person_only` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-021) |
| TC-027 | 대상자 보관: 주어진: '나'와 'Mom' → 하면: '나' 보관 → 그러면: 보관됨, Mom만 남고 배지 숨김. 주어진: 'Mom'만 남음 → 그러면: 보관 버튼 없음 (RR-57) | SPEC-027, REQ-017, REQ-093, REQ-018, REQ-079, FL-006 | jvm: `ManagePeopleTest.archiving_the_last_person_is_refused`·`archived_persons_items_*` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-021) |
| TC-028 | 알림 켜기와 권한: 주어진: Android 14, 처음 → 하면: [알림 받기] → 그러면: 시스템 권한 창. 거부 → 첫 화면 안내 | SPEC-028, REQ-049, REQ-089, FL-007 | 에뮬레이터: 권한 거부·허용 (API 33 이상) | 앱에 없음 (TASK-023·026) |
| TC-029 | 정확 알람 안내: 주어진: 정확 알람 미허용 → 하면: 앱을 엶 → 그러면: 안내와 [허용하기]. 허용 → 안내 사라짐 | SPEC-029, REQ-050, FL-007 | 에뮬레이터: `appops`로 정확 알람 거부 (API 34) | 앱에 없음 (TASK-026). 거부 때 동작은 아직 안 봄 (TASK-027) |
| TC-030 | 절전 예외 안내: 주어진: 갤럭시 → 하면: [절전 예외 방법] → 그러면: 안내와 시스템 설정 화면 | SPEC-030, REQ-051, FL-007 | 실기기(갤럭시) | 앱에 없음 (TASK-026) |
| TC-031 | 방해 금지 겹침 안내: 주어진: 시작 21:00, 간격 60, 최대 3, 방해 금지 22:00~07:00 → 하면: 저장 → 그러면: "다시 알림이 2번 줄어요" (RR-49, RR-50) | SPEC-031, REQ-077, FL-007 | jvm: `ReminderSlotsTest.rr49_*`·`rr50_*`, `PlanRemindersTest.rr49_rr50_*` | jvm 통과 (CI, `45faa8f`). 안내 화면은 없음 (TASK-023) |
| TC-032 | 알림 다시 계산: 주어진: 09:30에 시작 09:00 → 11:00 → 하면: 저장 → 그러면: 11:00·12:00·13:00 (RR-18, RR-56, RR-58, RR-81) | SPEC-032, REQ-047, FL-006, FL-007, FL-008, FL-009, FL-010 | jvm: `PlanRemindersTest`(다시 계산, `rr54_*`~`rr56_*`) | jvm 통과 (CI, `45faa8f`). 예약기는 앱에 없음 (TASK-024·027) |
| TC-033 | 하루 시작 시각·기준 시간대 바꾸기: 주어진: 새벽 03:00에 04:00 → 06:00 → 그러면: 중복 판정 없음, 지난 판정 기간키 그대로 (RR-15, RR-44, RR-77) | SPEC-033, REQ-053, REQ-011, FL-008 | jvm: `ManageSettingsTest.rr15_*`·`base_zone_change_keeps_past_period_keys` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-020) |
| TC-034 | 잠금 화면 이름 표시 바꾸기: 주어진: 끔 → 하면: 켬 → 그러면: 다음 알림부터 잠금 화면에 이름과 버튼 | SPEC-034, REQ-053, FL-008 | jvm: `ManageSettingsTest.names_on_lock_screen_default_off` | jvm 통과 (CI, `45faa8f`). 화면은 없음 (TASK-020) |
| TC-035 | 백업 안내: 주어진: 화면 잠금 없음 → 하면: 설정을 엶 → 그러면: "화면 잠금이 없어 복용 기록이 백업되지 않아요" (RR-68, RR-70) | SPEC-035, REQ-054, FL-008 | 에뮬레이터: 화면 잠금 있음·없음, API 26·27 | 앱에 없음 (TASK-020) |
| TC-036 | 의료 안내와 개인정보처리방침: 주어진: 설정 → 그러면: 의료 안내 문구와 링크가 보임 | SPEC-036, REQ-036, FL-008 | 에뮬레이터 캡처, 문구 검사 | 일부: 첫 화면 아래 문구만 있음 (TASK-020·022) |
| TC-037 | 백업에 넣는 파일: 주어진: 기록 1건 → 하면: 백업 → 삭제 → 다시 설치 → 그러면: 1건이 보임 (RR-26, RR-27, RR-28, RR-29, RR-68, RR-69, RR-70) | SPEC-037, REQ-055, FL-009 | jvm: `BackupFileSetTest`, `BackupRulesTest`. 에뮬레이터: `backup_restore_check.py` | jvm 통과 (CI, `45faa8f`). 에뮬레이터 통과 ([보고서](../07-qa/2026-11-12-backup-restore/report.md), `311013f`) |
| TC-038 | 복원 감지: 주어진: 백업에서 복원 → 하면: 첫 실행 → 그러면: 복원으로 처리, 옛 `device_id`는 따로 남음 (RR-31, RR-34, RR-71, RR-72) | SPEC-038, REQ-056, FL-009 | jvm: `InstallCheckTest`. 에뮬레이터: `backup_restore_check.py` | jvm 통과 (CI, `45faa8f`). 에뮬레이터 통과 (같은 보고서) |
| TC-039 | 복원 안내와 확인: 주어진: 새 폰에 복원, 옛 폰에서 오늘 기록함 → 하면: 첫 실행 → 그러면: 오늘 칸 미완료, 안내와 마지막 기록 시각, 복용 알림 없음 (RR-30, RR-32, RR-33, RR-73, RR-75) | SPEC-039, REQ-073, REQ-074, FL-009 | jvm: `InstallCheckTest.rr30_*`~`rr33_*`. 에뮬레이터: `backup_restore_check.py` | jvm 통과 (CI, `45faa8f`). 첫 화면 안내는 에뮬레이터 통과 (같은 보고서). 위젯·알림 안내는 앱에 없음 (TASK-040) |
| TC-040 | 하루 경계 갱신: 주어진: 23:59 복용 → 하면: 00:01 다시 탭 → 그러면: 같은 창이라 1건. 04:00 → 새 날짜 (RR-3, RR-4, RR-20) | SPEC-040, REQ-008, REQ-002, FL-010 | jvm: `DayBoundaryTest`, `StatusBoardTest.day_start_boundary_0400_*`. 둘러보기 10·11 | jvm 통과 (CI, `45faa8f`) |
| TC-041 | 재부팅·시각·시간대·업데이트 뒤 다시 예약: 주어진: 재부팅 → 하면: 잠금 해제 → 그러면: 위젯이 오늘로 그려지고 다음 경계 알람이 있음 (RR-18) | SPEC-041, REQ-084, FL-010 | jvm: `PlanRemindersTest.rr55_*`. 둘러보기 9(재부팅). 실기기 TASK-032 | jvm 통과 (CI, `45faa8f`). 앱의 다시 예약은 일부 (TASK-027) |
| TC-042 | 기기 시간대 표시: 주어진: 기준 Asia/Seoul, 기기 런던 → 하면: 위젯을 봄 → 그러면: 기록 시각은 런던 시각, 기준 날짜는 서울 기준 (RR-8, RR-58) | SPEC-042, REQ-082, FL-010 | jvm: `RecordRulesCounterexampleTest.c08_deviceZoneChange_windowUsesBaseZone`. 표시는 에뮬레이터 | jvm 통과 (CI, `45faa8f`). 기기 시간대 표시는 없음 (TASK-043) |
| TC-043 | 한 창에 판정 하나: 주어진: 위젯·앱 거의 동시 기록 → 그러면: 활성 1건 (RR-1, RR-2, RR-6, RR-24) | SPEC-043, REQ-001, REQ-003, REQ-006, FL-002, FL-003, FL-004 | jvm: `RecordRulesCounterexampleTest.c01`·`c02`·`c24`, `DoseRecordDaoTest.uniqueIndex_*` | jvm 통과 (CI, `45faa8f`) |

## 2. 흐름

주 경로와 실패 길을 끝까지 걸음. 둘러보기가 걷는 단계는 [`explore.py`](../../scripts/emulator/explore.py) 첫 주석에 있음.

| TC | 확인할 것 | 근거 | 방법 | 지금 |
| --- | --- | --- | --- | --- |
| TC-044 | 처음 쓰기: 첫 실행 → 첫 항목 추가 → (알림) → 위젯 놓기. 실패 길: 빈 이름, 항목 추가 중 뒤로 가기, 위젯 고정 요청을 지원하지 않는 런처 | FL-001, SCR-005, SCR-006#error, SCR-002, SCR-001#normal | 둘러보기 1~3, 5-1. 첫 실행 안내·대상자 고르기는 화면이 생기면 더함 | 둘러보기 6회차(`45faa8f`, 실행 43178960350): 첫 실행·항목 3개 추가·사람 추가·위젯 놓기 통과(위젯 놓기는 두 번째 시도에 됨). 뒤로 가기는 4회차에서 앱이 닫힘(BUG-001). 첫 실행 안내 화면은 없음 |
| TC-045 | 위젯 탭: 미완료 버튼 → 기록, 같은 버튼 다시 → 이미 기록됨. 실패 길: 보관된 항목, 날짜가 바뀐 화면, 오류 | FL-002, SCR-001#normal, #already, #stale, #gone, #error | 둘러보기 5-2~5-4. 날짜가 바뀐 화면은 jvm `c13` | jvm 통과. 둘러보기 6회차(`45faa8f`, 실행 43178960350) 5-2~5-4 통과: 위젯 탭 기록(source WIDGET), 다시 탭하면 'BPpill: 이미 기록됨 (08:35). 변경 없음', 앱에도 완료로 보임 |
| TC-046 | 알림: 알림 → 먹었어·30분 뒤·오늘 건너뜀. 실패 길: 이미 기록됨, 잠금 화면 | FL-003, SCR-003, #already, #lock | 에뮬레이터(알림이 생기면 둘러보기에 더함) | 앱에 없음 (TASK-024·025) |
| TC-047 | 오늘 고치기: 기록 취소, 다시 먹었어, 건너뜀, 시각 고치기. 실패 길: 미래 시각, 잠긴 창 | FL-004, SCR-004#skipped, #future, SCR-007 | 둘러보기 6-1·6-2. 나머지는 화면이 생기면 | 둘러보기 9회차(`0e2dce3`, 실행 43191073123) 6-1·6-2 통과: '기록을 취소할까요?' 확인 뒤 취소되고 'Vitamin 기록을 취소했어요. 기록은 무효로 남아요', 다시 [먹었어]로 기록됨. 6회차에는 시험 스크립트가 확인을 누르지 않아 6-2가 실패했음. 건너뜀·시각 고치기 화면은 없음 (TASK-016·018) |
| TC-048 | 지난 기록: 달 넘기기, 판정 눌러 고치기. 실패 길: 빈 달, 잠긴 창 | FL-005, SCR-008, #empty, SCR-007#locked | 에뮬레이터(화면이 생기면) | 앱에 없음 (TASK-019) |
| TC-049 | 항목·대상자 관리: 항목 고치기·보관·완전 삭제, 대상자 추가·고치기·보관. 실패 길: 마지막 대상자 보관 | FL-006, SCR-006, SCR-009, SCR-010, SCR-001#reconfigure | 둘러보기 7-1·7-2. 대상자 관리는 화면이 생기면 | 둘러보기 6회차(`45faa8f`, 실행 43178960350) 7-1·7-2 통과. 화면 글자는 아직 '삭제'('Weekly 항목을 삭제했어요. 지난 기록은 남아요'), 08 정의는 '보관'. 대상자 관리·완전 삭제 화면은 없음 (TASK-020·021) |
| TC-050 | 알림 설정: 항목에서 알림 켜기, 권한 요청, 방해 금지 겹침 안내. 실패 길: 권한 거부, 정확 알람 거부 | FL-007, SCR-006#permission, #quiet, SCR-009 | 에뮬레이터(API 33 이상 권한 대화상자) | 앱에 없음 (TASK-023·026) |
| TC-051 | 설정: 하루 시작·기준 시간대 바꾸기, 잠금 화면 이름, 백업 안내, 의료 안내 | FL-008, SCR-009 | 에뮬레이터(화면이 생기면) | 앱에 없음 (TASK-020) |
| TC-052 | 복원: 새 폰에 복원 → 안내 → [확인했어요]. 실패 길: 확인 전 기록, 앱이 깔린 채 복원 | FL-009, SCR-004#restored, SCR-001#restored, SCR-003#restored, SCR-002 | `backup_restore_check.py`(API 34·30·27·26) | 첫 화면 쪽 통과 ([보고서](../07-qa/2026-11-12-backup-restore/report.md), `311013f`). 위젯·알림 안내는 앱에 없음 (TASK-040) |
| TC-053 | 날짜 바뀜: 하루 시작 시각에 위젯·앱이 새 날로. 실패 길: 하루 시작 전 새벽, 시계가 틀렸던 기록 | FL-010, SCR-001#clock, SCR-004#future | 둘러보기 9~11(재부팅, 다음 날 아침, 04:00 전 새벽). jvm `DayBoundaryTest` | jvm 통과. 둘러보기 6회차(`45faa8f`, 실행 43178960350) 9~11 통과: 재부팅 뒤 위젯이 다시 그려짐, 11/13 08:31에 위젯·앱이 11/13 (금)로 바뀜, 11/14 02:00에는 아직 11/13 (금) 기준. 보조 문구는 '마지막 어제 08:35'라 08 정의 '마지막 복용 어제 08:35'와 다름 |

## 3. 비기능 요구

| TC | 확인할 것 | 근거 | 방법 | 지금 |
| --- | --- | --- | --- | --- |
| TC-054 | 위젯 4×2에서 버튼·이름·마지막 복용·안내 줄이 잘리지 않음 (글자 100%·가장 크게) | REQ-026 | 둘러보기 5-1·8-2 캡처를 사람이 봄 | **실패** (둘러보기 6회차(`45faa8f`, 실행 43178960350) 8-2 캡처): 글자 2.0에서 위젯 이름이 'Vitam…'으로 줄고 머리글 날짜와 완료 수가 붙음. 글자 1.3은 잘림 없음 |
| TC-055 | 글자 200%에서 앱 화면의 버튼·이름이 잘리거나 겹치지 않음 (폭 360dp) | REQ-066 | 둘러보기 8-2 캡처 | **실패** (1회차, 둘러보기 6회차(`45faa8f`, 실행 43178960350)): 첫 화면 카드 글자가 낱말 가운데서 끊김('오/늘', '이/번 창'), 항목 추가 화면 시간대 칩이 오른쪽으로 넘침 (BUG-002) |
| TC-056 | 누르는 곳 48×48dp 이상 | REQ-067 | 에뮬레이터: uiautomator 덤프의 버튼 크기 재기 | 안 함 |
| TC-057 | TalkBack이 버튼을 이름과 상태로 읽음 | REQ-068 | 에뮬레이터: 위젯·앱 버튼의 `content-desc` 읽기 | 안 함. 지금 문장은 화면 정의와 다름 (TASK-013) |
| TC-058 | 글자·배경 명암비 4.5:1 이상 | REQ-069 | 색 쌍 계산 | 안 함 (09 목업에서 토큰으로 잼) |
| TC-059 | 위젯 탭 뒤 완료 표시 P95 1초(앱 살아 있음)·3초(내려가 있음) | REQ-070 | 실기기 20회 (TASK-032) | 안 함 |
| TC-060 | 알림 지연을 분으로 약속하는 문구 없음. 지연은 실기기에서 잼 | REQ-048 | 문구 검사, 실기기 하룻밤 (TASK-032) | 안 함 |
| TC-061 | 복용량 권고·빠뜨렸을 때 처치·상호작용 판정 문구 없음 | REQ-087 | 문구 검사: `strings.xml`, `Labels.kt`, 스토어 설명 | 안 함 |
| TC-062 | `USE_EXACT_ALARM`을 선언하지 않음 | REQ-052 | CI: APK 권한 목록 = `androidApp/expected-permissions.txt` | 통과 (CI, `45faa8f`) |
| TC-063 | 서버·계정 없이 기기 안에만 둠: `INTERNET` 권한 없음 | REQ-057 | CI: `check_permissions.py`(디버그·릴리즈 APK) | 통과 (CI, `45faa8f`) |
| TC-064 | 로그·저장소에 기록·이름을 남기지 않음 | REQ-058 | 코드 검사: `Log.` 호출 | 통과 (2026-11-12 코드 검사: 6곳 모두 id와 상태만, 이름 없음). 자동 검사는 없음 |
| TC-065 | 분석·크래시 SDK 없음 | REQ-090 | 의존성 목록 검사 | 안 함 (권한 검사로 `INTERNET`이 없음은 확인) |
| TC-066 | AI 기능·외부 AI API 없음 | REQ-059 | 의존성 목록 검사 | 안 함 (같음) |
| TC-067 | 광고·광고 SDK 없음 | REQ-083 | 의존성 목록 검사, `INTERNET` 없음 | `INTERNET` 없음은 통과. 의존성 목록은 안 함 |
| TC-068 | 알림 버튼 수신기 `exported="false"`, PendingIntent `FLAG_IMMUTABLE` | REQ-081 | 병합 매니페스트, 코드 검사 | 수신기가 아직 없음 (TASK-025) |
| TC-069 | 공통 코드에 Android API 없음 | REQ-071 | 코드 검사: `shared/src/commonMain`에 `import android.` 없음 | 통과 (2026-11-12, 0곳) |
| TC-070 | 기록 규칙을 JVM 테스트로 검증 | REQ-091 | CI `./gradlew :shared:jvmTest` | 통과 (CI, `45faa8f`) |
| TC-071 | 스키마를 바꿀 때마다 이주와 그 시험 | REQ-072 | jvm `MigrationTest`, `check_docs.py`의 스키마 대조 | 통과 (CI, `45faa8f`) |
| TC-072 | Play 비공개 테스트로 배포 | REQ-060 | Play Console (TASK-048~050) | 안 함 |
| TC-073 | 패키지 이름을 첫 업로드 전에 정하고 바꾸지 않음 | REQ-061 | 빌드 설정, 첫 업로드 | 정함 (TASK-001, `com.judangbang.pillbadeum`). 업로드는 안 함 |
| TC-074 | 개인 APK 기록이 Play 버전으로 넘어갈 때 남음 | REQ-062 | 실기기: 개인 APK 위에 Play 버전 설치 (TASK-047·050) | 안 함 |
| TC-075 | Play Console 앱 콘텐츠 완료, 앱 안 개인정보처리방침 | REQ-063 | Play Console, SCR-009 | 안 함 |
| TC-076 | 스토어 설명에 의료기기 아님·의료인 상의 문구 | REQ-064 | 스토어 설명 문안 (TASK-037) | 안 함 |
| TC-077 | 새 개인 계정이면 테스터 12명 14일 | REQ-065 | 비공개 테스트 참여 기록 | 안 함 |

## 4. 화면 상태

화면마다 모든 상태를 한 번씩 만들어 캡처함. 화면 작업 PR이 그 화면의 상태 캡처를 남김(08 화면 정의의 '바꿀 것').

| TC | 확인할 것 | 근거 | 방법 | 지금 |
| --- | --- | --- | --- | --- |
| TC-078 | SCR-001 홈 화면 위젯의 상태 13개가 화면 정의대로 보임 | SCR-001#normal, SCR-001#loading, SCR-001#empty, SCR-001#error, SCR-001#permission, SCR-001#stale, SCR-001#gone, SCR-001#already, SCR-001#skipped, SCR-001#overflow, SCR-001#restored, SCR-001#reconfigure, SCR-001#clock | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-079 | SCR-002 위젯 대상자 고르기의 상태 6개가 화면 정의대로 보임 | SCR-002#normal, SCR-002#loading, SCR-002#empty, SCR-002#error, SCR-002#permission, SCR-002#stale | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-080 | SCR-003 반복 알림의 상태 9개가 화면 정의대로 보임 | SCR-003#normal, SCR-003#loading, SCR-003#empty, SCR-003#error, SCR-003#permission, SCR-003#stale, SCR-003#already, SCR-003#lock, SCR-003#restored | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-081 | SCR-004 기록 (오늘·이번 주)의 상태 9개가 화면 정의대로 보임 | SCR-004#normal, SCR-004#loading, SCR-004#empty, SCR-004#error, SCR-004#permission, SCR-004#stale, SCR-004#skipped, SCR-004#future, SCR-004#restored | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-082 | SCR-005 첫 실행 안내의 상태 6개가 화면 정의대로 보임 | SCR-005#normal, SCR-005#loading, SCR-005#empty, SCR-005#error, SCR-005#permission, SCR-005#stale | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-083 | SCR-006 항목 추가·수정의 상태 8개가 화면 정의대로 보임 | SCR-006#normal, SCR-006#loading, SCR-006#empty, SCR-006#error, SCR-006#permission, SCR-006#stale, SCR-006#gone, SCR-006#quiet | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-084 | SCR-007 판정 고치기의 상태 10개가 화면 정의대로 보임 | SCR-007#normal, SCR-007#loading, SCR-007#empty, SCR-007#error, SCR-007#permission, SCR-007#stale, SCR-007#already, SCR-007#gone, SCR-007#locked, SCR-007#future | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-085 | SCR-008 지난 기록의 상태 7개가 화면 정의대로 보임 | SCR-008#normal, SCR-008#loading, SCR-008#empty, SCR-008#error, SCR-008#permission, SCR-008#stale, SCR-008#future | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-086 | SCR-009 설정의 상태 6개가 화면 정의대로 보임 | SCR-009#normal, SCR-009#loading, SCR-009#empty, SCR-009#error, SCR-009#permission, SCR-009#stale | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |
| TC-087 | SCR-010 대상자 관리의 상태 6개가 화면 정의대로 보임 | SCR-010#normal, SCR-010#loading, SCR-010#empty, SCR-010#error, SCR-010#permission, SCR-010#stale | 둘러보기와 화면 작업 PR 캡처 | 안 함. 둘러보기는 normal·empty 위주로 봄 |

## 5. 사례 종류

| 종류 | 이 앱에서 | 사례 |
| --- | --- | --- |
| 접근 | 계정이 없어 로그인·권한 응답이 없음. 잠금 화면 알림만 따로 봄 | TC-013, TC-034 |
| 입력 | 빈 이름, 미래 시각, 창 밖 시각, 설정 변경 전 창, 초가 있는 하루 시작 | TC-004, TC-017, TC-033 |
| 대상 | 보관된 항목·대상자, 앱 데이터를 지운 뒤 남은 위젯 버튼 | TC-009, TC-024, TC-027 |
| 상태 변화 | 날짜가 바뀐 화면, 건너뜀을 복용으로, 복원 | TC-009, TC-019, TC-039, TC-053 |
| 반복 | 위젯 연타, 알림 버튼 두 번 | TC-007, TC-043 |
| 동시성 | 위젯·앱·알림이 거의 같이 기록 | TC-043 |
| 한도 | 하루 최대 알림, 방해 금지, 위젯 넘침, 글자 200% | TC-006, TC-031, TC-055 |
| 외부 서비스 | 해당 없음. 네트워크를 쓰지 않음(REQ-057). 개인정보처리방침만 브라우저로 엶 | TC-036, TC-063 |
| 데이터 노출 | 잠금 화면 이름, 로그, 백업 범위 | TC-013, TC-064, TC-037 |
| 빈 결과 | 항목 없음, 창이 없는 달 | TC-015, TC-022 |
| 화면 상태 | 모든 상태, 폭 360dp, 글자 200% | TC-055, TC-078~087 |
| AI 답 | 해당 없음. AI 기능 없음(REQ-059) | TC-066 |

## 6. 버그

| BUG | 무엇 | 근거 | 찾은 곳 | 상태 |
| --- | --- | --- | --- | --- |
| BUG-001 | 항목 추가 화면에서 시스템 뒤로 가기를 누르면 앱이 통째로 닫힘. 기대: 앞 화면(SCR-004)으로 돌아감 | TC-021, SPEC-021, REQ-094, SCR-006 | 둘러보기 4회차 (`73e720f` 전), 코드: `App.kt`에 `BackHandler` 없음 | 열림 (TASK-016) |
| BUG-002 | 글자 크기 2.0에서 첫 화면 카드 글자가 낱말 가운데서 끊기고, 시간대 칩이 넘치고, 위젯 이름이 줄어듦 | TC-055, TC-054, REQ-066, REQ-026, SCR-004, SCR-006, SCR-001 | 둘러보기 1회차·6회차 캡처 | 열림 (TASK-030). 6회차에도 그대로. 위젯 글자 2.0 잘림도 같은 버그로 봄(TC-054) |
| BUG-003 | 앱 화면이 다시 만들어지면(화면 회전, 키보드 연결, 설치 직후 시스템 변경 등) 보던 화면과 입력이 사라지고 첫 화면으로 돌아감. 항목 추가 중이면 쓰던 이름이 없어짐. 기대: 보던 화면과 입력이 남음 | SCR-006, FL-001. 이를 요구하는 REQ가 없음(02 빈틈 후보) | 백업 확인 API 34 (`fb3b645`, 실행 43189568290): 다시 설치 직후 항목 추가 화면에 이름을 넣는 사이 MainActivity가 다시 만들어져(logcat 'finishDrawing of relaunch') 첫 화면으로 돌아옴. 코드: `App.kt`가 지금 화면을 `remember`에만 둠(`rememberSaveable` 아님) | 열림 (TASK-016과 같이). 백업 확인 스크립트는 이때 한 번 더 입력하고 보고서에 적음 |
| BUG-004 | 설치 직후 홈 화면에 처음 놓은 위젯이 로딩 표시(도는 원)에 멈춰 항목이 보이지 않음. 기대: 놓은 뒤 몇 초 안에 항목이 그려짐 | TC-006, SPEC-006, REQ-019, SCR-001#loading | 릴리즈 변형 확인 API 34 (`82e0fe5`, 실행 43195651489, 앱 코드는 바꾸지 않은 커밋). 10:01:47 위젯을 묶고 10:01:48 우리 패키지에 PACKAGE_CHANGED가 났음(logcat `onPackageModified`). 10:02:32 Glance 세션이 닫혔고(SessionWorker SUCCESS) 10:02:40 캡처에도 로딩 표시 그대로임. 같은 단계는 앞 실행들(`0e2dce3` 등)에서 통과했음. 원인은 확인하지 못함. 가설: 첫 SessionWorker를 넣을 때 WorkManager가 구성요소를 켜서 PACKAGE_CHANGED가 나고, 런처가 위젯을 처음 모양(로딩)으로 다시 그린 뒤 Glance가 다시 보내지 않음 | 열림 (TASK-014). PR #16 기술 시험에서도 그려진 위젯이 가끔 로딩으로 돌아갔음 |

## 7. 셈 (2026-11-12)

- 명세 합격 기준 43개: 공통 코드의 jvm 시험이 통과한 것 34개(화면까지 본 것은 아님), 실패 1개(TC-021). 앱에 화면·기능이 없어 끝까지 못 본 것은 '지금' 열에 작업 번호로 적음
- 흐름 10개: 끝까지 걸은 것 0개. 둘러보기 9회차(`0e2dce3`, 실행 43191073123)가 FL-001·002·004·006·010의 앱에 있는 부분을 걸음: 22단계 모두 통과. 그중 캡처만 하는 6단계(8-1~8-3, 9~11)는 사람이 캡처를 봐야 판정됨. 6회차는 21단계 통과였고 실패 1단계(6-2)는 시험 스크립트 문제였음. 7회차는 재부팅 뒤 화면 읽기와 시계 되돌아감으로 9~11 실패, 8회차는 푸시로 취소됨
- 비기능 요구 24개: 통과 6개(REQ-052·057·058·071·091·072), 일부 통과 1개(REQ-083의 권한 부분), 실패 2개(REQ-066, REQ-026), 나머지는 안 함
- 화면 상태 80개: 상태마다 캡처한 것은 아직 없음
- 하지 않은 것: 실기기 전부, 알림(앱에 없음), TalkBack, 명암비, 의존성 목록 검사
