package com.zmanlab.diditake.domain

import com.zmanlab.diditake.testing.seoul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class WindowTest {
    private val defaults = WindowSettings()

    private fun daily(at: Instant, settings: WindowSettings = defaults) = windowOf(ScheduleRule.Daily, at, settings)

    private fun weekly(anchor: DayOfWeek, at: Instant) = windowOf(ScheduleRule.Weekly(anchor), at, defaults)

    @Test
    fun defaults_are_0400_and_seoul() {
        assertEquals(LocalTime(4, 0), defaults.dayStart)
        assertEquals("Asia/Seoul", defaults.baseZone.id)
    }

    @Test
    fun daily_morning_belongs_to_same_day() {
        val window = daily(seoul("2026-11-11T08:02"))
        assertEquals("2026-11-11", window.periodKey)
        assertEquals(seoul("2026-11-11T04:00"), window.start)
        assertEquals(seoul("2026-11-12T04:00"), window.end)
    }

    @Test
    fun daily_0030_belongs_to_previous_days_window() {
        val window = daily(seoul("2026-11-12T00:30"))
        assertEquals("2026-11-11", window.periodKey)
        assertEquals(seoul("2026-11-11T04:00"), window.start)
        assertEquals(seoul("2026-11-12T04:00"), window.end)
    }

    @Test
    fun daily_2359_and_0001_share_a_window() {
        assertEquals(daily(seoul("2026-11-11T23:59")), daily(seoul("2026-11-12T00:01")))
    }

    @Test
    fun daily_0359_and_0401_are_different_windows() {
        val before = daily(seoul("2026-11-12T03:59"))
        val after = daily(seoul("2026-11-12T04:01"))
        assertEquals("2026-11-11", before.periodKey)
        assertEquals("2026-11-12", after.periodKey)
        assertEquals(before.end, after.start)
    }

    @Test
    fun daily_boundary_instant_starts_the_new_window() {
        val boundary = seoul("2026-11-12T04:00")
        assertEquals("2026-11-12", daily(boundary).periodKey)
        assertEquals("2026-11-11", daily(boundary - 1.milliseconds).periodKey)
    }

    @Test
    fun daily_uses_configured_day_start() {
        val settings = WindowSettings(dayStart = LocalTime(2, 0))
        assertEquals("2026-11-12", daily(seoul("2026-11-12T03:00"), settings).periodKey)
        assertEquals("2026-11-11", daily(seoul("2026-11-12T01:59"), settings).periodKey)
    }

    @Test
    fun daily_is_computed_in_base_zone_not_utc() {
        // 2026-11-11T20:00Z is 2026-11-12T05:00 in Seoul.
        assertEquals("2026-11-12", daily(Instant.parse("2026-11-11T20:00:00Z")).periodKey)
    }

    @Test
    fun weekly_friday_anchor_on_monday_is_in_window_started_last_friday() {
        val window = weekly(DayOfWeek.FRIDAY, seoul("2026-11-16T08:00")) // Monday
        assertEquals("2026-11-13", window.periodKey) // Friday
        assertEquals(seoul("2026-11-13T04:00"), window.start)
        assertEquals(seoul("2026-11-20T04:00"), window.end)
    }

    @Test
    fun weekly_monday_anchor_sunday_night_and_next_monday_morning_are_different_windows() {
        val sunday = weekly(DayOfWeek.MONDAY, seoul("2026-11-15T22:00"))
        val monday = weekly(DayOfWeek.MONDAY, seoul("2026-11-16T08:00"))
        assertEquals("2026-11-09", sunday.periodKey)
        assertEquals("2026-11-16", monday.periodKey)
        assertNotEquals(sunday, monday)
        assertEquals(sunday.end, monday.start)
    }

    @Test
    fun weekly_anchor_day_before_day_start_still_belongs_to_previous_week() {
        assertEquals("2026-11-09", weekly(DayOfWeek.MONDAY, seoul("2026-11-16T03:59")).periodKey)
    }

    @Test
    fun year_end_keys_are_plain_dates() {
        assertEquals("2026-12-31", daily(seoul("2026-12-31T08:00")).periodKey)
        assertEquals("2027-01-01", daily(seoul("2027-01-01T08:00")).periodKey)
        // 2026 has an ISO week 53; the key must still be the window start date, never "2026-W53".
        val lastWeek = weekly(DayOfWeek.MONDAY, seoul("2027-01-02T08:00"))
        assertEquals("2026-12-28", lastWeek.periodKey)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(lastWeek.periodKey))
    }

    @Test
    fun windows_stay_contiguous_across_a_dst_gap_at_day_start() {
        // T0 = 02:30 falls into the 2026-03-08 spring-forward gap (02:00 -> 03:00) in New York.
        val zone = TimeZone.of("America/New_York")
        val settings = WindowSettings(dayStart = LocalTime(2, 30), baseZone = zone)
        var at = LocalDateTime.parse("2026-03-07T00:00").toInstant(zone)
        val stop = LocalDateTime.parse("2026-03-10T00:00").toInstant(zone)
        var previous: Window? = null
        while (at < stop) {
            val window = windowOf(ScheduleRule.Daily, at, settings)
            assertTrue(at in window, "window $window must contain $at")
            if (previous != null && previous != window) assertEquals(previous.end, window.start)
            previous = window
            at += 10.minutes
        }
    }

    @Test
    fun windows_stay_contiguous_across_a_dst_overlap_at_day_start() {
        // T0 = 01:30 occurs twice on 2026-11-01 (fall back 02:00 -> 01:00) in New York.
        val zone = TimeZone.of("America/New_York")
        val settings = WindowSettings(dayStart = LocalTime(1, 30), baseZone = zone)
        var at = LocalDateTime.parse("2026-10-31T00:00").toInstant(zone)
        val stop = at + 72.hours
        var previous: Window? = null
        while (at < stop) {
            val window = windowOf(ScheduleRule.Weekly(DayOfWeek.SUNDAY), at, settings)
            assertTrue(at in window, "window $window must contain $at")
            if (previous != null && previous != window) assertEquals(previous.end, window.start)
            previous = window
            at += 10.minutes
        }
    }
}
