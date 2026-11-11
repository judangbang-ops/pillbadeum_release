# 사용자 플로우·상태 흐름 (설계 초안)

다이어그램을 먼저 두고, 단계별 관련 테이블을 아래 표로 연결함. 테이블 정의는 [data-model](../02-design/data-model.md)에 있음.

## 1. 사용자 플로우 맵

```mermaid
flowchart TD
    install(["앱 설치"]) --> perm["권한 안내: 알림 · 정확 알람 · 갤럭시 절전 예외"]
    perm --> person["대상자 등록: 나 / 가족"]
    person --> intake["복용 항목 등록: 이름 · 주기 · 시간대 · 위젯 별칭"]
    intake --> widget["홈 화면에 위젯 배치 · 대상자 필터 선택"]
    widget --> look{"매일: 위젯 확인"}
    look -->|"미완료"| take["약 복용"]
    take --> tap["위젯 버튼 탭"]
    tap --> done["완료 표시 + 기록 시각"]
    look -->|"완료"| stop["먹지 않음 (이중투약 방지)"]
    look -->|"반복 알림 받음"| notif["알림에서 '먹었어'"]
    notif --> done
    done --> app["앱: 오늘 · 이번 주 기록"]
    app --> cancel["잘못 누름 → 기록 취소 (확인 후 무효 처리)"]
    app --> edit["항목 수정 → 일정 새 버전"]
    app --> archive["항목 삭제 → 보관 (기록 유지)"]
    app --> settings["설정: 하루 시작 시각 · 기준 시간대 · 백업"]
```

| 단계 | 관련 테이블 | 메모 |
| --- | --- | --- |
| 대상자 등록 | person | 색은 위젯 표시용 |
| 복용 항목 등록 | intake, schedule_version | 주기는 `schedule_version.type`, `params_json` |
| 위젯 배치 | (위젯 인스턴스 상태) | 대상자 필터는 위젯마다 따로 저장 |
| 위젯 탭 기록 | dose_record (`kind` = TAKEN, `source` = WIDGET) | `UNIQUE(intake_id, period_key, active)` |
| 알림 '먹었어' | dose_record (`source` = NOTIFICATION) | 확장 단계 reminder_rule |
| 기록 취소 | dose_record (`active` = NULL, `voided_at`) | 행을 지우지 않음 |
| 항목 수정 | schedule_version 새 행 | 이전 버전 `effective_to` 채움 |
| 항목 삭제 | intake.archived_at | 기록 유지 |
| 설정 | setting | `day_start`, `base_zone`, `backup_policy` |

## 2. 창 상태

```mermaid
stateDiagram-v2
    state "예정" as SCHEDULED
    state "미완료" as PENDING
    state "기록됨" as TAKEN
    state "건너뜀" as SKIPPED
    state "빠짐" as MISSED
    [*] --> SCHEDULED
    SCHEDULED --> PENDING: 창 시작
    PENDING --> TAKEN: 위젯 · 앱 · 알림에서 기록
    PENDING --> SKIPPED: 앱 · 알림에서 건너뜀
    TAKEN --> PENDING: 앱에서 취소 (창이 열려 있을 때)
    SKIPPED --> PENDING: 앱에서 취소 (창이 열려 있을 때)
    SKIPPED --> TAKEN: 앱에서 복용으로 변경
    PENDING --> MISSED: 창 끝 (판정 없음)
    TAKEN --> [*]: 창 끝
    SKIPPED --> [*]: 창 끝
    MISSED --> [*]
```

| 상태 | 저장 여부 | 위젯 표시 |
| --- | --- | --- |
| 예정 | 계산 | 표시하지 않음 (일정 시작 전·중단 구간) |
| 미완료 | 계산 | 점선 빈 원 + "마지막 어제 22:00" |
| 기록됨 | dose_record (TAKEN, active = 1) | 채움 + 체크 + 기록 시각 |
| 건너뜀 | dose_record (SKIPPED, active = 1) | 확장 단계에서 별도 표시 |
| 빠짐 | 계산 | 위젯에 표시하지 않음. 앱 기록·리포트에서 보여줌 |

