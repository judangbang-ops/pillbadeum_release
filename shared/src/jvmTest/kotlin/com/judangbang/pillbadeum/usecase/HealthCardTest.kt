package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.judangbang.pillbadeum.data.CareContactKind
import com.judangbang.pillbadeum.data.IntakeMedicationEntity
import com.judangbang.pillbadeum.data.MedicationEntity
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.restore.CheckInstall
import com.judangbang.pillbadeum.restore.FakeInstallMarker
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

class HealthCardTest {
    private val fx = DbFixture(start = seoul("2026-11-20T08:00"))
    private val db = fx.db
    private val people = ManagePeople(db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val save = SaveCardEntry(db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val load = LoadHealthCard(db)
    private val delete = DeleteCardEntries(db, fx.clock, TEST_DEVICE_ID)
    private val buildText = BuildEmergencyText()
    private val notice = HealthCardNoticeActions(db, fx.clock, TEST_DEVICE_ID)

    @AfterTest
    fun close() = fx.close()

    @Test
    fun spec045_preservesAllergyAndYearOnlyProcedure() = runTest {
        val person = person()
        assertTrue(card(person).isEmpty)
        assertNull(db.healthCardDao().findByPerson(person))
        val allergy = CardEntryFields.Allergy("페니실린 계열")
        val procedure = CardEntryFields.Procedure("쓸개 수술", "2019")
        add(person, allergy)
        add(person, procedure)

        assertEquals(listOf(allergy, procedure), card(person).entries.map { it.fields })
        assertEquals("2019", db.procedureHistoryDao().listByPerson(person).single().performedOn)
        assertEquals("2019년", Labels.cardDate("2019"))
        assertEquals(now(), times(person).editedAt)
    }

    @Test
    fun spec045_requiresGuardianPhoneAndPreservesDraft() = runTest {
        val person = person()
        val draft = CardEntryFields.Guardian("가상 딸", "", "가상 관계")
        val error = assertIs<SaveCardEntryResult.Invalid>(save.add(person, save.newEntryId(), draft))
        assertEquals(CardField.PHONE, error.field)
        assertEquals("전화번호를 입력해 주세요", Labels.cardEntryError(draft.kind, error.field, error.problem))
        assertEquals("가상 딸", draft.name)
        assertEquals("가상 관계", draft.relation)
        assertTrue(card(person).isEmpty)
        assertNull(db.healthCardDao().findByPerson(person))
    }

    @Test
    fun spec045_rejects2030() = runTest {
        val person = person()
        val draft = CardEntryFields.Procedure("가상 시술", "2030")
        assertEquals(
            SaveCardEntryResult.Invalid(CardField.DATE, CardFieldProblem.FUTURE_DATE),
            save.add(person, save.newEntryId(), draft),
        )
        assertTrue(card(person).isEmpty)
    }

    @Test
    fun spec045_checksDatePrecisionCalendarAndBaseZone() = runTest {
        val person = person()
        db.settingDao().upsert(SettingEntity(SettingKeys.BASE_ZONE, "UTC"))
        fx.at("2026-11-20T08:00") // Still November 19 in UTC.
        val id = save.newEntryId()
        suspend fun writeDate(date: String) = save.add(person, id, CardEntryFields.Condition("가상 질환", date))
        assertEquals(SaveCardEntryResult.Invalid(CardField.DATE, CardFieldProblem.BEFORE_1900), writeDate("1899-12-31"))
        for (date in listOf("2024-2", "2024-13", "2023-02-29", "2024-02-00")) {
            assertEquals(SaveCardEntryResult.Invalid(CardField.DATE, CardFieldProblem.INVALID_DATE), writeDate(date))
        }
        for (date in listOf("2026-12", "2026-11-20")) {
            assertEquals(SaveCardEntryResult.Invalid(CardField.DATE, CardFieldProblem.FUTURE_DATE), writeDate(date))
        }
        val validDates = listOf("1900", "2024-02-29", "2026-11", "2026-11-19")
        for (date in validDates) add(person, CardEntryFields.Condition("가상 질환", date))
        assertEquals(validDates, db.conditionDao().listByPerson(person).map { it.diagnosedOn })
        assertEquals("2026년 11월", Labels.cardDate("2026-11"))
        assertEquals("2024년 2월 29일", Labels.cardDate("2024-02-29"))
        assertEquals(LocalDate(2026, 11, 19), card(person).lastEditedDate)
    }

    @Test
    fun spec045_editsAllSixKindsInPlaceAndRetainsText() = runTest {
        val person = person()
        val original = listOf(
            CardEntryFields.Condition("가상 질환", "2020", "가상 메모"),
            CardEntryFields.Procedure("가상 시술", "2021-11", "가상 기관"),
            CardEntryFields.Allergy("가상 원인", "가상 반응"),
            CardEntryFields.ImplantedDevice("가상 기기", "2022-02-22", "가상 메모"),
            CardEntryFields.CareProvider("가상 의료기관", "가상 진료과", "가상 의사", "010-0000-0000"),
            CardEntryFields.Guardian("가상 딸", "010-0000-0000", "가상 관계"),
        )
        val ids = original.map { add(person, it) }
        val firstCreated = now()
        fx.at("2026-11-23T08:00")
        val changed = listOf(
            CardEntryFields.Condition("  가상 질환 둘  ", "2024-01", " 가상 새 메모  "),
            CardEntryFields.Procedure("가상 시술 둘", "2024-02-29", " 가상 새 기관"),
            CardEntryFields.Allergy("가상 원인 둘", "  가상 새 반응"),
            CardEntryFields.ImplantedDevice("가상 기기 둘", "2023", "가상 새 메모  "),
            CardEntryFields.CareProvider("가상 의료기관 둘", "가상 새 진료과", "가상 새 의사", " 010-0000-0000"),
            CardEntryFields.Guardian("가상 딸 둘", "010-0000-0000 ", "가상 새 관계"),
        )
        for ((id, fields) in ids.zip(changed)) {
            assertEquals(SaveCardEntryResult.Saved(id), save.edit(person, id, fields))
        }
        assertEquals(ids.zip(changed).toMap(), card(person).entries.associate { it.id to it.fields })
        assertEquals(firstCreated, db.conditionDao().listByPerson(person).single().createdAt)
        assertEquals(2, db.conditionDao().listByPerson(person).single().version)
        assertEquals(now(), times(person).editedAt)
    }

    @Test
    fun spec045_enforcesFieldAndPerKindLimitsAndDeduplicatesSave() = runTest {
        val person = person()
        val id = save.newEntryId()
        val draft = CardEntryFields.Guardian("가".repeat(50), "0".repeat(20), "가".repeat(50))
        val submits = List(2) { async { save.add(person, id, draft) } }
        assertEquals(listOf(SaveCardEntryResult.Saved(id), SaveCardEntryResult.Saved(id)), submits.map { it.await() })
        assertEquals(1, times(person).version)
        assertEquals(draft, card(person).entries.single().fields)
        val mistakes = listOf(
            CardEntryFields.Allergy("  ") to SaveCardEntryResult.Invalid(CardField.SUBSTANCE, CardFieldProblem.REQUIRED),
            CardEntryFields.Allergy("가".repeat(51)) to SaveCardEntryResult.Invalid(CardField.SUBSTANCE, CardFieldProblem.TOO_LONG),
            CardEntryFields.Allergy("가상", "가".repeat(201)) to SaveCardEntryResult.Invalid(CardField.REACTION, CardFieldProblem.TOO_LONG),
            draft.copy(phone = "0".repeat(21)) to SaveCardEntryResult.Invalid(CardField.PHONE, CardFieldProblem.TOO_LONG),
        )
        for ((fields, error) in mistakes) assertEquals(error, save.add(person, save.newEntryId(), fields))
        repeat(19) { add(person, CardEntryFields.Guardian("가상 보호자 $it", "010-0000-0000")) }
        assertEquals(SaveCardEntryResult.LimitReached, save.add(person, save.newEntryId(), draft))
        add(person, CardEntryFields.CareProvider("가상 의료기관"))
        assertEquals(20, db.careContactDao().listByPerson(person).count { it.kind == CareContactKind.GUARDIAN })
        assertEquals(21, card(person).entries.size)
    }

    @Test
    fun spec045_doesNotRecreateDeletedEntryOrEditOtherPersonsEntry() = runTest {
        val person = person()
        val other = person("가상 대상자")
        val fields = CardEntryFields.Allergy("가상 원인")
        val id = add(person, fields)
        assertEquals(SaveCardEntryResult.Gone, save.edit(other, id, fields.copy(reaction = "가상 반응")))
        assertEquals(1, delete.one(person, fields.kind, id, confirmed = true))
        assertEquals(SaveCardEntryResult.Gone, save.edit(person, id, fields))
        assertTrue(card(other).isEmpty)
        assertTrue(card(person).isEmpty)
    }

    @Test
    fun spec046_confirmDeletesOnlyThatRowAndRepeatIsNoOp() = runTest {
        val person = person()
        val allergy = add(person, CardEntryFields.Allergy("가상 원인"))
        val procedure = add(person, CardEntryFields.Procedure("가상 시술"))
        fx.at("2026-11-23T08:00")
        assertEquals(1, delete.one(person, CardEntryKind.ALLERGY, allergy, confirmed = true))
        assertTrue(db.allergyDao().listByPerson(person).isEmpty())
        assertEquals(procedure, db.procedureHistoryDao().listByPerson(person).single().id)
        val editedAt = now()
        assertEquals(editedAt, times(person).editedAt)
        fx.at("2026-11-24T08:00")
        assertEquals(0, delete.one(person, CardEntryKind.ALLERGY, allergy, confirmed = true))
        assertEquals(editedAt, times(person).editedAt)
    }

    @Test
    fun spec046_cancelLeavesBothRowsAndTimestamp() = runTest {
        val person = person()
        val allergy = add(person, CardEntryFields.Allergy("가상 원인"))
        val procedure = add(person, CardEntryFields.Procedure("가상 시술"))
        val before = times(person)
        fx.at("2026-11-23T08:00")
        assertEquals(0, delete.one(person, CardEntryKind.ALLERGY, allergy, confirmed = false))
        assertEquals(setOf(allergy, procedure), card(person).entries.map { it.id }.toSet())
        assertEquals(before, times(person))
    }

    @Test
    fun spec047_deleteAllKeepsBothPeopleIntakesMedicinesAndTenJudgments() = runTest {
        val me = person("나")
        val person = person()
        add(me, CardEntryFields.Allergy("가상 원인 하나"))
        add(person, CardEntryFields.Allergy("가상 원인 둘"))
        add(person, CardEntryFields.Procedure("가상 시술"))
        add(person, CardEntryFields.Guardian("가상 보호자", "010-0000-0000"))
        val intakes = listOf(intake(person, "아침약", SlotLabel.MORNING), intake(person, "저녁약", SlotLabel.EVENING))
        for (id in intakes) medication(id)
        for (day in 20..24) {
            fx.at("2026-11-${day}T08:00")
            for (id in intakes) assertIs<RecordResult.Recorded>(fx.record(id, renderedKey = "2026-11-$day"))
        }
        val before = db.personDao().listAll()
        val intakeRows = db.intakeDao().listAll()
        val medRows = intakes.flatMap { db.intakeMedicationDao().listByIntake(it) }
        val medicines = medRows.map { db.medicationDao().findById(it.medicationId) }
        val judgments = intakes.flatMap { fx.history(it) }
        assertEquals(10, judgments.size)

        assertEquals(0, delete.all(person, confirmed = false))
        assertEquals(3, card(person).entries.size)
        assertEquals(3, delete.all(person, confirmed = true))
        assertEquals(0, delete.all(person, confirmed = true))
        assertTrue(card(person).entries.isEmpty())
        assertEquals(2, card(person).medications.size)
        assertEquals(now(), times(person).editedAt)
        assertEquals(1, card(me).entries.size)
        assertEquals(before, db.personDao().listAll())
        assertEquals(intakeRows, db.intakeDao().listAll())
        assertEquals(medRows, intakes.flatMap { db.intakeMedicationDao().listByIntake(it) })
        assertEquals(medicines, medRows.map { db.medicationDao().findById(it.medicationId) })
        assertEquals(judgments, intakes.flatMap { fx.history(it) })
    }

    @Test
    fun spec047_deleteFailureRollsBackAllTablesAndTimestamp() = runTest {
        val person = person()
        add(person, CardEntryFields.Condition("가상 질환"))
        add(person, CardEntryFields.Allergy("가상 원인"))
        val before = times(person)
        sql("CREATE TRIGGER prevent_allergy_delete BEFORE DELETE ON allergy BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        fx.at("2026-11-23T08:00")
        assertFailsWith<SQLiteException> { delete.all(person, confirmed = true) }
        assertEquals(2, card(person).entries.size)
        assertEquals(before, times(person))
    }

    @Test
    fun spec049_ordersMedicinesKeepsDuplicateNamesAndFallsBackToIntakeName() = runTest {
        val person = person()
        medication(intake(person, "저녁약", SlotLabel.EVENING))
        medication(intake(person, "아침약", SlotLabel.MORNING))
        val archived = intake(person, "감기약")
        medication(archived, "가상 보관약")
        assertTrue(ArchiveIntake(db, fx.clock)(archived))
        intake(person, "비타민")
        medication(intake(person("가상 대상자"), "다른 약"), "가상 다른 약")
        assertEquals(
            listOf("가상정 10mg 1정 · 매일 아침", "가상정 10mg 1정 · 매일 저녁", "비타민 · 매일"),
            card(person).medications.map { it.text },
        )
    }

    @Test
    fun spec049_readsLatestOpenScheduleAndCreationOrderWithinSlot() = runTest {
        val person = person()
        intake(person, "가상 첫째", SlotLabel.MORNING)
        intake(person, "가상 둘째", SlotLabel.MORNING)
        val weekly = intake(person, "가상 주간")
        val update = SaveIntake(db, fx.clock, fx.ids, TEST_DEVICE_ID)
        assertIs<SaveIntakeResult.Saved>(
            update.update(weekly, IntakeDraft(person, "가상 주간", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))),
        )
        assertEquals(
            listOf("가상 첫째 · 매일 아침", "가상 둘째 · 매일 아침", "가상 주간 · 매주 월"),
            card(person).medications.map { it.text },
        )
    }

    @Test
    fun spec049_medicationEditTransactionUpdatesDateThroughSharedWriter() = runTest {
        val person = person()
        val evening = intake(person, "저녁약", SlotLabel.EVENING)
        val row = medication(evening)
        add(person, CardEntryFields.Allergy("가상 원인"))
        assertEquals(LocalDate(2026, 11, 20), card(person).lastEditedDate)
        fx.at("2026-11-23T08:00")
        db.useWriterConnection { writer ->
            writer.immediateTransaction {
                db.intakeMedicationDao().upsert(row.copy(qtyPerTake = "반 알", updatedAt = now()))
                db.markHealthCardEdited(setOf(person), now(), TEST_DEVICE_ID)
            }
        }
        assertEquals(LocalDate(2026, 11, 23), card(person).lastEditedDate)
        assertEquals("가상정 10mg 반 알 · 매일 저녁", card(person).medications.single().text)
    }

    @Test
    fun spec049_nameChangeWritesEditTimeButColorChangeDoesNot() = runTest {
        val person = person()
        add(person, CardEntryFields.Allergy("가상 원인"))
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.ALL))
        val before = times(person)
        fx.at("2026-11-23T08:00")
        assertEquals(PersonChange.Saved, people.update(person, "가상 엄마", PersonColors.PALETTE.last()))
        assertEquals(before, times(person))
        assertEquals(PersonChange.Saved, people.update(person, "가상 이름", PersonColors.PALETTE.last()))
        assertEquals(now(), times(person).editedAt)
        assertEquals(StaleCardNotice.CHANGED, card(person).staleNotice)
        assertEquals("가상 이름", card(person).person.name)
    }

