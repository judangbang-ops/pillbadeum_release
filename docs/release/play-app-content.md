# Play Console '앱 콘텐츠' 답 초안 (TASK-036)

> **초안** (2026-11-15, Claude). Play Console에 앱을 만든 뒤(TASK-050) '앱 콘텐츠'(정책 및 프로그램 아래)에 넣을 답이다. 입력은 TASK-036 담당인 동희님이 하고, 답 초안과 근거는 Claude가 썼다.
>
> 도움말 원문은 2026-11-15에 조사 에이전트가 읽었다. 대부분 영어판이고, 계정 유형 쪽은 한국어판도 읽었다. 인용은 그 보고에서 옮겼으며, Claude가 도움말을 직접 다시 열지는 못했다(자동 접속 확인 화면).
>
> Play Console 화면의 문항 문구는 앱을 만들기 전이라 보지 못했다. 화면 문구가 이 문서와 다르면 각 절의 근거로 판단한다.

## 0. 먼저 정할 것: 개발자 계정 종류 (결정 Q11, TASK-048)

### 도움말에 적힌 것

[개발자 계정 유형 선택](https://support.google.com/googleplay/android-developer/answer/13634885?hl=ko)에서 옮김.

- 한국어판: "다음 서비스를 제공하는 경우 조직 계정을 선택해야 합니다."
- 같은 목록의 둘째 줄: "건강 앱(예: 의료 앱 및 인간 대상 연구 앱)."
- 영어판: "You must choose an organization account if you provide any of the following services:" / "Health apps, such as medical apps and human subjects research apps."
- 같은 목록의 나머지 줄은 금융 서비스, `VpnService`를 승인받은 앱, 정부 앱이다.
- 이 도움말은 "새 Play Console 계정을 설정하는 개발자를 대상"으로 한다고 적었다.
- "To create a developer account for an organization, you must provide a D-U-N-S number."

### 이 앱에 걸리는 점

- 이 앱은 건강 앱 신고(8장)에서 'Medical' 묶음의 "Medication and Treatment Management"를 고른다.
- 그 분류의 설명은 "medication reminders"를 예로 든다.
- 위 목록의 '건강 앱'이 가리키는 [건강 앱 분류](https://support.google.com/googleplay/android-developer/answer/13996367)는 의료 앱을 이렇게 설명한다.
  - "Apps that provide medical information, resources, or tools to users to enhance medical care, facilitate diagnosis and treatment"
  - 예로는 "developed by a healthcare provider (such as a HIPAA covered entity) or similar institution."인 앱을 든다.
- 개인이 만든 복용 기록 앱이 이 규칙의 '건강 앱'에 드는지는 두 도움말 어디에도 직접 나오지 않는다.

### 선택지

| | 선택지 | 좋은 점 | 걸리는 점 |
| --- | --- | --- | --- |
| 가 | 조직 계정 | 규칙을 문자 그대로 따른다 | D-U-N-S 번호가 필요하다. 받으려면 사업자(개인사업자 포함)여야 하는지는 확인하지 못했다 |
| 나 | 개인 계정 | 만들기 쉽다 | 이 앱이 규칙의 '건강 앱'으로 보이면 게시가 막히거나 조직 계정으로 옮겨야 할 수 있다. 새 개인 계정이면 테스터 12명이 14일 동안 참여해야 한다(REQ-065, 이번에 도움말을 다시 읽지는 않았다) |
| 다 | 계정을 만들기 전에 Play 개발자 지원에 묻기 | 이 앱 설명(복용 기록·알림, 의료 기관이 아님, 인터넷 권한 없음)을 보내 계정 종류를 확인받는다 | 답을 기다리는 시간이 든다 |

- **Claude 추천**: 다를 먼저 하고, 답에 따라 가 또는 나를 고른다. 계정을 이미 만드셨으면 그 종류에 맞춰 다음 일을 고친다(종류를 알려 주세요).
- **반대 의견**: 도움말이 예로 든 것(의료 기관이 만든 앱, 연구 앱)과 이 앱은 거리가 있다. 그래서 개인 계정으로도 될 가능성이 있고, 바로 나로 가는 편이 빠르다.

## 1. 요약

| 앱 콘텐츠 항목 | 답 | 절 | 동희님 |
| --- | --- | --- | --- |
| 개인정보처리방침 | TASK-035 방침을 공개한 주소 | 2 | 공개 위치와 승인 (TASK-035) |
| 앱 액세스 | 모든 기능을 특별한 액세스 권한 없이 쓸 수 있음 | 3 | 입력 |
| 광고 | 광고 없음 | 4 | 입력 |
| 콘텐츠 등급 | 설문의 모든 문항에 '아니요' | 5 | 화면 문항을 보고 입력 |
| 타겟층 및 콘텐츠 | 18세 이상만 | 6 | 확인 뒤 입력 |
| 데이터 보안 | 수집·공유하는 데이터 없음 | 7 | 입력 |
| 건강 앱 | Medication and Treatment Management 하나 | 8 | 입력 |
| 광고 ID | 사용하지 않음 | 9 | 입력 |
| 정부 앱, 금융 기능, 뉴스 앱 | 아님, 없음, 아님 | 10 | 화면에 있으면 입력 |
| 그 밖의 신고 (정확한 알람, 포그라운드 서비스 등) | 신고할 것 없음 | 11 | 화면이 물으면 그대로 답함 |

## 2. 개인정보처리방침

- **답**: [TASK-035 방침](privacy-policy.md)을 공개한 주소. 같은 주소를 앱 설정(SCR-009, `PRIVACY_POLICY_URL`)에도 넣는다.
- **근거**
  - 데이터 보안 도움말: "Even developers with apps that do not collect any user data must complete this form" 그리고 "provide a link to their privacy policy."
  - 앱 액세스·광고 도움말: "You must link to a privacy policy on your store listing and within your app, even if your app" "doesn't access personal or sensitive user data or target children."
  - 건강 콘텐츠 정책: "Your app must post a privacy policy link in the designated field within Play Console"
  - 타겟층 도움말: "You must also have added a privacy policy."
- **먼저 할 일**: 방침 공개(동희님 승인). 정할 것은 [근거와 정할 것](privacy-policy-basis.md) 3장에 있다.

## 3. 앱 액세스

- **답**: 모든 기능을 특별한 액세스 권한 없이 쓸 수 있다고 고른다. 로그인 정보는 넣지 않는다.
- **근거**
  - 앱에 서버·계정·로그인·구독이 없다(REQ-057).
  - 첫 실행 안내(SCR-005)는 [건너뛰기]로 넘길 수 있다.
  - 알림·정확한 알람 권한은 거절해도 앱을 쓸 수 있고, 설정에서 다시 켤 수 있다(SCR-009#permission, TASK-026).
  - 도움말이 로그인 정보를 요구하는 경우는 "If your entire app or parts of your app are restricted based on login credentials"이다. 이 앱은 여기에 해당하지 않는다.
- **확인하지 못한 것**: 모든 기능이 열려 있을 때 고를 선택지의 문구(도움말에 없음).

## 4. 광고

- **답**: 앱에 광고가 없다.
- **근거**
  - 결정 Q8: 1차에는 광고가 없다.
  - [개인정보 점검](../07-qa/2026-11-14-privacy/report.md): 광고 SDK가 없고 `INTERNET` 권한도 없다. 이 점검은 PR마다 CI가 다시 한다.
  - 도움말의 광고 정의: "This includes ads delivered through third-party ad software development kits (SDKs), display ads, native ads"

## 5. 콘텐츠 등급

- **설문 이메일**: `{연락 이메일}`. 방침 8장과 같은 주소를 쓴다(TASK-035 정할 것 3).
- **카테고리**: 게임이 아닌 앱 가운데 유틸리티·생산성 쪽을 고른다(Claude 추천). 도움말에 카테고리 목록이 없어 화면에서 고른다.
- **문항**: 모두 '아니요'다. 문항은 폭력, 공포, 성적 내용, 욕설, 도박, 약물·술·담배, 사용자끼리의 소통이나 공유, 위치 공유, 디지털 상품 구매 같은 것으로 알려져 있으나, 이번에 원문을 읽지 못했다.
  - 약물 문항: 이 앱이 다루는 것은 사용자가 적은 합법 의약품의 복용 기록이다. 약물 사용을 권하거나 묘사하지 않으므로 '아니요'로 본다(Claude 판단). IARC 기준 문서는 읽지 못했다.
  - 사용자 상호작용: 약 이름 같은 입력은 그 휴대폰 안에만 있고 다른 사람과 나누는 기능이 없다(REQ-057).
- **예상 등급**: 도움말에 "The content rating system for apps (not including games) in South Korea is based on Google Play Rating system." 이 있고, 등급은 3+, 7+, 12+, 16+, 19+이다. 모든 문항이 '아니요'면 가장 낮은 등급이 나올 것으로 본다(확인하지 못함).
- **확인하지 못한 것**: 카테고리 이름과 문항 원문. 도움말 세 쪽에 없었고, IARC 안내 쪽(globalratings.com)의 두 주소는 없어졌다(404).

## 6. 타겟층 및 콘텐츠

- **답**: 대상 연령은 '18세 이상'만 고른다.
- **까닭**
  - 첫 사용자는 본인과 가족의 약을 함께 챙기는 성인이다(결정 Q2, [기획 v4](../01-planning/planning-v4.md) 0장 11번).
  - 도움말: "Any apps that have at least one target audience age group that includes children must comply with" 가족 정책 요건. 아동을 대상에 넣을 까닭이 없다.
  - 스토어 등록정보(TASK-037)에도 아동을 겨냥하는 요소를 넣지 않는다. 도움말에 "If your app is not primarily designed for children under 13 but your listing contains marketing elements that suggest otherwise" 이면 "Google Play may reject your app."이라고 적혀 있다.
- **'미성년자 액세스 제한'**: 켜지 않는다(Claude 추천). 도움말은 이 기능을 "If your app is only suitable or designed for an adult audience" 일 때 쓰라고 한다. 이 앱의 내용은 성인용이 아니다.
- **동희님 확인**: 18세 이상만으로 할지 확인해 주세요. 반대 의견은, 청소년이 자기 약을 기록하는 데 쓸 수도 있어 16~17세를 넣는 것이다. 그러면 아동 기준과의 관계를 다시 봐야 한다(도움말: "if your app targets any users who are under the age of 21, you must consider if they would be considered children").

## 7. 데이터 보안

### 답

- 첫 질문 "앱에서 필수 사용자 데이터 유형을 수집하거나 공유하나요?"에는 '아니요'.
- 도움말: "If your app collects or shares any of the required user data types, select Yes. If not, select No."
- 스토어에는 수집·공유하는 데이터가 없다고 보일 것으로 본다. 그 표시 문구는 확인하지 못했다.

### 근거

- **정의**: "Collect" means transmitting data from your app off a user's device.
- **기기 안에서만 다룸**: 앱에는 `INTERNET` 권한이 없어 데이터를 기기 밖으로 보낼 수 없다(TASK-031, TC-063). 도움말: "User data accessed by your app that is only processed locally on the user's device" "and not sent off device does not need to be disclosed."
- **Android 자동 백업** (검토 B9의 물음: 자동 백업이 '수집'인가)
  - 도움말에는 Android 자동 백업 문장이 없다. "backup"이 나오는 문장은 하나뿐이다: "My app enables users to upload their data directly to Google Drive or Dropbox for backup or storage."
  - 이 앱은 백업이 종단 간 암호화될 수 있을 때만 Google 계정으로 백업한다. 근거는 `data_extraction_rules.xml`의 `disableIfNoEncryptionCapabilities="true"`와 `backup_rules.xml`의 `requireFlags="clientSideEncryption"`(결정 Q16). 도움말의 종단 간 암호화 예외에 해당한다: "User data that is sent off device, but that is unreadable by you or anyone other than the sender and recipient" "as a result of end-to-end encryption does not need to be disclosed."
  - 또 백업은 사용자가 휴대폰 설정에서 켠 Google 백업으로 사용자의 Google 계정에 간다. 개발자는 그 데이터를 받거나 볼 수 없다. 도움말의 사용자 클라우드 예외: "If the user chooses to upload their data directly to their own external drive or cloud storage account" … "this upload is governed by the external drive or cloud storage provider's terms of service and privacy policy" … "your app never collects or accesses the data in question". 같은 곳에 "It depends on the particular implementation."이라는 단서가 붙어 있다.
  - 기기 간 이전(케이블이나 무선으로 새 휴대폰에 옮기기)은 사용자의 두 휴대폰 사이에서 Android가 옮긴다. 개발자나 제3자에게 가지 않으므로 수집으로 보지 않는다.
  - **결론 (Claude 판단)**: 자동 백업과 기기 간 이전은 '수집'으로 신고하지 않는다. Google이 Android 자동 백업을 직접 적지는 않았으므로 판단으로 남긴다.
  - **반대 의견**: 백업을 하는 것은 앱이 아니라 OS다. 그러나 백업에 넣을 파일을 정하는 것은 앱이므로, 보수적으로 '수집'으로 신고할 수도 있다. 그러면 스토어에 건강 정보 수집이 보이게 되어 실제와 달라진다.
- **나머지 질문**: 도움말은 'Yes'를 고른 경우에만 전송 중 암호화와 삭제 요청 방법을 묻는다고 적었다("If you selected Yes, confirm the following by answering Yes or No:"). '아니요'일 때 건너뛰는지는 화면에서 확인한다.
- **양식은 수집이 없어도 채운다**: "Even developers with apps that do not collect any user data must complete this form"

## 8. 건강 앱 신고

- **답**: Medical 묶음의 "Medication and Treatment Management" 하나만 고른다.
- **근거**: 도움말의 설명은 "Apps for managing medication schedules, pharmacy services, and ensuring adherence to treatment plans." "May include pill identifiers, medication reminders, and prescription management." 이 앱이 하는 일은 복용 일정, 복용 기록, 복용 알림이다.
- **고르지 않는 분류**
  - Diseases and Conditions Management: 질병을 관리하는 기능이 없다.
  - Clinical Decision Support: 복용량 권고, 빠뜨렸을 때의 처치, 상호작용 판정을 하지 않는다(REQ-087).
  - Medical Reference and Education: 약 정보를 싣지 않는다.
  - Healthcare Services and Management: 병원·약국과 연결하지 않는다.
- **의료기기 앱**: 고르지 않는다. 도움말은 "Please select "Medical Device Apps" if your app is regulated"라고 한다. 이 앱은 의료기기로 허가받지 않았고, 앱 안에 "의료기기가 아니며 질병을 진단·치료·예방하지 않아요"를 보인다(REQ-036). 식약처 기준으로 의료기기에 해당하지 않는지는 [방침 근거](privacy-policy-basis.md) 4장의 '확인하지 못한 것'에 있다.
- **둘째 쪽**: 도움말에 "you'll be asked for this on a second page"가 있으나 문항은 적혀 있지 않다. 화면에서 확인한다.
- **Health Connect**: 쓰지 않는다.
- **이 분류와 계정 종류**: 0장.

## 9. 광고 ID

- **답**: 광고 ID를 사용하지 않는다.
- **근거**
  - 릴리즈 병합 매니페스트에 `com.google.android.gms.permission.AD_ID`가 없다. 확인한 자료는 CI [43696668379](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43696668379)의 개인정보 점검 자료(`privacy-release`)다.
  - 대상 SDK는 36이다. 도움말에 따르면 광고 ID를 쓰는 앱은 "when apps update their target to Android 13 or above will need to declare a Google Play services normal permission" 이다.
  - SDK가 이 권한을 넣을 수 있다는 도움말 문장: "the AD_ID permission from the SDK's library manifest will be merged with your app's main manifest by default". 이 앱의 의존성에는 그런 SDK가 없다(TASK-031).
- **확인하지 못한 것**: Play Console 신고 문항의 문구(도움말에 없음).

## 10. 정부 앱, 금융 기능, 뉴스 앱

- 정부 앱이 아니고, 금융 기능이 없고, 뉴스 앱이 아니다. 화면에 이 항목이 있으면 그렇게 답한다.
- 이 세 항목의 도움말은 이번에 읽지 않았다.

## 11. 신고할 것이 없다고 보는 항목

| 항목 | 이 앱 | 판단 |
| --- | --- | --- |
| 정확한 알람 | `SCHEDULE_EXACT_ALARM`만 쓰고 `USE_EXACT_ALARM`은 없다(병합 매니페스트) | Play Console 신고는 `USE_EXACT_ALARM`을 쓰는 앱(알람·타이머 앱, 일정 알림을 보이는 캘린더 앱)에 대한 것이다(조사 에이전트 요약, 원문 확인 못 함). `SCHEDULE_EXACT_ALARM`은 사용자가 허용해야 해서 설정의 [허용하기]로 안내한다(TASK-026) |
| 포그라운드 서비스 | 앱은 포그라운드 서비스를 시작하지 않는다. WorkManager가 넣은 `SystemForegroundService`에 유형이 없고 `FOREGROUND_SERVICE_*` 권한도 없다(병합 매니페스트) | 도움말: "When your apps target Android 14 and above, you'll need to declare any foreground service types that you use". 쓰는 유형이 없으므로 신고할 것이 없다 |
| 전체 화면 인텐트 | `USE_FULL_SCREEN_INTENT` 없음 | 해당 없음 |
| 사진·동영상, 위치, 연락처, Health Connect, 접근성 API, VPN | 쓰지 않음 | 해당 없음 |

Play Console이 위 항목을 물으면, 이 표의 '이 앱' 칸대로 답한다.

## 12. 확인한 것과 하지 못한 것

### 확인한 것

- 도움말 원문(2026-11-15, 조사 에이전트)
  - [데이터 보안](https://support.google.com/googleplay/android-developer/answer/10787469)
  - [건강 앱 신고](https://support.google.com/googleplay/android-developer/answer/14738291)
  - [건강 콘텐츠 정책](https://support.google.com/googleplay/android-developer/answer/16679511)
  - [건강 앱 분류](https://support.google.com/googleplay/android-developer/answer/13996367)
  - [계정 유형](https://support.google.com/googleplay/android-developer/answer/13634885)(영어·한국어)
  - [타겟층](https://support.google.com/googleplay/android-developer/answer/9867159)
  - [콘텐츠 등급](https://support.google.com/googleplay/android-developer/answer/9859655)
  - [앱 액세스·광고](https://support.google.com/googleplay/android-developer/answer/9859455)
  - [광고 ID](https://support.google.com/googleplay/android-developer/answer/6048248)
  - [포그라운드 서비스](https://support.google.com/googleplay/android-developer/answer/13392821)
- 릴리즈 병합 매니페스트의 권한과 구성요소: 위 CI 실행의 `privacy-release`. 대상 SDK 36, 최소 26
- [개인정보 점검 보고서](../07-qa/2026-11-14-privacy/report.md)(TASK-031)

### 확인하지 못한 것

- Play Console 화면의 문항과 선택지 문구. 앱을 만들기 전이라 볼 수 없다
- 콘텐츠 등급 설문의 카테고리와 문항
- 정확한 알람 정책의 원문. 에이전트가 읽은 쪽에서 해당 절이 인용 한도 밖이라 요약만 받았다
- 광고 ID 신고 문구
- 건강 앱 신고의 둘째 쪽
- 정부·금융·뉴스 도움말
- 새 개인 계정의 테스트 요건(12명, 14일) 도움말
- 조직 계정 규칙이 개인이 만든 복용 기록 앱에 걸리는지(0장)

## 13. 정할 것 (동희님)

1. 개발자 계정 종류 (결정 Q11): 0장. 추천은 계정을 만들기 전에 Play 개발자 지원에 묻는 것이다
2. 타겟 연령: 18세 이상만 (6장)
3. 개인정보처리방침의 공개 위치·연락처·시행일과 공개 승인: [TASK-035](privacy-policy-basis.md) 3장

## 14. 입력한 뒤

- '앱 콘텐츠'의 모든 항목이 완료로 보이는 화면을 캡처해 `docs/07-qa/`에 둔다. 그러면 TASK-036과 TC-075의 Play Console 쪽이 끝난다
- 앱 쪽 확인(설정에서 방침 열기)은 공개 주소를 넣은 PR의 시험이 맡는다(TC-036)
- 화면 문항이 이 초안과 달랐던 곳은 이 문서에 고쳐 적는다
