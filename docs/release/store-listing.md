# Play 스토어 등록정보 초안 (TASK-037)

> **초안** (2026-11-15, Claude). Play Console의 기본 스토어 등록정보에 넣을 글과 그림 계획이다. 입력은 TASK-038에서 동희님이 한다.
>
> 붙여 넣을 글은 [listing/ko-KR](listing/ko-KR/)의 세 파일에 있다. 이 문서는 그 글의 근거, 그림 계획, 정할 것을 적는다. 파일 이름은 Play 업로드 도구 fastlane supply가 쓰는 이름을 따랐다(Claude가 알던 것이고, 이번에 그 문서를 다시 보지는 않았다).
>
> - `{앱 이름}`은 TASK-045에서 정하는 이름으로 바꾼다.
> - 스크린샷은 이름을 정한 뒤 찍는다. 위젯 머리글(`앱 이름 · 11/15`)과 첫 실행 안내에 앱 이름이 보이기 때문이다.
>
> Play 도움말 원문은 2026-11-15에 조사 에이전트가 읽었다. 영어판을 읽었고, 건강 정책은 한국어판도 읽었다. 인용은 그 보고에서 옮겼다. Play Console 화면은 앱을 만들기 전이라 보지 못했다.

## 1. 한눈에

| 칸 | 한도 | 초안 | 상태 |
| --- | --- | --- | --- |
| 앱 이름 | 30자 | `{앱 이름}` ([title.txt](listing/ko-KR/title.txt)) | TASK-045 뒤 |
| 간단한 설명 | 80자 | 49자 ([short_description.txt](listing/ko-KR/short_description.txt)) | 초안 |
| 자세한 설명 | 4,000자 | 1,213자 ([full_description.txt](listing/ko-KR/full_description.txt)) | 초안. 건강 정책의 고지 둘 있음 |
| 앱 아이콘 | 512×512 | 없음 | TASK-045 |
| 그래픽 이미지 | 1024×500 | 없음 | TASK-045 뒤 |
| 휴대전화 스크린샷 | 2~8장 | 계획 6장(추천 조건은 4장) | TASK-045 뒤 |
| 앱 카테고리 | 하나 | 의료 (Claude 추천) | 동희님 입력 때 |
| 태그 | 5개까지 | 입력 화면의 목록에서 고름 | TASK-038 |
| 연락처 이메일 | 꼭 | `{연락 이메일}` | 동희님 결정. 방침 8장과 같은 주소 |
| 기본 언어 | 하나 | 한국어(ko-KR) | 앱을 만들 때(TASK-050) |