    @Test
    fun spec051_formatsAllSectionsInOrderWithFooterAndCharacterCount() = runTest {
        val person = person()
        add(person, CardEntryFields.Condition("가상 질환"))
        add(person, CardEntryFields.Procedure("가상 시술"))
        add(person, CardEntryFields.CareProvider("가상 의료기관"))
        add(person, CardEntryFields.ImplantedDevice("가상 기기"))
        add(person, CardEntryFields.Allergy("페니실린 계열", "가상 반응"))
        add(person, CardEntryFields.Guardian("가상 딸", "010-0000-0000"))
        medication(intake(person, "아침약", SlotLabel.MORNING))
        val emergency = emergency(person)
        val expected = "가상 엄마. 알레르기: 페니실린 계열(가상 반응). 지금 먹는 약: 가상정 10mg 1정 매일 아침. " +
            "질환: 가상 질환. 이식형 의료기기: 가상 기기. 보호자: 가상 딸 010-0000-0000. " +
            "의료기관: 가상 의료기관. 필받음 건강 카드, 2026-11-20 고침."
        assertEquals(expected, emergency.text)
        assertEquals(expected.length, emergency.characterCount)
        for (line in emergency.lines) {
            assertTrue(emergency.text.contains("${Labels.emergencySection(line.section, inSentence = true)}: ${line.text}."))
        }
        assertEquals(emergency, emergency(person))
    }

