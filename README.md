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
| 업로드 키 (`upload`) | Play에 올릴 AAB 서명. 잃으면 Play에서 재설정 | GitHub Secrets `ANDROID_UPLOAD_KEYSTORE_BASE64`·`ANDROID_UPLOAD_KEYSTORE_PASSWORD`·`ANDROID_UPLOAD_KEY_ALIAS`와 동희님 PC | `7D:23:C2:A8:C9:1B:96:4A:08:14:D5:70:12:CB:38:93:CF:25:BF:68:E7:A9:B3:43:6E:AA:87:FA:D5:2D:39:BB` |

- Gradle(`androidApp/build.gradle.kts`)은 환경 변수 `PILLBADEUM_KEYSTORE_FILE`·`PILLBADEUM_KEYSTORE_PASSWORD`·`PILLBADEUM_KEY_ALIAS`가 모두 있을 때만 release 빌드에 서명함. 하나도 없으면 서명하지 않은 release 빌드를 만들고, 일부만 있으면 멈춤. release 빌드는 debuggable이 아니고 코드 축소(R8)를 끔(TASK-046에서 다시 정함)
- 버전 번호(`versionCode`)는 CI가 넘기는 `PILLBADEUM_VERSION_CODE`이고, 빌드한 커밋까지의 커밋 수(`git rev-list --count HEAD`)임. Play용 AAB와 개인 APK가 한 실행에서 나와 같은 번호를 쓰고, main에 커밋이 쌓일수록 커짐. 같은 커밋은 같은 번호. 환경 변수가 없는 로컬 빌드는 1
- [`release.yml`](.github/workflows/release.yml)은 main 푸시(앱 빌드에 닿는 파일이 바뀔 때)·`v*` 태그·수동 실행에서만 돌고 PR에서는 돌지 않음. 서명하지 않은 릴리즈 APK와 업로드 키로 서명한 AAB를 만들고, AAB 서명 인증서가 업로드 키 지문과 같은지와 APK의 버전 번호·debuggable·정렬을 [`check_release.py`](.github/scripts/check_release.py)로, 권한 목록을 `check_permissions.py`로 확인함. APK는 `release-apk-unsigned` 아티팩트(7일, 태그는 30일), AAB는 태그·수동 실행에서만 `release-aab` 아티팩트(30일)로 남김
- PR에서는 ci.yml이 그 실행에서만 쓰는 임시 키로 같은 서명 경로를 시험하고 같은 확인을 함. 업로드 키는 쓰지 않음
- 개인 APK는 동희님 PC에서 [`scripts/sign-personal-apk.ps1`](scripts/sign-personal-apk.ps1)로 만듦. 최근 main 릴리즈 빌드의 APK를 받아 앱 서명 키로 서명하고, `apksigner verify --print-certs`의 SHA-256이 앱 서명 키 지문과 같은지 확인함. 결과는 `%USERPROFILE%\Downloads\pillbadeum-<versionName>-<versionCode>-personal.apk`. 비밀번호는 PC의 DPAPI 파일에서 읽어 환경 변수로만 넘김. PC에 Android SDK build-tools 35 이상과 JDK 17, 로그인한 `gh`가 필요함

## 작업 규칙

- 작업 브랜치와 PR로 올리고, 동희님이 보고 `main`에 합침
- 건강정보·실사용 기록·인증정보·서명 키를 커밋하지 않음. 문서의 약 이름과 사람 이름은 가상의 예시임
