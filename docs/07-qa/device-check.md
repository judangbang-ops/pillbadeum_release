# 갤럭시 실기기 확인 순서

에뮬레이터로 볼 수 없는 확인을 시험용 갤럭시 한 대(기기 간 이전만 두 대)로 하는 순서입니다. 결과는 [보고서 양식](device-check-template.md)대로 `docs/07-qa/<첫날 날짜>-galaxy/report.md`에 씁니다.

- 대상
  - TASK-052: 잠금 화면 알림 여덟 칸 (TC-013, REQ-046·REQ-076, SPEC-013·SPEC-034)
  - TASK-032: 경계 직후 위젯 탭(RR-13, REQ-004), 재부팅(RR-18, REQ-084), 하룻밤 Doze 뒤 알림 지연 두 밤(REQ-048, TC-060), 위젯 반영 시간(REQ-070, TC-059), 새로 놓은 4×2 위젯 큰 글씨, 대상 API 36에서 WorkManager 2.7.1, 기기 간 이전(RR-29)
  - BUG-004의 실기기 확인 (TASK-054): 설치 직후 처음 놓은 위젯
- 누가: 동희님이 휴대폰을 만지고(위젯 놓기, 잠금 풀기, 시각 고르기, 밤에 USB 뽑기), Claude가 동희님 PC에서 [`scripts/device/device-check.ps1`](../../scripts/device/device-check.ps1)을 한 단계씩 돌리고 보고서를 씀. 사람 손이 필요한 단계는 Claude가 돌리기 전에 채팅으로 무엇을 할지 먼저 알림
- 스크립트는 루트 없이 adb만 씀. 단계마다 결과 파일을 `docs/07-qa/<날짜>-galaxy/raw/`에 남기고 요약을 화면에 씀. 단계 목록과 인수는 스크립트 첫 주석(`Get-Help`)에 있음
- 갤럭시 전에 같은 단계를 에뮬레이터(API 34·36)에서 루트 없이 돌려 봄: [`device-kit.yml`](../../.github/workflows/device-kit.yml)이 [`device_kit_check.py`](../../scripts/emulator/device_kit_check.py)로 사람 몫(위젯 놓기, 시험 PIN으로 잠금 풀기, 잠금 화면 [먹었어])을 하고, Windows PowerShell 5.1에서 스크립트를 읽어 봄. 에뮬레이터로 볼 수 없는 것(One UI 런처·잠금 화면, 실제 Doze, 하룻밤, 두 대)이 이 순서의 몫
- 날짜는 2026-11-13 기준으로 정한 순서이고, 실제로 한 날짜와 다른 점은 보고서에 적음

## 0. 지키는 것

- **PIN**: 화면 잠금 PIN은 동희님만 앎. 스크립트와 Claude는 PIN을 묻거나 적지 않고, 잠금은 사람이 품
- **항목 이름**: 시험용 영문 이름만 씀(`PillA`, `L01` 등). 캡처·화면 덤프·알림 목록이 보고서와 채팅으로 가기 때문. `additem`은 영문·숫자 이름만 받음(adb가 한글을 넣지 못함)
- **원본 파일**: `raw/`의 화면 덤프·알림 목록·로그에는 그 휴대폰의 다른 앱 알림과 기기 정보가 들 수 있어 저장소에 올리지 않음(`.gitignore`). 보고서에는 고른 캡처와 숫자만 옮기고, 캡처에 다른 앱 알림이 보이면 가림
- **휴대폰 설정**: 휴대폰을 바꾸는 단계(`install`·`exact`·`content`·`clock`·`appnames` 등)는 이 순서에 적힌 때만 부름. `clock`으로 시계를 바꾸면 그날 끝에 `clock -Mode auto`로 자동 시간을 되돌림
- **시계를 앞으로 돌린 뒤**: 여덟 칸과 RR-13은 휴대폰 시계를 다음 날로 돌려서 함. 그러면 앱 기록이 실제보다 미래 날짜에 생기므로, 그 확인이 끝나면 앱을 지우고 다시 깔아(`install -Fresh`) 밤 측정과 그 뒤 사용(TASK-047)을 깨끗한 기록에서 시작함
- 단계가 멈추면 화면에 나온 까닭대로 휴대폰을 고치고 같은 단계를 다시 부름. 멈춘 단계의 화면 덤프(`work.xml` 등)는 원인을 보려고 남겨 둠

