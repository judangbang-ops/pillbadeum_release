-- 필받음 2차 첫 릴리즈 데이터베이스 (CORTHEX 05 ERD)
--
-- 원본: Room이 내보낸 스키마 shared/schemas/com.judangbang.pillbadeum.data.DidITakeDatabase/3.json
--       (엔터티는 shared/src/commonMain/kotlin/com/judangbang/pillbadeum/data/의 Entities.kt와 HealthCardEntities.kt).
-- 이 파일은 그 JSON의 CREATE 문을 한 글자도 바꾸지 않고 옮기고 표마다 TBL 번호와 주석을 붙인 것.
-- .github/scripts/check_docs.py가 두 쪽이 같은지 봄. 스키마를 바꾸면 엔터티 → Room 내보내기 → 이 파일 순서로 고침.
-- SQLite. 시각은 모두 UTC 밀리초 INTEGER, id는 UUID TEXT. created_at·updated_at·version·device_id는 행마다 둠(2차 동기화 대비).
-- 이주: Migrations.kt의 MIGRATION_1_2와 MIGRATION_2_3. 시험은 shared/src/jvmTest/.../data/MigrationTest.kt.
-- 새 설치는 Room이 스키마 3을 바로 만듦. 기존 대상자의 카드 시각을 채우는 규칙은 docs/05-erd/README.md 8.5절.
--
-- without a table: TERM-001 (계정 없음), TERM-006 (창: 주기·창 설정으로 계산), TERM-007 (dose_record.period_key),
--   TERM-009·TERM-010 (dose_record.kind 값), TERM-011 (미완료: 활성 판정이 없는 창, 계산), TERM-013 (늦은 입력: taken_at),
--   TERM-019 (완전 삭제: 동작), TERM-020 (dose_record.source), TERM-022 (알림 칸: 계산),
--   TERM-025 (위젯: Android 위젯과 Glance 상태 파일), TERM-026 (고른 대상자: 위젯마다 Glance 상태에 둘 것, 백업 제외, 아직 없음),
--   TERM-027 (기기 표식: noBackupFilesDir의 install_marker 파일), TERM-028 (복원: 사건), TERM-029 (복원 안내: setting restore_notice_*),
--   TERM-031 (빠짐: 계산),
--   TERM-041 (카드 요약: 읽기 모델, 계산), TERM-042 (응급 대비 문장: 계산, 복사 시각은 health_card),
--   TERM-043 (내보내기 파일: 앱 캐시 파일, 백업 밖), TERM-044 (의견 보내기: 메일 앱에 맡김)


-- TBL-001 person (CTX-002) ← SPEC-001, SPEC-004, SPEC-005, SPEC-026, SPEC-027, SPEC-044 | terms: TERM-002, TERM-003, TERM-018
--   대상자 한 명. 처음 실행에 '나'가 생김(SPEC-001).
--   rule (CTX-002, 코드): 보관되지 않은 대상자는 늘 한 명 이상. 마지막 한 명은 보관하지 않음(REQ-093, ManagePeople.archive).
--   rule (코드): archived_at이 있으면 보관(TERM-018). 그 대상자의 항목은 위젯·알림·오늘 목록에서 빠지고 판정은 남음.
CREATE TABLE IF NOT EXISTS `person` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`));

-- TBL-002 intake (CTX-002) ← SPEC-003, SPEC-006, SPEC-015, SPEC-023, SPEC-024, SPEC-025 | terms: TERM-004, TERM-018
--   복용 항목 하나. 대상자 한 명에 속함. widget_alias는 위젯에 보일 짧은 이름(선택), slot_label은 매일 항목의 시간대
--   MORNING·NOON·EVENING·BEDTIME(보이기·정렬과 알림 기본 시작 시각에만 씀, 창을 나누지 않음).
--   rule (코드): 보관된 항목만 완전 삭제할 수 있음(TERM-019, DeleteIntake). 삭제는 자식 표부터: clock_flag → dose_record → reminder_snooze → reminder_rule → intake_medication → (2차) 그 약 줄의 medication → schedule_version → intake.
--   FK는 모두 ON DELETE NO ACTION이고 Room이 foreign_keys를 켬(DoseRecordDaoTest.foreignKeys_*): 판정이 남은 항목은 실수로 지워지지 않음.
CREATE TABLE IF NOT EXISTS `intake` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `widget_alias` TEXT, `slot_label` TEXT, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_intake_person` ON `intake` (`person_id`);