    @Test
    fun spec051_returnsOnlyThreeCopyLinesAndUsesBareAllergyBody() = runTest {
        val person = person()
        val intake = intake(person, "아침약", SlotLabel.MORNING)
        medication(intake)
        add(person, CardEntryFields.Allergy("페니실린 계열"))
        add(person, CardEntryFields.Guardian("가상 딸", "010-0000-0000"))
        val emergency = emergency(person)
        assertEquals(
            listOf(
                EmergencyLine(EmergencySection.ALLERGY, "페니실린 계열"),
                EmergencyLine(EmergencySection.MEDICATION, "가상정 10mg 1정 매일 아침"),
                EmergencyLine(EmergencySection.GUARDIAN, "가상 딸 010-0000-0000"),
            ),
            emergency.lines,
        )
        val positions = listOf("가상 엄마", "페니실린 계열", "가상정 10mg", "가상 딸").map { emergency.text.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun spec051_doesNotTruncateLongTextOrBuildAnEmptySummary() = runTest {
        val person = person()
        assertNull(buildText(card(person)))
        val reaction = "가".repeat(200)
        repeat(20) { add(person, CardEntryFields.Allergy("가상 원인 $it", reaction)) }
        val emergency = emergency(person)
        assertTrue(emergency.characterCount > 4000)
        assertTrue(emergency.lines.single().text.endsWith("가상 원인 19($reaction)"))
        assertTrue(emergency.text.endsWith("필받음 건강 카드, 2026-11-20 고침."))
    }

    @Test
    fun spec054_archivedPersonIsExcludedFromPickerButCardSurvivesAndCanBeEdited() = runTest {
        val me = person("나")
        fx.at("2026-11-20T08:01")
        val person = person()
        val allergy = add(person, CardEntryFields.Allergy("가상 원인"))
        medication(intake(person, "아침약", SlotLabel.MORNING))
        assertEquals(listOf(me, person), assertIs<HealthCardSelection.Choose>(load.selection()).people.map { it.id })
        assertEquals(PersonChange.Saved, people.archive(person))
        assertEquals(HealthCardSelection.Open(me), load.selection())
        assertNull(load.fromHistory(person))
        assertTrue(card(person).archived)
        assertEquals(allergy, card(person).entries.single().id)
        assertEquals(1, card(person).medications.size)
        assertIs<SaveCardEntryResult.Saved>(save.edit(person, allergy, CardEntryFields.Allergy("가상 새 원인")))
        assertEquals(PersonChange.Saved, people.restore(person))
        assertEquals(listOf(me, person), assertIs<HealthCardSelection.Choose>(load.selection()).people.map { it.id })
        assertFalse(assertNotNull(load.fromHistory(person)).archived)
    }

    @Test
    fun spec055_allCopyClearsNoticeAfterCardChange() = runTest {
        val person = copiedCardThatChanged()
        assertEquals(StaleCardNotice.CHANGED, card(person).staleNotice)
        val editedAt = times(person).editedAt
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.ALL))
        assertEquals(now(), times(person).emergencyCopiedAt)
        assertEquals(editedAt, times(person).editedAt)
        assertNull(card(person).staleNotice)
    }

