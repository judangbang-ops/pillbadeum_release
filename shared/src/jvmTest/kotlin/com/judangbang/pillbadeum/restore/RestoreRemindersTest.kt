package com.judangbang.pillbadeum.restore

import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import com.judangbang.pillbadeum.usecase.SaveReminderSettingsResult
import com.judangbang.pillbadeum.usecase.ShownReminder
import com.judangbang.pillbadeum.usecase.SnoozeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime

/**
 * TASK-040: dose reminders around a restore (record-rules-supplement.md R-3, N-12, RR-30, RR-33, RR-73). Until
 * [확인했어요] no dose reminder is planned or shown, whatever run state the old phone left in the backup; after it
 * reminders start from the moment of the tap. What the phone shows (the one restore notice) is checked on an
 * emulator (TASK-040 acceptance).
 *
 * Calendar: 2026-11-11 Wed (Asia/Seoul, day start 04:00). Reminders every 60 minutes, at most 5 a day.
 */
class RestoreRemindersTest {
    private val fx = DbFixture(start = seoul("2026-11-11T07:00"))
    private val marker = FakeInstallMarker()

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun open(): DidITakeCore = DidITakeCore.open(fx.db, marker, fx.clock) { SEOUL }

    private suspend fun setting(key: String): String? = fx.db.settingDao().get(key)

    /**
     * The old phone: A (08:00) not taken today, B (08:00) taken at 08:10 today, both with reminders on. The old phone
     * ran its reminders at 08:00, snoozed A to 08:30 and planned the next alarm at 08:30 when the backup was made.
     */
    private suspend fun oldPhone(): Pair<String, String> {
        val core = open()
        assertIs<SaveReminderSettingsResult.Saved>(core.reminders.saveSettings(ReminderSettings(maxPerDay = 5)))
        val a = fx.newIntake()
        val b = fx.newIntake()
        for (id in listOf(a, b)) assertIs<SaveReminderResult.Saved>(core.reminders.saveRule(id, enabled = true, start = LocalTime(8, 0)))
        assertEquals(seoul("2026-11-11T08:00"), core.planReminders.nextAlarm())
        fx.at("2026-11-11T08:00")
        assertEquals(setOf(a, b), core.planReminders.fire().notices.map { it.intakeId }.toSet())
        assertIs<SnoozeResult.Snoozed>(core.reminders.snooze(a, "2026-11-11"))
        fx.at("2026-11-11T08:10")
        assertIs<RecordResult.Recorded>(fx.record(b, renderedKey = "2026-11-11"))
        assertEquals(seoul("2026-11-11T08:30"), core.planReminders.nextAlarm())
        return a to b
    }

    /** The backup is restored on a new phone: no marker there (RR-71 shows the marker is wiped too). */
    private suspend fun restoreOnNewPhone(at: String): DidITakeCore {
        marker.value = null
        fx.at(at)
        return open().also { assertIs<InstallState.Restored>(it.installState) }
    }

    @Test
    fun rr30_restore_detection_drops_the_old_phones_alarm_and_snoozes() = runTest {
        val (a, _) = oldPhone()
        assertTrue(setting(SettingKeys.REMINDER_ALARM_AT) != null)

        restoreOnNewPhone("2026-11-11T09:30")

        assertNull(setting(SettingKeys.REMINDER_ALARM_AT), "the old phone's planned alarm")
        assertNull(fx.db.reminderDao().findSnooze(a), "the old phone's [30분 뒤]")
    }

    @Test
    fun rr30_no_dose_reminder_until_the_notice_is_acknowledged() = runTest {
        val (a, b) = oldPhone()
        val core = restoreOnNewPhone("2026-11-11T09:30")

        assertNull(core.planReminders.nextAlarm(), "no alarm while the restore notice is pending")
        assertEquals(emptyList(), core.planReminders.fire().notices)
        assertNull(setting(SettingKeys.REMINDER_ALARM_AT))
        // Hours later, still pending: A's 10:00, 11:00 and 12:00 slots do not show either.
        fx.at("2026-11-11T12:10")
        assertEquals(emptyList(), core.planReminders.fire().notices)
        assertNull(core.planReminders.nextAlarm())

        // Any reminder still on screen goes, and the caller learns that the notice is pending.
        val shown = listOf(ShownReminder(a, "2026-11-11"), ShownReminder(b, "2026-11-11"))
        val review = core.planReminders.review(shown)
        assertTrue(review.restorePending)
        assertEquals(shown, review.clear)
    }

    @Test
    fun rr73_rr33_acknowledging_starts_reminders_from_the_tap() = runTest {
        val (a, _) = oldPhone()
        // The backup's reminder_last_run_at is the old phone's 08:00; 09:00 and 10:00 pass before the tap.
        val core = restoreOnNewPhone("2026-11-11T09:30")
        fx.at("2026-11-11T10:30")

        core.acknowledgeRestore()

        assertEquals(CheckInstall.DONE, setting(SettingKeys.RESTORE_NOTICE_PENDING))
        assertEquals(seoul("2026-11-11T10:30").toEpochMilliseconds().toString(), setting(SettingKeys.REMINDER_LAST_RUN_AT))
        // Not at once (no 08:30 snooze, no 10:00 slot): A's next slot. B was taken today on the old phone.
        assertEquals(seoul("2026-11-11T11:00"), core.planReminders.nextAlarm())
        fx.at("2026-11-11T11:00")
        val run = core.planReminders.fire()
        assertEquals(listOf(a to seoul("2026-11-11T11:00")), run.notices.map { it.intakeId to it.dueAt })
        assertEquals(seoul("2026-11-11T12:00"), run.nextAlarmAt)
        assertEquals(false, core.planReminders.review(emptyList()).restorePending)
    }

    @Test
    fun acknowledging_twice_changes_nothing_the_second_time() = runTest {
        oldPhone()
        val core = restoreOnNewPhone("2026-11-11T09:30")
        fx.at("2026-11-11T10:30")
        core.acknowledgeRestore()
        fx.at("2026-11-11T11:30")
        core.planReminders.fire()
        val lastRun = setting(SettingKeys.REMINDER_LAST_RUN_AT)

        fx.clock.now += 5.minutes
        core.acknowledgeRestore()

        assertEquals(lastRun, setting(SettingKeys.REMINDER_LAST_RUN_AT))
    }

    @Test
    fun a_normal_start_keeps_the_reminder_state() = runTest {
        oldPhone()
        val alarm = setting(SettingKeys.REMINDER_ALARM_AT)

        val again = open()

        assertIs<InstallState.Normal>(again.installState)
        assertEquals(alarm, setting(SettingKeys.REMINDER_ALARM_AT))
        assertEquals(seoul("2026-11-11T08:30"), again.planReminders.nextAlarm())
    }
}