## 1. 준비 (처음 한 번)

| # | 할 일 | 누가 | 확인 |
| --- | --- | --- | --- |
| 1 | 시험용 갤럭시(Android 14 이상), 배터리 50% 이상 | 동희님 | `info`의 기기·Android·One UI 버전 |
| 2 | 개발자 옵션에서 USB 디버깅을 켜고 PC에 꽂은 뒤, 휴대폰에 뜨는 'USB 디버깅 허용'에서 이 컴퓨터를 항상 허용 | 동희님 | `adb devices`에 `device` 한 줄 |
| 3 | 화면 잠금 PIN, 화면을 끄면 바로 잠기게(보안 잠금 설정의 자동 잠금 '즉시' 또는 '전원 버튼으로 바로 잠금'), Smart Lock·얼굴 인식 끔 | 동희님 | `info`의 화면 잠금, `lock`이 잠금 화면을 띄움 |
| 4 | 잠금 화면 알림: 알림을 '세부 정보'로 보이고 모든 알림을 보임. '내용 숨기기'는 스크립트가 바꿈(`content`) | 동희님 | 처음 한 번 `content -Mode show`·`hide` 뒤 휴대폰 설정 화면을 캡처해 그 값이 화면과 같은지 봄 |
| 5 | 화면 자동 꺼짐 2분 이상 (RR-13·지연 측정 중에 화면이 꺼지지 않게) | 동희님 | - |
| 6 | PC에 Android SDK platform-tools(adb), Windows PowerShell 5.1 이상 | Claude | `info`가 돎 |
| 7 | 깔 APK: main의 개인 APK(앱 서명 키, [`scripts/sign-personal-apk.ps1`](../../scripts/sign-personal-apk.ps1)). 이미 다른 키의 앱이 깔려 있으면 `install -Fresh`가 지우고 깖 | Claude | `install`의 versionCode·flags |