    @Test
    fun spec055_lineCopyDuringNoticeLeavesTimestampAndNoticeIntact() = runTest {
        val person = copiedCardThatChanged()
        val before = times(person)
        assertEquals(EmergencyCopyRecord.NOTICE_REMAINS, notice.recordCopy(person, EmergencyCopy.LINE))
        assertEquals(before, times(person))
        assertEquals(StaleCardNotice.CHANGED, card(person).staleNotice)
        assertEquals("다 넣었으면 [다 넣었어요]를 눌러 주세요", Labels.CARD_COPY_STALE)
    }

    @Test
    fun spec055_lineCopyWithoutNoticeWritesTimestampAndLaterEditShowsNotice() = runTest {
        val person = person()
        add(person, CardEntryFields.Allergy("페니실린 계열"))
        fx.at("2026-11-21T08:00")
        assertNull(card(person).staleNotice)
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.LINE))
        assertEquals(now(), times(person).emergencyCopiedAt)
        assertNull(card(person).staleNotice)
        fx.at("2026-11-23T08:00")
        add(person, CardEntryFields.Allergy("가상 원인"))
        assertEquals(StaleCardNotice.CHANGED, card(person).staleNotice)
    }

    @Test
    fun spec055_noCopyMeansNoNoticeAfterEditOrRestore() = runTest {
        val person = person()
        add(person, CardEntryFields.Allergy("가상 원인"))
        assertNull(card(person).staleNotice)
        fx.at("2026-11-23T08:00")
        restore()
        assertNull(card(person).staleNotice)
    }

    @Test
    fun spec055_acknowledgingDismissesAndNextEditShowsNoticeAgain() = runTest {
        val person = copiedCardThatChanged()
        assertTrue(notice.closeNotice(person))
        assertEquals(now(), times(person).staleNoticeClosedAt)
        assertNull(card(person).staleNotice)
        assertFalse(notice.closeNotice(person))
        fx.at("2026-11-24T08:00")
        add(person, CardEntryFields.Allergy("가상 다음 원인"))
        assertEquals(StaleCardNotice.CHANGED, card(person).staleNotice)
    }

    @Test
    fun spec055_clearedSummaryShowsEmptyNoticeUntilClosed() = runTest {
        val person = copiedCardThatChanged()
        assertEquals(2, delete.all(person, confirmed = true))
        assertEquals(StaleCardNotice.EMPTY, card(person).staleNotice)
        assertEquals("카드 내용이 없어요. 휴대폰 긴급 정보에 넣은 문장도 지워 주세요", Labels.cardNotice(StaleCardNotice.EMPTY))
        assertNull(buildText(card(person)))
        assertTrue(notice.closeNotice(person))
        assertEquals(now(), times(person).staleNoticeClosedAt)
        assertNull(card(person).staleNotice)
    }

    @Test
    fun spec055_firstLaunchAfterRestoreShowsNoticeWithNoCardEdits() = runTest {
        val person = person()
        add(person, CardEntryFields.Allergy("가상 원인"))
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.ALL))
        val original = times(person)
        fx.at("2026-11-23T08:00")
        val check = restore()
        assertEquals(now().toString(), db.settingDao().get(SettingKeys.RESTORED_AT))
        assertEquals(original, times(person))
        assertEquals(StaleCardNotice.RESTORED, card(person).staleNotice)
        val restoredAt = now()
        fx.at("2026-11-24T08:00")
        assertIs<InstallState.Normal>(check().state)
        assertEquals(restoredAt.toString(), db.settingDao().get(SettingKeys.RESTORED_AT))
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.ALL))
        assertNull(card(person).staleNotice)
    }

    @Test
    fun spec055_copyOfIntakeOnlyCardRecordsHistoryWithoutChangingDate() = runTest {
        val person = person()
        intake(person, "가상 항목")
        assertNull(db.healthCardDao().findByPerson(person))
        fx.at("2026-11-23T08:00")
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.LINE))
        assertEquals(now(), times(person).emergencyCopiedAt)
        assertEquals(LocalDate(2026, 11, 20), card(person).lastEditedDate)
        assertNull(card(person).staleNotice)
    }

    private suspend fun person(name: String = "가상 엄마") = people.add(name, PersonColors.DEFAULT).id
    private suspend fun card(person: String) = assertNotNull(load(person))
    private suspend fun times(person: String) = assertNotNull(db.healthCardDao().findByPerson(person))
    private suspend fun emergency(person: String) = assertNotNull(buildText(card(person)))
    private fun now() = fx.clock.now().toEpochMilliseconds()

    private suspend fun add(person: String, fields: CardEntryFields): String =
        assertIs<SaveCardEntryResult.Saved>(save.add(person, save.newEntryId(), fields)).id

    private suspend fun intake(person: String, name: String, slot: SlotLabel? = null): String {
        val saveIntake = SaveIntake(db, fx.clock, fx.ids, TEST_DEVICE_ID)
        return assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, name, slotLabel = slot))).intakeId
    }

    private suspend fun medication(intake: String, name: String = "가상정 10mg"): IntakeMedicationEntity {
        val medicine = MedicationEntity(fx.ids.next(), name, createdAt = now(), updatedAt = now(), deviceId = TEST_DEVICE_ID)
        val row = IntakeMedicationEntity(
            fx.ids.next(), intake, medicine.id, qtyPerTake = "1정", effectiveFrom = now(),
            createdAt = now(), updatedAt = now(), deviceId = TEST_DEVICE_ID,
        )
        db.medicationDao().upsert(medicine)
        db.intakeMedicationDao().upsert(row)
        return row
    }

    private suspend fun copiedCardThatChanged(): String {
        val person = person()
        add(person, CardEntryFields.Allergy("페니실린 계열"))
        assertEquals(EmergencyCopyRecord.RECORDED, notice.recordCopy(person, EmergencyCopy.ALL))
        fx.at("2026-11-23T08:00")
        add(person, CardEntryFields.Allergy("가상 원인"))
        return person
    }

    private suspend fun restore(): CheckInstall {
        db.settingDao().upsert(SettingEntity(SettingKeys.DEVICE_ID, TEST_DEVICE_ID))
        val check = CheckInstall(db, fx.clock, fx.ids, FakeInstallMarker())
        assertIs<InstallState.Restored>(check().state)
        return check
    }

    private suspend fun sql(sql: String) {
        db.useWriterConnection { writer -> writer.usePrepared(sql) { it.step() } }
    }
}