-- TBL-003 schedule_version (CTX-002) ← SPEC-003, SPEC-022, SPEC-023, SPEC-044 | terms: TERM-005
--   주기의 한 버전. 주기를 바꾸면 지금 버전을 닫고(effective_to) 새 버전을 엶. 지난 기록은 그날 유효했던 버전대로 그림(SPEC-022).
--   type: DAILY | WEEKLY. params_json: 주 1회는 기준 요일 {"anchorWeekday":"MON"} 꼴(ScheduleParams.kt).
--   late_policy: ASK | MOVE | KEEP, 주 1회를 늦게 먹었을 때 기준 요일을 옮길지(확장용, 1차 화면에는 없음).
--   rule (코드): 항목마다 열린 버전(effective_to IS NULL)은 하나. ManageIntakes.update가 닫고 같은 거래에서 엶.
--   첫 버전은 등록한 창의 시작에서 시작(보충 P-4, MIGRATION_1_2).
--   대상자를 되살리면 그 대상자 항목의 열린 버전을 보관한 때에 닫고 되살린 때부터 새로 엶(보충 P-9, SPEC-044). 보관한 동안은 버전이 없어 빠짐이 아님.
CREATE TABLE IF NOT EXISTS `schedule_version` (`id` TEXT NOT NULL, `intake_id` TEXT NOT NULL, `type` TEXT NOT NULL, `params_json` TEXT NOT NULL, `late_policy` TEXT NOT NULL, `effective_from` INTEGER NOT NULL, `effective_to` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_schedule_intake_from` ON `schedule_version` (`intake_id`, `effective_from`);

-- TBL-004 dose_record (CTX-001) ← SPEC-007, SPEC-008, SPEC-011, SPEC-016, SPEC-017, SPEC-018, SPEC-019, SPEC-022, SPEC-043 | terms: TERM-008, TERM-012, TERM-030
--   복용 판정 한 행. 지우지 않고 무효로 남김(TERM-012): 취소·고치기는 이 행에 voided_at·void_reason을 쓰고 active를 NULL로 바꾼 뒤 새 행을 씀.
--   kind: TAKEN(먹음, TERM-009) | SKIPPED(건너뜀, TERM-010). source: WIDGET | APP | NOTIFICATION(기록한 곳, TERM-020).
--   period_key: 기간키(TERM-007), 기준 시간대의 창 시작 날짜 YYYY-MM-DD. taken_at은 먹은 시각, recorded_at은 쓴 시각(늦은 입력 TERM-013이면 둘이 다름).
--   rule (CTX-001 핵심 규칙, DB가 막음): 활성 판정은 창마다 하나 = ux_dose_active UNIQUE(intake_id, period_key, active). active는 1 또는 NULL이고 SQLite UNIQUE는 NULL끼리 겹쳐도 됨 → 무효 행은 몇 개든 남음(SPEC-043, RR-1·RR-2·RR-6).
--   rule (코드): 판정 쓰기는 공통 기록 함수(RecordDose·VoidDose·ChangeJudgment)만 함(SPEC-043).
CREATE TABLE IF NOT EXISTS `dose_record` (`id` TEXT NOT NULL, `intake_id` TEXT NOT NULL, `schedule_version_id` TEXT NOT NULL, `period_key` TEXT NOT NULL, `kind` TEXT NOT NULL, `taken_at` INTEGER NOT NULL, `zone_id` TEXT NOT NULL, `recorded_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `active` INTEGER, `voided_at` INTEGER, `void_reason` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`schedule_version_id`) REFERENCES `schedule_version`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE UNIQUE INDEX IF NOT EXISTS `ux_dose_active` ON `dose_record` (`intake_id`, `period_key`, `active`);
CREATE INDEX IF NOT EXISTS `ix_dose_taken` ON `dose_record` (`intake_id`, `taken_at`);
CREATE INDEX IF NOT EXISTS `ix_dose_schedule_version` ON `dose_record` (`schedule_version_id`);

