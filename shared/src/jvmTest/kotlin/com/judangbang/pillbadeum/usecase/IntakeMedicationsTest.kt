package com.judangbang.pillbadeum.usecase

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.status.ReminderLabels
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

class IntakeMedicationsTest {
    private val fx = DbFixture()
    private val db = fx.db
    private val people = ManagePeople(db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val save = SaveIntake(db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val archive = ArchiveIntake(db, fx.clock)
    private val board = LoadStatusBoard(db, fx.clock)
    private val examples = listOf(IntakeMedicationDraft("가상정 10mg", "1정"), IntakeMedicationDraft("보기정 500mg", "반 알"))

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun draft() =
        IntakeDraft(people.ensureDefault().single().id, "아침약", slotLabel = SlotLabel.MORNING)

    private suspend fun create(draft: IntakeDraft) = assertIs<SaveIntakeResult.Saved>(save.create(draft)).intakeId

    private suspend fun editedAt(personId: String) = assertNotNull(db.healthCardDao().findByPerson(personId)).editedAt

    @Test
    fun tc093_two_rows_leave_today_widget_and_reminder_text_unchanged() = runTest {
        val draft = draft().copy(reminder = ReminderChoice(true, LocalTime(8, 0)))
        val id = create(draft)
        val before = board()
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(medications = examples)))
        val rows = save.loadMedications(id)
        assertEquals(examples, rows.map { it.copy(id = null) })
        val links = db.intakeMedicationDao().listByIntake(id)
        assertEquals(rows.map { it.id }, links.map { it.id })
        assertEquals(2, links.map { it.medicationId }.toSet().size)
        for (row in links) {
            assertEquals(fx.clock.now().toEpochMilliseconds(), row.effectiveFrom)
            assertNull(row.effectiveTo)
            assertNull(db.medicationDao().findById(row.medicationId)?.productCode)
        }
        val after = board()
        assertEquals(Labels.appRow(before.items.single(), before, SEOUL), Labels.appRow(after.items.single(), after, SEOUL))
        assertEquals(before.items.single().window, after.items.single().window)
        assertEquals(WidgetModel.from(before, SEOUL), WidgetModel.from(after, SEOUL))
        assertEquals(ReminderLabels.title(before.items.single()), ReminderLabels.title(after.items.single()))
        assertEquals(ReminderLabels.text(before.items.single(), before.now, SEOUL), ReminderLabels.text(after.items.single(), after.now, SEOUL))
        assertEquals(before.items.single().reminder, after.items.single().reminder)

        val stamped = editedAt(draft.personId)
        fx.at("2026-11-11T08:05")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(medications = rows)))
        assertEquals(links, db.intakeMedicationDao().listByIntake(id))
        assertEquals(stamped, editedAt(draft.personId))
    }

    @Test
    fun tc093_blank_or_over_limit_input_does_not_write_item_or_medications() = runTest {
        val draft = draft()
        val invalid = listOf(
            SaveIntakeResult.Invalid.BLANK_MEDICATION to listOf(examples.first(), IntakeMedicationDraft(" ", "반 알")),
            SaveIntakeResult.Invalid.MEDICATION_LIMIT to List(11) { examples.first() },
            SaveIntakeResult.Invalid.MEDICATION_LIMIT to listOf(IntakeMedicationDraft("가".repeat(51))),
            SaveIntakeResult.Invalid.MEDICATION_LIMIT to listOf(IntakeMedicationDraft("예시정", "가".repeat(31))),
        )
        for ((reason, rows) in invalid) {
            assertEquals(SaveIntakeResult.Invalid(reason), save.create(draft.copy(medications = rows)))
        }
        assertTrue(db.intakeDao().listActive().isEmpty())
        assertNull(db.healthCardDao().findByPerson(draft.personId))

        val id = create(draft.copy(medications = examples))
        val before = db.intakeDao().findById(id)
        val rows = save.loadMedications(id)
        val invalidEdit = draft.copy(name = "저녁약", medications = listOf(rows[0], rows[1].copy(name = "")))
        assertEquals(SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.BLANK_MEDICATION), save.update(id, invalidEdit))
        assertEquals(before, db.intakeDao().findById(id))
        assertEquals(rows, save.loadMedications(id))
    }

    @Test
    fun tc093_removing_one_row_keeps_the_other_and_deletes_its_medication() = runTest {
        val draft = draft()
        val id = create(draft.copy(medications = examples))
        val rows = save.loadMedications(id)
        val links = db.intakeMedicationDao().listByIntake(id)
        val other = create(draft.copy(name = "저녁약", medications = listOf(examples[1])))
        val sameNameElsewhere = db.intakeMedicationDao().listByIntake(other).single()
        assertNotEquals(links[1].medicationId, sameNameElsewhere.medicationId)

        fx.at("2026-11-11T08:10")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(medications = rows.take(1))))
        assertEquals(rows.take(1), save.loadMedications(id))
        assertEquals(listOf(links[0]), db.intakeMedicationDao().listByIntake(id))
        assertNull(db.medicationDao().findById(links[1].medicationId))
        assertNotNull(db.medicationDao().findById(sameNameElsewhere.medicationId))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
    }

    @Test
    fun editing_a_row_keeps_its_ids_and_stamps_the_card_with_quantity_verbatim() = runTest {
        val draft = draft()
        val id = create(draft.copy(medications = examples))
        val rows = save.loadMedications(id)
        val old = db.intakeMedicationDao().listByIntake(id).first()
        val replacement = rows[0].copy(name = "예시정", quantity = "  반  알 ")
        val card = assertNotNull(db.healthCardDao().findByPerson(draft.personId))
        db.healthCardDao().upsert(card.copy(emergencyCopiedAt = card.editedAt, staleNoticeClosedAt = card.editedAt))

        fx.at("2026-11-11T08:10")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(medications = listOf(replacement, rows[1]))))
        assertEquals(listOf(replacement, rows[1]), save.loadMedications(id))
        val edited = db.intakeMedicationDao().listByIntake(id).first()
        assertEquals(old.medicationId, edited.medicationId)
        assertEquals(old.createdAt, edited.createdAt)
        assertEquals(old.effectiveFrom, edited.effectiveFrom)
        assertEquals(old.version + 1, edited.version)
        val stamped = assertNotNull(db.healthCardDao().findByPerson(draft.personId))
        assertEquals(fx.clock.now().toEpochMilliseconds(), stamped.editedAt)
        assertEquals(card.editedAt, stamped.emergencyCopiedAt)
        assertEquals(card.editedAt, stamped.staleNoticeClosedAt)
    }

    @Test
    fun a_database_failure_rolls_back_an_item_and_its_medication() = runTest {
        val draft = draft()
        db.useWriterConnection {
            it.executeSQL("CREATE TRIGGER reject_test_medication BEFORE INSERT ON intake_medication BEGIN SELECT RAISE(ABORT, 'test'); END")
        }
        assertFails { save.create(draft.copy(medications = examples, reminder = ReminderChoice(true, LocalTime(8, 0)))) }
        assertTrue(db.intakeDao().listActive().isEmpty())
        db.useWriterConnection { connection ->
            for (table in listOf("intake", "schedule_version", "reminder_rule", "medication", "intake_medication", "health_card")) {
                connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
                    assertTrue(statement.step())
                    assertEquals(0L, statement.getLong(0), table)
                }
            }
        }
    }

    @Test
    fun creating_an_item_without_medications_sets_the_cards_edit_time() = runTest {
        val draft = draft()
        val id = create(draft)
        assertTrue(save.loadMedications(id).isEmpty())
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
    }

    @Test
    fun renaming_an_item_stamps_the_card() = runTest {
        val draft = draft()
        val id = create(draft)
        fx.at("2026-11-11T08:10")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(name = "저녁약")))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
    }

    @Test
    fun switching_slot_and_schedule_stamps_the_card_each_time() = runTest {
        val draft = draft()
        val id = create(draft)
        fx.at("2026-11-11T08:10")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(slotLabel = SlotLabel.EVENING)))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
        fx.at("2026-11-11T08:20")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
    }

    @Test
    fun moving_an_item_stamps_both_cards() = runTest {
        val draft = draft()
        val id = create(draft.copy(medications = examples))
        val other = people.add("가상 가족", "#B5562B").id
        fx.at("2026-11-11T08:10")
        assertIs<SaveIntakeResult.Saved>(save.update(id, draft.copy(personId = other)))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
        assertEquals(editedAt(draft.personId), editedAt(other))
        assertEquals(2, save.loadMedications(id).size)
    }

    @Test
    fun archiving_stamps_the_card_and_keeps_the_rows() = runTest {
        val draft = draft()
        val id = create(draft.copy(medications = examples))
        val rows = save.loadMedications(id)
        fx.at("2026-11-11T08:10")
        assertTrue(archive(id))
        assertEquals(fx.clock.now().toEpochMilliseconds(), editedAt(draft.personId))
        assertEquals(rows, save.loadMedications(id))
        assertTrue(board().items.isEmpty())
    }

    @Test
    fun a_judgment_alone_keeps_the_cards_edit_time() = runTest {
        val draft = draft()
        val id = create(draft.copy(medications = examples))
        val before = editedAt(draft.personId)
        fx.at("2026-11-11T08:10")
        assertIs<RecordResult.Recorded>(fx.record(id, renderedKey = "2026-11-11"))
        assertEquals(before, editedAt(draft.personId))
    }
}
