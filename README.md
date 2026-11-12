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
| [기획 v4](docs/01-planning/planning-v4.md) | DidITake 기획 v3 뒤에 정한 결정(1차 범위, 백업, Play 비공개 테스트, 범위 확정)으로 바뀐 점 | 범위 확정 반영 (2026-11-12) |
| [요구사항](docs/01-planning/requirements.md) | 요구 REQ-001~083, 합격 기준, 범위 확정 때 정한 것 | 1차 범위 확정 (2026-11-12 10:18 KST) |
| [기록 규칙 보충](docs/04-spec/record-rules-supplement.md) | 설계 독립 검토(PR #3)의 A1~A6과 B3을 규칙과 반례 RR-26~67로 적음. 주 1회 약은 결정 Q15의 1차 방식 | 개발 기준 (2026-11-12) |
| [화면 목록](docs/05-ui/sitemap.yaml) · [화면 상태](docs/05-ui/screens.md) | 화면 SCR-001~010과 화면별 상태, 화면에서 정한 것. 상태별 와이어프레임은 아직 없음(TASK-044) | 범위 확정 반영 (2026-11-12) |
| [설계 독립 검토](docs/06-review/design-review-2026-11-11.md) | 2026-11-11 독립 검토 의견과 근거 파일 | 끝남 (PR #3) |
| [작업 분해](docs/08-wbs/wbs.md) | 작업 TASK-001~049, 담당, 릴리즈 단계 R0~R5와의 대응. 지금 프로토타입에서 Play 비공개 테스트까지 | 범위 확정 반영 (2026-11-12). 담당은 Claude 제안 |
| [.corthex/project.json](.corthex/project.json) | 단계별 원본 위치 (CORTHEX) | |

문서를 고치면 `python3 .github/scripts/check_docs.py`로 요구·화면·작업 분해·기록 규칙 보충이 서로 맞는지와 문서 안 링크를 대조함. PR마다 CI의 '문서 대조' 작업에서도 돎.

## 빌드와 실행

[DidITake README의 빌드와 실행](https://github.com/judangbang-ops/DidITake/blob/7a800ac/README.md#빌드와-실행)(`7a800ac`)과 같음. 앱 소스는 `shared/`(공통 규칙·DB·앱 화면)와 `androidApp/`(Android 앱·위젯)에 있음.

패키지 이름(`applicationId`)은 `com.judangbang.pillbadeum`임 (결정 Q9, 2026-11-12 동희님). Play에 한 번 올리면 바꿀 수 없음. 코드 패키지와 `namespace`도 같은 이름을 씀(`shared`는 `com.judangbang.pillbadeum.shared`).

준비: JDK 17, Android SDK(platform 36). 저장소 루트에 `local.properties`를 만들고 `sdk.dir=<Android SDK 경로>`를 적음 (커밋하지 않음).

```bash
./gradlew :shared:jvmTest            # 공통 규칙·DB 테스트 (JVM)
./gradlew :androidApp:assembleDebug  # 디버그 APK → androidApp/build/outputs/apk/debug/
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

- 디버그 서명 APK임. 정식 서명 키로 바꾸면 한 번 지우고 다시 설치해야 하고, 기기에 저장된 기록은 사라짐 (REQ-062, TASK-002)
- 2026-11-12 전에 빌드한 APK의 패키지 이름은 `com.zmanlab.diditake`임. 지금 APK와 다른 앱으로 깔리고 기록도 옮겨 오지 않으니, 시험하던 폰에서는 따로 지움
- 릴리즈 서명용 업로드 키와 비밀번호는 저장소 밖에 둠. CI에는 GitHub Secrets `ANDROID_UPLOAD_KEYSTORE_BASE64`·`ANDROID_UPLOAD_KEYSTORE_PASSWORD`·`ANDROID_UPLOAD_KEY_ALIAS`로만 넣음(2026-11-12 등록). 릴리즈 서명 설정과 CI 검증은 아직 없음 (TASK-002)
- PR마다 GitHub Actions([`.github/workflows/ci.yml`](.github/workflows/ci.yml))가 위 두 명령을 돌리고, 디버그 APK의 권한 목록을 [`androidApp/expected-permissions.txt`](androidApp/expected-permissions.txt)와 비교함. `INTERNET` 권한이 있으면 실패함(REQ-057). 결과 표는 실행 요약에, 디버그 APK는 7일 보관 아티팩트에 있음. 2026-11-12 첫 실행(PR #2)에서 JVM 테스트 71개 통과, 디버그 APK 빌드됨

## 작업 규칙

- 작업 브랜치와 초안 PR로 올리고, CI가 통과하면 Claude가 순서를 확인해 `main`에 합침. 충돌이나 실패가 있으면 멈추고 동희님께 알림 (결정 Q14, 2026-11-12)
- 건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음. 문서의 약 이름과 사람 이름은 가상의 예시임