글자 수는 [7장](#7-검사-tc-061tc-076)의 검사가 센 값이다. Play는 전각·반각 글자를 똑같이 한 글자로 센다("Limits apply to both full-width and half-width characters").

## 2. 글

### 2.1 앱 이름

- 도움말([스토어 등록정보 도움말](https://support.google.com/googleplay/android-developer/answer/9859152), [메타데이터 정책](https://support.google.com/googleplay/android-developer/answer/9898842))
  - "Your app title must be 30 characters or less."
  - "You can add one localized name per language."
  - 이름·아이콘·개발자 이름에 순위, 가격, 프로모션, Play 프로그램을 뜻하는 글을 넣지 않는다("Don't use text or image in the app title, icon, or developer name that indicate store performance or ranking, price or promotional information, or that suggests relations to existing Google Play programs.").
- 초안: 이름만 쓴다(`{앱 이름}`).
- **Claude 추천**: 이름만 쓴다. 하는 일은 간단한 설명이 첫 줄에서 말한다.
- **반대 의견**: '복용 기록' 같은 말을 이름 뒤에 붙이면 검색에 걸리기 쉽다. 이름이 정해지면 30자 안에서 다시 본다.

### 2.2 간단한 설명 (49자)

> 먹었는지 헷갈릴 때, 위젯 한 번이면 돼요. 약 먹은 시각이 남고 가족 약도 함께 챙겨요

- 도움말
  - 간단한 설명은 "The first text users see when looking at your app's detail page in Google Play."이다.
  - 넣지 말 것: "Language that reflects or suggests Google Play performance, ranking, accolades or awards, user testimonials, or price and promotional information"
  - 넣지 말 것: "Call-to-actions, for example, "download now," "install now," "play now," or "try now.""
  - "Do not include special characters, line breaks, emojis, emoticons, repeated punctuation"
  - "Do not use capitalization for emphasis"
- 첫 문장은 앱 첫 실행 안내의 머리글(`Labels.INTRO_HEADLINE`, SCR-005)과 같다. 스토어와 앱의 첫 화면이 같은 말을 하게 했다. 둘째 문장은 남는 것(먹은 시각)과 누구의 약인지(가족)를 말한다.
- 가운뎃점(·)은 쓰지 않았다. 도움말의 'special characters'에 드는지 알 수 없어서다.
- 다른 안: "약을 먹고 위젯을 한 번 누르면 시각이 남아요. 가족 약도 함께 챙기는 복용 기록"(45자).

### 2.3 자세한 설명 (1,213자)

글은 [full_description.txt](listing/ko-KR/full_description.txt)에 있다.

구성은 이렇다.

1. 첫 줄: 첫 실행 안내의 머리글
2. 하는 일 두 문장
3. 누구를 위해 만들었는지
4. 주요 기능 9줄
5. 개인정보 4줄
6. 알아 두세요 4줄. 건강 정책의 고지 둘이 여기 있다(3장)

문장마다 앱의 어디에서 왔는지 적는다. 앱에 없는 기능이나 확인하지 않은 일은 쓰지 않았다.

| 설명의 말 | 근거 |
| --- | --- |
| 위젯에서 그 약을 한 번 누르면 먹은 시각이 남는다. 앱을 열지 않아도 된다 | SCR-001. 첫 실행 안내 "위젯에서 바로 누르면 앱을 열지 않아도 기록돼요." (`Labels.INTRO_PIN_LEAD`) |
| 오늘 먹었는지, 마지막으로 언제 먹었는지 보인다 | SCR-001의 보조 문구 `마지막 복용 어제 22:00` (REQ-021) |
| 약통 칸처럼 보이고, 누르면 먹음으로 바뀌며, 4×2로 놓는다 | SCR-001(09 목업 2안 요일 약통). 첫 실행 안내 "4×2 크기로 끌어다 놓기" (`Labels.INTRO_STEPS`) |
| 사람을 나누고 색으로 구분한다. 위젯마다 사람을 고른다 | SCR-001의 칸 색 = 대상자 색, SCR-002, SCR-010 |
| 매일 약에 아침·점심·저녁·자기 전, 주 1회 약에 요일. 주 1회 약은 마지막으로 먹은 때를 크게 | SCR-006의 주기·시간대 표시(`Labels.slot`), REQ-075 |
| 실제로 먹은 시각을 골라 남긴다 | SCR-007의 안내 "먹은 시각은 …부터 지금(…)까지 고를 수 있어요" (`Labels.judgeNote`), 기획 v4 1장의 늦은 입력 |
| 첫 알림 시각, 다시 알림 간격, 하루 최대, 방해 금지. 알림의 버튼 셋 | SCR-006의 알림, SCR-009, SCR-003의 [먹었어] [30분 뒤] [오늘 건너뜀] (`ReminderLabels`) |
| 건너뜀은 앱이나 알림에서 | 기획 v4 1장 "앱·알림에서 함. 위젯은 표시만" |
| 한 달씩 날짜별 기록 | SCR-008 |
| 하루가 바뀌는 시각은 처음에 새벽 4시이고 바꿀 수 있다 | REQ-053(기본 04:00), SCR-009 |
| 글자를 크게 해도 위젯과 화면을 읽을 수 있다 | TASK-030 [접근성 점검](../07-qa/2026-11-14-a11y/report.md)의 글자 200% 캡처 (에뮬레이터) |
| 회원 가입·로그인·광고 없음, 인터넷 권한 없음, 개발자에게 보내지 않음 | 결정 Q8, TASK-031 [개인정보 점검](../07-qa/2026-11-14-privacy/report.md), [방침 초안](privacy-policy.md) 요약 |
| Google 백업은 화면 잠금이 있는 Android 9 이상에서. 새 휴대폰으로 옮길 때 되살림 | 방침 초안 3장, 결정 Q16, 기록 규칙 보충 B-3 |
| 잠금 화면에 처음에는 약 이름이 보이지 않는다 | REQ-046(기본 끔), 방침 초안 4장 |
| 휴대폰끼리 기록이 이어지지 않는다. 한 약은 한 휴대폰에서 | REQ-074, 첫 실행 안내 (`Labels.ONE_PHONE`, `INTRO_ONE_PHONE_BODY`) |
| 알림이 배터리 설정에 따라 늦을 수 있다 | REQ-048(분 단위로 약속하지 않음), 설정의 배터리 안내 "제한되어 있어 알림이 늦을 수 있어요." (`Labels.BATTERY_NOTE`) |

쓰지 않은 것과 그 까닭은 이렇다.

| 쓰지 않은 것 | 까닭 |
| --- | --- |
| 무료, 순위, 후기, 이모지, 강조용 대문자 | Play 메타데이터 정책 (2.1·2.2의 도움말 인용) |
| 복용량, 빠뜨린 약을 어떻게 먹을지, 약끼리의 상호작용, 효과 | REQ-087 |
| 알림이 몇 분 안에 온다는 말 | REQ-048 |
| TalkBack 지원 | 화면 덤프로 읽을 문장만 봤고, TalkBack으로 듣는 것은 실기기 확인(TASK-032) 전이다 |
| 휴대폰 제조사 이름 | 갤럭시에서 먼저 쓰지만, 다른 Android 휴대폰에서도 깔린다 |

## 3. 건강 앱 고지 (REQ-064, TC-076)

### 도움말에 적힌 것

[건강 콘텐츠 및 서비스 정책](https://support.google.com/googleplay/android-developer/answer/16679511)에서 옮겼다. Policy Center가 가리키는 [answer/9878878](https://support.google.com/googleplay/android-developer/answer/9878878)도 같은 문장이다.

- 영어판
  - "Other health and medical apps must include a clear disclaimer in their app description indicating that the app is" "not a medical device and does not diagnose, treat, cure, or prevent any medical condition."
  - "Apps must also remind users to consult a healthcare professional for medical advice, diagnosis, or treatment."
- 한국어판
  - "그 외 건강 및 의료 앱은 앱 설명에 앱이 '의료 기기가 아니며 어떠한 의학적 상태도 진단, 처치, 치료 또는 예방하지 않는다'는 내용의 명확한 고지를 포함해야 합니다."
  - "또한 앱은 사용자에게 의학적인 조언, 진단 또는 치료를 위해서는 의료 전문가와 상담하도록 안내해야 합니다."
- '그 외'는 의료기기로 허가받은 앱과 견준 말이다. 의료기기 앱은 "proof of approval, clearance or certification"을 내야 한다.
- 고지는 앱 설명에 넣으라고 적혀 있다. 상담 안내는 어디에 넣을지 적혀 있지 않다.

### 이 앱의 문장

자세한 설명의 '알아 두세요' 끝 두 줄이다.

- "이 앱은 복용 여부를 남기는 기록 도구예요. 의료기기가 아니며, 어떤 질병이나 의학적 상태도 진단·처치·치료·예방하지 않아요."
- "의학적인 조언, 진단, 치료는 의사·약사 같은 의료 전문가와 상의해 주세요."

맞춘 점은 이렇다.

- 정책의 동사 넷(diagnose, treat, cure, prevent / 진단, 처치, 치료, 예방)을 다 넣었다.
  - REQ-064의 문장은 셋("진단·치료·예방")이다. 설명은 그 셋을 모두 담고 '처치'를 더했다.
  - 앱 설정의 의료 안내(`Labels.SETTINGS_MEDICAL`, REQ-036)는 셋이다. 정책은 앱 안 문구를 요구하지 않는다. [건강 앱 분류](https://support.google.com/googleplay/android-developer/answer/13996367)는 앱 안 고지를 '권장 사항'(Best practices)으로 든다. 그래서 앱 문구는 고치지 않았다.
- 상담 안내도 앱 안에 있다. 첫 화면 아래 "기록 도구이며 복용 방법은 의사·약사와 상의해 주세요."(REQ-036)와 첫 실행 안내가 그것이다.
- 이 앱이 정책의 '그 외 건강 및 의료 앱'에 드는지는 도움말이 직접 말하지 않는다. 건강 앱 신고에서 'Medication and Treatment Management'를 고르므로([앱 콘텐츠 답](play-app-content.md) 8장), 드는 것으로 보고 넣었다(Claude 판단). 넣어서 생기는 문제는 없다.

## 4. 그림

### 4.1 규격

[그래픽 애셋 도움말](https://support.google.com/googleplay/android-developer/answer/9866151)에서 옮겼다.

| 그림 | 규격 | 넣지 말 것 |
| --- | --- | --- |
| 앱 아이콘 | "32-bit PNG (with alpha)", "512px by 512px", "Maximum file size: 1024KB" | "Do not include badges or text that suggest ranking, price, Google Play categories, or otherwise mislead users." |
| 그래픽 이미지 | "JPEG or 24-bit PNG (no alpha)", "1024px by 500px" | 성과·순위·후기·수상·가격·프로모션. 기기 그림은 피하라고 권함("Device imagery (as this can become obsolete quickly or alienate some users)") |
| 휴대전화 스크린샷 | "JPEG or 24-bit PNG (no alpha)". 짧은 변 320px 이상, 긴 변 3840px 이하. "The maximum dimension of your screenshot can't be more than twice as long as the minimum dimension." | 아래 4.3 |

- 장수
  - 게시하려면 기기 종류를 합쳐 2장 이상이다("You must provide a minimum of two screenshots across different device types to publish your store listing").
  - 기기 종류마다 8장까지다("You can add up to 8 screenshots for each supported device type.").
  - 추천에 오르려면 "at least four screenshots with minimum 1080px resolution"이 있어야 하고, 세로는 "9:16 for portrait screenshots (minimum 1080x1920px)"이다.
- 스크린샷마다 대체 텍스트를 넣는다("Include alt text with each screenshot.", "140 characters or less").

### 4.2 지금 있는 캡처는 못 쓴다

QA 보고서의 캡처는 스토어 규격에 맞지 않는다. Claude가 캡처 넷의 PNG 머리를 읽어 쟀다(`docs/07-qa/2026-11-15-regression/01-empty-home.png` 등).

- 전체 화면 캡처 셋은 540×1170이다. 긴 변이 짧은 변의 2.167배라 두 배를 넘는다.
- 위젯만 자른 캡처(540×340)를 포함해 넷 모두 1080px보다 작아 추천 조건에 못 미친다.
- 넷 모두 팔레트 PNG(색 형식 3)라 '24-bit PNG'가 아니다.

휴대폰 화면을 그대로 찍어도 같은 문제가 생길 수 있다. 1080×2340 화면이면 2.167배다(계산).

그래서 스토어용 캡처는 에뮬레이터 화면을 1080×1920(9:16)으로 맞춰 따로 찍는다(Claude 계획).

### 4.3 찍을 장면 (TASK-045 뒤)

| 순서 | 장면 | 화면 | 대체 텍스트 초안 |
| --- | --- | --- | --- |
| 1 | 홈 화면 위젯. 나와 엄마의 약 넷 가운데 둘을 먹음 | SCR-001 | 홈 화면 위젯. 나와 엄마의 오늘 약이 칸으로 보이고, 먹은 약에는 체크와 먹은 시각이 있어요 |
| 2 | 펼친 복용 알림과 버튼 셋 | SCR-003 | 복용 알림. 아침 혈압약 알림에 먹었어, 30분 뒤, 오늘 건너뜀 버튼이 있어요 |
| 3 | 앱의 오늘·이번 주 | SCR-004 | 앱의 기록 화면. 사람별로 오늘 먹을 약과 먹은 시각, 이번 주 기록이 보여요 |
| 4 | 지난 기록 한 달 | SCR-008 | 지난 기록 화면. 한 달 동안 날짜별로 먹음과 건너뜀이 보여요 |
| 5 (더 넣을 때) | 약 추가와 알림 | SCR-006 | 약 추가 화면. 이름, 사람, 매일 또는 주 1회, 시간대, 알림 시작 시각을 정해요 |
| 6 (더 넣을 때) | 글자 200%의 위젯 | SCR-001 | 휴대폰 글자를 크게 한 위젯. 약 이름과 먹은 시각이 큰 글씨로 보여요 |

- 보기 데이터
  - 대상자: '나', '엄마'
  - 항목: '아침 혈압약'(매일 아침), '비타민D'(매일 점심), '저녁약'(매일 저녁), '골다공증약'(주 1회 일요일)
  - 상표 이름은 쓰지 않는다.
- 지킬 것
  - 실제 앱 화면을 찍는다("Screenshots must demonstrate the actual in-app or in-game experience").
  - 손가락 그림을 넣지 않는다("Do not include people interacting with the device").
  - 상태 표시줄을 정리한다("Edit excess elements in the notification bar before submitting. Do not show service providers or notifications."). 에뮬레이터의 시스템 UI 데모 모드로 시계·배터리·신호를 고정하고 알림 아이콘을 숨긴다.
  - 글 띠는 넣지 않는다. 넣는다면 그림의 20% 이하다("Taglines should not take up more than 20% of the image.").
- 2번은 앱의 알림 자체를 보인다.
  - 도움말의 "Do not show … notifications"는 상태 표시줄의 다른 알림 아이콘을 가리킨다고 봤다(Claude 판단).
  - 걱정되면 2번을 빼고 5번을 넣는다.
- 만드는 법: 화면 확인 스크립트(`scripts/emulator/screens_check.py`)처럼 Actions 에뮬레이터에서 앱을 써 가며 찍는다. 24-bit PNG로 저장한다. 파일은 `listing/ko-KR/images/phoneScreenshots/`에 둔다. 이름이 정해진 뒤 PR로 만든다.

### 4.4 아이콘과 그래픽 이미지

TASK-045(앱 이름과 아이콘)에서 함께 만든다.

- 그래픽 이미지(1024×500)는 앱 이름과 위젯 그림으로 만든다. 휴대폰 테두리는 넣지 않는다.
- Play Console이 그래픽 이미지를 반드시 넣으라고 하는지는 도움말 보고에 없었다. 입력 화면에서 확인한다.

## 5. 카테고리와 태그

### 도움말에 적힌 것

[카테고리와 태그 도움말](https://support.google.com/googleplay/android-developer/answer/9859673)의 앱 카테고리 설명이다.

| 카테고리 | 설명 |
| --- | --- |
| 의료(Medical) | "Drug and clinical references, calculators, handbooks for healthcare providers, medical journals and news" |
| 건강/운동(Health and Fitness) | "Personal fitness, workout tracking, diet and nutritional tips, health and safety" |
| 생산성(Productivity, 견줘 보려고) | "Notepad, to-do list, keyboard, printing, calendar, backup, calculator, conversion" |

- 태그는 "a maximum of five tags"까지 넣는다. "You should choose tags that are most obviously relevant to your app."
- 카테고리를 나중에 바꿀 수 있는지는 도움말에 없다. 앱/게임 구분만 "You can change this later."라고 적혀 있다.

### 비슷한 앱

2026-11-15에 Claude가 Play 웹 페이지(미국 스토어, 영어)를 Chromium으로 열어 카테고리 링크를 읽었다.

| 앱 | 카테고리 |
| --- | --- |
| Medisafe Pill & Med Reminder | 건강/운동 (`HEALTH_AND_FITNESS`) |
| Meds & Pill Reminder MyTherapy | 의료 (`MEDICAL`) |
| Dosecast - Pill Reminder App | 의료 (`MEDICAL`) |

### 추천

- **Claude 추천**: 의료.
  - 앱이 하는 일이 약 복용 기록이다.
  - 건강 앱 신고에서도 Medical 묶음의 'Medication and Treatment Management'를 고른다([앱 콘텐츠 답](play-app-content.md) 8장).
  - 비슷한 앱 셋 가운데 둘이 의료다.
- **반대 의견**: Play의 의료 설명은 의료인용 참고·계산 도구에 가깝고, 가장 큰 앱(Medisafe)은 건강/운동이다.
  - 계정 종류(결정 Q11)에서 '의료 앱'으로 덜 보이는 쪽이 낫다면 건강/운동을 고를 수 있다.
  - 다만 카테고리가 계정 종류 판단에 쓰인다는 근거는 도움말에 없다(추정).
- 태그는 입력 화면의 목록에서 고른다. 목록은 앱을 만들기 전이라 보지 못했다.
- 접근성 태그(예: "Screen reader-friendly", "Visual assistance")는 실기기에서 TalkBack과 큰 글씨를 확인한 뒤(TASK-032) 고른다(Claude 추천).

## 6. 연락처와 기본 언어

- 연락처 이메일
  - 도움말: "A contact email address is required."
  - 도움말: "Add an email address that Google Play users can use to contact you about this application."
  - Play 사용자가 이 주소를 본다. 방침 8장의 `{연락 이메일}`과 같은 주소를 쓴다.
  - **Claude 추천**: 개인 주소 대신 앱 연락용 주소를 따로 만든다.
- 웹사이트와 전화번호는 넣지 않아도 된다. 웹사이트를 넣으면 방침의 공개 위치(TASK-035)와 함께 정한다.
- 기본 언어
  - 도움말: "When you upload an app, the default language is English (United States, en-US)."
  - 앱 만들기 단계에 "Select a default language."가 있다.
  - → 앱을 만들 때(TASK-050) 한국어(ko-KR)를 고른다. 영어로 두면 영어 등록정보가 기본이 된다.
  - 다른 언어는 따로 쓰지 않는다. "If you don't add custom translations, Google Play automatically displays your store listing either as an automated translation generated by Google Translate or in your app's default language."

## 7. 검사 (TC-061·TC-076)

CI의 문서 대조 작업에서 [`check_wording.py`](../../.github/scripts/check_wording.py)가 PR마다 돈다.

검사하는 것은 이렇다.

- 등록정보 글
  - 세 칸의 길이
  - 3장의 고지 둘이 자세한 설명에 있는지
  - 이모지·이모티콘, 같은 기호의 반복, 강조용 대문자
  - 순위·가격·프로모션·Play 프로그램 문구
- 간단한 설명은 더해서
  - 줄바꿈
  - 한글·영문·숫자·공백과 `. , ( ) ' -` 밖의 문자
  - 설치를 재촉하는 문구
- 등록정보 글과 앱 문구 모두
  - 의학 판단 문구(REQ-087): 복용량, mg 같은 양, 두 배, 빠뜨린 약의 처치, 상호작용, 효과·부작용
  - 알림이 몇 분 안에 온다는 약속(REQ-048)
  - 앱 문구는 제품 Kotlin 코드의 한글 문자열과 `strings.xml`이고, 주석은 보지 않는다.

이 커밋에서 Claude가 돌린 결과는 이렇다.

| 칸 | 글자 수 / 한도 | 남은 자리 | 결과 |
| --- | --- | --- | --- |
| 앱 이름 | 6 / 30 | `{앱 이름}` | 통과 |
| 간단한 설명 | 49 / 80 | 없음 | 통과 |
| 자세한 설명 | 1213 / 4000 | 없음 | 통과 |

- 앱 문구: 한글이 든 문자열 429개(15개 파일)에서 걸린 것이 없다.
- 검사가 실제로 잡는지도 봤다. 금지 문구를 일부러 넣은 글과 코드(작업 폴더, 저장소에 넣지 않음)로 돌려 24개를 잡았다. 처음 돌렸을 때 "놓친 약은 생각나면 바로 드세요"를 놓쳐 규칙을 고쳤다.
- 이 검사가 못 보는 것: 사용자 후기 인용, 기능 설명이 앱과 맞는지. 2.3의 근거 표로 Claude가 문장마다 대 봤다.

## 8. 정할 것 (동희님)

| | 정할 것 | Claude 추천 |
| --- | --- | --- |
| 1 | 앱 이름 (TASK-045) | 앱 이름 칸에는 이름만 쓴다 |
| 2 | 연락 이메일 (스토어에 보임, 방침 8장과 같음) | 앱 연락용 주소를 따로 만든다 |
| 3 | 앱 카테고리 | 의료 |
| 4 | 간단한 설명 | 2.2의 초안 (다른 안도 있음) |

## 9. 다음 일

1. TASK-045 뒤
   - `title.txt`에 이름을 넣는다.
   - 4.3의 스크린샷을 에뮬레이터에서 찍어 `listing/ko-KR/images/`에 둔다.
   - 아이콘과 그래픽 이미지를 만든다.
2. TASK-038: 동희님이 Play Console에 글을 붙여 넣고 그림을 올린다. 입력을 마친 화면을 `docs/07-qa/`에 남긴다.
