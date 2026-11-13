package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.CellState
import com.judangbang.pillbadeum.status.HistoryMonth
import com.judangbang.pillbadeum.status.LoadHistory
import com.judangbang.pillbadeum.status.LoadStatusBoard
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
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Person edit, archive and restore (TASK-008, TASK-053, REQ-005, REQ-016, REQ-017, REQ-095, review B5,
 * record-rules-supplement.md P-9). Tests named rrNN follow the supplement's counterexample rows; do not edit
 * their expectations to make them pass.
 */
class ManagePeopleTest {
    private val fx = DbFixture()
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val board = LoadStatusBoard(fx.db, fx.clock)
    private val history = LoadHistory(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun intakeOf(personId: String, name: String, rule: ScheduleRule = ScheduleRule.Daily): String =
        assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(personId, name, rule = rule))).intakeId

    private suspend fun versions(intakeId: String): List<ScheduleVersionEntity> =
        fx.db.scheduleVersionDao().listByIntake(intakeId)

    private fun ms(localDateTime: String): Long = seoul(localDateTime).toEpochMilliseconds()

    private fun HistoryMonth.statesOn(date: String, intakeId: String): List<CellState> =
        days.firstOrNull { it.date == LocalDate.parse(date) }?.cells.orEmpty().filter { it.intakeId == intakeId }.map { it.state }

    /** Version spans never overlap and exactly one is open (TASK-005, P-9). */
    private suspend fun assertOneOpenVersionAndNoOverlap(intakeId: String) {
        val all = versions(intakeId)
        assertEquals(1, all.count { it.effectiveTo == null }, "open versions of $intakeId: $all")
        // An emptied version (from = to) sorts before the version that starts where it ends.
        for ((a, b) in all.sortedWith(compareBy({ it.effectiveFrom }, { it.effectiveTo ?: Long.MAX_VALUE })).zipWithNext()) {
            val aEnd = assertNotNull(a.effectiveTo, "only the last version is open: $all")
            assertTrue(aEnd <= b.effectiveFrom, "versions overlap: $a, $b")
            assertTrue(a.effectiveFrom <= aEnd, "a version ends before it starts: $a")
        }
    }

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
        val before = assertIs<RecordResult.Recorded>(fx.record(hers, renderedKey = "2026-11-11")).record
        fx.at("2026-11-11T09:00")
        people.archive(mom.id)
        assertEquals(listOf(mom.id), people.listArchived().map { it.id })

        // Archived and restored in the same window (P-9): that window is the new version's and the judgment
        // written before the archive completes it, so a widget tap is Already and history shows one cell.
        fx.at("2026-11-11T10:00")
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        assertEquals(PersonChange.Gone, people.restore(mom.id), "already active")
        assertTrue(people.listArchived().isEmpty())
        assertEquals(setOf(me.id, mom.id), people.list().map { it.id }.toSet())
        assertTrue(board().items.single { it.intakeId == hers }.isDone)
        assertEquals(before.id, assertIs<RecordResult.Already>(fx.record(hers, renderedKey = "2026-11-11")).existing.id)
        assertEquals(1, fx.activeRows(hers).size)
        val today = history(LocalDate(2026, 11, 1)).days.single { it.date == LocalDate(2026, 11, 11) }.cells.filter { it.intakeId == hers }
        assertEquals(listOf(before.id), today.map { it.judgment?.id })
        assertFalse(today.single().cutByScheduleChange)
        assertOneOpenVersionAndNoOverlap(hers)
    }

    @Test
    fun rr90_restoring_a_person_leaves_the_archived_stretch_without_cells() = runTest {
        fx.at("2026-11-01T12:00")
        val me = people.ensureDefault().single()
        val mom = people.add("Mom", "#B5562B")
        val hers = intakeOf(mom.id, "혈압약")
        val mine = intakeOf(me.id, "아침약")
        assertIs<SaveReminderResult.Saved>(
            ManageReminders(fx.db, fx.clock, TEST_DEVICE_ID).saveRule(hers, enabled = true, start = LocalTime(8, 0)),
        )
        fx.at("2026-11-02T08:00")
        assertIs<RecordResult.Recorded>(fx.record(hers, renderedKey = "2026-11-02"))
        fx.at("2026-11-03T08:00")
        assertIs<RecordResult.Recorded>(fx.record(hers, renderedKey = "2026-11-03"))
        fx.at("2026-11-03T10:00")
        assertEquals(PersonChange.Saved, people.archive(mom.id))
        fx.at("2026-11-06T12:00")
        assertNull(PlanReminders(fx.db, fx.clock).nextAlarm(), "no reminder while Mom is archived")

        fx.at("2026-11-10T09:00")
        assertEquals(PersonChange.Saved, people.restore(mom.id))

        val month = history(LocalDate(2026, 11, 1))
        assertEquals(listOf(CellState.TAKEN), month.statesOn("2026-11-02", hers))
        assertEquals(listOf(CellState.TAKEN), month.statesOn("2026-11-03", hers), "the archive's window keeps its judgment")
        for (day in 4..9) {
            assertEquals(emptyList(), month.statesOn("2026-11-0$day", hers), "11/0$day: archived, no cell (not missed)")
            assertEquals(listOf(CellState.MISSED), month.statesOn("2026-11-0$day", mine), "11/0$day: my item is not touched")
        }
        assertEquals(listOf(CellState.OPEN), month.statesOn("2026-11-10", hers))
        // The widget and today's list show Mom's item again, not done; reminders plan it again (N-9 recompute).
        assertFalse(board().items.single { it.intakeId == hers }.isDone)
        val next = assertNotNull(PlanReminders(fx.db, fx.clock).nextAlarm())
        assertTrue(next > fx.clock.now())
        // Pressing [되살리기] again adds no version.
        val after = versions(hers)
        assertEquals(PersonChange.Gone, people.restore(mom.id))
        assertEquals(after, versions(hers))
        assertEquals(2, after.size)
        assertOneOpenVersionAndNoOverlap(hers)
    }

    @Test
    fun restore_reopens_each_items_schedule_and_leaves_separately_archived_items_alone() = runTest {
        fx.at("2026-11-02T12:00")
        val me = people.ensureDefault().single()
        val mom = people.add("Mom", "#B5562B")
        val daily = intakeOf(mom.id, "혈압약")
        val weekly = intakeOf(mom.id, "골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        val shelved = intakeOf(mom.id, "감기약")
        val mine = intakeOf(me.id, "아침약")
        fx.at("2026-11-03T09:00")
        assertTrue(ArchiveIntake(fx.db, fx.clock)(shelved))
        val shelvedBefore = versions(shelved)
        val mineBefore = versions(mine)
        fx.at("2026-11-04T10:00")
        people.archive(mom.id)

        fx.at("2026-11-12T09:30")
        assertEquals(PersonChange.Saved, people.restore(mom.id))

        for (id in listOf(daily, weekly)) {
            val (old, new) = versions(id).also { assertEquals(2, it.size, "$it") }
            assertEquals(ms("2026-11-04T10:00"), old.effectiveTo, "closed at the archive")
            assertEquals(ms("2026-11-12T09:30"), new.effectiveFrom, "opened at the restore")
            assertNull(new.effectiveTo)
            assertEquals(listOf(old.type, old.paramsJson, old.latePolicy), listOf(new.type, new.paramsJson, new.latePolicy))
            assertEquals(TEST_DEVICE_ID, new.deviceId)
            assertEquals(1, new.version)
            assertOneOpenVersionAndNoOverlap(id)
        }
        // The separately archived item is not restored and its version is not touched; other people neither.
        assertEquals(shelvedBefore, versions(shelved))
        assertNotNull(fx.db.intakeDao().findById(shelved)?.archivedAt)
        assertEquals(mineBefore, versions(mine))
        assertEquals(setOf(daily, weekly, mine), board().items.map { it.intakeId }.toSet())
    }

    @Test
    fun restore_with_the_clock_set_back_never_overlaps_versions() = runTest {
        val me = people.ensureDefault().single()
        val mom = people.add("Mom", "#B5562B")
        val hers = intakeOf(mom.id, "혈압약") // first version from 11-11 04:00 (window of 08:00, P-4)
        intakeOf(me.id, "아침약")
        fx.at("2026-11-11T09:00")
        people.archive(mom.id)

        // Set back to before the archive: both ends at now (P-9).
        fx.at("2026-11-11T08:30")
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        val (old, new) = versions(hers)
        assertEquals(ms("2026-11-11T08:30"), old.effectiveTo)
        assertEquals(ms("2026-11-11T08:30"), new.effectiveFrom)
        assertOneOpenVersionAndNoOverlap(hers)

        // Set back to before the open version's own start: it is left empty, never ending before it starts.
        fx.at("2026-11-11T09:00")
        people.archive(mom.id)
        fx.at("2026-11-10T20:00")
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        val emptied = versions(hers).single { it.id == new.id }
        assertEquals(ms("2026-11-11T08:30"), emptied.effectiveTo, "empty: it started at 08:30")
        assertEquals(ms("2026-11-11T08:30"), versions(hers).single { it.effectiveTo == null }.effectiveFrom)
        assertEquals(3, versions(hers).size)
        assertOneOpenVersionAndNoOverlap(hers)
    }

    @Test
    fun a_version_that_starts_after_the_archive_is_kept() = runTest {
        val me = people.ensureDefault().single()
        val mom = people.add("Mom", "#B5562B")
        val hers = intakeOf(mom.id, "혈압약")
        intakeOf(me.id, "아침약")
        fx.at("2026-11-11T09:00")
        people.archive(mom.id)
        // A schedule change written while archived (not offered by the app, e.g. a clock that was wrong).
        val dao = fx.db.scheduleVersionDao()
        val first = versions(hers).single()
        fx.at("2026-11-12T10:00")
        assertEquals(1, dao.close(first.id, effectiveTo = ms("2026-11-12T10:00"), now = ms("2026-11-12T10:00")))
        dao.insert(first.copy(id = fx.ids.next(), effectiveFrom = ms("2026-11-12T10:00"), effectiveTo = null))
        val before = versions(hers)

        fx.at("2026-11-13T09:00")
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        assertEquals(before, versions(hers))
        assertOneOpenVersionAndNoOverlap(hers)
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
