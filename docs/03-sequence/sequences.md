# 시퀀스 다이어그램 (설계 초안)

주요 동작의 호출 순서임. 판정 규칙은 [record-rules](../02-design/record-rules.md), 모듈 이름은 [architecture](../02-design/architecture.md#3-모듈-구조)를 따름.

| # | 시퀀스 | 단계 |
| --- | --- | --- |
| 1 | 위젯 탭으로 기록 (Android) | MVP |
| 2 | 위젯·앱 거의 동시 기록 | MVP |
| 3 | 앱에서 기록 취소 | MVP |
| 4 | 날짜 경계 갱신 | MVP |
| 5 | 반복 알림 | MVP 다음 |
| 6 | 주 1회 늦은 투약과 기준일 이동 | 확장 |
| 7 | iOS 위젯 탭으로 기록 | 2단계 |

## 1. 위젯 탭으로 기록 (Android)

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant W as 홈 위젯 (Glance)
    participant A as ActionCallback
    participant R as RecordDose (공통)
    participant D as DB (Room)
    participant N as 알림 예약기
    U->>W: 미완료 버튼 탭
    W->>A: 항목 ID, 렌더 기간키
    A->>R: recordDose(항목, 렌더 기간키, now)
    R->>D: 쓰기 트랜잭션 시작
    R->>D: 항목 조회 (보관 여부)
    R->>R: 현재 창·기간키 계산
    alt 보관된 항목
        R-->>A: Gone
        A->>W: 위젯 전체 갱신
    else 렌더 기간키가 현재와 다름
        R-->>A: Stale
        A->>W: 위젯 전체 갱신 + "날짜가 바뀌었어요"
    else 창 안에 활성 판정 있음
        R-->>A: Already (기존 시각)
        A->>W: 갱신 + "이미 기록됨 (08:02)"
    else 판정 없음
        R->>D: INSERT OR IGNORE (active = 1)
        R->>D: 커밋
        R-->>A: Recorded
        A->>N: 이 창의 남은 알림 취소
        A->>W: 위젯 전체 갱신
    end
```

- 위젯 동작은 비동기 방송 수신기 안에서 돎. 오래 걸리는 일은 넣지 않음
- 앱 프로세스가 내려가 있으면 처음 실행 비용 때문에 반영이 늦어질 수 있음. 그 사이 연타는 2번 시퀀스처럼 막힘

## 2. 위젯·앱 거의 동시 기록

```mermaid
sequenceDiagram
    autonumber
    participant W as 위젯 동작
    participant P as 앱 화면
    participant R as RecordDose (공통)
    participant D as DB
    par 위젯
        W->>R: recordDose(항목 X)
    and 앱
        P->>R: recordDose(항목 X)
    end
    R->>D: 트랜잭션 A — 판정 없음 → INSERT → 커밋
    Note over D: 트랜잭션 B는 A가 끝날 때까지 기다림 (쓰기 직렬화)
    R->>D: 트랜잭션 B — 판정 있음
    R-->>P: Already
    R-->>W: Recorded
    Note over D: 별도 프로세스(iOS 위젯)라 직렬화가 안 되더라도<br/>UNIQUE(intake_id, period_key, active)가 두 번째 활성 행을 막음
```

## 3. 앱에서 기록 취소 (무효 처리)

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant P as 앱 화면
    participant V as VoidDose (공통)
    participant D as DB
    participant PL as PlanReminders (공통)
    participant W as 위젯
    U->>P: 기록 취소
    P->>U: 확인 대화상자
    U->>P: 취소 확정
    P->>V: voidDose(기록 ID, 사유)
    V->>D: UPDATE active = NULL, voided_at = now
    V-->>P: Voided
    opt 창이 아직 열려 있음
        P->>PL: 이 창의 반복 알림 다시 계산·예약
    end
    P->>W: 위젯 전체 갱신
```

## 4. 날짜 경계 갱신

```mermaid
sequenceDiagram
    autonumber
    participant S as 시스템
    participant B as 경계 수신기
    participant C as 공통 계산
    participant W as 위젯
    participant AL as 알람
    S->>B: 경계 알람(04:00) / 부팅 / 시각 변경 / 시간대 변경
    B->>W: 위젯 전체 갱신 (새 기준 날짜·상태)
    B->>C: 새 창들의 반복 알림 계획
    C-->>AL: 알림 예약
    B->>C: 다음 경계 시각 계산
    C-->>AL: 다음 경계 알람 예약 (비정확 알람)
```

- 경계 알람이 늦게 와도 위젯 머리글 날짜와 렌더 기간키 대조가 잘못된 기록을 막음

## 5. 반복 알림

```mermaid
sequenceDiagram
    autonumber
    participant AL as 시스템 알람
    participant RC as 알림 수신기
    participant D as DB
    participant PL as PlanReminders (공통)
    participant NT as 알림
    actor U as 사용자
    participant R as RecordDose (공통)
    AL->>RC: 예약 시각 도달 (항목, 기간키)
    RC->>D: 이 창에 활성 판정이 있나
    alt 이미 기록·건너뜀
        RC->>RC: 알림을 띄우지 않음
    else 판정 없음
        RC->>NT: "아침약 먹어~!" [먹었어] [30분 뒤] [오늘 건너뜀]
        RC->>PL: 다음 반복 시각 계산 (간격·방해 금지·하루 최대·창 끝)
        PL-->>AL: 다음 알람 예약
    end
    U->>NT: [먹었어]
    NT->>R: recordDose(항목, 알림의 기간키, now)
    R-->>NT: Recorded
    NT->>AL: 이 창의 남은 알람 취소
```

- iOS는 예약 시각에 앱 코드를 실행할 수 없음. 기록될 때 남은 예약을 취소하는 방식으로 같은 결과를 냄
- 알림의 기간키가 현재와 다르면(날짜가 바뀐 뒤 누름) 기록하지 않고 안내함

## 6. 주 1회 늦은 투약과 기준일 이동

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant P as 위젯 또는 앱
    participant R as RecordDose (공통)
    participant D as DB
    participant S as 일정 버전
    U->>P: 예정일(월) 이틀 뒤(수)에 기록
    P->>R: recordDose(now = 수요일)
    R->>D: 이번 창(월요일 시작)에 기록
    R-->>P: Recorded (2일 늦음)
    alt 약별 설정 = 매번 묻기
        P->>U: "다음부터 기준일을 수요일로 옮길까요?" (위젯에서 기록했으면 앱·알림으로 물음)
        U->>P: 옮김 / 유지
    else 설정 = 항상 옮기기
        P->>P: 묻지 않고 옮김
    else 설정 = 항상 유지
        P->>P: 그대로 둠 (다음 창은 월요일 시작)
    end
    opt 옮김
        P->>S: 새 일정 버전 (기준 요일 수, 적용 시작 = 수요일 04:00)
        S->>D: 이전 버전 종료 + 새 버전 저장
        Note over D: 방금 기록은 새 창 [수, 다음 수) 안에 있어 완료로 판정됨
    end
```

## 7. iOS 위젯 탭으로 기록 (2단계)

```mermaid
sequenceDiagram
    autonumber
    actor U as 사용자
    participant WX as 위젯 익스텐션 (별도 프로세스)
    participant I as App Intent
    participant R as RecordDose (공통 프레임워크)
    participant G as App Group DB
    participant APP as 앱 프로세스
    U->>WX: 버튼 탭
    WX->>I: perform(항목 ID, 렌더 기간키)
    I->>R: recordDose
    R->>G: 짧은 쓰기 트랜잭션 (파일 잠금)
    Note over G: 앱이 동시에 써도 유니크 제약이 두 번째 활성 판정을 막음
    R-->>I: 결과
    I->>I: 이 창의 예약 알림 취소
    I->>WX: 위젯 타임라인 다시 불러오기
    I-->>APP: 앱이 떠 있으면 변경 알림 → 화면 갱신
```

- 트랜잭션을 짧게 유지함. 공유 컨테이너 DB 잠금을 쥔 채 정지되면 시스템이 프로세스를 강제 종료함
