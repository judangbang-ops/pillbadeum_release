package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.saveReminderLastRunAt
import com.judangbang.pillbadeum.domain.QuietNotice
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * The reminder plan with its one alarm (TASK-011, REQ-041~045, REQ-077, record-rules-supplement.md N-4~N-8,
 * N-12, original RR-21·22). Tests named rrNN follow the counterexample rows; do not edit their expectations
 * to make them pass. The day starts at 04:00 (Asia/Seoul); quiet hours are 22:00~07:00 unless a test changes them.
 */
class PlanRemindersTest {
    private val fx = DbFixture(start = seoul("2026-11-11T07:00"))
    private val plan = PlanReminders(fx.db, fx.clock)
    private val manage = ManageReminders(fx.db, fx.clock, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    private val names = mutableMapOf<String, String>()

    /** The alarm as the app would hold it: replaced by every re-plan and by every run. */
    private var alarm: Instant? = null

    private suspend fun item(name: String, start: String, rule: ScheduleRule = ScheduleRule.Daily): String {
        val id = fx.newIntake(rule)
        names[id] = name
        assertIs<SaveReminderResult.Saved>(manage.saveRule(id, enabled = true, start = LocalTime.parse(start)))
        return id
    }

    private fun local(at: Instant): String = at.toLocalDateTime(SEOUL).toString().replace("2026-", "")

    /** After a change (N-9): only the next alarm. */
    private suspend fun replan() {
        alarm = plan.nextAlarm()
    }

    private fun ReminderNotice.label(): String = "${local(dueAt)} ${names[intakeId]}" + if (snoozed) " (snoozed)" else ""

    /**
     * Lets the alarm ring at each planned time up to [until]; returns what was shown. An alarm already past
     * rings at once. The clock ends at [until].
     */
    private suspend fun ringUntil(until: String): List<String> {
        val end = seoul(until)
        val shown = mutableListOf<String>()
        while (true) {
            val at = alarm ?: break
            if (at > end) break
            if (at > fx.clock.now) fx.clock.now = at
            val run = plan.fire()
            shown += run.notices.map { it.label() }.sorted()
            alarm = run.nextAlarmAt
        }
        fx.clock.now = end
        return shown
    }

    @Test
    fun rr46_daily_item_rings_three_times_then_tomorrow() = runTest {
        item("A", "08:00")
        replan()
        assertEquals(seoul("2026-11-11T08:00"), alarm)
        assertEquals(listOf("11-11T08:00 A", "11-11T09:00 A", "11-11T10:00 A"), ringUntil("2026-11-11T20:00"))
        assertEquals(seoul("2026-11-12T08:00"), alarm)
    }

    @Test
    fun items_without_reminders_switched_on_never_ring() = runTest {
        fx.newIntake()
        val off = item("off", "08:00")
        assertIs<SaveReminderResult.Saved>(manage.saveRule(off, enabled = false, start = LocalTime(8, 0)))
        replan()
        assertNull(alarm)
    }

    @Test
    fun rr47_rr21_a_widget_record_stops_the_remaining_reminders() = runTest {
        val a = item("A", "08:00")
        item("B", "09:00")
        replan()
        assertEquals(listOf("11-11T08:00 A"), ringUntil("2026-11-11T08:40"))
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11"))
        replan()
        // B's alarm still rings at 09:00 and 10:00; A is checked at that moment and is not shown (N-6).
        assertEquals(listOf("11-11T09:00 B", "11-11T10:00 B", "11-11T11:00 B"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun rr48_rr22_cancelling_rings_the_next_slot_not_at_once() = runTest {
        val a = item("A", "08:00")
        replan()
        assertEquals(listOf("11-11T08:00 A"), ringUntil("2026-11-11T08:40"))
        val record = assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11")).record
        replan()
        fx.at("2026-11-11T09:30")
        assertEquals(VoidResult.Voided, fx.voidDose(record.id))
        replan()
        assertEquals(seoul("2026-11-11T10:00"), alarm, "not at 09:30 (N-12)")
        assertEquals(listOf("11-11T10:00 A"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun rr49_rr50_quiet_hours_and_the_save_notices() = runTest {
        val bedtime = fx.newIntake().also { names[it] = "bedtime" }
        val late = fx.newIntake().also { names[it] = "late" }
        assertEquals(
            SaveReminderResult.Saved(QuietNotice.Reduced(2)),
            manage.saveRule(bedtime, enabled = true, start = LocalTime(21, 0)),
        )
        assertEquals(
            SaveReminderResult.Saved(QuietNotice.FirstOnly),
            manage.saveRule(late, enabled = true, start = LocalTime(22, 30)),
        )
        replan()
        assertEquals(listOf("11-11T21:00 bedtime", "11-11T22:30 late"), ringUntil("2026-11-12T04:30"))
    }

    @Test
    fun rr51_snooze_rings_thirty_minutes_later_and_the_grid_goes_on() = runTest {
        val a = item("A", "08:00")
        replan()
        assertEquals(listOf("11-11T08:00 A"), ringUntil("2026-11-11T08:00"))
        assertEquals(SnoozeResult.Snoozed(seoul("2026-11-11T08:30")), manage.snooze(a, "2026-11-11"))
        replan()
        assertEquals(seoul("2026-11-11T08:30"), alarm)
        assertEquals(
            listOf("11-11T08:30 A (snoozed)", "11-11T09:00 A", "11-11T10:00 A"),
            ringUntil("2026-11-11T20:00"),
            "the snooze is not counted in the three a day",
        )
    }

    @Test
    fun rr52_only_the_last_snooze_is_kept() = runTest {
        val a = item("A", "08:00")
        replan()
        ringUntil("2026-11-11T08:00")
        manage.snooze(a, "2026-11-11")
        replan()
        fx.at("2026-11-11T08:20")
        assertEquals(SnoozeResult.Snoozed(seoul("2026-11-11T08:50")), manage.snooze(a, "2026-11-11"))
        replan()
        assertEquals(listOf("11-11T08:50 A (snoozed)", "11-11T09:00 A", "11-11T10:00 A"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun a_snooze_holds_back_grid_slots_before_it() = runTest {
        val a = item("A", "08:00")
        replan()
        ringUntil("2026-11-11T08:45")
        manage.snooze(a, "2026-11-11") // 09:15
        replan()
        assertEquals(listOf("11-11T09:15 A (snoozed)", "11-11T10:00 A"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun another_items_alarm_does_not_repeat_what_was_already_shown() = runTest {
        val a = item("A", "08:00")
        item("B", "08:30")
        replan()
        assertEquals(listOf("11-11T08:00 A"), ringUntil("2026-11-11T08:00"))
        manage.snooze(a, "2026-11-11") // 08:30
        replan()
        assertEquals(
            listOf(
                "11-11T08:30 A (snoozed)", "11-11T08:30 B", "11-11T09:00 A", "11-11T09:30 B", "11-11T10:00 A", "11-11T10:30 B",
            ),
            ringUntil("2026-11-11T20:00"),
        )
    }

    @Test
    fun a_late_alarm_survives_a_replan_before_it_is_collected() = runTest {
        manage.saveSettings(ReminderSettings(maxPerDay = 1))
        item("C", "08:00")
        val a = item("A", "09:00")
        item("B", "09:00")
        replan()
        assertEquals(listOf("11-11T08:00 C"), ringUntil("2026-11-11T08:00"))
        assertEquals(seoul("2026-11-11T09:00"), alarm, "planned by the 08:00 run")
        // Doze holds the 09:00 alarm; A is recorded at 09:02 before it rings, which re-plans (N-9).
        fx.at("2026-11-11T09:02")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11"))
        replan()
        assertEquals(seoul("2026-11-11T09:00"), alarm, "the planned alarm is kept, so it rings at once")
        assertEquals(listOf("11-11T09:00 B"), ringUntil("2026-11-11T20:00"))
        assertEquals(seoul("2026-11-12T08:00"), alarm)
        replan()
        assertEquals(seoul("2026-11-12T08:00"), alarm, "once collected, a re-plan looks ahead again")
    }

    @Test
    fun a_late_snooze_survives_a_replan_before_it_is_collected() = runTest {
        val a = item("A", "10:00")
        val b = item("B", "10:00")
        replan()
        assertEquals(listOf("11-11T10:00 A", "11-11T10:00 B"), ringUntil("2026-11-11T10:10"))
        assertEquals(SnoozeResult.Snoozed(seoul("2026-11-11T10:40")), manage.snooze(a, "2026-11-11"))
        replan()
        fx.at("2026-11-11T10:41") // the 10:40 alarm is late; B is recorded first
        assertIs<RecordResult.Recorded>(fx.record(b, renderedKey = "2026-11-11"))
        replan()
        assertEquals(
            listOf("11-11T10:40 A (snoozed)", "11-11T11:00 A", "11-11T12:00 A"),
            ringUntil("2026-11-11T20:00"),
        )
    }

    @Test
    fun an_alarm_planned_before_the_last_run_is_not_kept() = runTest {
        item("A", "08:00")
        replan()
        ringUntil("2026-11-11T08:00")
        assertEquals(seoul("2026-11-11T09:00"), alarm)
        // As after confirming a restore (R-3): the last run is set to now, after the planned alarm.
        fx.at("2026-11-11T09:30")
        fx.db.settingDao().saveReminderLastRunAt(seoul("2026-11-11T09:30"))
        replan()
        assertEquals(seoul("2026-11-11T10:00"), alarm, "09:00 does not ring at 09:30")
    }

    @Test
    fun a_snooze_of_an_ended_window_is_dropped_not_shown() = runTest {
        manage.saveSettings(ReminderSettings(quietStart = LocalTime(0, 0), quietEnd = LocalTime(0, 0)))
        fx.at("2026-11-11T02:00") // the 11-10 window, which ends at 04:00
        val a = item("A", "02:30")
        replan()
        assertEquals(listOf("11-11T02:30 A"), ringUntil("2026-11-11T03:20"))
        assertEquals(SnoozeResult.Snoozed(seoul("2026-11-11T03:50")), manage.snooze(a, "2026-11-10"))
        replan()
        assertEquals(seoul("2026-11-11T03:50"), alarm)
        // The alarm comes after the window changed: the old window's snooze is not shown in the new one.
        fx.at("2026-11-11T04:10")
        assertEquals(emptyList(), plan.fire().notices)
        assertNull(fx.db.reminderDao().findSnooze(a))
    }

    @Test
    fun rr53_weekly_items_ring_on_the_anchor_day_only() = runTest {
        fx.at("2026-11-09T07:00") // Monday
        item("W", "08:00", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        replan()
        assertEquals(listOf("11-09T08:00 W", "11-09T09:00 W", "11-09T10:00 W"), ringUntil("2026-11-15T23:00"))
        assertEquals(seoul("2026-11-16T08:00"), alarm)
    }

    @Test
    fun rr54_one_alarm_for_five_items_at_the_same_time() = runTest {
        repeat(5) { item("P$it", "08:00") }
        replan()
        assertEquals(seoul("2026-11-11T08:00"), alarm)
        // Doze delivers the alarm late: all five still come together, for their 08:00 slot.
        fx.at("2026-11-11T08:07")
        val run = plan.fire()
        assertEquals(List(5) { "11-11T08:00 P$it" }, run.notices.map { it.label() }.sorted())
        assertEquals(seoul("2026-11-11T09:00"), run.nextAlarmAt)
    }

    @Test
    fun rr55_after_a_reboot_each_item_gets_its_latest_slot_once() = runTest {
        item("A", "08:00")
        item("B", "08:00")
        fx.at("2026-11-11T07:50")
        replan() // reminder_last_run_at becomes 07:50, then the phone is off until 10:20
        fx.at("2026-11-11T10:20")
        val run = plan.fire() // first run after unlocking (N-10)
        assertEquals(listOf("11-11T10:00 A", "11-11T10:00 B"), run.notices.map { it.label() }.sorted())
        assertEquals(seoul("2026-11-12T08:00"), run.nextAlarmAt, "no slot left today")
    }

    @Test
    fun rr56_changing_the_start_time_replans_from_the_new_time() = runTest {
        val a = item("A", "09:00")
        replan()
        assertEquals(listOf("11-11T09:00 A"), ringUntil("2026-11-11T09:30"))
        manage.saveRule(a, enabled = true, start = LocalTime(11, 0))
        replan()
        assertEquals(listOf("11-11T11:00 A", "11-11T12:00 A", "11-11T13:00 A"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun rr57_archiving_the_person_drops_the_items_reminders_and_snooze() = runTest {
        val a = item("A", "08:00")
        item("B", "08:30")
        replan()
        ringUntil("2026-11-11T08:10")
        manage.snooze(a, "2026-11-11")
        val person = fx.db.intakeDao().findById(a)!!.personId
        assertEquals(PersonChange.Saved, ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID).archive(person))
        replan()
        assertNull(fx.db.reminderDao().findSnooze(a))
        assertEquals(listOf("11-11T08:30 B", "11-11T09:30 B", "11-11T10:30 B"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun rr79_switching_reminders_on_late_rings_the_next_slot_only() = runTest {
        item("B", "07:30") // an earlier run sets reminder_last_run_at
        replan()
        ringUntil("2026-11-11T09:30")
        val a = fx.newIntake().also { names[it] = "A" }
        manage.saveRule(a, enabled = true, start = LocalTime(8, 0))
        replan()
        assertEquals(seoul("2026-11-11T10:00"), alarm)
        assertEquals(listOf("11-11T10:00 A"), ringUntil("2026-11-11T11:00"))
    }

    @Test
    fun rr79_another_items_alarm_does_not_bring_back_slots_before_switching_on() = runTest {
        item("B", "07:30")
        replan()
        ringUntil("2026-11-11T09:20")
        val a = fx.newIntake().also { names[it] = "A" }
        manage.saveRule(a, enabled = true, start = LocalTime(8, 0))
        replan()
        assertEquals(seoul("2026-11-11T09:30"), alarm)
        // B's 09:30 alarm does not show A's 09:00, which was before A's reminders were switched on.
        assertEquals(listOf("11-11T09:30 B", "11-11T10:00 A"), ringUntil("2026-11-11T11:00"))
    }

    @Test
    fun a_rule_saved_with_the_clock_ahead_does_not_hide_slots() = runTest {
        fx.at("2026-11-11T12:00") // the clock is wrong when the rule is saved
        item("A", "08:00")
        fx.at("2026-11-11T07:00")
        replan()
        assertEquals(listOf("11-11T08:00 A", "11-11T09:00 A", "11-11T10:00 A"), ringUntil("2026-11-11T20:00"))
    }

    @Test
    fun rr80_slots_older_than_one_interval_are_not_shown() = runTest {
        val a = item("A", "08:00")
        item("B", "16:00")
        replan()
        val record = assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11")).record
        replan()
        assertEquals(seoul("2026-11-11T16:00"), alarm)
        fx.at("2026-11-11T15:00")
        fx.voidDose(record.id)
        replan()
        assertEquals(listOf("11-11T16:00 B"), ringUntil("2026-11-11T16:30"))
    }

    @Test
    fun rr81_a_clock_set_back_rewrites_the_last_run_and_rings_again() = runTest {
        item("A", "08:00")
        replan()
        ringUntil("2026-11-11T10:00")
        fx.at("2026-11-11T08:30")
        replan()
        assertEquals((seoul("2026-11-11T08:30")).toEpochMilliseconds().toString(), fx.db.settingDao().get(SettingKeys.REMINDER_LAST_RUN_AT))
        assertEquals(seoul("2026-11-11T09:00"), alarm)
        assertEquals(listOf("11-11T09:00 A", "11-11T10:00 A"), ringUntil("2026-11-11T12:00"))
    }

    @Test
    fun rr84_a_snooze_rings_in_quiet_hours() = runTest {
        val late = item("late", "22:30")
        replan()
        assertEquals(listOf("11-11T22:30 late"), ringUntil("2026-11-11T22:30"))
        assertEquals(SnoozeResult.Snoozed(seoul("2026-11-11T23:00")), manage.snooze(late, "2026-11-11"))
        replan()
        assertEquals(listOf("11-11T23:00 late (snoozed)"), ringUntil("2026-11-12T03:00"))
    }

    @Test
    fun rr85_no_snooze_into_the_next_window() = runTest {
        assertIs<SaveReminderSettingsResult.Saved>(
            manage.saveSettings(ReminderSettings(quietStart = LocalTime(0, 0), quietEnd = LocalTime(0, 0))),
        )
        fx.at("2026-11-11T02:00") // still the 11-10 window, which ends at 04:00
        val a = item("A", "02:30")
        replan()
        fx.at("2026-11-11T02:30")
        val first = plan.fire()
        assertTrue(first.notices.single().canSnooze)
        fx.at("2026-11-11T03:30")
        val last = plan.fire().notices.single()
        assertEquals("11-11T03:30 A", last.label())
        assertFalse(last.canSnooze, "03:30 + 30 minutes is the window end")
        fx.at("2026-11-11T03:45")
        assertEquals(SnoozeResult.TooLate, manage.snooze(a, "2026-11-10"))
        assertNull(fx.db.reminderDao().findSnooze(a))
    }

    @Test
    fun a_snooze_goes_away_when_the_window_is_recorded_or_reminders_are_switched_off() = runTest {
        val a = item("A", "08:00")
        val b = item("B", "08:00")
        replan()
        ringUntil("2026-11-11T08:00")
        manage.snooze(a, "2026-11-11")
        manage.snooze(b, "2026-11-11")
        fx.at("2026-11-11T08:10")
        fx.record(a, renderedKey = "2026-11-11")
        manage.saveRule(b, enabled = false, start = LocalTime(8, 0))
        assertNull(fx.db.reminderDao().findSnooze(b), "switching off drops it at once")
        assertEquals(SnoozeResult.NotNeeded, manage.snooze(b, "2026-11-11"), "no snooze while reminders are off")
        replan()
        assertNull(fx.db.reminderDao().findSnooze(a), "a judged window drops it on the next plan")
        assertEquals(seoul("2026-11-12T08:00"), alarm)
        assertEquals(SnoozeResult.NotNeeded, manage.snooze(a, "2026-11-11"))
    }

    @Test
    fun a_snooze_from_an_old_notification_or_an_archived_item_writes_nothing() = runTest {
        val a = item("A", "08:00")
        assertEquals(SnoozeResult.Stale, manage.snooze(a, "2026-11-10"))
        assertTrue(ArchiveIntake(fx.db, fx.clock)(a))
        assertEquals(SnoozeResult.Gone, manage.snooze(a, "2026-11-11"))
        assertNull(fx.db.reminderDao().findSnooze(a))
        assertEquals(SaveReminderResult.Gone, manage.saveRule(a, enabled = true, start = LocalTime(9, 0)))
    }

    @Test
    fun rules_read_with_defaults_and_save_with_metadata() = runTest {
        val a = fx.newIntake()
        assertEquals(ReminderRule(a, enabled = false, start = LocalTime(8, 0)), manage.rule(a))
        assertNull(fx.db.reminderDao().findRule(a), "reading writes nothing (N-3)")
        assertEquals(SaveReminderResult.Invalid, manage.saveRule(a, enabled = true, start = LocalTime(8, 0, 30)))

        manage.saveRule(a, enabled = true, start = LocalTime(7, 30))
        fx.at("2026-11-11T07:10")
        manage.saveRule(a, enabled = true, start = LocalTime(7, 45))
        val row = assertNotNull(fx.db.reminderDao().findRule(a))
        assertEquals("07:45", row.startTime)
        assertEquals(seoul("2026-11-11T07:00").toEpochMilliseconds(), row.createdAt)
        assertEquals(seoul("2026-11-11T07:10").toEpochMilliseconds(), row.updatedAt)
        assertEquals(2, row.version)
        assertEquals(TEST_DEVICE_ID, row.deviceId)

        fx.at("2026-11-11T07:20")
        manage.saveRule(a, enabled = true, start = LocalTime(7, 45))
        assertEquals(row, fx.db.reminderDao().findRule(a), "saving the same values writes nothing")
    }

    @Test
    fun common_settings_are_validated_and_report_quiet_notices_per_item() = runTest {
        val bedtime = item("bedtime", "21:00")
        item("morning", "08:00")
        assertEquals(SaveReminderSettingsResult.Invalid, manage.saveSettings(ReminderSettings(interval = 45.minutes)))
        assertEquals(ReminderSettings(), manage.settings())

        val result = assertIs<SaveReminderSettingsResult.Saved>(manage.saveSettings(ReminderSettings(interval = 30.minutes, maxPerDay = 5)))
        // 21:00, 21:30 ring; 22:00, 22:30, 23:00 are quiet.
        assertEquals(mapOf(bedtime to QuietNotice.Reduced(3)), result.notices)
        assertEquals(ReminderSettings(interval = 30.minutes, maxPerDay = 5), manage.settings())
    }
}