- 취소는 판정을 무효 처리할 뿐 행을 지우지 않음. 창이 끝난 뒤 취소하면 그 창은 빠짐이 됨
- 위젯은 미완료 → 기록됨 전이만 만듦. 나머지 전이는 앱·알림에서만 일어남

## 3. 위젯 탭 판단 흐름

```mermaid
flowchart TD
    t["위젯 버튼 탭"] --> a{"항목이 있고 보관되지 않았나"}
    a -->|"아니오"| r1["기록하지 않음 → 위젯 새로고침"]
    a -->|"예"| b{"렌더 기간키 = 현재 기간키"}
    b -->|"아니오"| r2["기록하지 않음 → 새로고침 + '날짜가 바뀌었어요'"]
    b -->|"예"| c{"현재 창에 활성 판정이 있나"}
    c -->|"있음"| r3["변경 없음 → '이미 기록됨 (기존 시각)'"]
    c -->|"없음"| d["INSERT OR IGNORE"]
    d --> e{"1행 삽입됐나"}
    e -->|"예"| r4["기록 완료 → 남은 알림 취소 → 위젯 갱신"]
    e -->|"아니오"| f{"다시 조회: 활성 판정이 있나"}
    f -->|"있음"| r3
    f -->|"없음"| r5["오류로 남김 (중복으로 숨기지 않음)"]
```

## 4. 반복 알림 흐름

```mermaid
flowchart TD
    o["창 시작 · 앱 실행 · 부팅 · 기록 취소"] --> p["공통 코드가 알림 시각 목록 계산"]
    p --> s["가까운 알림 예약"]
    s --> fire["알림 시각 도달"]
    fire --> q{"이 창에 활성 판정이 있나"}
    q -->|"있음"| stop["알림을 띄우지 않음 · 남은 예약 취소"]
    q -->|"없음"| dnd{"방해 금지 시간 · 하루 최대 · 창 끝"}
    dnd -->|"해당"| stop2["이번 창 알림 멈춤"]
    dnd -->|"아님"| show["알림 표시: 먹었어 · 30분 뒤 · 오늘 건너뜀"]
    show --> act{"사용자 동작"}
    act -->|"먹었어"| rec["RecordDose → 남은 예약 취소"]
    act -->|"30분 뒤"| snooze["30분 뒤 1회 예약"]
    act -->|"오늘 건너뜀"| sk["SkipDose → 남은 예약 취소"]
    act -->|"무시"| next["다음 간격까지 대기"]
    snooze --> fire
    next --> fire
```

| 단계 | 관련 테이블 | 메모 |
| --- | --- | --- |
| 알림 시각 계산 | reminder_rule (확장), schedule_version, setting | 시작 시각·간격·하루 최대·방해 금지 |
| 판정 확인 | dose_record | [record-rules 5장](../02-design/record-rules.md#5-판정-계산) 조건 |
| 먹었어 | dose_record (TAKEN, NOTIFICATION) | 알림의 기간키로 대조 |
| 오늘 건너뜀 | dose_record (SKIPPED, NOTIFICATION) | |

## 5. 주 1회 늦은 투약 처리

```mermaid
flowchart TD
    late["예정일보다 늦게 기록됨"] --> pol{"약별 설정 late_policy"}
    pol -->|"ASK (기본)"| ask["앱 · 알림으로 질문: 기준일을 옮길까요?"]
    pol -->|"MOVE"| move["기준일을 기록한 요일로 옮김"]
    pol -->|"KEEP"| keep["그대로 둠: 다음 창은 원래 요일"]
    ask -->|"옮김"| move
    ask -->|"유지"| keep
    move --> ver["새 schedule_version 저장 · 이전 버전 종료"]
    ver --> chk["방금 기록이 새 창을 채움 → 완료"]
```
