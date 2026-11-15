# 진행 기록

필받음 1차 릴리즈 작업의 진행·결정 기록. 새 것이 위에 옴.

- 2026-11-15부터 기록은 이 파일에 씀(`.corthex/project.json`의 `records`도 이 파일). [노션 '최근 업데이트'](https://app.notion.com/p/415bf16cc3ec81898818f38c317943e1)에는 작업 묶음을 마치고 보고할 때 그때까지의 최종본을 한 번만 올림
  - **정함**: 2026-11-15 15:31 KST쯤 동희님이 '필받음 재구축 인계' 채팅에서 "야 노션 권한요청 작작해 깃으로 관리해 노션에는 한번에 최종본만 올리고"
  - 그 전까지는 PR마다 노션 행을 썼음. 노션에 따로 쓴 마지막 행은 PR #33(아래)
- 칸: 목표, 한 일, 확인한 것, 확인하지 못한 것, 다음 행동. 숫자는 이름을 적은 명령 출력이나 실행에서 옮김
- 비밀값(키, 비밀번호, 폰 PIN)은 쓰지 않음

## 2026-11-15

### TASK-035 개인정보처리방침 초안 ([PR #36](https://github.com/judangbang-ops/pillbadeum_release/pull/36))

- **목표**: Play Console과 앱 설정(SCR-009)에 걸 개인정보처리방침의 글을 앱이 실제로 하는 일에 맞춰 씀(REQ-063, 검토 B9). 공개는 동희님 승인 뒤
- **한 일**: 앱 코드는 바꾸지 않음
  - [방침 초안](../release/privacy-policy.md): 공개할 글만 담음. 앱이 휴대폰에 저장하는 것(표), 개발자가 받는 것 없음(인터넷 권한 없음, 광고·분석·오류 보고·AI 없음), Google 백업과 기기 간 이전, 알림·위젯에 보이는 것, 보관 기간과 지우는 방법(항목 완전 삭제, 앱 데이터 삭제, Google 백업 지우기), 지키는 방법, 권리, 보호책임자와 신고 기관, 바뀔 때. 앱 이름·개발자·연락처·공개 주소·시행일은 `{ }` 칸
  - [근거와 정할 것](../release/privacy-policy-basis.md): 문장마다의 근거(스키마·매니페스트·백업 규칙·QA 보고서·요구), Play 사용자 데이터·건강 앱 정책과 개인정보 보호법 제30조·시행령 제31조 항목 대조, 동희님이 정할 것, 확인하지 못한 것, 공개한 뒤 할 일
  - README 문서 표, wbs TASK-035 진행 메모
- **확인한 것**
  - 앱 쪽 문장은 코드와 기존 보고서에서 옮김: 기기 식별자·광고 ID를 읽는 코드 없음(검색), 잠금 화면 이름 표시는 설정이 없으면 끔(`ManageSettings.showNamesOnLock`), 위젯 안내 문구는 '이미 기록됨'·'건너뜀으로 기록됨'·오류 때만 생김(`Labels.recordMessage`), 알림 기본값 1시간·하루 3번(`ReminderSettings`)
  - 요건: 조사 에이전트가 Play 도움말 5쪽, law.go.kr 조문, 개인정보보호위원회 작성지침(2026.4.), 기관 누리집을 읽음. Android 자동 백업·백업 개요 문서와 Google One 도움말(한국어)의 문장은 Claude가 원문에서 다시 확인함
  - 문서 대조(`check_docs.py`) 문제 없음
- **확인하지 못한 것**: Play 도움말 원문(자동 접속 확인 화면이 떠 Claude가 다시 열지 못함), 개발자가 정보를 받지 않을 때 개인정보처리자인지, 자동 백업이 데이터 보안 양식의 '수집'인지(도움말에 없음, TASK-036), Smart Switch 이전(TASK-032)
- **알게 된 것**: 예전 방침에 흔한 대검찰청 신고 창구는 2026-10-02 공소청법 시행으로 바뀌었고 작성지침(2026.4.) 예시에도 없어 넣지 않음. 앱 백업이 드라이브 용량에 드는지는 Google 문서끼리 어긋나 방침에 적지 않음
- **정할 것 (동희님)**: 앱 이름(TASK-045), 방침에 적을 개발자 이름, 연락처(이메일, 전화번호를 넣을지), 공개 위치(추천: 이 공개 저장소의 파일 주소), 문안 승인과 시행일
- **다음 행동**: PR #36 검사가 끝나면 머지. 이어서 TASK-036(Play '앱 콘텐츠' 답 초안)과 TASK-037(스토어 설명 초안)

### TASK-012 남은 몫: 옛 빌드에서 덮어 설치하는 업데이트 확인 ([PR #35](https://github.com/judangbang-ops/pillbadeum_release/pull/35))

- **목표**: TASK-012의 남은 둘을 끝냄. (1) 대상자 고르기 전부터 놓인 위젯은 '전체'(보충 R-3, RR-74)를 앱 시험이 아니라 실제 런처·실제 덮어 설치로 봄. (2) `onRestored` 실험 결과를 적음
- **한 일**: 앱 코드는 바꾸지 않음
  - `scripts/emulator/upgrade_check.py`와 `.github/workflows/upgrade.yml`(새로): 한 실행에서 대상자 고르기 전 커밋 `8c50d47`과 PR 커밋을 디버그 APK로 빌드하고, 옛 빌드에서 대상자 둘(나 Vitamin, 옛 항목 화면의 [+ 사람 추가]로 만든 Mom BPpill)로 위젯을 놓아 위젯에서 기록한 뒤 `adb install -r`로 올림. 앱 코드나 그 스크립트·도우미를 바꾼 PR마다 돎. 수동 실행은 입력 `from`으로 옛 커밋을 바꿈
  - [QA 보고서](../07-qa/2026-11-15-upgrade/report.md), wbs TASK-012 **끝남**과 진행 메모, 시험 계획 TC-052와 방법 목록
- **확인한 것**
  - 3회차 [43692177610](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43692177610)(`47aefbd`, 버전 번호 111 → 241)에서 6줄 모두 통과: 앱을 열기 전부터 새 빌드가 다시 그린 그 위젯이 '나 Vitamin'·'Mom BPpill'을 보이고 다시 설정을 묻지 않음, 첫 실행 안내 한 번, 채움 로그 한 번과 저장된 선택 `*`, 그 위젯에서 누른 Mom 항목이 기록됨(활성 판정 2건 모두 `WIDGET`), 대조(상태를 지우면 "위젯을 다시 설정해 주세요", 다시 채우지 않음)
  - 채운 때: 세 실행 모두 덮어 설치 0.588~0.997초 뒤, 시스템이 깨운 새 빌드의 첫 프로세스(1·3회차 경계 알람 수신기, 2회차 위젯 수신기)가 채웠고 앱을 연 것은 그 8.061~9.426초 뒤
  - 1회차 [43689285870](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43689285870)·2회차 [43691218110](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43691218110)도 그때 스크립트로 6줄 통과. 세 실행 로그캣에 앱의 `FATAL EXCEPTION`·ANR 없음
- **고친 것(확인 절차)**: 1회차의 1·2단계 화면 덤프가 탭 전 화면이었음(`save_ui`가 마지막 덤프를 남김), 3단계 기대가 "첫 실행에서 채움"이었음, 업데이트 전 대상자가 하나라 채우기가 늦거나 빠져도 통과할 수 있었음. 셋 다 고침(보고서 5장)
- **확인하지 못한 것**: `onRestored`가 실제로 오는지(에뮬레이터는 앱을 지웠다가 복원해 위젯이 사라짐. TASK-032 기기 간 이전에서 `onRestored: old … -> new …` 줄을 봄), 실제로 나눠 준 서명 APK 위의 업데이트(TASK-047 뒤), Play를 거친 업데이트(TASK-048), 앱을 열기 전 위젯 탭, 갤럭시 런처
- **알게 된 것**: 첫 실행 안내가 생기기 전 빌드에서 올리면 안내가 한 번 다시 뜸. 개인 APK를 아직 나눠 주지 않아 실제 사용자에게는 일어나지 않음
- **다음 행동**: PR #35의 남은 에뮬레이터 검사가 끝나면 머지. 이어서 R4 남은 것(TASK-035·036은 Claude 초안, TASK-045는 앱 이름을 여쭌 뒤, TASK-032·052는 실기기)
- **머지**: 2026-11-15 17:35 KST에 main에 머지함(`3f1cd39`). 머지 전 마지막 푸시(문서 `fd624bf`)에서 업데이트 확인이 한 번 더 돎: 4회차 [43694560118](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43694560118)(빌드 `75d7de7`, 버전 번호 111 → 242) 6줄 통과, 덮어 설치 0.881초 뒤 채움, 로그캣에 `FATAL EXCEPTION`·ANR 없음. CI [43694560117](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43694560117) 통과, 알림·기기 스크립트·릴리즈 변형 확인은 바뀐 파일이 없어 gate가 건너뜀

### TASK-046 릴리즈 변형 확인 ([PR #34](https://github.com/judangbang-ops/pillbadeum_release/pull/34))

- **목표**: 핵심 흐름 가운데 릴리즈 빌드로 보지 않은 알림 버튼을 릴리즈 변형 확인에 더하고, 코드 축소(R8)를 켠 빌드를 같은 흐름으로 돌려 끈 빌드와 견줌. 켤지는 결과를 보고 정함(1차 기본은 끔, TASK-002)
- **한 일**
  - `scripts/emulator/release_check.py` 6단계: N+1 릴리즈 빌드에서 [알림 받기]를 켠 항목 셋을 앱으로 만들고, 시계를 정확 알람 1분 전으로 옮겨 울린 뒤 알림창에서 [먹었어]·[오늘 건너뜀]·[30분 뒤]를 누름. 7단계(판정 아님): APK 크기·dex 수, 콜드 스타트 5회
  - `androidApp/build.gradle.kts`: `-Ppillbadeum.minify=true`일 때만 코드·리소스 축소를 켬(기본 끔), 규칙 파일 `androidApp/proguard-rules.pro`
  - `release-emulator.yml`: 수동 실행 입력 `minify`, 축소한 APK의 권한 비교, R8 출력 아티팩트
  - [QA 보고서](../07-qa/2026-11-15-release-variant/report.md), wbs TASK-046 **끝남**과 진행 메모, 시험 계획 TC-011·TC-012
- **정함**: 1차는 코드 축소를 끈 채로 둠. Claude가 정함(담당 작업의 판단이고 TASK-002의 기본을 바꾸지 않음). 까닭: 얻는 것은 APK 크기뿐, AGP 8.13.2의 R8이 Kotlin 2.4.21 메타데이터를 읽지 못한다고 경고함, 나머지 확인은 축소 안 한 빌드로 돎, 켜면 배포마다 mapping 보관이 늚. 반대 의견: 크기가 절반이고 켠 빌드도 15개 경우를 모두 통과함, 출시 빌드는 보통 축소함. 다시 볼 때는 R8이 Kotlin 2.4를 읽게 될 때나 2차(보고서 4장). 처음 이 칸에는 "켤지는 동희님께 여쭘"이라 적었으나, 기본을 그대로 두는 판단이라 Claude가 정하고 보고로 바꿈
- **확인한 것**
  - 릴리즈 변형 확인 두 실행이 15개 경우를 모두 통과(둘 다 처음 돌린 실행): 축소 끔 [43685796448](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43685796448)(PR, `04891a2` = `93751ee` + main, 버전 번호 236 → 237), 축소 켬 [43685794993](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43685794993)(수동 실행, `93751ee`, 235 → 236)
  - 6단계: 정확 알람 2026-11-16 08:00:00(`window=0`), 울린 알림 4개(복용 알림 셋 `SECRET`·버튼 3개, 잠금 화면용 "복용 시간이에요" `PUBLIC`), PillA `TAKEN`·PillB `SKIPPED`(둘 다 `NOTIFICATION`), PillC 미룸 누른 때부터 30.1분, 남은 알림 0개, 다음 알람이 미룬 시각(끔 08:30:42, 켬 08:30:37). 알림창 캡처는 두 실행이 바이트까지 같음
  - 7단계: APK 14,240,527 → 7,320,834 바이트(dex 3개 24.09 MB → 1개 3.22 MB), 콜드 스타트 중앙값 918 → 928 ms(5회씩, 퍼짐이 더 커서 차이를 말할 수 없음). R8 경고 빌드마다 16줄
  - 서명한 APK 넷의 권한이 기준 목록과 같음(8줄, `INTERNET` 없음). CI [43685796434](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43685796434) 통과
- **확인하지 못한 것**: 실기기의 릴리즈 빌드 알림·잠금 화면(TASK-032·052), 잠금 화면에서 누른 버튼과 미룬 알람이 다시 울리는 것의 릴리즈 몫(디버그 빌드로만 봄), Play에서 받는 크기(TASK-038), 실기기 콜드 스타트
- **다음 행동**: PR #34의 남은 에뮬레이터 검사가 끝나면 머지. 이어서 R4 남은 것(TASK-032·052는 실기기), TASK-035·036, TASK-045는 앱 이름을 여쭌 뒤
- **머지**: 2026-11-15 16:09 KST에 main에 머지함(`92983ce`). 머지 전 마지막 푸시(문서)의 CI [43688375033](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43688375033) 통과, 에뮬레이터 검사 9개는 그 푸시가 앱 코드·스크립트를 바꾸지 않아 gate가 건너뜀

### 동희님 결정 반영: REQ-003 함수 이름, 회귀 확인 범위, 알림 제목 ([PR #33](https://github.com/judangbang-ops/pillbadeum_release/pull/33), `9e59f10`)

- **목표**: TASK-034 보고([PR #32](https://github.com/judangbang-ops/pillbadeum_release/pull/32))의 '정해 주셔야 할 것' 셋을 반영함. **정함**: 2026-11-15 14:58 KST 동희님이 '필받음 재구축 인계' 채팅에서 "추천 모두 승인"(셋 다 Claude 추천)
- **한 일**: 2026-11-15 15:28 KST에 main에 머지함. 앱 코드는 바꾸지 않음
  - REQ-003 문장의 공통 기록 함수를 'RecordDose·VoidDose·RecordInWindow'로 고침([요구 0장](../01-planning/requirements.md))
  - 회귀 확인(`regression.yml`)이 앱 코드(`shared/`, `androidApp/`, Gradle 설정)를 바꾼 PR에서도 돎. 시험 코드만 바꾼 PR은 뺌
  - 알림 제목에는 낱말 잇기(U+2060)를 넣지 않고 그대로 둠([접근성 점검 보고서 7절](../07-qa/2026-11-14-a11y/report.md))
- **확인한 것**
  - CI [43683631504](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43683631504)(`93e9cf5`): 문서 대조 통과, jvm 333개·앱 시험 122개 통과, 디버그·릴리즈 APK 권한이 기준 목록과 같음(8줄, `INTERNET` 없음), 개인정보 점검 통과
  - 에뮬레이터 회귀 확인 [43683631505](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43683631505)(API 34, 약 25분): 준비와 1~15번 16줄 모두 통과
- **확인하지 못한 것**: 앱 코드를 바꾼 PR에서 회귀 확인이 실제로 도는지는 다음 앱 코드 PR에서 처음 봄(PR #33은 워크플로 파일을 바꿔서 돎)
- **다음 행동**: 같은 함수 이름이 고정된 04 명세 SPEC-043과 화면 정의 SCR-007에도 있음. 같은 이름으로 고칠지 동희님께 여쭘(추천: 고침. 동작은 같고 이름만 코드에 맞춤)
