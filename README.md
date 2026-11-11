# DidITake · 한 번만 약통

같은 기간에 같은 약은 몇 번을 눌러도 한 번만 기록되는 복용 기록 앱임. 홈 화면 위젯을 눌러 기록하고, "약 먹었나? 또 먹어도 되나?"를 위젯 상태로 바로 확인함. 핵심 가치는 이중투약 방지임.

> 앱은 기록·표시 도구임. 복용량 권고, 빼먹었을 때 처치 안내, 약물 상호작용 판정 같은 의학적 판단은 하지 않음.

## 현재 상태

| 구분 | 상태 | 문서 |
| --- | --- | --- |
| 기획 | v3 (2026-11-11). v2 검토 결과와 열린 질문 결정을 반영함 | [planning-v3](docs/01-planning/planning-v3.md) |
| 설계 | 초안. 기술 스택 확정 | [02-design](docs/02-design/) |
| 구현 | 착수 전 | — |
| 배포 | 1단계 Android APK 직접 설치 → 2단계 iOS | [architecture](docs/02-design/architecture.md) |

## 화면 미리보기

![UI 초안 v3](docs/05-ui/ui-mockup-v3.png)

홈 화면 위젯(왼쪽), 앱 화면(가운데), 반복 알림(오른쪽) 초안임. 설명은 [05-ui](docs/05-ui/README.md)에 있음.

## 핵심 규칙 요약

- **창(window)**: 한 번 먹어야 하는 시간 구간임. 매일 약은 04:00~다음 날 04:00, 주 1회 약은 기준 요일 04:00부터 7일임.
- **한 창에 활성 판정은 최대 1개**임. 판정은 기록(복용)·건너뜀 두 종류이며, DB 유니크 제약으로 보장함.
- **위젯은 비어 있는 창에만 판정을 만듦.** 이미 있는 판정은 앱에서만 바꿈.
- **취소는 지우지 않고 무효 처리**함. 이력이 남음.
- MVP는 서버·계정 없이 모든 데이터를 기기에 저장함. 추후 백엔드를 붙일 수 있게 메타데이터(UUIDv7 · version · device_id · 보관 처리)를 설계함. AI 기능은 없음.

전체 규칙과 반례별 기대 동작은 [record-rules](docs/02-design/record-rules.md)에 있음.

## 문서 지도

| 순서 | 문서 | 내용 |
| --- | --- | --- |
| 1 | [01-planning](docs/01-planning/) | 기획 v3(현재 기준)와 v1·v2 원본 |
| 2 | [02-design/record-rules.md](docs/02-design/record-rules.md) | 창·기간키·판정·쓰기 절차, 반례별 기대 동작 (핵심) |
| 3 | [02-design/data-model.md](docs/02-design/data-model.md) | ERD, 테이블 정의, 인덱스, 삭제·백업 정책 |
| 4 | [02-design/architecture.md](docs/02-design/architecture.md) | 크로스플랫폼 구조, 모듈, 플랫폼 분담, 테스트 전략 |
| 5 | [03-sequence/sequences.md](docs/03-sequence/sequences.md) | 위젯 기록, 동시 탭, 취소, 반복 알림, 날짜 경계, iOS 위젯, 기준일 이동 |
| 6 | [04-flow/flows.md](docs/04-flow/flows.md) | 사용자 플로우, 창 상태, 위젯 탭 판단, 알림 흐름 |
| 7 | [05-ui](docs/05-ui/) | UI 초안 v3와 화면 설명 |
| 8 | [06-review](docs/06-review/) | v2 검토 결과와 반례 실증 스크립트 |

처음 읽는 개발자는 1 → 2 → 5 순서를 권장함.

## 기술 스택 (확정, 2026-11-11)

언어는 Kotlin임. MVP는 기기 로컬 APK이고, 나중에 iOS와 백엔드를 붙일 수 있게 구성함.

