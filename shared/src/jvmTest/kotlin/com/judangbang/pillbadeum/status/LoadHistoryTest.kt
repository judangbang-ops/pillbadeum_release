package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ArchiveIntake
import com.judangbang.pillbadeum.usecase.ManageSettings
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * History cells (TASK-007, REQ-037~040, record-rules-supplement.md P-8, S-5) and the home week grid that
 * uses the same cells. Tests named rrNN follow the supplement's counterexample rows; do not edit their
 * expectations to make them pass.
 */
class LoadHistoryTest {
    private val fx = DbFixture(start = seoul("2026-11-11T12:00"))
    private val history = LoadHistory(fx.db, fx.clock)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private val november = LocalDate(2026, 11, 1)

    private fun HistoryMonth.cellsOn(date: String, intakeId: String): List<HistoryCell> =
        days.firstOrNull { it.date == LocalDate.parse(date) }?.cells.orEmpty().filter { it.intakeId == intakeId }

    private fun HistoryMonth.stateOn(date: String, intakeId: String): CellState? = cellsOn(date, intakeId).singleOrNull()?.state

    private fun ms(localDateTime: String): Long = seoul(localDateTime).toEpochMilliseconds()

    /** Records with the widget path at [at] (the window of that time). */
    private suspend fun recordAt(intakeId: String, at: String, kind: DoseKind = DoseKind.TAKEN): DoseRecordEntity {
        val keep = fx.clock.now
        fx.at(at)
        val key = windowKeyNow(intakeId)
        val result = fx.record(intakeId, renderedKey = key, source = DoseSource.APP, kind = kind)
        fx.clock.now = keep
        return (result as RecordResult.Recorded).record
    }

    private suspend fun windowKeyNow(intakeId: String): String = board().items.single { it.intakeId == intakeId }.window.periodKey

    @Test
    fun daily_cells_show_taken_skipped_missed_and_the_open_window_newest_first() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-08T04:00"))
        recordAt(intake, "2026-11-08T08:00")
        recordAt(intake, "2026-11-09T09:00", DoseKind.SKIPPED)
        // 11-10: nothing. 11-11 (today, 12:00): not yet.
        val month = history(november)

