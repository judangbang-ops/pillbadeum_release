package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * TASK-025: which reminders on screen go (record-rules-supplement.md N-13, RR-57, RR-86, RR-87, SPEC-014), and
 * whether any item has reminders on for the app's guidance (SPEC-015, SPEC-029). The phone removes what
 * [PlanReminders.review] returns; that part is checked on an emulator.
 *
 * Calendar: 2026-11-11 Wed (Asia/Seoul, day start 04:00).
 */
class ShownRemindersTest {
    private val fx = DbFixture(start = seoul("2026-11-11T07:00"))
    private val plan = PlanReminders(fx.db, fx.clock)
    private val manage = ManageReminders(fx.db, fx.clock, TEST_DEVICE_ID)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun item(start: String = "08:00", rule: ScheduleRule = ScheduleRule.Daily): String {
        val id = fx.newIntake(rule)
        assertIs<SaveReminderResult.Saved>(manage.saveRule(id, enabled = true, start = LocalTime.parse(start)))
        plan.nextAlarm() // the re-plan after the change (N-9) starts reminder_last_run_at
        return id
    }

    /** What the alarm at 08:00 put on screen. */
    private suspend fun shownAt0800(): List<ShownReminder> {
        fx.at("2026-11-11T08:00")
        return plan.fire().notices.map { ShownReminder(it.intakeId, it.window.periodKey) }
    }

    private suspend fun cleared(shown: List<ShownReminder>): List<String> = plan.review(shown).clear.map { it.intakeId }

    @Test
    fun a_reminder_waiting_for_its_window_stays() = runTest {
        val a = item()
        val shown = shownAt0800()
        assertEquals(listOf(ShownReminder(a, "2026-11-11")), shown)

        fx.at("2026-11-11T09:30")
        val review = plan.review(shown)

        assertEquals(emptyList(), review.clear)
        assertFalse(review.restorePending)
    }

    @Test
    fun rr86_a_judgment_from_anywhere_takes_the_reminder_off() = runTest {
        val a = item()
        val b = item()
        val shown = shownAt0800()
        fx.at("2026-11-11T08:20")
        assertIs<RecordResult.Recorded>(fx.record(a, renderedKey = "2026-11-11"))

        assertEquals(listOf(a), cleared(shown))
        assertEquals(listOf(b), shown.map { it.intakeId } - cleared(shown).toSet())
    }

    @Test
    fun rr87_the_window_end_takes_the_reminder_off() = runTest {
        item()
        val shown = shownAt0800()

        fx.at("2026-11-12T03:59")
        assertEquals(emptyList(), cleared(shown))
        fx.at("2026-11-12T04:00")
        assertEquals(shown.map { it.intakeId }, cleared(shown))
    }

    @Test
    fun rr57_archiving_the_item_or_its_person_takes_the_reminder_off() = runTest {
        val a = item()
        val b = item()
        val shown = shownAt0800()

        assertTrue(ArchiveIntake(fx.db, fx.clock)(a))
        assertEquals(listOf(a), cleared(shown))

        val person = checkNotNull(fx.db.intakeDao().findById(b)).personId
        assertEquals(PersonChange.Saved, people.archive(person))
        assertEquals(setOf(a, b), cleared(shown).toSet())
    }

    @Test
    fun switching_the_items_reminders_off_takes_its_reminder_off() = runTest {
        val a = item()
        val shown = shownAt0800()

        assertIs<SaveReminderResult.Saved>(manage.saveRule(a, enabled = false, start = LocalTime(8, 0)))

        assertEquals(listOf(a), cleared(shown))
    }

    @Test
    fun a_deleted_item_and_an_unknown_id_go_too() = runTest {
        val a = item()
        val shown = shownAt0800()
        assertTrue(ArchiveIntake(fx.db, fx.clock)(a))
        assertIs<DeleteIntakeResult.Deleted>(DeleteArchivedIntake(fx.db)(a))

        val unknown = ShownReminder("no-such-item", "2026-11-11")
        assertEquals(listOf(a, "no-such-item"), cleared(shown + unknown))
    }

    @Test
    fun a_weekly_reminder_stays_through_its_window_days() = runTest {
        // Weekly (Wed) item: reminders ring on the anchor day only (N-5), but a reminder left on screen stays
        // until the judgment or the window end, a week later.
        val w = item(rule = ScheduleRule.Weekly(DayOfWeek.WEDNESDAY))
        val shown = shownAt0800()
        assertEquals(listOf(w), shown.map { it.intakeId })

        fx.at("2026-11-17T12:00")
        assertEquals(emptyList(), cleared(shown))
        fx.at("2026-11-18T04:00")
        assertEquals(listOf(w), cleared(shown))
    }

    @Test
    fun any_enabled_counts_only_items_and_people_not_archived() = runTest {
        assertFalse(manage.anyEnabled())
        val off = fx.newIntake()
        assertIs<SaveReminderResult.Saved>(manage.saveRule(off, enabled = false, start = LocalTime(8, 0)))
        assertFalse(manage.anyEnabled())

        val a = item()
        assertTrue(manage.anyEnabled())

        assertTrue(ArchiveIntake(fx.db, fx.clock)(a))
        assertFalse(manage.anyEnabled())
    }
}