명령은 저장소 폴더에서 이렇게 부르고, 아래 표에는 `-Step` 뒤만 적음.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\device\device-check.ps1 -Step info
```

## 2. 첫째 날 낮: BUG-004, 재부팅, 여덟 칸, RR-13, 큰 글씨

| # | 단계 | 사람 | 합격 |
| --- | --- | --- | --- |
| 1 | `info` | - | 기기·버전·설정 값이 남음 |
| 2 | `install -Fresh -Apk <개인 APK>` | - | 깔림, versionCode가 main의 커밋 수 |
| 3 | `additem -Names PillA,PillB,PillC,PillD,PillE` | - | 첫 실행 안내를 [건너뛰기]로 넘기고 다섯 항목 |
| 4 | `bug004` | 홈 화면 첫 페이지에 4×2 위젯을 놓음(3분 안). 놓은 뒤 위젯 테두리가 남아 있으면 스크립트가 [뒤로]로 닫음 | **BUG-004**: 놓기 전 RescheduleReceiver 켜짐 `True`, 10초 캡처에 항목 칸이 보이고 도는 원이 아님, logcat에 우리 패키지의 패키지 바뀜 줄 0개 |
| 5 | `remind -Names PillA,PillB,PillC,PillD,PillE` | - | 다섯 항목 [알림 받기] 켬, 첫 알림 시각 08:00 |
| 6 | `exact -Mode allow`, `alarms` | - | 이 앱 알림 알람이 정확 알람 하나(보충 N-12) |
| 7 | `reboot` | 부팅 뒤 잠금을 풂 | **RR-18**: 잠금을 풀고 20초 뒤 홈 화면 위젯이 오늘로 그려짐(캡처), 경계 알람과 알림 알람이 다시 있음(`reboot-alarms`), 앱 로그에 죽은 기록 없음 |
| 8 | 아래 2.1 여덟 칸 | 칸마다 잠금을 풂 | **TASK-052** |
| 9 | 아래 2.2 RR-13 | - | **RR-13** |
| 10 | 아래 2.3 큰 글씨 | 글자 크기를 바꾸고 새 위젯을 놓음 | 새 4×2 위젯이 큰 글씨에서 잘리지 않음 |
| 11 | `clock -Mode auto`, `logcat -Name day1`, `info -Name info-day1-end` | - | 자동 시간이 돌아옴. 앱이 죽은 기록(crash) 0줄 |

### 2.1 잠금 화면 여덟 칸 (TASK-052)

시계를 내일 07:59:30으로 돌려 08:00에 다섯 항목 알림과 "복용 시간이에요"를 띄운 뒤(RR-54), 이름 표시(앱 설정 [잠금 화면에 약 이름 보이기]) 끔·켬 × 기기 '내용 숨기기' 끔(내용 표시)·켬(내용 숨김) × 잠금·해제를 캡처함. 칸 이름은 `lock-<이름 표시>-<기기 설정>-<잠금>`.

1. `appnames -Mode hide`, `content -Mode show`
2. `clock -At '<내일> 07:59:30'`, `lock`. 08:00:30까지 기다린 뒤 `snap -Name lock-off-show-locked -Names PillA,PillB,PillC,PillD,PillE`
3. 동희님이 잠금 화면의 알림을 아래로 끌어 펼침 → `snap -Name lock-off-show-locked-expanded -Names ...`
4. 동희님이 잠금을 풂 → `shade -Mode open`, `snap -Name lock-off-show-unlocked -Names ...`, `shade -Mode close`
5. `content -Mode hide` → 2~4의 `lock`·`snap`을 `lock-off-hide-*`로 (시계는 다시 돌리지 않음. 알림은 떠 있음)
6. `appnames -Mode show` (앱이 떠 있는 알림을 새 공개 범위로 다시 올림), `content -Mode show` → `lock-on-show-*`. 잠긴 칸을 캡처한 뒤 동희님이 잠금 화면의 PillA 알림에서 [먹었어]를 누름 → `snap -Name lock-on-show-locked-recorded -Names ...`. 잠금을 푼 뒤 앱 첫 화면에서 PillA가 기록됐는지 봄 (REQ-076: 잠금 해제 없이 기록)
7. `content -Mode hide` → `lock-on-hide-*`

합격 (TC-013과 같음):

- 이름 표시 끔: 잠긴 네 칸(펼친 것 포함) 어디에도 항목 이름이 없음. `snap` 요약의 '화면 글자의 항목 이름'과 '설명에만 있는 이름'이 모두 '없음'이고 알림 버튼 글자도 없음. "복용 시간이에요"는 보임
- 이름 표시 켬 + 내용 표시 + 잠금: 이름과 [먹었어]가 보이고, [먹었어]가 잠금 해제 없이 기록됨
- 이름 표시 켬 + 내용 숨김 + 잠금: 이름과 버튼이 없음. Android 16은 알림을 스스로 묶을 수 있어 "복용 시간이에요" 대신 접힌 앱 이름 줄만 보일 수 있음(보충 N-13 한계): 그대로 보고서에 적음
- 잠금 해제한 칸: 이름이 보임(이름 표시와 상관없음)
- 이름 표시가 끔인데 이름이 한 칸이라도 보이면 TASK-025의 방법을 고침 (TASK-052 끝난 조건)

### 2.2 경계 직후 위젯 탭 (RR-13)

위젯이 04:00 경계 전에 그려진 채로 경계 직후에 눌리면 옛날 날짜로 기록하면 안 됨(REQ-004).

1. 홈 화면에 위젯이 보이게 둠 (잠금 해제, 홈 화면 첫 페이지)
2. `clock -At '<모레> 03:59:30'` (2.1보다 뒤의 날)
3. `tap -Text PillA -When 04:00:00` (휴대폰 시계가 04:00:00이 되면 위젯의 PillA를 누름)
4. 곧바로 `snap -Name rr13-after -Names PillA`, 10초 뒤 `snap -Name rr13-after-10s -Names PillA`
5. 앱 [지난 기록]에서 PillA의 기록 날짜를 캡처

합격: 위젯에 "날짜가 바뀌었어요. 다시 눌러 주세요" 띠가 뜨고 기록이 없음, 또는 위젯이 이미 새 날로 다시 그려져 있어 새 날짜로 기록됨. **옛 날짜(경계 전 날)로 기록되면 실패**. `snap` 요약에 띠가 보였는지 적힘.

### 2.3 새로 놓은 4×2 위젯 큰 글씨

1. 동희님이 휴대폰의 글자 크기를 가장 크게 함
2. 홈 화면에 새 4×2 위젯을 하나 더 놓음
3. `info -Name info-font` (font_scale 값), `snap -Name widget-font-large -Names PillA,PillB,PillC,PillD,PillE`
4. 글자 크기를 되돌리고 새로 놓은 위젯을 지움

합격: 머리글과 항목 이름이 잘리지 않고, 낱말이 중간에서 끊기지 않음(앱 시험 `WidgetRenderTest`의 큰 글씨 그림과 비교).

### 2.4 첫째 날 끝: 깨끗한 설치

시계를 돌린 기록을 지우려고 `install -Fresh -Apk <개인 APK>`로 다시 깖. 앱을 지우면 홈 화면의 이 앱 위젯도 사라지므로 3장에서 다시 놓음.

## 3. 첫째 날 저녁 ~ 셋째 날 아침: 위젯 반영 시간과 두 밤

### 3.1 저녁 준비 (약 21:00)

| # | 단계 | 사람 | 합격 |
| --- | --- | --- | --- |
| 1 | `additem -Names L01,L02,L03,L04,L05,L06,L07,L08,L09,L10` | - | 열 항목 |
| 2 | `remind -Names L01,L02,L03,L04,L05,L06,L07,L08,L09,L10` | 앱에서 첫 알림 시각을 L01 06:00부터 15분씩(L10 08:15)으로 고르고, 설정의 하루 최대를 1번으로 | 항목마다 알림 켬. 하루 최대 1번이라 다시 알림 없이 항목마다 한 번 |
| 3 | - | 홈 화면 첫 페이지에 위젯을 놓고 열 항목이 다 보이게 크기를 늘림(4×4 정도). 다 안 보이면 스크립트가 위젯 목록을 밀어 찾음 | - |
| 4 | `latency -Names L01,L02,L03,L04,L05,L06,L07,L08,L09,L10 -Count 20` | - | 살아 있는 프로세스 20번: 처음 10번은 기록, 다음 10번은 "이미 기록됨" (약 6분) |
| 5 | `exact -Mode allow`, `alarms` | - | 06:00 정확 알람 하나 |
| 6 | `night -Phase start` | 끝나면 USB를 뽑고 화면을 끈 채 충전하지 않고 움직이지 않는 곳에 둠 | 시작 상태가 `raw/night-start.txt`에 남음 |

### 3.2 둘째 날 아침 (08:30 뒤)

알림이 와도 버튼을 누르지 않음(누르면 다른 알림이 사라짐).

| # | 단계 | 합격 |
| --- | --- | --- |
| 1 | USB를 꽂고 잠금을 풂. `night -Phase end -Slots 06:00,06:15,06:30,06:45,07:00,07:15,07:30,07:45,08:00,08:15` | **REQ-048 정확 알람 허용**: 열 칸의 늦음과 P50·최댓값이 `raw/night.md`에 남음. 깊은 Doze(IDLE)에 들었는지도 적힘 |
| 2 | `latency -Names L01,L02,L03,L04,L05,L06,L07,L08,L09,L10 -Count 20 -Process dead` | 내려간 프로세스 20번 (새 날이라 처음 10번은 기록) |
| 3 | `logcat -Name day2`, `info -Name info-day2` | **WorkManager 2.7.1 (API 36)**: crash 0줄, `WM-` 로그에 오류 없음, JobScheduler에 이 앱 작업 있음 |

`latency`가 두 번 끝나면 요약에 프로세스 상태·결과별 P50·P95·최댓값이 나옴 (**REQ-070**: 잠정 목표 살아 있음 P95 1초, 내려감 P95 3초). 값은 누른 때부터 위젯 머리글 자리가 처음 바뀐 캡처까지(위 끝)이고, 캡처 한 장에 0.2~0.5초가 걸려 그만큼 늦게 잡힘.

### 3.3 둘째 날 저녁 → 셋째 날 아침: 정확 알람 거부

1. `exact -Mode deny` (앱을 멈췄다가 한 번 열어 알람을 다시 걸게 함), `alarms` → 비정확 알람과 10분 창 알람(보충 N-12)
2. `night -Phase start` → USB를 뽑고 3.1의 6처럼 둠
3. 셋째 날 08:30 뒤 `night -Phase end -Slots 06:00,06:15,06:30,06:45,07:00,07:15,07:30,07:45,08:00,08:15` → **REQ-048 정확 알람 거부**. Doze 정비 창에 밀린 칸이 한꺼번에 오면 표에 '묶임'으로 적힘
4. `exact -Mode allow`로 되돌림

REQ-048의 목표 숫자는 두 밤의 결과를 보고 정해 요구 문서에 적음(4장 8번). 그 숫자는 Claude가 제안하고 동희님이 정함.

## 4. 기기 간 이전 (RR-29, 두 대)

가족 폰을 빌릴 수 있을 때 함. Android 9~11 폰이 있으면 그 폰을 한 대로 씀. 스크립트는 휴대폰이 한 대 꽂혀 있을 때만 돌므로(여럿이면 `-Serial`) 폰을 바꿔 꽂으며 함.

1. 옛 폰: 앱에 기록 몇 건과 위젯(대상자를 고른 위젯 포함), 화면 잠금 있음. `snap -Name rr29-old-home`, 앱 [지난 기록] 캡처
2. 옛 폰에서 백업: Google 백업(설정 > 계정 및 백업) 또는 `adb shell bmgr backupnow com.judangbang.pillbadeum`
3. 새 폰: Smart Switch나 Google 계정 복원으로 옮김. 그 뒤 새 폰을 꽂고 `info -Name rr29-new-info`, `snap -Name rr29-new-home`, `logcat -Name rr29-new`
4. 새 폰에서 앱을 엶 → 복원 안내 → [확인했어요] (TC-052)

합격: 기록이 그대로 옴(건수와 날짜), 복원 안내가 뜸, 위젯이 되살아나면 복원 줄을 보이고 대상자가 둘 이상이면 다시 고르게 함(보충 R-3). 런처가 위젯을 되살리지 않으면 그대로 적음. 화면 잠금이 없는 폰은 기록이 오지 않는 것이 맞음(RR-68).

위젯이 되살아났으면 `rr29-new-app.txt`에서 `onRestored: old … -> new …` 줄을 찾아 옛 위젯 번호와 새 번호를 적음. 에뮬레이터 확인은 앱을 지웠다가 복원하므로 홈 화면의 위젯이 사라져 이 줄을 볼 수 없음. 위젯 선택을 새 번호로 옮기는 방법([보충 2장의 제안](../04-spec/record-rules-supplement.md#2-복원-감지와-안내-a2))은 이 줄이 실제로 찍히는지를 보고 정함.

## 5. 보고서

Claude가 `raw/`의 요약 파일(`*-summary.txt`, `night.md`, `latency.csv`, `bug004.txt`, `remind.txt`)과 캡처를 보고 [양식](device-check-template.md)대로 씀. 숫자는 그 파일에서 그대로 옮기고, 캡처는 시험용 이름만 보이는 것을 골라 같은 폴더에 둠. 끝나면 요구 문서(REQ-048·REQ-070 줄), 시험 계획(TC-013·TC-041·TC-059·TC-060, BUG-004), WBS(TASK-032·052·054)의 상태를 고침.
