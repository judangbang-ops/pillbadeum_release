package com.zmanlab.diditake.domain

import com.zmanlab.diditake.testing.seoul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

class DayBoundaryTest {
    private val defaults = WindowSettings()

    @Test
    fun logicalDate_switches_exactly_at_0400() {
        assertEquals(LocalDate(2026, 11, 10), logicalDateOf(seoul("2026-11-11T03:59:59.999"), defaults))
        assertEquals(LocalDate(2026, 11, 11), logicalDateOf(seoul("2026-11-11T04:00"), defaults))
        assertEquals(LocalDate(2026, 11, 11), logicalDateOf(seoul("2026-11-12T00:30"), defaults))
    }

    @Test
    fun nextDayBoundary_is_the_coming_0400_and_never_now() {
        assertEquals(seoul("2026-11-11T04:00"), nextDayBoundary(seoul("2026-11-11T03:59"), defaults))
        // Exactly at the boundary the next one is a day later (no alarm loop at 04:00).
        assertEquals(seoul("2026-11-12T04:00"), nextDayBoundary(seoul("2026-11-11T04:00"), defaults))
        assertEquals(seoul("2026-11-12T04:00"), nextDayBoundary(seoul("2026-11-11T23:59"), defaults))
    }

    @Test
    fun dailyWindowOn_starts_at_day_start_of_that_date() {
        val window = dailyWindowOn(LocalDate(2026, 11, 9), defaults)
        assertEquals("2026-11-09", window.periodKey)
        assertEquals(seoul("2026-11-09T04:00"), window.start)
        assertEquals(seoul("2026-11-10T04:00"), window.end)
    }

    @Test
    fun mondayToSunday_covers_the_calendar_week() {
        val week = mondayToSunday(LocalDate(2026, 11, 11)) // Wednesday
        assertEquals(LocalDate(2026, 11, 9), week.first())
        assertEquals(LocalDate(2026, 11, 15), week.last())
        assertEquals(7, week.size)
        // Monday and Sunday map to their own week.
        assertEquals(week, mondayToSunday(LocalDate(2026, 11, 9)))
        assertEquals(week, mondayToSunday(LocalDate(2026, 11, 15)))
    }
}
