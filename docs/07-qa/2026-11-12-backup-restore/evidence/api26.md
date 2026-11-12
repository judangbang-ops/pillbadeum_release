### 에뮬레이터 확인 API 26

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 대조: 시험 전송이 백업을 받음 | com.android.providers.settings 백업 성공 | Backup finished with result: Success | 통과 |
| 준비: 새 설치에서 TestA 기록 | 활성 판정 1, 표식 = device_id | 활성 판정 1, device_id 5462b1, restored_from -, pending -, 표식 5462b1 | 통과 |
| RR-70 Android 8.x | 규칙 파일 파싱 실패로 앱 전체 백업이 빈 채로 끝남(0바이트면 시험 전송이 거절), 백업에 DB 없음, 다시 설치하면 DB 없음 | Package com.judangbang.pillbadeum with result: Transport rejected package; 규칙 파일 파싱 실패 기록 있음; 전송에 백업 없음; 다시 설치 뒤 DB 없음, 표식 없음 | 통과 |
| RR-70 Android 8.x 뒤 첫 실행 | 새 설치로 처리, 안내 없음 | 안내 없음 | 통과 |

- 기기: API 26, Android SDK built for x86_64, 전송 android/com.android.internal.backup.LocalTransport
