# pillbadeum_release

필받음 1차 릴리즈 저장소임. AI 없이 기기 안에만 저장하는 Android 복용 기록 앱(홈 화면 위젯 한 번으로 기록, 이중 복용 방지)을 Google Play 비공개 테스트까지 내는 설계와 작업 분해를 둠.

> 앱은 기록·표시 도구임. 복용량 권고, 빠뜨렸을 때 처치, 약물 상호작용 판정 같은 의학적 판단은 하지 않음.

## DidITake와의 관계

- [judangbang-ops/DidITake](https://github.com/judangbang-ops/DidITake)는 가쟈누님이 기획을 올리는 저장소임. 기획 v1~v3, 기록 규칙, 데이터 모델, 아키텍처, 플로우, UI 초안, 프로토타입 코드와 QA가 거기 있음
- 이 저장소의 문서는 DidITake 문서를 원본으로 삼고 빈 곳만 채움. DidITake로 가는 링크는 모두 읽은 판인 `7a800ac`(2026-11-11 19:54 KST)를 가리킴
- 앱 코드를 어느 저장소에서 이어 갈지는 아직 정하지 않음

## 문서

| 문서 | 내용 | 상태 |
| --- | --- | --- |
| [기획 v4](docs/01-planning/planning-v4.md) | DidITake 기획 v3 뒤에 정한 결정(1차 범위, DB만 백업, Play 비공개 테스트)으로 바뀐 점 | 초안 |
| [요구사항](docs/01-planning/requirements.md) | 요구 REQ-001~072, 합격 기준, 범위 확정 때 정할 것 | 초안 |
| [화면 목록](docs/05-ui/sitemap.yaml) · [화면 상태](docs/05-ui/screens.md) | 화면 SCR-001~010과 화면별 상태 | 초안 |
| [작업 분해](docs/08-wbs/wbs.md) | 작업 TASK-001~039, 지금 프로토타입에서 Play 비공개 테스트까지 | 초안 |
| [.corthex/project.json](.corthex/project.json) | 단계별 원본 위치 (CORTHEX) | |

## 작업 규칙

- 작업 브랜치와 PR로 올리고, 동희님이 보고 `main`에 합침
- 건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음. 문서의 약 이름과 사람 이름은 가상의 예시임