-- TBL-005 clock_flag (CTX-001) ← SPEC-020 | terms: TERM-017
--   시계 어긋남 표식. 복용 시각(taken_at)이 확인할 때의 시계보다 5분 넘게 뒤인 판정에 붙음(보충 C-1, ClockFlags.MARGIN).
--   판정 행을 고치지 않으려고 따로 둠. resolved_at은 챙기는 사람이 그대로 두기로 한 때.
--   rule (DB가 막음): 판정 하나에 표식 하나(PRIMARY KEY dose_id).
CREATE TABLE IF NOT EXISTS `clock_flag` (`dose_id` TEXT NOT NULL, `flagged_at` INTEGER NOT NULL, `resolved_at` INTEGER, PRIMARY KEY(`dose_id`), FOREIGN KEY(`dose_id`) REFERENCES `dose_record`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );

-- TBL-006 reminder_rule (CTX-003) ← SPEC-010, SPEC-028, SPEC-032 | terms: TERM-021, TERM-024
--   항목마다의 알림 켜기·끄기와 시작 시각(HH:MM). 간격·하루 최대·방해 금지는 setting(TBL-008)에 앱 전체 값으로 둠.
--   rule (DB가 막음): 항목 하나에 규칙 하나(PRIMARY KEY intake_id).
CREATE TABLE IF NOT EXISTS `reminder_rule` (`intake_id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `start_time` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`intake_id`), FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );

-- TBL-007 reminder_snooze (CTX-003) ← SPEC-012, SPEC-014 | terms: TERM-023
--   30분 뒤 다시 알리기. 그 창(period_key)에 판정이 생기거나 창이 바뀌면 다음 계산 때 지움(Reminders.kt, SPEC-014).
--   rule (DB가 막음): 항목 하나에 미룸 하나(PRIMARY KEY intake_id).
CREATE TABLE IF NOT EXISTS `reminder_snooze` (`intake_id` TEXT NOT NULL, `period_key` TEXT NOT NULL, `fire_at` INTEGER NOT NULL, PRIMARY KEY(`intake_id`), FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );

-- TBL-008 setting (CTX-001·CTX-003·CTX-005) ← SPEC-001, SPEC-002, SPEC-013, SPEC-028, SPEC-031, SPEC-032, SPEC-033, SPEC-034, SPEC-035, SPEC-038, SPEC-039, SPEC-040, SPEC-041 | terms: TERM-014, TERM-015, TERM-016, TERM-024
--   앱 전체 설정 키·값. 키 목록과 뜻은 docs/05-erd/README.md 4장과 Settings.kt.
--   rule (코드): day_start·base_zone은 바꾼 때부터 적용(TERM-014). 이미 쓴 판정의 period_key는 바꾸지 않음.
CREATE TABLE IF NOT EXISTS `setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`));

-- TBL-009 medication (CTX-002) ← SPEC-025, SPEC-048, SPEC-049 | terms: TERM-033
--   약 줄의 약 이름. 1차 앱은 읽지도 쓰지도 않음(빈 표, docs/05-erd/README.md 5장 4). 2차부터 약 줄 하나에 행 하나
--   (같은 이름도 합치지 않음, REQ-099). product_code는 비움(식약처 목록은 다음 릴리즈). 2차 쓰임은 README 8장 5.
CREATE TABLE IF NOT EXISTS `medication` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `product_code` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`));

-- TBL-010 intake_medication (CTX-002) ← SPEC-025, SPEC-048, SPEC-049 | terms: TERM-033, TERM-034
--   복용 항목에 든 약 줄. 1차 앱은 완전 삭제 때 지우기만 함(빈 표). 2차부터 qty_per_take는 1회 투약량(TERM-034) 글 그대로,
--   effective_from은 만든 때, effective_to는 비움(약을 빼면 이 행과 그 medication 행을 지움). 줄 순서는 created_at.
CREATE TABLE IF NOT EXISTS `intake_medication` (`id` TEXT NOT NULL, `intake_id` TEXT NOT NULL, `medication_id` TEXT NOT NULL, `qty_per_take` TEXT, `effective_from` INTEGER NOT NULL, `effective_to` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`medication_id`) REFERENCES `medication`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_intake_medication_intake` ON `intake_medication` (`intake_id`);
CREATE INDEX IF NOT EXISTS `ix_intake_medication_medication` ON `intake_medication` (`medication_id`);

-- TBL-011 dose_version (2차) ← 없음 | terms: none
--   2차: 약 용량의 버전. 1차 앱은 읽지도 쓰지도 않음(빈 표).
CREATE TABLE IF NOT EXISTS `dose_version` (`id` TEXT NOT NULL, `medication_id` TEXT NOT NULL, `dose_text` TEXT NOT NULL, `effective_from` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`medication_id`) REFERENCES `medication`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_dose_version_medication_from` ON `dose_version` (`medication_id`, `effective_from`);

