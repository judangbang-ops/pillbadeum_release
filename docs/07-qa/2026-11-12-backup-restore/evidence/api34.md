### 에뮬레이터 확인 API 34

| 경우 | 기대 | 결과 | 판정 |
| --- | --- | --- | --- |
| 대조: 시험 전송이 백업을 받음 | com.android.providers.settings 백업 성공 | Backup finished with result: Success | 통과 |
| 준비: 새 설치에서 TestA 기록 | 활성 판정 1, 표식 = device_id | 활성 판정 1, device_id 29d879, restored_from -, pending -, 표식 29d879 | 통과 |
| B-1 백업에 든 파일 (암호화 가능) | 기록 DB와 짝 파일만 | 백업 안: _manifest, db/diditake.db-wal, db/diditake.db, db/diditake.db-wal | 통과 |
| RR-26·RR-69 백업 → 지우고 다시 설치 (암호화 가능) | 기록 1건이 돌아옴, 표식 없음 | 활성 판정 1, device_id 29d879, restored_from -, pending -, 표식 - | 통과 |
| RR-30·R-2 복원 뒤 첫 실행 | 안내 보임, 새 device_id, restored_from = 옛 값, pending 1 | 안내 보임; 활성 판정 1, device_id 48d6c1, restored_from 29d879, pending 1, 표식 48d6c1 | 통과 |
| RR-32 확인 전에 기록 | 기록됨, 새 행 device_id = 새 값, 안내 남음 | 활성 판정 2, 새 행 48d6c1, 안내 남음 | 통과 |
| RR-33 [확인했어요] (알림 몫은 TASK-040) | 안내 사라짐, pending 0 | 안내 사라짐, pending 0 | 통과 |
| RR-31 같은 폰에서 업데이트 | 안내 없음, device_id 그대로 | 안내 없음; 활성 판정 2, device_id 48d6c1, restored_from 29d879, pending 0, 표식 48d6c1 | 통과 |
| RR-71 깔린 앱 위에 복원 | 복원으로 처리, 안내 보임, restored_from = 백업의 device_id | 안내 보임; 활성 판정 1, device_id 1559a7, restored_from 29d879, pending 1, 표식 1559a7 | 통과 |
| RR-34 앱 데이터 삭제 뒤 실행 | 새 설치, 안내 없음, 기록 0 | 안내 없음; 활성 판정 0, device_id c15c45, restored_from -, pending -, 표식 c15c45 | 통과 |
| 준비: 새 설치에서 TestA 기록 | 활성 판정 1, 표식 = device_id | 활성 판정 1, device_id d094e1, restored_from -, pending -, 표식 d094e1 | 통과 |
| RR-68 암호화 없음(화면 잠금 없음) | 백업에 DB 없음, 다시 설치하면 DB 없음 | Package com.judangbang.pillbadeum with result: Transport rejected package because it wasn't able to process it at the time; 전송에 백업 없음; DB 없음, 표식 없음 | 통과 |
| RR-68 그 뒤 첫 실행 | 새 설치로 처리, 안내 없음 | 안내 없음 | 통과 |
| 준비: 새 설치에서 TestA 기록 | 활성 판정 1, 표식 = device_id | 활성 판정 1, device_id a77acc, restored_from -, pending -, 표식 a77acc | 통과 |
| B-1 기기 간 이전에 든 파일 | 기록 DB와 짝 파일만 | 백업 안: _manifest, db/diditake.db-wal, db/diditake.db, db/diditake.db-wal | 통과 |
| B-3 기기 간 이전 (암호화 없음) | 기록 1건이 돌아오고 복원 안내 | 안내 보임; 활성 판정 1, device_id a77acc, restored_from -, pending -, 표식 - | 통과 |

- 기기: API 34, sdk_gphone64_x86_64, 전송 com.android.localtransport/.LocalTransport
- bmgr backupnow (is_encrypted=true): Package com.judangbang.pillbadeum with result: Success / Backup finished with result: Success
- bmgr restore: Scheduling restore: Local disk image / restoreStarting: 1 packages / onUpdate: 0 = com.judangbang.pillbadeum / restoreFinished: 0 / done
- RR-71 복원 직후 실행 전: 활성 판정 1, device_id 29d879, restored_from -, pending -, 표식 - (표식이 없으면 시스템이 복원 전에 앱 데이터를 지운 것: R-1 표의 '표식 없음' 줄로 감)
- bmgr backupnow (is_encrypted=false): Backup finished with result: Success
- bmgr backupnow (is_device_transfer=true): Package com.judangbang.pillbadeum with result: Success / Backup finished with result: Success
