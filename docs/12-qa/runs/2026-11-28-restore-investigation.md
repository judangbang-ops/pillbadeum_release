# main 릴리즈 검사의 복원 실패 조사 (항목 248)

## 판정

**원인 미확정, 배포 보류.** Android의 복원 에이전트 기동과 데이터 삭제 순서가 관계됐을 가능성은 있으나, 에뮬레이터에만 생기는 문제나 시험의 오판이라고 증명하지 못했다. 실기기에서 같은 일이 발생하지 않는다는 근거도 없으므로 시험만 완화해 APK를 배포하지 않는다.

## 대상과 증거

동일 main 커밋 `98eb92be732bb39101d79886dae09e0e2899548b`, API 34, Android 14 Google APIs x86_64, 빌드 `google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys`. 시험 데이터와 임시 서명 키를 사용했다. 공개용 키를 쓴 시험이나 실기기의 Google 백업이 아니다.

- 실패: push 실행 [46118755628](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46118755628). Actions 아티팩트 `release-check-api34`의 `report.md`, `api34-logcat.txt`, `api34-05-restored-notice.png`를 확인했다. 백업과 DB·WAL 파일 존재 검사는 통과했으나 재설치 직후 DB가 없었고, 첫 실행 뒤에도 시험 항목과 기록이 돌아오지 않았다. [화면](../../07-qa/2026-11-28-restore-investigation/failure.png)에도 복용 항목과 복원 안내가 없었다. 뒤따른 5건의 실패도 복원되지 않은 상태를 이어받았다.
- 성공: 같은 커밋의 수동 실행 [46119960135](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46119960135). 같은 이름의 아티팩트에서 보고서·logcat·화면을 확인했다. 재설치 뒤 판정 1건이 있었고 첫 실행에 [복원 안내와 `TestA` 기록](../../07-qa/2026-11-28-restore-investigation/success.png)이 보였다.
- 추가 수동 실행 [46121409827](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46121409827)과 [46122364640](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46122364640)은 성공했다. 최초 push와 세 수동 실행은 합계 4회 중 실패 1회·성공 3회다. 표본이 적어 실기기의 발생률을 추정할 수 없다. 수동 실행은 `workflow_dispatch`이므로 배포 워크플로의 자동 공개 조건에 해당하지 않는다.

## 시스템 로그 비교

실패 측 시각은 에뮬레이터 안 KST다. 16:01:26.870에 서명과 버전 일치를 확인하고 복원 전 데이터 삭제가 시작됐다. 26.881에 에이전트 연결 대기, 26.883에 `clearApplicationUserData`, 26.884에 시작 중인 앱 프로세스 종료, 26.891에 `agentDisconnected`, 26.902에 `Unable to create agent`, 26.948에 `Unable to finalize restore`가 기록됐다. 에이전트가 데이터를 복원하기 전 복원이 실패했으며, 화면과 DB 검사 결과도 실제 데이터가 없음을 보여 준다.

성공 측은 16:15:06.657에 복원 전 삭제, 06.663에 `clearApplicationUserData`, 06.699에 시스템의 `android/FullBackupAgent` 기동, 06.734에 `agentConnected`, 06.787에 복원이 완료됐다. 양쪽 모두 삭제가 있었으나, 실패 측은 연결 대기 뒤 삭제가 겹쳤고 성공 측은 그 전에 삭제가 진행됐다.

Android 14 AOSP의 [FullRestoreEngine](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/backup/java/com/android/server/backup/restore/FullRestoreEngine.java)은 표준 에이전트 연결 전 앱 데이터 삭제를 호출한다. [UserBackupManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/backup/java/com/android/server/backup/UserBackupManagerService.java)는 삭제 완료를 기다리는 구현을 담고 있다. 이들은 참고한 Android 14 소스이며 이 에뮬레이터의 정확한 빌드 소스와 일치한다고 확인한 것은 아니다.

`AndroidManifest.xml`은 별도 백업 에이전트를 지정하지 않았고 백업 설정 XML은 1차 커밋 `406419c`부터 바뀌지 않았다. 이것만으로 앱의 복원 처리에 문제가 없다고 증명할 수 없다. 로그만으로 삭제 완료 대기가 왜 일찍 끝났는지, 시험 환경에서만 생기는지 실기기 Android에서도 가능한지 구분할 수 없다. 재검사의 성공은 최초 실패의 수정이 아니며, 강제로 재시도하거나 대기 시간을 늘려 검사만 통과시키는 것은 실기기의 안전성을 보여 주지 않는다.

## 다음 확인

실패 당시 OS 버전과 기기 조건을 고정해 여러 번 시험하고, 가능하면 같은 Android 버전 실기기와 Google 백업에서도 복원을 확인한다. Android의 삭제 완료 알림과 에이전트 기동 순서를 수집해 OS 동작인지 시험 전제인지 특정한 뒤, 적절한 수정과 독립 회귀 확인을 한다. 실데이터는 사용하지 않고 복원 실패 뒤 데이터 처리도 확인한다.

배포 실행 [46119757966](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46119757966)은 건너뛰었고 최신 공개판은 `v0.1.0.297`이다. 새 서명 APK와 실기기 업데이트는 미확인이다.
