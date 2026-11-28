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

## 1차 비교와 업데이트 경로 추가 조사 (2026-11-28)

### 대상 백업과 업데이트

`release_check.py`는 시험용 LocalTransport를 고르고 암호화 가능 플래그를 설정해 `bmgr backupnow`를 실행하며, 백업 DB와 WAL의 존재를 확인한다. 다음으로 `pm uninstall --user 0`, 2초 대기, `adb install -t`로 재설치하고 Android의 설치 시 복원 직후 DB를 읽는다. 이는 Android Auto Backup에 쓰는 DB 복원 시험이지 앱에서 백업 파일을 내보내고 가져오는 시험이 아니다. 설정의 백업은 설명 표시이며 DB를 수동으로 내보내고 가져오는 동작은 찾지 못했다. 건강 카드 PDF·PNG 공유는 데이터베이스 백업과 별개다.

같은 보조 스크립트에 `bmgr restore`와 `pm clear`를 쓰는 다른 시험은 있지만, 이 RR-26 경로에서는 쓰지 않는다. Android 공식 [Auto Backup 문서](https://developer.android.com/identity/data/autobackup)는 신규 설치 후 앱을 열기 전에 복원한다고 설명한다. [bmgr 문서](https://developer.android.com/tools/bmgr)는 백업·복원 시험 도구와 암호화 백업에서의 `bmgr restore` 제한을 설명한다. LocalTransport의 `is_encrypted=true`는 암호화 가능 조건을 흉내 내는 시험 설정이며 Google 클라우드 암호화 백업 자체를 검사한 것이 아니다.

1차→2차의 정상 업데이트는 앱을 삭제하지 않고 같은 서명의 APK를 덮어 설치한다. Android 공식 [adb 문서](https://developer.android.com/tools/adb)는 `install -r`이 기존 데이터를 보존한다고 설명한다. 참고한 [Android 14 AOSP 설치 코드](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/pm/InstallPackageHelper.java)는 일반 업데이트를 BackupManager의 설치 시 복원 대상에서 제외한다. 따라서 이 경로는 위의 삭제 후 복원과 다르고 기존 DB의 스키마 2→3 이주가 필요하다. 이 소스가 모든 제조사 기기의 구현과 동일하다고 검증한 것은 아니다. `upgrade_check.py`는 `install -r` 뒤 기록·설치 표식·위젯을 검증하며, TASK-068의 297→440 동일 시험 키 APK를 이용한 API 36 시험에서 기록과 위젯 선택이 유지됐다. 하지만 실제 배포 키의 449 APK와 실기기 업데이트는 확인하지 않았다. 업데이트에 실패해 앱을 지우거나 새 기기로 옮기면 Auto Backup 복원 위험이 남는다.

### 에이전트와 경쟁 가능성

매니페스트의 앱 고유 `android:backupAgent` 지정과 앱 자체 `BackupAgent` 구현은 찾지 못했다. `DidITakeApplication.onCreate`는 글꼴 배율을 읽고 `RescheduleReceiverKeeper.ensure`를 통해 WorkManager의 유일한 예약 작업을 요청한다. 이 두 구현은 1차와 2차에서 바뀌지 않았다. 컨테이너와 DB는 지연 생성되며 복원 판정 `CheckInstall`은 DB를 연 뒤 동작한다. 2차에서는 복원 판정 시 건강 카드 재확인 상태를 표시하는 처리가 더해졌다.

실패 로그에서 OS는 복원 에이전트 연결을 기다리는 사이 `clearApplicationUserData`로 시작 중인 앱 프로세스를 종료했고, 프로세스는 `start not valid`로 기동이 취소됐다. 성공 로그에서는 삭제 처리 뒤 시스템 `android/FullBackupAgent`가 기동·연결됐다. 실패 시 에이전트가 연결되기 전에 OS가 프로세스 기동을 취소한 사실은 확인되나, `Application.onCreate`의 실행 여부나 WorkManager 초기화가 경쟁을 유발했는지는 계측하지 못해 단정하지 않는다.

시험은 백업 직전에 홈으로 이동하고 1초, `am kill` 뒤 1초, 삭제 뒤 2초를 기다린다. 데이터 삭제와 에이전트 시작의 경쟁은 Android 내부 복원 중 일어났으며 고정 2초 대기나 이전 프로세스·작업 상태와 관계가 있을 수 있으나 입증되지 않았다. 대기를 늘리거나 재시도해 검사만 통과시키는 것은 실제 기기의 안전성을 보여 주지 않는다. 실기기의 데이터 복원과 기기 간 이전도 미확인이다.

### 1차 반복 결과와 추천

1차 태그 `v0.1.0.297`, 커밋 `406419ce9bd09d961888358152ffe859db56e905`의 기존 `release-emulator.yml`을 API 34 Google APIs 에뮬레이터에서 네 번 수동 실행했다. 실행 [46123745166](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46123745166), [46124728155](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46124728155), [46125543853](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46125543853), [46126492491](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46126492491) 모두 전체 워크플로와 RR-26 복원·첫 실행 검사가 통과했다. 네 보고서에서 버전 297과 복원된 기록 1건을 확인했다. 네 번에서 같은 실패를 보지 못했으므로 1차에서 불가능하다거나 2차가 만든 회귀라고 결론낼 수 없다. 2차의 동일 커밋 네 실행은 1회 실패·3회 성공이었다. 표본이 작고 같은 날의 에뮬레이터여서 발생률이나 실기기 영향을 추정하지 않는다.

복원 단계의 두 릴리즈 스크립트는 같고 워크플로·에뮬레이터 부팅 스크립트에도 차이가 없다. `release_check.py`의 차이는 뒤에 실행되는 알림 입력 방법이고, 공통 백업 헬퍼의 차이는 `log -t` 재시도 허용이다. 다만 1차·2차의 실제 앱 코드와 DB 스키마는 달라 완전히 동일한 실험 대상은 아니다.

**추천: 고친 뒤 배포.** 일반적인 앱 덮어쓰기는 문제를 관찰한 삭제·복원 경로를 직접 통과하지 않지만, APK 449 배포 뒤에도 기기 교체나 앱 삭제 시 복원은 사용자의 기록을 지키는 경로다. 최초 실패에서 복원 DB가 없었고 원인이 환경 한정인지 확인되지 않았으며 1차에서도 같은 실패를 재현하지 못했다. 실기기에서 가상 데이터로 Google 백업·복원과 297→449 실서명 업데이트를 확인하고, 원인을 특정해 적절히 고친 뒤 독립적으로 다시 확인하기를 권한다. 449 실서명 APK는 아직 없으므로 해당 업데이트 검사는 진행하지 않았다.

## 추가 원인 분석 (2026-11-28, 항목 248 e4483)

**판정: OS의 복원 시작 중 관찰된 프로세스 경쟁의 촉발 원인은 미확정이다. 수정안의 효과를 증명하지 못해 배포를 보류한다.** 최초 실패에서 16:01:22.851에 백업을 마친 옛 앱 프로세스가 종료됐고, 22.877에 옛 UID의 `SystemJobService`가 다시 시작됐다가 23.767에 앱 삭제로 종료됐다. 재설치 뒤 26.881에 새 UID의 복원 에이전트 연결 대기가 시작됐고, 26.884에 `clearApplicationUserData`가 시작 중인 프로세스를 중단했으며 26.901에 PID 6846의 시작이 무효가 됐다. 앱이 복원 DB를 읽기 전 단계지만 삭제 완료 통지와 에이전트 연결이 왜 그 순서로 겹쳤는지는 확인하지 못했다.

옛 프로세스의 `SystemJobService` 재시작은 2차 성공 실행 [46122364640](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46122364640)과 1차 성공 실행 [46123745166](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46123745166)에서도 있었다. 따라서 그 재시작만을 실패 원인으로 단정할 수 없다. 실패한 PID 6846에서 `Application.onCreate`나 새 Provider 초기화가 실행됐다는 기록은 없고 시작이 취소된 모습이지만, 이전 프로세스가 남긴 작업이나 상태의 관여까지 배제할 수는 없다.

1차 `406419c`와 2차 `98eb92b`의 백업 규칙 XML, DB 백업 대상, WorkManager 의존 버전과 `Application.onCreate` 동작은 같다. 2차에는 비공개 `CardExportProvider`, 카드 테이블과 스키마 2→3 이주, DB를 연 뒤 복원 감지에 적용하는 카드 처리, 화면 진입 뒤 공유 캐시 정리가 추가됐다. 위젯·알람·WorkManager의 차이는 조사 범위에서 로그 형식 변경이 중심이다. 원래 실패는 에이전트 연결 전 복원 DB가 없는 단계여서 이주 자체가 실패했다고 볼 근거가 없다.

### 비교 실험

모두 사용 후 버리는 API 34 에뮬레이터, 가상 데이터, 시험 키를 사용했다. `workflow_dispatch`는 공개를 시작하지 않는다. 실험 브랜치는 main을 바탕으로 한 조사 전용이다. 2차에서 추가된 Provider 선언만 제거한 실험판은 카드 공유가 되지 않아 제품 수정안이 아니다.

- 같은 에뮬레이터에서 연속 12회 백업·삭제·재설치: 현행판 [46128928279](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46128928279) 12/12, Provider 제거판 [46128928573](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46128928573) 12/12 기록 복원. 매회 화면을 여는 변경이 있으며 12개의 독립 기기 결과가 아니다.
- 독립 에뮬레이터의 첫 복원(백업 전 화면을 다시 여는 시험판): 현행판 8/8 통과, Provider 제거판은 복원에 도달한 7/7 통과. 나머지 [46129720636](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/46129720636)은 복원 전에 위젯·화면 조작에서 실패해 복원 분모에서 제외했다. 원래 시험과 화면을 다시 여는 차이가 있다.
- 백업 전 화면 재진입을 제외한 독립 에뮬레이터 비교: 현행판 8/8, Provider 제거판 8/8 기록 복원. 현행판 실행 ID: `46130323533`, `46130323600`, `46130324024`, `46130323758`, `46130323933`, `46130323929`, `46130323795`, `46130324052`. Provider 제거판: `46130324230`, `46130324014`, `46130324390`, `46130324004`, `46130324066`, `46130323849`, `46130324033`, `46130323946`. 복원 부분을 분리한 조사용 절차이며 전체 릴리즈 검사나 수정 후 검증이 아니다.

두 조건에서 원래 에이전트 연결 실패가 재현되지 않아 Provider를 원인으로 확정하거나 무관하다고 증명하지 못했다. 1차 4/4와 2차 원래 4회 중 실패 1회·성공 3회도 표본이 작아 2차 회귀 여부를 결정하지 못한다. 수정하지 않았으므로 요청된 수정 후 동일 검사 8회 통과도 수행하지 못했다. 임의 대기나 자동 재시도로 실제 복원 실패를 성공으로 바꾸지 않는다.

[Android Auto Backup](https://developer.android.com/identity/data/autobackup)은 설치 후 앱 실행 전 복원을 설명하고, [bmgr](https://developer.android.com/tools/bmgr)는 테스트 명령을 설명한다. 같은 OS 빌드에서 삭제 완료 통지와 에이전트 시작 순서를 계측해 실패를 재현하고, 원인에 맞는 수정 뒤 독립 회귀 검증이 필요하다. 실기기·Google 백업의 영향은 미확인이다.

### 실기기에서 안전하게 확인할 준비

1. 평소 쓰는 폰에서는 앱을 삭제하거나 데이터를 지우거나 초기화하지 않는다. 앱에 복용 기록 DB를 수동으로 내보내는 안전한 기능이 없고, Google 백업 표시만으로 복원이 보장되지는 않는다.
2. 실제 기록이 없고 기존 데이터를 지워도 문제가 없는 예비 폰 2대와 시험 전용 Google 계정을 준비한다. 준비할 수 없다면 복원 실험을 보류한다.
3. 첫 번째 예비 폰에 시험용 앱을 설치하고 가상의 항목과 기록을 만든다. 화면 잠금과 Google 백업을 켠 뒤 설정의 백업 화면에서 백업을 실행하고 상태를 확인한다.
4. 두 번째는 지우면 안 되는 데이터가 없는 초기 설정 대기 폰을 사용해 같은 시험 계정으로 로그인하고, 설정 중 첫 번째 폰의 백업을 선택한다. 사용 중인 폰을 시험 때문에 초기화하지 않는다.
5. 두 번째 폰의 앱에서 가상 항목·기록과 복원 안내를 확인하고 결과를 적는다. 문제가 있으면 추가 삭제나 반복 복원 없이 조사를 중단한다.

실제 배포 키로 서명한 449 APK는 공개되지 않아 이 안내만으로 297→449 실제 서명 업데이트는 확인할 수 없다. main에 2차가 이미 통합돼 있으므로 수정할 수 있게 되면 `main`을 기준으로 별도 브랜치·PR을 만든다. main 직접 push나 PR 병합은 친구 배포로 이어지므로 별도 승인과 리뷰 전에는 하지 않는다.