| 영역 | 지금 (MVP) | 나중 |
| --- | --- | --- |
| 공통 코드 | Kotlin Multiplatform 모듈 1개 (Android + JVM 타깃) | iOS 타깃 추가 |
| 화면 | Compose Multiplatform | iOS에서 그대로 씀 |
| DB | Room 3 + BundledSQLiteDriver | 서버는 PostgreSQL |
| 시간 계산 | kotlinx-datetime | |
| 위젯 | Jetpack Glance | iOS WidgetKit + App Intents (iOS 17 이상) |
| 알림 | 앱 자체 알림 (AlarmManager · NotificationManager) | iOS UNUserNotificationCenter. 카카오톡은 보류 |
| 서버 | 없음 | Kotlin(Spring Boot 또는 Ktor) + PostgreSQL. 창·판정 규칙을 같은 코드로 재사용 |

선택 이유와 지금 하지 않는 것은 [architecture 2장](docs/02-design/architecture.md#2-기술-스택-확정)에 있음.

## 결정 사항

| 날짜 | 결정 |
| --- | --- |
| 2026-11-11 | 취소는 무효 처리로 함 (이력 보존) |
| 2026-11-11 | 주 1회 약은 기준 요일 창 하나로 계산함. 늦게 맞았을 때 기준일을 옮길지는 약별 설정임 |
| 2026-11-11 | 하루 시작 시각은 설정값이며 기본 04:00임 |
| 2026-11-11 | 가족 약은 위젯 1종 + 위젯마다 대상자 필터로 다룸 |
| 2026-11-11 | 크로스플랫폼으로 설계함. Android를 먼저 만들고 iOS로 확장함 |
| 2026-11-11 | 반복 알림은 앱 자체 알림으로 함. 카카오톡 알림은 추후 확장으로 보류함 |
| 2026-11-11 | 가족 기기 알림은 카카오톡 문제가 풀린 뒤 정함 |
| 2026-11-11 | 언어는 Kotlin. MVP는 기기 로컬 APK이며, 추후 백엔드를 고려해 메타데이터를 설계함 |
| 2026-11-11 | 기술 스택 확정: Kotlin + KMP 최소 구성(`shared` + `androidApp`), Compose Multiplatform, Room 3 + BundledSQLiteDriver, Glance. 백엔드는 나중에 Kotlin + PostgreSQL |
| 2026-11-11 | 저장소는 `judangbang-ops/DidITake` (AI 작업 전용). 앱 소스와 문서를 저장소 루트에 둠 |
| 2026-11-11 | 브랜치: `main` 없음. 기본 브랜치는 `develop`, 기능이 완성되면 `alpha`, 출시는 `release` |

## 보류

- 카카오톡 알림: 추후 확장 기능
- 가족 기기 알림: 카카오톡 문제가 풀린 뒤 정함

## 로드맵

1. 설계 승인 (기술 스택은 확정)
2. 공통 모듈: 창·판정 규칙과 반례 테스트
3. Android 앱·위젯 MVP
4. 반복 알림
5. APK 배포
6. iOS 확장 PoC
7. 확장 기능: 건너뜀, 주기 밀림, 복용일기, 처방전
8. 백엔드·동기화: Kotlin 서버 + PostgreSQL
9. (보류) 카카오톡 알림

## 폴더 구조

```
(저장소 루트)
├── README.md
├── shared/        공통 코드 (구현 단계에서 추가)
├── androidApp/    Android 앱·위젯 (구현 단계에서 추가)
└── docs/
    ├── 01-planning/   기획
    ├── 02-design/     설계 (기록 규칙·데이터 모델·아키텍처)
    ├── 03-sequence/   시퀀스 다이어그램
    ├── 04-flow/       사용자 플로우·상태 흐름
    ├── 05-ui/         UI 초안
    └── 06-review/     검토·반례 실증
```

앱 소스는 저장소 루트의 `shared/`(공통)와 `androidApp/`(Android)에 둠.

## 작업 규칙

- 브랜치: `main`은 두지 않음. 기본 브랜치 `develop`에 개발 내용을 올리고, 기능이 완성되면 `alpha`, 출시할 때 `release`.
- AI 작업 전용 저장소임. PR·사람 승인 없이 `develop`에 바로 커밋·push함.
- 커밋은 `docs: ...`, `feat: ...`처럼 영문 타입과 한글 설명을 씀.
- 건강정보·실사용 기록·인증정보를 커밋하지 않음. 문서와 시안의 약 이름은 가상의 예시임.
- 기획 문서는 덮어쓰지 않고 새 판(v4 …)으로 추가함.
- 다이어그램은 Mermaid로 작성해 GitHub에서 바로 보이게 함.
