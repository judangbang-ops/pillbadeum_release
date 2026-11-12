# 릴리즈 변형 확인 결과 (API 34)

[실행 43160759272](https://github.com/judangbang-ops/pillbadeum_release/actions/runs/43160759272)의 `release-check-api34` 아티팩트 `report.md`를 그대로 옮김. 머리글만 한 단계 낮춤.

## 릴리즈 변형 확인 API 34

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 대조: 시험 전송이 백업을 받음 | com.android.providers.settings 백업 성공 | Backup finished with result: Success | 통과 |
| 릴리즈 변형 N 설치 | debuggable 아님, run-as 거부, 백업 허용 | versionCode 77, flags [HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP], run-as: run-as: package not debuggable: com.judangbang.pillbadeum | 통과 |
| 위젯 탭 기록 (릴리즈 N) | 활성 판정 1, source WIDGET, device_id·표식 있음 | 위젯에 TestA 보임; 활성 판정 1, source ['WIDGET'], device_id f50188, 표식 f50188 | 통과 |
| B-1 릴리즈 변형 백업에 든 파일 (암호화 있음) | 앱 백업이 성공하고 기록 DB와 짝 파일만 | Package com.judangbang.pillbadeum with result: Success; 백업 안: _manifest, _meta, db/diditake.db-wal, db/diditake.db-wal, db/diditake.db | 통과 |
| RR-26 백업 → 지우고 다시 설치 (릴리즈 N) | 기록 1건(source WIDGET)이 돌아옴, 표식 없음 | 활성 판정 1, device_id f50188, restored_from -, pending -, 표식 -, source ['WIDGET'] | 통과 |
| 복원 뒤 첫 실행 (릴리즈 N) | 복원 안내, 새 device_id = 표식, restored_from = 옛 device_id | 안내 보임; 활성 판정 1, device_id 6a79d7, restored_from f50188, pending 1, 표식 6a79d7 | 통과 |
| 복원 뒤 위젯 (보충 R-3) | 머리글 아래 '복원됨 · … · 앱에서 확인' 줄 | 복원됨 · 마지막 기록 11/12 14:27 · 앱에서 확인 | 통과 |
| [확인했어요] 뒤 위젯 (RR-33 위젯 몫) | 위젯에 TestA가 보이고 복원 줄이 사라짐 | 위젯 TestA 보임, 복원 줄 없음 | 통과 |
| 다음 빌드 N+1을 덮어 설치 | 버전 번호가 커짐, 기록·device_id·표식 그대로 | versionCode 77 → 78; 활성 판정 1, device_id 6a79d7, restored_from f50188, pending 0, 표식 6a79d7 | 통과 |
| 덮어 설치 뒤 위젯 탭 (N+1) | 기록 2건, 둘 다 source WIDGET, 새 기록의 device_id = 이 폰 값, 옛 기록은 옛 값 | 위젯에 TestB 보임; 활성 판정 2, source ['WIDGET', 'WIDGET'], device_id ['f50188', '6a79d7'], 이 폰 6a79d7 | 통과 |
| 덮어 설치 뒤 앱 | 복원 안내 없음 | 안내 없음 | 통과 |

- 기기: API 34, sdk_gphone64_x86_64, APK release-77.apk → release-78.apk, 커밋 48fa8e5c287307f042c488844413fd726512352a (PR 머리 07b343dca65b033362ef467c41c14f79fd7c3f53)
- 위젯 놓기 (n): 끈 곳 com.google.android.apps.nexuslauncher:id/widget_preview_container (59, 930, 1021, 1516)
- 위젯 놓기 (n-again): 끈 곳 com.google.android.apps.nexuslauncher:id/widget_preview_container (59, 930, 1021, 1516)

## 서명한 두 APK (같은 실행의 '릴리즈 APK 둘' 작업 로그)

버전 번호 77과 78 모두 같은 결과. 인증서는 이 실행에서만 쓰고 지운 임시 키의 것임.

```
versionCode N = 77, N+1 = 78 (48fa8e5)
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
V3.0 Signer: certificate DN: CN=Pillbadeum release check ephemeral key
V3.0 Signer: certificate SHA-256 digest: 0242d69206d30ebf7eb7f0cabe49fbb5ee1936ed4c053a33b393c63ca0317548
package: name='com.judangbang.pillbadeum' versionCode='77' versionName='0.1.0'
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
V3.0 Signer: certificate DN: CN=Pillbadeum release check ephemeral key
V3.0 Signer: certificate SHA-256 digest: 0242d69206d30ebf7eb7f0cabe49fbb5ee1936ed4c053a33b393c63ca0317548
package: name='com.judangbang.pillbadeum' versionCode='78' versionName='0.1.0'
```
