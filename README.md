# pillbadeum_release

필받음 1차 릴리즈 저장소임. AI 없이 기기 안에만 저장하는 Android 복용 기록 앱(홈 화면 위젯 한 번으로 기록, 이중 복용 방지)을 Google Play 비공개 테스트까지 내는 설계·작업 분해와 앱 코드를 둠.

> 앱은 기록·표시 도구임. 복용량 권고, 빠뜨렸을 때 처치, 약물 상호작용 판정 같은 의학적 판단은 하지 않음.

## DidITake와의 관계

- [judangbang-ops/DidITake](https://github.com/judangbang-ops/DidITake)는 가쟈누님이 기획을 올리는 저장소임. 기획 v1~v3, 기록 규칙, 데이터 모델, 아키텍처, 플로우, UI 초안, 프로토타입 코드와 QA가 거기 있음
- 이 저장소의 문서는 DidITake 문서를 원본으로 삼고 빈 곳만 채움. DidITake로 가는 링크는 모두 읽은 판인 `7a800ac`(2026-11-11 19:54 KST)를 가리킴
- 앱 코드는 이 저장소에서 이어 감 (동희님 결정, 2026-11-11 21:52 KST). DidITake develop `7a800ac`의 프로토타입을 커밋 이력째 가져왔고(합친 커밋 `23d36ae`), DidITake의 `docs/`와 README는 가져오지 않음. 코드 주석에 나오는 `data-model.md`·`record-rules.md`·`architecture.md`·`planning-v3.md`는 DidITake `docs/`의 문서임
- DidITake에 `7a800ac` 뒤로 코드 변경이 생기면 같은 방식(`git merge`)으로 합침

## 문서

| 문서 | 내용 | 상태 |
| --- | --- | --- |
| [기획 v4](docs/01-planning/planning-v4.md) | DidITake 기획 v3 뒤에 정한 결정(1차 범위, DB만 백업, Play 비공개 테스트)으로 바뀐 점 | 초안 |
| [요구사항](docs/01-planning/requirements.md) | 요구 REQ-001~072, 합격 기준, 범위 확정 때 정할 것 | 초안 |
| [기록 규칙 보충](docs/04-spec/record-rules-supplement.md) | 설계 독립 검토(PR #3) A1·A2·A4·A5·A6을 규칙과 반례 RR-26~58로 적음. A3은 결정 Q15 대기 | 초안 |
| [화면 목록](docs/05-ui/sitemap.yaml) · [화면 상태](docs/05-ui/screens.md) | 화면 SCR-001~010과 화면별 상태 | 초안 |
| [작업 분해](docs/08-wbs/wbs.md) | 작업 TASK-001~039, 지금 프로토타입에서 Play 비공개 테스트까지 | 초안 |
| [.corthex/project.json](.corthex/project.json) | 단계별 원본 위치 (CORTHEX) | |

## 빌드와 실행

[DidITake README의 빌드와 실행](https://github.com/judangbang-ops/DidITake/blob/7a800ac/README.md#빌드와-실행)(`7a800ac`)과 같음. 앱 소스는 `shared/`(공통 규칙·DB·앱 화면)와 `androidApp/`(Android 앱·위젯)에 있음.

준비: JDK 17, Android SDK(platform 36). 저장소 루트에 `local.properties`를 만들고 `sdk.dir=<Android SDK 경로>`를 적음 (커밋하지 않음).

```bash
./gradlew :shared:jvmTest            # 공통 규칙·DB 테스트 (JVM)
./gradlew :androidApp:assembleDebug  # 디버그 APK → androidApp/build/outputs/apk/debug/
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

- 디버그 서명 APK임. 정식 서명 키로 바꾸면 한 번 지우고 다시 설치해야 하고, 기기에 저장된 기록은 사라짐 (REQ-062, TASK-002)
- 가져온 뒤 이 저장소에서는 아직 빌드·테스트를 돌리지 않음. DidITake README에는 JVM 테스트 71개 통과로 적혀 있음

## 작업 규칙

- 작업 브랜치와 PR로 올리고, 동희님이 보고 `main`에 합침
- 건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음. 문서의 약 이름과 사람 이름은 가상의 예시임
