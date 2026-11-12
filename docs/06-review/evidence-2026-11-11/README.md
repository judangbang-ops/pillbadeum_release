# 검토 근거 파일 (2026-11-11)

[설계 독립 검토](../design-review-2026-11-11.md)가 "사실(실행)"이라고 적은 것의 출력과, 그것을 다시 얻는 방법임.

- 대상: 이 저장소 `7b8d9df`의 깨끗한 복제본
- 환경: 리눅스, JDK 17.0.21, Gradle 8.14.6(래퍼), Android SDK platform 36·build-tools 36.0.0
- 안드로이드 기기와 에뮬레이터에서 돌린 것은 없음. 모두 JVM과 APK 파일 검사임
- 탐침 테스트(`*ProbeTest.kt`)는 검토용이고 제품 코드가 아님. 이 폴더에만 두었고 빌드에 들어가지 않음. 안의 이름("Probe person", "Test person")은 테스트용 가짜 값임

## 파일

| 파일 | 내용 |
| --- | --- |
| [check-docs.py](check-docs.py) | 요구·화면·작업 문서가 스스로 적은 숫자(개수, 번호 빠짐, 추적 연결, 의존 순환, 크기 합)를 다시 세는 스크립트. 읽기만 함 |
| [check-docs-output.txt](check-docs-output.txt) | 위 스크립트의 출력 |
| [build-and-apk-check.txt](build-and-apk-check.txt) | JVM 테스트 71개 결과, 디버그 APK의 권한과 출처, 대상 API, 16KB 정렬, 라이브러리 이름 검색, Room이 고르는 저널 모드, DB 닫기·로그 호출 검색 |
| [WalBackupProbeTest.kt](WalBackupProbeTest.kt) | 앱과 같은 DB 설정으로 파일 DB를 만들어 400일 치를 기록하면서 파일 크기와 본 파일이 쓰인 때를 적고, 다섯 시점의 파일을 복사해 둠 |
| [inspect-wal-copies.py](inspect-wal-copies.py) | 복사해 둔 파일을 열어 표 수와 판정 행 수를 셈 (본 파일만 / 본 파일 + WAL) |
| [wal-backup-probe-result.txt](wal-backup-probe-result.txt) | 위 둘의 출력 (검토 A1) |
| [BehaviourProbeTest.kt](BehaviourProbeTest.kt) | 지금 코드가 다섯 경우(건너뜀 표시, 지난 기간키에 지금 시각, 보관한 대상자, 잘못된 시계, 주 1회 늦은 투약)에 무엇을 보여 주는지 출력함. 단정문 없이 출력만 함 |
| [behaviour-probe-result.txt](behaviour-probe-result.txt) | 위 테스트의 출력 (검토 A3, A4, A5, B3, B5) |

## 다시 돌리는 방법

저장소 루트에서 함. `aapt2`와 `zipalign`은 Android SDK의 `build-tools/36.0.0`에 있음.

```sh
# 1. 문서 숫자 다시 세기
python3 docs/06-review/evidence-2026-11-11/check-docs.py .

# 2. JVM 테스트와 디버그 APK
./gradlew :shared:jvmTest
./gradlew :androidApp:assembleDebug
aapt2 dump permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk
zipalign -c -P 16 -v 4 androidApp/build/outputs/apk/debug/androidApp-debug.apk

# 3. 탐침 테스트: 테스트 폴더에 잠깐 넣고 돌린 뒤 뺌
mkdir -p shared/src/jvmTest/kotlin/com/zmanlab/diditake/probe
cp docs/06-review/evidence-2026-11-11/*ProbeTest.kt shared/src/jvmTest/kotlin/com/zmanlab/diditake/probe/
PROBE_DIR=<새 폴더 1> ./gradlew :shared:jvmTest --tests "com.zmanlab.diditake.probe.WalBackupProbeTest"
PROBE_DIR=<새 폴더 2> ./gradlew :shared:jvmTest --tests "com.zmanlab.diditake.probe.BehaviourProbeTest"
rm -r shared/src/jvmTest/kotlin/com/zmanlab/diditake/probe

# 4. 복사해 둔 DB 파일 열어 보기
python3 docs/06-review/evidence-2026-11-11/inspect-wal-copies.py <새 폴더 1>
```

- **주의**: WAL 탐침은 `PROBE_DIR`로 받은 폴더를 통째로 지우고 시작함. 저장소 밖의 새 빈 폴더만 줌
- 출력은 `<새 폴더 1>/probe-result.txt`와 `<새 폴더 2>/behaviour-probe-result.txt`에 생김
- 3번을 돌린 뒤 `shared/build/test-results`에는 탐침 결과만 남음. 71개 결과를 다시 보려면 탐침을 뺀 뒤 `./gradlew :shared:jvmTest --rerun-tasks`를 돌림
- `build-and-apk-check.txt`의 4번은 병합 보고서(`androidApp/build/outputs/logs/manifest-merger-debug-report.txt`), 5번은 `aapt2 dump badging`, 6번의 정렬 값은 네이티브 라이브러리의 ELF 프로그램 헤더, 7·8번은 `dexdump`, 9번은 `grep`에서 옴

## 이 출력이 말하지 않는 것

- WAL 실측은 JVM(리눅스)의 결과임. 같은 SQLite 드라이버를 쓰지만 안드로이드 기기의 파일을 본 것은 아님
- 탐침 B(지난 기간키에 지금 시각)는 지금 코드에 없는 행을 직접 넣어 본 것임. 그렇게 구현했을 때의 결과이고 지금 앱에서 일어나는 일이 아님
- 디버그 변형만 빌드함. 릴리즈 변형의 권한과 라이브러리는 확인하지 않음