-- TBL-012 health_card (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-048, SPEC-049, SPEC-051, SPEC-054, SPEC-055 | terms: TERM-032
--   대상자 한 명의 건강 카드에 딸린 시각. 칸은 종류마다 아래 표 다섯에 둠.
--   edited_at: 카드 고친 시각(SPEC-049). 마지막으로 고친 날짜(REQ-103)와 낡은 문장 안내(REQ-110)가 읽음.
--   emergency_copied_at: 응급 대비 문장을 마지막으로 복사한 때(SPEC-051). stale_notice_closed_at: 낡은 문장 안내를 닫은 때(SPEC-055).
--   rule (CTX-006, DB가 막음): 대상자 하나에 카드 하나 = PRIMARY KEY person_id.
--   rule (코드): 행은 edited_at이나 복사 시각을 처음 쓸 때 만듦(edited_at은 그때). 카드를 모두 지워도(SPEC-047) 이 행은 남김. 행이 없으면 빈 카드.
CREATE TABLE IF NOT EXISTS `health_card` (`person_id` TEXT NOT NULL, `edited_at` INTEGER NOT NULL, `emergency_copied_at` INTEGER, `stale_notice_closed_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`person_id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );

-- TBL-013 condition (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-049 | terms: TERM-035
--   질환 칸 하나. name은 꼭 적고 나머지는 비워도 됨(SPEC-045). diagnosed_on은 아는 만큼의 날짜(8.4 1).
--   code_system·code: 질병 분류 코드 자리, 2차 첫 릴리즈는 늘 비움. source: 적은 곳, 2차 첫 릴리즈는 늘 USER.
CREATE TABLE IF NOT EXISTS `condition` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `diagnosed_on` TEXT, `note` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_condition_person` ON `condition` (`person_id`);

-- TBL-014 procedure_history (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-049 | terms: TERM-036
--   수술·시술 칸 하나. name은 꼭 적음. performed_on은 아는 만큼의 날짜, place는 받은 의료기관. code_system·code·source는 TBL-013과 같음.
CREATE TABLE IF NOT EXISTS `procedure_history` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `performed_on` TEXT, `place` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_procedure_history_person` ON `procedure_history` (`person_id`);

-- TBL-015 allergy (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-049 | terms: TERM-037
--   알레르기 칸 하나. substance(원인)는 꼭 적고 reaction(반응)은 비워도 됨. code_system·code·source는 TBL-013과 같음.
CREATE TABLE IF NOT EXISTS `allergy` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `substance` TEXT NOT NULL, `reaction` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_allergy_person` ON `allergy` (`person_id`);

-- TBL-016 implanted_device (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-049 | terms: TERM-038
--   이식형 의료기기 칸 하나. name은 꼭 적음. implanted_on은 넣은 때(아는 만큼의 날짜). code_system·code·source는 TBL-013과 같음.
CREATE TABLE IF NOT EXISTS `implanted_device` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `implanted_on` TEXT, `note` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_implanted_device_person` ON `implanted_device` (`person_id`);

-- TBL-017 care_contact (CTX-006) ← SPEC-045, SPEC-046, SPEC-047, SPEC-049 | terms: TERM-039, TERM-040
--   의료기관·담당 의사 칸(kind CARE_PROVIDER) 또는 보호자 연락처 칸(kind GUARDIAN) 하나. name은 기관 이름이나 보호자 이름이고 꼭 적음.
--   CARE_PROVIDER는 department(진료과)·doctor(담당 의사)·phone, GUARDIAN은 relation(관계)·phone을 씀. 쓰지 않는 열은 비움.
--   rule (코드): GUARDIAN은 phone도 꼭 적음(SPEC-045). phone은 적은 글 그대로이고 형식을 따지지 않음.
CREATE TABLE IF NOT EXISTS `care_contact` (`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `department` TEXT, `doctor` TEXT, `relation` TEXT, `phone` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION );
CREATE INDEX IF NOT EXISTS `ix_care_contact_person_kind` ON `care_contact` (`person_id`, `kind`);
