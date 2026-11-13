package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * SCR-008's words (TASK-019, 09 History, REQ-037~039, S-5): what a cell shows under its name and what TalkBack reads, on
 * cells read by [LoadHistory]. Seen on Thursday 11/12 09:30 in Seoul, day start 04:00. ` ` is the no-break space.
 */
class HistoryLabelsTest {
    private val fx = DbFixture(start = seoul("2026-11-12T09:30"))
    private val history = LoadHistory(fx.db, fx.clock)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    /** Records at [at] in the window of that time, as a tap in the app would. */
    private suspend fun recordAt(intakeId: String, at: String, kind: DoseKind = DoseKind.TAKEN) {
        val keep = fx.clock.now
        fx.at(at)
        val key = board().items.single { it.intakeId == intakeId }.window.periodKey
        assertIs<RecordResult.Recorded>(fx.record(intakeId, renderedKey = key, source = DoseSource.APP, kind = kind))
        fx.clock.now = keep
    }

    private suspend fun cell(date: String, intakeId: String): HistoryCell {
        val day = LocalDate.parse(date)
        return history(LocalDate(day.year, day.month, 1)).days.single { it.date == day }.cells.single { it.intakeId == intakeId }
    }

    @Test
    fun a_daily_cell_shows_its_time_and_a_date_after_midnight() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-08T04:00"))
        recordAt(intake, "2026-11-09T08:02")
        // 01:30 on 11/11 is still 11/10's window (day start 04:00): the line is 11/10's, the time carries its date.
        recordAt(intake, "2026-11-11T01:30")
        recordAt(intake, "2026-11-11T09:10", DoseKind.SKIPPED)

        val taken = cell("2026-11-09", intake)
        assertEquals("08:02", Labels.historyStatus(taken, SEOUL))
        assertEquals("Morning dose, 08:02 먹음", Labels.historySpoken(taken, withPerson = false, SEOUL))
        val late = cell("2026-11-10", intake)
        assertEquals("11/11 01:30", Labels.historyStatus(late, SEOUL))
        assertEquals("Morning dose, 11월 11일 수요일 01:30 먹음", Labels.historySpoken(late, withPerson = false, SEOUL))

        // S-5: a skip shows the word, not a time (09 History); missed and the open window have their words.
        val skipped = cell("2026-11-11", intake)
        assertEquals("건너뜀", Labels.historyStatus(skipped, SEOUL))
        assertEquals("Morning dose, 건너뜀", Labels.historySpoken(skipped, withPerson = false, SEOUL))
        assertEquals("빠짐", Labels.historyStatus(cell("2026-11-08", intake), SEOUL))
        assertEquals("Morning dose, 아직", Labels.historySpoken(cell("2026-11-12", intake), withPerson = false, SEOUL))
    }

    @Test
    fun a_weekly_cell_names_its_days_and_dates_its_dose() = runTest {
        val intake = fx.newIntake(rule = ScheduleRule.Weekly(DayOfWeek.MONDAY), effectiveFrom = seoul("2026-10-26T04:00"))
        recordAt(intake, "2026-11-10T08:30")

        val week = cell("2026-11-09", intake)
        assertEquals("11/09~11/15", Labels.weekRange(week))
        assertEquals("11/10(화) 08:30", Labels.historyStatus(week, SEOUL))
        assertEquals("Morning dose, 11월 9일부터 15일까지, 11월 10일 화요일 08:30 먹음", Labels.historySpoken(week, withPerson = false, SEOUL))

        // A week across the month end is named with both months.
        val october = cell("2026-10-26", intake)
        assertEquals("10/26~11/01", Labels.weekRange(october))
        assertEquals("Morning dose, 10월 26일부터 11월 1일까지, 빠짐", Labels.historySpoken(october, withPerson = false, SEOUL))
    }

    @Test
    fun the_person_comes_first_and_a_clock_warning_last() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-09T04:00"))
        recordAt(intake, "2026-11-10T21:40")
        val plain = cell("2026-11-10", intake)
        val mom = plain.copy(intakeName = "혈압약", person = plain.person.copy(name = "엄마"))

        assertEquals("엄마 혈압약, 21:40 먹음", Labels.historySpoken(mom, withPerson = true, SEOUL))
        assertEquals("혈압약, 21:40 먹음", Labels.historySpoken(mom, withPerson = false, SEOUL))
        assertEquals("엄마 혈압약, 21:40 먹음, 시계 확인 필요", Labels.historySpoken(mom.copy(clockWarning = true), withPerson = true, SEOUL))
    }

    @Test
    fun times_are_on_the_phones_clock() = runTest {
        // REQ-082: lines stay the base zone's days; a time is the phone's, with its date when that is not the window's first.
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-09T04:00"))
        recordAt(intake, "2026-11-10T08:00")
        recordAt(intake, "2026-11-11T21:40")
        val utc = TimeZone.UTC

        // 11/10's window starts 11/09 19:00 UTC: 08:00 in Seoul is 23:00 on that first day.
        assertEquals("23:00", Labels.historyStatus(cell("2026-11-10", intake), utc))
        assertEquals("11/11 12:40", Labels.historyStatus(cell("2026-11-11", intake), utc))
    }

    @Test
    fun months_and_dates_are_named() {
        assertEquals("2026년 11월", Labels.monthTitle(LocalDate(2026, 11, 1)))
        assertEquals("10월", Labels.monthButton(LocalDate(2026, 10, 1), shown = LocalDate(2026, 11, 1)))
        assertEquals("2025년 12월", Labels.monthButton(LocalDate(2025, 12, 1), shown = LocalDate(2026, 1, 1)))
        assertEquals("11월 12일 목요일, 오늘", Labels.historyDay(LocalDate(2026, 11, 12), today = LocalDate(2026, 11, 12)))
        assertEquals("11월 11일 수요일", Labels.historyDay(LocalDate(2026, 11, 11), today = LocalDate(2026, 11, 12)))
    }
}
