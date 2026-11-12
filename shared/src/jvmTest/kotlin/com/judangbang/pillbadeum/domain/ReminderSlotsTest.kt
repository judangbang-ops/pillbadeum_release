package com.judangbang.pillbadeum.domain

import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * The reminder grid and the save-time quiet-hours notice (TASK-011, record-rules-supplement.md N-4, N-5).
 * Tests named rrNN follow the supplement's counterexample rows; do not edit their expectations to make them pass.
 */
class ReminderSlotsTest {
    private val settings = WindowSettings() // day start 04:00, Asia/Seoul
    private val reminders = ReminderSettings() // 60 minutes, 3 a day, quiet 22:00~07:00
    private val noQuiet = reminders.copy(quietStart = LocalTime(0, 0), quietEnd = LocalTime(0, 0))

    private fun dailyWindow(at: String): Window = windowOf(ScheduleRule.Daily, seoul(at), settings)

    private fun slots(window: Window, start: String, r: ReminderSettings = reminders): List<String> =
        reminderSlots(window, LocalTime.parse(start), settings, r).map { local(it.at) }

    private fun local(at: Instant): String = at.toLocalDateTime(SEOUL).toString()

    @Test
    fun rr46_three_slots_an_interval_apart() {
        assertEquals(
            listOf("2026-11-11T08:00", "2026-11-11T09:00", "2026-11-11T10:00"),
            slots(dailyWindow("2026-11-11T12:00"), "08:00"),
        )
    }

    @Test
    fun rr49_quiet_hours_drop_the_repeats_and_the_save_notice_counts_them() {
        assertEquals(listOf("2026-11-11T21:00"), slots(dailyWindow("2026-11-11T12:00"), "21:00"))
        assertEquals(QuietNotice.Reduced(2), quietNotice(LocalTime(21, 0), ScheduleRule.Daily, settings, reminders))
    }

    @Test
    fun rr50_a_start_in_quiet_hours_rings_once() {
        assertEquals(listOf("2026-11-11T22:30"), slots(dailyWindow("2026-11-11T12:00"), "22:30"))
        assertEquals(QuietNotice.FirstOnly, quietNotice(LocalTime(22, 30), ScheduleRule.Daily, settings, reminders))
    }

    @Test
    fun rr82_a_start_before_the_day_start_is_on_the_next_date() {
        assertEquals(
            listOf("2026-11-12T00:30", "2026-11-12T01:30", "2026-11-12T02:30"),
            slots(dailyWindow("2026-11-11T12:00"), "00:30", noQuiet),
        )
    }

    @Test
    fun rr83_the_end_of_quiet_hours_is_outside_them() {
        assertEquals(
            listOf("2026-11-11T07:00", "2026-11-11T08:00", "2026-11-11T09:00"),
            slots(dailyWindow("2026-11-11T12:00"), "07:00"),
        )
        // 06:00 rings as the first slot, 07:00 is no longer quiet.
        assertEquals(
            listOf("2026-11-11T06:00", "2026-11-11T07:00", "2026-11-11T08:00"),
            slots(dailyWindow("2026-11-11T12:00"), "06:00"),
        )
        assertNull(quietNotice(LocalTime(7, 0), ScheduleRule.Daily, settings, reminders))
        assertNull(quietNotice(LocalTime(6, 0), ScheduleRule.Daily, settings, reminders), "06:00 in quiet but no repeat is lost")
    }

    @Test
    fun slots_at_or_after_the_window_end_are_dropped_and_not_counted_as_quiet() {
        // Window 11-11 04:00 ~ 11-12 04:00; a 03:00 start is 11-12 03:00, so 04:00 and 05:00 fall in the next window.
        assertEquals(listOf("2026-11-12T03:00"), slots(dailyWindow("2026-11-11T12:00"), "03:00", noQuiet))
        assertNull(quietNotice(LocalTime(3, 0), ScheduleRule.Daily, settings, noQuiet))
    }

    @Test
    fun rr53_weekly_items_ring_on_the_first_day_of_the_window_only() {
        val window = windowOf(ScheduleRule.Weekly(DayOfWeek.MONDAY), seoul("2026-11-12T12:00"), settings)
        assertEquals("2026-11-09", window.periodKey)
        assertEquals(
            listOf("2026-11-09T08:00", "2026-11-09T09:00", "2026-11-09T10:00"),
            slots(window, "08:00"),
        )
        // A late start runs past midnight inside the 7-day window.
        assertEquals(
            listOf("2026-11-09T21:00", "2026-11-10T07:00"),
            slots(window, "21:00", reminders.copy(interval = 120.minutes, maxPerDay = 6)),
        )
    }

    @Test
    fun max_per_day_counts_slots_before_quiet_hours_drop_any() {
        // 20:00, 21:30, 23:00, 00:30, 02:00: the last three are quiet and are not moved later.
        val r = reminders.copy(interval = 90.minutes, maxPerDay = 5)
        assertEquals(listOf("2026-11-11T20:00", "2026-11-11T21:30"), slots(dailyWindow("2026-11-11T12:00"), "20:00", r))
        assertEquals(QuietNotice.Reduced(3), quietNotice(LocalTime(20, 0), ScheduleRule.Daily, settings, r))
    }

    @Test
    fun the_offered_values_are_the_only_valid_settings() {
        assertTrue(reminders.isValid)
        assertFalse(reminders.copy(interval = 45.minutes).isValid)
        assertFalse(reminders.copy(maxPerDay = 0).isValid)
        assertFalse(reminders.copy(maxPerDay = 6).isValid)
        assertFalse(reminders.copy(quietStart = LocalTime(22, 15)).isValid)
        assertTrue(noQuiet.isValid)
        assertFalse(noQuiet.isQuiet(LocalTime(3, 0)), "equal ends mean no quiet hours")
    }

    @Test
    fun default_start_times_follow_the_time_of_day_label() {
        assertEquals(LocalTime(8, 0), defaultReminderStart(SlotLabel.MORNING))
        assertEquals(LocalTime(12, 0), defaultReminderStart(SlotLabel.NOON))
        assertEquals(LocalTime(18, 0), defaultReminderStart(SlotLabel.EVENING))
        assertEquals(LocalTime(21, 0), defaultReminderStart(SlotLabel.BEDTIME))
        assertEquals(LocalTime(8, 0), defaultReminderStart(null))
    }
}
