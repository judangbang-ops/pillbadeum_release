# 진행 기록

필받음 1차 릴리즈 작업의 진행·결정 기록. 새 것이 위에 옴.

- 2026-11-15부터 기록은 이 파일에 씀(`.corthex/project.json`의 `records`도 이 파일). [노션 '최근 업데이트'](https://app.notion.com/p/415bf16cc3ec81898818f38c317943e1)에는 작업 묶음을 마치고 보고할 때 그때까지의 최종본을 한 번만 올림
  - **정함**: 2026-11-15 15:31 KST쯤 동희님이 '필받음 재구축 인계' 채팅에서 "야 노션 권한요청 작작해 깃으로 관리해 노션에는 한번에 최종본만 올리고"
  - 그 전까지는 PR마다 노션 행을 썼음. 노션에 따로 쓴 마지막 행은 PR #33(아래)
- 칸: 목표, 한 일, 확인한 것, 확인하지 못한 것, 다음 행동. 숫자는 이름을 적은 명령 출력이나 실행에서 옮김
- 비밀값(키, 비밀번호, 폰 PIN)은 쓰지 않음

## 2026-11-16

### 노션 '최근 업데이트'에 묶음 최종본 한 행 (PR #41~#47)

- **목표**: 2026-11-15 15:31 KST 동희님 지시("노션에는 한번에 최종본만 올리고")대로, PR #34~#39 행 뒤의 작업 묶음을 노션에 한 번만 올림
- **한 일**: 2026-11-16 16:23 KST쯤 [최근 업데이트 행](https://app.notion.com/p/41abf16cc3ec81a2b915c4bd13b82365) 하나를 만듦(종류 '진행·결과', 읽었어요 꺼짐). 본문에 PR #41~#47의 한 일, 새로 알게 된 것 셋, 정해 주실 것·하실 것 넷, 다음 일, 이 파일·스토어 등록정보·그림 폴더·앱 콘텐츠 답·방침·갤럭시 확인 순서의 링크를 둠. 연락 이메일 값은 노션에 쓰지 않음. 새 최상위 페이지나 PR별 행은 만들지 않음
- **정해 주실 것·하실 것 (동희님)**
  1. Play 개발자 지원에 계정 종류(Q11) 문의를 보내고 답을 알려 주심. 글은 [앱 콘텐츠 답](../release/play-app-content.md) 0장. Play 계정 만들기(TASK-048)가 이 답을 기다림
  2. 아이콘·그래픽 이미지·스크린샷을 보심. 바꾸실 것은 업로드(TASK-038) 전에. 알림 장면(2번)이 걱정되시면 빼고 올림
  3. 시험용 갤럭시를 PC에 연결(TASK-032·052와 BUG-004의 실기기 확인 TASK-054)
  4. Play 계정과 앱 만들기, 비공개 테스트 업로드(TASK-048·050·036·038). 업로드와 외부 공개는 동희님 확인을 받은 뒤
- **확인한 것**: 행에 쓴 WBS 숫자(작업 54개 중 44개 끝남, 크기로 46.5일 / 54.0일, 기간이 열린 TASK-047·039 빼고)는 `check_docs.py` 출력과 wbs 표의 **끝남**에서 옮김
- **받은 말**: 16:30 KST쯤, 세션 작업자가 다시 시작된 직후 이 세션에 "추천대로 모두 승인"과 세 줄(앱 이름, 개발자 이름, 연락 이메일)이 다시 옴. 오전 10:05·10:09 KST에 받은 말과 글자까지 같고 [PR #41](https://github.com/judangbang-ops/pillbadeum_release/pull/41)에서 모두 반영해 바꿀 것이 없음. 이번 묶음에서 Claude가 정한 것(아이콘 도형과 색, 보기 데이터, 장면 순서, 그래픽 이미지 배치, BUG-007의 2초 기다림)은 지금대로 둠
- **다음 행동**: 동희님의 Q11 답과 갤럭시 연결을 기다림. 연결되면 [갤럭시 확인 순서](../07-qa/device-check.md)대로 TASK-032·052와 BUG-004의 실기기 확인(2장 4번 `bug004`, TASK-054)을 함
- **바로잡음** (16:45 KST쯤): 처음에는 위 3번과 다음 행동에 'BUG-004를 고치는 TASK-054'라고 썼음. 고침은 [PR #19](https://github.com/judangbang-ops/pillbadeum_release/pull/19)의 `RescheduleReceiverKeeper`에 이미 있고 에뮬레이터 릴리즈 변형 확인에서 통과하며, 남은 것은 실기기 확인이라 고침(근거는 wbs 1.3 진행 메모의 TASK-054). 노션 행 본문에도 'BUG-004 고침(TASK-054)'이 있어 다음 묶음의 노션 행에서 바로잡음
- **머지**: 이 기록은 [PR #48](https://github.com/judangbang-ops/pillbadeum_release/pull/48)으로 2026-11-16 16:37 KST에 main에 머지함(`c7e85a3`). CI [43824546448](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43824546448) 1차 시도에서 통과

### TASK-022 첫 실행 안내 끝 ([PR #47](https://github.com/judangbang-ops/pillbadeum_release/pull/47))

- **목표**: TASK-022(첫 실행 안내와 [위젯 추가])의 남은 것인 앱 이름이 든 캡처와 TalkBack을 확인해 끝냄(SPEC-002, REQ-080·036·074, TC-002)
- **한 일**
  - 새로 돌리지 않고, 이미 돈 에뮬레이터 실행의 아티팩트(7일 보관)를 받아 봄
    - PR #46의 마지막 커밋 `1cf4e34`: 회귀 확인 [43818404064](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818404064) 1단계의 안내 두 쪽, 접근성 확인 [43818404042](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818404042)의 SCR-005 글자 100%·200%
    - 앱 이름을 바꾼 PR #43의 `64ecbe8`: 화면 확인 [43794389361](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389361) 17단계([위젯 추가] → 런처의 놓기 창 → 놓인 위젯), 둘러보기 [43794389204](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389204) 1단계
    - `64ecbe8` 뒤 main의 앱 코드 변경은 테마(BUG-006)와 알림 모으기(BUG-007)뿐이라 안내 화면과 위젯 고정 코드는 그대로임(`git diff 64ecbe8 92c5cb6`)
  - 문서: wbs TASK-022 **끝남**과 메모, 시험 계획 TC-002, 화면 정의 SCR-005, 요구 REQ-080 상태 칸
  - [갤럭시 확인 순서](../07-qa/device-check.md) 2.4: 깨끗한 설치 뒤 첫 실행 안내의 [위젯 추가]를 One UI 홈에서 눌러 보는 단계와 합격 기준을 더함. 3.1의 3번은 그 위젯을 그대로 씀. REQ-080 상태 칸은 실제 런처의 확인 창을 '에뮬레이터·실기기에서' 본다고 했으나 순서에 그 단계가 없었음(기기 스크립트의 `additem`은 안내를 [건너뛰기]로 넘김)
- **확인한 것**
  - 회귀 1단계(`1cf4e34`): 안내 1쪽에 '필받음은 기록 도구예요. 복용 방법은 의사·약사와 상의해 주세요.'(REQ-036)와 '한 약은 한 휴대폰에서 기록해 주세요. 휴대폰끼리는 기록이 이어지지 않아요.'(REQ-074). 2쪽에 '필받음 · 11/12' 위젯 그림, "'위젯'에서 필받음 찾기", [위젯 추가]·[시작하기]. [시작하기] 뒤 '아직 복용 항목이 없어요.'와 [항목 추가]. 상태 표시줄은 BUG-006대로 흰 바탕에 어두운 아이콘
  - 접근성(`1cf4e34`) SCR-005: 글자 100%는 쪽 1개, 누르는 곳 2가지(가장 작은 [건너뛰기] 78.7×48dp). 200%는 쪽 2개(가장 작은 [건너뛰기] 124×48dp). 글자 명암비 최소 5.50:1(ink.muted / surface.sunken). 화면 덤프의 글이 모두 TalkBack이 읽는 말로 있음
  - 화면 확인 17단계(`64ecbe8`): 다시 깐 앱에서 [위젯 추가] → Pixel 런처의 놓기 창('필받음 widget', '4 × 2', [Add to home screen]) → 위젯 [3], 선택 ['*']. 놓인 위젯이 '필받음 · 11/18', '0 / 0 완료', '앱에서 복용 항목을 추가해 주세요'를 그림
  - '첫 실행에만 보임'은 앱 시험 `IntroScreenTest`(`spec002_the_first_start_shows_the_intro_once`, `skipping_leaves_the_intro_for_good`). CI의 앱 화면 시험에서 돎
  - `check_docs.py` no problems, `check_wording.py` 문제 없음
- **확인하지 못한 것**: 갤럭시 홈(One UI)이 고정 요청을 받는지와 그 창의 모양(TASK-032에서 봄)
- **다음 행동**: 이번 묶음(PR #41~#47)의 노션 최종본 한 행과 동희님 보고. 그 뒤 실기기 확인(TASK-032·052·054)은 시험용 갤럭시가 준비되면
- **머지**: 2026-11-16 16:22 KST에 main에 머지함(`a3e3d7d`). CI [43822603911](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43822603911)(`f38c3a1`) 1차 시도에서 통과(문서 대조, JVM 테스트 + 디버그·릴리즈 APK). 문서만 바꿔 에뮬레이터 검사는 워크플로의 경로 조건(`paths`)으로 돌지 않음

### TASK-037 스토어 스크린샷과 TASK-045 그래픽 이미지 ([PR #46](https://github.com/judangbang-ops/pillbadeum_release/pull/46))

- **목표**: Play 스토어용 휴대전화 스크린샷(1080×1920, 24-bit PNG)과 그래픽 이미지(1024×500)를 실제 앱 화면으로 만들어 저장소에 둠(TASK-037, TASK-045). 런처의 앱 목록에 이름과 아이콘이 보이는 캡처도 남김
- **한 일**
  - 워크플로 '스토어 스크린샷'(`store-shots.yml`): API 34 에뮬레이터를 Pixel 2 기기 정의(1080×1920, 420dpi)로 만듦(에뮬레이터 액션에 `device` 입력을 더함, 기본은 지금처럼 pixel_5). 아티팩트 `store-shots-api34`는 7일 보관
  - 스크립트 `scripts/emulator/store_shots.py`(13단계): 설치와 보기 데이터 → 위젯 놓기·키우기 → 한국어로 다시 켜기와 0일 12:29:30 → 지난 한 달 기록 → 데모 모드 → 스토어 2(알림)·1(위젯)·3(첫 화면)·4(지난 기록)·5(항목 수정)·6(글자 200% 위젯) → 앱 목록. 단계마다 화면 덤프와 DB로 판정함
  - 디버그 수신기(`ReminderSeedReceiver`, 디버그 빌드에만 있음): SEED가 시간대(`slot`)와 사람 색(`color`)을 받음. 새 `RECORD`가 지난 날의 먹음·건너뜀을 SCR-007의 지난 날 고치기와 같은 길(`recordIn`)로 씀
  - 위젯 크기 확인의 `resize`가 위·왼쪽 손잡이도 끔. 화면 맨 아래 줄에 놓인 위젯은 위로만 키울 수 있음
  - `scripts/release/store_images.py`: `shots`는 캡처를 24-bit PNG로(`phoneScreenshots/N-이름.png`), `graphic`은 그래픽 이미지를 만듦. 그림은 [스토어 등록정보 4.3·4.4](../release/store-listing.md#43-찍은-장면)에 적음
  - 문서: 스토어 등록정보 1·4.2·4.3·4.4·9장, wbs TASK-037·045 **끝남**과 메모
- **실행과 고친 것** (스토어 스크린샷 워크플로)
  1. [43808874013](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43808874013)(`b4df73e`) 실패
     - 알림이 뜨지 않음. 에뮬레이터는 켤 때 시간대를 컴퓨터의 UTC로, 시계를 실제 날짜(11-16)로 돌림. 그 뒤 시계를 11-13 12:30:20으로 되돌리자 앱이 시계가 뒤로 간 것으로 봄(RR-81). 12:30 칸은 이미 지난 것이 됨 → 다시 켠 뒤 시간대를 다시 맞추고, 시계를 12:29:30에 두고 12:30:05까지 기다린 뒤 알람 방송
     - 위젯 칸이 둘만 보임. 위젯이 런처 격자의 맨 아래 줄이라 아래 손잡이로 키울 수 없었음. 네 칸 폭은 296dp라 한 줄에 칸 하나였음(아래 '본 것') → 왼쪽 손잡이로 화면 폭까지, 위 손잡이로 한 줄 더 키움
     - 첫 화면에 "알림이 늦을 수 있어요" 띠. 정확 알람이 허용되지 않은 새 설치였음 → 준비 단계에서 appops로 허용함. 사람이 [허용하기]로 하는 것과 같음
  2. [43811015299](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43811015299)(`4764314`) 10단계 실패
     - 수정 화면의 '알림 받기'와 '첫 알림 시각, 8시'는 글이 아니라 TalkBack 설명(content-desc)이었음 → 둘 다 봄
     - 항목 넷이면 화면 폭 위젯의 세 줄 가운데 한 줄이 비었음 → 보기 항목을 여섯으로 함
  3. [43812534973](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43812534973)(`e855aee`) 13단계 모두 통과. 그러나 스토어 1의 위젯이 다시 켠 날(`월`, `11/16`, `0 / 6`)에 멈춰 있었음. 7단계는 칸 여섯만 보고 머리는 보지 않았음
     - 원인(logcat과 라이브러리 소스로 봄): 시간대를 바꾸자 앱이 위젯을 새로 그리게 했고(DayBoundaryReceiver), 350ms 뒤 스크립트가 시계를 3일 되돌림
       - Glance는 위젯을 WorkManager 작업인 세션에서 그림. WorkManager 2.7.1은 작업을 넣을 때 휴대폰 시각을 찍고(`EnqueueRunnable`), 그 시각이 아직 오지 않은 작업은 미룸(`GreedyScheduler`). 그래서 3일 뒤로 밀림
       - Glance 1.2.0은 기다리는 세션도 도는 것으로 셈(`SessionManager.isSessionRunning`, RUNNING 또는 ENQUEUED). 그래서 그 뒤의 새로 그리기(시계 바뀜, 기록 쓰기, [먹었어])가 모두 그 세션으로 가고 그려지지 않음
       - 그 뒤 위젯 세션의 작업 결과도, 시스템의 'Widget update received'도 로그에 없음
     - 고친 것(`d8ac143`): 시간대를 바꾼 뒤 앱이 그 처리를 로그에 남길 때까지(최대 60초) 기다렸다가 시계를 바꿈. 7단계가 위젯 머리의 요일·날짜·`4 / 6`을 봄. 세 번째 실행의 덤프에 넣어 세 값이 모두 없다고 잡는 것을, 두 번째 실행의 덤프에서는 날짜와 요일을 찾는 것을 확인함
     - 앱 버그로 적지 않음: 휴대폰에서는 위젯 세션을 넣은 뒤 WorkManager가 그것을 시작하기 전의 아주 짧은 틈에 시계가 뒤로 가야 함. 그때도 위젯은 되돌린 만큼 늦거나 앱 프로세스가 다시 시작될 때 새로 그려짐. 실제 폰에서 본 적은 없음
  4. [43815036745](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43815036745)(`d8ac143`) 13단계 모두 통과
     - 3단계: 앱이 시간대 바뀜을 처리했다는 로그(`DayBoundaryAlarm`)는 스크립트의 표지 뒤 0.33초에 옴(logcat). 보고서에는 '42초 안'이라 나왔으나 이것은 기다림 뒤의 시계 맞추기와 자리 잡기까지 잰 것이라 잘못임 → 기다림이 끝난 때를 재게 고침(`ee487dc`)
     - 7단계: 위젯 머리가 `금`, `필받음 · 11/13`, `4 / 6 완료`
     - 사진을 보니 스토어 6(글자 200%)의 상태 표시줄 시계가 커져 숫자 윗부분이 잘림 → 그 사진을 찍는 동안만 시계를 숨김(`cmd statusbar send-disable-flag clock`, `9086dcf`). Play 도움말도 상태 표시줄을 정리하라고 함
  5. [43816516662](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43816516662)(`9086dcf`) 13단계 모두 통과. **저장소의 사진 여섯과 그래픽 이미지는 이 실행의 아티팩트로 만듦**
     - 3단계: 앱 로그는 표지 뒤 0.36초에 옴(logcat). 7단계: 위젯 머리가 `금`, `필받음 · 11/13`, `4 / 6 완료`. 11단계: 보이는 칸 유산균·비타민D·오메가3, 상태 표시줄 시계 숨김
     - 사진 여섯을 모두 봄. 6번에 시계가 없고 와이파이·신호·배터리만 있음. 1~4번의 시계는 12:30, 5번은 12:31
     - `store_images.py`: 여섯 장 모두 1080×1920 RGB(63~746KB), 그래픽 이미지 1024×500 RGB(93KB, 위젯을 430×342로 줄여 넣음)
- **Claude가 정한 것 (바꾸실 수 있음)**
  - 보기 데이터: 나·엄마의 약 여섯과 한 달 기록. 상표 이름 없음
  - 장면 순서: 위젯, 알림, 첫 화면, 지난 기록, 항목 수정, 글자 200% 위젯. 알림 장면(2번)이 걱정되면 빼고 올림
  - 그래픽 이미지의 배치와 글(앱 이름과 첫 실행 안내 제목). 글꼴은 Noto Sans KR
- **본 것** (앱 동작, 고치지 않음)
  - 다섯 칸 런처(Pixel Launcher, 폭 1080px)에서 네 칸 폭 위젯은 296.38dp이고 칸을 한 줄에 하나 놓음. 두 줄 기준(`WidgetLayout.plan`, 글자 100%에서 칸 글 폭 113.6dp)에 113.19dp로 조금 모자람. 298.29dp면 두 줄. 갤럭시의 네 칸 격자에서는 4×2가 화면 폭이라 두 줄이고, TASK-032에서 실기기로 봄
  - 계산으로 본 것: 화면 폭(약 374dp)·두 줄 높이(193dp) 위젯에 항목 넷이면, 다 들어가도 넘침 화살표가 뜸(칸 두 줄 134dp가 목록 높이 135.14dp에서 끝 여백 4dp를 뺀 것보다 큼). 캡처로는 보지 않음
- **확인한 것**: `check_docs.py` no problems, `check_wording.py` 문제 없음. 스크립트는 `py_compile` 통과. 그림 규격은 위 5번 실행. 검사 결과는 아래 머지 줄
- **확인하지 못한 것**: Play Console에서 그림이 받아지는지(TASK-038). 갤럭시 런처에서의 위젯 크기와 칸 배치(TASK-032)
- **다음 행동**: 이번 묶음의 노션 최종본 한 행과 동희님 보고. 그 뒤 실기기 확인(TASK-032·052)은 동희님 폰이 준비되면
- **머지**: 2026-11-16 16:13 KST에 main에 머지함(`92c5cb6`).
  - `1cf4e34`의 검사 13개 모두 1차 시도에 통과: CI [43818403849](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818403849), 스토어 스크린샷 [43818403874](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818403874), 알림 [43818403951](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818403951), 접근성 [43818404042](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818404042), 회귀 [43818404064](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818404064), 기기 스크립트 [43818403988](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818403988), 릴리즈 변형 [43818403884](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43818403884). 나머지 여섯(화면 확인과 2부, 위젯 크기, 업데이트, 둘러보기, 에뮬레이터 확인)은 이 푸시가 경로 조건의 파일을 바꾸지 않아 gate가 건너뜀
  - 스토어 스크린샷 여섯 번째 실행(`1cf4e34`): 13단계 모두 통과. 3단계는 '로그 1줄, 기다린 지 0.4초에 읽음'(logcat에서 앱 로그는 표지 뒤 0.36초). 사진 1~4·6은 다섯 번째 실행과 화소까지 같고, 5번은 같은 화면이 9화소 아래에 있음(목록을 그만큼 덜 내림). 저장소의 그림은 다섯 번째 실행 것 그대로 둠

### BUG-007 늦게 온 알람 뒤 새 알림 하나가 사라짐 ([PR #45](https://github.com/judangbang-ops/pillbadeum_release/pull/45))

- **목표**: 지난 창의 알림이 떠 있는 채 다음 창의 알람이 울려도 항목마다 새 알림이 뜨게 함(REQ-041, SPEC-010·013)
- **찾은 것**: PR #44의 알림 확인 API 36 1차 시도([실행 43798196358](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196358), 작업 133072458077)에서 RR-91이 실패함. 08:40에 늦게 온 08:00 알람 뒤 앱 로그는 알림 4개를 띄웠다고 했으나 시스템에는 PillA·PillC·PillD만 있었음. 시스템 로그에 PillE 키의 'Cannot find enqueued record'. 같은 코드가 `64ecbe8`(PR #43)과 재실행에서는 통과해서, 때에 따라 나는 경쟁임
  - 원인: 모으기(`Reminders.collect`)가 새 알림을 띄운 **뒤** 떠 있는 알림을 정리했음. Android는 `notify`를 받고 조금 뒤에 알림을 목록에 올림. 그래서 정리가 읽은 목록에 PillE의 지난 창(전날) 알림이 아직 있었고, 창이 끝났다며 지운 요청이 같은 태그·번호의 새 알림까지 지움. Android 알림 서비스는 지운 키를 기다리는 줄에서도 뺌
  - 같은 이유로, 새 알림이 목록에 오르기 전에 다음 단계가 목록을 읽으면 남은 알림이 없다고 보고 "복용 시간이에요"를 내릴 수 있음. 정확 알람이 허용되지 않으면(Android 14부터 새로 깐 앱의 기본) 같은 시각에 건 두 알람이 함께 울려, 둘째 모으기가 곧바로 목록을 읽음
- **한 일**
  - `Reminders.collect`: 떠 있는 알림 정리를 새 알림 앞으로 옮김. 옛 알림을 지우는 요청이 새 알림보다 먼저 가므로 새 알림이 지워지지 않음
  - 새 알림을 띄운 뒤 시스템 목록에 모두 오를 때까지 잠금을 쥐고 기다림(`awaitListed`, 25ms마다 보고 최대 2초). 그동안 둘째 알람이나 쓰기 뒤의 정리는 잠금을 기다리므로 새 알림이 든 목록을 읽음. 2초 안에 오르지 않으면 로그만 남기고 넘어감
  - 앱 시험 `ReminderListLagTest`(새로, Robolectric): `LaggingNotificationManager`는 알림을 정한 시간 뒤에야 목록에 올리고, 그 전에 같은 태그·번호를 지우면 기다리던 알림도 지움. 이것으로 두 경우를 봄
    - 12일 알림 넷이 떠 있는 채 13일 08:00 알람이 08:40에 옴. 시스템은 모으기가 끝날 때까지 아무것도 올리지 않음 → 넷 모두 13일 알림이고 "복용 시간이에요"가 있음(RR-93)
    - 같은 시각 두 알람이 잇달아 울림, 목록에 오르는 데 0.4초 → 알림 둘과 "복용 시간이에요"가 남음(RR-94)
  - 커밋을 둘로 나눔: 시험만 넣은 `ab62b8d`의 CI([실행 43804868377](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43804868377))에서 두 시험 모두 고치기 전 코드로 실패함. 13일 알림 넷이 남지 않았고(`ReminderListLagTest.kt:81`), 둘째 알람 뒤 "복용 시간이에요"가 없었음(`:97`, 그 앞의 알림 둘은 통과). 나머지 앱 시험 125개는 통과. 시험이 이 버그를 잡는 것을 확인한 뒤 고침을 넣음
  - 문서: 보충 N-13 '구현'에 모으기의 순서, 반례 RR-93·94, 요구 REQ-041 합격 기준과 상태, 시험 계획 BUG-007·TC-010, wbs 진행 메모
- **Claude가 정한 것 (바꾸실 수 있음)**: 기다림 최대 2초. Android가 알림 도우미를 기다리는 시간(약 0.2초)보다 넉넉히 잡음. 넘으면 기다리지 않고 넘어감
- **확인한 것**: `check_docs.py` no problems, `check_wording.py` 문제 없음. 고친 코드의 앱 시험과 에뮬레이터 알림 확인(API 26·30·34·36)은 CI 실행으로 적음(머지 줄). 로컬 Gradle은 Maven Central 429로 쓰지 못함
- **확인하지 못한 것**: 실제 폰에서 두 알람이 정말 함께 울리는지와 목록에 오르기까지 걸리는 시간. 에뮬레이터 알림 확인은 1단계 뒤로 정확 알람을 허용해서 이 길을 울리지 않음. 갤럭시 확인(TASK-032)의 비정확 알람 단계에서 봄
- **다음 행동**: TASK-037 스토어 스크린샷 6장과 그래픽 이미지
- **머지**: 2026-11-16 14:12 KST에 main에 머지함(`924aec0`).
  - `85dad71`의 검사 5개 모두 1차 시도에 통과: CI [43805579429](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43805579429)(앱 시험 127개 모두 통과, `ReminderListLagTest` 둘 포함), 알림 [43805579499](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43805579499)(API 26·30·34·36), 회귀 [43805579432](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43805579432), 업데이트 [43805579434](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43805579434), 기기 스크립트 [43805579444](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43805579444)
  - 알림 확인 API 36(아티팩트 `reminders-api36`): 보고서 41줄 통과·실패 0. RR-91에서 08:40에 늦게 온 08:00 알람 뒤 PillA·PillC·PillD·PillE 넷과 "복용 시간이에요"가 모두 뜸. 시스템 로그에 항목 알림 키의 'Cannot find enqueued record'는 없음(남은 것은 Android 자동 묶음 요약 키뿐). 2초 기다림을 넘긴 로그(`not listed by the system`)도 없음
  - 버그를 처음 본 실행에서도 때에 따라 통과했으므로, 한 번의 통과로 경쟁이 없어졌다고 단정하지는 않음. 근거는 순서를 바꾼 이유(옛 알림 정리가 새 알림보다 먼저 감)와 앱 시험 `ReminderListLagTest`

### BUG-006 Android 15 이상에서 상태 표시줄 아이콘이 흰 화면에 묻힘 ([PR #44](https://github.com/judangbang-ops/pillbadeum_release/pull/44))

- **목표**: Android 15 이상에서 앱 화면과 위젯 대상자 고르기(SCR-002) 위의 시계·배터리 아이콘이 보이게 함(REQ-069 명암)
- **찾은 것**: TASK-037 스크린샷을 준비하며 지난 캡처를 보다 Claude가 찾음. 에뮬레이터 알림 확인 API 36의 `api36-10-app-taken.png`(`47aefbd`, [실행 43692177634](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43692177634))에서 맨 위 188줄이 모두 #FFFFFF. 상태 표시줄 아이콘이 흰 바탕에 흰색이었음
  - 원인: targetSdk 36이라 Android 15부터 앱이 상태 표시줄 밑까지 그려지고(edge-to-edge 강제) 상태 표시줄 색(`statusBarColor`)은 쓰이지 않음. 아이콘 색은 테마의 `windowLightStatusBar`만 정하는데, 앱이 쓰던 플랫폼 테마 `Theme.Material.Light.NoActionBar`는 이를 켜지 않음. API 34 캡처는 회색(#757575) 띠에 흰 아이콘이라 보였음. 앱 코드에서 시스템 바 모양을 바꾸는 곳은 없음
- **한 일**
  - 앱 테마 `Theme.Pillbadeum`(`res/values/themes.xml`): 상태 표시줄 흰색에 어두운 아이콘. Android 8.1 이상(`values-v27`)은 아래 내비게이션 바도 흰색에 어두운 버튼. 매니페스트의 앱 테마를 이것으로 바꿈
  - 고르기 화면 테마 `Theme.Pillbadeum.Pick`: 지금처럼 반투명. Android 15 이상(`values-v35`)에서는 흰 화면이 시스템 바 밑에 오므로 어두운 아이콘·버튼. Android 14 이하에서는 시스템이 그리는 어두운 띠 그대로임
  - 앱 시험 `SystemBarsTest`(새로, Robolectric Android 15): 두 화면이 두 테마를 쓰는지, 두 테마가 어두운 아이콘·버튼을 켜는지, 앱 테마의 상태·내비게이션 바가 흰색인지 봄
  - 문서: 시험 계획 6장 BUG-006, 화면 정의 6장
- **Claude가 정한 것 (바꾸실 수 있음)**: Android 14 이하도 회색 띠 대신 흰 띠로 함. 화면이 모두 흰색이라 띠가 화면과 이어져 보이게 함. 아래 내비게이션 바도 같은 이유로 흰색
- **확인한 것**: `check_docs.py` no problems, `check_wording.py` 문제 없음. 앱 빌드·시험과 에뮬레이터 캡처는 CI 실행으로 적음(아래 머지 줄). 로컬 Gradle은 Maven Central 429로 쓰지 못함
- **다음 행동**: TASK-037 스토어 스크린샷 6장과 그래픽 이미지
- **머지**: 2026-11-16 13:32 KST에 main에 머지함(`d0217f9`).
  - `b926597`의 검사 5개 통과(바꾼 파일이 경로 조건에 걸린 것만 돎): CI [43798196332](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196332)(JVM 시험과 앱 화면 시험, `SystemBarsTest` 포함), 에뮬레이터 확인 [43798196345](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196345), 업데이트 확인 [43798196341](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196341), 회귀 [43798196336](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196336), 알림 [43798196358](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43798196358)(API 26·30·34는 1차 시도, API 36은 2차 시도)
  - 알림 확인 API 36의 1차 시도는 RR-91 하나가 실패함(작업 133072458077): 늦게 온 08:00 알람의 새 알림 넷 중 PillE가 뜨지 않음. 테마와 상관없는 앱의 알림 순서 문제여서 BUG-007로 따로 고침(위 항목). 실패한 작업만 다시 돌려 통과함(작업 133083626723, 보고서 41줄 통과·실패 0, RR-91도 넷)
  - BUG-006 확인: 2차 시도 아티팩트 `reminders-api36`의 `api36-10-app-taken.png`(앱 첫 화면, Android 16)에서 맨 위 130줄에 #666666 아이콘 화소 2,448개, 모두 흰 줄은 맨 위 52줄(고치기 전 188줄). 1차 시도의 같은 캡처도 52줄이었음
  - 확인하지 못한 것: 고르기 화면(SCR-002)의 Android 15 이상 캡처는 없음(대상자 둘인 고르기 캡처는 API 34 화면 확인에만 있음). 앱 시험 `SystemBarsTest`가 그 테마 값을 보고, 실기기(TASK-052)에서 봄

### TASK-045 앱 이름 필받음과 아이콘 ([PR #43](https://github.com/judangbang-ops/pillbadeum_release/pull/43))

- **목표**: 2026-11-16 정하신 앱 이름 '필받음'을 앱에 넣고, 런처 아이콘과 스토어 512px 아이콘을 만듦(TASK-045, REQ-060). 스토어 스크린샷(TASK-037)이 이름이 든 화면을 찍을 수 있게 함
- **한 일**
  - 이름: `strings.xml`의 `app_name`·`widget_label`과 `Labels.APP_NAME`을 '필받음'으로. 런처·위젯 목록·알림의 앱 이름, 위젯 머리글('필받음 · 11/16'), 첫 실행 안내("필받음은 기록 도구예요.", 09 그대로가 됨), 배터리·설정 안내("필받음을 찾아")가 바뀜. 조사는 이미 이름 끝 글자에 맞춰 붙음(`withTopic`·`withObject`)
  - 런처 아이콘: 적응형 아이콘 `mipmap-anydpi-v26/ic_launcher.xml`(바탕 색 #2B59C3, 앞 층, Android 13 이상 테마 아이콘용 단색 층)과 매니페스트 `android:icon`. minSdk 26이라 비트맵은 두지 않음. 앞 층은 비스듬한 캡슐과 흰 원 안의 체크이고, 캡슐은 체크 둘레를 잘라(클립 경로) 바탕색 틈으로 떨어뜨림. 모두 안전 원(지름 66dp) 안
  - 스토어 아이콘: `docs/release/listing/ko-KR/images/icon.png`(512×512, 32-bit PNG, 14KB). 앱의 두 벡터 파일을 SVG로 옮겨 보이는 72dp를 Chromium으로 그림. 옮긴 SVG로 원·둥근 네모 48·192px와 밝은·어두운 테마 아이콘도 그려 봄
  - 에뮬레이터 스크립트: 런처의 위젯 목록에서 앱을 이름으로 찾는데 `adb shell input text`는 한글을 못 침. 라틴 글자 이름은 지금처럼 검색하고, 한글 이름은 목록을 위에서부터 반 화면쯤씩 느린 밀기(1.5초)로 훑음(`release_check.drag_list`, 빠른 밀기는 목록이 튕겨 줄을 지나침). 목록이 더 움직이지 않으면 끝으로 봄. 위젯 미리 보기 아래의 위젯 이름(`widget_name`)은 앱 줄로 치지 않음
  - 업데이트 확인(`upgrade_check.py`)의 옛 빌드는 이름이 DidITake라 `--old-label`로 그 이름을 넘기고, 워크플로가 옛 커밋의 `app_name`을 읽어 줌. ANR 창 판별은 두 이름을 다 봄
  - PR 검사에서 고친 것: 기기 스크립트 확인 API 36([실행 43790874626](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43790874626), `a80736e`)이 첫 위젯을 놓지 못해 실패함(두 번 모두 'widget preview not on screen'). 저장된 덤프에서 '필받음' 줄이 위젯 목록 맨 끝(1492~1739px)이라 펼친 미리보기가 1783px부터 화면 아래 끝 2340px까지 잘려 있었고, 스크립트의 밀기로 올라오지 않았음. 미리보기는 다 보일 때만 잡았기 때문임. 다 보이는 것이 없으면 제스처 막대 위로 150px 이상 보이는 잘린 미리보기를 잡고 그 보이는 곳의 가운데를 길게 눌러 끌게 함(`release_check.widget_preview`, 사람도 보이는 곳을 누름). 두 덤프를 새 규칙에 넣어 (540, 2002)를 고르는 것을 확인. 같은 실행의 API 34는 통과
  - jvm 시험의 기대 문구 셋(`StatusBoardTest` 머리글, `AccessNoticeTest` 배터리 문장, `WidgetLayoutTest` 머리글 낱말 잇기), 문서(화면 정의 SCR-005·004·009 문구와 6장, 요구 REQ-036 칸, wbs TASK-045 **진행**과 메모, 스토어 등록정보 1·4.4·9장, README)
- **Claude가 정한 것 (바꾸실 수 있음)**: 아이콘 도형(후보 셋 중 캡슐과 체크 배지)과 색. 바꾸시려면 TASK-038 전에
- **확인한 것**: `check_docs.py` no problems, `check_wording.py` 문제 없음(앱 문구 한글 문자열 430개, 16개 파일). 스크립트는 `py_compile` 통과. 앱 빌드·시험과 에뮬레이터 확인은 CI 실행으로 적음(아래 머지 줄). 로컬 Gradle은 Maven Central 429로 쓰지 못함
- **남은 것**: 그래픽 이미지(1024×500)와 런처·위젯 목록 캡처는 TASK-037 스크린샷과 같은 PR에서. 그 뒤 TASK-045 **끝남**
- **다음 행동**: TASK-037 스토어 스크린샷 6장과 그래픽 이미지
- **머지**: 2026-11-16 12:25 KST에 main에 머지함(`2c68978`).
  - `64ecbe8`의 검사 12개가 모두 1차 시도에서 통과: CI [43794389185](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389185)(JVM 시험과 앱 화면 시험, 디버그·릴리즈 APK), 기기 스크립트 확인 [43794389224](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389224)(API 34·36), 업데이트 확인 [43794389245](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389245)(옛 빌드 DidITake에서 필받음으로), 릴리즈 변형 [43794389203](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389203), 회귀 [43794389181](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389181), 화면 확인 [43794389361](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389361)과 2부 [43794389171](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389171), 둘러보기 [43794389204](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389204), 알림 [43794389270](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389270)(API 26·30·34·36), 위젯 크기 [43794389265](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389265), 접근성 [43794389314](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389314), 에뮬레이터 확인 [43794389228](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43794389228)
  - 첫 커밋 `a80736e`에서는 기기 스크립트 확인 API 36만 실패했음(위 '한 일'의 PR 검사에서 고친 것). 고친 뒤 API 36에서 첫 위젯 놓기(BUG-004 첫 위젯), 위젯 탭, 기록 지연 단계가 모두 통과함(작업 로그)

### TASK-035 끝: 앱 설정의 방침 링크 ([PR #42](https://github.com/judangbang-ops/pillbadeum_release/pull/42))

- **목표**: 앱 설정(SCR-009)의 [개인정보처리방침]이 공개한 방침을 열게 함(REQ-063, TC-036). TASK-035의 마지막 몫
- **한 일**
  - `ui/SettingsScreen.kt`: `PRIVACY_POLICY_URL`을 방침 파일의 main 주소로 정함. 주소가 없을 때의 '준비 중' 줄과 그 문구(`Labels.NOT_READY`)를 지움. `App`의 `privacyPolicyUrl`은 null을 받지 않음(시험은 다른 주소를 넘김)
  - 앱 시험 `SettingsScreenTest`의 기본 화면: '개인정보처리방침, 준비 중' 대신 '개인정보처리방침, 브라우저에서 열기'
  - jvm `PrivacyPolicyUrlTest`(새로): 주소가 이 저장소 main의 파일을 가리키고, 그 파일이 있고, `{ }` 빈칸과 초안 상자 없이 시행일 줄이 있는지 봄. 앱에 들어간 주소는 업데이트 없이 못 바꾸므로, 방침 파일을 옮기면 CI가 막음
  - 문서: 화면 정의 SCR-009 앱 정보 줄, 시험 계획 TC-036, 요구 REQ-063, wbs TASK-035 **끝남**, 방침 근거 5장
- **확인한 것**: 결과는 CI 실행으로 적음(아래 머지 줄). 로컬 Gradle은 Maven Central이 429(요청 과다)를 돌려줘 설정 단계에서 멈춤
- **다음 행동**: TASK-045(앱 이름 필받음과 아이콘)
- **머지**: 2026-11-16 11:12 KST에 main에 머지함(`1e875a4`).
  - CI [43786320289](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43786320289) 1차 시도에서 통과(JVM 시험과 앱 화면 시험, 새 `PrivacyPolicyUrlTest`와 바꾼 `SettingsScreenTest` 포함, 디버그·릴리즈 APK). 에뮬레이터 회귀 확인 [43786320371](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43786320371)과 업데이트 확인 [43786320261](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43786320261)도 1차에서 통과
  - 기기 스크립트 확인 [43786320246](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43786320246): API 36은 1차에서 통과. API 34는 1차에서 재부팅 뒤 잠금 해제가 시간 안에 끝나지 않아('PIN 입력 못 함') 실패함. 아티팩트 기록에서 PIN 입력 화면은 떴지만 잠금이 풀린 것은 스크립트가 `locksettings clear`를 한 뒤였음. 이 PR이 바꾼 설정 화면 링크와 무관한 에뮬레이터 잠금 화면 문제로 보고, 실패한 작업만 다시 돌려 2차에서 통과

### 결정 반영: 출시 준비 결정과 앱 이름 ([PR #41](https://github.com/judangbang-ops/pillbadeum_release/pull/41))

- **목표**: 2026-11-15 묶음 보고의 '정해 주실 것'에 대한 동희님 답을 문서에 넣음. 방침을 공개함
- **정함**
  - 2026-11-16 10:05 KST, '필받음 재구축 인계' 채팅, 동희님 "추천대로 모두 승인". 보고의 2·4·5·6번과 3번의 추천: 계정 종류(Q11)는 Play 개발자 지원에 먼저 물음, 타겟 연령 18세 이상만, 카테고리 의료, SPEC-043·SCR-007의 함수 이름을 코드에 맞춤, 연락 이메일은 앱용 주소를 따로, 방침은 이 저장소의 파일 주소로 공개, 문안 승인
  - 같은 채팅 10:09 KST, 동희님이 세 줄로 주심: 앱 이름 '필받음', Play에 보일 개발자 이름, 연락 이메일(값은 [방침](../release/privacy-policy.md) 8장)
- **한 일**
  - [요구 0장](../01-planning/requirements.md#0-v3-뒤에-정한-결정)에 '출시 준비 뒤 정한 것' 줄을 더하고 REQ-063의 '지금 코드' 칸을 고침
  - [명세](../04-functional-spec/spec.md) SPEC-043의 함수 이름을 고치고 5장에 '고정한 뒤 바꾼 것'을 더함
  - [화면 정의](../05-ui/screens.md) SCR-007의 [먹었어로 바꾸기]와 규칙의 함수 이름을 고치고 6장에 적음. 맞추다 시계 경고 두 버튼도 `VoidDose`가 아니라 `ResolveClockFlag`임을 찾아 함께 고침(동작은 그대로)
  - [방침](../release/privacy-policy.md): 빈칸(앱 이름, 개발자 이름, 연락처, 공개 주소, 게시 서비스 GitHub, 변경 이력 주소)을 채우고 초안 상자를 지움. 시행일 2026년 11월 16일
  - [방침 근거](../release/privacy-policy-basis.md) 3장·5장, [앱 콘텐츠 답](../release/play-app-content.md) 0·1·2·5·6·12·13장, [스토어 등록정보](../release/store-listing.md) 1·2.1·5·6·7·8장과 `title.txt`, README 문서 표, wbs 진행 메모와 '결정이 먼저 필요한 작업', 시험 계획 TC-075
  - Play 개발자 지원 문의 글(한국어, 영어)과 양식에 넣을 값을 [앱 콘텐츠 답](../release/play-app-content.md) 0장에 둠. 보내는 것은 동희님(외부 메시지)
  - 앱 코드는 바꾸지 않음
- **Claude가 정한 것 (바꾸실 수 있음)**
  - 방침 시행일을 공개한 날로 씀. 근거 문서의 추천은 'Play Console에 넣는 날'이었으나, 저장소 파일은 main에 합치는 날 바로 공개되고 앱 설정도 Play보다 먼저 이 주소를 엶
  - 방침의 연락처는 이메일만. 전화번호는 보고에서 묻지 않았고, 조문의 '전화번호 등 연락처'에 이메일도 듦
  - 스토어 등록정보의 웹사이트·전화번호 칸은 비움
  - 간단한 설명은 보고에서 묻지 않아 초안대로 둠
- **확인한 것**
  - Play 지원 양식([Google Play 관련 문제 신고](https://support.google.com/googleplay/android-developer/contact/general_contact?hl=ko))을 Claude가 Chromium으로 열어(로그인 전, 한국어·영어) 문구와 칸을 읽음. 계정을 만들 수 없을 때 쓰는 양식이고, 한국어 답을 받을 수 있고, 개발자 계정 ID는 필수 칸이 아님
  - `check_wording.py`: 앱 이름 3/30, 간단한 설명 49/80, 자세한 설명 1213/4000, 앱 문구 한글 문자열 429개(15개 파일)에 문제 없음
  - `check_docs.py`: no problems (요구 95개, 명세 44줄, 작업 54개, 시험 사례 88개, 문서 55개의 상대 링크 475개와 표 275개)
  - 방침 파일에 `{ }` 칸이 남지 않음(정규식으로 셈)
  - SCR-007의 버튼마다 부르는 함수: `ui/JudgeOutcomes.kt`와 `usecase/ClockFlags.kt`의 주석과 결과 형을 읽음
- **확인하지 못한 것**: Play 지원의 답(동희님이 보낸 뒤). 공개 주소가 로그인 없이 열리는지는 머지 뒤에 봄
- **다음 행동**: TASK-035 남은 것(앱 설정의 방침 주소와 시험), TASK-045(앱 이름 필받음과 아이콘), 그 뒤 스토어 스크린샷
- **머지**: 2026-11-16 10:25 KST에 main에 머지함(`6c2c44b`). CI [43785585958](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43785585958) 1차 시도에서 통과. 머지 뒤 방침 공개 주소를 로그인 없이(curl, 쿠키 없음) 열어 HTTP 200과 제목·시행일·연락처를 봄

## 2026-11-15

### 노션 '최근 업데이트'에 묶음 최종본 한 행 (PR #34~#39)

- **목표**: 15:31 KST 동희님 지시("노션에는 한번에 최종본만 올리고")대로, PR #33 행 뒤의 작업 묶음을 노션에 한 번만 올림
- **한 일**: 2026-11-15 18:45 KST쯤 [최근 업데이트 행](https://app.notion.com/p/419bf16cc3ec8112845ef69d4ac92a65) 하나를 만듦(종류 '진행·결과', 읽었어요 꺼짐). 본문에 PR #34~#39의 한 일, 새로 알게 된 것 셋, 정해 주실 것 여섯, 다음 일, 이 파일과 출시 문서 세 개의 링크를 둠. 새 최상위 페이지나 PR별 행은 만들지 않음
- **정해 주실 것 (동희님)**: 앱 이름(TASK-045), 개발자 계정 종류(Q11, 추천은 계정을 만들기 전에 Play 개발자 지원에 물음), 방침·스토어의 개발자 이름·연락 이메일·방침 공개 위치·시행일과 문안 승인, 타겟 연령 18세 이상만(추천), 스토어 카테고리 의료(추천), SPEC-043·SCR-007의 함수 이름을 코드에 맞출지(추천: 맞춤)
- **다음 행동**: 위 결정을 기다림. 실기기 확인(TASK-032·052)은 시험용 갤럭시를 PC에 연결하신 뒤
- **머지**: 이 기록은 [PR #40](https://github.com/judangbang-ops/pillbadeum_release/pull/40)으로 2026-11-15 19:21 KST에 main에 머지함(`bb9ded9`). CI [43703238985](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43703238985) 1차 시도에서 통과

### TASK-037 스토어 등록정보 초안 ([PR #39](https://github.com/judangbang-ops/pillbadeum_release/pull/39))

- **목표**: Play 스토어 등록정보(앱 이름, 간단한 설명, 자세한 설명, 그림, 카테고리, 연락처)를 앱이 실제로 하는 일에 맞춰 미리 씀. 건강 정책의 고지 둘을 넣고 의학 판단 문구를 뺌(REQ-064, REQ-087). 입력은 TASK-038 동희님
- **한 일**
  - [스토어 등록정보](../release/store-listing.md)(새로): 칸별 초안과 도움말 근거, 문장마다의 근거 표, 건강 정책 고지, 그림 규격과 찍을 장면, 카테고리 추천, 연락처·기본 언어, 정할 것
  - [붙여 넣을 글](../release/listing/ko-KR/) 세 파일(새로): `title.txt`(`{앱 이름}`), `short_description.txt`(49자), `full_description.txt`(1,213자)
  - CI 문구 검사 `.github/scripts/check_wording.py`(새로)와 `ci.yml` 문서 대조 작업의 그 단계. 등록정보 글의 길이·고지·금지 문구, 등록정보 글과 앱 문구의 의학 판단 문구(REQ-087)와 분 단위 알림 약속(REQ-048)을 봄
  - README 문서 표, wbs TASK-037 진행 메모, 요구 REQ-064·REQ-087의 '지금 코드' 칸, 시험 계획 TC-060·TC-061·TC-076과 0장의 검사 목록
  - 앱 코드는 바꾸지 않음
- **확인한 것**
  - 조사 에이전트가 Play 도움말 6쪽(스토어 등록정보, 그래픽 애셋, 메타데이터 정책, 카테고리와 태그, 건강 콘텐츠 및 서비스 정책의 두 주소. 건강 정책은 한국어판도)을 읽고 원문을 인용함
  - Claude가 비슷한 앱 셋의 Play 웹 페이지(미국 스토어)를 Chromium으로 열어 카테고리를 읽음: Medisafe 건강/운동, MyTherapy·Dosecast 의료
  - Claude가 설명의 문장마다 앱 문구·화면 정의·요구와 대 봄(문서 2.3의 표)
  - `check_wording.py`: 이 커밋에서 통과. 앱 이름 6/30(자리 표시 남음), 간단한 설명 49/80, 자세한 설명 1213/4000, 앱 문구는 한글 문자열 429개(15개 파일)에 문제 없음
  - 검사가 실제로 잡는지: 금지 문구를 일부러 넣은 글과 코드(작업 폴더)로 돌려 24개를 잡음. 처음에는 "놓친 약은 생각나면 바로 드세요"를 놓쳐 규칙을 고침
  - `check_docs.py`: 문제 없음
  - 지금의 QA 캡처 넷의 PNG 머리: 전체 화면 셋은 540×1170(긴 변 2.167배), 위젯 하나는 540×340, 모두 팔레트 PNG
- **알게 된 것**
  - Play 스크린샷은 긴 변이 짧은 변의 두 배를 넘으면 안 됨. 지금 캡처와 1080×2340 같은 휴대폰 화면은 그대로 못 올림. 그래서 1080×1920으로 따로 찍기로 함
  - 건강 정책 고지의 동사는 넷(진단·처치·치료·예방)이고 REQ-064 문장은 셋임. 설명에는 넷을 넣음(Claude)
  - Play의 기본 언어는 영어(미국)라 앱을 만들 때(TASK-050) 한국어를 골라야 함
- **확인하지 못한 것**: Play Console 입력 화면(앱을 만들기 전), 그래픽 이미지가 필수인지, 태그 목록, 카테고리를 나중에 바꿀 수 있는지(도움말에 없음), fastlane supply의 파일 이름(Claude가 알던 것)
- **정할 것 (동희님)**: 앱 이름(TASK-045), 스토어에 보일 연락 이메일(방침과 같은 주소, 추천은 앱 연락용 주소를 따로 만듦), 카테고리(추천 의료), 간단한 설명(초안 또는 다른 안)
- **다음 행동**: PR #39 검사가 끝나면 머지. 스크린샷·아이콘·그래픽 이미지는 앱 이름이 정해진 뒤. 이어서 노션 '최근 업데이트'에 최종본 한 행을 올리고 보고
- **머지**: 2026-11-15 18:43 KST에 main에 머지함(`d0e3ab4`). CI [43700228427](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43700228427) 1차 시도에서 통과. 새 단계 '스토어 설명·앱 문구'도 CI에서 통과함

### TASK-036 Play '앱 콘텐츠' 답 초안 ([PR #38](https://github.com/judangbang-ops/pillbadeum_release/pull/38))

- **목표**: Play Console '앱 콘텐츠'(개인정보처리방침, 앱 액세스, 광고, 콘텐츠 등급, 타겟층, 데이터 보안, 건강 앱, 광고 ID 등)에 넣을 답을 도움말 원문 근거와 함께 미리 씀(REQ-063). 입력은 앱을 만든 뒤(TASK-050) 동희님
- **한 일**: [앱 콘텐츠 답](../release/play-app-content.md)(새로), README 문서 표, wbs TASK-036 진행 메모, 시험 계획 TC-075의 '지금' 칸. 앱 코드는 바꾸지 않음
- **답의 요지**: 모든 기능을 로그인 없이 씀, 광고 없음, 콘텐츠 등급 설문 모두 '아니요', 타겟 18세 이상만, 데이터 수집·공유 없음, 건강 앱은 'Medication and Treatment Management' 하나, 광고 ID 안 씀, 정확한 알람·포그라운드 서비스 신고 없음
- **확인한 것**
  - 조사 에이전트가 Play 도움말 13쪽(영어, 계정 유형은 한국어판도)을 읽고 원문을 인용함. 문서 12장에 그 가운데 근거로 쓴 10쪽의 주소를 둠
  - Claude가 CI 43696668379의 릴리즈 병합 매니페스트에서 권한 7개(`AD_ID`·`USE_EXACT_ALARM`·`FOREGROUND_SERVICE_*`·`USE_FULL_SCREEN_INTENT` 없음), 대상 SDK 36, WorkManager `SystemForegroundService`에 유형이 없음을 확인함
- **판단 (Claude)**: 검토 B9의 물음(자동 백업이 데이터 보안의 '수집'인가)에 대해, 도움말에 Android 자동 백업 문장이 없음. 이 앱은 종단 간 암호화될 때만 백업하므로 E2E 예외에 들고, 백업이 사용자의 Google 계정으로 가므로 사용자 클라우드 예외에도 든다고 보고 '수집 아님'으로 답함. 반대 의견과 함께 문서 7장에 적음
- **알게 된 것**: Play 도움말 '개발자 계정 유형 선택'이 새 계정에 "건강 앱(예: 의료 앱 및 인간 대상 연구 앱)"이면 조직 계정(D-U-N-S 번호 필요)을 고르라고 함. 이 앱의 신고 분류는 'Medical' 묶음에 있음. 개인이 만든 복용 기록 앱이 거기 드는지는 도움말에 없음. 결정 Q11(TASK-048)에 바로 걸림
- **확인하지 못한 것**: Play Console 화면 문항(앱을 만들기 전), 콘텐츠 등급 설문 문항(도움말에 없고 IARC 안내 주소는 404), 정확한 알람 정책 원문(요약만), 광고 ID 신고 문구, 건강 앱 신고 둘째 쪽, 정부·금융·뉴스 도움말, 새 개인 계정 테스트 요건 도움말
- **정할 것 (동희님)**: 개발자 계정 종류(Q11, 추천은 계정을 만들기 전에 Play 개발자 지원에 물음), 타겟 연령 18세 이상만 확인
- **다음 행동**: PR #38 검사가 끝나면 머지. 이어서 TASK-037(스토어 설명 초안)
- **머지**: 2026-11-15 18:27 KST에 main에 머지함(`5b5d8d7`). CI [43698929982](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43698929982) 1차 시도에서 통과

### 설정 화면 시험의 한 번 실패에 대비 ([PR #37](https://github.com/judangbang-ops/pillbadeum_release/pull/37))

- **목표**: PR #36(문서만)의 CI에서 한 번 실패한 앱 화면 시험이 다시 머지를 막지 않게 함. 앱 코드는 바꾸지 않음
- **증상**: CI [43696668379](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43696668379) 1차 시도, `SettingsScreenTest.c6_common_reminder_settings_say_how_many_repeats_quiet_hours_drop` 245번 줄. 방해 금지를 저장한 바로 뒤 '하루 최대, 3번' 줄을 누르고 고르기 창의 `1번`을 찾았으나 창이 없었음(`Failed to inject touch input. … could not find any node`). 같은 커밋의 2차 시도에서는 통과
- **확인한 것**: 시험 결과 XML(1차 시도 아티팩트)의 전체 메시지. 최근 CI 실패 6번(11-13~11-14)의 시험 결과에는 이 시험의 실패가 없음(위젯 그림 시험 실패뿐). Compose 시험 도구(1.11.2) 소스에서 이 메시지는 노드를 찾지 못했을 때의 것이고, 누름 사이에 시계를 16ms씩 돌려 화면이 바뀔 수 있음을 확인함
- **원인(추정, 확정 못 함)**: 저장 뒤 일(위젯·알림 다시 맞추기, 화면 다시 읽기)은 메시지가 뜬 뒤에도 주 스레드 밖에서 이어지는데, 시험 도구는 그 일을 기다리지 않음. 이 단계만 저장 바로 뒤에 다른 줄을 눌러(다른 단계는 사이에 캡처 저장이 있음) 그 틈에 걸림. 누름이 사라졌는지 창이 늦게 붙었는지는 로그가 없어 가리지 못함. 사람이 쓰는 앱에서 같은 일이 생긴다는 근거는 없음
- **한 일**: 그 자리만 `openPicker`로 바꿈. 줄을 누르고 고르기 창이 뜰 때까지 기다리고, 3초 안에 안 뜨면 한 번 더 누르며 그 사실을 stderr에 남김(시험 결과 XML의 `system-err`에 남아 다음에 보임)
- **다음 행동**: 이 PR의 CI가 통과하면 머지. 뒤로 CI 시험 결과에 `openPicker:` 줄이 보이면 첫 누름이 사라진 것이니 원인을 다시 봄
- **머지**: 2026-11-15 18:18 KST에 main에 머지함(`dae9fb2`). CI [43698228674](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43698228674) 통과. 그 실행의 시험 결과에서 이 시험은 0.442초에 통과했고 `system-err`가 비어 있음(첫 누름에 창이 떠 다시 누르지 않음)

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
- **머지**: 2026-11-15 18:09 KST에 main에 머지함(`852cfff`). CI [43696668379](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43696668379) 1차 시도에서 앱 화면 시험 122개 가운데 1개가 실패함(바로 위 항목). 이 PR은 문서만 바꿨고 같은 앱 코드가 `fd624bf`의 CI에서 통과했으므로 흔들림으로 보고, 실패한 작업만 다시 돌림(빈 커밋 없음). 2차 시도에서 122개 모두 통과한 뒤 머지

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
