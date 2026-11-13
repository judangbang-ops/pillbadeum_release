package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ManageReminders
import com.judangbang.pillbadeum.usecase.PlanReminders
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.ReminderNotice
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * TASK-025: what a dose reminder says and offers (screens.md SCR-003, REQ-027, REQ-045, REQ-088, N-7, N-11).
 * How Android draws it, on the lock screen too, is checked on an emulator.
 *
 * Calendar: 2026-11-10 Tue, 11-11 Wed (Asia/Seoul, day start 04:00). Reminders are switched on at 11-10 21:00.
 */
class ReminderLabelsTest {
    private val fx = DbFixture(start = seoul("2026-11-10T21:00"))
    private val plan = PlanReminders(fx.db, fx.clock)
    private val manage = ManageReminders(fx.db, fx.clock, TEST_DEVICE_ID)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun item(start: String = "08:00", rule: ScheduleRule = ScheduleRule.Daily): String {
        val id = fx.newIntake(rule)
        assertIs<SaveReminderResult.Saved>(manage.saveRule(id, enabled = true, start = LocalTime.parse(start)))
        plan.nextAlarm() // the re-plan after the change (N-9) starts reminder_last_run_at
        return id
    }

    private suspend fun ringAt(at: String): List<ReminderNotice> {
        fx.at(at)
        return plan.fire().notices
    }

    private suspend fun contents(notices: List<ReminderNotice>, zone: TimeZone = SEOUL): List<ReminderContent?> {
        val now = board()
        return notices.map { ReminderLabels.content(now, it, zone) }
    }

    @Test
    fun title_body_and_buttons_of_a_daily_item() = runTest {
        val a = item()
        fx.at("2026-11-10T21:40")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-10"))

        val content = assertNotNull(contents(ringAt("2026-11-11T08:00")).single())

        assertEquals(a, content.intakeId)
        assertEquals("2026-11-11", content.periodKey)
        assertEquals("Morning dose", content.name)
        assertEquals("Morning dose 먹어~!", content.title)
        assertEquals("마지막 복용 어제\u00a021:40", content.text, "the widget's line (W-4)")
        assertEquals(ReminderLabels.SKIP_TODAY, content.skipLabel)
        assertTrue(content.canSnooze)
        assertEquals(seoul("2026-11-12T04:00"), content.windowEnd)
        assertNull(content.person, "one person: no name in the header")
    }

    /** W-2, W-4: a weekly reminder starts with the last dose and its weekday, as the widget button does. */
    @Test
    fun w4_a_weekly_reminder_starts_with_the_last_dose_and_its_weekday() = runTest {
        val a = item(rule = ScheduleRule.Weekly(DayOfWeek.WEDNESDAY))
        fx.at("2026-11-10T21:40")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = null, source = DoseSource.APP))

        val content = assertNotNull(contents(ringAt("2026-11-11T08:00")).single())

        assertEquals("마지막 복용 어제(화)\u00a021:40", content.text)
    }

    /** S-2, W-2: a skip later than the last dose is a second line, seen when the reminder is unfolded. */
    @Test
    fun a_later_skip_is_the_second_line_of_the_body() = runTest {
        val a = item()
        fx.at("2026-11-10T21:40")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = null, source = DoseSource.APP, kind = DoseKind.SKIPPED))

        val content = assertNotNull(contents(ringAt("2026-11-11T08:00")).single())

        assertEquals("${Labels.NO_RECORD}\n어제 건너뜀", content.text, "a skip is never the last dose")
    }

    /** REQ-082, RR-58: the body's times follow the phone's zone, while the window stays the base zone's. */
    @Test
    fun rr58_the_body_follows_the_phones_zone() = runTest {
        val a = item()
        fx.at("2026-11-10T21:40") // 12:40 in London
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-10"))

        val notices = ringAt("2026-11-11T08:00") // 11/10 23:00 in London
        val content = assertNotNull(contents(notices, TimeZone.of("Europe/London")).single())

        assertEquals("마지막 복용 오늘\u00a012:40", content.text, "still 11/10 in London")
        assertEquals("2026-11-11", content.periodKey, "Seoul's window")
    }

    @Test
    fun rr027_the_widget_alias_is_the_name() = runTest {
        val a = item()
        val intake = checkNotNull(fx.db.intakeDao().findById(a))
        fx.db.intakeDao().updateDetails(a, intake.personId, intake.name, "혈압약", intake.slotLabel, 0)

        val content = assertNotNull(contents(ringAt("2026-11-11T08:00")).single())

        assertEquals("혈압약", content.name)
        assertEquals("혈압약 먹어~!", content.title)
    }

    @Test
    fun the_person_is_named_only_when_items_belong_to_more_than_one_person() = runTest {
        item()
        item()

        val shown = contents(ringAt("2026-11-11T08:00"))

        assertEquals(listOf("Test person", "Test person"), shown.map { it?.person })
    }

    @Test
    fun a_weekly_item_offers_this_weeks_skip() = runTest {
        item(rule = ScheduleRule.Weekly(DayOfWeek.WEDNESDAY))

        val content = assertNotNull(contents(ringAt("2026-11-11T08:00")).single())

        assertEquals(ReminderLabels.SKIP_THIS_WEEK, content.skipLabel)
        assertEquals("이번 주 건너뜀", content.skipLabel)
        assertEquals(seoul("2026-11-18T04:00"), content.windowEnd)
    }

    @Test
    fun rr85_no_snooze_in_the_last_half_hour_of_the_window() = runTest {
        item(start = "03:30")

        val content = assertNotNull(contents(ringAt("2026-11-12T03:30")).single())

        assertFalse(content.canSnooze)
    }

    @Test
    fun nothing_to_show_when_the_item_was_judged_meanwhile() = runTest {
        val a = item()
        val notices = ringAt("2026-11-11T08:00")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11"))

        assertEquals(listOf(null), contents(notices))
    }

    @Test
    fun scr003_already_names_the_recorded_time() = runTest {
        val a = item()
        fx.at("2026-11-11T08:02")
        val record = assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11")).record

        assertEquals("이미 기록됨 (08:02)", ReminderLabels.already(record, seoul("2026-11-11T09:00"), SEOUL))
    }

    @Test
    fun n11_no_copy_promises_a_delay_in_minutes() {
        val copy = listOf(
            ReminderLabels.TAKEN, ReminderLabels.SNOOZE, ReminderLabels.SKIP_TODAY, ReminderLabels.SKIP_THIS_WEEK,
            ReminderLabels.LOCK_TITLE, ReminderLabels.RESTORED_TITLE, ReminderLabels.RESTORED_TEXT,
            ReminderLabels.STALE, ReminderLabels.ERROR,
        )
        for (line in copy) {
            for (promise in listOf("분 안", "분 이내", "분 내", "정확히", "바로 알려")) {
                assertFalse(line.contains(promise), "\"$line\" promises a delay")
            }
        }
        assertEquals("복용 시간이에요", ReminderLabels.LOCK_TITLE)
        assertEquals("백업에서 복원했어요. 앱에서 확인해 주세요", "${ReminderLabels.RESTORED_TITLE}. ${ReminderLabels.RESTORED_TEXT}")
    }
}
