# TASK-056 건강 카드 기술 시험

- 일자 2026-11-23 (KST). 기준 `release/p2-r1`의 `a06c6bc`.
- 범위 [TASK-056과 P2-PR1](../../08-wbs/wbs.md#19-2차-첫-릴리즈-건강-카드-p2-r1), [SPEC-050~053](../../04-functional-spec/spec.md), [SCR-009·013·014·015](../../05-ui/screens.md), [TC-095~098·105·109·115~118](../../12-qa/test-plan.md).
- 검토 보드 [Export](../../09-mockup/renders-p2/Export.png), [ExportPages](../../09-mockup/renders-p2/ExportPages.png), [Emergency](../../09-mockup/renders-p2/Emergency.png), [EmergencyCopied](../../09-mockup/renders-p2/EmergencyCopied.png), [CardShow](../../09-mockup/renders-p2/CardShow.png).
- 환경 GitHub Actions의 Pixel 5 하드웨어 프로필, `google_apis` x86_64, API 32·34·36. 앱 디버그 변형에 시험용 화면과 FileProvider를 추가했고, 다른 앱의 역할은 별도 수신 APK로 시험했다. 카드와 메일에 가상 데이터만 사용했다.
- 결과는 기술 시험에 적용된다. 정식 화면 배치, 화면 이동, 카드 저장, 실기기 동작은 TASK-062~065 및 TASK-068에서 확인한다.

- 최종 실행 `e27cb92`의 APK·권한 검사와 API 32·34·36 작업이 모두 통과했다. 자동 판정은 API마다 43건 통과, 실패 0건이었다. 실행의 아티팩트 `task056-api32`·`task056-api34`·`task056-api36`에 `observations.txt`, `task056-logcat.txt`, 캡처와 렌더 파일이 있다. 권한 덤프는 `task056-apks`에 있다. [최종 실행](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45136012121).
- Actions의 APK·권한 덤프는 7일, 에뮬레이터 아티팩트는 30일 보관한다. 주요 로그는 아래 항목에 옮겼다. 일부 캡처는 메뉴 전환과 복사 미리보기가 선명했던 앞선 실행에서 가져왔다. [내보내기·공유·입력 증거 `9a9d242`](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45134823500), [API 34 미리보기 `2a11dbd`](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45134082507), [API 36 미리보기 `4550746`](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45135220825).

## 1. PDF·PNG 내보내기

- **사실** — `PdfDocument`의 페이지 크기는 1/72인치 단위다. 화면의 `sp` 변환 없이 Canvas에 파일 단위로 글자와 배치를 지정할 수 있다. [Android PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument), [Android Canvas](https://developer.android.com/reference/android/graphics/Canvas) (2026-11-23 열람).
- **관찰** — API 32·34·36 모두 두 글꼴로 한글이 있는 A4 세로 PDF(595 × 842pt)를 생성했다. 본문 12pt, 제목 18pt, 한 줄당 21pt인 가상 데이터에서 5줄은 1쪽, 60줄은 2쪽이었다. 두 페이지 각각에서 이름과 1/2·2/2 쪽 번호를 추출했다. 짧은 PNG는 1190 × 1684px 한 장이었고, 60줄은 같은 폭의 세로로 긴 PNG 한 장이었다. 시스템 글자 크기 100%와 200%에서 각 PDF를 래스터화한 PNG 및 직접 생성한 PNG의 SHA-256이 API별로 일치했다. TC-097의 카드 데이터와 종류 제목을 포함한 쪽 수는 TASK-064에서 확인한다. [기기 기본 PDF 1쪽](evidence/api34-system-pdf-one.png), [Gothic A1 PDF 첫 쪽](evidence/api34-gothic-pdf-page-1.png), [둘째 쪽](evidence/api34-gothic-pdf-page-2.png), [PNG 한 장](evidence/api34-system-png.png).
- **TASK-064 적용** — 한 개의 레이아웃 계산을 미리보기와 파일 생성에 사용한다. PDF 페이지를 595 × 842pt로 두고 줄바꿈 후의 높이로 페이지를 나눈다. 여러 쪽이면 이름·만든 날짜·쪽 번호를 각 페이지에 넣는다. PNG는 같은 폭에서 높이를 늘린다. 긴 한 항목이 페이지 높이를 넘는 경우에도 글을 줄이거나 자르지 않도록 줄 단위 분할을 구현하고, 긴 PNG 생성 실패는 SPEC-052의 실패 상태로 처리한다.
- **글꼴 결정 대조** — TASK-056에 '앱에 든 Gothic A1'이라고 적혀 있다. [04 명세의 글꼴 결정](../../04-functional-spec/spec.md#5-고정-기록)과 [09 목업 기록](../../09-mockup/README.md)에는 기기 기본 글꼴 사용으로 승인되어 있고 기준 앱에 Gothic A1 파일이 없다. 시험 APK에만 [Gothic A1 Regular](https://github.com/google/fonts/tree/16680f8688ffcd467d2eb2146a9ce0343404581d/ofl/gothica1) (2026-11-23 열람)을 추가해 기기 기본 글꼴과 대조했다. TASK-064에는 현재 결정대로 기기 기본 글꼴을 권한다.

## 2. 공유 창·FileProvider·캐시 정리

- **사실** — FileProvider의 `<cache-path>`로 공유 범위를 좁히고, 공유 인텐트에 임시 읽기 권한을 줄 수 있다. Android Auto Backup은 `getCacheDir()`을 제외한다. [AndroidX FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [Android 공유 창](https://developer.android.com/develop/ui/compose/sharing/send), [Android Auto Backup](https://developer.android.com/identity/data/autobackup#Files) (2026-11-23 열람).
- **관찰** — API 32·34·36에서 PDF와 PNG 각각 Android 공유 창의 시험 수신 앱을 선택했다. 별도 앱 UID로 파일 시그니처를 읽었으며 `rw` 모드로 열 때는 `SecurityException`이 발생했다. 주소는 `content://`이고, 이름 대신 만든 날짜가 든 파일 이름이었다. 강제 종료 뒤 앱을 열자 `PASS (2) removed 10 files on cold start`와 `PASS (2) export cache empty after cold start`가 세 API 모두 기록됐다. [별도 앱의 PDF URI 확인](evidence/api32-share-recipient.png).
- **TASK-064 적용** — 내보내기 전용 캐시 하위 폴더에 파일을 저장한다. 공급자는 `exported=false`와 `grantUriPermissions=true`를 설정하고, 공유 인텐트에 같은 `content://` URI를 `EXTRA_STREAM`과 `ClipData`로 넣고 임시 읽기 권한을 부여한다. 날짜가 포함된 파일 이름을 사용하고, SPEC-052에 따라 다음 프로세스 시작 때 정리한다. 명세에는 수신 앱이 읽기를 마치기 전에 파일이 지워질 수 있다는 제약이 있다. 수신 앱 접근이나 백그라운드에서 프로세스를 다시 시작하는 경우의 영향은 미시험이다. TC-097의 파일 앱 저장은 TASK-064에서 확인한다.

## 3. 클립보드

- **사실** — Android 13 이상은 시스템 복사 표시를 제공한다. 민감 표시가 켜지면 시스템 미리보기가 내용을 가린다. [Android 복사하여 붙여넣기](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste), [ClipDescription.EXTRA_IS_SENSITIVE](https://developer.android.com/reference/android/content/ClipDescription#EXTRA_IS_SENSITIVE) (2026-11-23 열람).
- **관찰** — API 32·34·36에서 복사한 가상 문장을 클립보드에서 그대로 다시 읽었다. API 32에서는 민감 표시 없이 복사했고 앱의 복사 안내 두 줄이 나타났다. API 34·36에서는 민감 미리보기가 점으로 표시됐고, 대조군에는 가상 문구가 보였다. 글자 크기 100%와 가상 문장 98자의 시험 화면에서 시스템 미리보기와 앱 안내 줄은 서로 가리지 않았다. API 36에서는 복사 직후 첫 캡처보다 뒤의 프레임에 미리보기가 나타났다. [API 32 복사 안내](evidence/api32-clipboard.png), [API 34 민감 미리보기](evidence/api34-clipboard-sensitive.png), [API 34 대조군](evidence/api34-clipboard-control.png), [API 36 민감 미리보기](evidence/api36-clipboard.png), [API 36 대조군](evidence/api36-clipboard-control.png).
- **TASK-063 적용** — API 33 이상에서 `ClipDescription.EXTRA_IS_SENSITIVE`를 켜고 [복사] 아래의 진행 안내만 갱신한다. API 32에서는 일반 복사와 화면 안내를 쓴다. 복사 성공 시에만 해당 대상자의 시각을 기록하고, 클립보드 내용을 로그에 남기지 않는다. 실제 화면에서 긴 문장과 글자 크기 200%일 때도 안내 줄을 읽을 수 있는지 확인한다.

## 4. 화면 켜 두기·잠금 화면

- **사실** — `FLAG_KEEP_SCREEN_ON`을 붙인 Activity가 백그라운드에 가면 화면이 정상적으로 꺼질 수 있다. `setShowWhenLocked`는 잠금 화면 위에 Activity를 표시하는 별도 동작이다. [Android 화면 켜 두기](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on), [Android Activity](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean)) (2026-11-23 열람).
- **관찰** — API 32·34·36에서 화면 꺼짐을 30초로 설정했다. 보여 주기 화면을 62초간 그대로 두어도 `Awake`였고, 절전 후 다시 켰을 때 잠금 화면에 카드가 보이지 않았다. 시험 PIN으로 풀면 같은 카드가 다시 보였다. 뒤로 가기로 진입 화면에 돌아가고 34초를 기다렸을 때는 `Asleep`이었다. 초기 API 34 실행의 잠금 해제 확인은 실패했다. 최종 `e27cb92` 실행에서 PIN 입력 전 대기를 늘렸고, 세 API 모두 `PASS (4) PIN unlock resumes the card`와 `PASS (4) timeout restored after back`을 남겼다. 아래 API 34 캡처는 최종 실행에서 찍었다. 62초와 잠금 해제 직후 캡처는 바이트가 같으며, 이벤트 순서는 잠금 캡처와 로그에서 확인할 수 있다. [62초 뒤](evidence/api34-show-62s.png), [잠금 화면](evidence/api34-show-locked.png), [잠금 해제 뒤](evidence/api34-show-unlocked.png), [뒤로 가기 뒤](evidence/api34-show-back.png).
- **TASK-062 적용** — SCR-013이 앞에 보이는 동안에만 `FLAG_KEEP_SCREEN_ON`을 설정한다. 화면 종료와 백그라운드 이동에서 풀고, 다시 앞에 보일 때 상태에 맞춰 복구한다. 키가드 위에 표시하는 API와 매니페스트 속성은 사용하지 않는다.

## 5. 긴급 정보 메뉴, 바로 열기, 글자 수

- **사실** — Google의 Pixel 도움말은 `위급 상황 정보 앱 → 내 정보 → 의료 정보 → 항목 선택`을 안내한다. 로그인 요청이 표시되는 경우가 있으며 글자 수 한계는 확인 안 됨. [Pixel 도움말](https://support.google.com/pixelphone/answer/7055029?hl=ko) (2026-11-23 열람).
- **사실** — 삼성전자서비스의 Android 12·13·14 안내는 `설정 → 안전 및 긴급 → 의료정보`에서 건강 상태, 알레르기, 복용 중인 약 등을 나누어 입력하고 저장하는 경로를 보여 준다. 글자 수 한계와 의료정보 화면을 직접 여는 타사 앱용 공개 API는 확인 안 됨. Galaxy 실기기는 미시험. [삼성전자서비스](https://www.samsungsvc.co.kr/solution/39783) (2026-11-23 열람).
- **사실** — 공개 Android SDK는 일반 설정을 여는 `Settings.ACTION_SETTINGS`를 제공한다. AOSP EmergencyInfo의 매니페스트에는 `android.settings.EDIT_EMERGENCY_INFO`와 `android.emergency.EDIT_MEDICAL_INFO`가 있다. 두 문자열에 대한 제조사 공통 지원은 공개 Settings 문서에서 확인 안 됨. [Android Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_SETTINGS), [AOSP EmergencyInfo 매니페스트](https://android.googlesource.com/platform/packages/apps/EmergencyInfo/+/6b8e1aad/AndroidManifest.xml) (2026-11-23 열람).
- **관찰** — API 32·34는 `Settings → Safety & emergency → Emergency information → Add information → Allergies`에서 ASCII 가상 문자열 197자와 1,024자를 붙여 넣었다. 입력란에서 마지막 문자까지 보였고 저장 단계는 시험하지 않았다. 한글 문장 수용 여부와 이 칸의 최대값은 확인 안 됨. API 36에서는 `Settings → Safety & emergency → Medical information`을 선택하면 Personal Safety의 시작 화면이 열렸다. 약관 동의 단계에서 시험을 마쳐 글자 수는 확인 안 됨. `ACTION_SETTINGS`는 API 32·34·36에서 모두 열렸다. 시험한 AOSP 전용 인텐트 두 개는 API 32·34에서 열렸고 API 36에서 열리지 않았다. `android.settings.EMERGENCY_SETTINGS`는 세 이미지 모두에서 열리지 않았다. [API 34 메뉴](evidence/api34-emergency-settings.png), [API 34 입력란](evidence/api34-emergency-1024.png), [API 36 메뉴](evidence/api36-emergency-menu.png), [API 36 시작 화면](evidence/api36-emergency-onboarding.png).
- **TASK-063 적용** — 제조사에 따라 다른 메뉴 경로를 문구로 안내한다. 설정을 여는 기능이 필요하면 일반 설정의 공개 인텐트를 사용한다. 생성한 문장은 줄이지 않고 글자 수를 표시한다. 기종 공통의 글자 수 제한은 확인 안 됨으로 두고, 특정 칸에 전체 문장이 들어간다는 보장은 문구에 넣지 않는다.

## 6. 119안심콜

- **사실** — 소방청과 정부 안내는 인적사항, 병력, 복용 약물, 보호자 정보를 등록한다고 설명한다. 강원소방본부 신청서에는 건강 상태 또는 특이사항, 의료기관, 보호자 연락처가 별도 항목이다. [소방청 119안심콜서비스](https://www.nfa.go.kr/nfa/safetyinfo/emergencyservice/urelaxcallservice), [소방청 보도자료](https://www.nfa.go.kr/nfa/news/pressrelease/press/?cntId=2490&mode=view), [정부 정책브리핑 신청 안내](https://www.korea.kr/multi/visualNewsView.do?newsId=148896108), [강원소방본부 신청서](https://fire.gwd.go.kr/egf/bp/board/article/download?fileSeq=1243119) (2026-11-23 열람).
- **관찰** — API 해당 없음. [119안전신고센터](https://www.119.go.kr/Center119/main.jsp)에서 [119안심콜 서비스](https://u119.nfa.go.kr/)로 연결됨을 확인했다(2026-11-23 열람). 승인 문장 전체를 그대로 붙여 넣을 온라인 입력란 및 글자 수 한계는 공식 안내에서 확인 안 됨. 가입이나 정보 등록 실험은 하지 않았다.
- **TASK-063 적용** — 10-4 미룸 결정에 따라 앱에는 119안심콜 안내 문장을 빼고 구현한다. [10-4 결정 기록](../../10-records/changes/2026-11-22-phase2-release1.md).

## 7. 메일 앱과 패키지 가시성

- **사실** — 메일 작성에는 `ACTION_SENDTO`와 `mailto:`를 쓸 수 있다. Android 11 이상에서 패키지 가시성은 사전 조회 결과를 제한할 수 있다. Android 문서는 직접 실행을 시도한 뒤 `ActivityNotFoundException`을 처리하는 방법을 안내한다. [Android 공통 인텐트](https://developer.android.com/guide/components/intents-common#Email), [제한된 패키지 가시성에서의 실행](https://developer.android.com/training/package-visibility/use-cases), [패키지 가시성 선언](https://developer.android.com/training/package-visibility/declaring) (2026-11-23 열람).
- **관찰** — API 32·34·36에서 기본 메일 처리 앱을 끄고 [의견 보내기]를 실행했을 때 `MAIL no handler`가 기록됐다. 시험 화면에 주소와 복사 버튼이 보였고 주소 복사가 성공했다. 별도 수신 APK를 설치하자 메일용 `<queries>`가 없는 빌드에서 `MAIL resolved=null` 뒤에도 수신 화면이 열렸다. `<queries>`를 넣은 빌드에서는 같은 수신 앱이 사전 조회에도 나타났다. 두 경우 모두 `RECIPIENT mail to=true subject=true body=true`를 기록했다. 다른 인텐트 부가 값의 존재 여부와 SCR-009의 실제 화면 배치는 미시험이다. [API 34 메일 앱 없음](evidence/api34-mail-fallback.png).
- **TASK-065 적용** — [의견 보내기]에서 직접 `startActivity`를 호출하고, 실행할 앱이 없을 때에만 주소와 [복사]를 보인다. 이 경로에서는 메일 앱 사전 조회용 `<queries>`를 추가할 필요가 없다. 실제 메일 앱마다 제목·본문을 받는 방식이 달라 TASK-068에서 계정 없이 확인 가능한 범위를 따로 기록한다.

## 8. APK 권한

- **사실** — `aapt2 dump permissions`는 APK에 선언된 권한을 표시한다. `<queries>`는 패키지 가시성 선언이다. [Android AAPT2](https://developer.android.com/tools/aapt2), [패키지 가시성 선언](https://developer.android.com/training/package-visibility/declaring) (2026-11-23 열람).
- **관찰** — API 32·34·36에 사용한 디버그 APK 두 변형을 `aapt2 dump permissions`로 검사했다. 두 변형에서 같은 결과가 나왔다. “기준 목록과 같음 (8줄). `android.permission.INTERNET` 없음.” [최종 권한 검사 로그](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/45136012121/job/137562340072), [저장소 권한 기준](../../../androidApp/expected-permissions.txt).
- **TASK-062~065 적용** — 권한 기준을 유지한다. TASK-068에서 실제 기능이 들어간 디버그·릴리즈 APK의 권한을 각각 다시 검사한다.

## SCR-014 문구 제안

[승인된 화면 정의](../../05-ui/screens.md#scr-014-응급-대비-문장)의 넣는 곳 안내에 대한 제안이며 이 PR의 변경은 보고서와 증거에만 적용한다.

- **메뉴 경로** — “갤럭시는 설정 → 안전 및 긴급 → 의료정보에서 열어요. Pixel은 위급 상황 정보 앱 → 내 정보 → 의료 정보에서 열어요. 필요한 항목에 나누어 붙여 넣어 주세요.”
- **글자 수** — 수치 안내는 보류한다. 문장 아래의 글자 수 표시는 유지한다.
- **119안심콜** — “119안심콜에 등록할 때도 쓸 수 있어요.”의 앱 표시 보류를 유지하고, 공식 입력 경로에서 문장 사용 범위를 확인할 때 화면 정의와 명세의 표현을 다시 검토한다.
- **직접 등록 안내** — “앱은 대신 등록하지 않아요.”를 유지한다.

칸이 나뉘어 있어 한 문장을 통째로 복사하는 설계(SPEC-051)를 다시 볼 필요가 있음. 필받음 봇이 설계 체크포인트로 정함.
