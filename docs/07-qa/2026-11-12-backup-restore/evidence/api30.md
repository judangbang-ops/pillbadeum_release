### 에뮬레이터 확인 API 30

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 대조: 시험 전송이 백업을 받음 | com.android.providers.settings 백업 성공 | Backup finished with result: Success | 통과 |
| 준비: 새 설치에서 TestA 기록 | 활성 판정 1, 표식 = device_id | 활성 판정 1, device_id 1a6e79, restored_from -, pending -, 표식 1a6e79 | 통과 |
| Android 9~11, 시험 전송(암호화 플래그 없음) | 앱 백업은 돌지만 DB가 들지 않음(0바이트면 시험 전송이 거절), 다시 설치하면 DB 없음 (이 전송은 clientSideEncryption을 켤 수 없음) | Package com.judangbang.pillbadeum with result: Transport rejected package because it wasn't able to process it at the time; 규칙 파일 파싱 실패 기록 없음; 전송에 백업 없음; 다시 설치 뒤 DB 없음, 표식 없음 | 통과 |
| Android 9~11, 시험 전송(암호화 플래그 없음) 뒤 첫 실행 | 새 설치로 처리, 안내 없음 | 안내 없음 | 통과 |

- 기기: API 30, sdk_gphone_x86_64, 전송 com.android.localtransport/.LocalTransport