        assertEquals(listOf("2026-11-11", "2026-11-10", "2026-11-09", "2026-11-08"), month.days.map { it.date.toString() })
        assertEquals(CellState.TAKEN, month.stateOn("2026-11-08", intake))
        assertEquals(ms("2026-11-08T08:00"), month.cellsOn("2026-11-08", intake).single().judgment?.takenAt)
        assertEquals(CellState.SKIPPED, month.stateOn("2026-11-09", intake))
        assertEquals(CellState.MISSED, month.stateOn("2026-11-10", intake))
        assertEquals(CellState.OPEN, month.stateOn("2026-11-11", intake))
        assertEquals(LocalDate(2026, 11, 1), month.firstMonth)
    }

    @Test
    fun no_cells_before_registration_after_archive_or_in_the_future() = runTest {
        fx.at("2026-11-09T15:00")
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-09T04:00"))
        recordAt(intake, "2026-11-10T08:00")
        fx.at("2026-11-11T10:00")
        assertTrue(ArchiveIntake(fx.db, fx.clock)(intake))
        fx.at("2026-11-13T12:00")

        val month = history(november)
        assertEquals(listOf("2026-11-10", "2026-11-09"), month.days.map { it.date.toString() }, "${month.days}")
        assertEquals(CellState.MISSED, month.stateOn("2026-11-09", intake), "registered that day: its window is scheduled")
        assertEquals(CellState.TAKEN, month.stateOn("2026-11-10", intake))
        // The window holding the archive (11-11) had no judgment: no cell, not a missed one.
        assertTrue(month.cellsOn("2026-11-11", intake).isEmpty())
        assertTrue(history(LocalDate(2026, 10, 1)).isEmpty, "the month before registration is empty")
        assertTrue(history(LocalDate(2026, 12, 1)).isEmpty, "next month has not started")
    }

    @Test
    fun the_archive_day_keeps_its_cell_when_it_was_recorded() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        recordAt(intake, "2026-11-11T08:00")
        fx.at("2026-11-11T10:00")
        ArchiveIntake(fx.db, fx.clock)(intake)
        fx.at("2026-11-12T12:00")

        assertEquals(CellState.TAKEN, history(november).stateOn("2026-11-11", intake))
    }

    /** TASK-019: an archived item's cells are marked (SCR-007 cannot open them), and `today` is the base date. */
    @Test
    fun archived_cells_are_marked_and_today_is_the_base_date() = runTest {
        val kept = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        val archived = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        recordAt(archived, "2026-11-10T08:00")
        fx.at("2026-11-11T10:00")
        assertTrue(ArchiveIntake(fx.db, fx.clock)(archived))
        fx.at("2026-11-12T02:00")

        val month = history(november)
        assertEquals(LocalDate(2026, 11, 11), month.today, "02:00 is still the 11th's window (day start 04:00)")
        assertTrue(month.cellsOn("2026-11-10", archived).single().archived)
        assertTrue(month.cellsOn("2026-11-10", kept).none { it.archived })
    }

    @Test
    fun rr45_weekly_items_have_one_cell_on_the_anchor_day_and_no_missed_days_between() = runTest {
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY), effectiveFrom = seoul("2026-10-26T04:00"))
        recordAt(intake, "2026-11-04T20:00") // Wednesday, in the week of Monday 11-02
        fx.at("2026-11-18T12:00")

        val month = history(november)
        assertEquals(listOf("2026-11-16", "2026-11-09", "2026-11-02"), month.days.map { it.date.toString() })
        assertEquals(CellState.TAKEN, month.stateOn("2026-11-02", intake))
        assertEquals(LocalDate.parse("2026-11-09"), LocalDate.parse(month.cellsOn("2026-11-09", intake).single().window.periodKey))
        assertEquals(CellState.MISSED, month.stateOn("2026-11-09", intake))
        assertEquals(CellState.OPEN, month.stateOn("2026-11-16", intake))
    }

    @Test
    fun rr45_daily_to_weekly_in_mid_month() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        recordAt(intake, "2026-11-10T08:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        fx.at("2026-11-24T12:00")

        val month = history(november)
        // Before the change: daily cells. After it: Monday cells only, the other days are empty, not missed.
        assertEquals(CellState.MISSED, month.stateOn("2026-11-02", intake))
        assertEquals(CellState.TAKEN, month.stateOn("2026-11-10", intake))
        for (day in listOf("2026-11-11", "2026-11-12", "2026-11-15", "2026-11-17", "2026-11-22")) {
            assertTrue(month.cellsOn(day, intake).isEmpty(), day)
        }
        assertEquals(listOf(ScheduleRule.Weekly(DayOfWeek.MONDAY)), month.cellsOn("2026-11-16", intake).map { it.rule })
        assertEquals(listOf(ScheduleRule.Weekly(DayOfWeek.MONDAY)), month.cellsOn("2026-11-23", intake).map { it.rule })
    }

    @Test
    fun rr77_a_day_start_change_does_not_put_one_judgment_in_two_cells() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val early = recordAt(intake, "2026-11-10T05:00") // period key 11-10 under 04:00
        assertEquals("2026-11-10", early.periodKey)
        fx.at("2026-11-11T12:00")
        ManageSettings(fx.db, fx.clock).saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0)))

        val month = history(november)
        assertEquals(CellState.TAKEN, month.stateOn("2026-11-10", intake))
        // Under 06:00 the 11-09 window [11-09 06:00, 11-10 06:00) holds 05:00, but the same version matches by key only.
        assertEquals(CellState.MISSED, month.stateOn("2026-11-09", intake))
    }

    @Test
    fun rr78_in_the_week_daily_became_weekly_monday_has_both_cells() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val monday = recordAt(intake, "2026-11-09T08:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val (daily, weekly) = fx.db.scheduleVersionDao().listByIntake(intake).map { it.id }
        fx.at("2026-11-11T12:00")

        val month = history(november)
        val mondayCells = month.cellsOn("2026-11-09", intake)
        assertEquals(listOf(daily, weekly), mondayCells.map { it.scheduleVersionId })
        assertEquals(listOf(monday.id, monday.id), mondayCells.map { it.judgment?.id }, "one judgment in both versions' cells")
        assertEquals(seoul("2026-11-16T04:00"), mondayCells[1].window.end, "the weekly cell is 11/09~11/15")
        assertEquals(CellState.MISSED, month.stateOn("2026-11-10", intake))
        assertTrue(month.cellsOn("2026-11-11", intake).isEmpty(), "no daily cell from the change day")
    }

    @Test
    fun in_the_week_weekly_became_daily_the_weekly_judgment_stays_visible() = runTest {
        // PR #12 review: a weekly (Monday) dose, then daily from Wednesday 10:00.
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY), effectiveFrom = seoul("2026-10-26T04:00"))
        val unrecorded = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY), effectiveFrom = seoul("2026-10-26T04:00"))
        val monday = recordAt(intake, "2026-11-09T08:00")
        fx.changeSchedule(intake, ScheduleRule.Daily, at = seoul("2026-11-11T10:00"))
        fx.changeSchedule(unrecorded, ScheduleRule.Daily, at = seoul("2026-11-11T10:00"))
        val (weekly, daily) = fx.db.scheduleVersionDao().listByIntake(intake).map { it.id }
        fx.at("2026-11-12T12:00")

        val month = history(november)
        // The cut weekly window 11/09~ belongs to the daily version, but keeps a cell for its own judgment.
        val cut = month.cellsOn("2026-11-09", intake).single()
        assertEquals(weekly, cut.scheduleVersionId)
        assertEquals(monday.id, cut.judgment?.id)
        assertEquals(CellState.TAKEN, cut.state)
        assertTrue(cut.cutByScheduleChange)
        assertTrue(month.cellsOn("2026-11-10", intake).isEmpty())
        assertEquals(listOf(daily), month.cellsOn("2026-11-11", intake).map { it.scheduleVersionId })
        assertEquals(CellState.MISSED, month.stateOn("2026-11-11", intake))
        assertEquals(CellState.OPEN, month.stateOn("2026-11-12", intake))
        assertEquals(CellState.MISSED, month.stateOn("2026-11-02", intake))
        // Without a judgment the cut window has no cell, not a missed one.
        assertTrue(month.cellsOn("2026-11-09", unrecorded).isEmpty())
    }

    @Test
    fun a_schedule_changed_and_changed_back_shows_the_judgment_once() = runTest {
        // PR #12 re-review: daily, weekly at 10:00, daily again at 10:05, after an 08:00 dose.
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val dose = recordAt(intake, "2026-11-11T08:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        fx.changeSchedule(intake, ScheduleRule.Daily, at = seoul("2026-11-11T10:05"))
        val last = fx.db.scheduleVersionDao().listByIntake(intake).last().id
        fx.at("2026-11-11T12:00")

        val month = history(november)
        val wednesday = month.cellsOn("2026-11-11", intake).single()
        assertEquals(last, wednesday.scheduleVersionId)
        assertEquals(dose.id, wednesday.judgment?.id)
        assertTrue(month.cellsOn("2026-11-09", intake).all { it.rule == ScheduleRule.Daily }, "no weekly cell on Monday")
    }

    @Test
    fun a_new_versions_judgment_is_not_matched_by_key_in_an_old_versions_cell() = runTest {
        // Daily, then weekly (Monday) from Wednesday 10:00; the weekly dose on Thursday has period key 11-09.
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val thursday = recordAt(intake, "2026-11-12T08:00")
        assertEquals("2026-11-09", thursday.periodKey)
        fx.at("2026-11-12T12:00")

        val (dailyCell, weeklyCell) = history(november).cellsOn("2026-11-09", intake)
        assertEquals(CellState.MISSED, dailyCell.state, "Monday's daily window does not hold Thursday 08:00")
        assertEquals(thursday.id, weeklyCell.judgment?.id)
    }

    @Test
    fun a_weekly_window_crossing_the_month_end_shows_a_judgment_of_the_next_month() = runTest {
        // Daily until Sunday 12-06 10:00, then weekly (Monday): the window 11/30~12/06 is the weekly one's.
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val december = recordAt(intake, "2026-12-01T08:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-12-06T10:00"))
        fx.at("2026-12-08T12:00")

        val weekly = history(november).cellsOn("2026-11-30", intake).single { it.rule is ScheduleRule.Weekly }
        assertEquals(december.id, weekly.judgment?.id)
        assertEquals(CellState.TAKEN, weekly.state)
    }

    @Test
    fun reading_history_raises_clock_flags_itself() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val friday = recordAt(intake, "2026-11-13T08:00")
        fx.at("2026-11-11T09:00")

        assertNull(fx.db.clockFlagDao().find(friday.id))
        history(november)
        assertTrue(fx.db.clockFlagDao().find(friday.id) != null, "LoadHistory raises the flag before reading")
    }

    @Test
    fun person_filter_keeps_one_persons_items() = runTest {
        val mine = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        val hers = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        val herPerson = fx.db.intakeDao().findById(hers)!!.personId

        assertEquals(setOf(mine, hers), history(november).days.flatMap { it.cells }.map { it.intakeId }.toSet())
        assertEquals(setOf(hers), history(november, personId = herPerson).days.flatMap { it.cells }.map { it.intakeId }.toSet())
    }

    @Test
    fun home_week_grid_uses_the_same_cells() = runTest {
        // Registered Tuesday 11-10; the week grid of Wednesday 11-11.
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-10T04:00"))
        val cells = board().items.single().weekCells.associate { it.date.toString() to it.state }
        assertEquals(DayState.NOT_SCHEDULED, cells["2026-11-09"])
        assertEquals(DayState.EMPTY, cells["2026-11-10"])
        assertEquals(DayState.TODAY_PENDING, cells["2026-11-11"])
        assertEquals(DayState.FUTURE, cells["2026-11-12"])

        // RR-77 on the home grid: after a day-start change the early dose stays on its own day only.
        recordAt(intake, "2026-11-11T05:00")
        fx.at("2026-11-11T12:00")
        ManageSettings(fx.db, fx.clock).saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0)))
        val after = board().items.single().weekCells.associate { it.date.toString() to it.state }
        assertEquals(DayState.EMPTY, after["2026-11-10"], "05:00 is in the 11-10 window under 06:00, but its key is 11-11")
        assertEquals(DayState.DONE, after["2026-11-11"])
    }

    @Test
    fun clock_warnings_are_marked_on_their_cells() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-01T04:00"))
        val friday = recordAt(intake, "2026-11-13T08:00")
        fx.at("2026-11-13T09:00")
        // Raise the flag as if the clock had been set back in between.
        fx.at("2026-11-11T09:00")
        board()
        fx.at("2026-11-13T09:00")

        val cell = history(november).cellsOn("2026-11-13", intake).single()
        assertEquals(friday.id, cell.judgment?.id)
        assertTrue(cell.clockWarning)
    }

    @Test
    fun ten_items_for_a_year_read_one_month_in_time() = runTest {
        val ids = UuidV7(fx.clock, Random(3))
        val start = LocalDate(2025, 11, 12)
        val intakes = (1..10).map { fx.newIntake(effectiveFrom = seoul("2025-11-12T04:00")) }
        val versions = intakes.associateWith { fx.db.scheduleVersionDao().listByIntake(it).single().id }
        var day = start
        while (day <= LocalDate(2026, 11, 10)) {
            for (intake in intakes) {
                val at = seoul("${day}T08:00").toEpochMilliseconds()
                fx.db.doseRecordDao().insertOrIgnore(
                    DoseRecordEntity(
                        id = ids.next(), intakeId = intake, scheduleVersionId = versions.getValue(intake),
                        periodKey = day.toString(), kind = DoseKind.TAKEN, takenAt = at, zoneId = SEOUL.id,
                        recordedAt = at, source = DoseSource.WIDGET, createdAt = at, updatedAt = at, deviceId = TEST_DEVICE_ID,
                    ),
                )
            }
            day = day.plus(1, DateTimeUnit.DAY)
        }
        fx.at("2026-11-11T12:00")
        history(november) // warm up

        val mark = TimeSource.Monotonic.markNow()
        val month = history(LocalDate(2026, 10, 1))
        val elapsed = mark.elapsedNow()
        println("TASK-007 timing: 10 items x 1 year (${10 * 364} rows), one month read in $elapsed")

        assertEquals(31, month.days.size)
        assertTrue(month.days.all { d -> d.cells.size == 10 && d.cells.all { it.state == CellState.TAKEN } })
        assertTrue(elapsed.inWholeMilliseconds < 3_000, "one month took $elapsed")
    }
}
