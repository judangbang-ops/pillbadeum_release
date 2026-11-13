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
| [01 도메인 설계](docs/01-domain/domain.md) | 경계 CTX-001~005와 용어집 TERM-001~031. Miro 보드와 맞춤 | 확인 1~3 지남 (2026-11-12 16:03 KST) |
| [02 요구사항](docs/01-planning/requirements.md) | 요구 REQ-001~095, 합격 기준, v3 뒤에 정한 결정(0장) | 범위 확인 지남 (2026-11-12 16:32 KST). REQ-095는 04 고정 때 더함 |
| [03 사용자 흐름](docs/03-user-flow/README.md) | 흐름 FL-001~010. 흐름마다 Mermaid 그림 | 확인 끝 (2026-11-12 23:50 KST) |
| [04 기능 명세](docs/04-functional-spec/spec.md) | 동작마다 한 줄인 SPEC-001~044(출처, 전제, 예외, 합격 기준) | 고정 (2026-11-13 00:16 KST) |
| [기록 규칙 보충](docs/04-spec/record-rules-supplement.md) | 04 명세가 따르는 규칙(I·B·R·S·P·N·W·C)과 반례 RR-26~90. 주 1회 약은 결정 Q15의 1차 방식 | 개발 기준 (2026-11-12, 2026-11-13 P-9·RR-90 더함) |
| [05 ERD](docs/05-erd/README.md) · [`db/schema.sql`](db/schema.sql) | 표 TBL-001~011. Room이 내보낸 스키마와 같음 | 고정 (2026-11-13 01:04 KST) |
| [07 화면 목록](docs/05-ui/sitemap.yaml) · [08 화면 상태](docs/05-ui/screens.md) | 화면 SCR-001~010과 화면별 상태, 화면에서 정한 것 | 승인 (2026-11-13 01:04 KST) |
| [09 목업](docs/09-mockup/README.md) | 2안 요일 약통 보드와 토큰([`design/base.tokens.json`](design/base.tokens.json)) | 확인 끝 (2026-11-12 19:11 KST). 글꼴만 2026-11-13에 기기 글꼴로 바꿈 |
| [10 작업 분해](docs/08-wbs/wbs.md) | 작업 TASK-001~054, 담당, 릴리즈 단계 R0~R5와의 대응. 지금 프로토타입에서 Play 비공개 테스트까지 | 다음 묶음 계획 확인 (2026-11-13 01:04 KST) |
| [12 QA 시험 계획](docs/12-qa/test-plan.md) | 시험 사례 TC-001~088과 버그 BUG ID. 이전 QA 보고서는 [`docs/07-qa/`](docs/07-qa/) | 초안, 출시 판단 전 |
| [설계 독립 검토](docs/06-review/design-review-2026-11-11.md) | 2026-11-11 독립 검토 의견과 근거 파일 | 끝남 (PR #3) |
| [범위 확정 반영 검토](docs/06-review/scope-confirm-review-2026-11-12.md) | 2026-11-12 Fable 5.2 독립 검토 의견(조건부 통과). 반영한 곳은 각 문서에 '2026-11-12 검토'로 적음 | 반영함 (PR #5) |
| [.corthex/project.json](.corthex/project.json) | CORTHEX 단계별 원본 위치와 확인 기록 요약. 06 API와 13 발표는 뺌 | |

문서를 고치면 `python3 .github/scripts/check_docs.py`로 요구·흐름·명세·스키마·화면·작업 분해·시험 계획·기록 규칙 보충이 서로 맞는지와 문서 안 링크를 대조함. PR마다 CI의 '문서 대조' 작업에서도 돎.

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
- PR마다 GitHub Actions([`.github/workflows/ci.yml`](.github/workflows/ci.yml))가 위 두 명령을 돌리고, 디버그 APK와 릴리즈 APK의 권한 목록을 [`androidApp/expected-permissions.txt`](androidApp/expected-permissions.txt)와 비교함. `INTERNET` 권한이 있으면 실패함(REQ-057). 결과 표는 실행 요약에, 디버그 APK는 7일 보관 아티팩트에 있음. 2026-11-12 첫 실행(PR #2)에서 JVM 테스트 71개 통과, 디버그 APK 빌드됨

### 릴리즈 서명 (TASK-002)

키를 둘로 나눔 (요구 4장 2번, 2026-11-12). 둘 다 PKCS12, RSA 4096이고 2054-03-30까지 유효함. 키 파일과 비밀번호는 저장소에 두지 않음.

| 키 (별칭) | 쓰는 곳 | 있는 곳 | 인증서 SHA-256 (공개값) |
| --- | --- | --- | --- |
| 앱 서명 키 (`app-signing`) | 개인 APK 서명. Play 앱 서명에도 넘김(첫 비공개 테스트 전). 잃으면 되살릴 수 없음 | 동희님 PC와 Google Drive 폴더. GitHub에는 없음 | `35:64:B6:82:4B:1E:3D:A0:74:7A:2E:97:96:6E:F5:5D:B9:CC:93:C5:E8:8A:7D:45:24:64:2F:E6:2A:32:95:36` |
| 업로드 키 (`upload`) | Play에 올릴 AAB 서명. 잃으면 Play에서 재설정 | GitHub Secrets `ANDROID_UPLOAD_KEYSTORE_BASE64`·`ANDROID_UPLOAD_KEYSTORE_PASSWORD`와 동희님 PC | `7D:23:C2:A8:C9:1B:96:4A:08:14:D5:70:12:CB:38:93:CF:25:BF:68:E7:A9:B3:43:6E:AA:87:FA:D5:2D:39:BB` |

- Gradle(`androidApp/build.gradle.kts`)은 환경 변수 `PILLBADEUM_KEYSTORE_FILE`·`PILLBADEUM_KEYSTORE_PASSWORD`·`PILLBADEUM_KEY_ALIAS`가 모두 있을 때만 release 빌드에 서명함. 하나도 없으면 서명하지 않은 release 빌드를 만들고, 일부만 있으면 멈춤. release 빌드는 debuggable이 아니고 코드 축소(R8)를 끔(TASK-046에서 다시 정함)
- 버전 번호(`versionCode`)는 CI가 넘기는 `PILLBADEUM_VERSION_CODE`이고, 빌드한 커밋까지의 커밋 수(`git rev-list --count HEAD`)임. Play용 AAB와 개인 APK가 한 실행에서 나와 같은 번호를 쓰고, main에 커밋이 쌓일수록 커짐. 같은 커밋은 같은 번호라 Play에 이미 올린 번호를 다시 올리려면 새 커밋이 필요함. main을 강제로 덮어쓰면(force push) 번호가 줄 수 있으니 하지 않음. 환경 변수가 없는 로컬 빌드는 1
- [`release.yml`](.github/workflows/release.yml)은 main 푸시(앱 빌드에 닿는 파일이 바뀔 때)·`v*` 태그·수동 실행에서만 돌고 PR에서는 돌지 않음. 빌드할 커밋이 main 이력 안에 없으면(다른 브랜치에서 수동 실행, main 밖 커밋의 태그) 빌드 전에 멈춤. 업로드 키 별칭 `upload`는 공개값이라 이 파일에 적음(2026-11-12에 같이 등록한 Secret `ANDROID_UPLOAD_KEY_ALIAS`는 읽지 않음. 비밀값으로 넘기면 그 작업 로그의 'upload'가 모두 가려짐). 서명하지 않은 릴리즈 APK와 업로드 키로 서명한 AAB를 만들고, AAB 서명 인증서가 업로드 키 지문과 같은지와 APK의 버전 번호·debuggable·정렬을 [`check_release.py`](.github/scripts/check_release.py)로, 권한 목록을 `check_permissions.py`로 확인함. APK는 `release-apk-unsigned` 아티팩트(7일, 태그는 30일), AAB는 태그·수동 실행에서만 `release-aab` 아티팩트(30일)로 남김
- PR에서는 ci.yml이 그 실행에서만 쓰는 임시 키로 같은 서명 경로를 시험하고 같은 확인을 함. 업로드 키는 쓰지 않음
- 개인 APK는 동희님 PC에서 [`scripts/sign-personal-apk.ps1`](scripts/sign-personal-apk.ps1)로 만듦. 최근 main 릴리즈 빌드의 APK를 받아 앱 서명 키로 서명하고, `apksigner verify --print-certs`의 SHA-256이 앱 서명 키 지문과 같은지와 정렬·패키지 정보를 확인한 뒤에야 `%USERPROFILE%\Downloads\pillbadeum-<versionName>-<versionCode>-personal.apk`로 내보냄. 개인 APK로 서명하는 것은 main에 들어간 커밋의 release.yml 성공 실행뿐임. 다른 실행(`-RunId`, ci.yml의 PR 실행 등)이나 파일(`-Apk`)은 `-Test`를 붙여야 서명하고 이름이 `-test.apk`가 됨. 버전 번호가 뒤의 main 빌드보다 클 수 있어 쓰는 폰에는 깔지 않음. 비밀번호는 PC의 DPAPI 파일에서 읽어 환경 변수로만 넘김. PC에 Android SDK build-tools 35 이상(2026-11-12에 37.0.0 설치), JDK 17, 로그인한 `gh`가 필요함

## 작업 규칙

- 작업 브랜치와 초안 PR로 올리고, CI가 통과하면 Claude가 순서를 확인해 `main`에 합침. 충돌이나 실패가 있으면 멈추고 동희님께 알림 (결정 Q14, 2026-11-12)
- 건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음. 문서의 약 이름과 사람 이름은 가상의 예시임
