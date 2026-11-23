# 09 목업: 2안 요일 약통 (1차 릴리즈, 2차 첫 릴리즈)

상태 (2차 첫 릴리즈): **09 확인 끝** (2026-11-23 KST, 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(09-1~09-4, 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_e1c2389f7568`에 대한 오랑이 답). 09-3은 '바꿈 승인'. 그린 사람 Claude). 건강 카드 화면 다섯과 바뀐 화면 넷의 보드 25개를 그림. 개발 중 설계 바꿈 S-1(2026-11-23 '바꿈 승인', 8.6)로 응급 대비 문장 보드 둘을 다시 그리고 `Emergency200`을 더해 26개. [8장](#8-2차-첫-릴리즈-건강-카드-보드-2026-11-23-claude-기능-변경)

상태 (1차): **09 확인 끝** (2026-11-12 19:11 KST, 동희님 "모두 승인"). 방향은 2안 요일 약통, 화면 10개를 그렸고 토큰을 굳혔음. 토큰 값을 바꾸려면 다시 확인받음. 2026-11-13에 글꼴 값을 04 명세 고정 질문 4의 승인으로, 2026-11-14에 `color.line.strong` 값을 TASK-030 접근성 점검 뒤의 승인으로 바꿈(0장 마지막 두 줄, 4.1절).

- 목업 캔버스: [필받음 1차 목업 (2안 요일 약통)](https://claude.ai/artifact/QW5JRsx1Zpz5ekeqHmjpKC). claude.ai Design 아티팩트이고 동희님 계정에서만 열림. 보드는 2안 18개와 참고로 남긴 1안 3개임. 2026-11-13의 글꼴 변경과 대상자 관리 보드 수정, 2026-11-14의 `color.line.strong` 변경은 이 저장소의 보드에만 있고 캔버스에는 아직 올리지 않았음(지금 Claude 계정은 캔버스를 읽지 못함)
- 보드 원본: [boards/](boards/). 캔버스에 올린 2안 보드와 같은 파일이고, `canvas.json`은 1안을 뺀 배치임
- 토큰 원본: [`design/base.tokens.json`](../../design/base.tokens.json). DTCG 2025.10 형식임

## 0. 정한 것

| 언제 | 누가, 어디서 | 무엇 |
| --- | --- | --- |
| 2026-11-12 17:34 KST | 동희님, 이 스레드 | 첫 초안(이전 웹 제품 Pillbox 토큰으로 그림)을 보고 "목업 너무 안예쁜데;;;; 왤캐 AI 슬롭이 심하냐;; 이거 해결좀해봐". 회색 바탕, 흰 카드, 초록 버튼, 색 점 태그, 가짜 상태 표시줄, 글자 기호 아이콘을 버리고, 약을 먹는 실제 물건에서 출발해 두 방향을 다시 그림 |
| 2026-11-12 18:27 KST | 동희님, 이 스레드의 결정 카드 "어느 목업 방향으로 화면을 이어 그릴까요?" | **'2안 요일 약통'** 을 고름. 1안 약봉투는 캔버스 아래에 참고로만 남기고 더 그리지 않음 |
| 2026-11-12 19:11 KST | 동희님, 이 스레드 "모두 승인" (18:57 메시지의 세 가지, 셋 다 Claude 추천) | (1) 위젯이 넘칠 때 '더 있음'은 글자 없이 작은 아래 화살표로만 보임(REQ-078). (2) 위젯의 가장 작은 크기를 실제 4×2 칸으로 올림(REQ-026, 250×110dp 기준을 버림). (3) 이 문서 2장의 토큰을 지금 값으로 굳혀 개발에 넘김 |
| 2026-11-13 00:16 KST | 동희님, 프로젝트 채팅 "추천 모두 승인" (04 명세 고정 질문 4. 질문은 00:03 KST 프로젝트 스레드, Claude 추천) | 글꼴 Gothic A1을 앱에 넣지 않고 기기 글꼴을 씀. 토큰 `font.family.base`를 기기 기본(`sans-serif`)으로 고치고 글자 200% 배치를 다시 잼(4.1절). 까닭: 세 굵기가 압축해도 약 2.1MB라 릴리즈 APK(약 11MB)가 2할쯤 커지고, 위젯(Glance)은 앱에 넣은 글꼴을 쓸 수 없어 넣어도 위젯과 앱 글꼴이 달라짐 |
| 2026-11-14 23:24 KST | 동희님, '필받음 재구축 인계' 채팅 "추천 모두 승인" (TASK-030 [접근성 점검 보고서](../07-qa/2026-11-14-a11y/report.md) 7절의 넷째, Claude 추천) | `color.line.strong`을 #8B909A에서 #838892로 바꿈([PR #30](https://github.com/judangbang-ops/pillbadeum_release/pull/30)). 글자 아닌 것의 3:1(WCAG 1.4.11)을 옅은 면에서도 맞추려는 것: 흰 바탕 3.20 → 3.56:1, `surface.sunken` 2.89 → 3.20:1, `surface.notice` 2.85 → 3.17:1, `surface.skip` 2.74 → 3.04:1. 입력 칸 테두리·끈 스위치·고르지 않은 칩·건너뜀 빗금과 원이 조금 진해짐 |
| 2026-11-23 KST | 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_e1c2389f7568`에 대한 오랑이 답. 질문은 `msg_37e2d2c9f943`, 넷 다 Claude 추천) | 2차 첫 릴리즈 보드 25개를 그린 대로 함(09-1), 글자 200% 배치(09-2), SCR-006 이름 칸 '약 이름' → '항목 이름'과 별칭 설명(09-3 '바꿈 승인'), 2차 보드는 캔버스에 올리지 않음(09-4). 8장 |
| 2026-11-23 KST | 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_c79a95db1d77`에 대한 오랑이 답. 개발 중 설계 바꿈 S-1, '바꿈 승인') | 응급 대비 문장 보드 `Emergency`·`EmergencyCopied`에 종류별 줄과 [복사], [모두 복사], 새 넣는 곳 안내를 그리고 글자 200% 보드 `Emergency200`을 더함. 8.2·8.6 |

## 1. 장면과 재료

장면: 챙기는 사람이 아침에 부엌이나 식탁 앞에서 약을 손에 든 채 홈 화면을 힐끗 봄. '먹었나?'를 1초 안에 확인하고, 한 번 눌러 남김. 밝은 곳이라 화면도 밝음.

재료: 요일별 약통. 일곱 칸이 있고 칸마다 색 뚜껑이 달려 있음. 그래서 사람마다 뚜껑 색을 하나 주고, 먹은 칸은 그 색으로 채움. 이번 주는 일곱 칸 줄로 보임.

## 2. 토큰

`design/base.tokens.json`이 원본임. 보드는 이 파일에서 만든 [`boards/tokens.css`](boards/tokens.css)(`gen_css.py`)의 변수 이름만 씀. 보드가 토큰에 없는 이름이나 날 색값을 쓰면 `.github/scripts/check_docs.py`가 잡음. 알림 보드는 기기가 그리는 모양이라 이 검사에서 뺌.

| 무리 | 토큰 | 값 |
| --- | --- | --- |
| 바탕 | `color.surface.default` · `sunken` · `skip` · `notice` | #FFFFFF · #F2F3F5(아직 안 온 날) · #ECEDEF(건너뜀) · #FFF1CC(안내 띠) |
| 글자 | `color.ink.default` · `muted` · `on-solid` · `notice` · `danger` | #15171C · #5C6270 · #FFFFFF · #5C4400 · #B42318 |
| 선 | `color.line.subtle` · `strong` | #E3E5E9(꾸밈만) · #838892(입력 칸, 빠짐 칸, 건너뜀 빗금. 2026-11-14까지 #8B909A) |
| 사람 뚜껑 | `color.person.1`~`6`의 `solid` · `tint` · `ink` | 파랑 #2B59C3, 주황 #C2501A, 초록 #1F7A4C, 보라 #6B43B8, 장미 #B8325F, 청록 #0F6E7A. 먹은 칸과 버튼은 solid, 아직 칸은 tint, tint 위 글자는 ink |
| 글꼴 | `font.family.base`, `font.weight.*` | 기기 기본 글꼴(`sans-serif`, 앱에 넣지 않음) 400 · 600 · 800. 보드는 Pixel·AOSP처럼 라틴은 Roboto, 한글은 Noto Sans KR로 그림. 기기 글꼴이 가진 굵기에 따라 600·800이 가까운 굵기로 보일 수 있어 기기에서 봄 |
| 글자 크기 | `font.size.*` | body-sm 14, label 15, body 16, title-sm 18, title-md 20, title-lg 22, display-sm 24, display 26 (sp) |
| 간격 | `space.*` | 2 · 4 · 6 · 8 · 12 · 16 · 20 · 24 |
| 모서리 | `radius.*` | cell 8, button 12, badge 14, tile 18, widget 24 |
| 크기 | `size.*` | 누르는 곳 48, 큰 버튼 52, 입력 칸 56, 이번 주 칸 28, 요일 배지 48, 뚜껑 40 |

색 대비 (2026-11-12 Claude가 WCAG 식으로 계산):

- 채운 칸 위 흰 글자는 4.71:1(주황)에서 6.73:1(보라) 사이임.
- 옅은 면 위 글자는 6.76:1 이상임.
- 보조 글자는 흰 바탕에서 6.11:1, 건너뜀 칸에서 5.22:1임.
- 진한 선은 흰 바탕에서 3.56:1, 옅은 면(`surface.sunken`·`notice`·`skip`)에서 3.04:1 이상임(2026-11-14 #838892로 바꾼 뒤. 그 전 #8B909A는 흰 바탕 3.2:1, 옅은 면 2.74~2.89:1).

밝은 모드만 있음. 다크 테마는 2차 후보라, 넣을 때 CORTHEX 09 계약대로 `design/theme/*.tokens.json`과 `design/tokens.resolver.json`을 더함.

## 3. 그린 화면과 상태

| 화면 | 보드 | 그린 상태 |
| --- | --- | --- |
| SCR-001 홈 화면 위젯 | `BoxWidget`, `BoxWidget200` | normal (글자 100%·200%), skipped, overflow (200%) |
| SCR-002 위젯 대상자 고르기 | `WidgetPick` | normal |
| SCR-003 반복 알림 | `Notif` | normal(펼친 모양), lock(이름 숨김), 주 1회 |
| SCR-004 기록 | `BoxHome`, `HomeEmpty`, `HomeRestored` | normal, skipped, empty, restored, permission |
| SCR-005 첫 실행 안내 | `Welcome1`, `Welcome2` | normal 두 쪽 |
| SCR-006 항목 추가·수정 | `ItemForm`, `ItemFormError`, `ItemForm200` | normal(새 항목, 주 1회), error(이름 없음), 글자 200% |
| SCR-007 판정 고치기 | `Judge`, `JudgeTaken` | 기록 없음, 먹음 |
| SCR-008 지난 기록 | `History` | normal (먹음·건너뜀·빠짐·아직·주 1회 창) |
| SCR-009 설정 | `Settings` | normal, permission(정확 알람 꺼짐) |
| SCR-010 대상자 관리 | `People`, `PersonAdd` | normal, 대상자 추가 |

아직 그리지 않은 상태가 있음. 위젯의 empty·restored·reconfigure·clock, 화면마다 loading·stale, SCR-006 quiet·gone, SCR-007 already·locked·future임. [08 화면 정의 4장](../05-ui/screens.md#4-검사-결과-2026-11-12-claude)대로 그 화면 작업에서 그림. TASK-044가 먼저임.

'스크롤 전체'라고 적은 보드는 휴대폰 한 화면(800dp)보다 길어서, 스크롤해 볼 부분까지 다 그림. 그런 화면의 [저장] 버튼은 휴대폰에서 화면 아래에 붙어 있음.

## 4. 잰 것

### 4.1 기기 글꼴로 다시 잼 (2026-11-13, Claude)

이 컨테이너의 Chromium(Playwright)에서 보드를 [`boards/measure_boards.js`](boards/measure_boards.js)로 그림. 캔버스 실행기(`support.js`)가 저장소에 없어서, 이 스크립트가 보드마다 자기 `renderVals()`로 빈칸을 채움. 글꼴은 Google Fonts의 Roboto와 Noto Sans KR(400·600·800)로, Pixel·AOSP 기본 글꼴을 흉내 냄. 앱 화면은 휴대폰에서 스크롤되므로 내용 길이만큼 늘려 재고(`UNROLL=1`), 위젯과 알림 보드는 크기를 그대로 둠. 안드로이드 기기에서 본 것은 아니고, 갤럭시 제조사 글꼴로는 재지 않음.

보드 18개를 각자의 기본 속성으로 한 번씩, 글자 크기를 고를 수 있는 보드 15개는 200%로 한 번 더 그림(33번). 잰 것은 CORTHEX `page-check.js`, 보드 끝을 넘는 글자와 상자(버튼·칸·테두리), 넘침을 숨기는 상자에 옆으로 잘린 글자, 아래로 잘린 상자임.

| 경우 | 결과 |
| --- | --- |
| 글자 100%, 보드 16개(알림 포함) | 모두 통과: 오류 0, 넘침·잘림 0 |
| 글자 200%, 앱 화면 15개 | 고친 뒤 모두 통과: 오류 0, 넘침·잘림 0. 고치기 전에는 대상자 관리(`People`)의 [되살리기] 버튼이 보드 오른쪽 끝을 11px 넘어 잘렸음 |
| 글자 200%, 위젯 2개 | 옆 넘침 없음. 목록이 위젯보다 길어 둘째 칸이 아래에서 잘리고 아래 화살표가 보임(넘칠 때의 정한 모양, REQ-078). `page-check.js`는 반쯤 보이는 둘째 칸 글자를 오류 1개로 셈 |
| 남은 경고 | 줄 끝에 낱말 하나만 남는 줄 11개(200%, 보드 9개). 기기 폭마다 달라 개발 때 다시 봄 |
| 같은 보드를 Gothic A1으로 그림(`FONT_OVERRIDE`) | 위와 같음. 글꼴 폭이 조금 달라도 버팀 |

- 고친 것: 대상자 관리 200%에서 보관된 대상자의 [되살리기]를 이름 아래 줄로 내림(`build_boards.py`의 `People`). 같은 스크립트로 Gothic A1 때의 보드를 재 보니 그때도 25px 넘었음. 그래서 글꼴 때문은 아님. 4.2절의 '앱 화면 13개'에 이 보드가 없었던 것으로 봄(추정. 그때 잰 스크립트는 남아 있지 않음). 앱(TASK-021)도 글자가 크면 버튼을 이름 아래로 내림
- 앱 화면 길이(200%)는 Gothic A1 때보다 64px 짧은 것(`HomeRestored`)부터 10px 긴 것(`Welcome2`)까지 달라짐. 4.2절에서 바꾼 200% 배치(아래 넷)는 그대로 둠

### 4.2 처음 잰 것 (2026-11-12 19시대 KST, Claude, Gothic A1)

이 컨테이너의 Chromium(Playwright)에서 보드를 실제 글꼴(Google Fonts Gothic A1)로 그림. 각 보드에서 잰 것은 다음 넷임:

- CORTHEX `page-check.js`
- 보드 오른쪽 끝을 넘는 글자
- 버튼·칸 안에서 옆으로 잘린 글자
- 목록이 넘치는지

안드로이드 기기에서 본 것은 아님.

| 경우 | 결과 |
| --- | --- |
| 글자 100%, 2안 보드 18개 | 모두 통과 (오류 0) |
| 글자 200% (안드로이드 14 비선형: 14→26, 16→28, 18→30, 20→34), 앱 화면 13개 | 고친 뒤 모두 통과. 처음 잰 때 첫 화면(`BoxHome`, `HomeRestored`)의 두 칸 줄이 오른쪽으로 넘쳤고 이번 주 칸의 약 이름이 잘렸음 |
| 남은 경고 | 줄 끝에 낱말 하나만 남는 줄 몇 개. 기기 폭마다 달라 개발 때 다시 봄 |

200%에서 바꾼 배치는 앱도 같게 만들어야 함:

- 첫 화면 '오늘' 칸은 한 줄에 하나.
- 이번 주 칸의 약 이름은 칸 위 한 줄로 올림.
- 항목 추가의 '언제 먹나요?' 다섯 칸은 세 칸씩 놓음.
- 요일 고르기는 100%에서도 네 칸·세 칸 두 줄임. 일곱 칸 한 줄은 칸 폭이 44dp라 누르는 곳 48dp(REQ-067)보다 좁기 때문임.

이 컨테이너의 첫 측정 스크립트(17:45 표)는 '옆으로 잘림' 검사가 보드 대신 첫 `<link>` 요소의 크기로 재고 있어 실제로는 아무것도 보지 않았음. 19시대에 고쳐 다시 잼. 그때의 '옆으로 잘림 없음'은 page-check의 잘림 검사만 근거였음.

## 5. 위젯 공통 (R2 기술 시험 TASK-042에서 온 사실)

- '더 있음'은 가려진 동안만 보일 수 없음. 위젯은 스크롤 위치를 앱에 알려 주지 않기 때문임. 그래서 넘칠 때 늘 보이는 작은 아래 화살표로 그림. 글자는 쓰지 않음(19:11 확인). TalkBack은 '더 있음'으로 읽음.
- 칸은 줄 단위 목록(LazyColumn)으로 만듦. 격자로 만들면 탭할 때마다 맨 위로 돌아감.
- 250×110dp에서는 머리글 아래 목록이 55dp라 한 줄 남짓만 들어가고, 글자 200%면 39dp라 한 줄도 다 안 들어감. 그래서 가장 작은 크기를 실제 4×2 칸으로 올림(REQ-026, 19:11 확인). 가로로 돌린 홈 화면의 4×2(약 670×118dp)는 여전히 낮아서 TASK-014에서 배치를 봄. TASK-014 렌더 시험: 높이 117dp에서 목록이 63dp라 첫 줄 칸(64dp)이 1dp 가려지고 화살표가 보임(스크롤은 에뮬레이터 확인 전).

## 6. 화면 정의와 다르게 그린 것 (08에 반영함)

- SCR-004: [항목 추가]를 머리글이 아니라 목록 아래의 큰 버튼으로 둠. 머리글에는 [지난 기록]·[설정]만 있음.
- SCR-005: 1쪽에 [다음]을 둠.
- SCR-007: 지금 창이면 제목을 "오늘 기록"으로 씀.

## 7. 정할 것과 개발에 넘길 것

동희님이 정한 것 (2026-11-12 19:11 KST "모두 승인"): '더 있음'은 글자 없는 아래 화살표, 가장 작은 크기는 실제 4×2 칸, 토큰은 지금 값으로 굳힘. 0장에 적음.

정한 것 (2026-11-13 00:16 KST 04 명세 고정, 동희님 "추천 모두 승인"):

- 글꼴: 앱에 넣지 않고 기기 글꼴을 씀(0장 마지막 줄). 처음 Claude 추천은 세 굵기를 넣는 것이었으나, 재 보니 압축해도 약 2.1MB이고 위젯은 기기 글꼴만 써서 추천을 바꿈. 토큰을 고치고 4.1절에서 다시 잼.
- 대상자 되살리기: 1차에 넣음(REQ-095, SPEC-044). `People` 보드의 [되살리기]가 그 화면이고, 200%에서는 이름 아래 줄로 내림(4.1절).

개발에 넘길 것:

- 앱의 사람 색 목록(`PersonColors.PALETTE`, 지금 초록 #3F7D58로 시작하는 여섯 색)을 토큰의 여섯 뚜껑 색으로 바꿈. 옅은 면과 글자 색은 저장하지 않고 토큰에서 고름. 아직 배포 전이라 저장된 값을 옮길 일은 없음.
- 버튼과 칸의 높이는 `size.*`를 씀. 누르는 곳은 48dp 이상임.
- 위젯의 `minResizeWidth`·`minResizeHeight`를 실제 4×2 칸보다 작게 줄지 않는 값으로 올림. 지금 250×110dp는 Pixel 세로에서 3칸 폭까지 줄어듦(칸 폭 약 87dp, Claude 계산이고 기기에서 재 보지 않음).
- 위젯 넘침 화살표는 글자 없이 그리고 `contentDescription`은 '더 있음'.

## 8. 2차 첫 릴리즈: 건강 카드 보드 (2026-11-23, Claude, 기능 변경)

- 상태: **09 확인 끝** (2026-11-23 KST, 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(09-1~09-4, 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_e1c2389f7568`에 대한 오랑이 답), 8.6). 2안 요일 약통과 지금 토큰을 그대로 쓰고 토큰은 바꾸지도 더하지도 않음. 08 화면 정의(2장 SCR-011~015와 '(2차)' 줄, 7장 화면 글자)를 그림
- 보드 원본: [`boards/build_boards_p2.py`](boards/build_boards_p2.py)가 2차 보드 26개(09 확인 때 25개, 개발 중 설계 바꿈 S-1에서 `Emergency200`을 더함, 8.6)와 배치 [`boards/canvas-p2.json`](boards/canvas-p2.json)을 씀. 1차 보드와 `build_boards.py`는 건드리지 않음. `HomeCard*`는 손으로 쓴 `BoxHome`에 [건강 카드]를 더해 만듦
- 그림: [`renders-p2/`](renders-p2/)에 보드마다 PNG 한 장(배율 1, 스크롤 전체). 캔버스(claude.ai Design)에는 올리지 않음(8.5절)
- 예시는 모두 가상임: 대상자 '나'·'엄마'·'아빠'(1차 보드와 같음), 약 '가상정 10mg'·'보기정 500mg', 사람 '홍가상'·'이가상', 전화번호 010-0000-0000, 의견 주소 feedback@example.com

### 8.1 그린 화면과 상태

| 화면 | 보드 | 그린 상태 |
| --- | --- | --- |
| SCR-011 건강 카드 | [`Card`](boards/Card.dc.html) ([그림](renders-p2/Card.png)), [`Card200`](boards/Card200.dc.html) ([그림](renders-p2/Card200.png)), [`CardRecopy`](boards/CardRecopy.dc.html) ([그림](renders-p2/CardRecopy.png)), [`CardArchived`](boards/CardArchived.dc.html) ([그림](renders-p2/CardArchived.png)), [`CardEmpty`](boards/CardEmpty.dc.html) ([그림](renders-p2/CardEmpty.png)) | normal(글자 100%·200%), recopy, archived, empty |
| SCR-012 카드 칸 적기·고치기 | [`CardEntry`](boards/CardEntry.dc.html) ([그림](renders-p2/CardEntry.png)), [`CardEntry200`](boards/CardEntry200.dc.html) ([그림](renders-p2/CardEntry200.png)), [`CardEntryError`](boards/CardEntryError.dc.html) ([그림](renders-p2/CardEntryError.png)) | normal(수술·시술, 글자 100%·200%), error(보호자 연락처의 전화번호 없음) |
| SCR-013 보여 주기 | [`CardShow`](boards/CardShow.dc.html) ([그림](renders-p2/CardShow.png)), [`CardShow200`](boards/CardShow200.dc.html) ([그림](renders-p2/CardShow200.png)) | normal(글자 100%·200%) |
| SCR-014 응급 대비 문장 | [`Emergency`](boards/Emergency.dc.html) ([그림](renders-p2/Emergency.png)), [`EmergencyCopied`](boards/EmergencyCopied.dc.html) ([그림](renders-p2/EmergencyCopied.png)), [`Emergency200`](boards/Emergency200.dc.html) ([그림](renders-p2/Emergency200.png)) | normal(글자 100%·200%), copied(지금 먹는 약 줄의 [복사] 뒤, Android 13 이상). 설계 바꿈 S-1(8.6) 뒤의 모양 |
| SCR-015 내보내기 | [`Export`](boards/Export.dc.html) ([그림](renders-p2/Export.png)), [`ExportPages`](boards/ExportPages.dc.html) ([그림](renders-p2/ExportPages.png)) | normal(PDF 미리보기), pages(2쪽) |
| SCR-004 기록 (바뀜) | [`HomeCard`](boards/HomeCard.dc.html) ([그림](renders-p2/HomeCard.png)), [`HomeCard200`](boards/HomeCard200.dc.html) ([그림](renders-p2/HomeCard200.png)), [`HomeCardPick`](boards/HomeCardPick.dc.html) ([그림](renders-p2/HomeCardPick.png)) | 헤더 [건강 카드](글자 100%·200%), cardpicker |
| SCR-006 항목 추가·수정 (바뀜) | [`ItemFormMeds`](boards/ItemFormMeds.dc.html) ([그림](renders-p2/ItemFormMeds.png)), [`ItemFormMeds200`](boards/ItemFormMeds200.dc.html) ([그림](renders-p2/ItemFormMeds200.png)), [`ItemFormMedsError`](boards/ItemFormMedsError.dc.html) ([그림](renders-p2/ItemFormMedsError.png)) | 약 두 줄(글자 100%·200%), error(약 이름 빈 줄) |
| SCR-009 설정 (바뀜) | [`SettingsFeedback`](boards/SettingsFeedback.dc.html) ([그림](renders-p2/SettingsFeedback.png)), [`SettingsNoMail`](boards/SettingsNoMail.dc.html) ([그림](renders-p2/SettingsNoMail.png)) | 아래쪽의 [의견 보내기], nomail |
| SCR-010 대상자 관리 (바뀜) | [`PeopleCard`](boards/PeopleCard.dc.html) ([그림](renders-p2/PeopleCard.png)), [`PeopleCard200`](boards/PeopleCard200.dc.html) ([그림](renders-p2/PeopleCard200.png)), [`PersonEditCard`](boards/PersonEditCard.dc.html) ([그림](renders-p2/PersonEditCard.png)) | '보관됨' 줄의 건강 카드 보기(글자 100%·200%), 대상자 수정의 [건강 카드] |

그리지 않은 상태: 화면마다 loading·stale·permission(1차와 같이 그 화면 작업에서 그림), SCR-012의 gone, SCR-013의 error. 1차 보드(`ItemForm`, `People`, `Settings`, `BoxHome`)는 그대로 둠.

### 8.2 그리면서 정한 것

- **글자 200%의 SCR-004 헤더** (`HomeCard200`): 헤더 버튼 셋이 한 줄에 들어가지 않으면 [건강 카드]를 헤더에서 빼고 날짜 아래에 줄 버튼으로 둠. [지난 기록]·[설정]은 헤더에 남음. 글자 100%에서는 셋 다 헤더(`HomeCard`)
- **글자 200%의 SCR-010 '보관됨' 줄** (`PeopleCard200`): 이름, '약 1개 · 지난 기록은 남아요', '건강 카드 보기 ›', [되살리기] 차례로 쌓음. [되살리기]를 이름 아래로 내리는 것은 1차 규칙(4.1절)과 같음. 줄의 [되살리기] 밖을 누르면 카드
- **설명 한 줄이 든 적는 화면**: 카드 화면은 종류 제목 아래(`Card`, `CardEmpty`), 칸 적기는 헤더 아래(`CardEntry`, `CardEntryError`), 항목 화면은 '약' 제목 아래와 약 줄 아래(`ItemFormMeds`). 설명은 `font.size.body-sm`·`color.ink.muted`로 입력과 구별함
- **카드 화면** (`Card`): 맨 위 [보여 주기](검은 큰 버튼), 그 아래 [응급 대비 문장]·[내보내기](테두리 버튼 둘, 200%에서는 세로). 지금 먹는 약과 칸 종류 여섯은 같은 제목 크기(`font.size.title-sm`). 칸 줄의 [지우기]는 `color.ink.danger` 글자 버튼
- **보여 주기** (`CardShow`): 대상자 이름 `font.size.display`(26sp), 종류 제목 `title-sm`(18sp, 보조 글자), 칸 `title-md`(20sp, 앱 본문 16sp보다 큼). 종류 사이는 진한 선. 사람 색은 쓰지 않음. 글자 200%에서 이름 36.7sp·칸 34sp이고 자르지 않음(`CardShow200`)
- **내보내기 미리보기** (`Export`): A4 세로 비율 종이를 작게 그림. 미리보기 글자는 기기 글자 크기와 상관없음(SPEC-052)
- **응급 대비 문장** (`Emergency`): 문장은 옅은 면 상자에 읽기만, 오른쪽 아래 글자 수, [복사]. 복사한 뒤(`EmergencyCopied`)는 Android 13 이상 모양(시스템이 복사를 알리고 앱은 안내 줄만)
  - 개발 중 설계 바꿈 S-1(2026-11-23, 8.6) 뒤: 위에서부터 설명 한 줄, 문장과 글자 수, [모두 복사](검은 큰 버튼, 전의 [복사]), '넣는 곳'(갤럭시·Pixel 메뉴 경로와 "칸마다 맞는 줄을 복사해 붙여 넣어 주세요."), '칸별 복사'의 종류별 줄. 줄은 제목(`font.size.label`, 굵게)과 글이 왼쪽, [복사](테두리 작은 버튼, 48dp)가 오른쪽이고 줄 사이는 옅은 선. 적은 칸이 없는 종류(예의 이식형 의료기기)는 줄이 없음. 안내 줄 "이제 긴급 정보에 붙여 넣어 주세요."는 누른 버튼 아래에 하나만(`EmergencyCopied`는 지금 먹는 약 줄). 글자 200%(`Emergency200`)에서는 줄마다 제목 → 글 → [복사]로 쌓음. 119안심콜 문장은 10-4대로 앱에 넣지 않아 보드에도 없음

### 8.3 측정한 것

4.1절과 같은 방법([`boards/measure_boards.js`](boards/measure_boards.js), CORTHEX `page-check.js`)으로 2차 보드 25개를 그림. 이 PC의 Chromium(Playwright 1.65.1)이고, 글꼴은 Google Fonts의 Roboto와 Noto Sans KR. 앱 화면은 내용 길이만큼 늘려 잼(`UNROLL=1`). 기본 글자 크기로 25번, 글자 크기를 바꿀 수 있는 보드는 200%로 한 번 더(19번), 모두 44번.

| 경우 | 결과 |
| --- | --- |
| 44번 모두 | 오류 0. 보드 끝을 넘는 글자·상자 0, 옆으로 잘린 글자 0, 아래로 잘린 상자 0 |
| 고치기 전 | 메일 앱이 없을 때(`SettingsNoMail`) 200%에서 주소가 입력 상자를 14px 넘음. 주소를 아무 데서나 줄을 바꿀 수 있게 고침 |
| 남은 경고 | 줄 끝에 짧은 단어 하나만 남는 줄 20개(대부분 200%의 설명 문장). 1차처럼 기기 폭마다 달라 개발 때 다시 봄 |

색: 토큰 짝만 씀. 1차 2장에 없던 짝 둘을 WCAG 식으로 계산함(Claude, 2026-11-23): 흰 바탕의 위험 글자(`color.ink.danger`, [지우기]·[빼기]) 6.57:1, 옅은 면(`surface.sunken`, 응급 대비 문장 상자) 위 글자 16.15:1. 나머지는 1차 2장의 짝임. 내보내기 미리보기 안의 작은 글자(6~12px)는 읽으라는 글이 아니라 종이 모양을 보이는 그림임.

### 8.4 08 화면 정의와 다르게 그린 것 (09 확인에서 여쭘)

- SCR-006 이름 칸의 글자: 1차 보드와 앱은 항목 이름 칸을 '약 이름'으로 씀(`ItemForm`, `ItemScreen.kt`). 2차에서 같은 화면에 약 줄의 '약 이름'(TERM-033)이 생겨 한 화면에 같은 글자가 두 뜻으로 보임. 2차 보드는 항목 이름 칸을 '항목 이름'(TERM-004 복용 항목)으로, 위젯 별칭 설명을 "홈 화면에서 항목 이름을 가리고 싶을 때 써요."로 그림(`ItemFormMeds`). 09 확인 09-3 '바꿈 승인'(2026-11-23 KST, 8.6). 앱 글자와 앱 시험은 11에서 고침(TASK-059)
- 보여 주기에는 사람 색을 쓰지 않음. 변경 기록 2장 제안은 '사람 색은 머리에만'이었음. 의료진에게 사람 색은 뜻이 없고 내보내기 파일(흑백 인쇄)과 같은 모양이 되게 함
- 2장 제안의 '내보낸 PDF 한 장'은 따로 보드를 두지 않고 `Export`의 미리보기로 그림. 실제 PDF는 11 개발에서 만들고 12에서 봄

### 8.5 하지 않은 것

- 캔버스에 올리지 않음: 1차 캔버스는 동희님 계정의 claude.ai Design 아티팩트이고 지금 Claude 계정은 읽지 못함(이 문서 머리). 게시는 물어야 하는 일이라 09 확인 09-4에서 여쭘. 그동안 보드 원본과 PNG를 이 저장소에 둠
- 안드로이드 기기·에뮬레이터에서 보지 않음. 갤럭시 제조사 글꼴로 재지 않음
- TalkBack 읽기 순서는 화면 정의(08)의 규칙에만 있고 보드로는 보지 않음

### 8.6 확인 기록 (2차)

- **받은 말**: 2026-11-23 KST, 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(09-1~09-4, 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_e1c2389f7568`에 대한 오랑이 답). 필받음 봇의 답장 `msg_970a9f23eaa4`: 09-1~09-4 모두 추천안대로(PR #57, 커밋 189786b 기준). 09-3은 '바꿈 승인', 09-4는 claude.ai에 올리지 않고 저장소·PR과 필받음 항목 36의 그림으로 봄. 사용자님께 09-3의 글자 바뀜을 항목 36에 따로 알렸고, 다르게 고르시면 필받음 봇이 다시 알려 주기로 함
- **정함**: 보드 25개와 글자 200% 배치를 그린 대로 개발에 넘김. SCR-006 이름 칸은 '항목 이름', 별칭 설명은 "홈 화면에서 항목 이름을 가리고 싶을 때 써요."(앱 글자와 앱 시험 `AppScreenTest`·`ItemReminderTest`는 11에서 TASK-059가 고침). 토큰은 그대로
- **넘기는 것**: 10 WBS가 화면 작업마다 이 장의 보드 이름을 끝난 조건에 적음(TASK-059~065)
- **10에서 미룸** (2026-11-23 KST, 필받음 봇이 전한 사용자님 답 "추천안 모두 승인"(10-1~10-4, 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST)과 필받음 봇 질문 `msg_9973d634e7a5`에 대한 오랑이 답), 10-4 '미룸 승인'): `Emergency`·`EmergencyCopied` 보드의 "119안심콜에 등록할 때도 쓸 수 있어요."는 TASK-056에서 소방청 공식 안내로 확인하기 전에는 앱에 넣지 않음. 보드는 그대로 둠
- **PR #57 리뷰에서 바꿈** (2026-11-23 KST, [변경 기록](../10-records/changes/2026-11-22-phase2-release1.md) 6장 'PR #57 리뷰에서 바꾼 승인 항목' R2): `ExportPages`를 SPEC-052대로 다시 그림. 1쪽은 '엄마 · 1/2쪽', 지금 먹는 약~의료기관·담당 의사, 아래 만든 날. 2쪽은 '엄마 · 2/2쪽', 보호자 연락처, 아래 마지막으로 고친 날·만든 날·문구. 안내 띠 글자는 SCR-015#pages의 "카드가 길어 N쪽이에요"(R1)의 예로 2쪽. 승인한 사람은 사용자님. 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST, "야 누가 너한테 체크포인트때 뭐 물어보면 추천 모두 승인이라고 해")과 필받음 봇 질문 `msg_d0454863115a`에 대한 오랑이 답(2026-11-23 KST). 리뷰 작업자의 질문 `msg_aab1b29ee04e`에 필받음 봇이 답장 `msg_e0b11e2309c9`로 전함: "1 추천, 2 추천, 3 추천이에요. 셋 다 고쳐 주세요." 다시 측정한 결과: 44번, 오류 0, 넘침·잘림 0, 경고 20(8.3과 같음). `renders-p2/ExportPages.png`를 다시 만듦
- **개발 중 설계 바꿈 S-1에서 바꿈** (2026-11-23 KST, [변경 기록](../10-records/changes/2026-11-22-phase2-release1.md) 6장 '개발 중 설계 바꿈: 응급 대비 문장 칸별 복사'): `Emergency`·`EmergencyCopied`를 종류별 줄과 [복사], [모두 복사], 새 넣는 곳 안내로 다시 그리고 글자 200% 보드 `Emergency200`을 더함(8.2). 10-4 때 "보드는 그대로 둠"이던 "119안심콜에 등록할 때도 쓸 수 있어요."는 넣는 곳 안내 글이 바뀌면서 보드에서 빠짐(앱에 넣지 않는 것은 10-4 그대로, 승인된 글은 screens.md SCR-014에 남김). 승인한 사람은 사용자님. 근거는 사용자님 말씀(오랑이 채팅, 2026-11-23 01시대 KST, "야 누가 너한테 체크포인트때 뭐 물어보면 추천 모두 승인이라고 해")과 필받음 봇 질문 `msg_c79a95db1d77`에 대한 오랑이 답 "추천안 모두 승인". 다시 측정한 결과(`UNROLL=1`, 이 PC의 Chromium, Playwright 1.65.1): 보드 26개, 기본 26번과 200% 19번으로 45번, 오류 0, 보드 끝을 넘는 글자·상자 0, 옆으로 잘린 글자 0, 아래로 잘린 상자 0, 경고 20(줄 끝 짧은 단어. 응급 대비 문장 보드의 경고는 `EmergencyCopied` 200%의 안내 줄 1개로, 바꾸기 전 보드를 같은 방법으로 측정한 것과 같음. `Emergency200`은 0). `renders-p2/`의 `Emergency.png`·`EmergencyCopied.png`·`Emergency200.png`를 새로 그림. 다른 보드 23개의 원본은 그대로이고, 그중 21개는 다시 그린 PNG가 저장소 PNG와 바이트까지 같음(`Export`·`HomeCard`는 크기가 같고 바이트가 달라 그대로 둠)
