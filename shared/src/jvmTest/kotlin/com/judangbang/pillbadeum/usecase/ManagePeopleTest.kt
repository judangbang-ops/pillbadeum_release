package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Person edit, archive and restore (TASK-008, REQ-005, REQ-016, REQ-017, review B5). */
class ManagePeopleTest {
    private val fx = DbFixture()
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun intakeOf(personId: String, name: String): String =
        assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(personId, name))).intakeId

    @Test
    fun archiving_the_last_person_is_refused() = runTest {
        val me = people.ensureDefault().single()
        assertEquals(PersonChange.LastPerson, people.archive(me.id))
        assertEquals(listOf(me.id), people.list().map { it.id })

        val mom = people.add("엄마", "#B5562B")
        assertEquals(PersonChange.Saved, people.archive(mom.id))
        assertEquals(PersonChange.LastPerson, people.archive(me.id), "still the only active person")
    }

    @Test
    fun archived_persons_items_leave_the_board_keep_judgments_and_reject_taps() = runTest {
        val me = people.ensureDefault().single()
        val mom = people.add("엄마", "#B5562B")
        val mine = intakeOf(me.id, "아침약")
        val hers = intakeOf(mom.id, "혈압약")
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(hers, renderedKey = "2026-11-11"))

        fx.at("2026-11-11T09:00")
        assertEquals(PersonChange.Saved, people.archive(mom.id))

        assertEquals(listOf(mine), board().items.map { it.intakeId })
        assertEquals(1, fx.activeRows(hers).size, "her judgment stays")
        // RR-14 (person part): a button rendered before the archive is refused, nothing written.
        assertEquals(RecordResult.Gone, fx.record(hers, renderedKey = "2026-11-11"))
        assertEquals(RecordResult.Gone, fx.record(hers, renderedKey = null, source = DoseSource.APP, kind = DoseKind.SKIPPED))
        val change = RecordCommand(hers, DoseKind.SKIPPED, DoseSource.APP, renderedPeriodKey = null, deviceZoneId = "Asia/Seoul")
        assertEquals(RecordResult.Gone, fx.changeJudgment(change))
        assertEquals(1, fx.history(hers).size)
        // New items cannot be filed under an archived person.
        assertEquals(
            SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_PERSON),
            saveIntake.create(IntakeDraft(mom.id, "비타민")),
        )
    }

    @Test
    fun restore_brings_items_back_with_their_judgments() = runTest {
        val me = people.ensureDefault().single()
        val mom = people.add("엄마", "#B5562B")
        val hers = intakeOf(mom.id, "혈압약")
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(hers, renderedKey = "2026-11-11"))
        people.archive(mom.id)
        assertEquals(listOf(mom.id), people.listArchived().map { it.id })

        fx.at("2026-11-11T10:00")
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        assertEquals(PersonChange.Gone, people.restore(mom.id), "already active")
        assertTrue(people.listArchived().isEmpty())
        assertEquals(setOf(me.id, mom.id), people.list().map { it.id }.toSet())
        assertTrue(board().items.single { it.intakeId == hers }.isDone)
    }

    @Test
    fun update_edits_an_active_person_only() = runTest {
        people.ensureDefault()
        val mom = people.add("엄마", "#B5562B")
        fx.at("2026-11-11T09:00")
        assertEquals(PersonChange.Saved, people.update(mom.id, " 어머니 ", "#1D4E89"))
        val edited = fx.db.personDao().findById(mom.id)!!
        assertEquals("어머니", edited.name)
        assertEquals("#1D4E89", edited.color)
        assertEquals(2, edited.version)

        assertFailsWith<IllegalArgumentException> { people.update(mom.id, " ", "#1D4E89") }
        assertFailsWith<IllegalArgumentException> { people.update(mom.id, "엄마", "blue") }
        people.archive(mom.id)
        assertEquals(PersonChange.Gone, people.update(mom.id, "엄마", "#B5562B"))
        assertEquals(PersonChange.Gone, people.update("no-such-person", "엄마", "#B5562B"))
        assertEquals(PersonChange.Gone, people.archive(mom.id), "already archived")
    }
}
